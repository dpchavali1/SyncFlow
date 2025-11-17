package com.phoneintegration.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val SMS_RECEIVED_ACTION = "com.phoneintegration.app.SMS_RECEIVED"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_MESSAGE = "message"
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

            Log.d("SMS_RECEIVER", "Received SMS from $sender: $fullMessage")

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
        }
    }
}
