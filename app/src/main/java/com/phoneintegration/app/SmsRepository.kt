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
    
    // Cache for contact names to avoid repeated lookups
    private val contactCache = mutableMapOf<String, String?>()

    // -------------------------------------------------------------
    // CONTACT NAME LOOKUP (with caching)
    // -------------------------------------------------------------
    private fun getContactName(address: String): String? {
        // Check cache first
        if (contactCache.containsKey(address)) {
            return contactCache[address]
        }

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
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else null
        }

        // Cache the result
        contactCache[address] = name
        return name
    }

    // -------------------------------------------------------------
    // LOAD CONVERSATION MESSAGES (Paginated, Optimized)
    // -------------------------------------------------------------
    suspend fun getMessages(address: String, limit: Int, offset: Int): List<SmsMessage> =
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
                "${Telephony.Sms.ADDRESS} = ?",
                arrayOf(address),
                "${Telephony.Sms.DATE} DESC LIMIT $limit OFFSET $offset"
            ) ?: return@withContext emptyList()

            // Get contact name once for this address
            val contactName = getContactName(address)

            cursor.use { c ->
                while (c.moveToNext()) {

                    val id = c.getLong(0)
                    val addr = c.getString(1) ?: ""
                    val body = c.getString(2) ?: ""
                    val date = c.getLong(3)
                    val type = c.getInt(4)

                    // Build SmsMessage with cached contact name
                    val sms = SmsMessage(
                        id = id,
                        address = addr,
                        body = body,
                        date = date,
                        type = type,
                        contactName = contactName
                    )

                    // Categorization
                    sms.category = MessageCategorizer.categorizeMessage(sms)
                    sms.otpInfo = MessageCategorizer.extractOtp(sms.body)

                    list.add(sms)
                }
            }

            list
        }

    // -------------------------------------------------------------
    // GET CONVERSATION LIST SUMMARY (Optimized with LIMIT)
    // -------------------------------------------------------------
    suspend fun getConversations(): List<ConversationInfo> =
        withContext(Dispatchers.IO) {

            val map = LinkedHashMap<String, ConversationInfo>()

            // Query with LIMIT to load faster initially
            val cursor = resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.READ
                ),
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT 500"  // Limit for faster initial load
            ) ?: return@withContext emptyList()

            cursor.use { c ->

                while (c.moveToNext()) {

                    val addr = c.getString(0) ?: continue
                    val body = c.getString(1) ?: ""
                    val ts = c.getLong(2)
                    val isRead = c.getInt(3) == 1

                    val existing = map[addr]

                    if (existing == null) {
                        // Only lookup contact name for new conversations
                        val name = getContactName(addr)
                        
                        map[addr] = ConversationInfo(
                            address = addr,
                            contactName = name,
                            lastMessage = body,
                            timestamp = ts,
                            unreadCount = if (isRead) 0 else 1
                        )
                    } else {
                        // Just increment unread count for existing
                        if (!isRead) existing.unreadCount += 1
                    }
                }
            }

            map.values.toList()
        }

    // -------------------------------------------------------------
    // SEND SMS (with delay for database write)
    // -------------------------------------------------------------
    suspend fun sendSms(address: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            return@withContext try {
                SmsManager.getDefault().sendTextMessage(address, null, body, null, null)
                // Give system time to write to SMS database
                kotlinx.coroutines.delay(200)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

    // -------------------------------------------------------------
    // DELETE MESSAGE
    // -------------------------------------------------------------
    suspend fun deleteMessage(id: Long): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString())
                resolver.delete(uri, null, null) > 0
            } catch (e: Exception) {
                false
            }
        }
    
    // -------------------------------------------------------------
    // GET LATEST MESSAGE (for quick refresh after send)
    // -------------------------------------------------------------
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
                    val id = c.getLong(0)
                    val addr = c.getString(1) ?: ""
                    val body = c.getString(2) ?: ""
                    val date = c.getLong(3)
                    val type = c.getInt(4)
                    
                    val contactName = getContactName(addr)

                    val sms = SmsMessage(
                        id = id,
                        address = addr,
                        body = body,
                        date = date,
                        type = type,
                        contactName = contactName
                    )
                    
                    sms.category = MessageCategorizer.categorizeMessage(sms)
                    sms.otpInfo = MessageCategorizer.extractOtp(sms.body)
                    
                    sms
                } else null
            }
        }
}
