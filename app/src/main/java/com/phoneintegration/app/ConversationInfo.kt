package com.phoneintegration.app

data class ConversationInfo(
    val threadId: Long,
    val address: String,
    var contactName: String? = null,
    var lastMessage: String,
    val timestamp: Long,
    var unreadCount: Int = 0,
    var photoUri: String? = null,
    var isAdConversation: Boolean = false
)
