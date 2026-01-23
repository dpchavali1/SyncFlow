package com.phoneintegration.app.desktop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import com.phoneintegration.app.usage.UsageCategory
import com.phoneintegration.app.usage.UsageCheck
import com.phoneintegration.app.usage.UsageTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream

/**
 * Service that handles file transfers between macOS/desktop and Android.
 * Files are uploaded to Firebase Storage and synced via Realtime Database.
 */
class FileTransferService(context: Context) {
    private val context: Context = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val usageTracker = UsageTracker(database)

    private var fileListenerHandle: ChildEventListener? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "FileTransferService"
        private const val FILE_TRANSFERS_PATH = "file_transfers"
        private const val USERS_PATH = "users"
        private const val CHANNEL_ID = "file_transfer_channel"
        private const val NOTIFICATION_ID = 9001
        private const val MAX_FILE_SIZE = 50 * 1024 * 1024L // 50MB max
    }

    init {
        createNotificationChannel()
    }

    /**
     * Data class representing a file transfer
     */
    data class FileTransfer(
        val id: String,
        val fileName: String,
        val fileSize: Long,
        val contentType: String,
        val downloadUrl: String,
        val source: String,
        val timestamp: Long,
        val status: String
    )

    /**
     * Start listening for file transfers
     */
    fun startListening() {
        Log.d(TAG, "Starting file transfer service")
        listenForFileTransfers()
    }

    /**
     * Stop listening for file transfers
     */
    fun stopListening() {
        Log.d(TAG, "Stopping file transfer service")
        removeFileListener()
        scope.cancel()
    }

    /**
     * Listen for incoming file transfers
     */
    private fun listenForFileTransfers() {
        scope.launch {
            try {
                val currentUser = auth.currentUser ?: return@launch
                val userId = currentUser.uid

                val filesRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(FILE_TRANSFERS_PATH)

                fileListenerHandle = object : ChildEventListener {
                    override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                        handleFileTransfer(snapshot)
                    }

                    override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                        // Handle status changes if needed
                    }

                    override fun onChildRemoved(snapshot: DataSnapshot) {}
                    override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "File listener cancelled: ${error.message}")
                    }
                }

                filesRef.addChildEventListener(fileListenerHandle!!)
                Log.d(TAG, "File transfer listener registered")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting file listener", e)
            }
        }
    }

    private fun removeFileListener() {
        scope.launch {
            try {
                val userId = auth.currentUser?.uid ?: return@launch
                fileListenerHandle?.let { listener ->
                    database.reference
                        .child(USERS_PATH)
                        .child(userId)
                        .child(FILE_TRANSFERS_PATH)
                        .removeEventListener(listener)
                }
                fileListenerHandle = null
            } catch (e: Exception) {
                Log.e(TAG, "Error removing file listener", e)
            }
        }
    }

    /**
     * Handle incoming file transfer
     */
    private fun handleFileTransfer(snapshot: DataSnapshot) {
        val fileId = snapshot.key ?: return
        val fileName = snapshot.child("fileName").value as? String ?: return
        val fileSize = (snapshot.child("fileSize").value as? Long) ?: 0
        val contentType = snapshot.child("contentType").value as? String ?: "application/octet-stream"
        val downloadUrl = snapshot.child("downloadUrl").value as? String ?: return
        val source = snapshot.child("source").value as? String ?: "unknown"
        val status = snapshot.child("status").value as? String ?: "pending"
        val timestamp = snapshot.child("timestamp").value as? Long ?: 0

        // Only process files from other devices (not Android)
        if (source == "android") {
            return
        }

        // Only process pending files
        if (status != "pending") {
            return
        }

        // Check if file is recent (within last 5 minutes)
        val now = System.currentTimeMillis()
        if (now - timestamp > 300000) {
            Log.d(TAG, "Ignoring old file transfer: $fileId")
            return
        }

        Log.d(TAG, "Received file transfer: $fileName ($fileSize bytes)")

        // Download the file
        scope.launch {
            downloadFile(fileId, fileName, fileSize, contentType, downloadUrl)
        }
    }

    /**
     * Download file from Firebase Storage
     */
    private suspend fun downloadFile(
        fileId: String,
        fileName: String,
        fileSize: Long,
        contentType: String,
        downloadUrl: String
    ) {
        try {
            // Check file size
            if (fileSize > MAX_FILE_SIZE) {
                Log.w(TAG, "File too large: $fileSize bytes")
                updateTransferStatus(fileId, "failed", "File too large (max 50MB)")
                showToast("File too large to download")
                return
            }

            // Show download notification
            showDownloadNotification(fileName, 0)

            // Update status to downloading
            updateTransferStatus(fileId, "downloading")

            // Get storage reference
            val storageRef = storage.getReferenceFromUrl(downloadUrl)

            // Download to temp file first
            val tempFile = File(context.cacheDir, "download_$fileName")
            storageRef.getFile(tempFile).await()

            Log.d(TAG, "File downloaded to temp: ${tempFile.absolutePath}")

            // Save to Downloads folder
            saveToDownloads(fileName, contentType, tempFile)

            // Update status
            updateTransferStatus(fileId, "downloaded")

            // Show completion notification
            showDownloadCompleteNotification(fileName)

            // Clean up temp file
            tempFile.delete()

            Log.d(TAG, "File saved successfully: $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading file", e)
            updateTransferStatus(fileId, "failed", e.message)
            showToast("Failed to download: $fileName")
        }
    }

    /**
     * Save file to Downloads folder
     */
    private fun saveToDownloads(fileName: String, contentType: String, sourceFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use MediaStore for Android 10+
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, contentType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SyncFlow")
            }

            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            )

            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    sourceFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        } else {
            // Direct file access for older versions
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val syncFlowDir = File(downloadsDir, "SyncFlow")
            if (!syncFlowDir.exists()) {
                syncFlowDir.mkdirs()
            }

            val destFile = File(syncFlowDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)
        }
    }

    /**
     * Upload a file to share with other devices
     */
    suspend fun uploadFile(file: File, fileName: String, contentType: String): Boolean {
        return try {
            val userId = auth.currentUser?.uid ?: return false

            // Check file size
            if (file.length() > MAX_FILE_SIZE) {
                Log.w(TAG, "File too large to upload: ${file.length()} bytes")
                showToast("File too large (max 50MB)")
                return false
            }

            val usageCheck = runCatching {
                usageTracker.isUploadAllowed(
                    userId = userId,
                    bytes = file.length(),
                    countsTowardStorage = true
                )
            }.getOrElse {
                UsageCheck(true)
            }

            if (!usageCheck.allowed) {
                Log.w(TAG, "File upload blocked by usage limits: ${usageCheck.reason}")
                showToast(usageLimitMessage(usageCheck.reason))
                return false
            }

            // Upload to Firebase Storage
            val fileId = System.currentTimeMillis().toString()
            val storagePath = "$USERS_PATH/$userId/transfers/$fileId/$fileName"
            val storageRef = storage.reference.child(storagePath)

            storageRef.putFile(android.net.Uri.fromFile(file)).await()
            val downloadUrl = storageRef.downloadUrl.await().toString()

            // Create transfer record
            val transferRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(FILE_TRANSFERS_PATH)
                .child(fileId)

            val transferData = mapOf(
                "fileName" to fileName,
                "fileSize" to file.length(),
                "contentType" to contentType,
                "downloadUrl" to downloadUrl,
                "source" to "android",
                "status" to "pending",
                "timestamp" to ServerValue.TIMESTAMP
            )

            transferRef.setValue(transferData).await()

            runCatching {
                usageTracker.recordUpload(
                    userId = userId,
                    bytes = file.length(),
                    category = UsageCategory.FILE,
                    countsTowardStorage = true
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to record file transfer usage: ${error.message}")
            }

            Log.d(TAG, "File uploaded: $fileName")
            showToast("File shared: $fileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading file", e)
            showToast("Failed to share file")
            false
        }
    }

    /**
     * Update transfer status
     */
    private suspend fun updateTransferStatus(fileId: String, status: String, error: String? = null) {
        try {
            val userId = auth.currentUser?.uid ?: return

            val updates = mutableMapOf<String, Any>(
                "status" to status
            )
            if (error != null) {
                updates["error"] = error
            }

            database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(FILE_TRANSFERS_PATH)
                .child(fileId)
                .updateChildren(updates)
                .await()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating transfer status", e)
        }
    }

    /**
     * Create notification channel
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "File transfer notifications"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Show download progress notification
     */
    private fun showDownloadNotification(fileName: String, progress: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading file")
            .setContentText(fileName)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Show download complete notification
     */
    private fun showDownloadCompleteNotification(fileName: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText(fileName)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun usageLimitMessage(reason: String?): String {
        return when (reason) {
            UsageTracker.REASON_TRIAL_EXPIRED ->
                "Free trial expired. Upgrade to keep sharing files."
            UsageTracker.REASON_MONTHLY_LIMIT ->
                "Monthly upload limit reached. Try again next month or upgrade."
            UsageTracker.REASON_STORAGE_LIMIT ->
                "Storage limit reached. Free up space or upgrade your plan."
            else ->
                "Upload limit reached. Please try again later."
        }
    }

    /**
     * Show toast message
     */
    private fun showToast(message: String) {
        MainScope().launch {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
