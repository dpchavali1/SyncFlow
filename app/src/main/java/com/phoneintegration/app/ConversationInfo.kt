package com.phoneintegration.app

data class ConversationInfo(
    val address: String,
    var contactName: String? = null,
    var lastMessage: String,
    val timestamp: Long,
    var unreadCount: Int = 0
)
