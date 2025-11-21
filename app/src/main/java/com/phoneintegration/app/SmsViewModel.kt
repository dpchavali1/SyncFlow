package com.phoneintegration.app

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import kotlinx.coroutines.delay
import android.util.Log
import com.phoneintegration.app.deals.DealsRepository
import com.phoneintegration.app.data.GroupRepository
import com.phoneintegration.app.desktop.DesktopSyncService

class SmsViewModel(app: Application) : AndroidViewModel(app) {

    private var currentThreadId: Long? = null

    private val repo = SmsRepository(app.applicationContext)
    private val groupRepository = GroupRepository(app.applicationContext)
    private val syncService = DesktopSyncService(app.applicationContext)

    // ContentObserver to detect SMS/MMS changes
    private val smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            Log.d("SmsViewModel", "SMS database changed - reloading conversations")
            viewModelScope.launch {
                // Small delay to ensure SMS/MMS is fully written to database
                delay(500)

                Log.d("SmsViewModel", "Reloading conversations after database change")
                // Reload conversation list
                loadConversations()

                // Reload current conversation if viewing one
                currentThreadId?.let { threadId ->
                    Log.d("SmsViewModel", "Reloading current conversation thread: $threadId")
                    val firstPage = repo.getMessagesByThreadId(threadId, pageSize, 0)
                    _conversationMessages.value = firstPage
                    Log.d("SmsViewModel", "Reloaded ${firstPage.size} messages for thread $threadId")
                }
            }
        }
    }

    init {
        // Register ContentObserver to watch for SMS/MMS changes
        app.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            smsObserver
        )
        app.contentResolver.registerContentObserver(
            Uri.parse("content://mms"),
            true,
            smsObserver
        )
        Log.d("SmsViewModel", "ContentObserver registered for SMS/MMS changes")
    }

    override fun onCleared() {
        super.onCleared()
        // Unregister ContentObserver
        getApplication<Application>().contentResolver.unregisterContentObserver(smsObserver)
        Log.d("SmsViewModel", "ContentObserver unregistered")
    }

    // Tracks whether SyncFlow is the default SMS app
    private val _isDefaultSmsApp = MutableStateFlow(false)
    val isDefaultSmsApp = _isDefaultSmsApp.asStateFlow()
    /**
     * Called when the system default SMS role changes.
     * - Called from MainActivity.onResume()
     * - Called after user accepts the RoleManager popup
     */
    fun onDefaultSmsAppChanged(isDefault: Boolean) {
        _isDefaultSmsApp.value = isDefault

        if (isDefault) {
            // Reload everything because now we have full SMS/MMS access
            loadConversations()
        }
    }

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
            Log.d("SmsViewModel", "=== loadConversations() started ===")
            _isLoading.value = true

            // Fetch SMS conversations
            val smsList = repo.getConversations()
            Log.d("SmsViewModel", "Loaded ${smsList.size} SMS conversations from repository")

            // Fetch groups from database
            val groups = withContext(Dispatchers.IO) {
                groupRepository.getAllGroupsWithMembers().first()
            }

            // Sync groups to Firebase in background
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    syncService.syncGroups(groups)
                    Log.d("SmsViewModel", "Synced ${groups.size} groups to Firebase")
                } catch (e: Exception) {
                    Log.e("SmsViewModel", "Failed to sync groups to Firebase", e)
                }
            }

            // Convert groups to ConversationInfo
            val groupConversations = groups.map { groupWithMembers ->
                val group = groupWithMembers.group
                val members = groupWithMembers.members

                ConversationInfo(
                    threadId = group.threadId ?: -(group.id + 1000), // Negative ID for groups without thread
                    address = members.joinToString(", ") { it.phoneNumber },
                    contactName = group.name,
                    lastMessage = if (group.threadId != null) {
                        "Group conversation"  // Will be replaced by actual last message
                    } else {
                        "Tap to start chatting"
                    },
                    timestamp = group.lastMessageAt,
                    unreadCount = 0,
                    photoUri = null,
                    isAdConversation = false,
                    isGroupConversation = true,
                    recipientCount = members.size,
                    groupId = group.id
                )
            }

            // Filter out SMS conversations that match saved groups (to avoid duplicates)
            val groupThreadIds = groups.mapNotNull { it.group.threadId }.toSet()
            val filteredSmsList = smsList.filterNot { smsConvo ->
                groupThreadIds.contains(smsConvo.threadId)
            }

            // Combine filtered SMS and group conversations, sort by timestamp
            val allConversations = (filteredSmsList + groupConversations)
                .sortedByDescending { it.timestamp }

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

            _conversations.value = listOf(adsConversation) + allConversations
            _isLoading.value = false

            Log.d("SmsViewModel", "=== Conversations updated ===")
            Log.d("SmsViewModel", "Total conversations in state: ${_conversations.value.size}")
            _conversations.value.take(5).forEach { convo ->
                Log.d("SmsViewModel", "  - ${convo.contactName}: ${convo.lastMessage.take(30)}...")
            }

            // background name resolution (only for SMS conversations)
            resolveContactNames(smsList)
            // background photo resolution (only for SMS conversations)
            resolveContactPhotos(smsList)
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

            // Resolve threadId for fastest load
            val threadId = repo.getThreadIdForAddress(address)
            currentThreadId = threadId

            if (threadId == null) {
                _conversationMessages.value = emptyList()
                _hasMore.value = false
                return@launch
            }

            val firstPage = repo.getMessagesByThreadId(threadId, pageSize, 0)

            _conversationMessages.value = firstPage
            _hasMore.value = firstPage.size == pageSize

            resolveNameForMessages(address)

            if (firstPage.isNotEmpty()) {
                val last = firstPage.first()
                if (last.type == 1) {
                    _smartReplies.value = generateSmartReplies(last.body)
                }
            }
        }
    }

    // Load conversation by thread ID directly (for groups)
    fun loadConversationByThreadId(threadId: Long, displayName: String) {
        viewModelScope.launch {
            Log.d("SmsViewModel", "=== LOADING CONVERSATION BY THREAD ID ===")
            Log.d("SmsViewModel", "Thread ID: $threadId")
            Log.d("SmsViewModel", "Display Name: $displayName")

            currentAddress = displayName
            offset = 0
            currentThreadId = threadId

            val firstPage = repo.getMessagesByThreadId(threadId, pageSize, 0)

            Log.d("SmsViewModel", "Loaded ${firstPage.size} messages for thread $threadId")
            firstPage.forEach { msg ->
                Log.d("SmsViewModel", "  - Message ${msg.id}: ${msg.body.take(50)}... (type=${msg.type}, date=${msg.date})")
            }

            _conversationMessages.value = firstPage
            _hasMore.value = firstPage.size == pageSize

            if (firstPage.isNotEmpty()) {
                val last = firstPage.first()
                if (last.type == 1) {
                    _smartReplies.value = generateSmartReplies(last.body)
                }
            } else {
                Log.d("SmsViewModel", "No messages found for thread ID $threadId")
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
    fun sendMms(address: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {

            Log.d("SmsViewModel", "sendMms() called with uri = $uri")

            val ok = MmsHelper.sendMms(getApplication(), address, uri)

            if (ok) {
                Log.d("SmsViewModel", "MMS send request succeeded, waiting DB insert…")

                delay(1800)  // Telephony inserts MMS asynchronously

                currentThreadId?.let { id ->
                    val updated = repo.getMessagesByThreadId(id, pageSize, 0)
                    withContext(Dispatchers.Main) {
                        _conversationMessages.value = updated
                    }
                }
            } else {
                // Insert a temporary failed message bubble
                val failedMsg = SmsMessage(
                    id = -System.currentTimeMillis(),
                    address = address,
                    body = "[MMS image]",
                    date = System.currentTimeMillis(),
                    type = 2,
                    isMms = true,
                    mmsAttachments = emptyList(),
                    category = null,
                    otpInfo = null
                )

                withContext(Dispatchers.Main) {
                    // Prepend failed message into conversation
                    _conversationMessages.value = listOf(failedMsg) + _conversationMessages.value
                }
            }
        }
    }

    fun retryMms(sms: SmsMessage) {
        viewModelScope.launch {
            val uri = sms.mmsAttachments.firstOrNull()?.filePath ?: return@launch

            sendMms(sms.address, Uri.parse(uri))
        }
    }

    fun refreshDeals(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = DealsRepository(getApplication()).refreshFromCloud()
            if (ok) {
                // Force reload conversations, including SyncFlow Deals
                loadConversations()
            }
            onDone(ok)
        }
    }
}
