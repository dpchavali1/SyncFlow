package com.phoneintegration.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.phoneintegration.app.desktop.OutgoingMessageService
import com.phoneintegration.app.e2ee.SignalProtocolManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SyncFlowMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "SyncFlowMessaging"
        private const val CALL_CHANNEL_ID = "syncflow_calls"
        private const val CALL_NOTIFICATION_ID = 3001
    }

    private lateinit var signalProtocolManager: SignalProtocolManager
    private val firebaseDatabase = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        signalProtocolManager = SignalProtocolManager(applicationContext)
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CALL_CHANNEL_ID,
                "SyncFlow Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming SyncFlow video/audio calls"
                setShowBadge(true)
                setSound(ringtoneUri, audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM message received: ${remoteMessage.data}")

        val data = remoteMessage.data
        when (data["type"]) {
            "e2ee_message" -> handleE2EEMessage(data)
            "incoming_call" -> handleIncomingCall(data)
            "call_cancelled" -> handleCallCancelled(data)
            "call_status_changed" -> handleCallStatusChanged(data)
            "call_ended_by_remote" -> handleCallEndedByRemote(data)
            "outgoing_message" -> handleOutgoingMessage(data)
            else -> Log.d(TAG, "Unknown message type: ${data["type"]}")
        }
    }

    private fun handleE2EEMessage(data: Map<String, String>) {
        val senderId = data["senderId"]
        val encryptedBody = data["encryptedBody"]

        if (senderId != null && encryptedBody != null) {
            try {
                val decryptedBody = signalProtocolManager.decryptMessage(encryptedBody)
                if (decryptedBody != null) {
                    Log.d(TAG, "Decrypted message: $decryptedBody")
                    insertSms(senderId, decryptedBody)
                } else {
                    Log.e(TAG, "Failed to decrypt message")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error decrypting message", e)
            }
        }
    }

    private fun handleIncomingCall(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        val callerName = data["callerName"] ?: "Unknown"
        val callerPhone = data["callerPhone"] ?: ""
        val isVideo = data["isVideo"]?.toBoolean() ?: false

        Log.d(TAG, "Incoming call from $callerName ($callerPhone), isVideo: $isVideo, callId: $callId")

        // Start the SyncFlowCallService with the incoming call data
        // Pass the call data directly so it doesn't rely on Firebase listeners (which are offline)
        // The service will handle showing the notification with proper looping ringtone
        val serviceIntent = Intent(this, SyncFlowCallService::class.java).apply {
            action = SyncFlowCallService.ACTION_INCOMING_USER_CALL
            putExtra(SyncFlowCallService.EXTRA_CALL_ID, callId)
            putExtra(SyncFlowCallService.EXTRA_CALLER_NAME, callerName)
            putExtra(SyncFlowCallService.EXTRA_CALLER_PHONE, callerPhone)
            putExtra(SyncFlowCallService.EXTRA_IS_VIDEO, isVideo)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Note: SyncFlowCallService.showIncomingCallNotification() handles:
        // - Showing the notification with proper call UI
        // - Playing looping ringtone via MediaPlayer
        // - Vibration
        // - Launching incoming call activity
        // We don't show a separate notification here to avoid duplicate sounds
    }

    private fun handleCallCancelled(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        Log.d(TAG, "Call cancelled: $callId")

        // Dismiss the notification
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(CALL_NOTIFICATION_ID)
    }

    private fun handleCallStatusChanged(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        val status = data["status"] ?: return
        Log.d(TAG, "📞 Call status changed via FCM: callId=$callId, status=$status")

        // If the call is no longer ringing (answered, ended, rejected, cancelled, etc.),
        // dismiss the notification immediately
        if (status != "ringing") {
            Log.d(TAG, "📞 Call no longer ringing, dismissing notifications")

            // Cancel all call-related notifications immediately
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.cancel(CALL_NOTIFICATION_ID)

            // Also cancel the service's incoming call notification (ID 2002)
            notificationManager.cancel(2002)

            // Also cancel the FCM call notification (ID 3001)
            notificationManager.cancel(3001)

            // IMPORTANT: Directly update the static flow so MainActivity can observe the change
            SyncFlowCallService.dismissIncomingCall()
            Log.d(TAG, "📞 Called SyncFlowCallService.dismissIncomingCall()")

            // Send broadcast as backup
            val dismissIntent = Intent("com.phoneintegration.app.DISMISS_INCOMING_CALL").apply {
                putExtra("call_id", callId)
                putExtra("reason", status)
            }
            sendBroadcast(dismissIntent)

            // Try to tell the service to stop ringing (if it's running)
            try {
                val serviceIntent = Intent(this, SyncFlowCallService::class.java).apply {
                    action = SyncFlowCallService.ACTION_DISMISS_CALL_NOTIFICATION
                    putExtra(SyncFlowCallService.EXTRA_CALL_ID, callId)
                }
                startService(serviceIntent)
                Log.d(TAG, "📞 Sent dismiss intent to SyncFlowCallService")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send dismiss to service (may not be running)", e)
            }
        }
    }

    private fun handleCallEndedByRemote(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        Log.d(TAG, "📞 Call ended by remote: callId=$callId")

        // IMPORTANT: Directly update the static flow so MainActivity can observe the change
        SyncFlowCallService.dismissIncomingCall()
        Log.d(TAG, "📞 Called SyncFlowCallService.dismissIncomingCall()")

        // Send broadcast as backup
        val endCallIntent = Intent("com.phoneintegration.app.CALL_ENDED_BY_REMOTE").apply {
            putExtra("call_id", callId)
        }
        sendBroadcast(endCallIntent)

        // Tell the call service to end the call
        try {
            val serviceIntent = Intent(this, SyncFlowCallService::class.java).apply {
                action = SyncFlowCallService.ACTION_END_CALL
                putExtra(SyncFlowCallService.EXTRA_CALL_ID, callId)
            }
            startService(serviceIntent)
            Log.d(TAG, "📞 Sent end call intent to SyncFlowCallService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send end call to service", e)
        }

        // Also try to directly end via the call manager
        serviceScope.launch {
            try {
                SyncFlowCallService.getCallManager()?.let { callManager ->
                    if (callManager.currentCall.value?.id == callId) {
                        Log.d(TAG, "📞 Ending call via call manager")
                        callManager.endUserCall()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error ending call via call manager", e)
            }
        }
    }

    private fun handleOutgoingMessage(data: Map<String, String>) {
        val messageId = data["messageId"] ?: return
        val address = data["address"] ?: ""

        Log.d(TAG, "Outgoing message notification: $messageId to $address")

        // Start OutgoingMessageService to process the message
        // The service will process pending messages and stop itself when done
        OutgoingMessageService.start(this)
    }

    private fun insertSms(senderId: String, body: String) {
        serviceScope.launch {
            val address = getAddressForUid(senderId)
            if (address != null) {
                val values = ContentValues().apply {
                    put(Telephony.Sms.Inbox.ADDRESS, address)
                    put(Telephony.Sms.Inbox.BODY, body)
                    put(Telephony.Sms.Inbox.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.Inbox.READ, 0)
                }
                contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            }
        }
    }

    private suspend fun getAddressForUid(uid: String): String? {
        return try {
            val dataSnapshot = firebaseDatabase.getReference("users").child(uid).child("phoneNumber").get().await()
            dataSnapshot.getValue(String::class.java)
        } catch (e: Exception) {
            android.util.Log.e("SyncFlowMessaging", "Error getting address for UID: ${e.message}")
            null
        }
    }


    override fun onNewToken(token: String) {
        super.onNewToken(token)
        sendTokenToServer(token)
    }

    private fun sendTokenToServer(token: String) {
        val uid = auth.currentUser?.uid
        if (uid != null) {
            firebaseDatabase.getReference("fcm_tokens").child(uid).setValue(token)
        }
    }
}
