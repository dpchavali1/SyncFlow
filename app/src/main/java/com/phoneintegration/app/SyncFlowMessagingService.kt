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
            val channel = NotificationChannel(
                CALL_CHANNEL_ID,
                "SyncFlow Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming SyncFlow video/audio calls"
                setShowBadge(true)
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

        Log.d(TAG, "Incoming call from $callerName ($callerPhone), isVideo: $isVideo")

        // Start the SyncFlowCallService to handle the call
        val serviceIntent = Intent(this, SyncFlowCallService::class.java).apply {
            action = SyncFlowCallService.ACTION_START_SERVICE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Launch the MainActivity with the incoming call
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("incoming_syncflow_call_id", callId)
            putExtra("incoming_syncflow_call_name", callerName)
            putExtra("incoming_syncflow_call_video", isVideo)
        }

        // Show a heads-up notification for incoming call
        showIncomingCallNotification(callId, callerName, isVideo, intent)
    }

    private fun showIncomingCallNotification(callId: String, callerName: String, isVideo: Boolean, launchIntent: Intent) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callType = if (isVideo) "Video" else "Audio"

        val notification = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Incoming $callType Call")
            .setContentText("$callerName is calling")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(CALL_NOTIFICATION_ID, notification)
    }

    private fun handleCallCancelled(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        Log.d(TAG, "Call cancelled: $callId")

        // Dismiss the notification
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(CALL_NOTIFICATION_ID)
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
