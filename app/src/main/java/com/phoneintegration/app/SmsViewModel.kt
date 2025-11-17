package com.phoneintegration.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SmsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SmsRepository(app.applicationContext)

    private val _conversations = MutableStateFlow<List<ConversationInfo>>(emptyList())
    val conversations = _conversations.asStateFlow()

    private val _conversationMessages = MutableStateFlow<List<SmsMessage>>(emptyList())
    val conversationMessages = _conversationMessages.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore = _hasMore.asStateFlow()

    private val _smartReplies = MutableStateFlow<List<String>>(emptyList())
    val smartReplies = _smartReplies.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val pageSize = 50
    private var offset = 0
    private var currentAddress = ""

    fun loadConversations() {
        viewModelScope.launch {
            _isLoading.value = true
            _conversations.value = repo.getConversations()
            _isLoading.value = false
        }
    }
    
    fun refreshConversations() {
        // Force refresh without showing loading
        viewModelScope.launch {
            _conversations.value = repo.getConversations()
        }
    }

    fun loadConversation(address: String) {
        viewModelScope.launch {
            currentAddress = address
            offset = 0
            val firstPage = repo.getMessages(address, pageSize, 0)

            _conversationMessages.value = firstPage
            _hasMore.value = firstPage.size == pageSize
            
            // Generate smart replies based on last message
            if (firstPage.isNotEmpty()) {
                val lastMessage = firstPage.first()
                if (lastMessage.type == 1) { // Received message
                    _smartReplies.value = generateSmartReplies(lastMessage.body)
                }
            }
        }
    }

    fun loadMore() {
        if (_isLoadingMore.value || !_hasMore.value) return

        viewModelScope.launch {
            _isLoadingMore.value = true

            offset += pageSize
            val new = repo.getMessages(currentAddress, pageSize, offset)

            _conversationMessages.value += new
            _hasMore.value = new.size == pageSize

            _isLoadingMore.value = false
        }
    }

    fun sendSms(address: String, body: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            // Optimistic update - add message immediately to UI
            val tempMessage = SmsMessage(
                id = -System.currentTimeMillis(), // Negative ID for temp messages
                address = address,
                body = body,
                date = System.currentTimeMillis(),
                type = 2, // Sent message
                contactName = null
            )
            
            // Add to UI immediately at the top (most recent)
            _conversationMessages.value = listOf(tempMessage) + _conversationMessages.value
            
            // Actually send the SMS
            val ok = repo.sendSms(address, body)
            
            if (ok) {
                // Wait a bit longer for database to update
                kotlinx.coroutines.delay(500)
                
                // Reload to get the actual message from database
                val updatedMessages = repo.getMessages(address, pageSize, 0)
                _conversationMessages.value = updatedMessages
                
                // Also refresh the conversation list
                refreshConversations()
            } else {
                // Remove the optimistic message if send failed
                _conversationMessages.value = 
                    _conversationMessages.value.filterNot { it.id == tempMessage.id }
            }
            onResult(ok)
        }
    }

    fun deleteMessage(id: Long, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = repo.deleteMessage(id)
            if (ok) {
                _conversationMessages.value =
                    _conversationMessages.value.filterNot { it.id == id }
            }
            onResult(ok)
        }
    }

    private fun generateSmartReplies(message: String): List<String> {
        val lowerMessage = message.lowercase()
        
        return when {
            lowerMessage.contains("how are you") || lowerMessage.contains("how r u") ->
                listOf("I'm good, thanks!", "Doing well!", "All good here")
            lowerMessage.contains("?") ->
                listOf("Yes", "No", "Let me check")
            lowerMessage.contains("thanks") || lowerMessage.contains("thank you") ->
                listOf("You're welcome!", "No problem!", "Anytime!")
            lowerMessage.contains("sorry") ->
                listOf("No worries!", "It's okay", "Don't worry about it")
            else ->
                listOf("OK", "Sure", "Got it")
        }
    }
}
