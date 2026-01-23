package com.phoneintegration.app.realtime

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Typing status for a conversation
 */
data class TypingStatus(
    val conversationAddress: String,
    val isTyping: Boolean,
    val device: String, // "android", "macos", "web"
    val timestamp: Long
)

/**
 * Manager for real-time typing indicators across devices
 */
class TypingIndicatorManager(private val context: Context) {

    companion object {
        private const val TAG = "TypingIndicatorManager"
        private const val TYPING_PATH = "typing"
        private const val USERS_PATH = "users"
        private const val TYPING_TIMEOUT_MS = 5000L // 5 seconds
        private const val DEBOUNCE_MS = 300L
    }

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Debounce job for typing updates
    private var typingJob: Job? = null
    private var currentConversation: String? = null

    // Auto-clear job
    private var clearJob: Job? = null

    /**
     * Start typing indicator for a conversation
     */
    fun startTyping(conversationAddress: String) {
        typingJob?.cancel()

        typingJob = scope.launch {
            delay(DEBOUNCE_MS) // Debounce rapid typing

            try {
                val userId = auth.currentUser?.uid ?: return@launch
                currentConversation = conversationAddress

                val typingRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(TYPING_PATH)
                    .child(sanitizeAddress(conversationAddress))

                val typingData = mapOf(
                    "conversationAddress" to conversationAddress,
                    "isTyping" to true,
                    "device" to "android",
                    "timestamp" to ServerValue.TIMESTAMP
                )

                typingRef.setValue(typingData).await()

                // Auto-clear after timeout
                scheduleClear(conversationAddress)

            } catch (e: Exception) {
                Log.e(TAG, "Error setting typing status", e)
            }
        }
    }

    /**
     * Stop typing indicator
     */
    fun stopTyping(conversationAddress: String? = null) {
        typingJob?.cancel()
        clearJob?.cancel()

        val address = conversationAddress ?: currentConversation ?: return

        scope.launch {
            try {
                val userId = auth.currentUser?.uid ?: return@launch

                val typingRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(TYPING_PATH)
                    .child(sanitizeAddress(address))

                typingRef.removeValue().await()
                currentConversation = null

            } catch (e: Exception) {
                Log.e(TAG, "Error clearing typing status", e)
            }
        }
    }

    /**
     * Schedule auto-clear of typing indicator
     */
    private fun scheduleClear(conversationAddress: String) {
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(TYPING_TIMEOUT_MS)
            stopTyping(conversationAddress)
        }
    }

    /**
     * Observe typing status for a conversation (from other devices)
     */
    fun observeTypingStatus(conversationAddress: String): Flow<TypingStatus?> = callbackFlow {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val typingRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(TYPING_PATH)
            .child(sanitizeAddress(conversationAddress))

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(null)
                    return
                }

                try {
                    val device = snapshot.child("device").getValue(String::class.java) ?: "unknown"

                    // Don't show our own typing indicator
                    if (device == "android") {
                        trySend(null)
                        return
                    }

                    val isTyping = snapshot.child("isTyping").getValue(Boolean::class.java) ?: false
                    val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L

                    // Check if typing indicator is stale
                    if (System.currentTimeMillis() - timestamp > TYPING_TIMEOUT_MS * 2) {
                        trySend(null)
                        return
                    }

                    trySend(TypingStatus(
                        conversationAddress = conversationAddress,
                        isTyping = isTyping,
                        device = device,
                        timestamp = timestamp
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing typing status", e)
                    trySend(null)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Typing status listener cancelled", error.toException())
            }
        }

        typingRef.addValueEventListener(listener)

        awaitClose {
            typingRef.removeEventListener(listener)
        }
    }

    /**
     * Observe all typing statuses
     */
    fun observeAllTypingStatuses(): Flow<Map<String, TypingStatus>> = callbackFlow {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            trySend(emptyMap())
            close()
            return@callbackFlow
        }

        val typingRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(TYPING_PATH)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val statuses = mutableMapOf<String, TypingStatus>()

                snapshot.children.forEach { child ->
                    try {
                        val address = child.child("conversationAddress").getValue(String::class.java)
                            ?: return@forEach
                        val device = child.child("device").getValue(String::class.java) ?: "unknown"

                        // Don't include our own typing
                        if (device == "android") return@forEach

                        val isTyping = child.child("isTyping").getValue(Boolean::class.java) ?: false
                        val timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L

                        // Skip stale entries
                        if (System.currentTimeMillis() - timestamp > TYPING_TIMEOUT_MS * 2) return@forEach

                        if (isTyping) {
                            statuses[address] = TypingStatus(
                                conversationAddress = address,
                                isTyping = true,
                                device = device,
                                timestamp = timestamp
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing typing status", e)
                    }
                }

                trySend(statuses)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Typing statuses listener cancelled", error.toException())
            }
        }

        typingRef.addValueEventListener(listener)

        awaitClose {
            typingRef.removeEventListener(listener)
        }
    }

    /**
     * Clear all typing indicators (call on app close)
     */
    fun clearAllTyping() {
        scope.launch {
            try {
                val userId = auth.currentUser?.uid ?: return@launch

                database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child(TYPING_PATH)
                    .removeValue()
                    .await()

            } catch (e: Exception) {
                Log.e(TAG, "Error clearing all typing", e)
            }
        }
    }

    /**
     * Sanitize phone address for use as Firebase key
     */
    private fun sanitizeAddress(address: String): String {
        return address.replace(Regex("[.#\$\\[\\]]"), "_")
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        typingJob?.cancel()
        clearJob?.cancel()
        clearAllTyping()
        scope.cancel()
    }
}
