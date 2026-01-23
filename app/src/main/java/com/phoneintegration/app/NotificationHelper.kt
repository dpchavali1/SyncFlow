package com.phoneintegration.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.util.Log
import com.phoneintegration.app.data.database.SyncFlowDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationHelper(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "sms_channel"
        private const val CHANNEL_NAME = "SMS Messages"
        private const val SPAM_CHANNEL_ID = "spam_channel"
        private const val SPAM_CHANNEL_NAME = "Spam Messages"
        private const val NOTIFICATION_ID = 1
        private const val CUSTOM_CHANNEL_PREFIX = "sms_custom_"
    }

    init {
        createNotificationChannel()
        createSpamNotificationChannel()
    }

    private fun createNotificationChannel(soundUri: Uri? = null, channelId: String = CHANNEL_ID) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, CHANNEL_NAME, importance).apply {
                description = "Notifications for incoming SMS messages"
                enableVibration(true)
                setShowBadge(true)
                if (soundUri != null) {
                    setSound(soundUri, AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                }
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createSpamNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_LOW // Lower importance for spam
            val channel = NotificationChannel(SPAM_CHANNEL_ID, SPAM_CHANNEL_NAME, importance).apply {
                description = "Notifications for messages detected as spam"
                enableVibration(false) // No vibration for spam
                setShowBadge(false) // Don't show badge for spam
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create a custom notification channel for a specific thread with custom sound
     */
    fun createCustomChannelForThread(threadId: Long, soundUri: Uri) {
        val channelId = "$CUSTOM_CHANNEL_PREFIX$threadId"
        createNotificationChannel(soundUri, channelId)
    }

    fun showSmsNotification(
        sender: String,
        message: String,
        contactName: String? = null,
        customSoundUri: String? = null,
        threadId: Long? = null
    ) {
        val displayName = contactName ?: sender

        // Intent to open app when notification is tapped
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_thread_id", threadId ?: 0L)
            putExtra("open_address", sender)
            putExtra("open_name", displayName)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build notification with optional custom sound
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email) // We'll replace this with custom icon later
            .setContentTitle("New message from $displayName")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(message)
            )

        // Apply custom sound if provided (for pre-O devices)
        if (customSoundUri != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            try {
                builder.setSound(Uri.parse(customSoundUri))
            } catch (e: Exception) {
                Log.w("NotificationHelper", "Failed to set custom sound: ${e.message}")
            }
        }

        val notification = builder.build()

        // Show notification with unique ID based on timestamp to avoid overwriting
        try {
            val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            android.util.Log.e("NotificationHelper", "Permission denied: ${e.message}")
        }
    }

    /**
     * Show notification with custom sound from database settings
     */
    fun showSmsNotificationWithSettings(sender: String, message: String, contactName: String?, threadId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settingsDao = SyncFlowDatabase.getInstance(context).notificationSettingsDao()
                val settings = settingsDao.get(threadId)

                // Show notification on main thread
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    showSmsNotification(sender, message, contactName, settings?.customSoundUri, threadId)
                }
            } catch (e: Exception) {
                Log.e("NotificationHelper", "Error loading notification settings", e)
                showSmsNotification(sender, message, contactName, threadId = threadId)
            }
        }
    }
    fun showMmsNotification(
        sender: String,
        message: String,
        contactName: String? = null
    ) {

        val displayName = contactName ?: sender

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("New MMS from $displayName")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()

        try {
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) {
                manager.notify(
                    (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                    notification
                )
            } else {
                Log.e("NotificationHelper", "Notification permission not granted")
            }
        } catch (e: SecurityException) {
            Log.e("NotificationHelper", "Notification permission denied", e)
        }
    }

    /**
     * Show notification for spam messages - less intrusive than regular SMS notifications
     */
    fun showSpamNotification(sender: String, message: String, contactName: String? = null) {
        val displayName = contactName ?: sender

        // Intent to open spam folder when notification is tapped
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_spam", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            1, // Different request code for spam
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Truncate message for preview
        val truncatedMessage = if (message.length > 50) message.take(50) + "..." else message

        val notification = NotificationCompat.Builder(context, SPAM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_delete) // Use delete icon to indicate spam
            .setContentTitle("Spam detected from $displayName")
            .setContentText("Message moved to Spam folder")
            .setSubText("Tap to view spam folder")
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority for spam
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Message moved to Spam folder\n\n\"$truncatedMessage\"")
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        // Show notification
        try {
            val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.e("NotificationHelper", "Permission denied for spam notification: ${e.message}")
        }
    }

}
