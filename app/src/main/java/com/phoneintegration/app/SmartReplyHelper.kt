package com.phoneintegration.app

import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.TextMessage
import com.phoneintegration.app.ai.AIService
import kotlinx.coroutines.runBlocking

class SmartReplyHelper {

    fun generateReplies(list: List<SmsMessage>): List<String> {
        if (list.isEmpty()) return emptyList()

        val sorted = list.sortedBy { it.date }
        val conversation = mutableListOf<TextMessage>()

        sorted.forEach { sms ->
            // Unique user ID for remote user
            val uid = sms.address ?: "user"

            if (sms.type == 1) { // received
                conversation.add(
                    TextMessage.createForRemoteUser(
                        sms.body,
                        sms.date,
                        uid
                    )
                )
            } else { // sent
                conversation.add(
                    TextMessage.createForLocalUser(
                        sms.body,
                        sms.date
                    )
                )
            }
        }

        var replies = emptyList<String>()
        val lock = Object()

        SmartReply.getClient()
            .suggestReplies(conversation)
            .addOnSuccessListener { result ->
                replies = result.suggestions.map { it.text }
                synchronized(lock) { lock.notify() }
            }
            .addOnFailureListener {
                synchronized(lock) { lock.notify() }
            }

        synchronized(lock) { lock.wait(300) }

        return replies
    }
}
