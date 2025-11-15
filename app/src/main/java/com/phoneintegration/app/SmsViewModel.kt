package com.phoneintegration.app

import android.app.Application
import android.telephony.PhoneNumberUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SmsRepository(application)
    private val smartReplyHelper = SmartReplyHelper()

    private val _allMessages = MutableStateFlow<List<SmsMessage>>(emptyList())
    private val _messages = MutableStateFlow<List<SmsMessage>>(emptyList())
    val messages: StateFlow<List<SmsMessage>> = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _conversationMessages = MutableStateFlow<List<SmsMessage>>(emptyList())
    val conversationMessages: StateFlow<List<SmsMessage>> = _conversationMessages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _hiddenConversations = MutableStateFlow<Set<String>>(emptySet())

    private val _selectedCategory = MutableStateFlow<MessageCategory?>(null)
    val selectedCategory: StateFlow<MessageCategory?> = _selectedCategory.asStateFlow()

    private val _categoryStats = MutableStateFlow<Map<MessageCategory, Int>>(emptyMap())
    val categoryStats: StateFlow<Map<MessageCategory, Int>> = _categoryStats.asStateFlow()

    // Smart Reply
    private val _smartReplies = MutableStateFlow<List<String>>(emptyList())
    val smartReplies: StateFlow<List<String>> = _smartReplies.asStateFlow()

    private val _isGeneratingReplies = MutableStateFlow(false)
    val isGeneratingReplies: StateFlow<Boolean> = _isGeneratingReplies.asStateFlow()

    private var lastSmartReplyTime = 0L

    init {
        android.util.Log.d("SmsViewModel", "Initializing - loading conversations")
        loadConversations()
    }

    fun loadMessages() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                android.util.Log.d("SmsViewModel", "Loading all messages...")
                val messages = withContext(Dispatchers.IO) {
                    repository.getAllSmsMessages()
                }

                android.util.Log.d("SmsViewModel", "Loaded ${messages.size} messages")

                // Categorize messages synchronously
                withContext(Dispatchers.IO) {
                    repository.categorizeMessages(messages)
                }

                _allMessages.value = messages
                filterMessages()

                // Update category stats
                _categoryStats.value = MessageCategorizer.getCategoryStats(messages)

                android.util.Log.d("SmsViewModel", "Category stats: ${_categoryStats.value}")
            } catch (e: Exception) {
                android.util.Log.e("SmsViewModel", "Error loading messages", e)
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadConversations() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                android.util.Log.d("SmsViewModel", "Loading conversations...")

                // 1. Load all messages
                val messages = withContext(Dispatchers.IO) {
                    repository.getAllSmsMessages()
                }

                android.util.Log.d("SmsViewModel", "Loaded ${messages.size} raw messages")

                // 2. Categorize messages BEFORE doing anything else
                withContext(Dispatchers.IO) {
                    repository.categorizeMessages(messages)
                }

                _allMessages.value = messages

                // 3. Calculate category stats from all messages
                val stats = MessageCategorizer.getCategoryStats(messages)
                _categoryStats.value = stats

                android.util.Log.d("SmsViewModel", "Category breakdown:")
                stats.forEach { (category, count) ->
                    android.util.Log.d("SmsViewModel", "  ${category.displayName}: $count messages")
                }

                // 4. Get all conversations
                val allConversations = withContext(Dispatchers.IO) {
                    repository.getConversations(messages)
                }

                android.util.Log.d("SmsViewModel", "Created ${allConversations.size} conversations")

                // 5. Filter conversations by hidden status and category
                val filteredConversations = allConversations.filter { conv ->
                    // Check if not hidden
                    val normalized = PhoneNumberUtils.normalizeNumber(conv.address)
                    val notHidden = !_hiddenConversations.value.contains(normalized)

                    // Check if matches selected category
                    val matchesCategory = when (val category = _selectedCategory.value) {
                        null -> {
                            // No category selected - show all
                            true
                        }
                        else -> {
                            // Category selected - check if ANY message in conversation matches
                            val hasMatchingMessage = conv.messages.any { msg ->
                                msg.category == category
                            }
                            android.util.Log.d("SmsViewModel",
                                "Conversation ${conv.getDisplayName()}: hasMatchingMessage=$hasMatchingMessage for category ${category.displayName}")
                            hasMatchingMessage
                        }
                    }

                    val include = notHidden && matchesCategory
                    if (!include && !notHidden) {
                        android.util.Log.d("SmsViewModel", "Filtered out (hidden): ${conv.getDisplayName()}")
                    } else if (!include && !matchesCategory) {
                        android.util.Log.d("SmsViewModel", "Filtered out (category): ${conv.getDisplayName()}")
                    }

                    include
                }

                _conversations.value = filteredConversations

                android.util.Log.d("SmsViewModel",
                    "Final: ${filteredConversations.size} conversations (category: ${_selectedCategory.value?.displayName ?: "All"})")

                // Also update filtered messages list
                filterMessages()

            } catch (e: Exception) {
                android.util.Log.e("SmsViewModel", "Error loading conversations", e)
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setCategory(category: MessageCategory?) {
        android.util.Log.d("SmsViewModel", "Category changed to: ${category?.displayName ?: "All"}")
        _selectedCategory.value = category
        loadConversations()
    }

    fun loadConversation(address: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                android.util.Log.d("SmsViewModel", "Loading conversation for: $address")

                val messages = withContext(Dispatchers.IO) {
                    repository.getConversationMessages(address)
                }

                // Categorize these messages too
                withContext(Dispatchers.IO) {
                    repository.categorizeMessages(messages)
                }

                _conversationMessages.value = messages

                android.util.Log.d("SmsViewModel", "Loaded ${messages.size} messages for conversation")
            } catch (e: Exception) {
                android.util.Log.e("SmsViewModel", "Error loading conversation", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addOptimisticMessage(message: SmsMessage) {
        val currentMessages = _conversationMessages.value.toMutableList()
        currentMessages.add(message)
        _conversationMessages.value = currentMessages

        android.util.Log.d("SmsViewModel", "Added optimistic message")
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        filterMessages()
    }

    fun hideConversation(address: String) {
        val normalized = PhoneNumberUtils.normalizeNumber(address)
        _hiddenConversations.value = _hiddenConversations.value + normalized
        android.util.Log.d("SmsViewModel", "Hidden conversation: $normalized")
        loadConversations()
    }

    fun deleteMessage(message: SmsMessage, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                val success = withContext(Dispatchers.IO) {
                    repository.markMessageAsDeleted(message.id)
                }

                if (success) {
                    _allMessages.value = _allMessages.value.filter { it.id != message.id }
                    filterMessages()

                    kotlinx.coroutines.delay(300)
                    loadConversations()

                    android.util.Log.d("SmsViewModel", "Message deleted successfully")
                } else {
                    android.util.Log.e("SmsViewModel", "Failed to delete message")
                }

                _isLoading.value = false
                onResult(success)

            } catch (e: Exception) {
                android.util.Log.e("SmsViewModel", "Error deleting message", e)
                _isLoading.value = false
                onResult(false)
            }
        }
    }

    fun generateSmartReplies(conversationMessages: List<SmsMessage>) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSmartReplyTime < 2000) {
            return
        }
        lastSmartReplyTime = currentTime

        viewModelScope.launch {
            try {
                _isGeneratingReplies.value = true

                val lastMessage = conversationMessages.lastOrNull()
                if (lastMessage?.type == 1) {
                    val replies = withContext(Dispatchers.IO) {
                        smartReplyHelper.generateReplies(conversationMessages)
                    }
                    _smartReplies.value = replies

                    android.util.Log.d("SmartReply", "Generated ${replies.size} suggestions")
                } else {
                    _smartReplies.value = emptyList()
                }

            } catch (e: Exception) {
                android.util.Log.e("SmartReply", "Error generating replies", e)
                _smartReplies.value = emptyList()
            } finally {
                _isGeneratingReplies.value = false
            }
        }
    }

    fun clearSmartReplies() {
        _smartReplies.value = emptyList()
    }

    private fun filterMessages() {
        val query = _searchQuery.value.trim().lowercase()
        val category = _selectedCategory.value

        val filtered = _allMessages.value.filter { message ->
            val matchesSearch = if (query.isEmpty()) {
                true
            } else {
                message.body.lowercase().contains(query) ||
                        message.address.lowercase().contains(query) ||
                        (message.contactName?.lowercase()?.contains(query) == true)
            }

            val matchesCategory = if (category == null) {
                true
            } else {
                message.category == category
            }

            matchesSearch && matchesCategory
        }

        _messages.value = filtered

        android.util.Log.d("SmsViewModel",
            "Filtered messages: ${filtered.size} (from ${_allMessages.value.size} total)")
    }

    override fun onCleared() {
        super.onCleared()
        smartReplyHelper.close()
        android.util.Log.d("SmsViewModel", "ViewModel cleared")
    }
}