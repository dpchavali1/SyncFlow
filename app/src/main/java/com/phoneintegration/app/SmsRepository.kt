package com.phoneintegration.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsRepository(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    // Cache to avoid repeated contact lookups
    private val contactCache = mutableMapOf<String, String?>()

    // ---------------------------------------------------------------------
    //  CONTACT NAME LOOKUP (Slow → Use only in background)
    // ---------------------------------------------------------------------
    fun resolveContactName(address: String): String? {
        if (contactCache.containsKey(address)) return contactCache[address]

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
    suspend fun getAllMessages(limit: Int = 500): List<SmsMessage> =
        withContext(Dispatchers.IO) {
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
                "${Telephony.Sms.DATE} DESC LIMIT $limit"
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
                }
            }

            list
        }

    // ---------------------------------------------------------------------
    //  LOAD CONVERSATIONS (SUPER FAST)
    // ---------------------------------------------------------------------
    suspend fun getConversations(): List<ConversationInfo> =
        withContext(Dispatchers.IO) {

            val map = LinkedHashMap<Long, ConversationInfo>()

            // ------------------------------
            // 1) Load SMS conversations
            // ------------------------------
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
                null
            )

            smsCursor?.use { c ->
                while (c.moveToNext()) {

                    val threadId = c.getLong(0)
                    val address = c.getString(1) ?: continue

                    // Filter out RBM (Rich Business Messaging) spam
                    if (address.contains("@rbm.goog", ignoreCase = true)) {
                        continue
                    }

                    val body = c.getString(2) ?: ""
                    val ts = c.getLong(3)
                    val isRead = c.getInt(4) == 1

                    val cachedName = contactCache[address] ?: address

                    val existing = map[threadId]
                    if (existing == null) {
                        map[threadId] = ConversationInfo(
                            threadId = threadId,
                            address = address,
                            contactName = cachedName,
                            lastMessage = body,
                            timestamp = ts,
                            unreadCount = if (isRead) 0 else 1
                        )
                    } else {
                        map[threadId] = existing.copy(
                            lastMessage = if (ts > existing.timestamp) body else existing.lastMessage,
                            timestamp = maxOf(ts, existing.timestamp),
                            unreadCount = existing.unreadCount + if (isRead) 0 else 1
                        )
                    }
                }
            }

            // ------------------------------
            // 2) Load MMS conversations
            // ------------------------------
            val mmsCursor = resolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id", "thread_id", "date", "sub"),
                null,
                null,
                null
            )

            mmsCursor?.use { c ->
                while (c.moveToNext()) {
                    val mmsId = c.getLong(0)
                    val threadId = c.getLong(1)
                    val dateSec = c.getLong(2)
                    val subject = c.getString(3) ?: "(MMS)"

                    val timestamp = dateSec * 1000L

                    // Get ALL recipients to detect group conversations
                    val allRecipients = MmsHelper.getMmsAllRecipients(resolver, mmsId)
                    if (allRecipients.isEmpty()) continue

                    // Filter out RBM spam
                    if (allRecipients.any { it.contains("@rbm.goog", ignoreCase = true) }) {
                        continue
                    }

                    val isGroup = allRecipients.size > 1
                    val address = allRecipients.joinToString(", ")
                    val contactNames = allRecipients.joinToString(", ") { addr ->
                        contactCache[addr] ?: addr
                    }

                    val existing = map[threadId]
                    if (existing == null) {
                        map[threadId] = ConversationInfo(
                            threadId = threadId,
                            address = address,
                            contactName = contactNames,
                            lastMessage = subject,
                            timestamp = timestamp,
                            unreadCount = 0,
                            isGroupConversation = isGroup,
                            recipientCount = allRecipients.size
                        )
                    } else {
                        map[threadId] = existing.copy(
                            lastMessage = if (timestamp > existing.timestamp) subject else existing.lastMessage,
                            timestamp = maxOf(timestamp, existing.timestamp),
                            isGroupConversation = isGroup || existing.isGroupConversation,
                            recipientCount = maxOf(allRecipients.size, existing.recipientCount)
                        )
                    }
                }
            }

            // ------------------------------
            // 3) Return sorted list
            // ------------------------------
            return@withContext map.values.sortedByDescending { it.timestamp }
        }

    // ---------------------------------------------------------------------
    //  SEND SMS
    // ---------------------------------------------------------------------
    suspend fun sendSms(address: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                SmsManager.getDefault().sendTextMessage(address, null, body, null, null)
                kotlinx.coroutines.delay(200)
                true
            } catch (e: Exception) {
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
            }
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

            // Second pass: batch lookup contact names for unique addresses
            uniqueAddresses.forEach { address ->
                if (!contactCache.containsKey(address)) {
                    resolveContactName(address) // This caches it
                }
            }

            // Third pass: apply contact names from cache
            list.forEach { sms ->
                sms.contactName = contactCache[sms.address]
            }

            list
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

        val final = mutableListOf<SmsMessage>()

        // ------------------------------
        // 1) Load SMS
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
            null
        )

        smsCursor?.use { c ->
            while (c.moveToNext()) {
                val sms = SmsMessage(
                    id = c.getLong(0),
                    address = c.getString(1) ?: "",
                    body = c.getString(2) ?: "",
                    date = c.getLong(3),
                    type = c.getInt(4),
                    contactName = resolveContactName(c.getString(1) ?: "")
                )

                sms.category = MessageCategorizer.categorizeMessage(sms)
                sms.otpInfo = MessageCategorizer.extractOtp(sms.body)

                final.add(sms)
            }
        }

        // ------------------------------
        // 2) Load MMS
        // ------------------------------
        val mmsList = loadMmsForThread(threadId)
        final.addAll(mmsList)

        // ------------------------------
        // 3) Sort newest -> oldest
        // ------------------------------
        final.sortByDescending { it.date }

        // ------------------------------
        // 4) Apply limit/offset
        // ------------------------------
        return@withContext final.drop(offset).take(limit)
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
                val subject = c.getString(2)
                val msgBox = c.getInt(5)  // 1 = inbox, 2 = sent

                val timestamp = dateSec * 1000L

                val address = MmsHelper.getMmsAddress(resolver, mmsId) ?: continue
                val text = MmsHelper.getMmsText(resolver, mmsId)

                val attachments = loadMmsParts(mmsId)

                final.add(
                    SmsMessage(
                        id = mmsId,
                        address = address,
                        body = text ?: "",
                        date = timestamp,
                        type = if (msgBox == 2) 2 else 1,
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

}
