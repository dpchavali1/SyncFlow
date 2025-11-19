package com.phoneintegration.app.desktop

import android.content.Context
import android.util.Log
import androidx.work.*
import com.phoneintegration.app.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
         * Schedule periodic SMS sync
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SmsSyncWorker>(
                repeatInterval = 15, // Sync every 15 minutes
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

            Log.d(TAG, "SMS sync worker scheduled")
        }

        /**
         * Cancel SMS sync
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "SMS sync worker cancelled")
        }

        /**
         * Trigger immediate sync
         */
        fun syncNow(context: Context) {
            val syncRequest = OneTimeWorkRequestBuilder<SmsSyncWorker>()
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
            Log.d(TAG, "Immediate SMS sync triggered")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting SMS sync...")

            val syncService = DesktopSyncService(applicationContext)
            val smsRepository = SmsRepository(applicationContext)

            // Get recent messages (last 50)
            val messages = smsRepository.getAllRecentMessages(limit = 50)

            Log.d(TAG, "Syncing ${messages.size} messages")

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

            val workRequest = PeriodicWorkRequestBuilder<OutgoingMessageWorker>(
                repeatInterval = 15,
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Log.d(TAG, "Outgoing message worker scheduled")
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

            // Listen for messages to send
            syncService.listenForOutgoingMessages().collect { messageData ->
                val address = messageData["address"] as? String ?: return@collect
                val body = messageData["body"] as? String ?: return@collect

                Log.d(TAG, "Sending SMS to $address: $body")

                // Send SMS
                smsRepository.sendSms(address, body)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing outgoing messages", e)
            Result.retry()
        }
    }
}
