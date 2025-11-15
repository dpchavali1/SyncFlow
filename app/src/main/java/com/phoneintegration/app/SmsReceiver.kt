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
        if (intent?.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val bundle = intent.extras
            if (bundle != null && context != null) {
                val pdus = bundle.get("pdus") as Array<*>
                val messages = pdus.map { pdu ->
                    Telephony.Sms.Intents.getMessagesFromIntent(intent)[0]
                }

                messages.forEach { smsMessage ->
                    val sender = smsMessage.displayOriginatingAddress
                    val messageBody = smsMessage.messageBody

                    Log.d("SMS_RECEIVER", "Received SMS from: $sender")
                    Log.d("SMS_RECEIVER", "Message: $messageBody")

                    // Get contact name
                    val contactHelper = ContactHelper(context)
                    val contactName = contactHelper.getContactName(sender)

                    // Show notification
                    val notificationHelper = NotificationHelper(context)
                    notificationHelper.showSmsNotification(sender, messageBody, contactName)

                    // Broadcast locally that new SMS arrived
                    val broadcastIntent = Intent(SMS_RECEIVED_ACTION).apply {
                        putExtra(EXTRA_ADDRESS, sender)
                        putExtra(EXTRA_MESSAGE, messageBody)
                    }
                    context.sendBroadcast(broadcastIntent)
                }
            }
        }
    }
}