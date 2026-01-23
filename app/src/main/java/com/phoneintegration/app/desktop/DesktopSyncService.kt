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
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.data.database.Group
import com.phoneintegration.app.data.database.GroupMember
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.e2ee.SignalProtocolManager
import com.phoneintegration.app.usage.UsageCategory
import com.phoneintegration.app.usage.UsageCheck
import com.phoneintegration.app.usage.UsageTracker
import com.phoneintegration.app.utils.NetworkUtils
import com.phoneintegration.app.utils.RetryUtils
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
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

    companion object {
        private const val TAG = "DesktopSyncService"
        private const val MESSAGES_PATH = "messages"
        private const val DEVICES_PATH = "devices"
        private const val USERS_PATH = "users"
        private const val GROUPS_PATH = "groups"
        private const val ATTACHMENTS_PATH = "attachments"
        private const val MESSAGE_REACTIONS_PATH = "message_reactions"
        private const val SPAM_MESSAGES_PATH = "spam_messages"
    }

    init {
        // Initialize E2EE keys if not already done
        e2eeManager.initializeKeys()
    }

    /**
     * Get current user ID (restored to working system)
     */
    suspend fun getCurrentUserId(): String {
        // First try to get from AuthManager (real user)
        authManager.getCurrentUserId()?.let { userId ->
            e2eeManager.ensureDeviceKeysPublished()
            return userId
        }

        // If no real user, create anonymous user (restored working behavior)
        val result = authManager.signInAnonymously()
        return result.getOrNull() ?: run {
            Log.e(TAG, "Error signing in anonymously")
            throw Exception("Authentication failed")
        }.also {
            e2eeManager.ensureDeviceKeysPublished()
        }
    }

    /**
     * Get the "conversation partner" address for a message.
     * For received messages (type=1), this is the sender's address.
     * For sent messages (type=2), we need to find the recipient from the thread.
     */
    private suspend fun getConversationAddress(message: SmsMessage): String {
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
                val exact = recipients.firstOrNull {
                    PhoneNumberUtils.areNumbersEqual(it, message.address)
                }
                return exact ?: recipients.first()
            }

            return message.address
        }

        // For sent messages, we need to find the recipient
        // Query the thread to find a received message and get its address
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
            cursor?.use {
                if (it.moveToFirst()) {
                    threadId = it.getLong(2)
                }
            }

            // If we found the thread ID, look for a received message in that thread
            if (threadId != null) {
                val threadCursor = context.contentResolver.query(
                    uri,
                    arrayOf(android.provider.Telephony.Sms.ADDRESS),
                    "${android.provider.Telephony.Sms.THREAD_ID} = ? AND ${android.provider.Telephony.Sms.TYPE} = 1",
                    arrayOf(threadId.toString()),
                    "${android.provider.Telephony.Sms.DATE} DESC LIMIT 1"
                )

                threadCursor?.use {
                    if (it.moveToFirst()) {
                        val recipientAddress = it.getString(0)
                        if (!recipientAddress.isNullOrBlank()) {
                            Log.d(TAG, "Found conversation partner for sent message: $recipientAddress")
                            return recipientAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding conversation address", e)
        }

        // Fallback: return the original address
        return message.address
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
     * Sync a message to Firebase with E2EE encryption
     */
    suspend fun syncMessage(message: SmsMessage) {
        try {
            Log.d(TAG, "Starting sync for message: id=${message.id}, isMms=${message.isMms}, address=${message.address}, body length=${message.body?.length ?: 0}")

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
            e2eeManager.ensureDeviceKeysPublished()
            val messageKey = getFirebaseMessageKey(message)
            val messageRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(MESSAGES_PATH)
                .child(messageKey)

            // Get the normalized conversation address (the "other party")
            val conversationAddress = getConversationAddress(message)

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

            // Add SIM subscription ID if available
            message.subId?.let {
                messageData["subId"] = it
            }

            // Handle MMS attachments
            if (message.isMms) {
                Log.d(TAG, "Processing MMS attachments for message ${message.id}")
                messageData["isMms"] = true
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

            // Use retry logic for Firebase write operations
            RetryUtils.withFirebaseRetry("syncMessage ${message.id}") {
                messageRef.setValue(messageData).await()
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
        try {
            val userId = getCurrentUserId()
            val spamRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(SPAM_MESSAGES_PATH)
                .child(message.messageId.toString())

            val payload = mapOf(
                "messageId" to message.messageId,
                "address" to message.address,
                "body" to message.body,
                "date" to message.date,
                "contactName" to message.contactName,
                "spamConfidence" to message.spamConfidence,
                "spamReasons" to message.spamReasons,
                "detectedAt" to message.detectedAt,
                "isUserMarked" to message.isUserMarked,
                "isRead" to message.isRead,
                "timestamp" to ServerValue.TIMESTAMP
            )

            spamRef.setValue(payload).await()
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing spam message ${message.messageId}", e)
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
        try {
            val userId = getCurrentUserId()
            val spamRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(SPAM_MESSAGES_PATH)
                .child(messageId.toString())
            spamRef.removeValue().await()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting spam message $messageId", e)
        }
    }

    suspend fun clearAllSpamMessages() {
        try {
            val userId = getCurrentUserId()
            val spamRef = database.reference
                .child(USERS_PATH)
                .child(userId)
                .child(SPAM_MESSAGES_PATH)
            spamRef.removeValue().await()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing spam messages", e)
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
     * Sync multiple messages to Firebase
     */
    suspend fun syncMessages(messages: List<SmsMessage>) {
        // Early exit if no network - avoid looping through messages
        if (!NetworkUtils.isNetworkAvailable(context)) {
            Log.w(TAG, "No network available, skipping batch sync of ${messages.size} messages")
            return
        }

        for (message in messages) {
            syncMessage(message)
        }
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
                val platform = deviceSnapshot.child("platform").value as? String ?: "web"

                // Skip Android devices - those are for presence/calling, not desktop pairing
                if (platform == "android") {
                    return@forEach
                }

                // Check if device has basic pairing information
                val hasBasicInfo = deviceSnapshot.child("name").exists() &&
                                   deviceSnapshot.child("platform").exists()

                // Include devices that have basic info (be more permissive than strict isPaired check)
                if (hasBasicInfo) {
                    // Get sync status if available
                    val syncStatusSnapshot = deviceSnapshot.child("syncStatus")
                    val syncStatus = if (syncStatusSnapshot.exists()) {
                        SyncStatus(
                            status = syncStatusSnapshot.child("status").value as? String ?: "idle",
                            syncedMessages = syncStatusSnapshot.child("syncedMessages").value as? Int ?: 0,
                            totalMessages = syncStatusSnapshot.child("totalMessages").value as? Int ?: 0,
                            lastSyncAttempt = syncStatusSnapshot.child("lastSyncAttempt").value as? Long ?: 0L,
                            lastSyncCompleted = syncStatusSnapshot.child("lastSyncCompleted").value as? Long,
                            errorMessage = syncStatusSnapshot.child("errorMessage").value as? String
                        )
                    } else null

                    val device = PairedDevice(
                        id = deviceSnapshot.key ?: return@forEach,
                        name = deviceSnapshot.child("name").value as? String ?: "Unknown",
                        platform = platform,
                        lastSeen = deviceSnapshot.child("lastSeen").value as? Long ?: 0L,
                        isPaired = deviceSnapshot.child("isPaired").value as? Boolean ?: true, // Default to true for existing devices
                        syncStatus = syncStatus
                    )
                    devices.add(device)
                }
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
     */
    suspend fun completePairing(token: String, approved: Boolean): CompletePairingResult {
        try {
            val result = functions
                .getHttpsCallable("completePairing")
                .call(mapOf(
                    "token" to token,
                    "approved" to approved
                ))
                .await()

            val data = result.data as? Map<*, *>
                ?: return CompletePairingResult.Error("Invalid response from server")

            val success = data["success"] as? Boolean ?: false
            val status = data["status"] as? String ?: "unknown"

            return when {
                success && status == "approved" -> {
                    val deviceId = data["deviceId"] as? String
                    Log.d(TAG, "Pairing approved successfully. Device ID: $deviceId")
                    CompletePairingResult.Approved(deviceId)
                }
                success && status == "rejected" -> {
                    Log.d(TAG, "Pairing rejected by user")
                    CompletePairingResult.Rejected
                }
                else -> {
                    CompletePairingResult.Error("Unexpected status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error completing pairing", e)
            return CompletePairingResult.Error(e.message ?: "Failed to complete pairing")
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

            if (token.isBlank()) {
                Log.w(TAG, "Invalid QR code: missing token")
                null
            } else {
                PairingQrData(token, name, platform, version)
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
}

/**
 * Data class representing a paired device
 */
data class PairedDevice(
    val id: String,
    val name: String,
    val platform: String,
    val lastSeen: Long,
    val isPaired: Boolean,
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
 * Data parsed from a pairing QR code
 */
data class PairingQrData(
    val token: String,
    val name: String,
    val platform: String,
    val version: String
) {
    val displayName: String
        get() = "$name ($platform)"
}
