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
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service that handles file transfers between macOS/desktop and Android.
 * Files are uploaded to Cloudflare R2 and synced via Realtime Database.
 *
 * Tiered Limits:
 * - Free: 50MB per file
 * - Pro: 1GB per file
 */
class FileTransferService(context: Context) {
    private val context: Context = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val functions = FirebaseFunctions.getInstance()

    private var fileListenerHandle: ChildEventListener? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processingFileIds = mutableSetOf<String>() // Track files being processed to prevent duplicates

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "FileTransferService"
        private const val FILE_TRANSFERS_PATH = "file_transfers"
        private const val USERS_PATH = "users"
        private const val CHANNEL_ID = "file_transfer_channel"
        private const val NOTIFICATION_ID = 9001

        // Tiered limits (no daily limits - R2 has free egress)
        const val MAX_FILE_SIZE_FREE = 50 * 1024 * 1024L     // 50MB for free users
        const val MAX_FILE_SIZE_PRO = 1024 * 1024 * 1024L    // 1GB for pro users

        // Legacy constant for backward compatibility
        private const val MAX_FILE_SIZE = 50 * 1024 * 1024L
    }

    /**
     * Data class for transfer limits
     */
    data class TransferLimits(
        val maxFileSize: Long,
        val isPro: Boolean
    )

    /**
     * Check if user has pro subscription
     */
    private suspend fun isPro(): Boolean {
        return try {
            val userId = auth.currentUser?.uid ?: return false
            // Check user's subscription directly
            val snapshot = database.reference
                .child("users")
                .child(userId)
                .child("subscription")
                .child("plan")
                .get()
                .await()

            val plan = snapshot.getValue(String::class.java)
            plan != null && plan != "free"
        } catch (e: Exception) {
            Log.w(TAG, "Error checking pro status: ${e.message}")
            // Default to allowing transfers if check fails (connectivity issues)
            true
        }
    }

    /**
     * Get current transfer limits based on subscription
     */
    suspend fun getTransferLimits(): TransferLimits {
        val isPro = isPro()
        val maxFileSize = if (isPro) MAX_FILE_SIZE_PRO else MAX_FILE_SIZE_FREE

        return TransferLimits(
            maxFileSize = maxFileSize,
            isPro = isPro
        )
    }

    /**
     * Check if transfer is allowed
     */
    data class TransferCheck(
        val allowed: Boolean,
        val reason: String? = null
    )

    suspend fun canTransfer(fileSize: Long): TransferCheck {
        val limits = getTransferLimits()

        return when {
            fileSize > limits.maxFileSize -> {
                val maxMB = limits.maxFileSize / (1024 * 1024)
                TransferCheck(false, "File too large. Max size: ${maxMB}MB" +
                    if (!limits.isPro) " (Upgrade to Pro for 1GB)" else "")
            }
            else -> TransferCheck(true)
        }
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
        database.goOnline()
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

        // Prevent duplicate processing of the same file
        synchronized(processingFileIds) {
            if (processingFileIds.contains(fileId)) {
                Log.d(TAG, "Skipping duplicate file transfer: $fileId")
                return
            }
        }

        val fileName = snapshot.child("fileName").value as? String ?: return
        val fileSize = (snapshot.child("fileSize").value as? Long) ?: 0
        val contentType = snapshot.child("contentType").value as? String ?: "application/octet-stream"
        val source = snapshot.child("source").value as? String ?: "unknown"
        val status = snapshot.child("status").value as? String ?: "pending"
        val timestamp = snapshot.child("timestamp").value as? Long ?: 0

        // Support both R2 (r2Key) and legacy Firebase Storage (downloadUrl)
        val r2Key = snapshot.child("r2Key").value as? String
        val legacyDownloadUrl = snapshot.child("downloadUrl").value as? String

        if (r2Key == null && legacyDownloadUrl == null) {
            return
        }

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

        // Mark as processing before starting download
        synchronized(processingFileIds) {
            processingFileIds.add(fileId)
        }

        Log.d(TAG, "Received file transfer: $fileName ($fileSize bytes)")

        // Download the file
        scope.launch {
            try {
                downloadFile(fileId, fileName, fileSize, contentType, r2Key, legacyDownloadUrl)
            } finally {
                // Remove from processing set after completion (success or failure)
                synchronized(processingFileIds) {
                    processingFileIds.remove(fileId)
                }
            }
        }
    }

    /**
     * Download file from R2 or legacy Firebase Storage
     */
    private suspend fun downloadFile(
        fileId: String,
        fileName: String,
        fileSize: Long,
        contentType: String,
        r2Key: String?,
        legacyDownloadUrl: String?
    ) {
        try {
            val userId = auth.currentUser?.uid ?: return

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

            // Get download URL - either from R2 or legacy Firebase Storage
            val downloadUrl: String = if (r2Key != null) {
                // Get presigned download URL from R2
                val result = functions
                    .getHttpsCallable("getR2DownloadUrl")
                    .call(mapOf(
                        "syncGroupUserId" to userId,
                        "fileKey" to r2Key
                    ))
                    .await()

                @Suppress("UNCHECKED_CAST")
                val data = result.data as? Map<String, Any>
                data?.get("downloadUrl") as? String
                    ?: throw Exception("Failed to get download URL from R2")
            } else {
                legacyDownloadUrl ?: throw Exception("No download URL available")
            }

            // Download to temp file first
            val tempFile = File(context.cacheDir, "download_$fileName")

            withContext(Dispatchers.IO) {
                val connection = URL(downloadUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 30000
                connection.readTimeout = 60000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    throw Exception("Download failed with status: ${connection.responseCode}")
                }
            }

            Log.d(TAG, "File downloaded to temp: ${tempFile.absolutePath}")

            // Save to Downloads folder
            saveToDownloads(fileName, contentType, tempFile)

            // Update status
            updateTransferStatus(fileId, "downloaded")

            // Show completion notification immediately
            showDownloadCompleteNotification(fileName)

            // Clean up temp file
            tempFile.delete()

            Log.d(TAG, "File saved successfully: $fileName")

            // Delete file from R2 after successful download (in background)
            if (r2Key != null) {
                try {
                    functions
                        .getHttpsCallable("deleteR2File")
                        .call(mapOf(
                            "syncGroupUserId" to userId,
                            "fileKey" to r2Key
                        ))
                        .await()
                    Log.d(TAG, "Cleaned up file from R2: $fileName")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clean up file from R2 (non-fatal): ${e.message}")
                }
            }

            // Clean up the transfer record from Database after a delay
            // This gives the sender time to see the "downloaded" status
            try {
                delay(5000) // Wait 5 seconds
                database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(FILE_TRANSFERS_PATH)
                    .child(fileId)
                    .removeValue()
                    .await()
                Log.d(TAG, "Cleaned up transfer record from Database: $fileId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up transfer record (non-fatal): ${e.message}")
            }
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
     * Upload a file to share with other devices via R2
     * Respects tiered limits (Free: 50MB/file | Pro: 1GB/file)
     */
    suspend fun uploadFile(file: File, fileName: String, contentType: String): Boolean {
        return try {
            val userId = auth.currentUser?.uid ?: return false
            val fileSize = file.length()

            // Check tiered limits (file size)
            val transferCheck = canTransfer(fileSize)
            if (!transferCheck.allowed) {
                Log.w(TAG, "File transfer blocked: ${transferCheck.reason}")
                showToast(transferCheck.reason ?: "Transfer not allowed")
                return false
            }

            // Step 1: Get presigned upload URL from R2
            val urlResult = functions
                .getHttpsCallable("getR2UploadUrl")
                .call(mapOf(
                    "syncGroupUserId" to userId,
                    "fileName" to fileName,
                    "contentType" to contentType,
                    "fileSize" to fileSize,
                    "transferType" to "files"
                ))
                .await()

            @Suppress("UNCHECKED_CAST")
            val urlData = urlResult.data as? Map<String, Any>
            val uploadUrl = urlData?.get("uploadUrl") as? String
                ?: throw Exception("Failed to get upload URL")
            val r2Key = urlData["fileKey"] as? String
                ?: throw Exception("Failed to get file key")

            Log.d(TAG, "Got R2 upload URL for: $fileName")

            // Step 2: Upload file directly to R2 using presigned URL
            withContext(Dispatchers.IO) {
                val connection = URL(uploadUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "PUT"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setRequestProperty("Content-Length", fileSize.toString())
                connection.connectTimeout = 30000
                connection.readTimeout = 120000

                file.inputStream().use { input ->
                    connection.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Upload to R2 failed with status: ${connection.responseCode}")
                }
            }

            Log.d(TAG, "File uploaded to R2: $fileName")

            // Step 3: Confirm upload to record usage
            functions
                .getHttpsCallable("confirmR2Upload")
                .call(mapOf(
                    "syncGroupUserId" to userId,
                    "fileKey" to r2Key,
                    "fileSize" to fileSize,
                    "transferType" to "files"
                ))
                .await()

            // Step 4: Create transfer record in database
            val fileId = System.currentTimeMillis().toString()
            val transferRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(FILE_TRANSFERS_PATH)
                .child(fileId)

            val transferData = mapOf(
                "fileName" to fileName,
                "fileSize" to fileSize,
                "contentType" to contentType,
                "r2Key" to r2Key,
                "source" to "android",
                "status" to "pending",
                "timestamp" to ServerValue.TIMESTAMP
            )

            transferRef.setValue(transferData).await()

            Log.d(TAG, "File uploaded: $fileName (${fileSize / 1024}KB)")
            showToast("File shared: $fileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading file", e)
            val message = e.message ?: "Failed to share file"
            showToast(if (message.contains("resource-exhausted", ignoreCase = true) ||
                         message.contains("limit", ignoreCase = true)) {
                message
            } else {
                "Failed to share file"
            })
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
                NotificationManager.IMPORTANCE_DEFAULT
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
            .setContentTitle("File received from Mac")
            .setContentText("$fileName saved to Downloads/SyncFlow")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
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
