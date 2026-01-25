//
//  MessageStore.swift
//  SyncFlowMac
//
//  Observable store for managing messages and conversations
//

import Foundation
import FirebaseDatabase
import Combine

class MessageStore: ObservableObject {

    @Published var messages: [Message] = []
    @Published var conversations: [Conversation] = []
    @Published var isLoading = false
    @Published var error: Error?
    @Published var showArchived = false
    @Published var showUnreadOnly = false
    @Published var showSpamOnly = false
    @Published var messageReactions: [String: String] = [:]
    @Published var readReceipts: [String: ReadReceipt] = [:]
    @Published var pinnedMessages: Set<String> = [] // Set of pinned message IDs
    @Published var spamMessages: [SpamMessage] = []
    @Published var selectedSpamAddress: String? = nil
    @Published var canLoadMore = false  // Whether more old messages exist
    @Published var isLoadingMore = false  // Loading state for pagination

    private var messageListenerHandle: DatabaseHandle?
    private var reactionsListenerHandle: DatabaseHandle?
    private var readReceiptsListenerHandle: DatabaseHandle?
    private var spamListenerHandle: DatabaseHandle?
    private var currentUserId: String?
    private var lastMessageIds: Set<String> = []
    private var lastMessageHash: Int = 0  // Track if message data actually changed
    private var readReceiptsLoaded = false  // Track if read receipts have been loaded at least once
    private var cancellables = Set<AnyCancellable>()

    // Pagination state
    private var loadedTimeRangeStart: TimeInterval?  // Oldest message timestamp loaded
    private var initialLoadDays: Int = 180  // Load last 180 days (6 months) to show more history on initial pairing
    private var loadMoreDays: Int = 90  // Load 90 more days when "Load More" is clicked
    private var contactsListenerHandle: DatabaseHandle?
    private var latestContacts: [Contact] = []
    private var contactNameLookup: [String: String] = [:]
    private let pendingOutgoingQueue = DispatchQueue(label: "MessageStore.pendingOutgoingQueue")
    private var pendingOutgoingMessages: [String: Message] = [:]

    private let firebaseService = FirebaseService.shared
    private let notificationService = NotificationService.shared
    private let preferences = PreferencesService.shared

    // MARK: - Phone Number Normalization

    /// Normalize phone number for comparison (handles different formats like +1234567890 vs 1234567890)
    private func normalizePhoneNumber(_ address: String) -> String {
        // Skip non-phone addresses (email, short codes, etc.)
        if address.contains("@") || address.count < 6 {
            return address.lowercased()
        }

        // Remove all non-digit characters
        let digitsOnly = address.filter { $0.isNumber }

        // For comparison, use last 10 digits (handles country code differences)
        if digitsOnly.count >= 10 {
            return String(digitsOnly.suffix(10))
        }
        return digitsOnly
    }

    // MARK: - Initialization

    init() {
        loadPinnedMessages()
        setupNotificationHandlers()
        setupPerformanceOptimizations()
    }

