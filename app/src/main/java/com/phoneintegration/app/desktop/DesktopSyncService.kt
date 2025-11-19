package com.phoneintegration.app.desktop

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.phoneintegration.app.SmsMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Handles syncing SMS messages to Firebase Realtime Database
 * for desktop access
 */
class DesktopSyncService(private val context: Context) {

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    companion object {
        private const val TAG = "DesktopSyncService"
        private const val MESSAGES_PATH = "messages"
        private const val DEVICES_PATH = "devices"
        private const val USERS_PATH = "users"
    }

    /**
     * Get current user ID (or create anonymous user)
     */
    suspend fun getCurrentUserId(): String {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            return currentUser.uid
        }

        // Sign in anonymously if no user
        try {
            val result = auth.signInAnonymously().await()
            Log.d(TAG, "Signed in anonymously: ${result.user?.uid}")
            return result.user?.uid ?: throw Exception("Failed to sign in")
        } catch (e: Exception) {
            Log.e(TAG, "Error signing in", e)
            throw e
        }
    }

    /**
     * Sync a message to Firebase
     */
    suspend fun syncMessage(message: SmsMessage) {
        try {
            val userId = getCurrentUserId()
            val messageRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MESSAGES_PATH)
                .child(message.id.toString())

            val messageData = mapOf(
                "id" to message.id,
                "address" to message.address,
                "body" to message.body,
                "date" to message.date,
                "type" to message.type,
                "timestamp" to ServerValue.TIMESTAMP
            )

            messageRef.setValue(messageData).await()
            Log.d(TAG, "Message synced: ${message.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing message", e)
        }
    }

    /**
     * Sync multiple messages to Firebase
     */
    suspend fun syncMessages(messages: List<SmsMessage>) {
        for (message in messages) {
            syncMessage(message)
        }
    }

    /**
     * Listen for new messages from desktop (user sending SMS from web)
     */
    fun listenForOutgoingMessages(): Flow<Map<String, Any?>> = callbackFlow {
        val userId = runCatching { getCurrentUserId() }.getOrNull()
        if (userId == null) {
            close()
            return@callbackFlow
        }

        val outgoingRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child("outgoing_messages")

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val messageData = snapshot.value as? Map<String, Any?> ?: return
                trySend(messageData)

                // Remove after processing
                snapshot.ref.removeValue()
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Listen for outgoing messages cancelled", error.toException())
            }
        }

        outgoingRef.addChildEventListener(listener)

        awaitClose {
            outgoingRef.removeEventListener(listener)
        }
    }

    /**
     * Get paired devices
     */
    suspend fun getPairedDevices(): List<PairedDevice> {
        try {
            val userId = getCurrentUserId()
            val devicesRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(DEVICES_PATH)

            val snapshot = devicesRef.get().await()
            val devices = mutableListOf<PairedDevice>()

            snapshot.children.forEach { deviceSnapshot ->
                val device = PairedDevice(
                    id = deviceSnapshot.key ?: return@forEach,
                    name = deviceSnapshot.child("name").value as? String ?: "Unknown",
                    platform = deviceSnapshot.child("platform").value as? String ?: "web",
                    lastSeen = deviceSnapshot.child("lastSeen").value as? Long ?: 0L,
                    isPaired = deviceSnapshot.child("isPaired").value as? Boolean ?: false
                )
                devices.add(device)
            }

            return devices
        } catch (e: Exception) {
            Log.e(TAG, "Error getting paired devices", e)
            return emptyList()
        }
    }

    /**
     * Pair a new device
     */
    suspend fun pairDevice(deviceId: String, deviceName: String): Boolean {
        try {
            val userId = getCurrentUserId()
            val deviceRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(DEVICES_PATH)
                .child(deviceId)

            val deviceData = mapOf(
                "name" to deviceName,
                "platform" to "web",
                "isPaired" to true,
                "pairedAt" to ServerValue.TIMESTAMP,
                "lastSeen" to ServerValue.TIMESTAMP
            )

            deviceRef.setValue(deviceData).await()
            Log.d(TAG, "Device paired: $deviceName")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error pairing device", e)
            return false
        }
    }

    /**
     * Unpair a device
     */
    suspend fun unpairDevice(deviceId: String): Boolean {
        try {
            val userId = getCurrentUserId()
            val deviceRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(DEVICES_PATH)
                .child(deviceId)

            deviceRef.removeValue().await()
            Log.d(TAG, "Device unpaired: $deviceId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error unpairing device", e)
            return false
        }
    }

    /**
     * Generate pairing token (for QR code)
     */
    suspend fun generatePairingToken(): String {
        val userId = getCurrentUserId()
        val timestamp = System.currentTimeMillis()
        val randomToken = (0..999999).random().toString().padStart(6, '0')

        val pairingToken = "$userId:$timestamp:$randomToken"

        // Store pending pairing in Firebase
        val pairingRef = database.reference
            .child("pending_pairings")
            .child(randomToken)

        val pairingData = mapOf(
            "userId" to userId,
            "token" to pairingToken,
            "createdAt" to ServerValue.TIMESTAMP,
            "expiresAt" to timestamp + (5 * 60 * 1000) // 5 minutes
        )

        pairingRef.setValue(pairingData).await()

        return pairingToken
    }
}

/**
 * Data class representing a paired device
 */
data class PairedDevice(
    val id: String,
    val name: String,
    val platform: String,
    val lastSeen: Long,
    val isPaired: Boolean
)
