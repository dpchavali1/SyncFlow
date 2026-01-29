package com.phoneintegration.app.desktop

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import androidx.work.*
import com.google.firebase.functions.FirebaseFunctions
import com.phoneintegration.app.MmsHelper
import com.phoneintegration.app.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically syncs SMS messages to Firebase
 */
class SmsSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SmsSyncWorker"
        const val WORK_NAME = "sms_sync_work"

        /**
         * Schedule adaptive SMS sync (triggered by IntelligentSyncManager)
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Use longer intervals since IntelligentSyncManager handles real-time updates
            val syncRequest = PeriodicWorkRequestBuilder<SmsSyncWorker>(
                repeatInterval = 60, // Sync every hour as backup (IntelligentSyncManager handles frequent updates)
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )

            Log.d(TAG, "Adaptive SMS sync worker scheduled (backup to IntelligentSyncManager)")
        }

        /**
         * Cancel SMS sync
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "SMS sync worker cancelled")
        }

        /**
         * Trigger immediate sync (called by IntelligentSyncManager)
         */
        fun syncNow(context: Context) {
            val syncRequest = OneTimeWorkRequestBuilder<SmsSyncWorker>()
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
            Log.d(TAG, "Immediate SMS sync triggered by IntelligentSyncManager")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting SMS sync...")

            val syncService = DesktopSyncService(applicationContext)
            val smsRepository = SmsRepository(applicationContext)

            // Get messages from last 7 days for periodic sync (balance between freshness and efficiency)
            val messages = smsRepository.getMessagesFromLastDays(days = 7)

            Log.d(TAG, "Syncing ${messages.size} messages from last 7 days")

            // Sync each message
            syncService.syncMessages(messages)

            Log.d(TAG, "SMS sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during SMS sync", e)
            Result.retry()
        }
    }
}

/**
 * Worker to listen for outgoing messages from desktop
 */
class OutgoingMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "OutgoingMessageWorker"
        const val WORK_NAME = "outgoing_message_work"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // IntelligentSyncManager handles real-time outgoing messages via listeners
            // This worker serves as backup and for bulk operations
            val workRequest = PeriodicWorkRequestBuilder<OutgoingMessageWorker>(
                repeatInterval = 30, // Less frequent since IntelligentSyncManager handles real-time
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(0, TimeUnit.SECONDS) // Run immediately
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Log.d(TAG, "Adaptive outgoing message worker scheduled (backup to IntelligentSyncManager)")
        }

        /**
         * Trigger immediate check for outgoing messages
         */
        fun checkNow(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<OutgoingMessageWorker>()
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(TAG, "Immediate outgoing message check triggered")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Outgoing message worker cancelled")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking for outgoing messages...")

            val syncService = DesktopSyncService(applicationContext)
            val smsRepository = SmsRepository(applicationContext)

            // Get outgoing messages from Firebase
            val outgoingMessages = syncService.getOutgoingMessages()

            if (outgoingMessages.isEmpty()) {
                Log.d(TAG, "No outgoing messages to process")
                return@withContext Result.success()
            }

            Log.d(TAG, "Found ${outgoingMessages.size} outgoing messages")

            // Process each message
            outgoingMessages.forEach { (messageId, messageData) ->
                try {
                    val address = messageData["address"] as? String ?: return@forEach
                    val body = messageData["body"] as? String ?: ""
                    val isMms = messageData["isMms"] as? Boolean ?: false
                    @Suppress("UNCHECKED_CAST")
                    val attachments = messageData["attachments"] as? List<Map<String, Any?>>

                    val sendSuccess = if (isMms && !attachments.isNullOrEmpty()) {
                        Log.d(TAG, "Sending MMS to $address (attachments=${attachments.size})")
                        sendMmsWithAttachments(address, body, attachments)
                    } else if (body.isNotBlank()) {
                        Log.d(TAG, "Sending SMS to $address: $body")
                        smsRepository.sendSms(address, body)
                    } else {
                        Log.w(TAG, "Empty outgoing message (no body, no attachments)")
                        false
                    }

                    if (!sendSuccess) {
                        Log.e(TAG, "Failed to send message $messageId to $address")
                        return@forEach
                    }

                    // Write sent message to messages collection
                    if (isMms && !attachments.isNullOrEmpty()) {
                        syncService.writeSentMmsMessage(messageId, address, body, attachments)
                    } else {
                        syncService.writeSentMessage(messageId, address, body)
                    }

                    // Delete from outgoing_messages
                    syncService.deleteOutgoingMessage(messageId)

                    Log.d(TAG, "Message sent and synced successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending message $messageId", e)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing outgoing messages", e)
            Result.retry()
        }
    }

    private suspend fun sendMmsWithAttachments(
        address: String,
        body: String,
        attachments: List<Map<String, Any?>>
    ): Boolean {
        return try {
            val attachment = attachments.firstOrNull() ?: return false

            val url = attachment["url"] as? String
            val inlineData = attachment["inlineData"] as? String
            val fileName = attachment["fileName"] as? String ?: "attachment"
            val contentType = attachment["contentType"] as? String ?: "application/octet-stream"

            Log.d(TAG, "Processing MMS attachment: $fileName ($contentType)")

            val attachmentData: ByteArray? = when {
                !inlineData.isNullOrEmpty() -> {
                    Log.d(TAG, "Using inline data for attachment")
                    Base64.decode(inlineData, Base64.DEFAULT)
                }
                !url.isNullOrEmpty() -> {
                    Log.d(TAG, "Downloading attachment from: ${url.take(50)}...")
                    downloadAttachment(url)
                }
                else -> null
            }

            if (attachmentData == null) {
                Log.e(TAG, "Failed to get attachment data")
                return false
            }

            Log.d(TAG, "Attachment data size: ${attachmentData.size} bytes")

            val cacheDir = File(applicationContext.cacheDir, "mms_outgoing")
            cacheDir.mkdirs()
            val cacheFile = File(cacheDir, fileName)
            cacheFile.writeBytes(attachmentData)

            val contentUri = FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.provider",
                cacheFile
            )

            val success = MmsHelper.sendMms(applicationContext, address, contentUri, body.ifEmpty { null })

            cacheFile.delete()

            if (success) {
                Log.d(TAG, "MMS sent successfully to $address")
            } else {
                Log.e(TAG, "MMS send failed to $address")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Error sending MMS with attachments", e)
            false
        }
    }

    /**
     * Download attachment from R2 storage
     */
    private suspend fun downloadAttachment(r2Key: String): ByteArray? {
        return try {
            // Get presigned download URL from R2 via Cloud Function
            val downloadUrlData = hashMapOf("r2Key" to r2Key)
            val result = FirebaseFunctions.getInstance()
                .getHttpsCallable("getR2DownloadUrl")
                .call(downloadUrlData)
                .await()

            @Suppress("UNCHECKED_CAST")
            val response = result.data as? Map<String, Any>
            val downloadUrl = response?.get("downloadUrl") as? String
                ?: throw Exception("Failed to get R2 download URL")

            // Download from presigned URL
            withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                try {
                    val urlObj = URL(downloadUrl)
                    connection = urlObj.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 30000
                    connection.readTimeout = 60000

                    if (connection.responseCode in 200..299) {
                        connection.inputStream.use { it.readBytes() }
                    } else {
                        Log.e(TAG, "Download failed with response code: ${connection.responseCode}")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error downloading from URL", e)
                    null
                } finally {
                    connection?.disconnect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading attachment from R2", e)
            null
        }
    }
}