    private func setupPerformanceOptimizations() {
        // Listen for battery state changes
        BatteryAwareServiceManager.shared.addStateChangeHandler { [weak self] state in
            self?.handleBatteryStateChange(state)
        }

        // Listen for memory optimization notifications
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleMemoryPressure),
            name: .memoryPressureCritical,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleClearMessageCache),
            name: .clearMessageCache,
            object: nil
        )
    }

    private func handleBatteryStateChange(_ state: BatteryAwareServiceManager.ServiceState) {
        switch state {
        case .reduced:
            // Reduce message processing frequency
            reduceMessageProcessing()
        case .minimal:
            // Minimize message processing
            minimizeMessageProcessing()
        case .suspended:
            // Pause message processing
            pauseMessageProcessing()
        case .full:
            // Resume normal message processing
            resumeNormalProcessing()
        }
    }

    private func reduceMessageProcessing() {
        // Reduce frequency of message updates and processing
        print("[MessageStore] Reducing message processing frequency")
        // Implementation would adjust timers and processing queues
    }

    private func minimizeMessageProcessing() {
        // Further minimize processing
        print("[MessageStore] Minimizing message processing")
        // Clear non-visible message caches
    }

    private func pauseMessageProcessing() {
        // Pause background message processing
        print("[MessageStore] Pausing message processing")
        // Stop background processing but keep UI responsive
    }

    private func resumeNormalProcessing() {
        // Resume normal processing
        print("[MessageStore] Resuming normal message processing")
        // Restore normal processing frequency
    }

    @objc private func handleMemoryPressure() {
        // Clear cached messages that aren't currently visible
        clearNonVisibleMessageCache()
    }

    @objc private func handleClearMessageCache(_ notification: Notification) {
        if let userInfo = notification.userInfo,
           let olderThan = userInfo["olderThan"] as? TimeInterval {
            clearOldMessageCache(olderThan: olderThan)
        }
    }

    private func clearNonVisibleMessageCache() {
        // Clear messages that aren't currently displayed
        // This is a simplified implementation
        print("[MessageStore] Clearing non-visible message cache")
    }

    private func clearOldMessageCache(olderThan seconds: TimeInterval) {
        // Clear message cache entries older than specified time
        print("[MessageStore] Clearing message cache older than \(seconds) seconds")
    }

    private func setupNotificationHandlers() {
        // Handle quick reply from notifications
        NotificationCenter.default.publisher(for: .quickReply)
            .sink { [weak self] notification in
                guard let userInfo = notification.userInfo,
                      let address = userInfo["address"] as? String,
                      let body = userInfo["body"] as? String,
                      let userId = self?.currentUserId else { return }

                Task {
                    try? await self?.sendMessage(userId: userId, to: address, body: body)
                }
            }
            .store(in: &cancellables)
    }

    // MARK: - Start Listening

    func startListening(userId: String) {
        print("[MessageStore] startListening called for userId: \(userId)")
        guard currentUserId != userId else {
            print("[MessageStore] Already listening to user: \(userId)")
            return
        }

        // Remove old listener if exists
        if let handle = messageListenerHandle, let oldUserId = currentUserId {
            firebaseService.removeMessageListener(userId: oldUserId, handle: handle)
        }
        if let handle = reactionsListenerHandle, let oldUserId = currentUserId {
            firebaseService.removeMessageReactionsListener(userId: oldUserId, handle: handle)
        }
        if let handle = readReceiptsListenerHandle, let oldUserId = currentUserId {
            firebaseService.removeReadReceiptsListener(userId: oldUserId, handle: handle)
        }
        if let handle = spamListenerHandle, let oldUserId = currentUserId {
            firebaseService.removeSpamMessagesListener(userId: oldUserId, handle: handle)
        }
        if currentUserId != nil {
            stopListeningForContacts()
        }

        currentUserId = userId
        isLoading = true

        // Reset pagination state
        loadedTimeRangeStart = nil
        canLoadMore = false

        // Load ALL messages (no time filter) - matches web behavior
        // Previously limited to 180 days which caused conversations to not appear
        messageListenerHandle = firebaseService.listenToMessages(userId: userId, startTime: nil) { [weak self] messages in
            guard let self = self else { return }

            // Process on background thread to avoid blocking UI
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                guard let self = self else { return }

                // Calculate hash of message data to detect actual changes
                let messageHash = messages.reduce(0) { hash, msg in
                    hash ^ msg.id.hashValue ^ msg.body.hashValue ^ msg.date.hashValue
                }

                // Read current state safely
                var lastHash: Int = 0
                var lastIds: Set<String> = []
                DispatchQueue.main.sync {
                    lastHash = self.lastMessageHash
                    lastIds = self.lastMessageIds
                }

                // Skip processing if data hasn't actually changed
                if messageHash == lastHash && !lastIds.isEmpty {
                    return
                }

                // Detect new messages for notifications
                let newMessageIds = Set(messages.map { $0.id })
                let actuallyNewIds = newMessageIds.subtracting(lastIds)

                // Apply read status on background
                let processedMessages = self.applyReadStatus(to: messages)

                // Merge pending outgoing messages and build conversations on background thread
                let mergeResult = self.mergeMessagesWithPendingOutgoing(remoteMessages: processedMessages)
                let newConversations = self.buildConversations(from: mergeResult.mergedMessages)

                // Update UI on main thread
                DispatchQueue.main.async { [weak self] in
                    guard let self = self else { return }

                    self.lastMessageHash = messageHash
                    self.lastMessageIds = newMessageIds

                    // Show notifications for new messages
                    if !actuallyNewIds.isEmpty && !lastIds.isEmpty {
                        let newMsgs = messages.filter { actuallyNewIds.contains($0.id) && $0.isReceived }
                        for message in newMsgs {
                            self.notificationService.showMessageNotification(
                                from: message.address,
                                contactName: message.contactName,
                                body: message.body,
                                messageId: message.id
                            )
                        }
                    }

                    if !mergeResult.matchedPendingIds.isEmpty {
                        self.pendingOutgoingQueue.sync {
                            for id in mergeResult.matchedPendingIds {
                                self.pendingOutgoingMessages.removeValue(forKey: id)
                            }
                        }
                    }

                    self.messages = mergeResult.mergedMessages
                    self.conversations = newConversations
                    self.isLoading = false

                    // Track oldest message timestamp for pagination
                    if let oldestMessage = mergeResult.mergedMessages.min(by: { $0.date < $1.date }) {
                        self.loadedTimeRangeStart = oldestMessage.date / 1000  // Convert to seconds
                    }
                    // All messages loaded (no time filter), so no need for pagination
                    self.canLoadMore = false

                    // Update badge count
                    self.notificationService.setBadgeCount(self.totalUnreadCount)
                }
            }
        }

        reactionsListenerHandle = firebaseService.listenToMessageReactions(userId: userId) { [weak self] reactions in
            DispatchQueue.main.async {
                self?.messageReactions = reactions
            }
        }

        readReceiptsListenerHandle = firebaseService.listenToReadReceipts(userId: userId) { [weak self] receipts in
            DispatchQueue.main.async {
                self?.readReceipts = receipts
                self?.readReceiptsLoaded = true  // Mark that read receipts have been loaded
                self?.messages = self?.applyReadStatus(to: self?.messages ?? []) ?? []
                self?.updateConversations(from: self?.messages ?? [])
                self?.notificationService.setBadgeCount(self?.totalUnreadCount ?? 0)
            }
        }

        spamListenerHandle = firebaseService.listenToSpamMessages(userId: userId) { [weak self] spam in
            DispatchQueue.main.async {
                self?.spamMessages = spam
                if self?.selectedSpamAddress == nil {
                    self?.selectedSpamAddress = spam.first?.address
                }
            }
        }

        startListeningForContacts(userId: userId)
    }

    // MARK: - Stop Listening

    func stopListening() {
        if let handle = messageListenerHandle, let userId = currentUserId {
            firebaseService.removeMessageListener(userId: userId, handle: handle)
            messageListenerHandle = nil
        }
        if let reactionsHandle = reactionsListenerHandle, let userId = currentUserId {
            firebaseService.removeMessageReactionsListener(userId: userId, handle: reactionsHandle)
            reactionsListenerHandle = nil
        }
        if let receiptsHandle = readReceiptsListenerHandle, let userId = currentUserId {
            firebaseService.removeReadReceiptsListener(userId: userId, handle: receiptsHandle)
            readReceiptsListenerHandle = nil
        }
        if let spamHandle = spamListenerHandle, let userId = currentUserId {
            firebaseService.removeSpamMessagesListener(userId: userId, handle: spamHandle)
            spamListenerHandle = nil
        }
        stopListeningForContacts()
        currentUserId = nil
        readReceiptsLoaded = false  // Reset flag when stopping
        loadedTimeRangeStart = nil
        canLoadMore = false
    }

    // MARK: - Load More Messages (Pagination)

    func loadMoreMessages() {
        guard let userId = currentUserId, !isLoadingMore, canLoadMore,
              let oldestTimestamp = loadedTimeRangeStart else {
            print("[MessageStore] Cannot load more: userId=\(currentUserId != nil), loading=\(isLoadingMore), canLoad=\(canLoadMore), oldest=\(loadedTimeRangeStart != nil)")
            return
        }

        isLoadingMore = true
        print("[MessageStore] Loading more messages older than \(Date(timeIntervalSince1970: oldestTimestamp))")

        // Calculate new time range (30 more days back)
        let endTime = oldestTimestamp * 1000  // Convert to milliseconds
        let startTime = (oldestTimestamp - Double(loadMoreDays * 24 * 60 * 60)) * 1000

        Task {
            do {
                let olderMessages = try await firebaseService.loadMessagesInTimeRange(
                    userId: userId,
                    startTime: startTime,
                    endTime: endTime
                )

                await MainActor.run {
                    print("[MessageStore] Loaded \(olderMessages.count) older messages")

                    // Merge with existing messages
                    var allMessages = self.messages + olderMessages
                    allMessages = Array(Set(allMessages))  // Deduplicate
                    allMessages.sort { $0.date > $1.date }  // Sort newest first

                    self.messages = self.applyReadStatus(to: allMessages)
                    self.updateConversations(from: self.messages)

                    // Update pagination state
                    if let newOldest = olderMessages.min(by: { $0.date < $1.date }) {
                        self.loadedTimeRangeStart = newOldest.date / 1000
                        // Check if we hit the time range boundary (more messages might exist)
                        self.canLoadMore = abs(newOldest.date / 1000 - startTime / 1000) < (24 * 60 * 60)
                    } else {
                        // No more messages found
                        self.canLoadMore = false
                    }

                    self.isLoadingMore = false
                }
            } catch {
                await MainActor.run {
                    print("[MessageStore] Error loading more messages: \(error)")
                    self.error = error
                    self.isLoadingMore = false
                }
            }
        }
    }

    // MARK: - Read Status

    private func applyReadStatus(to messages: [Message]) -> [Message] {
        // Batch read all read message IDs once (avoids reading UserDefaults for each message)
        let readMessageIds = Set(UserDefaults.standard.stringArray(forKey: "readMessages") ?? [])
        let readReceiptIds = Set(readReceipts.keys)

        return messages.map { message in
            var updatedMessage = message

            // Sent messages are always read
            if message.type == 2 {
                updatedMessage.isRead = true
            }
            // Check local macOS read status first
            else if readMessageIds.contains(message.id) {
                updatedMessage.isRead = true
            }
            // Check if Android marked it as read (read receipt from Android)
            else if readReceiptIds.contains(message.id) {
                updatedMessage.isRead = true
            }
            // If we haven't received any read receipts yet, assume synced messages are read
            else if !readReceiptsLoaded {
                updatedMessage.isRead = true
            }
            // Otherwise keep as is (default will be true from Message struct)
            else {
                updatedMessage.isRead = true
            }

            return updatedMessage
        }
    }

    var totalUnreadCount: Int {
        return conversations.filter { !$0.isArchived }.reduce(0) { $0 + $1.unreadCount }
    }

    func markConversationAsRead(_ conversation: Conversation) {
        // Get all messages for this conversation (using normalized address matching)
        let conversationMessages = messages(for: conversation)
        let unreadMessageIds = conversationMessages.filter { $0.isReceived && !$0.isRead }.map { $0.id }
        preferences.markConversationAsRead(conversation.address, messageIds: unreadMessageIds)

        if let userId = currentUserId, !unreadMessageIds.isEmpty {
            let normalizedAddress = normalizePhoneNumber(conversation.address)
            let deviceName = Host.current().localizedName ?? "Mac"
            Task {
                try? await firebaseService.markMessagesRead(
                    userId: userId,
                    messageIds: unreadMessageIds,
                    conversationAddress: normalizedAddress,
                    readBy: "macos",
                    readDeviceName: deviceName
                )
            }
        }

        // Refresh conversations
        messages = applyReadStatus(to: messages)
        updateConversations(from: messages)
        notificationService.setBadgeCount(totalUnreadCount)
    }

    // MARK: - Update Conversations

    /// Build conversations from messages (thread-safe, can be called from background)
    private func buildConversations(from messages: [Message]) -> [Conversation] {
        // First, collect all unique addresses AND their normalized forms
        var allAddresses = Set<String>()
        var normalizedToOriginal: [String: String] = [:] // normalized -> first seen original address
        for message in messages {
            allAddresses.insert(message.address)
            let normalized = normalizePhoneNumber(message.address)
            if normalizedToOriginal[normalized] == nil {
                normalizedToOriginal[normalized] = message.address
            }
        }

        // Batch read ALL preferences in ONE main thread call (avoids repeated sync calls)
        var prefCache: [String: (isPinned: Bool, isArchived: Bool, isBlocked: Bool, avatarColor: String?)] = [:]
        DispatchQueue.main.sync {
            for address in allAddresses {
                prefCache[address] = (
                    isPinned: self.preferences.isPinned(address),
                    isArchived: self.preferences.isArchived(address),
                    isBlocked: self.preferences.isBlocked(address),
                    avatarColor: self.preferences.getAvatarColor(for: address)
                )
            }
        }

        // Now process messages entirely on background thread using cached prefs
        // Group by NORMALIZED address to merge duplicate contacts with different formats
        var conversationDict: [String: (primaryAddress: String, lastMessage: Message, messages: [Message], allAddresses: Set<String>)] = [:]

        for message in messages {
            let address = message.address
            let normalizedAddress = normalizePhoneNumber(address)

            // Skip blocked numbers (using cached value)
            if prefCache[address]?.isBlocked == true {
                continue
            }

            if var existing = conversationDict[normalizedAddress] {
                existing.messages.append(message)
                existing.allAddresses.insert(address)
                if message.date > existing.lastMessage.date {
                    existing.lastMessage = message
                    existing.primaryAddress = address // Use the most recent message's address
                }
                conversationDict[normalizedAddress] = existing
            } else {
                conversationDict[normalizedAddress] = (
                    primaryAddress: address,
                    lastMessage: message,
                    messages: [message],
                    allAddresses: [address]
                )
            }
        }

        // Convert to Conversation objects (no main thread calls needed)
        var newConversations: [Conversation] = []

        for (normalizedAddress, data) in conversationDict {
            // Get prefs from any of the addresses (prefer primary)
            let prefs = prefCache[data.primaryAddress]
                ?? data.allAddresses.compactMap({ prefCache[$0] }).first
                ?? (isPinned: false, isArchived: false, isBlocked: false, avatarColor: nil)

            let unreadCount = data.messages.filter { $0.isReceived && !$0.isRead }.count

            // Find the best contact name from any message in the conversation
            // (last message might not have contact name, but earlier ones might)
            let candidateName = data.lastMessage.contactName
                ?? data.messages.first(where: { $0.contactName != nil })?.contactName
            let contactName = resolveContactName(
                candidate: candidateName,
                normalizedAddress: normalizedAddress
            )

            let conversation = Conversation(
                id: normalizedAddress, // Use normalized address as ID for deduplication
                address: data.primaryAddress, // Use the most recent address for display/sending
                contactName: contactName,
                lastMessage: data.lastMessage.body,
                timestamp: data.lastMessage.timestamp,
                unreadCount: unreadCount,
                allAddresses: Array(data.allAddresses),
                isPinned: prefs.isPinned,
                isArchived: prefs.isArchived,
                isBlocked: prefs.isBlocked,
                avatarColor: prefs.avatarColor
            )

            newConversations.append(conversation)
        }

        // Sort: Pinned first, then by timestamp
        newConversations.sort { conv1, conv2 in
            if conv1.isPinned != conv2.isPinned {
                return conv1.isPinned
            }
            return conv1.timestamp > conv2.timestamp
        }

        return newConversations
    }

    /// Update conversations on main thread (for actions like pin/archive)
    private func updateConversations(from messages: [Message]) {
        // This is called from main thread, so do sync version without DispatchQueue.main.sync
        // Group by NORMALIZED address to merge duplicate contacts
        var conversationDict: [String: (primaryAddress: String, lastMessage: Message, messages: [Message], allAddresses: Set<String>)] = [:]

        for message in messages {
            let address = message.address
            let normalizedAddress = normalizePhoneNumber(address)
            if preferences.isBlocked(address) { continue }

            if var existing = conversationDict[normalizedAddress] {
                existing.messages.append(message)
                existing.allAddresses.insert(address)
                if message.date > existing.lastMessage.date {
                    existing.lastMessage = message
                    existing.primaryAddress = address
                }
                conversationDict[normalizedAddress] = existing
            } else {
                conversationDict[normalizedAddress] = (
                    primaryAddress: address,
                    lastMessage: message,
                    messages: [message],
                    allAddresses: [address]
                )
            }
        }

        var newConversations: [Conversation] = []
        for (normalizedAddress, data) in conversationDict {
            let unreadCount = data.messages.filter { $0.isReceived && !$0.isRead }.count

            // Find the best contact name from any message in the conversation
            let candidateName = data.lastMessage.contactName
                ?? data.messages.first(where: { $0.contactName != nil })?.contactName
            let contactName = resolveContactName(
                candidate: candidateName,
                normalizedAddress: normalizedAddress
            )

            // Get prefs from any of the addresses (prefer primary)
            let isPinned = preferences.isPinned(data.primaryAddress) || data.allAddresses.contains(where: { preferences.isPinned($0) })
            let isArchived = preferences.isArchived(data.primaryAddress) || data.allAddresses.contains(where: { preferences.isArchived($0) })
            let isBlocked = preferences.isBlocked(data.primaryAddress) || data.allAddresses.contains(where: { preferences.isBlocked($0) })
            let avatarColor = preferences.getAvatarColor(for: data.primaryAddress)

            let conversation = Conversation(
                id: normalizedAddress,
                address: data.primaryAddress,
                contactName: contactName,
                lastMessage: data.lastMessage.body,
                timestamp: data.lastMessage.timestamp,
                unreadCount: unreadCount,
                allAddresses: Array(data.allAddresses),
                isPinned: isPinned,
                isArchived: isArchived,
                isBlocked: isBlocked,
                avatarColor: avatarColor
            )
            newConversations.append(conversation)
        }

        newConversations.sort { conv1, conv2 in
            if conv1.isPinned != conv2.isPinned { return conv1.isPinned }
            return conv1.timestamp > conv2.timestamp
        }

        self.conversations = newConversations
    }

    // MARK: - Get Messages for Conversation

    func messages(for conversation: Conversation) -> [Message] {
        // Use normalized address to get all messages from different phone number formats
        let normalizedConversationAddress = normalizePhoneNumber(conversation.address)
        return messages
            .filter { normalizePhoneNumber($0.address) == normalizedConversationAddress }
            .sorted { $0.date < $1.date }  // Oldest first for chat view
    }

    // MARK: - Send Message

    func sendMessage(userId: String, to address: String, body: String) async throws {
        guard !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return
        }

        let pendingMessage = createPendingOutgoingMessage(to: address, body: body)

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.messages.append(pendingMessage)
            self.updateConversations(from: self.messages)
        }

        pendingOutgoingQueue.sync {
            pendingOutgoingMessages[pendingMessage.id] = pendingMessage
        }

        do {
            try await firebaseService.sendMessage(userId: userId, to: address, body: body)
        } catch {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.messages.removeAll { $0.id == pendingMessage.id }
                self.updateConversations(from: self.messages)
            }

            pendingOutgoingQueue.sync {
                pendingOutgoingMessages.removeValue(forKey: pendingMessage.id)
            }

            DispatchQueue.main.async {
                self.error = error
            }
            throw error
        }
    }

    private func createPendingOutgoingMessage(to address: String, body: String) -> Message {
        let normalizedAddress = normalizePhoneNumber(address)
        let contactName = conversations.first { normalizePhoneNumber($0.address) == normalizedAddress }?.contactName

        return Message(
            id: "pending_\(UUID().uuidString)",
            address: address,
            body: body,
            date: Date().timeIntervalSince1970 * 1000.0,
            type: 2,
            contactName: contactName,
            isRead: true,
            isMms: false,
            attachments: nil,
            e2eeFailed: false,
            e2eeFailureReason: nil
        )
    }

    private func mergeMessagesWithPendingOutgoing(remoteMessages: [Message]) -> (mergedMessages: [Message], matchedPendingIds: Set<String>) {
        let pendingSnapshot = pendingOutgoingQueue.sync { pendingOutgoingMessages }
        guard !pendingSnapshot.isEmpty else {
            return (remoteMessages, [])
        }

        let sentRemoteMessages = remoteMessages.filter { $0.type == 2 }
        var remainingPending: [Message] = []
        var matchedIds: Set<String> = []

        for pending in pendingSnapshot.values {
            if hasMatchingRemoteMessage(pending: pending, remoteMessages: sentRemoteMessages) {
                matchedIds.insert(pending.id)
            } else {
                remainingPending.append(pending)
            }
        }

        let merged = (remoteMessages + remainingPending).sorted { $0.date < $1.date }
        return (merged, matchedIds)
    }

    private func hasMatchingRemoteMessage(pending: Message, remoteMessages: [Message]) -> Bool {
        let pendingAddress = normalizePhoneNumber(pending.address)
        let trimmedBody = pending.body.trimmingCharacters(in: .whitespacesAndNewlines)
        let maxDeltaMs = 5.0 * 60.0 * 1000.0

        for remote in remoteMessages {
            guard remote.type == 2 else { continue }
            guard normalizePhoneNumber(remote.address) == pendingAddress else { continue }
            guard remote.body.trimmingCharacters(in: .whitespacesAndNewlines) == trimmedBody else { continue }
            guard abs(remote.date - pending.date) <= maxDeltaMs else { continue }
            guard remote.isMms == pending.isMms else { continue }
            return true
        }

        return false
    }

    func setReaction(messageId: String, reaction: String?) {
        guard let userId = currentUserId else { return }

        if let reaction = reaction, !reaction.isEmpty {
            messageReactions[messageId] = reaction
        } else {
            messageReactions.removeValue(forKey: messageId)
        }

        Task {
            try? await firebaseService.setMessageReaction(
                userId: userId,
                messageId: messageId,
                reaction: reaction
            )
        }
    }

    /// Delete a message
    func deleteMessage(_ message: Message) {
        guard let userId = currentUserId else { return }

        // Optimistically remove from local state
        messages.removeAll { $0.id == message.id }
        updateConversations(from: messages)

        // Also remove any reaction
        messageReactions.removeValue(forKey: message.id)

        // Also unpin if pinned
        pinnedMessages.remove(message.id)
        savePinnedMessages()

        // Delete from Firebase
        Task {
            do {
                try await firebaseService.deleteMessages(userId: userId, messageIds: [message.id])
            } catch {
                print("Failed to delete message: \(error)")
                // Could re-add message on failure, but Firebase listener will sync anyway
            }
        }
    }

    /// Delete multiple messages
    func deleteMessages(_ messagesToDelete: [Message]) {
        guard let userId = currentUserId else { return }
        let ids = Set(messagesToDelete.map { $0.id })
        if ids.isEmpty { return }

        // Optimistically remove from local state
        messages.removeAll { ids.contains($0.id) }
        updateConversations(from: messages)

        // Clean related local state
        ids.forEach { id in
            messageReactions.removeValue(forKey: id)
            readReceipts.removeValue(forKey: id)
        }
        pinnedMessages.subtract(ids)
        savePinnedMessages()

        // Delete from Firebase
        Task {
            do {
                try await firebaseService.deleteMessages(userId: userId, messageIds: Array(ids))
            } catch {
                print("Failed to delete messages: \(error)")
            }
        }
    }

    // MARK: - Message Pinning

    /// Pin or unpin a message
    func togglePinMessage(_ message: Message) {
        if pinnedMessages.contains(message.id) {
            pinnedMessages.remove(message.id)
        } else {
            pinnedMessages.insert(message.id)
        }
        savePinnedMessages()
    }

    /// Check if a message is pinned
    func isMessagePinned(_ message: Message) -> Bool {
        return pinnedMessages.contains(message.id)
    }

    /// Get all pinned messages for a conversation
    func pinnedMessages(for conversation: Conversation) -> [Message] {
        let normalizedConversationAddress = normalizePhoneNumber(conversation.address)
        return messages
            .filter { normalizePhoneNumber($0.address) == normalizedConversationAddress && pinnedMessages.contains($0.id) }
            .sorted { $0.date > $1.date }  // Most recent first
    }

    /// Load pinned messages from local storage
    private func loadPinnedMessages() {
        let defaults = UserDefaults.standard
        if let savedPins = defaults.array(forKey: "pinned_messages") as? [String] {
            pinnedMessages = Set(savedPins)
        }
    }

    /// Save pinned messages to local storage
    private func savePinnedMessages() {
        let defaults = UserDefaults.standard
        defaults.set(Array(pinnedMessages), forKey: "pinned_messages")
    }

    // MARK: - Send MMS Message

    func sendMmsMessage(userId: String, to address: String, body: String, attachment: SelectedAttachment) async throws {
        do {
            try await firebaseService.sendMmsMessage(
                userId: userId,
                to: address,
                body: body,
                attachmentData: attachment.data,
                fileName: attachment.fileName,
                contentType: attachment.contentType,
                attachmentType: attachment.type
            )
        } catch {
            DispatchQueue.main.async {
                self.error = error
            }
            throw error
        }
    }

    // MARK: - Conversation Actions

    func togglePin(_ conversation: Conversation) {
        preferences.setPinned(conversation.address, pinned: !conversation.isPinned)
        updateConversations(from: messages)
    }

    func toggleArchive(_ conversation: Conversation) {
        preferences.setArchived(conversation.address, archived: !conversation.isArchived)
        updateConversations(from: messages)
    }

    func toggleBlock(_ conversation: Conversation) {
        preferences.setBlocked(conversation.address, blocked: !conversation.isBlocked)
        updateConversations(from: messages)
    }

    func deleteConversation(_ conversation: Conversation) {
        deleteConversations([conversation])
    }

    /// Delete multiple conversations (and all messages in them)
    func deleteConversations(_ conversations: [Conversation]) {
        guard !conversations.isEmpty else { return }

        let addresses = Set(conversations.map { $0.address })
        let messagesToDelete = messages.filter { addresses.contains($0.address) }

        // Remove from preferences
        for convo in conversations {
            preferences.setArchived(convo.address, archived: false)
            preferences.setPinned(convo.address, pinned: false)
        }

        deleteMessages(messagesToDelete)
    }

    // MARK: - Filtered Conversations

    private var spamAddressLookup: Set<String> {
        let normalized = spamMessages.map { normalizePhoneNumber($0.address) }
        return Set(normalized)
    }

    private func isSpamConversation(_ conversation: Conversation) -> Bool {
        let normalized = normalizePhoneNumber(conversation.address)
        return spamAddressLookup.contains(normalized)
    }

    var activeConversations: [Conversation] {
        return conversations.filter { !$0.isArchived && !isSpamConversation($0) }
    }

    var archivedConversations: [Conversation] {
        return conversations.filter { $0.isArchived && !isSpamConversation($0) }
    }

    var unreadConversations: [Conversation] {
        return activeConversations.filter { $0.unreadCount > 0 }
    }

    var displayedConversations: [Conversation] {
        if showSpamOnly {
            return []
        } else if showArchived {
            return archivedConversations
        } else if showUnreadOnly {
            return unreadConversations
        } else {
            return activeConversations
        }
    }

    /// Filter mode for conversations
    enum ConversationFilter: String, CaseIterable {
        case all = "All"
        case unread = "Unread"
        case archived = "Archived"
        case spam = "Spam"

        var icon: String {
            switch self {
            case .all: return "tray"
            case .unread: return "envelope.badge"
            case .archived: return "archivebox"
            case .spam: return "shield.lefthalf.filled"
            }
        }
    }

    var currentFilter: ConversationFilter {
        get {
            if showSpamOnly {
                return .spam
            } else if showArchived {
                return .archived
            } else if showUnreadOnly {
                return .unread
            } else {
                return .all
            }
        }
        set {
            switch newValue {
            case .all:
                showArchived = false
                showUnreadOnly = false
                showSpamOnly = false
            case .unread:
                showArchived = false
                showUnreadOnly = true
                showSpamOnly = false
            case .archived:
                showArchived = true
                showUnreadOnly = false
                showSpamOnly = false
            case .spam:
                showArchived = false
                showUnreadOnly = false
                showSpamOnly = true
            }
        }
    }

    var spamConversations: [SpamConversation] {
        let grouped = Dictionary(grouping: spamMessages) { $0.address }
        return grouped.map { (address, messages) in
            let latest = messages.max(by: { $0.date < $1.date })
            return SpamConversation(
                address: address,
                contactName: latest?.contactName ?? address,
                latestMessage: latest?.body ?? "",
                timestamp: latest?.date ?? 0,
                messageCount: messages.count
            )
        }.sorted { $0.timestamp > $1.timestamp }
    }

    func spamMessages(for address: String) -> [SpamMessage] {
        return spamMessages
            .filter { $0.address == address }
            .sorted { $0.date < $1.date }
    }

    func deleteSpamMessages(for address: String) async {
        guard let userId = currentUserId else { return }
        let ids = spamMessages.filter { $0.address == address }.map { $0.id }
        for id in ids {
            try? await firebaseService.deleteSpamMessage(userId: userId, messageId: id)
        }
    }

    func clearAllSpam() async {
        guard let userId = currentUserId else { return }
        try? await firebaseService.clearAllSpamMessages(userId: userId)
    }

    func markMessageAsSpam(_ message: Message) {
        guard let userId = currentUserId else { return }
        Task {
            do {
                try await firebaseService.markMessageAsSpam(userId: userId, message: message)
            } catch {
                print("[MessageStore] Failed to mark message as spam: \(error)")
            }
        }
    }

    func markConversationAsSpam(_ conversation: Conversation) {
        guard let latest = messages
            .filter({ $0.address == conversation.address })
            .max(by: { $0.date < $1.date }) else {
            print("[MessageStore] No messages found to mark as spam for \(conversation.address)")
            return
        }
        markMessageAsSpam(latest)
    }

    // MARK: - Search

    /// Extract only digits from a string for phone number comparison
    private func digitsOnly(_ value: String) -> String {
        return value.filter { $0.isNumber }
    }

    func search(query: String, in conversationsList: [Conversation] = []) -> [Conversation] {
        let list = conversationsList.isEmpty ? conversations : conversationsList

        if query.isEmpty {
            return list
        }

        let lowercaseQuery = query.lowercased()
        let queryDigits = query.filter { $0.isNumber }

        return list.filter { conversation in
            // Match by display name (contact name or address)
            if conversation.displayName.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Match by contact name if available
            if let contactName = conversation.contactName,
               contactName.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Match by last message content
            if conversation.lastMessage.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Match by address (exact or partial)
            if conversation.address.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Match by conversation ID
            if conversation.id.lowercased().contains(lowercaseQuery) {
                return true
            }

            // Phone number digit matching
            if queryDigits.count >= 3 {
                let addressDigits = conversation.address.filter { $0.isNumber }
                let idDigits = conversation.id.filter { $0.isNumber }

                // Check if query digits appear anywhere in address or id digits
                if addressDigits.contains(queryDigits) {
                    return true
                }
                if idDigits.contains(queryDigits) {
                    return true
                }

                // Check if address/id digits appear in query
                if queryDigits.contains(addressDigits) && !addressDigits.isEmpty {
                    return true
                }
                if queryDigits.contains(idDigits) && !idDigits.isEmpty {
                    return true
                }
            }

            return false
        }
    }

    func searchMessages(query: String) -> [Message] {
        if query.isEmpty {
            return []
        }

        // Get digits from query for phone number matching
        let queryDigits = digitsOnly(query)
        let isPhoneSearch = queryDigits.count >= 4

        return messages.filter { message in
            // Match by message body
            if message.body.localizedCaseInsensitiveContains(query) {
                return true
            }
            // Match by exact address
            if message.address.contains(query) {
                return true
            }
            // Match by contact name
            if message.contactName?.localizedCaseInsensitiveContains(query) == true {
                return true
            }
            // Match by phone number digits (handles all formats)
            if isPhoneSearch {
                let addressDigits = digitsOnly(message.address)
                // Check if digits match
                if addressDigits.contains(queryDigits) || queryDigits.contains(addressDigits) {
                    return true
                }
                // Also check last N digits match
                if queryDigits.count >= 7 && addressDigits.count >= 7 {
                    let queryLast7 = String(queryDigits.suffix(7))
                    let addressLast7 = String(addressDigits.suffix(7))
                    if queryLast7 == addressLast7 {
                        return true
                    }
                }
            }
            return false
        }
    }

    deinit {
        stopListening()
    }

    // MARK: - Contacts lookup

    private func startListeningForContacts(userId: String) {
        contactsListenerHandle = firebaseService.listenToContacts(userId: userId) { [weak self] contacts in
            DispatchQueue.main.async {
                self?.latestContacts = contacts
                self?.rebuildContactLookup()
            }
        }
    }

    private func stopListeningForContacts() {
        if let handle = contactsListenerHandle, let userId = currentUserId {
            firebaseService.removeContactsListener(userId: userId, handle: handle)
        }
        contactsListenerHandle = nil
        latestContacts = []
        contactNameLookup = [:]
    }

    private func rebuildContactLookup() {
        var lookup: [String: String] = [:]

        for contact in latestContacts {
            let normalized = normalizePhoneNumber(
                (contact.normalizedNumber ?? "").isEmpty ? (contact.phoneNumber ?? "") : (contact.normalizedNumber ?? "")
            )
            if !normalized.isEmpty {
                lookup[normalized] = contact.displayName
            }
        }

        contactNameLookup = lookup

        if !messages.isEmpty {
            updateConversations(from: messages)
        }
    }

    private func resolveContactName(candidate: String?, normalizedAddress: String) -> String? {
        let trimmed = candidate?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let lookup = contactNameLookup[normalizedAddress], !lookup.isEmpty {
            return lookup
        }

        if let name = trimmed,
           !name.isEmpty,
           name.rangeOfCharacter(from: .letters) != nil {
            return name
        }

        return trimmed?.isEmpty == true ? nil : trimmed
    }
}

struct SpamConversation: Identifiable, Hashable {
    let address: String
    let contactName: String
    let latestMessage: String
    let timestamp: Double
    let messageCount: Int

    var id: String { address }
}
