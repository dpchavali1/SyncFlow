package com.phoneintegration.app

import android.content.Context
import android.provider.Telephony
import androidx.annotation.WorkerThread

class SmsRepository(private val context: Context) {

    @WorkerThread
    fun getAllSmsMessages(): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()

        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            )

            android.util.Log.d("SmsRepository", "Querying SMS content provider...")

            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"  // Sort by date descending
            )

            cursor?.use { c ->
                val idIndex = c.getColumnIndex(Telephony.Sms._ID)
                val addressIndex = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIndex = c.getColumnIndex(Telephony.Sms.BODY)
                val dateIndex = c.getColumnIndex(Telephony.Sms.DATE)
                val typeIndex = c.getColumnIndex(Telephony.Sms.TYPE)

                android.util.Log.d("SmsRepository", "Cursor has ${c.count} rows")

                var count = 0
                while (c.moveToNext()) {
                    try {
                        val id = if (idIndex >= 0) c.getLong(idIndex) else 0L
                        val address = if (addressIndex >= 0) c.getString(addressIndex) ?: "" else ""
                        val body = if (bodyIndex >= 0) c.getString(bodyIndex) ?: "" else ""
                        val date = if (dateIndex >= 0) c.getLong(dateIndex) else System.currentTimeMillis()
                        val type = if (typeIndex >= 0) c.getInt(typeIndex) else 1

                        if (address.isNotBlank() && body.isNotBlank()) {
                            val message = SmsMessage(
                                id = id,
                                address = address,
                                body = body,
                                date = date,
                                type = type
                            )
                            messages.add(message)
                            count++
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SmsRepository", "Error reading message row", e)
                    }
                }

                android.util.Log.d("SmsRepository", "Successfully read $count messages")
            } ?: run {
                android.util.Log.e("SmsRepository", "Cursor is null - permission issue?")
            }

            // Populate contact names in a batch
            if (messages.isNotEmpty()) {
                android.util.Log.d("SmsRepository", "Populating contact names...")
                val contactHelper = ContactHelper(context)

                var contactCount = 0
                for (message in messages) {
                    try {
                        message.contactName = contactHelper.getContactName(message.address)
                        if (message.contactName != null) contactCount++
                    } catch (e: Exception) {
                        android.util.Log.e("SmsRepository", "Error getting contact for ${message.address}", e)
                    }
                }

                android.util.Log.d("SmsRepository", "Found contact names for $contactCount messages")
            }

        } catch (e: SecurityException) {
            android.util.Log.e("SmsRepository", "Security exception - missing permissions?", e)
        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "Error loading messages", e)
            e.printStackTrace()
        }

        android.util.Log.d("SmsRepository", "Returning ${messages.size} messages")
        return messages
    }

    @WorkerThread
    fun getConversations(messages: List<SmsMessage>): List<Conversation> {
        try {
            android.util.Log.d("SmsRepository", "Creating conversations from ${messages.size} messages")

            val conversations = messages
                .groupBy { it.address }
                .mapNotNull { (address, msgs) ->
                    try {
                        val sortedMsgs = msgs.sortedByDescending { it.date }
                        val lastMessage = sortedMsgs.firstOrNull() ?: return@mapNotNull null

                        Conversation(
                            address = address,
                            contactName = lastMessage.contactName,
                            messages = sortedMsgs,
                            lastMessage = lastMessage
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("SmsRepository", "Error creating conversation for $address", e)
                        null
                    }
                }
                .sortedByDescending { it.lastMessage.date }

            android.util.Log.d("SmsRepository", "Created ${conversations.size} conversations")
            return conversations

        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "Error creating conversations", e)
            return emptyList()
        }
    }

    @WorkerThread
    fun categorizeMessages(messages: List<SmsMessage>) {
        try {
            android.util.Log.d("SmsRepository", "Categorizing ${messages.size} messages...")

            var categorized = 0
            for (message in messages) {
                if (message.category == null) {
                    message.category = MessageCategorizer.categorizeMessage(message)

                    if (message.category == MessageCategory.OTP) {
                        message.otpInfo = MessageCategorizer.extractOtp(message.body)
                    }

                    categorized++
                }
            }

            android.util.Log.d("SmsRepository", "Categorized $categorized messages")

        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "Error categorizing messages", e)
        }
    }

    @WorkerThread
    fun getConversationMessages(address: String): List<SmsMessage> {
        try {
            android.util.Log.d("SmsRepository", "Loading messages for address: $address")

            val allMessages = getAllSmsMessages()
            val conversationMessages = allMessages.filter { it.address == address }

            android.util.Log.d("SmsRepository", "Found ${conversationMessages.size} messages for this conversation")

            return conversationMessages

        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "Error loading conversation messages", e)
            return emptyList()
        }
    }

    @WorkerThread
    fun markMessageAsDeleted(id: Long): Boolean {
        return try {
            android.util.Log.d("SmsRepository", "Deleting message with id: $id")

            val deletedRows = context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms._ID} = ?",
                arrayOf(id.toString())
            )

            val success = deletedRows > 0
            android.util.Log.d("SmsRepository", "Delete result: $success (rows: $deletedRows)")

            success
        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "Error deleting message", e)
            e.printStackTrace()
            false
        }
    }
}