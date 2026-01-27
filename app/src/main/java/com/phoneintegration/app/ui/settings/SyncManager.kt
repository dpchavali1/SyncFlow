package com.phoneintegration.app.ui.settings

import android.content.Context
import android.util.Log
import com.phoneintegration.app.SmsRepository
import com.phoneintegration.app.desktop.DesktopSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Singleton manager for message history sync.
 * Persists sync state across screen navigations.
 */
object SyncManager {
    private const val TAG = "SyncManager"
    private const val SYNC_TIMEOUT_MS = 15_000L // 15 seconds per message

    data class SyncState(
        val isSyncing: Boolean = false,
        val progress: Float = 0f,
        val status: String = "",
        val syncedCount: Int = 0,
        val totalCount: Int = 0,
        val isComplete: Boolean = false,
        val error: String? = null
    )

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // Use SupervisorJob so sync continues even if one part fails
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var smsRepository: SmsRepository? = null
    private var desktopSyncService: DesktopSyncService? = null

    private fun ensureInitialized(context: Context) {
        if (smsRepository == null) {
            smsRepository = SmsRepository(context.applicationContext)
        }
        if (desktopSyncService == null) {
            desktopSyncService = DesktopSyncService(context.applicationContext)
        }
    }

    /**
     * Start syncing messages for the specified number of days.
     * Sync continues even if user navigates away from the screen.
     */
    fun startSync(context: Context, days: Int) {
        if (_syncState.value.isSyncing) {
            Log.d(TAG, "Sync already in progress, ignoring request")
            return
        }

        ensureInitialized(context)

        scope.launch {
            try {
                _syncState.value = SyncState(
                    isSyncing = true,
                    status = "Loading messages..."
                )

                // Load messages on IO dispatcher
                val daysToLoad = if (days <= 0) 3650 else days
                val messages = withContext(Dispatchers.IO) {
                    smsRepository!!.getMessagesFromLastDays(daysToLoad)
                }

                val totalCount = messages.size
                Log.d(TAG, "Loaded $totalCount messages to sync")

                if (totalCount == 0) {
                    _syncState.value = SyncState(
                        isSyncing = false,
                        status = "No messages to sync",
                        isComplete = true
                    )
                    return@launch
                }

                _syncState.value = _syncState.value.copy(
                    status = "Syncing $totalCount messages...",
                    totalCount = totalCount
                )

                // Sync all messages (SMS and MMS)
                var syncedCount = 0
                var errorCount = 0
                val smsCount = messages.count { !it.isMms }
                val mmsCount = messages.count { it.isMms }

                Log.d(TAG, "Starting sync: $smsCount SMS + $mmsCount MMS = $totalCount total")

                for ((index, message) in messages.withIndex()) {
                    try {
                        // Use NonCancellable to ensure sync completes
                        withContext(NonCancellable + Dispatchers.IO) {
                            withTimeout(SYNC_TIMEOUT_MS) {
                                desktopSyncService!!.syncMessage(
                                    message = message,
                                    skipAttachments = true // Skip attachments for history sync
                                )
                            }
                        }
                        syncedCount++
                    } catch (e: TimeoutCancellationException) {
                        errorCount++
                        Log.w(TAG, "Timeout syncing message ${message.id} (${if (message.isMms) "MMS" else "SMS"})")
                    } catch (e: Exception) {
                        errorCount++
                        Log.w(TAG, "Failed to sync message ${message.id}: ${e.message}")
                    }

                    // Update UI every 10 messages or on last message
                    if ((index + 1) % 10 == 0 || index == messages.lastIndex) {
                        _syncState.value = _syncState.value.copy(
                            syncedCount = syncedCount,
                            progress = (index + 1).toFloat() / totalCount.toFloat(),
                            status = "Syncing... ${index + 1} / $totalCount"
                        )
                        Log.d(TAG, "Progress: ${index + 1}/$totalCount (synced: $syncedCount, errors: $errorCount)")
                    }
                }

                Log.i(TAG, "Sync completed: $syncedCount messages synced, $errorCount errors")
                val statusMsg = buildString {
                    append("Completed! Synced $syncedCount messages")
                    if (errorCount > 0) append(" ($errorCount failed)")
                }
                _syncState.value = _syncState.value.copy(
                    isSyncing = false,
                    syncedCount = syncedCount,
                    progress = 1f,
                    status = statusMsg,
                    isComplete = true
                )

            } catch (e: Exception) {
                Log.e(TAG, "Sync failed: ${e.message}", e)
                _syncState.value = _syncState.value.copy(
                    isSyncing = false,
                    status = "Failed: ${e.message}",
                    error = e.message
                )
            }
        }
    }

    /**
     * Reset the sync state to allow another sync
     */
    fun resetState() {
        if (!_syncState.value.isSyncing) {
            _syncState.value = SyncState()
        }
    }
}
