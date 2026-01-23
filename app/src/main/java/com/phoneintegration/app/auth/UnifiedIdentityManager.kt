package com.phoneintegration.app.auth

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.*
import com.phoneintegration.app.PhoneNumberUtils
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.SmsRepository
import com.phoneintegration.app.security.SecurityEvent
import com.phoneintegration.app.security.SecurityEventType
import com.phoneintegration.app.security.SecurityMonitor
import com.phoneintegration.app.sync.SyncGroupManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Unified Identity Manager - Single Firebase user across all devices
 *
 * This solves the Firebase resource wastage by ensuring all devices share
 * the same authenticated user identity with device-scoped data storage.
 */
class UnifiedIdentityManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UnifiedIdentityManager"
        private const val PAIRING_TOKEN_EXPIRY_MINUTES = 5L
        private const val DEVICE_HEARTBEAT_INTERVAL_MINUTES = 5L

        @Volatile
        private var instance: UnifiedIdentityManager? = null

        fun getInstance(context: Context): UnifiedIdentityManager {
            return instance ?: synchronized(this) {
                instance ?: UnifiedIdentityManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val securityMonitor: SecurityMonitor? = try {
        SecurityMonitor.getInstance(context)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to initialize SecurityMonitor, continuing without it", e)
        null
    }
    private val syncGroupManager = SyncGroupManager(context, database)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Device management
    private val _pairedDevices = MutableStateFlow<Map<String, DeviceInfo>>(emptyMap())
    val pairedDevices: StateFlow<Map<String, DeviceInfo>> = _pairedDevices.asStateFlow()

    // Sync group info
    private val _syncGroupId = MutableStateFlow<String?>(syncGroupManager.syncGroupId)
    val syncGroupId: StateFlow<String?> = _syncGroupId.asStateFlow()

    private var deviceHeartbeatJob: Job? = null
    private var deviceMonitorJob: Job? = null

    init {
        startDeviceMonitoring()
        startDeviceHeartbeat()
    }

    /**
     * Get the unified user ID synchronously (for system services)
     */
    fun getUnifiedUserIdSync(): String? {
        return auth.currentUser?.uid
    }

    /**
     * Get the unified user ID (same across all devices)
     * Falls back to anonymous auth for testing if no authenticated user
     */
    suspend fun getUnifiedUserId(): String? {
        Log.d(TAG, "getUnifiedUserId() called")
        var user = auth.currentUser
        Log.d(TAG, "Current user: ${user?.uid}, isAnonymous: ${user?.isAnonymous}")

        // If no authenticated user, try anonymous authentication for testing
        if (user == null) {
            try {
                Log.d(TAG, "No authenticated user found, attempting anonymous authentication for testing")
                val result = auth.signInAnonymously().await()
                user = result.user
                Log.d(TAG, "Anonymous authentication result - user: ${user?.uid}, isAnonymous: ${user?.isAnonymous}")
                if (user != null) {
                    Log.d(TAG, "Anonymous authentication successful: ${user.uid}")
                } else {
                    Log.e(TAG, "Anonymous authentication returned null user")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Anonymous authentication failed", e)
                return null
            }
        }

        val finalUserId = user?.uid
        Log.d(TAG, "Returning user ID: $finalUserId")
        return finalUserId
    }

    /**
     * Redeem a pairing token and register the device
     */
    suspend fun redeemPairingToken(token: String, deviceName: String, platform: String): Result<DeviceInfo> {
        return try {
            Log.d(TAG, "Redeeming pairing token: $token")

            val pairingRef = database.getReference("pending_pairings").child(token)
            val snapshot = pairingRef.get().await()

            if (!snapshot.exists()) {
                return Result.failure(Exception("Pairing token not found or expired"))
            }

            val pairingData = snapshot.value as? Map<*, *>
                ?: return Result.failure(Exception("Invalid pairing data"))

            // Check expiration
            val expiresAt = pairingData["expiresAt"] as? Long ?: 0L
            val currentTime = System.currentTimeMillis()
            if (currentTime > expiresAt) {
                Log.e(TAG, "Token has expired. Expires: $expiresAt, Current: $currentTime")
                throw Exception("Pairing token has expired")
            }

            Log.d(TAG, "Registering device: $deviceName ($platform) for user: $token")

            // Register this device under the unified user account
            val deviceInfo = registerDevice(
                userId = token, // This will be the unified user ID
                deviceId = "dev_${System.currentTimeMillis()}",
                deviceName = deviceName,
                deviceType = platform,
                platform = platform
            )

            Log.d(TAG, "Device registered successfully: ${deviceInfo.name} (${deviceInfo.id})")

            // Try to recover existing sync group, or create new one
            val syncGroupResult = syncGroupManager.recoverSyncGroup().getOrNull()
            if (syncGroupResult != null) {
                Log.d(TAG, "Recovered existing sync group: $syncGroupResult")
                _syncGroupId.value = syncGroupResult
            } else {
                // No existing group, create new one
                val createResult = syncGroupManager.createSyncGroup(deviceName)
                if (createResult.isSuccess) {
                    val newGroupId = createResult.getOrNull() ?: return Result.failure(Exception("Failed to create sync group"))
                    Log.d(TAG, "Created new sync group: $newGroupId")
                    _syncGroupId.value = newGroupId
                } else {
                    Log.w(TAG, "Failed to create sync group: ${createResult.exceptionOrNull()}")
                    // Continue anyway, sync group creation is not blocking
                }
            }

            // Mark token as used
            pairingRef.child("status").setValue("completed").await()
            pairingRef.child("completedAt").setValue(System.currentTimeMillis()).await()
            pairingRef.child("completedDevice").setValue(deviceName).await()

            // Schedule token removal after a delay
            scope.launch {
                delay(TimeUnit.MINUTES.toMillis(1))
                try {
                    pairingRef.removeValue().await()
                } catch (e: Exception) {
                    Log.d(TAG, "Token already removed")
                }
            }

            Log.i(TAG, "Successfully redeemed pairing token. Device joined user account")
            Result.success(deviceInfo)

        } catch (e: Exception) {
            Log.e(TAG, "Error redeeming pairing token", e)
            Result.failure(e)
        }
    }

    /**
     * Register a device under the unified user account
     */
    private suspend fun registerDevice(
        userId: String,
        deviceId: String,
        deviceName: String,
        deviceType: String,
        platform: String
    ): DeviceInfo {
        val deviceRef = database.getReference("users")
            .child(userId)
            .child("devices")
            .child(deviceId)

        val deviceData = mapOf(
            "id" to deviceId,
            "name" to deviceName,
            "type" to deviceType,
            "platform" to platform,
            "registeredAt" to System.currentTimeMillis(),
            "lastSeen" to System.currentTimeMillis(),
            "isOnline" to true,
            "capabilities" to getDeviceCapabilities()
        )

        deviceRef.setValue(deviceData).await()

        val deviceInfo = DeviceInfo(
            id = deviceId,
            name = deviceName,
            type = deviceType,
            platform = platform,
            lastSeen = System.currentTimeMillis(),
            isOnline = true,
            registeredAt = System.currentTimeMillis()
        )

        // For newly paired devices, trigger initial message sync
        if (deviceType != "android") { // Don't sync to Android devices (they already have messages)
            scope.launch {
                syncLast30DaysMessages(userId, deviceId, deviceName)
            }
        }

        return deviceInfo
    }

    /**
     * Unregister a device from the unified account
     */
    suspend fun unregisterDevice(deviceId: String): Result<Unit> {
        return try {
            val userId = getUnifiedUserId() ?: return Result.failure(Exception("No authenticated user"))
            val deviceRef = database.getReference("users").child(userId).child("devices").child(deviceId)
            deviceRef.removeValue().await()
            Log.d(TAG, "Device unregistered: $deviceId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering device", e)
            Result.failure(e)
        }
    }

    /**
     * Join a sync group by scanning QR code from another device
     * Called when user scans QR code containing sync group ID
     */
    suspend fun joinSyncGroupFromQRCode(scannedSyncGroupId: String, deviceName: String): Result<JoinSyncGroupResult> {
        return try {
            Log.d(TAG, "Attempting to join sync group: $scannedSyncGroupId")

            // CRITICAL: Ensure Firebase authentication before attempting to join sync group
            // The Firebase rules require auth != null to read/write syncGroups
            val userId = getUnifiedUserId()
            if (userId == null) {
                Log.e(TAG, "Failed to authenticate with Firebase, cannot join sync group")
                return Result.failure(Exception("Firebase authentication failed"))
            }
            Log.d(TAG, "Authenticated as: $userId, proceeding with sync group join")

            val result = syncGroupManager.joinSyncGroup(scannedSyncGroupId, deviceName)

            if (result.isSuccess) {
                val joinResult = result.getOrNull() ?: return Result.failure(Exception("Failed to join sync group"))
                Log.i(TAG, "Successfully joined sync group. Device count: ${joinResult.deviceCount}/${joinResult.limit}")
                _syncGroupId.value = scannedSyncGroupId

                return Result.success(
                    JoinSyncGroupResult(
                        success = true,
                        syncGroupId = scannedSyncGroupId,
                        deviceCount = joinResult.deviceCount,
                        deviceLimit = joinResult.limit,
                        message = "Connected! Using ${joinResult.deviceCount}/${joinResult.limit} devices"
                    )
                )
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                Log.e(TAG, "Failed to join sync group: $error")

                // Check if it's a device limit error
                val isLimitError = error.contains("Device limit reached")
                return Result.success(
                    JoinSyncGroupResult(
                        success = false,
                        syncGroupId = scannedSyncGroupId,
                        deviceCount = 0,
                        deviceLimit = 0,
                        message = error,
                        isDeviceLimitError = isLimitError
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error joining sync group", e)
            Result.failure(e)
        }
    }

    /**
     * Get sync group info
     */
    suspend fun getSyncGroupInfo(): Result<SyncGroupInfo> {
        return try {
            val result = syncGroupManager.getSyncGroupInfo()
            if (result.isSuccess) {
                val info = result.getOrNull() ?: return Result.failure(Exception("No sync group info"))
                return Result.success(
                    SyncGroupInfo(
                        plan = info.plan,
                        deviceLimit = info.deviceLimit,
                        deviceCount = info.deviceCount,
                        devices = info.devices.map {
                            SyncGroupDeviceInfo(
                                deviceId = it.deviceId,
                                deviceType = it.deviceType,
                                joinedAt = it.joinedAt,
                                lastSyncedAt = it.lastSyncedAt,
                                status = it.status
                            )
                        }
                    )
                )
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Failed to get sync group info"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting sync group info", e)
            Result.failure(e)
        }
    }

    /**
     * Get device capabilities for this Android device
     */
    private fun getDeviceCapabilities(): Map<String, Any> {
        return mapOf(
            "sms" to true,
            "mms" to true,
            "calls" to true,
            "contacts" to true,
            "battery" to true,
            "notifications" to true,
            "media" to true,
            "clipboard" to true,
            "hotspot" to true,
            "wifi" to true,
            "location" to true
        )
    }

    /**
     * Sync recent messages to a newly paired device (optimized for performance)
     * Limits to recent conversations with reasonable message counts
     */
    private suspend fun syncLast30DaysMessages(userId: String, deviceId: String, deviceName: String) {
        try {
            Log.d(TAG, "Starting optimized message sync to device: $deviceId ($deviceName)")

            val context = this@UnifiedIdentityManager.context
            val smsRepository = SmsRepository(context)
            val database = FirebaseDatabase.getInstance()

            // Update sync status
            updateSyncStatus(userId, deviceId, "starting", 0, 0)

            // Get recent messages (limit to prevent performance issues)
            val messages = smsRepository.getAllRecentMessages(500) // Reasonable limit for initial sync
            Log.d(TAG, "Found ${messages.size} recent messages to evaluate")

            // Filter to last 7 days only for initial sync (much more reasonable)
            val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            val recentMessages = messages.filter { it.date > sevenDaysAgo }

            Log.d(TAG, "Filtered to ${recentMessages.size} messages from last 7 days")

            if (recentMessages.isEmpty()) {
                Log.d(TAG, "No recent messages to sync")
                updateSyncStatus(userId, deviceId, "completed", 0, 0)
                return
            }

            var syncedCount = 0
            var errorCount = 0

            // Group messages by conversation
            val conversationMap = mutableMapOf<String, MutableList<SmsMessage>>()
            for (message in recentMessages) {
                val conversationKey = PhoneNumberUtils.normalizeForConversation(message.address)
                conversationMap.getOrPut(conversationKey) { mutableListOf() }.add(message)
            }

            // Limit conversations to most active ones (top 20 by message count)
            val topConversations = conversationMap.entries
                .sortedByDescending { it.value.size }
                .take(20) // Limit to 20 most active conversations

            Log.d(TAG, "Syncing ${topConversations.size} most active conversations")

            val totalConversations = topConversations.size
            var processedConversations = 0

            // Sync each conversation (limit messages per conversation)
            for ((conversationKey, conversationMessages) in topConversations) {
                try {
                    processedConversations++

                    // Sort messages by date and limit to recent 50 per conversation
                    val sortedMessages = conversationMessages
                        .sortedBy { it.date }
                        .takeLast(50) // Keep most recent 50 messages per conversation

                    // Create/update conversation document
                    val conversationRef = database.getReference("users")
                        .child(userId)
                        .child("conversations")
                        .child(conversationKey)

                    val firstMessage = sortedMessages.firstOrNull()
                    val lastMessage = sortedMessages.lastOrNull()

                    val conversationData = mapOf(
                        "id" to conversationKey,
                        "displayName" to (firstMessage?.address ?: conversationKey),
                        "lastMessage" to (lastMessage?.body ?: ""),
                        "lastMessageTime" to (lastMessage?.date ?: System.currentTimeMillis()),
                        "messageCount" to sortedMessages.size,
                        "updatedAt" to System.currentTimeMillis(),
                        "syncedAt" to System.currentTimeMillis()
                    )

                    conversationRef.setValue(conversationData).await()

                    // Sync individual messages in batches to avoid overwhelming Firebase
                    val batchSize = 10
                    for (batch in sortedMessages.chunked(batchSize)) {
                        val batchPromises = batch.map { message ->
                            try {
                                val messageData = mapOf(
                                    "id" to message.id.toString(),
                                    "address" to message.address,
                                    "body" to (message.body ?: ""),
                                    "date" to message.date,
                                    "type" to message.type,
                                    "timestamp" to ServerValue.TIMESTAMP,
                                    "conversationId" to conversationKey,
                                    "isMms" to false,
                                    "read" to true,
                                    "syncedAt" to System.currentTimeMillis()
                                )

                                val messageRef = database.getReference("users")
                                    .child(userId)
                                    .child("messages")
                                    .child(message.id.toString())

                                messageRef.setValue(messageData).await()
                                syncedCount++
                                updateSyncStatus(userId, deviceId, "syncing",
                                    syncedCount, totalConversations * 50) // Estimate

                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to sync message ${message.id}", e)
                                errorCount++
                            }
                        }

                        // Wait for batch to complete
                        batchPromises.forEach { it }

                        // Small delay between batches to prevent overwhelming
                        kotlinx.coroutines.delay(100)
                    }

                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync conversation $conversationKey", e)
                    errorCount++
                }
            }

            // Mark sync as completed
            updateSyncStatus(userId, deviceId, "completed", syncedCount, syncedCount)

            Log.d(TAG, "Message sync completed for device: $deviceId - Synced: $syncedCount, Errors: $errorCount, Conversations: $processedConversations")

        } catch (e: Exception) {
            Log.e(TAG, "Error syncing messages to device", e)
            try {
                updateSyncStatus(userId, deviceId, "failed", 0, 0)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to update sync status after error", e2)
            }
        }
    }

    /**
     * Update sync status for a device
     */
    private suspend fun updateSyncStatus(userId: String, deviceId: String, status: String, syncedCount: Int, totalCount: Int) {
        try {
            val statusRef = database.getReference("users")
                .child(userId)
                .child("devices")
                .child(deviceId)
                .child("syncStatus")

            val statusData = mapOf(
                "status" to status,
                "syncedMessages" to syncedCount,
                "totalMessages" to totalCount,
                "lastSyncAttempt" to System.currentTimeMillis(),
                "lastSyncCompleted" to if (status == "completed") System.currentTimeMillis() else null
            )

            statusRef.setValue(statusData).await()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update sync status", e)
        }
    }

    /**
     * Start monitoring device online/offline status
     */
    private fun startDeviceMonitoring() {
        deviceMonitorJob = scope.launch {
            while (isActive) {
                try {
                    updateDeviceStatuses()
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring devices", e)
                }
                delay(TimeUnit.MINUTES.toMillis(1))
            }
        }
    }

    /**
     * Update device online/offline statuses
     */
    private suspend fun updateDeviceStatuses() {
        try {
            val userId = getUnifiedUserId() ?: return
            val devicesRef = database.getReference("users").child(userId).child("devices")
            val snapshot = devicesRef.get().await()

            if (snapshot.exists()) {
                val devices = mutableMapOf<String, DeviceInfo>()
                val deviceData = snapshot.value as? Map<*, *>

                deviceData?.forEach { (deviceId, data) ->
                    val deviceMap = data as? Map<*, *>
                    if (deviceMap != null) {
                        val deviceInfo = DeviceInfo(
                            id = deviceId as String,
                            name = deviceMap["name"] as? String ?: "Unknown",
                            type = deviceMap["type"] as? String ?: "unknown",
                            platform = deviceMap["platform"] as? String ?: "unknown",
                            lastSeen = deviceMap["lastSeen"] as? Long ?: 0L,
                            isOnline = deviceMap["isOnline"] as? Boolean ?: false,
                            registeredAt = deviceMap["registeredAt"] as? Long ?: 0L
                        )
                        devices[deviceId] = deviceInfo
                    }
                }

                _pairedDevices.value = devices
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating device statuses", e)
        }
    }

    /**
     * Start device heartbeat to keep this device marked as online
     */
    private fun startDeviceHeartbeat() {
        deviceHeartbeatJob = scope.launch {
            while (isActive) {
                try {
                    updateDeviceHeartbeat()
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending heartbeat", e)
                }
                delay(TimeUnit.MINUTES.toMillis(DEVICE_HEARTBEAT_INTERVAL_MINUTES))
            }
        }
    }

    /**
     * Update this device's heartbeat timestamp
     */
    private suspend fun updateDeviceHeartbeat() {
        try {
            val userId = getUnifiedUserId() ?: return
            val deviceId = "android_${android.os.Build.DEVICE}" // Use device identifier

            val deviceRef = database.getReference("users")
                .child(userId)
                .child("devices")
                .child(deviceId)
                .child("lastSeen")

            deviceRef.setValue(System.currentTimeMillis()).await()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating heartbeat", e)
        }
    }

    /**
     * Trigger initial message sync for first-time users
     * This can be called manually to sync existing messages to newly paired devices
     */
    fun triggerInitialMessageSync() {
        scope.launch {
            try {
                val userId = getUnifiedUserId() ?: return@launch
                Log.d(TAG, "Triggering initial message sync for user: $userId")

                // Get all paired devices
                val devicesRef = database.getReference("users").child(userId).child("devices")
                val devicesSnapshot = devicesRef.get().await()

                if (devicesSnapshot.exists()) {
                    val devices = devicesSnapshot.value as? Map<*, *>
                    devices?.forEach { (deviceId, deviceData) ->
                        val data = deviceData as? Map<*, *>
                        val deviceName = data?.get("name") as? String ?: "Unknown Device"
                        val platform = data?.get("platform") as? String ?: "unknown"

                        // Only sync to non-Android devices (they already have the messages)
                        if (platform != "android") {
                            Log.d(TAG, "Syncing messages to device: $deviceId ($deviceName)")
                            syncLast30DaysMessages(userId, deviceId as String, deviceName)
                        }
                    }
                }

                Log.d(TAG, "Initial message sync completed for user: $userId")
            } catch (e: Exception) {
                Log.e(TAG, "Error triggering initial message sync", e)
            }
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        deviceHeartbeatJob?.cancel()
        deviceMonitorJob?.cancel()
        scope.cancel()
        Log.i(TAG, "UnifiedIdentityManager cleaned up")
    }
}

/**
 * Data classes for unified identity management
 */
data class DeviceInfo(
    val id: String,
    val name: String,
    val type: String,
    val platform: String,
    val lastSeen: Long,
    val isOnline: Boolean,
    val registeredAt: Long
)

data class PairingToken(
    val token: String,
    val userId: String,
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val expiresAt: Long
)

/**
 * Result of joining a sync group
 */
data class JoinSyncGroupResult(
    val success: Boolean,
    val syncGroupId: String,
    val deviceCount: Int,
    val deviceLimit: Int,
    val message: String,
    val isDeviceLimitError: Boolean = false
)

/**
 * Sync group information
 */
data class SyncGroupInfo(
    val plan: String,
    val deviceLimit: Int,
    val deviceCount: Int,
    val devices: List<SyncGroupDeviceInfo>
)

/**
 * Device info within a sync group
 */
data class SyncGroupDeviceInfo(
    val deviceId: String,
    val deviceType: String,
    val joinedAt: Long,
    val lastSyncedAt: Long?,
    val status: String
)