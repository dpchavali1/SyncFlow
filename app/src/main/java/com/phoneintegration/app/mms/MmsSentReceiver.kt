package com.phoneintegration.app.mms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast

class MmsSentReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MmsSentReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "=== MMS_SENT broadcast received ===")
        Log.d(TAG, "Intent action: ${intent.action}")
        Log.d(TAG, "Intent extras: ${intent.extras?.keySet()?.joinToString()}")

        val result = resultCode
        Log.d(TAG, "Result code: $result")

        val resultMessage = when (result) {
            Activity.RESULT_OK -> "SUCCESS (RESULT_OK)"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE"
            SmsManager.RESULT_ERROR_NULL_PDU -> "NULL_PDU"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF"
            else -> "UNKNOWN_ERROR (code: $result)"
        }

        Log.d(TAG, "MMS send result: $resultMessage")

        when (result) {
            Activity.RESULT_OK -> {
                Toast.makeText(context, "MMS sent successfully!", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "✓ MMS successfully sent")
            }
            else -> {
                Toast.makeText(context, "MMS failed: $resultMessage", Toast.LENGTH_LONG).show()
                Log.e(TAG, "✗ MMS send failed: $resultMessage")
            }
        }
    }
}
