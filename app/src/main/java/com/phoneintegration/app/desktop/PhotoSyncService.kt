package com.phoneintegration.app.desktop

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.functions.FirebaseFunctions
import com.phoneintegration.app.auth.UnifiedIdentityManager
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

/**
 * Service that syncs recent photos from Android to Firebase for display on macOS.
 * Photos are uploaded as thumbnails to save bandwidth and storage.
 *
 * NOTE: Photo sync is a PREMIUM FEATURE. Only paid subscribers can sync photos.
 * Free/trial users will not have photo sync functionality.
 */
class PhotoSyncService(context: Context) {
    private val context: Context = context.applicationContext
    private val database = FirebaseDatabase.getInstance()
    private val functions = FirebaseFunctions.getInstance()
    private val unifiedIdentityManager = UnifiedIdentityManager.getInstance(context)

    private var contentObserver: ContentObserver? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track synced photos to avoid duplicates (loaded from Firebase on startup)
    private val syncedPhotoIds = mutableSetOf<Long>()
    private var syncedIdsLoaded = false
    private var lastSyncTimestamp: Long = 0

    companion object {
        private const val TAG = "PhotoSyncService"
        private const val PHOTOS_PATH = "photos"
        private const val USERS_PATH = "users"
        private const val USAGE_PATH = "usage"
        private const val MAX_THUMBNAIL_SIZE = 800 // Max dimension for thumbnails
        private const val THUMBNAIL_QUALITY = 80 // JPEG quality
        private const val MAX_PHOTOS_TO_SYNC = 20 // Max recent photos to keep
        private const val SYNC_DEBOUNCE_MS = 2000L
    }

