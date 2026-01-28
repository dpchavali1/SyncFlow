package com.phoneintegration.app.desktop

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.functions.FirebaseFunctions
import com.phoneintegration.app.auth.AuthManager
import com.phoneintegration.app.MmsHelper
import com.phoneintegration.app.MmsAttachment
import com.phoneintegration.app.PhoneNumberUtils
import com.phoneintegration.app.SimManager
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.SmsRepository
import com.phoneintegration.app.data.database.Group
import com.phoneintegration.app.data.database.GroupMember
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.e2ee.SignalProtocolManager
import com.phoneintegration.app.usage.UsageCategory
import com.phoneintegration.app.usage.UsageCheck
import com.phoneintegration.app.usage.UsageTracker
import com.phoneintegration.app.utils.NetworkUtils
import com.phoneintegration.app.utils.RetryUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.SecureRandom

/**
 * Handles syncing SMS messages to Firebase Realtime Database
 * for desktop access.
 * Note: Always uses applicationContext internally to prevent memory leaks.
 */
class DesktopSyncService(context: Context) {
    // Always use applicationContext to prevent Activity memory leaks
    private val context: Context = context.applicationContext

    private val authManager = AuthManager.getInstance(context)
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val functions = FirebaseFunctions.getInstance()
    private val e2eeManager = SignalProtocolManager(this.context)
    private val preferencesManager = PreferencesManager(this.context)
    private val usageTracker = UsageTracker(database)
    private val simManager = SimManager(this.context)

    // Cache of user's own phone numbers (normalized, last 10 digits) for filtering
    private val userPhoneNumbers: Set<String> by lazy {
        val numbers = mutableSetOf<String>()
        try {
            simManager.getActiveSims().forEach { sim ->
                sim.phoneNumber?.let { phone ->
                    // Store only the last 10 digits (standard US number length without country code)
                    val normalized = phone.replace(Regex("[^0-9]"), "")
                    if (normalized.length >= 10) {
                        numbers.add(normalized.takeLast(10))
                        Log.d(TAG, "Added user phone number to filter: ${normalized.takeLast(10)}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get user phone numbers: ${e.message}")
        }
        numbers
    }

    companion object {
        private const val TAG = "DesktopSyncService"
        private const val MESSAGES_PATH = "messages"
        private const val DEVICES_PATH = "devices"
        private const val USERS_PATH = "users"
        private const val GROUPS_PATH = "groups"
        private const val ATTACHMENTS_PATH = "attachments"
        private const val MESSAGE_REACTIONS_PATH = "message_reactions"
        private const val SPAM_MESSAGES_PATH = "spam_messages"
        private const val SYNC_REQUESTS_PATH = "sync_requests"

        // Cached paired devices status for fast checks
        private const val PREFS_NAME = "desktop_sync_prefs"
        private const val KEY_HAS_PAIRED_DEVICES = "has_paired_devices"
        private const val KEY_PAIRED_DEVICES_COUNT = "paired_devices_count"
        private const val KEY_LAST_CHECK_TIME = "last_paired_check_time"
        private const val CACHE_VALID_MS = 5 * 60 * 1000L // 5 minutes

        /**
         * Fast synchronous check if devices are paired (uses cached value)
         * Call this before any sync operation to skip unnecessary work
         */
        fun hasPairedDevices(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_HAS_PAIRED_DEVICES, false)
        }

        /**
         * Get cached paired devices count
         */
        fun getCachedDeviceCount(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_PAIRED_DEVICES_COUNT, 0)
        }

        /**
         * Update the cached paired devices status
         * Called after pairing/unpairing or after fetching device list
         */
        fun updatePairedDevicesCache(context: Context, hasPaired: Boolean, count: Int = 0) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_HAS_PAIRED_DEVICES, hasPaired)
                .putInt(KEY_PAIRED_DEVICES_COUNT, count)
                .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Updated paired devices cache: hasPaired=$hasPaired, count=$count")
        }

