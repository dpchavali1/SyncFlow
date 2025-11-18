package com.phoneintegration.app.mms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class MmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("MmsSentReceiver", "MMS_SENT broadcast received")

        val result = resultCode

        when (result) {
            android.app.Activity.RESULT_OK -> {
                Toast.makeText(context, "MMS sent!", Toast.LENGTH_SHORT).show()
                Log.d("MmsSentReceiver", "MMS successfully sent")
            }
            else -> {
                Toast.makeText(context, "Failed to send MMS!", Toast.LENGTH_LONG).show()
                Log.e("MmsSentReceiver", "MMS send failed: code=$result")
            }
        }
    }
}
