package com.phoneintegration.app

import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplyGenerator
import com.google.mlkit.nl.smartreply.TextMessage
import kotlinx.coroutines.tasks.await
import com.google.android.gms.tasks.Tasks

class SmartReplyHelper {

    private val smartReply: SmartReplyGenerator = SmartReply.getClient()

    /**
     * Generate smart reply suggestions based on conversation history
     * Returns up to 3 suggested replies
     */
    suspend fun generateReplies(messages: List<SmsMessage>): List<String> {
        return try {
            if (messages.isEmpty()) {
                return emptyList()
            }

            // Convert SMS messages to ML Kit format
            val conversation = messages
                .takeLast(10) // Only use last 10 messages for context
                .map { message ->
                    if (message.type == 1) {
                        // Received message - from remote user
                        TextMessage.createForRemoteUser(
                            message.body,
                            message.date,
                            message.getDisplayName()
                        )
                    } else {
                        // Sent message - from local user
                        TextMessage.createForLocalUser(
                            message.body,
                            message.date
                        )
                    }
                }

            // Get suggestions from ML Kit
            val result = smartReply.suggestReplies(conversation).await()

            // Check the status
            val status = result.status
            android.util.Log.d("SmartReply", "Status: $status")

            // Return suggestions if available
            val suggestions = result.suggestions
            if (suggestions.isNotEmpty()) {
                suggestions.map { suggestion -> suggestion.text }
            } else {
                android.util.Log.d("SmartReply", "No suggestions available")
                emptyList()
            }

        } catch (e: Exception) {
            android.util.Log.e("SmartReply", "Error generating replies: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get quick reply for a single message (simpler version)
     */
    suspend fun getQuickReplies(lastMessage: String, isFromRemote: Boolean = true): List<String> {
        return try {
            val conversation = listOf(
                if (isFromRemote) {
                    TextMessage.createForRemoteUser(
                        lastMessage,
                        System.currentTimeMillis(),
                        "User"
                    )
                } else {
                    TextMessage.createForLocalUser(
                        lastMessage,
                        System.currentTimeMillis()
                    )
                }
            )

            val result = smartReply.suggestReplies(conversation).await()
            val suggestions = result.suggestions

            if (suggestions.isNotEmpty()) {
                suggestions.map { it.text }
            } else {
                emptyList()
            }

        } catch (e: Exception) {
            android.util.Log.e("SmartReply", "Error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Clean up resources
     */
    fun close() {
        smartReply.close()
    }
}