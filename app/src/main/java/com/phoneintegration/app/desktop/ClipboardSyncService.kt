package com.phoneintegration.app.desktop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Service that syncs clipboard content between Android and macOS/desktop.
 * Supports text content syncing in real-time.
 */
class ClipboardSyncService(context: Context) {
    private val appContext: Context = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    private val clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var clipboardValueListener: ValueEventListener? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track last synced content to avoid loops
    private var lastSyncedContent: String? = null
    private var lastSyncedTimestamp: Long = 0
    private var isUpdatingFromRemote = false

    companion object {
        private const val TAG = "ClipboardSyncService"
        private const val CLIPBOARD_PATH = "clipboard"
        private const val USERS_PATH = "users"
        private const val MAX_CLIPBOARD_LENGTH = 50000 // 50KB max for text
        private const val SYNC_DEBOUNCE_MS = 500L
    }

    /**
     * Data class representing clipboard content
     */
    data class ClipboardContent(
        val text: String,
        val timestamp: Long,
        val source: String, // "android" or "macos"
        val type: String = "text" // Currently only "text" supported
    )

    /**
     * Start clipboard sync - monitors local clipboard and listens for remote changes
     */
    fun startSync() {
        Log.d(TAG, "Starting clipboard sync")
        database.goOnline()
        stopListeningForLocalClipboard()
        stopListeningForRemoteClipboard()
        startListeningForLocalClipboard()
        startListeningForRemoteClipboard()
    }

    /**
     * Stop clipboard sync
     */
    fun stopSync() {
        Log.d(TAG, "Stopping clipboard sync")
        stopListeningForLocalClipboard()
        stopListeningForRemoteClipboard()
        scope.cancel()
    }

    /**
     * Register listener for local clipboard changes
     */
    private fun startListeningForLocalClipboard() {
        stopListeningForLocalClipboard()
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            // Ignore if we're currently updating from remote
            if (isUpdatingFromRemote) {
                Log.d(TAG, "Ignoring clipboard change - updating from remote")
                return@OnPrimaryClipChangedListener
            }

            val clipData = clipboardManager.primaryClip
            if (clipData == null || clipData.itemCount == 0) return@OnPrimaryClipChangedListener

            val item = clipData.getItemAt(0)
            val text = item.text?.toString()

            if (text.isNullOrBlank()) return@OnPrimaryClipChangedListener
            if (text.length > MAX_CLIPBOARD_LENGTH) {
                Log.w(TAG, "Clipboard content too large (${text.length} chars), skipping sync")
                return@OnPrimaryClipChangedListener
            }

            // Debounce and check if content changed
            val now = System.currentTimeMillis()
            if (text == lastSyncedContent && now - lastSyncedTimestamp < 2000) {
                Log.d(TAG, "Clipboard content unchanged, skipping sync")
                return@OnPrimaryClipChangedListener
            }

            scope.launch {
                delay(SYNC_DEBOUNCE_MS) // Debounce
                syncClipboardToFirebase(text)
            }
        }

        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        Log.d(TAG, "Local clipboard listener registered")
    }

    private fun stopListeningForLocalClipboard() {
        clipboardListener?.let {
            clipboardManager.removePrimaryClipChangedListener(it)
        }
        clipboardListener = null
    }

    /**
     * Listen for clipboard changes from other devices
     */
    private fun startListeningForRemoteClipboard() {
        scope.launch {
            try {
                val currentUser = auth.currentUser ?: return@launch
                val userId = currentUser.uid

                val clipboardRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(CLIPBOARD_PATH)

                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val source = snapshot.child("source").value as? String ?: return

                        // Only process if from another device
                        if (source == "android") return

                        val text = snapshot.child("text").value as? String ?: return
                        val timestamp = snapshot.child("timestamp").value as? Long ?: return

                        // Check if this is newer than what we have
                        if (timestamp <= lastSyncedTimestamp) return

                        Log.d(TAG, "Received clipboard from $source: ${text.take(50)}...")

                        // Update local clipboard
                        updateLocalClipboard(text, timestamp)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Clipboard listener cancelled: ${error.message}")
                    }
                }

                clipboardValueListener = listener
                clipboardRef.addValueEventListener(listener)

                Log.d(TAG, "Remote clipboard listener registered")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting remote clipboard listener", e)
            }
        }
    }

    private fun stopListeningForRemoteClipboard() {
        clipboardValueListener?.let { listener ->
            scope.launch {
                try {
                    val userId = auth.currentUser?.uid ?: return@launch
                    database.reference
                        .child(USERS_PATH)
                        .child(userId)
                        .child(CLIPBOARD_PATH)
                        .removeEventListener(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing clipboard listener", e)
                }
            }
        }
        clipboardValueListener = null
    }

    /**
     * Sync local clipboard content to Firebase
     */
    private suspend fun syncClipboardToFirebase(text: String) {
        try {
            val currentUser = auth.currentUser ?: return
            val userId = currentUser.uid

            val clipboardRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(CLIPBOARD_PATH)

            val timestamp = System.currentTimeMillis()

            val clipboardData = mapOf(
                "text" to text,
                "timestamp" to timestamp,
                "source" to "android",
                "type" to "text"
            )

            clipboardRef.setValue(clipboardData).await()

            lastSyncedContent = text
            lastSyncedTimestamp = timestamp

            Log.d(TAG, "Clipboard synced to Firebase: ${text.take(50)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing clipboard", e)
        }
    }

    /**
     * Update local clipboard with remote content
     */
    private fun updateLocalClipboard(text: String, timestamp: Long) {
        try {
            isUpdatingFromRemote = true

            val clip = ClipData.newPlainText("SyncFlow", text)
            clipboardManager.setPrimaryClip(clip)

            lastSyncedContent = text
            lastSyncedTimestamp = timestamp

            Log.d(TAG, "Local clipboard updated from remote")

            // Reset flag after a short delay
            scope.launch {
                delay(500)
                isUpdatingFromRemote = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating local clipboard", e)
            isUpdatingFromRemote = false
        }
    }

    /**
     * Manually sync current clipboard
     */
    suspend fun syncNow() {
        val clipData = clipboardManager.primaryClip
        if (clipData == null || clipData.itemCount == 0) return

        val text = clipData.getItemAt(0).text?.toString()
        if (!text.isNullOrBlank() && text.length <= MAX_CLIPBOARD_LENGTH) {
            syncClipboardToFirebase(text)
        }
    }

    /**
     * Get current clipboard content
     */
    fun getCurrentClipboard(): String? {
        val clipData = clipboardManager.primaryClip
        if (clipData == null || clipData.itemCount == 0) return null
        return clipData.getItemAt(0).text?.toString()
    }
}
