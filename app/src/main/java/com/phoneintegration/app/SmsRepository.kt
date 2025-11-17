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
    //  LOAD CONVERSATIONS (SUPER FAST)
    // ---------------------------------------------------------------------
    suspend fun getConversations(): List<ConversationInfo> =
        withContext(Dispatchers.IO) {

            val map = LinkedHashMap<Long, ConversationInfo>()

            val cursor = resolver.query(
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
                "${Telephony.Sms.DATE} DESC"
            ) ?: return@withContext emptyList()

            cursor.use { c ->
                while (c.moveToNext()) {

                    val threadId = c.getLong(0)
                    val address = c.getString(1) ?: continue
                    val body = c.getString(2) ?: ""
                    val ts = c.getLong(3)
                    val isRead = c.getInt(4) == 1

                    val existing = map[threadId]

                    if (existing == null) {

                        // FAST: No contacts lookup here. Use cache → number.
                        val cachedName = contactCache[address] ?: address

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

            map.values.toList()
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
}
