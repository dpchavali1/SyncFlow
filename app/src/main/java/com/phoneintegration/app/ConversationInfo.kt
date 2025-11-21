package com.phoneintegration.app

data class ConversationInfo(
    val threadId: Long,
    val address: String,  // For groups: comma-separated addresses
    var contactName: String? = null,  // For groups: comma-separated names
    var lastMessage: String,
    val timestamp: Long,
    var unreadCount: Int = 0,
    var photoUri: String? = null,
    var isAdConversation: Boolean = false,
    var isGroupConversation: Boolean = false,  // NEW: indicates if this is a group chat
    var recipientCount: Int = 1,  // NEW: number of recipients in group
    var groupId: Long? = null  // NEW: database group ID for saved groups
)
