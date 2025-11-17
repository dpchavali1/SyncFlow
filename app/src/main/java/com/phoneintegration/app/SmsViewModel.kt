package com.phoneintegration.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    // ---------------------------------------------------------
    // BACKGROUND CONTACT-NAME RESOLUTION
    // ---------------------------------------------------------
    private fun resolveContactNames(list: List<ConversationInfo>) {
        viewModelScope.launch(Dispatchers.IO) {
            list.forEach { info ->
                val resolved = repo.resolveContactName(info.address)
                if (resolved != null && resolved != info.contactName) {
                    withContext(Dispatchers.Main) {
                        _conversations.value = _conversations.value.map { c ->
                            if (c.threadId == info.threadId)
                                c.copy(contactName = resolved)
                            else c
                        }
                    }
                }
            }
        }
    }

    private fun resolveNameForMessages(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolved = repo.resolveContactName(address)
            if (resolved != null) {
                withContext(Dispatchers.Main) {
                    _conversationMessages.value =
                        _conversationMessages.value.map { msg ->
                            msg.copy(contactName = resolved)
                        }
                }
            }
        }
    }

    // ---------------------------------------------------------
    // LOAD CONVERSATIONS
    // ---------------------------------------------------------
    fun loadConversations() {
        viewModelScope.launch {
            _isLoading.value = true
            val list = repo.getConversations()
            // Insert Fake Ads Conversation
            val adsConversation = ConversationInfo(
                threadId = -1L,
                address = "syncflow_ads",
                contactName = "SyncFlow Deals",
                lastMessage = "Tap here to explore today's best offers!",
                timestamp = System.currentTimeMillis(),
                unreadCount = 0,
                photoUri = null,
                isAdConversation = true
            )
            _conversations.value = listOf(adsConversation) + list
            _isLoading.value = false

            // background name resolution
            resolveContactNames(list)
            // background photo resolution
            resolveContactPhotos(list)
            // Insert our fake Ads conversation at top
        }
    }

    fun refreshConversations() {
        viewModelScope.launch {
            val list = repo.getConversations()
            _conversations.value = list

            resolveContactNames(list)
            resolveContactPhotos(list)
        }
    }

    // ---------------------------------------------------------
    // LOAD SINGLE CONVERSATION
    // ---------------------------------------------------------
    fun loadConversation(address: String) {
        viewModelScope.launch {
            currentAddress = address
            offset = 0

            val firstPage = repo.getMessages(address, pageSize, 0)

            _conversationMessages.value = firstPage
            _hasMore.value = firstPage.size == pageSize

            resolveNameForMessages(address)

            if (firstPage.isNotEmpty()) {
                val lastMessage = firstPage.first()
                if (lastMessage.type == 1) {
                    _smartReplies.value = generateSmartReplies(lastMessage.body)
                }
            }
        }
    }

    // ---------------------------------------------------------
    // LOAD MORE PAGINATION
    // ---------------------------------------------------------
    fun loadMore() {
        if (_isLoadingMore.value || !_hasMore.value) return

        viewModelScope.launch {
            _isLoadingMore.value = true

            offset += pageSize

            val new = repo.getMessages(currentAddress, pageSize, offset)

            _conversationMessages.value = _conversationMessages.value + new
            _hasMore.value = new.size == pageSize

            _isLoadingMore.value = false
        }
    }

    // ---------------------------------------------------------
    // SEND SMS
    // ---------------------------------------------------------
    fun sendSms(address: String, body: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {

            val temp = SmsMessage(
                id = -System.currentTimeMillis(),
                address = address,
                body = body,
                date = System.currentTimeMillis(),
                type = 2,
                contactName = null
            )

            _conversationMessages.value = listOf(temp) + _conversationMessages.value

            val ok = repo.sendSms(address, body)

            if (ok) {
                kotlinx.coroutines.delay(500)

                val updated = repo.getMessages(address, pageSize, 0)
                _conversationMessages.value = updated

                refreshConversations()
            } else {
                _conversationMessages.value =
                    _conversationMessages.value.filterNot { it.id == temp.id }
            }

            onResult(ok)
        }
    }

    // ---------------------------------------------------------
    // DELETE SMS
    // ---------------------------------------------------------
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

    // ---------------------------------------------------------
    // SMART REPLIES
    // ---------------------------------------------------------
    private fun generateSmartReplies(message: String): List<String> {
        val lower = message.lowercase()

        return when {
            "how are you" in lower || "how r u" in lower ->
                listOf("I'm good!", "Doing well!", "Great!")
            "thanks" in lower || "thank you" in lower ->
                listOf("You're welcome!", "Anytime!", "No problem!")
            "sorry" in lower ->
                listOf("It's okay!", "No worries!", "Don't worry about it")
            "?" in lower ->
                listOf("Yes", "No", "Let me check")
            else ->
                listOf("OK", "Sure", "Got it")
        }
    }

    private fun resolveContactPhotos(list: List<ConversationInfo>) {
        viewModelScope.launch(Dispatchers.IO) {
            list.forEach { info ->

                val photo = repo.resolveContactPhoto(info.address)

                if (photo != null) {
                    withContext(Dispatchers.Main) {
                        _conversations.value = _conversations.value.map { c ->
                            if (c.threadId == info.threadId)
                                c.copy(photoUri = photo)
                            else c
                        }
                    }
                }
            }
        }
    }
}