    /**
     * Check if user has a paid subscription (required for photo sync)
     */
    private suspend fun hasPremiumAccess(): Boolean {
        val userId = unifiedIdentityManager.getUnifiedUserIdSync() ?: return false

        return try {
            val usageRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(USAGE_PATH)

            val snapshot = usageRef.get().await()
            val planRaw = snapshot.child("plan").getValue(String::class.java)?.lowercase()
            val planExpiresAt = snapshot.child("planExpiresAt").getValue(Long::class.java)
            val now = System.currentTimeMillis()

            when (planRaw) {
                "lifetime", "3year" -> true
                "monthly", "yearly", "paid" -> planExpiresAt?.let { it > now } ?: true
                else -> false // Trial users and free users don't have access
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking premium status", e)
            false
        }
    }

    /**
     * Data class for photo metadata
     */
    data class PhotoMetadata(
        val id: String,
        val originalId: Long,
        val fileName: String,
        val dateTaken: Long,
        val thumbnailUrl: String,
        val width: Int,
        val height: Int,
        val size: Long,
        val mimeType: String,
        val syncedAt: Long
    )

    /**
     * Start photo sync - monitors for new photos
     * NOTE: Requires premium subscription. Free/trial users will be blocked.
     */
    fun startSync() {
        Log.d(TAG, "Starting photo sync")
        database.goOnline()

        // Sync recent photos on startup (with premium check)
        scope.launch {
            delay(3000) // Wait for app to fully initialize

            // Check premium access before syncing
            if (!hasPremiumAccess()) {
                Log.w(TAG, "Photo sync requires premium subscription - skipping")
                return@launch
            }

            registerContentObserver()

            // Clean up any existing duplicates first
            cleanupDuplicates()

            syncRecentPhotos().getOrNull() // Internal call - result ignored
        }
    }

    /**
     * Stop photo sync
     */
    fun stopSync() {
        Log.d(TAG, "Stopping photo sync")
        unregisterContentObserver()
        scope.cancel()
    }

    /**
     * Register content observer for media changes
     */
    private fun registerContentObserver() {
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "Media changed: $uri")

                // Debounce syncs
                scope.launch {
                    delay(SYNC_DEBOUNCE_MS)
                    syncRecentPhotos().getOrNull() // Internal call - result ignored
                }
            }
        }

        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver!!
        )

        Log.d(TAG, "Content observer registered")
    }

    private fun unregisterContentObserver() {
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        contentObserver = null
    }

    /**
     * Load already synced photo IDs from Firebase to prevent duplicates
     */
    private suspend fun loadSyncedPhotoIds(userId: String) {
        if (syncedIdsLoaded) return

        try {
            Log.d(TAG, "Loading synced photo IDs from Firebase...")

            val photosRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(PHOTOS_PATH)

            val snapshot = photosRef.get().await()

            for (child in snapshot.children) {
                val originalId = child.child("originalId").getValue(Long::class.java)
                if (originalId != null) {
                    syncedPhotoIds.add(originalId)
                }
            }

            syncedIdsLoaded = true
            Log.d(TAG, "Loaded ${syncedPhotoIds.size} synced photo IDs from Firebase")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading synced photo IDs", e)
        }
    }

    /**
     * Sync recent photos to Firebase
     * NOTE: Requires premium subscription
     */
    suspend fun syncRecentPhotos(): Result<String> {
        var syncedCount = 0

        try {
            // Double-check premium access before syncing
            if (!hasPremiumAccess()) {
                Log.w(TAG, "Photo sync requires premium subscription")
                return Result.failure(Exception("Photo sync requires a premium subscription. Please upgrade to Pro to sync photos."))
            }

            val userId = unifiedIdentityManager.getUnifiedUserIdSync() ?: return Result.failure(Exception("User authentication required"))

            Log.d(TAG, "Syncing recent photos...")

            // Load already synced IDs from Firebase first to prevent duplicates
            loadSyncedPhotoIds(userId)

            val photos = getRecentPhotos(MAX_PHOTOS_TO_SYNC)
            Log.d(TAG, "Found ${photos.size} recent photos")

            for (photo in photos) {
                if (!syncedPhotoIds.contains(photo.id)) {
                    uploadPhoto(userId, photo)
                    syncedPhotoIds.add(photo.id)
                    syncedCount++
                }
            }

            // Clean up old photos from Firebase
            cleanupOldPhotos(userId)

        } catch (e: Exception) {
            Log.e(TAG, "Error syncing photos", e)
            return Result.failure(Exception("Photo sync failed: ${e.message}"))
        }

        Log.d(TAG, "Photo sync completed successfully")
        return Result.success("Photo sync completed successfully! $syncedCount photos synced.")
    }

    /**
     * Get recent photos from device
     */
    private fun getRecentPhotos(limit: Int): List<LocalPhoto> {
        val photos = mutableListOf<LocalPhoto>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "photo_$id.jpg"
                    val dateTaken = cursor.getLong(dateTakenColumn)
                    val dateAdded = cursor.getLong(dateAddedColumn) * 1000 // Convert to ms
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)
                    val size = cursor.getLong(sizeColumn)
                    val mimeType = cursor.getString(mimeColumn) ?: "image/jpeg"

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    photos.add(
                        LocalPhoto(
                            id = id,
                            name = name,
                            dateTaken = if (dateTaken > 0) dateTaken else dateAdded,
                            width = width,
                            height = height,
                            size = size,
                            mimeType = mimeType,
                            contentUri = contentUri
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying photos", e)
        }

        return photos
    }

    /**
     * Upload a photo to R2 storage via Cloud Functions
     */
    private suspend fun uploadPhoto(userId: String, photo: LocalPhoto) {
        try {
            Log.d(TAG, "Uploading photo: ${photo.name}")

            // Create thumbnail
            val thumbnail = createThumbnail(photo.contentUri) ?: return
            val thumbnailBytes = compressThumbnail(thumbnail)

            val photoId = UUID.randomUUID().toString()
            val fileName = "$photoId.jpg"

            // Step 1: Get presigned upload URL from R2
            val uploadUrlData = hashMapOf(
                "fileName" to fileName,
                "contentType" to "image/jpeg",
                "fileSize" to thumbnailBytes.size,
                "transferType" to "photo"
            )

            val uploadUrlResult = functions
                .getHttpsCallable("getR2UploadUrl")
                .call(uploadUrlData)
                .await()

            @Suppress("UNCHECKED_CAST")
            val uploadResponse = uploadUrlResult.data as? Map<String, Any>
            val uploadUrl = uploadResponse?.get("uploadUrl") as? String
            val r2Key = uploadResponse?.get("r2Key") as? String
            val fileId = uploadResponse?.get("fileId") as? String

            if (uploadUrl == null || r2Key == null || fileId == null) {
                Log.e(TAG, "Failed to get R2 upload URL for photo: ${photo.name}")
                return
            }

            // Step 2: Upload directly to R2 via presigned URL
            val uploaded = withContext(Dispatchers.IO) {
                uploadToR2(uploadUrl, thumbnailBytes, "image/jpeg")
            }

            if (!uploaded) {
                Log.e(TAG, "Failed to upload photo to R2: ${photo.name}")
                return
            }

            // Step 3: Confirm upload with Cloud Function (this also stores metadata)
            val confirmData = hashMapOf(
                "fileId" to fileId,
                "r2Key" to r2Key,
                "fileName" to fileName,
                "fileSize" to thumbnailBytes.size,
                "contentType" to "image/jpeg",
                "transferType" to "photo",
                "photoMetadata" to hashMapOf(
                    "originalId" to photo.id,
                    "dateTaken" to photo.dateTaken,
                    "width" to photo.width,
                    "height" to photo.height,
                    "size" to photo.size,
                    "mimeType" to photo.mimeType
                )
            )

            functions
                .getHttpsCallable("confirmR2Upload")
                .call(confirmData)
                .await()

            Log.d(TAG, "Photo uploaded successfully to R2: ${photo.name} (key: $r2Key)")

        } catch (e: Exception) {
            Log.e(TAG, "Error uploading photo: ${photo.name}", e)
        }
    }

    /**
     * Upload bytes directly to R2 via presigned URL
     */
    private fun uploadToR2(uploadUrl: String, data: ByteArray, contentType: String): Boolean {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(uploadUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("Content-Length", data.size.toString())
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            connection.outputStream.use { outputStream ->
                outputStream.write(data)
                outputStream.flush()
            }

            val responseCode = connection.responseCode
            return responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading to R2", e)
            return false
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Create a thumbnail from an image URI
     */
    private fun createThumbnail(uri: Uri): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // Calculate sample size
            val width = options.outWidth
            val height = options.outHeight
            var sampleSize = 1

            if (width > MAX_THUMBNAIL_SIZE || height > MAX_THUMBNAIL_SIZE) {
                val halfWidth = width / 2
                val halfHeight = height / 2

                while ((halfWidth / sampleSize) >= MAX_THUMBNAIL_SIZE &&
                    (halfHeight / sampleSize) >= MAX_THUMBNAIL_SIZE) {
                    sampleSize *= 2
                }
            }

            // Decode with sample size
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating thumbnail", e)
            null
        }
    }

    /**
     * Compress bitmap to JPEG bytes
     */
    private fun compressThumbnail(bitmap: Bitmap): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, outputStream)
        return outputStream.toByteArray()
    }

    /**
     * Clean up old photos from R2 and database (keep only recent ones)
     */
    private suspend fun cleanupOldPhotos(userId: String) {
        try {
            val photosRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(PHOTOS_PATH)

            val snapshot = photosRef.orderByChild("syncedAt").get().await()
            val photoCount = snapshot.childrenCount

            if (photoCount > MAX_PHOTOS_TO_SYNC) {
                val photosToDelete = (photoCount - MAX_PHOTOS_TO_SYNC).toInt()
                var deleted = 0

                for (child in snapshot.children) {
                    if (deleted >= photosToDelete) break

                    val photoId = child.key ?: continue
                    val r2Key = child.child("r2Key").getValue(String::class.java)

                    // Delete from R2 if r2Key exists
                    if (r2Key != null) {
                        try {
                            val deleteData = hashMapOf("r2Key" to r2Key)
                            functions
                                .getHttpsCallable("deleteR2File")
                                .call(deleteData)
                                .await()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error deleting photo from R2: $r2Key", e)
                        }
                    }

                    // Delete from Database
                    child.ref.removeValue().await()
                    deleted++
                }

                Log.d(TAG, "Cleaned up $deleted old photos")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old photos", e)
        }
    }

    /**
     * Force sync all recent photos (re-checks Firebase for already synced photos)
     */
    suspend fun forceSync() {
        syncedPhotoIds.clear()
        syncedIdsLoaded = false  // Force reload from Firebase
        syncRecentPhotos().getOrNull() // Internal call - result ignored
    }

    /**
     * Clean up duplicate photos in R2 and database (keeps only the most recent upload for each originalId)
     */
    suspend fun cleanupDuplicates() {
        try {
            val userId = unifiedIdentityManager.getUnifiedUserIdSync() ?: return

            Log.d(TAG, "Cleaning up duplicate photos...")

            val photosRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(PHOTOS_PATH)

            val snapshot = photosRef.get().await()

            // Group photos by originalId, including r2Key for deletion
            data class PhotoInfo(val photoId: String, val syncedAt: Long, val r2Key: String?)
            val photosByOriginalId = mutableMapOf<Long, MutableList<PhotoInfo>>()

            for (child in snapshot.children) {
                val photoId = child.key ?: continue
                val originalId = child.child("originalId").getValue(Long::class.java) ?: continue
                val syncedAt = child.child("syncedAt").getValue(Long::class.java) ?: 0L
                val r2Key = child.child("r2Key").getValue(String::class.java)

                photosByOriginalId.getOrPut(originalId) { mutableListOf() }
                    .add(PhotoInfo(photoId, syncedAt, r2Key))
            }

            var deletedCount = 0

            // For each originalId with duplicates, keep only the most recent one
            for ((originalId, photos) in photosByOriginalId) {
                if (photos.size > 1) {
                    // Sort by syncedAt descending (most recent first)
                    val sorted = photos.sortedByDescending { it.syncedAt }

                    // Delete all but the first (most recent)
                    for (i in 1 until sorted.size) {
                        val photoToDelete = sorted[i]

                        // Delete from R2 if r2Key exists
                        if (photoToDelete.r2Key != null) {
                            try {
                                val deleteData = hashMapOf("r2Key" to photoToDelete.r2Key)
                                functions
                                    .getHttpsCallable("deleteR2File")
                                    .call(deleteData)
                                    .await()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error deleting duplicate photo from R2: ${photoToDelete.r2Key}", e)
                            }
                        }

                        // Delete from Database
                        photosRef.child(photoToDelete.photoId).removeValue().await()
                        deletedCount++

                        Log.d(TAG, "Deleted duplicate photo: ${photoToDelete.photoId} (originalId: $originalId)")
                    }
                }
            }

            Log.d(TAG, "Cleaned up $deletedCount duplicate photos")

        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up duplicates", e)
        }
    }

    /**
     * Local photo data class
     */
    private data class LocalPhoto(
        val id: Long,
        val name: String,
        val dateTaken: Long,
        val width: Int,
        val height: Int,
        val size: Long,
        val mimeType: String,
        val contentUri: Uri
    )
}