        /**
         * Check if cache needs refresh
         */
        fun isCacheStale(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0)
            return System.currentTimeMillis() - lastCheck > CACHE_VALID_MS
        }
    }

    init {
        // Initialize E2EE keys if not already done
        e2eeManager.initializeKeys()
    }

    /**
     * Get current user ID.
     *
     * Uses the simple approach of returning currentUser.uid directly.
     * Validation is only done during pairing to avoid hitting Firebase rate limits.
     *
     * NOTE: ensureDeviceKeysPublished() is NOT called here anymore because it can hang
     * due to Android Keystore operations. It should only be called when needed for E2EE,
     * not on every user ID request.
     */
    suspend fun getCurrentUserId(): String {
        // Priority 1: Use current Firebase Auth user (this is the source of truth)
        FirebaseAuth.getInstance().currentUser?.let { return it.uid }

        // Priority 2: Try AuthManager
        authManager.getCurrentUserId()?.let { return it }

        // Priority 3: Check RecoveryCodeManager (only as fallback when not signed in)
        val recoveryManager = com.phoneintegration.app.auth.RecoveryCodeManager.getInstance(context)
        recoveryManager.getEffectiveUserId()?.let { recoveredUserId ->
            FirebaseAuth.getInstance().signInAnonymously().await()
            return recoveredUserId
        }

        // Last resort: sign in anonymously
        val result = authManager.signInAnonymously()
        return result.getOrNull() ?: throw Exception("Authentication failed")
    }

    /**
     * Get the "conversation partner" address for a message.
     * For received messages (type=1), this is the sender's address.
     * For sent messages (type=2), we need to find the recipient from the thread.
     * Returns null only if the final resolved address is the user's own number.
     */
    private suspend fun getConversationAddress(message: SmsMessage): String? {
        // For received messages, the address is already the other party
        if (message.type == 1) {
            return message.address
        }

        // MMS sent: resolve from MMS recipients to avoid SMS ID collisions
        if (message.isMms) {
            val recipients = MmsHelper.getMmsAllRecipients(
                context.contentResolver,
                message.id
            )
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.contains("insert-address-token", ignoreCase = true) }

            if (recipients.isNotEmpty()) {
                // Prefer recipients that are NOT the user's own number
                val nonUserRecipients = recipients.filterNot { isUserOwnNumber(it) }
                if (nonUserRecipients.isNotEmpty()) {
                    return nonUserRecipients.first()
                }
                // All recipients are user's own number - skip this message
                Log.w(TAG, "MMS ${message.id} only has user's own number as recipient, skipping")
                return null
            }

            return message.address
        }

        // For sent SMS messages (type=2), the message.address SHOULD be the recipient
        // But some Android implementations store the sender's number instead
        // We need to verify by checking the Threads table
        try {
            val uri = android.provider.Telephony.Sms.CONTENT_URI
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(
                    android.provider.Telephony.Sms.ADDRESS,
                    android.provider.Telephony.Sms.TYPE,
                    android.provider.Telephony.Sms.THREAD_ID
                ),
                "${android.provider.Telephony.Sms._ID} = ?",
                arrayOf(message.id.toString()),
                null
            )

            var threadId: Long? = null
            var smsAddress: String? = null
            cursor?.use {
                if (it.moveToFirst()) {
                    smsAddress = it.getString(0)
                    threadId = it.getLong(2)
                }
            }

            // Try to get the recipient address from the Threads table
            // This is the most reliable source for the conversation partner
            if (threadId != null) {
                try {
                    val threadsUri = android.net.Uri.parse("content://mms-sms/conversations?simple=true")
                    val threadCursor = context.contentResolver.query(
                        threadsUri,
                        arrayOf("_id", "recipient_ids"),
                        "_id = ?",
                        arrayOf(threadId.toString()),
                        null
                    )

                    threadCursor?.use {
                        if (it.moveToFirst()) {
                            val recipientIdsIndex = it.getColumnIndex("recipient_ids")
                            if (recipientIdsIndex >= 0) {
                                val recipientIds = it.getString(recipientIdsIndex)
                                if (!recipientIds.isNullOrBlank()) {
                                    // Resolve ALL recipient_ids and filter out user's own number
                                    val allIds = recipientIds.split(" ").filter { id -> id.isNotBlank() }
                                    for (recipientId in allIds) {
                                        val resolvedAddress = resolveRecipientId(recipientId)
                                        if (!resolvedAddress.isNullOrBlank() && !isUserOwnNumber(resolvedAddress)) {
                                            return resolvedAddress
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not query Threads table: ${e.message}")
                }

                // Fallback: look for a received message in the same thread
                val threadMsgCursor = context.contentResolver.query(
                    uri,
                    arrayOf(android.provider.Telephony.Sms.ADDRESS),
                    "${android.provider.Telephony.Sms.THREAD_ID} = ? AND ${android.provider.Telephony.Sms.TYPE} = 1",
                    arrayOf(threadId.toString()),
                    "${android.provider.Telephony.Sms.DATE} DESC LIMIT 1"
                )

                threadMsgCursor?.use {
                    if (it.moveToFirst()) {
                        val recipientAddress = it.getString(0)
                        if (!recipientAddress.isNullOrBlank()) {
                            // Received message address is the OTHER party, use it
                            return recipientAddress
                        }
                    }
                }
            }

            // Use the address from the SMS table query if available
            if (!smsAddress.isNullOrBlank()) {
                // Only skip if this address is definitively the user's own number
                if (isUserOwnNumber(smsAddress!!)) {
                    Log.w(TAG, "Sent SMS ${message.id} has user's own number as address, skipping")
                    return null
                }
                return smsAddress!!
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding conversation address", e)
        }

        // Final fallback: only skip if address is user's own number
        if (isUserOwnNumber(message.address)) {
            Log.w(TAG, "Could not determine recipient for sent message ${message.id}, address is user's own number")
            return null
        }

        // Fallback: return the original address from the message
        return message.address
    }

    /**
     * Resolve a recipient_id to an actual phone number using the canonical_addresses table
     */
    private fun resolveRecipientId(recipientId: String): String? {
        try {
            val canonicalUri = android.net.Uri.parse("content://mms-sms/canonical-addresses")
            val cursor = context.contentResolver.query(
                canonicalUri,
                arrayOf("_id", "address"),
                "_id = ?",
                arrayOf(recipientId),
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val addressIndex = it.getColumnIndex("address")
                    if (addressIndex >= 0) {
                        return it.getString(addressIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve recipient ID $recipientId: ${e.message}")
        }
        return null
    }

    private fun isRcsAddress(address: String): Boolean {
        val lower = address.lowercase()
        return lower.contains("@rcs") ||
            lower.contains("rcs.google") ||
            lower.contains("rcs.goog") ||
            lower.startsWith("rcs:") ||
            lower.startsWith("rcs://")
    }

    /**
     * Check if an address is the user's own phone number.
     * Used to filter out incorrectly resolved sent message addresses.
     * Only matches on last 10 digits to avoid false positives while still
     * handling different country code formats.
     */
    private fun isUserOwnNumber(address: String): Boolean {
        if (userPhoneNumbers.isEmpty()) return false
        val normalized = address.replace(Regex("[^0-9]"), "")
        // Need at least 10 digits for a valid comparison
        if (normalized.length < 10) return false
        val last10 = normalized.takeLast(10)
        val isMatch = userPhoneNumbers.contains(last10)
        if (isMatch) {
            Log.d(TAG, "Address $address matches user's own number")
        }
        return isMatch
    }

    /**
     * Sync a message to Firebase with E2EE encryption
     */
    suspend fun syncMessage(message: SmsMessage, skipAttachments: Boolean = false) {
        try {
            Log.d(TAG, "Starting sync for message: id=${message.id}, isMms=${message.isMms}, address=${message.address}, body length=${message.body?.length ?: 0}, skipAttachments=$skipAttachments")

            // Check network connectivity before syncing
            if (!NetworkUtils.isNetworkAvailable(context)) {
                Log.w(TAG, "No network available, skipping sync for message: ${message.id}")
                return
            }

            // Filter out RBM (Rich Business Messaging) spam
            if (message.address.contains("@rbm.goog", ignoreCase = true)) {
                Log.d(TAG, "Skipping RBM message from: ${message.address}")
                return
            }
            if (isRcsAddress(message.address)) {
                Log.d(TAG, "Skipping RCS message from: ${message.address}")
                return
            }

            val userId = getCurrentUserId()
            // E2EE keys are initialized in init{}, no need to call ensureDeviceKeysPublished() here
            val messageKey = getFirebaseMessageKey(message)
            val messageRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MESSAGES_PATH)
                .child(messageKey)

            // Get the normalized conversation address (the "other party")
            val conversationAddress = getConversationAddress(message)

            // Skip messages where we couldn't determine the conversation partner
            // This happens when the address is the user's own phone number
            if (conversationAddress == null) {
                Log.w(TAG, "Skipping message ${message.id} - could not determine conversation partner")
                return
            }

            val messageData = mutableMapOf<String, Any?>(
                "id" to messageKey,
                "sourceId" to message.id,
                "sourceType" to if (message.isMms) "mms" else "sms",
                "address" to conversationAddress,  // Use normalized address
                "date" to message.date,
                "type" to message.type,
                "timestamp" to ServerValue.TIMESTAMP
            )

            // Check if E2EE is enabled in settings
            val isE2eeEnabled = preferencesManager.e2eeEnabled.value

            if (isE2eeEnabled) {
                val deviceId = e2eeManager.getDeviceId() ?: "android"
                val deviceKeys = e2eeManager.getDevicePublicKeys(userId)
                val dataKey = ByteArray(32).apply { java.security.SecureRandom().nextBytes(this) }

                val bodyToEncrypt = message.body.ifBlank { "[MMS]" }
                val encryptedBodyResult = e2eeManager.encryptMessageBody(dataKey, bodyToEncrypt)
                val keyMap = mutableMapOf<String, String>()

                if (encryptedBodyResult != null && deviceKeys.isNotEmpty()) {
                    for ((targetDeviceId, publicKey) in deviceKeys) {
                        val envelope = e2eeManager.encryptDataKeyForDevice(publicKey, dataKey)
                        if (!envelope.isNullOrBlank()) {
                            keyMap[targetDeviceId] = envelope
                        }
                    }
                }

                if (encryptedBodyResult != null && keyMap.isNotEmpty()) {
                    val (ciphertext, nonce) = encryptedBodyResult
                    messageData["body"] = android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP)
                    messageData["nonce"] = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP)
                    messageData["keyMap"] = keyMap
                    messageData["keyVersion"] = 2
                    messageData["senderDeviceId"] = deviceId
                    messageData["encrypted"] = true
                    Log.d(TAG, "Message encrypted with per-device key map")
                } else {
                    // E2EE encryption failed - fall back to plaintext with warning
                    // This ensures messages are never silently dropped
                    val failureReason = when {
                        encryptedBodyResult == null && keyMap.isEmpty() -> "encryption and key exchange both failed"
                        encryptedBodyResult == null -> "encryption failed"
                        keyMap.isEmpty() -> "no device keys available"
                        else -> "unknown error"
                    }
                    Log.w(TAG, "E2EE enabled but $failureReason - syncing as plaintext with warning flag")
                    messageData["body"] = message.body
                    messageData["encrypted"] = false
                    messageData["e2eeFailed"] = true
                    messageData["e2eeFailureReason"] = failureReason
                }
            } else {
                // E2EE disabled - store plaintext
                messageData["body"] = message.body
                messageData["encrypted"] = false
                Log.d(TAG, "E2EE disabled, storing plaintext message")
            }

            // Add contact name if available
            message.contactName?.let {
                messageData["contactName"] = it
            }

            Log.d(TAG, "Syncing message: id=${message.id}, type=${message.type}, address=$conversationAddress")

            // Handle MMS attachments
            if (message.isMms) {
                messageData["isMms"] = true
                if (skipAttachments) {
                    Log.d(TAG, "Skipping attachments for MMS message ${message.id} (history sync)")
                } else {
                    Log.d(TAG, "Processing MMS attachments for message ${message.id}")
                    val attachments = if (message.mmsAttachments.isNotEmpty()) {
                        Log.d(TAG, "Using existing MMS attachments: ${message.mmsAttachments.size}")
                        message.mmsAttachments
                    } else {
                        Log.d(TAG, "Loading MMS attachments from provider for message ${message.id}")
                        loadMmsAttachmentsFromProvider(message.id)
                    }
                    Log.d(TAG, "Found ${attachments.size} MMS attachments")
                    if (attachments.isNotEmpty()) {
                        val attachmentUrls = uploadMmsAttachments(userId, message.id, attachments)
                        Log.d(TAG, "Uploaded ${attachmentUrls.size} MMS attachment URLs")
                        if (attachmentUrls.isNotEmpty()) {
                            messageData["attachments"] = attachmentUrls
                        }
                    }
                }
            }

            // Add subId if available
            message.subId?.let {
                messageData["subId"] = it
            }

            // Write to Firebase via Cloud Function (prevents OOM from Firebase auto-sync)
            try {
                val result = functions
                    .getHttpsCallable("syncMessage")
                    .call(mapOf(
                        "messageId" to messageKey,
                        "message" to messageData
                    ))
                    .await()

                val data = result.data as? Map<*, *>
                val success = data?.get("success") as? Boolean ?: false
                if (success) {
                    Log.d(TAG, "Successfully synced message via Cloud Function: ${message.id}")
                } else {
                    Log.w(TAG, "Cloud Function returned success=false for message: ${message.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing message via Cloud Function: ${e.message}", e)
                // Don't throw - message sync failure shouldn't crash the app
            }

            Log.d(
                TAG,
                "Message synced successfully: $messageKey with address: $conversationAddress (encrypted: ${messageData["encrypted"] == true}, isMms: ${message.isMms})"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing message after retries", e)
        }
    }

    suspend fun setMessageReaction(messageId: Long, reaction: String?) {
        val userId = getCurrentUserId()
        val reactionRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(MESSAGE_REACTIONS_PATH)
            .child(messageId.toString())

        RetryUtils.withFirebaseRetry("setMessageReaction $messageId") {
            if (reaction.isNullOrBlank()) {
                reactionRef.removeValue().await()
            } else {
                val payload = mapOf(
                    "reaction" to reaction,
                    "updatedAt" to ServerValue.TIMESTAMP,
                    "updatedBy" to "android"
                )
                reactionRef.setValue(payload).await()
            }
        }
    }

    /**
     * Sync a spam message to Firebase so it persists across reinstalls/devices.
     */
    suspend fun syncSpamMessage(message: com.phoneintegration.app.data.database.SpamMessage) {
        withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            try {
                val spamMessageData = hashMapOf<String, Any>(
                    "address" to message.address,
                    "body" to message.body,
                    "date" to message.date,
                    "contactName" to (message.contactName ?: message.address),
                    "spamConfidence" to message.spamConfidence.toDouble(),
                    "spamReasons" to (message.spamReasons ?: "user_marked"),
                    "detectedAt" to message.detectedAt,
                    "isUserMarked" to message.isUserMarked,
                    "isRead" to message.isRead
                )

                val data = hashMapOf<String, Any>(
                    "messageId" to message.messageId.toString(),
                    "spamMessage" to spamMessageData
                )

                val response = functions.getHttpsCallable("syncSpamMessage").call(data).await()
                val resultData = response.data as? Map<*, *>
                val success = resultData?.get("success") as? Boolean ?: false
                if (!success) {
                    Log.e(TAG, "Failed to sync spam message ${message.messageId}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing spam message ${message.messageId}", e)
            }
        }
    }

    suspend fun syncSpamMessages(messages: List<com.phoneintegration.app.data.database.SpamMessage>) {
        messages.forEach { syncSpamMessage(it) }
    }

    suspend fun fetchSpamMessages(): List<com.phoneintegration.app.data.database.SpamMessage> {
        return try {
            val userId = getCurrentUserId()
            val spamRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(SPAM_MESSAGES_PATH)

            val snapshot = spamRef.get().await()
            val list = mutableListOf<com.phoneintegration.app.data.database.SpamMessage>()
            snapshot.children.forEach { child ->
                val address = child.child("address").getValue(String::class.java) ?: return@forEach
                val body = child.child("body").getValue(String::class.java) ?: ""
                val date = child.child("date").getValue(Long::class.java) ?: 0L
                val id = child.child("messageId").getValue(Long::class.java)
                    ?: child.key?.toLongOrNull()
                    ?: child.child("originalMessageId").getValue(String::class.java)?.toLongOrNull()
                    ?: generateSpamFallbackId(address, body, date)
                    ?: return@forEach
                val contactName = child.child("contactName").getValue(String::class.java)
                val spamConfidence = (child.child("spamConfidence").getValue(Double::class.java)
                    ?: child.child("spamConfidence").getValue(Float::class.java)?.toDouble()
                    ?: 0.5).toFloat()
                val spamReasons = child.child("spamReasons").getValue(String::class.java)
                val detectedAt = child.child("detectedAt").getValue(Long::class.java) ?: System.currentTimeMillis()
                val isUserMarked = child.child("isUserMarked").getValue(Boolean::class.java) ?: false
                val isRead = child.child("isRead").getValue(Boolean::class.java) ?: false

                list.add(
                    com.phoneintegration.app.data.database.SpamMessage(
                        messageId = id,
                        address = address,
                        body = body,
                        date = date,
                        contactName = contactName,
                        spamConfidence = spamConfidence,
                        spamReasons = spamReasons,
                        detectedAt = detectedAt,
                        isUserMarked = isUserMarked,
                        isRead = isRead
                    )
                )
            }
            list.sortedByDescending { it.date }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching spam messages", e)
            emptyList()
        }
    }

    private fun generateSpamFallbackId(address: String, body: String, date: Long): Long? {
        if (address.isBlank() && body.isBlank() && date == 0L) {
            return null
        }
        val input = "$address|$body|$date"
        val crc = java.util.zip.CRC32()
        crc.update(input.toByteArray(Charsets.UTF_8))
        return crc.value.toLong()
    }

    suspend fun deleteSpamMessage(messageId: Long) {
        withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            try {
                val data = hashMapOf<String, Any>("messageId" to messageId.toString())
                functions.getHttpsCallable("deleteSpamMessage").call(data).await()
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting spam message $messageId", e)
            }
        }
    }

    suspend fun clearAllSpamMessages() {
        withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            try {
                functions.getHttpsCallable("clearAllSpamMessages").call(hashMapOf<String, Any>()).await()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing spam messages", e)
            }
        }
    }

    /**
     * Real-time listener for spam messages from Firebase.
     * This enables bidirectional sync - when Mac/Web marks a message as spam,
     * Android will receive the update in real-time.
     */
    fun listenForSpamMessages(): Flow<List<com.phoneintegration.app.data.database.SpamMessage>> = callbackFlow {
        val userId = try {
            getCurrentUserId()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot listen for spam - not authenticated", e)
            close()
            return@callbackFlow
        }

        val spamRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(SPAM_MESSAGES_PATH)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<com.phoneintegration.app.data.database.SpamMessage>()
                snapshot.children.forEach { child ->
                    try {
                        val address = child.child("address").getValue(String::class.java) ?: return@forEach
                        val body = child.child("body").getValue(String::class.java) ?: ""
                        val date = child.child("date").getValue(Long::class.java) ?: 0L
                        val id = child.child("messageId").getValue(Long::class.java)
                            ?: child.key?.toLongOrNull()
                            ?: child.child("originalMessageId").getValue(String::class.java)?.toLongOrNull()
                            ?: generateSpamFallbackId(address, body, date)
                            ?: return@forEach
                        val contactName = child.child("contactName").getValue(String::class.java)
                        val spamConfidence = (child.child("spamConfidence").getValue(Double::class.java)
                            ?: child.child("spamConfidence").getValue(Float::class.java)?.toDouble()
                            ?: 0.5).toFloat()
                        val spamReasons = child.child("spamReasons").getValue(String::class.java)
                        val detectedAt = child.child("detectedAt").getValue(Long::class.java) ?: System.currentTimeMillis()
                        val isUserMarked = child.child("isUserMarked").getValue(Boolean::class.java) ?: false
                        val isRead = child.child("isRead").getValue(Boolean::class.java) ?: false

                        list.add(
                            com.phoneintegration.app.data.database.SpamMessage(
                                messageId = id,
                                address = address,
                                body = body,
                                date = date,
                                contactName = contactName,
                                spamConfidence = spamConfidence,
                                spamReasons = spamReasons,
                                detectedAt = detectedAt,
                                isUserMarked = isUserMarked,
                                isRead = isRead
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing spam message ${child.key}", e)
                    }
                }
                trySend(list.sortedByDescending { it.date }).isSuccess
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Spam listener cancelled: ${error.message}")
            }
        }

        spamRef.addValueEventListener(listener)
        awaitClose {
            spamRef.removeEventListener(listener)
        }
    }

    // -------------------------------------------------------------------------
    // SPAM WHITELIST/BLOCKLIST SYNC
    // -------------------------------------------------------------------------

    /**
     * Sync whitelist (not spam) to cloud
     */
    suspend fun syncWhitelist(addresses: Set<String>) {
        withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            try {
                val userId = getCurrentUserId()
                val whitelistRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child("spam_whitelist")

                whitelistRef.setValue(addresses.toList()).await()
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing whitelist", e)
            }
        }
    }

    /**
     * Sync blocklist (always spam) to cloud
     */
    suspend fun syncBlocklist(addresses: Set<String>) {
        withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            try {
                val userId = getCurrentUserId()
                val blocklistRef = database.reference
                    .child(USERS_PATH)
                    .child(userId)
                    .child("spam_blocklist")

                blocklistRef.setValue(addresses.toList()).await()
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing blocklist", e)
            }
        }
    }

    /**
     * Listen for whitelist changes from cloud (e.g., when macOS marks as not spam)
     */
    fun listenForWhitelist(): Flow<Set<String>> = callbackFlow {
        val userId = try {
            getCurrentUserId()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot listen for whitelist - not authenticated", e)
            close()
            return@callbackFlow
        }

        val whitelistRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child("spam_whitelist")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val addresses = mutableSetOf<String>()
                snapshot.children.forEach { child ->
                    (child.getValue(String::class.java))?.let { addresses.add(it) }
                }
                trySend(addresses).isSuccess
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Whitelist listener cancelled: ${error.message}")
            }
        }

        whitelistRef.addValueEventListener(listener)
        awaitClose {
            whitelistRef.removeEventListener(listener)
        }
    }

    /**
     * Listen for blocklist changes from cloud (e.g., when macOS marks as spam)
     */
    fun listenForBlocklist(): Flow<Set<String>> = callbackFlow {
        val userId = try {
            getCurrentUserId()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot listen for blocklist - not authenticated", e)
            close()
            return@callbackFlow
        }

        val blocklistRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child("spam_blocklist")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val addresses = mutableSetOf<String>()
                snapshot.children.forEach { child ->
                    (child.getValue(String::class.java))?.let { addresses.add(it) }
                }
                trySend(addresses).isSuccess
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Blocklist listener cancelled: ${error.message}")
            }
        }

        blocklistRef.addValueEventListener(listener)
        awaitClose {
            blocklistRef.removeEventListener(listener)
        }
    }

    suspend fun listenForMessageReactions(): Flow<Map<Long, String>> = callbackFlow {
        val userId = getCurrentUserId()
        val reactionsRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(MESSAGE_REACTIONS_PATH)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val reactions = mutableMapOf<Long, String>()
                snapshot.children.forEach { child ->
                    val id = child.key?.toLongOrNull() ?: return@forEach
                    val reactionValue = when (val value = child.child("reaction").value) {
                        is String -> value
                        else -> null
                    } ?: return@forEach
                    reactions[id] = reactionValue
                }
                trySend(reactions).isSuccess
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Reaction listener cancelled: ${error.message}")
            }
        }

        reactionsRef.addValueEventListener(listener)
        awaitClose {
            reactionsRef.removeEventListener(listener)
        }
    }

    /**
     * Upload MMS attachments to Firebase Storage with E2EE encryption
     * Returns a list of attachment metadata including download URLs
     */
    private suspend fun uploadMmsAttachments(
        userId: String,
        messageId: Long,
        attachments: List<MmsAttachment>
    ): List<Map<String, Any?>> {
        val uploadedAttachments = mutableListOf<Map<String, Any?>>()

        for (attachment in attachments) {
            try {
                // Only upload images and videos
                if (!attachment.isImage() && !attachment.isVideo()) {
                    // For non-media attachments, just include metadata
                    uploadedAttachments.add(mapOf(
                        "id" to attachment.id,
                        "contentType" to attachment.contentType,
                        "fileName" to (attachment.fileName ?: "attachment"),
                        "type" to getAttachmentType(attachment),
                        "encrypted" to false
                    ))
                    continue
                }

                // Get file content - try multiple approaches
                var attachmentBytes: ByteArray? = null

                // First try: use pre-loaded data if available
                if (attachment.data != null) {
                    attachmentBytes = attachment.data
                    Log.d(TAG, "Using pre-loaded attachment data for ${attachment.id}")
                }

                // Second try: read from content URI
                if (attachmentBytes == null && !attachment.filePath.isNullOrEmpty()) {
                    try {
                        val fileUri = Uri.parse(attachment.filePath)
                        attachmentBytes = context.contentResolver.openInputStream(fileUri)?.use { stream ->
                            stream.readBytes()
                        }
                        if (attachmentBytes != null) {
                            Log.d(TAG, "Loaded attachment from URI: ${attachment.filePath}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load from content URI: ${e.message}")
                    }
                }

                // Third try: query MMS part directly if we have a part ID
                if (attachmentBytes == null && attachment.id > 0) {
                    try {
                        val partUri = Uri.parse("content://mms/part/${attachment.id}")
                        attachmentBytes = context.contentResolver.openInputStream(partUri)?.use { stream ->
                            stream.readBytes()
                        }
                        if (attachmentBytes != null) {
                            Log.d(TAG, "Loaded attachment from part ID: ${attachment.id}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load from part ID: ${e.message}")
                    }
                }

                if (attachmentBytes == null) {
                    Log.e(TAG, "Could not load attachment ${attachment.id} - skipping")
                    continue
                }

                // Create storage reference
                val extension = getFileExtension(attachment.contentType)
                val storagePath = "$USERS_PATH/$userId/$ATTACHMENTS_PATH/$messageId/${attachment.id}.$extension"
                val storageRef = storage.reference.child(storagePath)

                // Use the loaded bytes
                val bytes = attachmentBytes
                var bytesToUpload = bytes
                var isEncrypted = false

                // Encrypt attachment data using E2EE
                try {
                    val encryptedBytes = e2eeManager.encryptBytes(userId, bytes)
                    if (encryptedBytes != null) {
                        bytesToUpload = encryptedBytes
                        isEncrypted = true
                        Log.d(TAG, "Attachment encrypted: ${attachment.id} (${bytes.size} -> ${encryptedBytes.size} bytes)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to encrypt attachment, uploading unencrypted: ${e.message}")
                }

                val usageCheck = runCatching {
                    usageTracker.isUploadAllowed(
                        userId = userId,
                        bytes = bytesToUpload.size.toLong(),
                        countsTowardStorage = true
                    )
                }.getOrElse {
                    UsageCheck(true)
                }

                if (!usageCheck.allowed) {
                    Log.w(
                        TAG,
                        "Skipping attachment ${attachment.id} due to usage limit: ${usageCheck.reason}"
                    )
                    continue
                }

                var downloadUrl: String? = null
                var useInlineData = false

                try {
                    // Upload the file (encrypted or plaintext)
                    val uploadTask = storageRef.putBytes(bytesToUpload)
                    uploadTask.await()

                    // Get download URL
                    downloadUrl = storageRef.downloadUrl.await().toString()
                    Log.d(TAG, "Uploaded attachment: ${attachment.id} -> $downloadUrl (encrypted: $isEncrypted)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error uploading attachment ${attachment.id}", e)
                    if (bytesToUpload.size < 500_000) {
                        useInlineData = true
                        Log.w(TAG, "Falling back to inline data for attachment ${attachment.id}")
                    } else {
                        continue
                    }
                }

                val metadata = mutableMapOf<String, Any?>(
                    "id" to attachment.id,
                    "contentType" to attachment.contentType,
                    "fileName" to (attachment.fileName ?: "attachment.$extension"),
                    "type" to getAttachmentType(attachment),
                    "encrypted" to isEncrypted,
                    "originalSize" to bytes.size
                )

                if (downloadUrl != null) {
                    metadata["url"] = downloadUrl
                } else if (useInlineData) {
                    metadata["inlineData"] = android.util.Base64.encodeToString(bytesToUpload, android.util.Base64.NO_WRAP)
                    metadata["isInline"] = true
                }

                uploadedAttachments.add(metadata)

                runCatching {
                    usageTracker.recordUpload(
                        userId = userId,
                        bytes = bytesToUpload.size.toLong(),
                        category = UsageCategory.MMS,
                        countsTowardStorage = downloadUrl != null
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Failed to record MMS usage for ${attachment.id}: ${error.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading attachment ${attachment.id}", e)
            }
        }

        return uploadedAttachments
    }

    private fun loadMmsAttachmentsFromProvider(mmsId: Long): List<MmsAttachment> {
        if (mmsId <= 0) return emptyList()

        val list = mutableListOf<MmsAttachment>()
        val cursor = context.contentResolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "name", "fn"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        ) ?: return emptyList()

        cursor.use {
            while (it.moveToNext()) {
                val partId = it.getLong(0)
                val contentType = it.getString(1) ?: ""
                val fileName = it.getString(2) ?: it.getString(3)

                if (contentType == "text/plain" || contentType == "application/smil") {
                    continue
                }

                val partUri = "content://mms/part/$partId"

                // Load actual attachment data for media files (critical for sync)
                val data: ByteArray? = if (contentType.startsWith("image/") ||
                    contentType.startsWith("video/") ||
                    contentType.startsWith("audio/")) {
                    try {
                        context.contentResolver.openInputStream(Uri.parse(partUri))?.use { stream ->
                            stream.readBytes()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read MMS attachment data for part $partId: ${e.message}")
                        null
                    }
                } else null

                list.add(
                    MmsAttachment(
                        id = partId,
                        contentType = contentType,
                        filePath = partUri,
                        data = data,
                        fileName = fileName
                    )
                )
            }
        }

        return list
    }

    fun getFirebaseMessageKey(message: SmsMessage): String {
        return if (message.isMms) {
            "mms_${message.id}"
        } else {
            message.id.toString()
        }
    }

    /**
     * Fetch recent Firebase message keys for deletion reconciliation.
     */
    suspend fun fetchRecentMessageKeys(limit: Int = 1000): Set<String> {
        return try {
            val userId = getCurrentUserId()
            val messagesRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MESSAGES_PATH)
                .orderByChild("date")
                .limitToLast(limit)

            val snapshot = messagesRef.get().await()
            val keys = mutableSetOf<String>()
            snapshot.children.forEach { child ->
                child.key?.let { keys.add(it) }
            }
            keys
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recent message keys", e)
            emptySet()
        }
    }

    /**
     * Get file extension from content type
     */
    private fun getFileExtension(contentType: String): String {
        return when {
            contentType.contains("jpeg") || contentType.contains("jpg") -> "jpg"
            contentType.contains("png") -> "png"
            contentType.contains("gif") -> "gif"
            contentType.contains("webp") -> "webp"
            contentType.contains("mp4") -> "mp4"
            contentType.contains("3gpp") || contentType.contains("3gp") -> "3gp"
            contentType.contains("mpeg") || contentType.contains("mp3") -> "mp3"
            contentType.contains("ogg") -> "ogg"
            contentType.contains("vcard") -> "vcf"
            else -> "bin"
        }
    }

    /**
     * Get attachment type string
     */
    private fun getAttachmentType(attachment: MmsAttachment): String {
        return when {
            attachment.isImage() -> "image"
            attachment.isVideo() -> "video"
            attachment.isAudio() -> "audio"
            attachment.isVCard() -> "vcard"
            else -> "file"
        }
    }

    /**
     * Sync multiple messages to Firebase using batched writes for better performance.
     * Messages are processed in parallel chunks with a concurrency limit.
     */
    suspend fun syncMessages(messages: List<SmsMessage>) {
        // Early exit if no network - avoid looping through messages
        if (!NetworkUtils.isNetworkAvailable(context)) {
            Log.w(TAG, "No network available, skipping batch sync of ${messages.size} messages")
            return
        }

        if (messages.isEmpty()) return

        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Starting batched sync of ${messages.size} messages")

        // Filter out RCS/RBM messages upfront
        val filteredMessages = messages.filter { msg ->
            !msg.address.contains("@rbm.goog", ignoreCase = true) && !isRcsAddress(msg.address)
        }

        Log.d(TAG, "After filtering: ${filteredMessages.size} messages to sync")

        // Process in parallel with limited concurrency (50 at a time)
        val chunkSize = 50
        val chunks = filteredMessages.chunked(chunkSize)

        for ((index, chunk) in chunks.withIndex()) {
            Log.d(TAG, "Processing chunk ${index + 1}/${chunks.size} (${chunk.size} messages)")

            // Process each chunk in parallel using coroutines
            coroutineScope {
                chunk.map { message: SmsMessage ->
                    async(Dispatchers.IO) {
                        try {
                            syncMessage(message)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error syncing message ${message.id}: ${e.message}")
                        }
                    }
                }.awaitAll()
            }

            // Small delay between chunks to avoid overwhelming Firebase
            if (index < chunks.size - 1) {
                delay(100)
            }
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Batched sync completed: ${filteredMessages.size} messages in ${duration}ms")
    }

    /**
     * Decrypt an encrypted message body
     * Returns the decrypted plaintext or the original body if not encrypted/decryption fails
     */
    fun decryptMessageBody(body: String?, isEncrypted: Boolean?): String {
        if (body == null) return ""
        if (isEncrypted != true) return body

        return try {
            val decrypted = e2eeManager.decryptMessage(body)
            if (decrypted != null) {
                Log.d(TAG, "Message decrypted successfully")
                decrypted
            } else {
                Log.w(TAG, "Decryption returned null, using original body")
                body
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting message", e)
            body
        }
    }

    private suspend fun MutableMap<String, Any?>.applyEncryptionPayload(userId: String, body: String?): MutableMap<String, Any?> {
        val safeBody = body ?: ""
        val isE2eeEnabled = preferencesManager.e2eeEnabled.value
        if (!isE2eeEnabled) {
            this["body"] = safeBody
            this["encrypted"] = false
            return this
        }

        val deviceId = e2eeManager.getDeviceId() ?: "android"
        val deviceKeys = e2eeManager.getDevicePublicKeys(userId)
        val dataKey = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val bodyToEncrypt = safeBody.ifBlank { "[MMS]" }
        val encryptedBodyResult = e2eeManager.encryptMessageBody(dataKey, bodyToEncrypt)
        val keyMap = mutableMapOf<String, String>()

        if (encryptedBodyResult != null && deviceKeys.isNotEmpty()) {
            for ((targetDeviceId, publicKey) in deviceKeys) {
                val envelope = e2eeManager.encryptDataKeyForDevice(publicKey, dataKey)
                if (!envelope.isNullOrBlank()) {
                    keyMap[targetDeviceId] = envelope
                }
            }
        }

        if (encryptedBodyResult != null && keyMap.isNotEmpty()) {
            val (ciphertext, nonce) = encryptedBodyResult
            this["body"] = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            this["nonce"] = Base64.encodeToString(nonce, Base64.NO_WRAP)
            this["keyMap"] = keyMap
            this["keyVersion"] = 2
            this["senderDeviceId"] = deviceId
            this["encrypted"] = true
        } else {
            val failureReason = when {
                encryptedBodyResult == null && keyMap.isEmpty() -> "encryption and key exchange both failed"
                encryptedBodyResult == null -> "encryption failed"
                keyMap.isEmpty() -> "no device keys available"
                else -> "unknown error"
            }

            this["body"] = safeBody
            this["encrypted"] = false
            this["e2eeFailed"] = true
            this["e2eeFailureReason"] = failureReason
        }

        return this
    }

    /**
     * Listen for new messages from desktop (user sending SMS from web)
     * Returns a Flow that emits message data with message ID included
     * Outgoing messages from web are NOT encrypted (web sends plaintext)
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

                // Decrypt body if encrypted (for future when web also encrypts)
                val isEncrypted = dataWithId["encrypted"] as? Boolean ?: false
                val body = dataWithId["body"] as? String
                if (isEncrypted && body != null) {
                    dataWithId["body"] = decryptMessageBody(body, true)
                }

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
     *
     * Uses NonCancellable to prevent the query from being cancelled when
     * the user navigates away from the screen. This ensures the query completes
     * and returns actual data instead of empty list due to cancellation.
     */
    /**
     * Get paired devices using Cloud Function (no direct Firebase reads to avoid OOM)
     *
     * IMPORTANT: Android should NEVER read directly from Firebase Realtime Database
     * because it tries to sync ALL data and causes OOM with large datasets.
     * Always use Cloud Functions for reads - they run on server and can paginate.
     */
    suspend fun getPairedDevices(): List<PairedDevice> = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        try {
            Log.d(TAG, "=== GET PAIRED DEVICES (via Cloud Function) ===")

            // Use getDeviceInfoV2 Cloud Function which already returns devices list
            val deviceInfo = getDeviceInfo()
            if (deviceInfo == null) {
                Log.w(TAG, "getDeviceInfo returned null, returning empty list")
                updatePairedDevicesCache(context, false, 0)
                return@withContext emptyList()
            }

            Log.d(TAG, "=== RESULT: Found ${deviceInfo.devices.size} paired devices ===")

            // Update cache for fast checks
            updatePairedDevicesCache(context, deviceInfo.devices.isNotEmpty(), deviceInfo.devices.size)

            deviceInfo.devices
        } catch (e: Exception) {
            Log.e(TAG, "ERROR getting paired devices: ${e.message}", e)
            emptyList()
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
     * Clean up duplicate device entries, keeping only the most recent one per device name.
     * This handles cases where multiple entries were created for the same device.
     */
    suspend fun cleanupDuplicateDevices(): Int {
        try {
            val userId = getCurrentUserId()
            val devices = getPairedDevices()

            // Group devices by name
            val devicesByName = devices.groupBy { it.name }

            var removedCount = 0

            for ((name, deviceList) in devicesByName) {
                if (deviceList.size > 1) {
                    // Sort by lastSeen descending, keep the most recent
                    val sorted = deviceList.sortedByDescending { it.lastSeen }
                    val toKeep = sorted.first()
                    val toRemove = sorted.drop(1)

                    Log.d(TAG, "Device '$name' has ${deviceList.size} entries, keeping ${toKeep.id} (lastSeen: ${toKeep.lastSeen})")

                    for (device in toRemove) {
                        Log.d(TAG, "Removing duplicate device: ${device.id} (lastSeen: ${device.lastSeen})")
                        unpairDevice(device.id)
                        removedCount++
                    }
                }
            }

            Log.d(TAG, "Cleanup complete. Removed $removedCount duplicate device entries.")
            return removedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up duplicate devices", e)
            return 0
        }
    }

    /**
     * Remove all old device entries (older than specified days)
     */
    suspend fun removeOldDevices(olderThanDays: Int = 30): Int {
        try {
            val userId = getCurrentUserId()
            val devices = getPairedDevices()
            val cutoffTime = System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000L)

            var removedCount = 0

            for (device in devices) {
                if (device.lastSeen < cutoffTime && device.lastSeen > 0) {
                    Log.d(TAG, "Removing old device: ${device.name} (${device.id}), lastSeen: ${device.lastSeen}")
                    unpairDevice(device.id)
                    removedCount++
                }
            }

            Log.d(TAG, "Removed $removedCount old device entries.")
            return removedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error removing old devices", e)
            return 0
        }
    }

    /**
     * Pair with token from web (phone scans QR code from website)
     */
    @Deprecated("Pairing is now initiated by the phone; use generatePairingToken() and redeem on desktop.")
    suspend fun pairWithToken(token: String): Boolean {
        Log.w(TAG, "pairWithToken is deprecated; use device-scoped pairing flow")
        return false
    }

    /**
     * Generate pairing token (OLD METHOD - for phone generating QR code)
     * Kept for backwards compatibility
     */
    suspend fun generatePairingToken(deviceType: String = "desktop"): String {
        val userId = getCurrentUserId()
        val result = functions
            .getHttpsCallable("createPairingToken")
            .call(mapOf("deviceType" to deviceType))
            .await()
        val data = result.data as? Map<*, *> ?: throw Exception("Invalid pairing response")
        return data["token"] as? String ?: throw Exception("Missing pairing token")
    }

    /**
     * Complete pairing after scanning QR code from Web/macOS
     * Called when Android user approves the pairing request
     * Supports both V1 and V2 pairing protocols
     */
    suspend fun completePairing(token: String, approved: Boolean): CompletePairingResult {
        Log.d(TAG, "=== COMPLETE PAIRING START ===")
        Log.d(TAG, "Token: ${token.take(8)}..., Approved: $approved")

        // Ensure user is authenticated
        var currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.d(TAG, "No authenticated user, signing in anonymously...")
            try {
                val authResult = FirebaseAuth.getInstance().signInAnonymously().await()
                currentUser = authResult.user
                Log.d(TAG, "Signed in anonymously as: ${currentUser?.uid}")
            } catch (authError: Exception) {
                Log.e(TAG, "Failed to sign in anonymously", authError)
                return CompletePairingResult.Error("Authentication failed: ${authError.message}")
            }
        } else {
            Log.d(TAG, "Using current user: ${currentUser.uid}")
        }

        // Try V2 first
        return try {
            Log.d(TAG, "Trying V2 pairing...")
            val result = completePairingV2(token, approved)
            Log.d(TAG, "V2 pairing result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "V2 pairing FAILED with exception: ${e.message}", e)
            Log.d(TAG, "Falling back to V1 pairing...")
            try {
                val v1Result = completePairingV1(token, approved)
                Log.d(TAG, "V1 pairing result: $v1Result")
                v1Result
            } catch (v1Error: Exception) {
                Log.e(TAG, "V1 pairing also FAILED: ${v1Error.message}", v1Error)
                CompletePairingResult.Error("Pairing failed: ${e.message ?: v1Error.message}")
            }
        }
    }

    /**
     * V2 Pairing Completion - Uses approvePairingV2 Cloud Function
     * Supports device limits and persistent device IDs
     */
    private suspend fun completePairingV2(token: String, approved: Boolean): CompletePairingResult {
        Log.d(TAG, "Completing V2 pairing for token: ${token.take(8)}..., approved: $approved")

        val result = functions
            .getHttpsCallable("approvePairingV2")
            .call(mapOf(
                "token" to token,
                "approved" to approved
            ))
            .await()

        Log.d(TAG, "V2 Pairing completion result: $result")

        val data = result.data as? Map<*, *>
            ?: return CompletePairingResult.Error("Invalid response from server")

        val success = data["success"] as? Boolean ?: false
        val status = data["status"] as? String
        val error = data["error"] as? String

        return when {
            // Device limit reached - return special error with upgrade info
            error == "device_limit" -> {
                val currentDevices = (data["currentDevices"] as? Number)?.toInt() ?: 0
                val limit = (data["limit"] as? Number)?.toInt() ?: 3
                val message = data["message"] as? String
                    ?: "Device limit reached ($currentDevices/$limit). Upgrade to Pro for unlimited devices."
                Log.w(TAG, "Device limit reached: $message")
                CompletePairingResult.Error(message)
            }

            success && status == "approved" -> {
                val deviceId = data["deviceId"] as? String
                val userId = data["userId"] as? String
                val isRePairing = data["isRePairing"] as? Boolean ?: false

                if (isRePairing) {
                    Log.d(TAG, "V2 Re-pairing approved. Device ID: $deviceId (already existed)")
                } else {
                    Log.d(TAG, "V2 Pairing approved. Device ID: $deviceId, UserID: $userId")
                }

                CompletePairingResult.Approved(deviceId)
            }

            success && status == "rejected" -> {
                Log.d(TAG, "V2 Pairing rejected by user")
                CompletePairingResult.Rejected
            }

            else -> {
                val errorMessage = data["message"] as? String ?: "Unexpected status: $status"
                CompletePairingResult.Error(errorMessage)
            }
        }
    }

    /**
     * V1 Pairing Completion - Legacy Cloud Function
     */
    private suspend fun completePairingV1(token: String, approved: Boolean): CompletePairingResult {
        try {
            Log.d(TAG, "Completing V1 pairing for token: ${token.take(8)}..., approved: $approved")

            val result = functions
                .getHttpsCallable("completePairing")
                .call(mapOf(
                    "token" to token,
                    "approved" to approved
                ))
                .await()

            Log.d(TAG, "V1 Pairing completion result: $result")

            val data = result.data as? Map<*, *>
                ?: return CompletePairingResult.Error("Invalid response from server")

            val success = data["success"] as? Boolean ?: false
            val status = data["status"] as? String ?: "unknown"

            return when {
                success && status == "approved" -> {
                    val deviceId = data["deviceId"] as? String
                    val userId = data["userId"] as? String

                    Log.d(TAG, "V1 Pairing approved. Device ID: $deviceId, UserID: $userId")
                    CompletePairingResult.Approved(deviceId)
                }
                success && status == "rejected" -> {
                    Log.d(TAG, "V1 Pairing rejected by user")
                    CompletePairingResult.Rejected
                }
                else -> {
                    CompletePairingResult.Error("Unexpected status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error completing V1 pairing", e)
            return CompletePairingResult.Error(e.message ?: "Failed to complete pairing")
        }
    }

    /**
     * Get device info including device count and limits
     * Uses getDeviceInfoV2 Cloud Function
     *
     * Uses NonCancellable to prevent issues when navigating away from screens.
     */
    suspend fun getDeviceInfo(): DeviceInfoResult? = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        try {
            // Ensure we're signed in before calling cloud function
            val userId = try {
                getCurrentUserId()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get userId for getDeviceInfo: ${e.message}")
                return@withContext null
            }
            Log.d(TAG, "Fetching device info from getDeviceInfoV2 (user: $userId)")

            val result = functions
                .getHttpsCallable("getDeviceInfoV2")
                .call(emptyMap<String, Any>())
                .await()

            val data = result.data as? Map<*, *>
            if (data == null) {
                Log.w(TAG, "getDeviceInfoV2 returned null data")
                return@withContext null
            }

            val success = data["success"] as? Boolean ?: false
            if (!success) {
                Log.w(TAG, "getDeviceInfoV2 returned success=false")
                return@withContext null
            }

            val deviceCount = (data["deviceCount"] as? Number)?.toInt() ?: 0
            val deviceLimit = (data["deviceLimit"] as? Number)?.toInt() ?: 3
            val plan = data["plan"] as? String ?: "free"
            val canAddDevice = data["canAddDevice"] as? Boolean ?: (deviceCount < deviceLimit)

            // Parse devices list from Cloud Function response
            val devicesData = data["devices"] as? List<*> ?: emptyList<Any>()
            val devices = devicesData.mapNotNull { deviceData ->
                try {
                    val device = deviceData as? Map<*, *> ?: return@mapNotNull null
                    val deviceId = device["deviceId"] as? String ?: return@mapNotNull null
                    val platform = device["platform"] as? String ?: device["type"] as? String ?: "web"

                    // Skip Android devices - we only want desktop/web devices
                    if (platform == "android") return@mapNotNull null

                    PairedDevice(
                        id = deviceId,
                        name = device["name"] as? String ?: "Unknown Device",
                        platform = platform,
                        lastSeen = (device["lastSeen"] as? Number)?.toLong() ?: 0L,
                        syncStatus = null
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing device: ${e.message}")
                    null
                }
            }

            Log.d(TAG, "Device info: $deviceCount/$deviceLimit devices, plan=$plan, canAdd=$canAddDevice, parsedDevices=${devices.size}")

            DeviceInfoResult(
                deviceCount = deviceCount,
                deviceLimit = deviceLimit,
                plan = plan,
                canAddDevice = canAddDevice,
                devices = devices
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching device info", e)
            null
        }
    }

    /**
     * Parse QR code payload from Web/macOS
     * Returns pairing info if valid, null otherwise
     */
    fun parsePairingQrCode(qrData: String): PairingQrData? {
        return try {
            val json = org.json.JSONObject(qrData)
            val token = json.optString("token", "")
            val name = json.optString("name", "Desktop")
            val platform = json.optString("platform", "web")
            val version = json.optString("version", "1.0.0")
            val syncGroupId = json.optString("syncGroupId", "")

            if (token.isBlank()) {
                Log.w(TAG, "Invalid QR code: missing token")
                null
            } else {
                PairingQrData(token, name, platform, version, syncGroupId.ifBlank { null })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse pairing QR code: ${e.message}")
            null
        }
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
     * Write sent message to messages collection with E2EE encryption
     */
    suspend fun writeSentMessage(messageId: String, address: String, body: String) {
        try {
            val userId = getCurrentUserId()
            val messageRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MESSAGES_PATH)
                .child(messageId)

            val messageData = mutableMapOf<String, Any?>(
                "id" to messageId,
                "address" to address,
                "date" to System.currentTimeMillis(),
                "type" to 2, // 2 = sent message
                "timestamp" to ServerValue.TIMESTAMP
            )

            messageData.applyEncryptionPayload(userId, body)

            messageRef.setValue(messageData).await()
            Log.d(TAG, "Sent message written to messages collection (encrypted: ${messageData["encrypted"] == true})")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing sent message", e)
        }
    }

    /**
     * Write sent MMS message to messages collection with attachments
     */
    suspend fun writeSentMmsMessage(
        messageId: String,
        address: String,
        body: String,
        attachments: List<Map<String, Any?>>
    ) {
        try {
            val userId = getCurrentUserId()
            val messageRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MESSAGES_PATH)
                .child(messageId)

            val messageData = mutableMapOf<String, Any?>(
                "id" to messageId,
                "address" to address,
                "date" to System.currentTimeMillis(),
                "type" to 2, // 2 = sent message
                "timestamp" to ServerValue.TIMESTAMP,
                "isMms" to true,
                "attachments" to attachments
            )

            Log.d(TAG, "[FirebaseWrite] Writing sent MMS to Firebase - address: \"$address\" (normalized: \"${PhoneNumberUtils.normalizeForConversation(address)}\"), messageId: $messageId")

            messageData.applyEncryptionPayload(userId, body)

            messageRef.setValue(messageData).await()
            Log.d(TAG, "Sent MMS message written to messages collection with ${attachments.size} attachment(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing sent MMS message", e)
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

            // Use retry logic for Firebase write operations
            RetryUtils.withFirebaseRetry("syncGroup ${group.id}") {
                groupRef.setValue(groupData).await()
            }
            Log.d(TAG, "Group synced: ${group.id} (${group.name})")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing group after retries", e)
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

            // Use retry logic for Firebase write operations
            RetryUtils.withFirebaseRetry("syncCallEvent $callId") {
                callRef.setValue(callData).await()
            }
            Log.d(TAG, "Call event synced: $callId - $callState")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing call event after retries", e)
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

            // Use retry logic for Firebase write operations
            RetryUtils.withFirebaseRetry("updateCallState $callId") {
                callRef.updateChildren(mapOf(
                    "callState" to newState,
                    "lastUpdated" to ServerValue.TIMESTAMP
                )).await()
            }

            Log.d(TAG, "Call state updated: $callId -> $newState")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating call state after retries", e)
        }
    }

    // =========================================================================
    // SYNC HISTORY REQUESTS - Load older messages on demand from Mac/Web
    // =========================================================================

    /**
     * Listen for sync history requests from Mac/Web clients.
     * When a request comes in, load the requested messages and sync them.
     */
    fun listenForSyncRequests(): Flow<SyncHistoryRequest> = callbackFlow {
        val userId = runCatching { getCurrentUserId() }.getOrNull()
        if (userId == null) {
            close()
            return@callbackFlow
        }

        val syncRequestsRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(SYNC_REQUESTS_PATH)

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val requestId = snapshot.key ?: return
                val data = snapshot.value as? Map<String, Any?> ?: return

                val status = data["status"] as? String ?: "pending"
                if (status != "pending") return // Only process pending requests

                val request = SyncHistoryRequest(
                    id = requestId,
                    days = (data["days"] as? Number)?.toInt() ?: 30,
                    requestedAt = (data["requestedAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                    requestedBy = data["requestedBy"] as? String ?: "unknown"
                )

                trySend(request)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Listen for sync requests cancelled", error.toException())
            }
        }

        syncRequestsRef.addChildEventListener(listener)

        awaitClose {
            syncRequestsRef.removeEventListener(listener)
        }
    }

    /**
     * Process a sync history request - load messages for the requested period and sync them.
     */
    suspend fun processSyncHistoryRequest(request: SyncHistoryRequest) {
        val userId = getCurrentUserId()
        val requestRef = database.reference
            .child(USERS_PATH)
            .child(userId)
            .child(SYNC_REQUESTS_PATH)
            .child(request.id)

        try {
            Log.d(TAG, "Processing sync history request: ${request.id}, days=${request.days}")

            // Update status to in_progress
            requestRef.updateChildren(mapOf(
                "status" to "in_progress",
                "startedAt" to ServerValue.TIMESTAMP
            )).await()

            // Load messages for the requested period
            val smsRepository = SmsRepository(context)
            val messages = if (request.days <= 0) {
                // Load all messages (use a very large number of days)
                smsRepository.getMessagesFromLastDays(days = 3650) // ~10 years
            } else {
                smsRepository.getMessagesFromLastDays(days = request.days)
            }

            Log.d(TAG, "Loaded ${messages.size} messages for sync request ${request.id}")

            // Update progress
            requestRef.updateChildren(mapOf(
                "totalMessages" to messages.size,
                "syncedMessages" to 0
            )).await()

            // Sync messages in batches
            val batchSize = 50
            var syncedCount = 0

            messages.chunked(batchSize).forEach { batch ->
                syncMessages(batch)
                syncedCount += batch.size

                // Update progress
                requestRef.updateChildren(mapOf(
                    "syncedMessages" to syncedCount
                )).await()
            }

            // Mark as completed
            requestRef.updateChildren(mapOf(
                "status" to "completed",
                "completedAt" to ServerValue.TIMESTAMP,
                "syncedMessages" to messages.size
            )).await()

            Log.d(TAG, "Sync history request ${request.id} completed: ${messages.size} messages synced")

        } catch (e: Exception) {
            Log.e(TAG, "Error processing sync history request ${request.id}", e)

            // Mark as failed
            try {
                requestRef.updateChildren(mapOf(
                    "status" to "failed",
                    "error" to (e.message ?: "Unknown error"),
                    "failedAt" to ServerValue.TIMESTAMP
                )).await()
            } catch (updateError: Exception) {
                Log.e(TAG, "Failed to update request status", updateError)
            }
        }
    }

    /**
     * Get current sync settings (last synced date range)
     */
    suspend fun getSyncSettings(): SyncSettings? {
        return try {
            val userId = getCurrentUserId()
            val settingsRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child("sync_settings")

            val snapshot = settingsRef.get().await()
            if (!snapshot.exists()) return null

            val data = snapshot.value as? Map<String, Any?> ?: return null
            SyncSettings(
                lastSyncDays = (data["lastSyncDays"] as? Number)?.toInt() ?: 30,
                lastFullSyncAt = (data["lastFullSyncAt"] as? Number)?.toLong()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting sync settings", e)
            null
        }
    }
}

/**
 * Sync history request from Mac/Web
 */
data class SyncHistoryRequest(
    val id: String,
    val days: Int, // Number of days to sync, or -1 for all
    val requestedAt: Long,
    val requestedBy: String // Device ID that requested the sync
)

/**
 * Sync settings
 */
data class SyncSettings(
    val lastSyncDays: Int = 30,
    val lastFullSyncAt: Long? = null
)

/**
 * Represents a paired device
 */
data class PairedDevice(
    val id: String,
    val name: String,
    val platform: String,
    val lastSeen: Long,
    val syncStatus: SyncStatus? = null
)

/**
 * Sync status for a device
 */
data class SyncStatus(
    val status: String, // "idle", "starting", "syncing", "completed", "failed"
    val syncedMessages: Int = 0,
    val totalMessages: Int = 0,
    val lastSyncAttempt: Long = 0,
    val lastSyncCompleted: Long? = null,
    val errorMessage: String? = null
)

/**
 * Result of completing a pairing request
 */
sealed class CompletePairingResult {
    data class Approved(val deviceId: String?) : CompletePairingResult()
    data object Rejected : CompletePairingResult()
    data class Error(val message: String) : CompletePairingResult()
}

/**
 * Result of getDeviceInfo call
 */
data class DeviceInfoResult(
    val deviceCount: Int,
    val deviceLimit: Int,
    val plan: String,
    val canAddDevice: Boolean,
    val devices: List<PairedDevice> = emptyList()
)

/**
 * Data parsed from a pairing QR code
 */
data class PairingQrData(
    val token: String,
    val name: String,
    val platform: String,
    val version: String,
    val syncGroupId: String?
) {
    val displayName: String
        get() = "$name ($platform)"
}
