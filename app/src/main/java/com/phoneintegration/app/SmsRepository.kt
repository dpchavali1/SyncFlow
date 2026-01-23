package com.phoneintegration.app

import android.content.ContentResolver
import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.phoneintegration.app.utils.MemoryOptimizer
import com.phoneintegration.app.utils.MemoryPressure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsRepository(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val mmsCache = MmsAttachmentCache(context.applicationContext)
    private val memoryOptimizer = MemoryOptimizer.getInstance(context)

    // Cache to avoid repeated contact lookups
    private val contactCache = mutableMapOf<String, String?>()
    private var contactCachePreloaded = false

    // Memory-efficient pagination settings
    private val DEFAULT_PAGE_SIZE = 50
    private val MAX_CACHED_CONVERSATIONS = 100

    // ---------------------------------------------------------------------
    //  PRE-LOAD ALL CONTACTS (Fast batch loading)
    // ---------------------------------------------------------------------
    fun preloadContactCache() {
        if (contactCachePreloaded) return

        val startTime = System.currentTimeMillis()

        try {
            val cursor = resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                null,
                null,
                null
            )

            cursor?.use { c ->
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

                while (c.moveToNext()) {
                    val number = if (numIdx >= 0) c.getString(numIdx) else continue
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else continue

                    if (number != null && name != null) {
                        // Store with original format
                        contactCache[number] = name
                        // Also store normalized version (digits only)
                        val normalized = number.replace(Regex("[^0-9+]"), "")
                        if (normalized != number) {
                            contactCache[normalized] = name
                        }
                        // Store last 10 digits for flexible matching
                        if (normalized.length >= 10) {
                            contactCache[normalized.takeLast(10)] = name
                        }
                    }
                }
            }

            contactCachePreloaded = true
            android.util.Log.d("SmsRepository", "Preloaded ${contactCache.size} contacts in ${System.currentTimeMillis() - startTime}ms")
        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "Failed to preload contacts", e)
        }
    }

    // ---------------------------------------------------------------------
    //  CONTACT NAME LOOKUP (Fast if preloaded)
    // ---------------------------------------------------------------------
    fun resolveContactName(address: String): String? {
        // Check cache first
        contactCache[address]?.let { return it }

        // Try normalized lookup
        val normalized = address.replace(Regex("[^0-9+]"), "")
        contactCache[normalized]?.let {
            contactCache[address] = it
            return it
        }

        // Try last 10 digits
        if (normalized.length >= 10) {
            contactCache[normalized.takeLast(10)]?.let {
                contactCache[address] = it
                return it
            }
        }

        // Fallback to slow lookup if not in cache
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )

        val name = resolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

        contactCache[address] = name
        return name
    }

    // ---------------------------------------------------------------------
    //  GET THREAD ID FOR PHONE NUMBER
    // ---------------------------------------------------------------------
    private fun findThreadIdForAddress(address: String): Long? {
        val cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.ADDRESS} = ?",
            arrayOf(address),
            null
        )

        cursor?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return null
    }

    // ---------------------------------------------------------------------
    //  LOAD MESSAGES (FAST — THREAD BASED)
    // ---------------------------------------------------------------------
    suspend fun getMessages(address: String, limit: Int, offset: Int): List<SmsMessage> =
        withContext(Dispatchers.IO) {

            val threadId = findThreadIdForAddress(address)
                ?: return@withContext emptyList()

            val list = mutableListOf<SmsMessage>()

            val cursor = resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT $limit OFFSET $offset"
            ) ?: return@withContext emptyList()

            val cachedName = contactCache[address] ?: address

            cursor.use { c ->
                while (c.moveToNext()) {

                    val sms = SmsMessage(
                        id = c.getLong(0),
                        address = c.getString(1) ?: "",
                        body = c.getString(2) ?: "",
                        date = c.getLong(3),
                        type = c.getInt(4),
                        contactName = cachedName
                    )

                    sms.category = MessageCategorizer.categorizeMessage(sms)
                    sms.otpInfo = MessageCategorizer.extractOtp(sms.body)

                    list.add(sms)
                }
            }

            list
        }

    // ---------------------------------------------------------------------
    //  GET ALL MESSAGES (for AI Assistant)
    // ---------------------------------------------------------------------
    suspend fun getAllMessages(limit: Int = DEFAULT_PAGE_SIZE): List<SmsMessage> =
        withContext(Dispatchers.IO) {
            // Check memory pressure before loading large datasets
            val memoryStats = memoryOptimizer.getMemoryStats()

            // Reduce limit if memory is under pressure
            val adjustedLimit = when (memoryStats.pressure) {
                MemoryPressure.CRITICAL -> minOf(limit, 20)
                MemoryPressure.HIGH -> minOf(limit, 30)
                MemoryPressure.NORMAL -> limit
            }

            val list = mutableListOf<SmsMessage>()

            val cursor = resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT $adjustedLimit"
            ) ?: return@withContext emptyList()

            cursor.use { c ->
                while (c.moveToNext()) {
                    val sms = SmsMessage(
                        id = c.getLong(0),
                        address = c.getString(1) ?: "",
                        body = c.getString(2) ?: "",
                        date = c.getLong(3),
                        type = c.getInt(4)
                    )
                    list.add(sms)

                    // Track memory usage for large datasets
                    if (list.size % 100 == 0) {
                        memoryOptimizer.checkMemoryPressure()
                    }
                }
            }

            Log.d("SmsRepository", "Loaded ${list.size} messages (adjusted limit: $adjustedLimit, memory pressure: ${memoryStats.pressure})")
            list
        }

    // ---------------------------------------------------------------------
    //  LOAD CONVERSATIONS (MEMORY OPTIMIZED)
    // ---------------------------------------------------------------------
    suspend fun getConversations(limit: Int = MAX_CACHED_CONVERSATIONS): List<ConversationInfo> =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()

            // Check memory pressure and adjust limit
            val memoryStats = memoryOptimizer.getMemoryStats()
            val adjustedLimit = when (memoryStats.pressure) {
                MemoryPressure.CRITICAL -> minOf(limit, 20)
                MemoryPressure.HIGH -> minOf(limit, 50)
                MemoryPressure.NORMAL -> limit
            }

            val list = mutableListOf<ConversationInfo>()
            val addressesToResolve = mutableSetOf<String>()

            // Get actual unread counts for all threads upfront (limit to prevent memory issues)
            val unreadCounts = getUnreadCountsForThreads().let { counts ->
                // Only keep counts for recent conversations to save memory
                counts.entries.take(adjustedLimit).associate { it.key to it.value }
            }

            // Use the threads table which is MUCH faster than scanning all SMS
            val threadsCursor = resolver.query(
                Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf(
                    "_id",           // thread_id
                    "date",          // timestamp
                    "message_count", // total messages
                    "recipient_ids", // comma-separated recipient IDs
                    "snippet",       // last message preview
                    "read"           // read status
                ),
                null,
                null,
                "date DESC LIMIT 500"  // Increased limit for better coverage
            )

            threadsCursor?.use { c ->
                val idxId = c.getColumnIndex("_id")
                val idxDate = c.getColumnIndex("date")
                val idxSnippet = c.getColumnIndex("snippet")
                val idxRead = c.getColumnIndex("read")
                val idxRecipientIds = c.getColumnIndex("recipient_ids")

                while (c.moveToNext()) {
                    val threadId = if (idxId >= 0) c.getLong(idxId) else continue
                    val timestamp = if (idxDate >= 0) c.getLong(idxDate) else 0L
                    val snippet = if (idxSnippet >= 0) c.getString(idxSnippet) ?: "" else ""
                    val isRead = if (idxRead >= 0) c.getInt(idxRead) == 1 else true
                    val recipientIds = if (idxRecipientIds >= 0) c.getString(idxRecipientIds) ?: "" else ""

                    val recipientAddresses = getAddressesForRecipientIds(recipientIds)
                    val filteredRecipients = recipientAddresses.filterNot {
                        it.contains("@rbm.goog", ignoreCase = true) || isRcsAddress(it)
                    }
                    val address = filteredRecipients.firstOrNull()
                        ?: getAddressForThread(threadId)
                        ?: continue

                    // Filter out RBM spam
                    if (address.contains("@rbm.goog", ignoreCase = true)) continue
                    if (isRcsAddress(address)) continue

                    val isGroup = recipientIds.contains(" ") || filteredRecipients.size > 1
                    val recipientCount = if (filteredRecipients.isNotEmpty()) {
                        filteredRecipients.size
                    } else if (isGroup) {
                        recipientIds.split(Regex("[\\s,]+")).size
                    } else {
                        1
                    }

                    // Collect addresses that need name resolution
                    if (!contactCache.containsKey(address)) {
                        addressesToResolve.add(address)
                    }

                    list.add(
                        ConversationInfo(
                            threadId = threadId,
                            address = address,
                            contactName = contactCache[address] ?: address,
                            lastMessage = snippet,
                            timestamp = timestamp,
                            unreadCount = unreadCounts[threadId] ?: 0,
                            isGroupConversation = isGroup,
                            recipientCount = recipientCount
                        )
                    )
                }
            }

            // If threads table didn't work, fallback to traditional method
            if (list.isEmpty()) {
                return@withContext getConversationsFallback()
            }

            // Batch resolve contact names BEFORE returning (for first 50 to be fast)
            val priorityAddresses = addressesToResolve.take(50)
            for (address in priorityAddresses) {
                resolveContactName(address) // This caches the result
            }

            // Update list with resolved names
            val resolvedList = list.map { conv ->
                val resolvedName = contactCache[conv.address]
                if (resolvedName != null && resolvedName != conv.contactName) {
                    conv.copy(contactName = resolvedName)
                } else {
                    conv
                }
            }

            // Deduplicate by normalized phone number (handles +1234567890 vs 1234567890 duplicates)
            val result = com.phoneintegration.app.utils.MessageDeduplicator.deduplicateByNormalizedAddress(resolvedList)

            android.util.Log.d("SmsRepository", "Loaded ${result.size} conversations (${list.size} before dedupe) in ${System.currentTimeMillis() - startTime}ms")
            return@withContext result
        }

    /**
     * Get actual unread message counts for all threads in a single efficient query.
     * Returns a map of threadId -> unreadCount
     */
    private fun getUnreadCountsForThreads(): Map<Long, Int> {
        val counts = mutableMapOf<Long, Int>()
        try {
            // Count unread SMS messages grouped by thread
            val smsCursor = resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID),
                "${Telephony.Sms.READ} = 0 AND ${Telephony.Sms.TYPE} = 1", // Unread received messages
                null,
                null
            )
            smsCursor?.use { c ->
                val threadIdIdx = c.getColumnIndex(Telephony.Sms.THREAD_ID)
                while (c.moveToNext()) {
                    val threadId = if (threadIdIdx >= 0) c.getLong(threadIdIdx) else continue
                    counts[threadId] = (counts[threadId] ?: 0) + 1
                }
            }

            // Count unread MMS messages
            val mmsCursor = resolver.query(
                Uri.parse("content://mms"),
                arrayOf("thread_id"),
                "read = 0 AND msg_box = 1", // Unread inbox MMS
                null,
                null
            )
            mmsCursor?.use { c ->
                val threadIdIdx = c.getColumnIndex("thread_id")
                while (c.moveToNext()) {
                    val threadId = if (threadIdIdx >= 0) c.getLong(threadIdIdx) else continue
                    counts[threadId] = (counts[threadId] ?: 0) + 1
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "Failed to get unread counts", e)
        }
        return counts
    }

    // Get address for a thread ID
    private fun getAddressForThread(threadId: Long): String? {
        // Try SMS first
        val smsCursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS),
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT 1"
        )

        smsCursor?.use { c ->
            if (c.moveToFirst()) {
                val address = c.getString(0)
                if (!address.isNullOrBlank()) {
                    return address
                }
            }
        }

        // Try MMS if SMS didn't have it
        val mmsCursor = resolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id"),
            "thread_id = ?",
            arrayOf(threadId.toString()),
            "date DESC LIMIT 1"
        )

        mmsCursor?.use { c ->
            if (c.moveToFirst()) {
                val mmsId = c.getLong(0)
                val mmsAddress = MmsHelper.getMmsAddress(resolver, mmsId)
                if (!mmsAddress.isNullOrBlank() && !isRcsAddress(mmsAddress)) {
                    return mmsAddress
                }
            }
        }

        // Last resort: try to get any address from this thread
        val anyCursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS),
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            null
        )

        anyCursor?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(0)
                if (!address.isNullOrBlank() && !isRcsAddress(address)) {
                    return address
                }
            }
        }

        android.util.Log.w("SmsRepository", "Could not find address for thread $threadId")
        return null
    }

    private fun getAddressesForRecipientIds(recipientIds: String): List<String> {
        if (recipientIds.isBlank()) return emptyList()

        val ids = recipientIds
            .trim()
            .split(Regex("[\\s,]+"))
            .mapNotNull { it.toLongOrNull() }
            .distinct()

        if (ids.isEmpty()) return emptyList()

        val idList = ids.joinToString(",")
        val uri = Uri.parse("content://mms-sms/canonical-addresses")
        val map = LinkedHashMap<Long, String>()

        resolver.query(
            uri,
            arrayOf("_id", "address"),
            "_id IN ($idList)",
            null,
            null
        )?.use { c ->
            val idIdx = c.getColumnIndex("_id")
            val addrIdx = c.getColumnIndex("address")
            while (c.moveToNext()) {
                val id = if (idIdx >= 0) c.getLong(idIdx) else continue
                val address = if (addrIdx >= 0) c.getString(addrIdx) else null
                if (address.isNullOrBlank() || address == "insert-address-token") continue
                map[id] = address
            }
        }

        return ids.mapNotNull { map[it] }.filterNot { isRcsAddress(it) }
    }

    private fun isRcsAddress(address: String): Boolean {
        val lower = address.lowercase()
        return lower.contains("@rcs") ||
            lower.contains("rcs.google") ||
            lower.contains("rcs.goog") ||
            lower.startsWith("rcs:") ||
            lower.startsWith("rcs://")
    }

    // Fallback method - slower but works on all devices
    private fun getConversationsFallback(): List<ConversationInfo> {
        val map = LinkedHashMap<Long, ConversationInfo>()

        // Get actual unread counts
        val unreadCounts = getUnreadCountsForThreads()

        // Load recent SMS messages to build conversation list
        val smsCursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC LIMIT 2000"  // Reasonable limit
        )

        smsCursor?.use { c ->
            while (c.moveToNext()) {
                val threadId = c.getLong(0)
                val address = c.getString(1) ?: continue

                if (address.contains("@rbm.goog", ignoreCase = true)) continue
                if (isRcsAddress(address)) continue

                val body = c.getString(2) ?: ""
                val ts = c.getLong(3)
                val isRead = c.getInt(4) == 1

                if (!map.containsKey(threadId)) {
                    map[threadId] = ConversationInfo(
                        threadId = threadId,
                        address = address,
                        contactName = contactCache[address] ?: address,
                        lastMessage = body,
                        timestamp = ts,
                        unreadCount = unreadCounts[threadId] ?: 0
                    )
                }
            }
        }

        // Load recent MMS messages
        val mmsCursor = resolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "thread_id", "date", "sub", "sub_cs"),
            null,
            null,
            "date DESC LIMIT 500"  // Reasonable limit
        )

        mmsCursor?.use { c ->
            val subIdx = c.getColumnIndex("sub")
            val subCsIdx = c.getColumnIndex("sub_cs")
            while (c.moveToNext()) {
                val mmsId = c.getLong(0)
                val threadId = c.getLong(1)
                val dateSec = c.getLong(2)
                val subjectBytes = if (subIdx >= 0) c.getBlob(subIdx) else null
                val subjectRaw = if (subIdx >= 0) c.getString(subIdx) else null
                val subjectCharset = if (subCsIdx >= 0) c.getInt(subCsIdx) else null
                val subject = MmsHelper.decodeMmsSubject(subjectBytes, subjectRaw, subjectCharset) ?: "(MMS)"

                val timestamp = dateSec * 1000L

                if (!map.containsKey(threadId)) {
                    val address = MmsHelper.getMmsAddress(resolver, mmsId) ?: continue
                    if (address.contains("@rbm.goog", ignoreCase = true)) continue
                    if (isRcsAddress(address)) continue

                    map[threadId] = ConversationInfo(
                        threadId = threadId,
                        address = address,
                        contactName = contactCache[address] ?: address,
                        lastMessage = subject,
                        timestamp = timestamp,
                        unreadCount = unreadCounts[threadId] ?: 0
                    )
                } else {
                    val existing = map[threadId]!!
                    if (timestamp > existing.timestamp) {
                        map[threadId] = existing.copy(
                            lastMessage = subject,
                            timestamp = timestamp
                        )
                    }
                }
            }
        }

        val sorted = map.values.sortedByDescending { it.timestamp }
        // Deduplicate by normalized phone number
        return com.phoneintegration.app.utils.MessageDeduplicator.deduplicateByNormalizedAddress(sorted)
    }

    // ---------------------------------------------------------------------
    //  SEND SMS
    // ---------------------------------------------------------------------
    suspend fun sendSms(address: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // Send the SMS
                SmsManager.getDefault().sendTextMessage(address, null, body, null, null)
                Log.d("SmsRepository", "SMS sent to $address")

                // Manually write to sent folder
                // This is needed because SmsManager.sendTextMessage() doesn't write to sent folder
                // unless the app is the default SMS app
                try {
                    // Get or create thread ID for this address
                    val threadId = try {
                        Telephony.Threads.getOrCreateThreadId(context, setOf(address))
                    } catch (e: Exception) {
                        Log.w("SmsRepository", "Could not get thread ID: ${e.message}")
                        null
                    }

                    val values = android.content.ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, address)
                        put(Telephony.Sms.BODY, body)
                        put(Telephony.Sms.DATE, System.currentTimeMillis())
                        put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
                        put(Telephony.Sms.READ, 1)
                        put(Telephony.Sms.SEEN, 1)
                        put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT) // 2 = sent
                        put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
                        threadId?.let { put(Telephony.Sms.THREAD_ID, it) }
                    }
                    val uri = resolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
                    if (uri != null) {
                        Log.d("SmsRepository", "Wrote sent SMS to provider: $uri (threadId=$threadId)")
                    } else {
                        Log.w("SmsRepository", "Insert returned null - message may not be persisted")
                    }
                } catch (e: Exception) {
                    // This might fail if we don't have write permissions
                    // or if we're not the default SMS app on some devices
                    Log.w("SmsRepository", "Could not write to sent folder: ${e.message}")
                }

                kotlinx.coroutines.delay(200)
                true
            } catch (e: Exception) {
                Log.e("SmsRepository", "Failed to send SMS", e)
                false
            }
        }

    // ---------------------------------------------------------------------
    //  DELETE SMS
    // ---------------------------------------------------------------------
    suspend fun deleteMessage(id: Long): Boolean =
        withContext(Dispatchers.IO) {
            try {
                resolver.delete(
                    Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString()),
                    null,
                    null
                ) > 0
            } catch (e: Exception) {
                false
            }
        }

    /**
     * Delete all messages in a thread (conversation).
     * Returns true if successful.
     */
    suspend fun deleteThread(threadId: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (threadId <= 0) return@withContext false
            try {
                // Delete all SMS messages in the thread
                val smsDeleted = resolver.delete(
                    Telephony.Sms.CONTENT_URI,
                    "${Telephony.Sms.THREAD_ID} = ?",
                    arrayOf(threadId.toString())
                )

                // Delete all MMS messages in the thread
                val mmsDeleted = resolver.delete(
                    Uri.parse("content://mms"),
                    "thread_id = ?",
                    arrayOf(threadId.toString())
                )

                // Try to delete the thread itself
                try {
                    resolver.delete(
                        Uri.parse("content://mms-sms/conversations/$threadId"),
                        null,
                        null
                    )
                } catch (e: Exception) {
                    // Some devices don't allow direct thread deletion
                    Log.w("SmsRepository", "Could not delete thread directly: ${e.message}")
                }

                Log.d("SmsRepository", "Deleted thread $threadId: $smsDeleted SMS, $mmsDeleted MMS")
                true
            } catch (e: Exception) {
                Log.e("SmsRepository", "Failed to delete thread $threadId", e)
                false
            }
        }

    // ---------------------------------------------------------------------
    //  GET LATEST MESSAGE (AFTER SEND)
    // ---------------------------------------------------------------------
    suspend fun getLatestMessage(address: String): SmsMessage? =
        withContext(Dispatchers.IO) {

            val cursor = resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                "${Telephony.Sms.ADDRESS} = ?",
                arrayOf(address),
                "${Telephony.Sms.DATE} DESC LIMIT 1"
            ) ?: return@withContext null

            cursor.use { c ->
                if (c.moveToFirst()) {
                    val cachedName = contactCache[address] ?: address
                    val sms = SmsMessage(
                        id = c.getLong(0),
                        address = c.getString(1) ?: "",
                        body = c.getString(2) ?: "",
                        date = c.getLong(3),
                        type = c.getInt(4),
                        contactName = cachedName
                    )
                    sms.category = MessageCategorizer.categorizeMessage(sms)
                    sms.otpInfo = MessageCategorizer.extractOtp(sms.body)
                    sms
                } else null
            }?.let { return@withContext it }

            // Fallback: normalize and scan recent sent messages (handles +1 / formatting differences)
            val normalizedInput = PhoneNumberUtils.normalizeForConversation(address)
            val fallbackCursor = resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                "${Telephony.Sms.TYPE} = ?",
                arrayOf(Telephony.Sms.MESSAGE_TYPE_SENT.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT 25"
            ) ?: return@withContext null

            fallbackCursor.use { c ->
                while (c.moveToNext()) {
                    val candidateAddress = c.getString(1) ?: ""
                    val normalizedCandidate = PhoneNumberUtils.normalizeForConversation(candidateAddress)
                    if (normalizedCandidate == normalizedInput) {
                        val cachedName = contactCache[address] ?: address
                        val sms = SmsMessage(
                            id = c.getLong(0),
                            address = candidateAddress,
                            body = c.getString(2) ?: "",
                            date = c.getLong(3),
                            type = c.getInt(4),
                            contactName = cachedName
                        )
                        sms.category = MessageCategorizer.categorizeMessage(sms)
                        sms.otpInfo = MessageCategorizer.extractOtp(sms.body)
                        return@withContext sms
                    }
                }
            }

            null
        }

    // ---------------------------------------------------------------------
    //  GET LATEST MMS MESSAGE (AFTER SEND)
    // ---------------------------------------------------------------------
    suspend fun getLatestMmsMessage(address: String): SmsMessage? =
        withContext(Dispatchers.IO) {
            val normalizedInput = address.replace(Regex("[^0-9+]"), "").takeLast(10)

            val mmsCursor = resolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id", "date", "sub", "sub_cs", "m_type", "msg_box"),
                null,
                null,
                "date DESC LIMIT 10"
            ) ?: return@withContext null

            mmsCursor.use { c ->
                while (c.moveToNext()) {
                    val mmsId = c.getLong(0)
                    val dateSec = c.getLong(1)
                    val subjectBytes = c.getBlob(2)
                    val subjectRaw = c.getString(2)
                    val subjectCharset = c.getInt(3)
                    val subject = MmsHelper.decodeMmsSubject(subjectBytes, subjectRaw, subjectCharset)
                    val msgBox = c.getInt(5)  // 1 = inbox, 2 = sent

                    // Only looking for sent MMS
                    if (msgBox != 2) continue

                    val timestamp = dateSec * 1000L

                    // Get all recipients for this MMS
                    val recipients = MmsHelper.getMmsAllRecipients(resolver, mmsId)
                        .filter { it.isNotBlank() && !it.contains("insert-address-token", ignoreCase = true) }
                        .map { it.replace(Regex("[^0-9+]"), "").takeLast(10) }

                    // Check if any recipient matches
                    if (!recipients.any { it == normalizedInput }) continue

                    val mmsAddress = recipients.firstOrNull() ?: continue
                    val text = MmsHelper.getMmsText(resolver, mmsId) ?: mmsCache.loadBody(mmsId)
                    val attachments = loadMmsParts(mmsId)

                    return@withContext SmsMessage(
                        id = mmsId,
                        address = mmsAddress,
                        body = text ?: "",
                        date = timestamp,
                        type = 2,
                        contactName = contactCache[address],
                        isMms = true,
                        mmsAttachments = attachments,
                        mmsSubject = subject
                    )
                }
            }

            null
        }

    // ---------------------------------------------------------------------
    //  GET ALL RECENT MESSAGES (FOR DESKTOP SYNC)
    // ---------------------------------------------------------------------
    suspend fun getAllRecentMessages(limit: Int = 100): List<SmsMessage> =
        withContext(Dispatchers.IO) {
            val list = mutableListOf<SmsMessage>()
            val uniqueAddresses = mutableSetOf<String>()

            val cursor = resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT $limit"
            ) ?: return@withContext emptyList()

            // First pass: collect messages and unique addresses
            cursor.use { c ->
                while (c.moveToNext()) {
                    val address = c.getString(1) ?: continue
                    if (isRcsAddress(address)) continue
                    uniqueAddresses.add(address)

                    val sms = SmsMessage(
                        id = c.getLong(0),
                        address = address,
                        body = c.getString(2) ?: "",
                        date = c.getLong(3),
                        type = c.getInt(4),
                        contactName = null // Will fill in next step
                    )

                    sms.category = MessageCategorizer.categorizeMessage(sms)
                    sms.otpInfo = MessageCategorizer.extractOtp(sms.body)

                    list.add(sms)
                }
            }

            // Include recent MMS (attachments metadata only)
            val mmsList = loadRecentMms(limit)
            mmsList.forEach { mms ->
                uniqueAddresses.add(mms.address)
            }
            list.addAll(mmsList)

            // Sort across SMS + MMS
            list.sortByDescending { it.date }

            val result = list.take(limit)

            // Second pass: batch lookup contact names for unique addresses
            uniqueAddresses.forEach { address ->
                if (!contactCache.containsKey(address)) {
                    resolveContactName(address) // This caches it
                }
            }

            // Third pass: apply contact names from cache
            result.forEach { sms ->
                sms.contactName = contactCache[sms.address]
            }

            result
        }

    private fun loadRecentMms(limit: Int): List<SmsMessage> {
        val final = mutableListOf<SmsMessage>()

        val mmsCursor = resolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "date", "sub", "sub_cs", "m_type", "msg_box"),
            null,
            null,
            "date DESC LIMIT $limit"
        ) ?: return emptyList()

        mmsCursor.use { c ->
            while (c.moveToNext()) {
                val mmsId = c.getLong(0)
                val dateSec = c.getLong(1)
                val subjectBytes = c.getBlob(2)
                val subjectRaw = c.getString(2)
                val subjectCharset = c.getInt(3)
                val subject = MmsHelper.decodeMmsSubject(subjectBytes, subjectRaw, subjectCharset)
                val msgBox = c.getInt(5)  // 1 = inbox, 2 = sent

                val timestamp = dateSec * 1000L

                var address = MmsHelper.getMmsAddress(resolver, mmsId)
                if (msgBox == 2 && (address.isNullOrBlank() || address.contains("insert-address-token", ignoreCase = true))) {
                    val recipients = MmsHelper.getMmsAllRecipients(resolver, mmsId)
                        .filter { it.isNotBlank() && !it.contains("insert-address-token", ignoreCase = true) }
                    address = recipients.firstOrNull() ?: address
                }

                if (address.isNullOrBlank()) continue
                if (isRcsAddress(address)) continue

                val text = MmsHelper.getMmsText(resolver, mmsId)
                    ?: mmsCache.loadBody(mmsId)
                val attachments = loadMmsPartsMetadata(mmsId)

                final.add(
                    SmsMessage(
                        id = mmsId,
                        address = address,
                        body = text ?: "",
                        date = timestamp,
                        type = if (msgBox != 1) 2 else 1,  // 1=inbox(received), 2/3/4/5=sent/draft/outbox/failed(outgoing)
                        contactName = null,
                        isMms = true,
                        mmsAttachments = attachments,
                        mmsSubject = subject
                    )
                )
            }
        }

        return final
    }

    suspend fun getRecentMmsMessages(limit: Int = 10): List<SmsMessage> =
        withContext(Dispatchers.IO) {
            loadRecentMms(limit)
        }

    suspend fun getRecentInboxMmsMessages(limit: Int = 10): List<SmsMessage> =
        withContext(Dispatchers.IO) {
            loadRecentMmsByBox(limit, inboxOnly = true)
        }

    private fun loadRecentMmsByBox(limit: Int, inboxOnly: Boolean): List<SmsMessage> {
        val final = mutableListOf<SmsMessage>()

        val selection = if (inboxOnly) "msg_box = 1" else null
        val mmsCursor = resolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "date", "sub", "sub_cs", "m_type", "msg_box"),
            selection,
            null,
            "date DESC LIMIT $limit"
        ) ?: return emptyList()

        mmsCursor.use { c ->
            while (c.moveToNext()) {
                val mmsId = c.getLong(0)
                val dateSec = c.getLong(1)
                val subjectBytes = c.getBlob(2)
                val subjectRaw = c.getString(2)
                val subjectCharset = c.getInt(3)
                val subject = MmsHelper.decodeMmsSubject(subjectBytes, subjectRaw, subjectCharset)
                val msgBox = c.getInt(5)

                val timestamp = dateSec * 1000L
                val rawAddress = MmsHelper.getMmsAddress(resolver, mmsId)
                Log.d("SmsRepository", "MMS $mmsId raw address: '$rawAddress'")
                val address = rawAddress
                    ?.takeIf { it.isNotBlank() && !it.contains("insert-address-token", ignoreCase = true) }
                    ?: continue
                Log.d("SmsRepository", "MMS $mmsId final address: '$address'")

                if (isRcsAddress(address)) continue

                val text = MmsHelper.getMmsText(resolver, mmsId)
                    ?: mmsCache.loadBody(mmsId)
                val attachments = loadMmsPartsMetadata(mmsId)

                final.add(
                    SmsMessage(
                        id = mmsId,
                        address = address,
                        body = text ?: "",
                        date = timestamp,
                        type = if (msgBox != 1) 2 else 1,
                        contactName = null,
                        isMms = true,
                        mmsAttachments = attachments,
                        mmsSubject = subject
                    )
                )
            }
        }

        return final
    }

    fun resolveContactPhoto(address: String): String? {
        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )

        resolver.query(
            lookupUri,
            arrayOf(
                ContactsContract.PhoneLookup.PHOTO_URI
            ),
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                return c.getString(0) // photo URI
            }
        }
        return null
    }
    suspend fun getMessagesByThreadId(
        threadId: Long,
        limit: Int,
        offset: Int
    ): List<SmsMessage> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val final = mutableListOf<SmsMessage>()

        // ------------------------------
        // 1) Load SMS (FAST - no contact lookups)
        // ------------------------------
        val smsCursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            ),
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} DESC" // Sort in DB for speed
        )

        var firstAddress: String? = null

        smsCursor?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(1) ?: ""
                if (isRcsAddress(address)) continue
                if (firstAddress == null) firstAddress = address

                val sms = SmsMessage(
                    id = c.getLong(0),
                    address = address,
                    body = c.getString(2) ?: "",
                    date = c.getLong(3),
                    type = c.getInt(4),
                    contactName = null // Skip contact lookup for speed - resolve later
                )

                // Only categorize if needed (skip for sent messages)
                if (sms.type == 1) {
                    sms.category = MessageCategorizer.categorizeMessage(sms)
                    sms.otpInfo = MessageCategorizer.extractOtp(sms.body)
                }

                final.add(sms)
            }
        }

        // ------------------------------
        // 2) Load MMS (FAST - defer attachment data)
        // ------------------------------
        val mmsList = loadMmsForThreadFast(threadId)
        final.addAll(mmsList)

        // ------------------------------
        // 3) Sort newest -> oldest (already sorted from DB for SMS)
        // ------------------------------
        if (mmsList.isNotEmpty()) {
            final.sortByDescending { it.date }
        }

        // ------------------------------
        // 4) Apply limit/offset
        // ------------------------------
        val result = final.drop(offset).take(limit)

        // ------------------------------
        // 5) Resolve contact name once for all messages (from cache)
        // ------------------------------
        if (firstAddress != null) {
            val cachedName = contactCache[firstAddress] ?: firstAddress
            result.forEach { it.contactName = cachedName }
        }

        android.util.Log.d("SmsRepository", "Loaded ${result.size} messages for thread $threadId in ${System.currentTimeMillis() - startTime}ms")

        return@withContext result
    }

    /**
     * Load messages from multiple thread IDs (for merged conversations)
     * This is used when a contact has multiple threads due to different phone number formats
     */
    suspend fun getMessagesByThreadIds(
        threadIds: List<Long>,
        limit: Int,
        offset: Int
    ): List<SmsMessage> = withContext(Dispatchers.IO) {
        if (threadIds.isEmpty()) return@withContext emptyList()
        if (threadIds.size == 1) return@withContext getMessagesByThreadId(threadIds[0], limit, offset)

        val startTime = System.currentTimeMillis()
        val allMessages = mutableListOf<SmsMessage>()
        var firstAddress: String? = null

        // Load SMS from all threads
        for (threadId in threadIds) {
            val smsCursor = resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC"
            )

            smsCursor?.use { c ->
                while (c.moveToNext()) {
                    val address = c.getString(1) ?: ""
                    if (isRcsAddress(address)) continue
                    if (firstAddress == null) firstAddress = address

                    val sms = SmsMessage(
                        id = c.getLong(0),
                        address = address,
                        body = c.getString(2) ?: "",
                        date = c.getLong(3),
                        type = c.getInt(4),
                        contactName = null
                    )

                    if (sms.type == 1) {
                        sms.category = MessageCategorizer.categorizeMessage(sms)
                        sms.otpInfo = MessageCategorizer.extractOtp(sms.body)
                    }

                    allMessages.add(sms)
                }
            }

            // Load MMS from this thread
            val mmsList = loadMmsForThreadFast(threadId)
            allMessages.addAll(mmsList)
        }

        // Sort all messages by date descending
        allMessages.sortByDescending { it.date }

        // Apply limit/offset
        val result = allMessages.drop(offset).take(limit)

        // Resolve contact name
        if (firstAddress != null) {
            val cachedName = contactCache[firstAddress] ?: firstAddress
            result.forEach { it.contactName = cachedName }
        }

        android.util.Log.d("SmsRepository", "Loaded ${result.size} messages from ${threadIds.size} threads in ${System.currentTimeMillis() - startTime}ms")

        return@withContext result
    }

    // Fast MMS loading - defer attachment data loading
    private fun loadMmsForThreadFast(threadId: Long): List<SmsMessage> {
        val final = mutableListOf<SmsMessage>()

        val mmsCursor = resolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "date", "sub", "sub_cs", "m_type", "msg_box"),
            "thread_id = ?",
            arrayOf(threadId.toString()),
            "date DESC"
        ) ?: return emptyList()

        mmsCursor.use { c ->
            while (c.moveToNext()) {
                val mmsId = c.getLong(0)
                val dateSec = c.getLong(1)
                val subjectBytes = c.getBlob(2)
                val subjectRaw = c.getString(2)
                val subjectCharset = c.getInt(3)
                val subject = MmsHelper.decodeMmsSubject(subjectBytes, subjectRaw, subjectCharset)
                val msgBox = c.getInt(5)  // 1 = inbox, 2 = sent

                val timestamp = dateSec * 1000L

                val address = MmsHelper.getMmsAddress(resolver, mmsId) ?: continue
                if (isRcsAddress(address)) continue
                val text = MmsHelper.getMmsText(resolver, mmsId)
                    ?: mmsCache.loadBody(mmsId)

                // Load attachments metadata only (defer actual data)
                val attachments = loadMmsPartsMetadata(mmsId)

                final.add(
                    SmsMessage(
                        id = mmsId,
                        address = address,
                        body = text ?: "",
                        date = timestamp,
                        type = if (msgBox != 1) 2 else 1,  // 1=inbox(received), 2/3/4/5=sent/draft/outbox/failed(outgoing)
                        contactName = contactCache[address] ?: address,
                        isMms = true,
                        mmsAttachments = attachments,
                        mmsSubject = subject
                    )
                )
            }
        }

        return final
    }

    // Load MMS parts metadata only (no actual data - much faster)
    private fun loadMmsPartsMetadata(mmsId: Long): List<MmsAttachment> {
        val list = mutableListOf<MmsAttachment>()

        val partCursor = resolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "name", "fn"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        ) ?: return emptyList()

        partCursor.use { pc ->
            while (pc.moveToNext()) {
                val partId = pc.getLong(0)
                val contentType = pc.getString(1) ?: ""
                val fileName = pc.getString(2) ?: pc.getString(3)

                // Skip text parts
                if (contentType == "text/plain" || contentType == "application/smil") continue

                val partUri = "content://mms/part/$partId"

                list.add(
                    MmsAttachment(
                        id = partId,
                        contentType = contentType,
                        filePath = partUri,
                        data = null, // Defer loading actual data
                        fileName = fileName
                    )
                )
            }
        }

        if (list.isEmpty()) {
            return mmsCache.loadAttachments(mmsId)
        }

        return list
    }

    private fun loadMmsForThread(threadId: Long): List<SmsMessage> {
        val final = mutableListOf<SmsMessage>()

        val mmsCursor = resolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "date", "sub", "sub_cs", "m_type", "msg_box"),
            "thread_id = ?",
            arrayOf(threadId.toString()),
            null
        ) ?: return emptyList()

        mmsCursor.use { c ->
            while (c.moveToNext()) {
                val mmsId = c.getLong(0)
                val dateSec = c.getLong(1)
                val subjectBytes = c.getBlob(2)
                val subjectRaw = c.getString(2)
                val subjectCharset = c.getInt(3)
                val subject = MmsHelper.decodeMmsSubject(subjectBytes, subjectRaw, subjectCharset)
                val msgBox = c.getInt(5)  // 1 = inbox, 2 = sent

                val timestamp = dateSec * 1000L

                val address = MmsHelper.getMmsAddress(resolver, mmsId) ?: continue
                if (isRcsAddress(address)) continue
                val text = MmsHelper.getMmsText(resolver, mmsId)
                    ?: mmsCache.loadBody(mmsId)

                val attachments = loadMmsParts(mmsId)

                final.add(
                    SmsMessage(
                        id = mmsId,
                        address = address,
                        body = text ?: "",
                        date = timestamp,
                        type = if (msgBox != 1) 2 else 1,  // 1=inbox(received), 2/3/4/5=sent/draft/outbox/failed(outgoing)
                        contactName = resolveContactName(address),
                        isMms = true,
                        mmsAttachments = attachments,
                        mmsSubject = subject
                    )
                )
            }
        }

        return final
    }

    private fun loadMmsParts(mmsId: Long): List<MmsAttachment> {
        val list = mutableListOf<MmsAttachment>()

        val partCursor = resolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "name", "text", "fn", "cid"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        ) ?: return emptyList()

        partCursor.use { pc ->
            while (pc.moveToNext()) {
                val partId = pc.getLong(0)
                val contentType = pc.getString(1) ?: ""
                val fileName = pc.getString(2) ?: pc.getString(4)

                val partUri = "content://mms/part/$partId"

                val data: ByteArray? =
                    if (contentType.startsWith("image/") ||
                        contentType.startsWith("video/") ||
                        contentType.startsWith("audio/")
                    ) {
                        resolver.openInputStream(Uri.parse(partUri))?.readBytes()
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

        if (list.isEmpty()) {
            return mmsCache.loadAttachments(mmsId)
        }

        return list
    }
    fun getThreadIdForAddress(address: String): Long? {
        return resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.ADDRESS} = ?",
            arrayOf(address),
            null
        )?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    // ---------------------------------------------------------------------
    //  SEARCH MESSAGES (INDEXED)
    // ---------------------------------------------------------------------

    /**
     * Search messages by query string
     * Uses SQL LIKE with optimized query for speed
     */
    suspend fun searchMessages(
        query: String,
        limit: Int = 50
    ): List<SmsMessage> = withContext(Dispatchers.IO) {
        if (query.isBlank() || query.length < 2) return@withContext emptyList()

        val startTime = System.currentTimeMillis()
        val results = mutableListOf<SmsMessage>()
        val searchPattern = "%${query.trim()}%"

        // Search in SMS body
        val smsCursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.THREAD_ID
            ),
            "${Telephony.Sms.BODY} LIKE ?",
            arrayOf(searchPattern),
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )

        smsCursor?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(1) ?: continue
                val sms = SmsMessage(
                    id = c.getLong(0),
                    address = address,
                    body = c.getString(2) ?: "",
                    date = c.getLong(3),
                    type = c.getInt(4),
                    contactName = contactCache[address] ?: resolveContactName(address)
                )
                results.add(sms)
            }
        }

        android.util.Log.d("SmsRepository", "Search '$query' found ${results.size} results in ${System.currentTimeMillis() - startTime}ms")
        results
    }

    /**
     * Search conversations by contact name or phone number
     */
    suspend fun searchConversations(
        query: String,
        conversations: List<ConversationInfo>
    ): List<ConversationInfo> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext conversations

        val lowerQuery = query.lowercase().trim()

        conversations.filter { conv ->
            conv.contactName?.lowercase()?.contains(lowerQuery) == true ||
            conv.address.contains(lowerQuery) ||
            conv.lastMessage.lowercase().contains(lowerQuery)
        }
    }

    /**
     * Search messages within a specific conversation
     */
    suspend fun searchInConversation(
        threadId: Long,
        query: String,
        limit: Int = 50
    ): List<SmsMessage> = withContext(Dispatchers.IO) {
        if (query.isBlank() || query.length < 2) return@withContext emptyList()

        val results = mutableListOf<SmsMessage>()
        val searchPattern = "%${query.trim()}%"

        val cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            ),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.BODY} LIKE ?",
            arrayOf(threadId.toString(), searchPattern),
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )

        cursor?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(1) ?: continue
                results.add(
                    SmsMessage(
                        id = c.getLong(0),
                        address = address,
                        body = c.getString(2) ?: "",
                        date = c.getLong(3),
                        type = c.getInt(4),
                        contactName = contactCache[address]
                    )
                )
            }
        }

        results
    }

    /**
     * Search by date range
     */
    suspend fun searchByDateRange(
        startDate: Long,
        endDate: Long,
        limit: Int = 100
    ): List<SmsMessage> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SmsMessage>()

        val cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            ),
            "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} <= ?",
            arrayOf(startDate.toString(), endDate.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )

        cursor?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(1) ?: continue
                results.add(
                    SmsMessage(
                        id = c.getLong(0),
                        address = address,
                        body = c.getString(2) ?: "",
                        date = c.getLong(3),
                        type = c.getInt(4),
                        contactName = contactCache[address] ?: resolveContactName(address)
                    )
                )
            }
        }

        results
    }

    /**
     * Get messages containing links/URLs
     */
    suspend fun getMessagesWithLinks(limit: Int = 50): List<SmsMessage> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SmsMessage>()

        // Search for common URL patterns
        val urlPatterns = listOf("%http://%", "%https://%", "%www.%")

        for (pattern in urlPatterns) {
            if (results.size >= limit) break

            val cursor = resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                "${Telephony.Sms.BODY} LIKE ?",
                arrayOf(pattern),
                "${Telephony.Sms.DATE} DESC LIMIT ${limit - results.size}"
            )

            cursor?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    // Avoid duplicates
                    if (results.any { it.id == id }) continue

                    val address = c.getString(1) ?: continue
                    results.add(
                        SmsMessage(
                            id = id,
                            address = address,
                            body = c.getString(2) ?: "",
                            date = c.getLong(3),
                            type = c.getInt(4),
                            contactName = contactCache[address] ?: resolveContactName(address)
                        )
                    )
                }
            }
        }

        results.sortedByDescending { it.date }
    }

    // ---------------------------------------------------------------------
    //  DEBUG: Find messages for a phone number (flexible matching)
    // ---------------------------------------------------------------------

    /**
     * Debug method to find all messages for a phone number using flexible matching.
     * This helps diagnose missing conversations.
     */
    suspend fun debugFindMessagesForNumber(phoneNumber: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, Any>()
            val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            val last7Digits = cleanNumber.takeLast(7)

            android.util.Log.d("SmsRepository", "DEBUG: Searching for phone number: $phoneNumber")
            android.util.Log.d("SmsRepository", "DEBUG: Clean number: $cleanNumber, last 7: $last7Digits")

            // Find all unique addresses that might match
            val matchingAddresses = mutableSetOf<String>()
            val addressCursor = resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS),
                null,
                null,
                null
            )

            addressCursor?.use { c ->
                while (c.moveToNext()) {
                    val addr = c.getString(0) ?: continue
                    val cleanAddr = addr.replace(Regex("[^0-9+]"), "")
                    if (cleanAddr.endsWith(last7Digits) ||
                        android.telephony.PhoneNumberUtils.compare(addr, phoneNumber)) {
                        matchingAddresses.add(addr)
                    }
                }
            }

            result["matchingAddresses"] = matchingAddresses.toList()
            android.util.Log.d("SmsRepository", "DEBUG: Found ${matchingAddresses.size} matching addresses: $matchingAddresses")

            // Find thread IDs for matching addresses
            val threadIds = mutableSetOf<Long>()
            for (addr in matchingAddresses) {
                val cursor = resolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.THREAD_ID),
                    "${Telephony.Sms.ADDRESS} = ?",
                    arrayOf(addr),
                    null
                )
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        threadIds.add(c.getLong(0))
                    }
                }
            }

            result["threadIds"] = threadIds.toList()
            android.util.Log.d("SmsRepository", "DEBUG: Found thread IDs: $threadIds")

            // Count messages in each thread
            val messageCounts = mutableMapOf<Long, Int>()
            for (threadId in threadIds) {
                val cursor = resolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf("COUNT(*)"),
                    "${Telephony.Sms.THREAD_ID} = ?",
                    arrayOf(threadId.toString()),
                    null
                )
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        messageCounts[threadId] = c.getInt(0)
                    }
                }
            }

            result["messageCounts"] = messageCounts
            android.util.Log.d("SmsRepository", "DEBUG: Message counts: $messageCounts")

            // Check MMS as well
            val mmsCount = resolver.query(
                Uri.parse("content://mms"),
                arrayOf("COUNT(*)"),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            } ?: 0

            result["totalMmsCount"] = mmsCount
            android.util.Log.d("SmsRepository", "DEBUG: Total MMS count: $mmsCount")

            result
        }

    /**
     * Mark all SMS/MMS in the given thread as read so the conversation list clears the unread badge.
     */
    fun markThreadRead(threadId: Long) {
        if (threadId <= 0) return

        try {
            val smsValues = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            resolver.update(
                Telephony.Sms.CONTENT_URI,
                smsValues,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString())
            )

            val threadValues = ContentValues().apply {
                put(Telephony.Threads.READ, 1)
            }
            resolver.update(
                Telephony.Threads.CONTENT_URI,
                threadValues,
                "${Telephony.Threads._ID} = ?",
                arrayOf(threadId.toString())
            )

            val mmsValues = ContentValues().apply {
                put("read", 1)
            }
            resolver.update(
                Uri.parse("content://mms"),
                mmsValues,
                "thread_id = ? AND read = 0",
                arrayOf(threadId.toString())
            )
        } catch (e: Exception) {
            Log.e("SmsRepository", "Failed to mark thread $threadId as read", e)
        }
    }
}
