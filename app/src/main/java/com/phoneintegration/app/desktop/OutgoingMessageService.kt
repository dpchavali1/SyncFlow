package com.phoneintegration.app.desktop

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phoneintegration.app.MainActivity
import com.phoneintegration.app.R
import com.phoneintegration.app.SmsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch

/**
 * Foreground service that continuously listens for outgoing messages from desktop
 * and sends them via SMS in real-time
 */
class OutgoingMessageService : Service() {

    companion object {
        private const val TAG = "OutgoingMessageService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "desktop_sync_channel"
        private const val CHANNEL_NAME = "Desktop Sync"

        fun start(context: Context) {
            val intent = Intent(context, OutgoingMessageService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OutgoingMessageService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listeningJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        // Start foreground service with notification
        val notification = createNotification("Listening for messages from desktop")
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY // Restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        listeningJob?.cancel()
        serviceScope.cancel()
    }

    private fun startListening() {
        listeningJob = serviceScope.launch {
            try {
                Log.d(TAG, "Starting to listen for outgoing messages...")
                val syncService = DesktopSyncService(applicationContext)
                val smsRepository = SmsRepository(applicationContext)

                // Listen for outgoing messages
                syncService.listenForOutgoingMessages()
                    .catch { e ->
                        Log.e(TAG, "Error in message flow", e)
                    }
                    .collect { messageData ->
                        try {
                            val messageId = messageData["_messageId"] as? String
                            val messageRef = messageData["_messageRef"] as? com.google.firebase.database.DatabaseReference
                            val address = messageData["address"] as? String
                            val body = messageData["body"] as? String

                            if (address != null && body != null && messageId != null) {
                                Log.d(TAG, "Received message to send: $address")
                                updateNotification("Sending SMS to $address...")

                                // Send SMS
                                smsRepository.sendSms(address, body)

                                Log.d(TAG, "SMS sent successfully to $address")

                                // Write sent message to messages collection
                                try {
                                    syncService.writeSentMessage(messageId, address, body)
                                    Log.d(TAG, "Sent message written to Firebase")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error writing sent message to Firebase", e)
                                }

                                // Remove from outgoing_messages
                                try {
                                    messageRef?.removeValue()
                                    Log.d(TAG, "Outgoing message removed from Firebase")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error removing outgoing message", e)
                                }

                                updateNotification("Listening for messages from desktop")
                            } else {
                                Log.w(TAG, "Invalid message data: address=$address, body=$body, id=$messageId")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error sending SMS", e)
                            updateNotification("Error sending SMS: ${e.message}")
                            delay(3000)
                            updateNotification("Listening for messages from desktop")
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in listening loop", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps SyncFlow connected to your desktop"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SyncFlow Desktop Sync")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(message: String) {
        val notification = createNotification(message)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
