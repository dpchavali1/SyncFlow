package com.phoneintegration.app.desktop

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.data.database.Group
import com.phoneintegration.app.data.database.GroupMember
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
        private const val GROUPS_PATH = "groups"
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
            // Filter out RBM (Rich Business Messaging) spam
            if (message.address.contains("@rbm.goog", ignoreCase = true)) {
                Log.d(TAG, "Skipping RBM message from: ${message.address}")
                return
            }

            val userId = getCurrentUserId()
            val messageRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MESSAGES_PATH)
                .child(message.id.toString())

            val messageData = mutableMapOf<String, Any?>(
                "id" to message.id,
                "address" to message.address,
                "body" to message.body,
                "date" to message.date,
                "type" to message.type,
                "timestamp" to ServerValue.TIMESTAMP
            )

            // Add contact name if available
            message.contactName?.let {
                messageData["contactName"] = it
            }

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
     * Returns a Flow that emits message data with message ID included
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
                val messageId = snapshot.key ?: return
                val messageData = snapshot.value as? Map<String, Any?> ?: return

                // Add message ID to the data
                val dataWithId = messageData.toMutableMap()
                dataWithId["_messageId"] = messageId
                dataWithId["_messageRef"] = snapshot.ref

                trySend(dataWithId)
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
     * Pair with token from web (phone scans QR code from website)
     */
    suspend fun pairWithToken(token: String): Boolean {
        try {
            val userId = getCurrentUserId()

            // Check if token exists in pending_pairings
            val pairingRef = database.reference
                .child("pending_pairings")
                .child(token)

            val snapshot = pairingRef.get().await()
            if (!snapshot.exists()) {
                throw Exception("Invalid or expired pairing token")
            }

            val tokenData = snapshot.value as? Map<String, Any?>
                ?: throw Exception("Invalid token data")

            // Check if token is expired (5 minutes)
            val expiresAt = tokenData["expiresAt"] as? Long ?: 0
            if (System.currentTimeMillis() > expiresAt) {
                pairingRef.removeValue().await()
                throw Exception("Pairing token has expired")
            }

            // Update the pending pairing with userId so web app knows pairing is complete
            pairingRef.updateChildren(mapOf(
                "userId" to userId,
                "completedAt" to ServerValue.TIMESTAMP
            )).await()

            // Add device to user's devices
            val deviceId = System.currentTimeMillis().toString()
            val deviceRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(DEVICES_PATH)
                .child(deviceId)

            val deviceData = mapOf(
                "name" to "Desktop",
                "type" to "web",
                "pairedAt" to ServerValue.TIMESTAMP,
                "lastSeen" to ServerValue.TIMESTAMP
            )

            deviceRef.setValue(deviceData).await()
            Log.d(TAG, "Device paired successfully")

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error pairing with token", e)
            throw e
        }
    }

    /**
     * Generate pairing token (OLD METHOD - for phone generating QR code)
     * Kept for backwards compatibility
     */
    suspend fun generatePairingToken(): String {
        val userId = getCurrentUserId()
        val timestamp = System.currentTimeMillis()

        // Use a simple, short token since the macOS app asks the user to paste it
        val token = (0..999999).random().toString().padStart(6, '0')

        // Store pending pairing in Firebase with enough context for the Mac to reuse this userId
        val pairingRef = database.reference
            .child("pending_pairings")
            .child(token)

        val pairingData = mapOf(
            "userId" to userId,
            "platform" to "macos",
            "token" to token,
            "createdAt" to ServerValue.TIMESTAMP,
            "expiresAt" to timestamp + (5 * 60 * 1000) // 5 minutes
        )

        pairingRef.setValue(pairingData).await()

        return token
    }

    /**
     * Get outgoing messages (snapshot, not listener)
     */
    suspend fun getOutgoingMessages(): Map<String, Map<String, Any?>> {
        try {
            val userId = getCurrentUserId()
            val outgoingRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child("outgoing_messages")

            val snapshot = outgoingRef.get().await()
            val messages = mutableMapOf<String, Map<String, Any?>>()

            snapshot.children.forEach { messageSnapshot ->
                val messageId = messageSnapshot.key ?: return@forEach
                val messageData = messageSnapshot.value as? Map<String, Any?> ?: return@forEach
                messages[messageId] = messageData
            }

            return messages
        } catch (e: Exception) {
            Log.e(TAG, "Error getting outgoing messages", e)
            return emptyMap()
        }
    }

    /**
     * Write sent message to messages collection
     */
    suspend fun writeSentMessage(messageId: String, address: String, body: String) {
        try {
            val userId = getCurrentUserId()
            val messageRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MESSAGES_PATH)
                .child(messageId)

            val messageData = mapOf(
                "id" to messageId,
                "address" to address,
                "body" to body,
                "date" to System.currentTimeMillis(),
                "type" to 2, // 2 = sent message
                "timestamp" to ServerValue.TIMESTAMP
            )

            messageRef.setValue(messageData).await()
            Log.d(TAG, "Sent message written to messages collection")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing sent message", e)
        }
    }

    /**
     * Delete outgoing message after processing
     */
    suspend fun deleteOutgoingMessage(messageId: String) {
        try {
            val userId = getCurrentUserId()
            val messageRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child("outgoing_messages")
                .child(messageId)

            messageRef.removeValue().await()
            Log.d(TAG, "Outgoing message deleted: $messageId")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting outgoing message", e)
        }
    }

    /**
     * Sync a group to Firebase
     */
    suspend fun syncGroup(group: Group, members: List<GroupMember>) {
        try {
            val userId = getCurrentUserId()
            val groupRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(GROUPS_PATH)
                .child(group.id.toString())

            val groupData = mutableMapOf<String, Any?>(
                "id" to group.id,
                "name" to group.name,
                "threadId" to group.threadId,
                "createdAt" to group.createdAt,
                "lastMessageAt" to group.lastMessageAt,
                "timestamp" to ServerValue.TIMESTAMP
            )

            // Add members as a nested map
            val membersData = members.associate {
                it.id.toString() to mapOf(
                    "phoneNumber" to it.phoneNumber,
                    "contactName" to (it.contactName ?: it.phoneNumber)
                )
            }
            groupData["members"] = membersData

            groupRef.setValue(groupData).await()
            Log.d(TAG, "Group synced: ${group.id} (${group.name})")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing group", e)
        }
    }

    /**
     * Sync all groups to Firebase
     */
    suspend fun syncGroups(groupsWithMembers: List<com.phoneintegration.app.data.database.GroupWithMembers>) {
        for (groupWithMembers in groupsWithMembers) {
            syncGroup(groupWithMembers.group, groupWithMembers.members)
        }
    }

    /**
     * Delete a group from Firebase
     */
    suspend fun deleteGroup(groupId: Long) {
        try {
            val userId = getCurrentUserId()
            val groupRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(GROUPS_PATH)
                .child(groupId.toString())

            groupRef.removeValue().await()
            Log.d(TAG, "Group deleted from Firebase: $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting group from Firebase", e)
        }
    }

    /**
     * Sync a call event to Firebase
     */
    suspend fun syncCallEvent(
        callId: String,
        phoneNumber: String,
        contactName: String?,
        callType: String, // "incoming" or "outgoing"
        callState: String  // "ringing", "active", "ended"
    ) {
        try {
            val userId = getCurrentUserId()
            val callRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child("calls")
                .child(callId)

            val callData = mutableMapOf<String, Any?>(
                "id" to callId,
                "phoneNumber" to phoneNumber,
                "callType" to callType,
                "callState" to callState,
                "timestamp" to ServerValue.TIMESTAMP,
                "date" to System.currentTimeMillis()
            )

            // Add contact name if available
            contactName?.let {
                callData["contactName"] = it
            }

            callRef.setValue(callData).await()
            Log.d(TAG, "Call event synced: $callId - $callState")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing call event", e)
        }
    }

    /**
     * Update call state in Firebase
     */
    suspend fun updateCallState(callId: String, newState: String) {
        try {
            val userId = getCurrentUserId()
            val callRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child("calls")
                .child(callId)

            callRef.updateChildren(mapOf(
                "callState" to newState,
                "lastUpdated" to ServerValue.TIMESTAMP
            )).await()

            Log.d(TAG, "Call state updated: $callId -> $newState")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating call state", e)
        }
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
