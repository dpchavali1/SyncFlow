package com.phoneintegration.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("MmsReceiver", "Received action: $action")
        
        when (action) {
            "android.provider.Telephony.WAP_PUSH_DELIVER",
            "android.provider.Telephony.WAP_PUSH_RECEIVED" -> {
                handleMmsReceived(context, intent)
            }
        }
    }

    private var lastMmsTimestamp: Long = 0

    private fun handleMmsReceived(context: Context, intent: Intent) {
        try {
            val now = System.currentTimeMillis()

            // Debounce duplicate MMS broadcasts occurring within 2 seconds
            if (now - lastMmsTimestamp < 2000) {
                Log.d("MmsReceiver", "Duplicate MMS intent ignored")
                return
            }
            lastMmsTimestamp = now

            Log.d("MmsReceiver", "MMS message received")

            val notificationHelper = NotificationHelper(context)
            notificationHelper.showMmsNotification(
                "📬 New MMS",
                "You received a multimedia message"
            )

            // Broadcast to refresh UI
            val local = Intent("com.phoneintegration.app.MMS_RECEIVED")
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(context)
                .sendBroadcast(local)

        } catch (e: Exception) {
            Log.e("MmsReceiver", "Error handling MMS", e)
        }
    }
}
