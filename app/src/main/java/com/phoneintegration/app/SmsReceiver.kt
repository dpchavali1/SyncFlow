package com.phoneintegration.app

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val SMS_RECEIVED_ACTION = "com.phoneintegration.app.SMS_RECEIVED"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_MESSAGE = "message"

        // Track recently processed messages to avoid duplicates
        private val recentMessages = mutableMapOf<String, Long>()
        private const val DUPLICATE_WINDOW_MS = 3000L // 3 seconds
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action

        // Samsung sends SMS only through SMS_DELIVER, not SMS_RECEIVED
        if (action == Telephony.Sms.Intents.SMS_DELIVER_ACTION ||
            action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {

            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val sender = messages[0].displayOriginatingAddress
            val fullMessage = messages.joinToString(separator = "") { it.messageBody }
            val timestamp = messages[0].timestampMillis

            Log.d("SMS_RECEIVER", "Received SMS from $sender: $fullMessage")

            // Check for duplicate (Samsung sends both SMS_DELIVER and SMS_RECEIVED)
            val messageKey = "$sender:$fullMessage:$timestamp"
            val now = System.currentTimeMillis()

            synchronized(recentMessages) {
                // Clean up old entries
                recentMessages.entries.removeIf { now - it.value > DUPLICATE_WINDOW_MS }

                // Check if we recently processed this message
                if (recentMessages.containsKey(messageKey)) {
                    Log.d("SMS_RECEIVER", "Duplicate SMS detected, ignoring")
                    return
                }

                // Mark this message as processed
                recentMessages[messageKey] = now
            }

            // IMPORTANT: Write SMS to database (required for default SMS apps)
            try {
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, sender)
                    put(Telephony.Sms.BODY, fullMessage)
                    put(Telephony.Sms.DATE, timestamp)
                    put(Telephony.Sms.DATE_SENT, timestamp)
                    put(Telephony.Sms.READ, 0) // Mark as unread
                    put(Telephony.Sms.SEEN, 1)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                }

                context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
                Log.d("SMS_RECEIVER", "SMS written to database")
            } catch (e: Exception) {
                Log.e("SMS_RECEIVER", "Failed to write SMS to database", e)
            }

            // Get contact name
            val contactHelper = ContactHelper(context)
            val contactName = contactHelper.getContactName(sender)

            // Show notification
            val notificationHelper = NotificationHelper(context)
            notificationHelper.showSmsNotification(sender, fullMessage, contactName)

            // Broadcast locally to update UI & ViewModel
            val broadcast = Intent(SMS_RECEIVED_ACTION).apply {
                putExtra(EXTRA_ADDRESS, sender)
                putExtra(EXTRA_MESSAGE, fullMessage)
            }

            LocalBroadcastManager.getInstance(context).sendBroadcast(broadcast)

            // Immediately sync this message to Firebase for desktop
            // Use WorkManager for guaranteed execution even if app is in background
            com.phoneintegration.app.desktop.SmsSyncWorker.syncNow(context)

            // Also try immediate sync in coroutine (faster if app is active)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val smsRepository = SmsRepository(context)
                    val syncService = com.phoneintegration.app.desktop.DesktopSyncService(context)

                    // Get the most recent message (the one we just received)
                    val recentMessages = smsRepository.getAllRecentMessages(1)
                    if (recentMessages.isNotEmpty()) {
                        syncService.syncMessage(recentMessages[0])
                        Log.d("SMS_RECEIVER", "✅ Message synced to Firebase for desktop immediately")
                    }
                } catch (e: Exception) {
                    Log.e("SMS_RECEIVER", "❌ Error syncing message to Firebase", e)
                }
            }
        }
    }
}
