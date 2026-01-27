package com.phoneintegration.app.services

import android.content.Context
import android.util.Log
import com.google.firebase.database.*
import com.phoneintegration.app.SmsRepository
import com.phoneintegration.app.auth.AuthManager
import com.phoneintegration.app.desktop.DesktopSyncService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Intelligent Sync Manager that provides seamless cross-platform messaging
 * while minimizing battery drain through adaptive strategies.
 *
 * Key Features:
 * - Real-time Firebase listeners for instant message delivery
 * - Adaptive sync intervals based on user activity and battery
 * - Smart batching to reduce device wake-ups
 * - Cross-platform state synchronization
 * - Battery-aware prioritization of sync features
 */
class IntelligentSyncManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "IntelligentSyncManager"
        private const val MIN_SYNC_INTERVAL = 30_000L  // 30 seconds
        private const val MAX_SYNC_INTERVAL = 30 * 60_000L  // 30 minutes
        private const val REALTIME_TIMEOUT = 5 * 60_000L  // 5 minutes of inactivity

        @Volatile
        private var instance: IntelligentSyncManager? = null

        fun getInstance(context: Context): IntelligentSyncManager {
            return instance ?: synchronized(this) {
                instance ?: IntelligentSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // Service states
    enum class SyncPriority {
        CRITICAL,    // Messages, calls - always real-time
        HIGH,        // Notifications, contacts - adaptive
        MEDIUM,      // Photos, media - batch when convenient
        LOW          // Analytics, logs - only when charging
    }

    private val authManager = AuthManager.getInstance(context)
    private val database = FirebaseDatabase.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Sync state flows
    private val _syncStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val syncStatus: StateFlow<Map<String, Boolean>> = _syncStatus.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastSyncTime: StateFlow<Map<String, Long>> = _lastSyncTime.asStateFlow()

    // Real-time listeners
    private var messageListener: ChildEventListener? = null
    private var callListener: ChildEventListener? = null
    private var notificationListener: ChildEventListener? = null
    private var syncRequestListener: ChildEventListener? = null
    private var messageListenerRef: com.google.firebase.database.Query? = null
    private var callListenerRef: DatabaseReference? = null
    private var notificationListenerRef: DatabaseReference? = null
    private var syncRequestListenerRef: DatabaseReference? = null
    private val appContext = context.applicationContext
    private val desktopSyncService by lazy { DesktopSyncService(appContext) }

    // Adaptive sync timers
    private var adaptiveSyncJob: Job? = null
    private var lastUserActivity = System.currentTimeMillis()

    // Battery awareness
    private val batteryManager = BatteryAwareServiceManager.getInstance(context)

    init {
        setupRealTimeListeners()
        startAdaptiveSync()
        monitorUserActivity()
    }

    /**
     * Setup real-time Firebase listeners for critical features
     */
    private fun setupRealTimeListeners() {
        scope.launch {
            try {
                val userId = authManager.getCurrentUserId() ?: return@launch

                // Real-time message listener (CRITICAL priority)
                setupMessageListener(userId)

                // Real-time call listener (CRITICAL priority)
                setupCallListener(userId)

                // Adaptive notification listener (HIGH priority)
                setupNotificationListener(userId)

                // Sync request listener (for loading older messages on demand)
                setupSyncRequestListener(userId)

                Log.i(TAG, "Real-time listeners established for user: $userId")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up real-time listeners", e)
            }
        }
    }

    private suspend fun setupMessageListener(userId: String) {
        // Listen only to the last N messages within the last 30 days to avoid OOM
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000 // 30 days in ms
        val messagesRef = database.getReference("users")
            .child(userId)
            .child("messages")
            .orderByChild("date")
            .startAt(cutoff.toDouble())
            .limitToLast(200)

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                if (!isSnapshotSafe(snapshot)) return
                handleNewMessage(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                if (!isSnapshotSafe(snapshot)) return
                handleMessageUpdate(snapshot)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                if (!isSnapshotSafe(snapshot)) return
                handleMessageDeletion(snapshot)
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Message listener cancelled", error.toException())
            }
        }

        messagesRef.addChildEventListener(listener)
        messageListener = listener
        messageListenerRef = messagesRef

        updateSyncStatus("messages", true)
    }

    private suspend fun setupCallListener(userId: String) {
        val callsRef = database.getReference("users").child(userId).child("active_calls")

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                handleIncomingCall(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                handleCallUpdate(snapshot)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                handleCallEnded(snapshot)
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Call listener cancelled", error.toException())
            }
        }

        callsRef.addChildEventListener(listener)
        callListener = listener
        callListenerRef = callsRef

        updateSyncStatus("calls", true)
    }

    private suspend fun setupNotificationListener(userId: String) {
        val notificationsRef = database.getReference("users").child(userId).child("notifications")

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                handleNewNotification(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Notification listener cancelled", error.toException())
            }
        }

        notificationsRef.addChildEventListener(listener)
        notificationListener = listener
        notificationListenerRef = notificationsRef

        updateSyncStatus("notifications", true)
    }

    /**
     * Listen for sync history requests from Mac/Web clients.
     * When a user requests older messages, this triggers the sync.
     */
    private suspend fun setupSyncRequestListener(userId: String) {
        val syncRequestsRef = database.getReference("users").child(userId).child("sync_requests")
        Log.d(TAG, "Setting up sync request listener at path: users/$userId/sync_requests")

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                Log.d(TAG, "onChildAdded triggered for sync_requests: key=${snapshot.key}, exists=${snapshot.exists()}")
                val requestId = snapshot.key
                if (requestId == null) {
                    Log.w(TAG, "Sync request has null key, skipping")
                    return
                }
                val data = snapshot.value as? Map<String, Any?>
                if (data == null) {
                    Log.w(TAG, "Sync request $requestId has invalid data format: ${snapshot.value}")
                    return
                }

                val status = data["status"] as? String ?: "pending"
                Log.d(TAG, "Sync request $requestId has status: $status")
                if (status != "pending") {
                    Log.d(TAG, "Skipping non-pending request $requestId")
                    return // Only process pending requests
                }

                val days = (data["days"] as? Number)?.toInt() ?: 30
                val requestedBy = data["requestedBy"] as? String ?: "unknown"

                Log.i(TAG, "Received sync history request: id=$requestId, days=$days, from=$requestedBy")

                // Process the request in background
                scope.launch {
                    try {
                        val request = com.phoneintegration.app.desktop.SyncHistoryRequest(
                            id = requestId,
                            days = days,
                            requestedAt = (data["requestedAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                            requestedBy = requestedBy
                        )
                        desktopSyncService.processSyncHistoryRequest(request)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing sync request $requestId", e)
                    }
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Sync request listener cancelled: ${error.message}", error.toException())
            }
        }

        syncRequestsRef.addChildEventListener(listener)
        syncRequestListener = listener
        syncRequestListenerRef = syncRequestsRef

        Log.i(TAG, "Sync request listener established at: users/$userId/sync_requests")
    }

    /**
     * Adaptive sync system that adjusts based on conditions
     */
    private fun startAdaptiveSync() {
        adaptiveSyncJob?.cancel()
        adaptiveSyncJob = scope.launch {
            while (isActive) {
                val interval = calculateAdaptiveInterval()
                delay(interval)

                performAdaptiveSync()
            }
        }
    }

    /**
     * Calculate optimal sync interval based on multiple factors
     */
    private fun calculateAdaptiveInterval(): Long {
        val batteryLevel = batteryManager.batteryLevel.value
        val isCharging = batteryManager.isCharging.value
        val isOnWifi = batteryManager.isOnWifi.value
        val timeSinceActivity = System.currentTimeMillis() - lastUserActivity

        return when {
            // User active - frequent sync
            timeSinceActivity < 60_000 -> MIN_SYNC_INTERVAL

            // Low battery - reduce frequency
            batteryLevel < 20 && !isCharging -> MAX_SYNC_INTERVAL

            // Good conditions - moderate sync
            isCharging || isOnWifi -> 5 * 60_000L  // 5 minutes

            // Normal conditions - balanced sync
            else -> 10 * 60_000L  // 10 minutes
        }.coerceIn(MIN_SYNC_INTERVAL, MAX_SYNC_INTERVAL)
    }

    /**
     * Perform adaptive sync based on priority and conditions
     */
    private suspend fun performAdaptiveSync() {
        val userId = authManager.getCurrentUserId() ?: return

        // Always sync critical data
        syncCriticalData(userId)

        // Sync high priority based on conditions
        if (shouldSyncHighPriority()) {
            syncHighPriorityData(userId)
        }

        // Sync medium priority only when optimal
        if (shouldSyncMediumPriority()) {
            syncMediumPriorityData(userId)
        }
    }

    private suspend fun syncCriticalData(userId: String) {
        // Sync read receipts, typing indicators, presence
        try {
            syncReadReceipts(userId)
            syncPresence(userId)
            updateSyncStatus("critical_sync", true)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing critical data", e)
        }
    }

    private suspend fun syncHighPriorityData(userId: String) {
        // Sync contacts, recent messages, notifications
        try {
            syncContacts(userId)
            syncRecentMessages(userId)
            updateSyncStatus("high_priority_sync", true)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing high priority data", e)
        }
    }

    private suspend fun syncMediumPriorityData(userId: String) {
        // Sync photos, media, analytics (only when conditions are good)
        try {
            if (shouldSyncPhotos()) {
                syncPhotos(userId)
            }
            if (shouldSyncMedia()) {
                syncMedia(userId)
            }
            updateSyncStatus("medium_priority_sync", true)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing medium priority data", e)
        }
    }

    /**
     * Smart decision making for when to sync different priorities
     */
    private fun shouldSyncHighPriority(): Boolean {
        val batteryLevel = batteryManager.batteryLevel.value
        val isCharging = batteryManager.isCharging.value
        val timeSinceLastSync = System.currentTimeMillis() - (_lastSyncTime.value["high_priority"] ?: 0)

        return when {
            isCharging -> true  // Always sync when charging
            batteryLevel > 30 -> timeSinceLastSync > 15 * 60_000  // 15 min on good battery
            batteryLevel > 15 -> timeSinceLastSync > 30 * 60_000  // 30 min on medium battery
            else -> timeSinceLastSync > 60 * 60_000  // 1 hour on low battery
        }
    }

    private fun shouldSyncMediumPriority(): Boolean {
        val isCharging = batteryManager.isCharging.value
        val isOnWifi = batteryManager.isOnWifi.value
        val batteryLevel = batteryManager.batteryLevel.value

        // Only sync media/photos when conditions are optimal
        return isCharging && isOnWifi && batteryLevel > 50
    }

    private fun shouldSyncPhotos(): Boolean {
        // Additional logic for photo sync (check if there are pending uploads, etc.)
        return true
    }

    private fun shouldSyncMedia(): Boolean {
        // Additional logic for media sync
        return true
    }

    /**
     * Monitor user activity to adjust sync frequency
     */
    private fun monitorUserActivity() {
        // This would be integrated with activity lifecycle callbacks
        // For now, we'll consider any sync operation as user activity
        lastUserActivity = System.currentTimeMillis()
    }

    /**
     * Cross-platform state synchronization
     */
    suspend fun syncCrossPlatformState(userId: String) {
        try {
            // Sync user preferences across devices
            syncUserPreferences(userId)

            // Sync conversation state (read receipts, etc.)
            syncConversationState(userId)

            // Sync device capabilities and status
            syncDeviceCapabilities(userId)

            Log.d(TAG, "Cross-platform state synchronized")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing cross-platform state", e)
        }
    }

    /**
     * Handle real-time message events
     */
    private fun handleNewMessage(snapshot: DataSnapshot) {
        scope.launch {
            try {
                // Process new message immediately
                val messageData = snapshot.value as? Map<String, Any> ?: return@launch
                processIncomingMessage(messageData)

                // Update activity timestamp
                lastUserActivity = System.currentTimeMillis()

            } catch (e: Exception) {
                Log.e(TAG, "Error handling new message", e)
            }
        }
    }

    private fun handleMessageUpdate(snapshot: DataSnapshot) {
        // Handle message updates (read receipts, reactions, etc.)
        scope.launch {
            try {
                val messageData = snapshot.value as? Map<String, Any> ?: return@launch
                processMessageUpdate(messageData)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling message update", e)
            }
        }
    }

    private fun handleMessageDeletion(snapshot: DataSnapshot) {
        // Handle message deletions
        scope.launch {
            try {
                val messageId = snapshot.key ?: return@launch
                processMessageDeletion(messageId)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling message deletion", e)
            }
        }
    }

    /**
     * Handle real-time call events
     */
    private fun handleIncomingCall(snapshot: DataSnapshot) {
        scope.launch {
            try {
                val callData = snapshot.value as? Map<String, Any> ?: return@launch
                processIncomingCall(callData)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming call", e)
            }
        }
    }

    private fun handleCallUpdate(snapshot: DataSnapshot) {
        scope.launch {
            try {
                val callData = snapshot.value as? Map<String, Any> ?: return@launch
                processCallUpdate(callData)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling call update", e)
            }
        }
    }

    private fun handleCallEnded(snapshot: DataSnapshot) {
        scope.launch {
            try {
                val callId = snapshot.key ?: return@launch
                processCallEnded(callId)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling call ended", e)
            }
        }
    }

    /**
     * Handle notification events
     */
    private fun handleNewNotification(snapshot: DataSnapshot) {
        scope.launch {
            try {
                val notificationData = snapshot.value as? Map<String, Any> ?: return@launch
                processNotification(notificationData)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling notification", e)
            }
        }
    }

    /**
     * Update sync status and timestamps
     */
    private fun updateSyncStatus(feature: String, active: Boolean) {
        val currentStatus = _syncStatus.value.toMutableMap()
        currentStatus[feature] = active
        _syncStatus.value = currentStatus

        if (active) {
            val currentTimes = _lastSyncTime.value.toMutableMap()
            currentTimes[feature] = System.currentTimeMillis()
            _lastSyncTime.value = currentTimes
        }
    }

    /**
     * Guard against oversized snapshots that can crash the client (OOM).
     * If the snapshot payload is >1MB, skip processing.
     */
    private fun isSnapshotSafe(snapshot: DataSnapshot): Boolean {
        return try {
            val raw = snapshot.value ?: return true
            val bytes = raw.toString().toByteArray(Charsets.UTF_8).size
            val safe = bytes < 1_000_000 // 1 MB
            if (!safe) {
                Log.w(TAG, "Skipping oversized snapshot (${bytes} bytes) at ${snapshot.ref.path}")
                android.widget.Toast.makeText(
                    appContext,
                    "Sync skipped: data too large. Please clear old messages.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            safe
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot size check failed, allowing", e)
            true
        }
    }

    /**
     * Placeholder methods for actual sync operations
     * These would be implemented to interface with existing services
     */
    private suspend fun syncReadReceipts(userId: String) { /* Implementation */ }
    private suspend fun syncPresence(userId: String) { /* Implementation */ }
    private suspend fun syncContacts(userId: String) { /* Implementation */ }
    private suspend fun syncRecentMessages(userId: String) { /* Implementation */ }
    private suspend fun syncPhotos(userId: String) { /* Implementation */ }
    private suspend fun syncMedia(userId: String) { /* Implementation */ }
    private suspend fun syncUserPreferences(userId: String) { /* Implementation */ }
    private suspend fun syncConversationState(userId: String) { /* Implementation */ }
    private suspend fun syncDeviceCapabilities(userId: String) { /* Implementation */ }

    private fun processIncomingMessage(messageData: Map<String, Any>) { /* Implementation */ }
    private fun processMessageUpdate(messageData: Map<String, Any>) { /* Implementation */ }
    private fun processMessageDeletion(messageId: String) {
        scope.launch {
            try {
                val localId = when {
                    messageId.startsWith("mms_") -> messageId.removePrefix("mms_").toLongOrNull()
                    else -> messageId.toLongOrNull()
                }
                if (localId == null) {
                    Log.w(TAG, "Cannot delete message with non-numeric id: $messageId")
                    return@launch
                }

                val repo = SmsRepository(context)
                val deleted = repo.deleteMessage(localId)
                Log.i(TAG, "Deleted message from device: $messageId (success=$deleted)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete message from device: $messageId", e)
            }
        }
    }
    private fun processIncomingCall(callData: Map<String, Any>) { /* Implementation */ }
    private fun processCallUpdate(callData: Map<String, Any>) { /* Implementation */ }
    private fun processCallEnded(callId: String) { /* Implementation */ }
    private fun processNotification(notificationData: Map<String, Any>) { /* Implementation */ }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.launch {
            try {
                // Remove Firebase listeners
                messageListener?.let { listener ->
                    messageListenerRef?.removeEventListener(listener)
                }
                callListener?.let { listener ->
                    callListenerRef?.removeEventListener(listener)
                }
                notificationListener?.let { listener ->
                    notificationListenerRef?.removeEventListener(listener)
                }

                // Clear references
                messageListener = null
                callListener = null
                notificationListener = null
                messageListenerRef = null
                callListenerRef = null
                notificationListenerRef = null

                // Cancel jobs
                adaptiveSyncJob?.cancel()

                // Update status
                updateSyncStatus("messages", false)
                updateSyncStatus("calls", false)
                updateSyncStatus("notifications", false)

                Log.i(TAG, "IntelligentSyncManager cleaned up")
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }
}
