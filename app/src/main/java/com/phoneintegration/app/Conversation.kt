package com.phoneintegration.app

data class Conversation(
    val address: String,
    val contactName: String?,
    val messages: List<SmsMessage>,
    val lastMessage: SmsMessage,
    val unreadCount: Int = 0
) {
    fun getDisplayName(): String {
        return contactName ?: address
    }

    fun getLastMessagePreview(): String {
        val preview = lastMessage.body.take(50)
        return if (lastMessage.body.length > 50) "$preview..." else preview
    }

    fun getFormattedTime(): String {
        return lastMessage.getFormattedTime()
    }
}