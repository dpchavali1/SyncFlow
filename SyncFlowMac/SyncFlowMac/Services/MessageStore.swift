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

    private var messageListenerHandle: DatabaseHandle?
    private var currentUserId: String?
    private var lastMessageIds: Set<String> = []
    private var cancellables = Set<AnyCancellable>()

    private let firebaseService = FirebaseService.shared
    private let notificationService = NotificationService.shared
    private let preferences = PreferencesService.shared

    // MARK: - Initialization

    init() {
        setupNotificationHandlers()
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
        guard currentUserId != userId else { return }

        // Remove old listener if exists
        if let handle = messageListenerHandle, let oldUserId = currentUserId {
            firebaseService.removeMessageListener(userId: oldUserId, handle: handle)
        }

        currentUserId = userId
        isLoading = true

        // Start listening to messages
        messageListenerHandle = firebaseService.listenToMessages(userId: userId) { [weak self] messages in
            DispatchQueue.main.async {
                guard let self = self else { return }

                // Detect new messages for notifications
                let newMessageIds = Set(messages.map { $0.id })
                let actuallyNewIds = newMessageIds.subtracting(self.lastMessageIds)

                if !actuallyNewIds.isEmpty && !self.lastMessageIds.isEmpty {
                    // Show notifications for new messages
                    let newMessages = messages.filter { actuallyNewIds.contains($0.id) && $0.isReceived }
                    for message in newMessages {
                        self.notificationService.showMessageNotification(
                            from: message.address,
                            contactName: message.contactName,
                            body: message.body,
                            messageId: message.id
                        )
                    }
                }

                self.lastMessageIds = newMessageIds
                self.messages = self.applyReadStatus(to: messages)
                self.updateConversations(from: self.messages)
                self.isLoading = false

                // Update badge count
                let unreadCount = self.totalUnreadCount
                self.notificationService.setBadgeCount(unreadCount)
            }
        }
    }

    // MARK: - Stop Listening

    func stopListening() {
        guard let handle = messageListenerHandle, let userId = currentUserId else { return }
        firebaseService.removeMessageListener(userId: userId, handle: handle)
        messageListenerHandle = nil
        currentUserId = nil
    }

    // MARK: - Read Status

    private func applyReadStatus(to messages: [Message]) -> [Message] {
        return messages.map { message in
            var updatedMessage = message
            updatedMessage.isRead = preferences.isMessageRead(message.id) || message.type == 2 // Sent messages are always "read"
            return updatedMessage
        }
    }

    var totalUnreadCount: Int {
        return conversations.filter { !$0.isArchived }.reduce(0) { $0 + $1.unreadCount }
    }

    func markConversationAsRead(_ conversation: Conversation) {
        let messageIds = messages(for: conversation).filter { !$0.isRead }.map { $0.id }
        preferences.markConversationAsRead(conversation.address, messageIds: messageIds)

        // Refresh conversations
        messages = applyReadStatus(to: messages)
        updateConversations(from: messages)
        notificationService.setBadgeCount(totalUnreadCount)
    }

    // MARK: - Update Conversations

    private func updateConversations(from messages: [Message]) {
        var conversationDict: [String: (lastMessage: Message, messages: [Message])] = [:]

        // Group messages by address
        for message in messages {
            let address = message.address

            // Skip blocked numbers
            if preferences.isBlocked(address) {
                continue
            }

            if var existing = conversationDict[address] {
                existing.messages.append(message)
                // Keep the most recent message
                if message.date > existing.lastMessage.date {
                    existing.lastMessage = message
                }
                conversationDict[address] = existing
            } else {
                conversationDict[address] = (lastMessage: message, messages: [message])
            }
        }

        // Convert to Conversation objects
        var newConversations: [Conversation] = []

        for (address, data) in conversationDict {
            let unreadCount = data.messages.filter { $0.isReceived && !$0.isRead }.count

            var conversation = Conversation(
                id: address,
                address: address,
                contactName: data.lastMessage.contactName,
                lastMessage: data.lastMessage.body,
                timestamp: data.lastMessage.timestamp,
                unreadCount: unreadCount,
                isPinned: preferences.isPinned(address),
                isArchived: preferences.isArchived(address),
                isBlocked: preferences.isBlocked(address),
                avatarColor: preferences.getAvatarColor(for: address)
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

        self.conversations = newConversations
    }

    // MARK: - Get Messages for Conversation

    func messages(for conversation: Conversation) -> [Message] {
        return messages
            .filter { $0.address == conversation.address }
            .sorted { $0.date < $1.date }  // Oldest first for chat view
    }

    // MARK: - Send Message

    func sendMessage(userId: String, to address: String, body: String) async throws {
        guard !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return
        }

        do {
            try await firebaseService.sendMessage(userId: userId, to: address, body: body)
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
        // Remove all messages for this conversation
        messages.removeAll { $0.address == conversation.address }
        updateConversations(from: messages)

        // Also remove from preferences
        preferences.setArchived(conversation.address, archived: false)
        preferences.setPinned(conversation.address, pinned: false)
    }

    // MARK: - Filtered Conversations

    var activeConversations: [Conversation] {
        return conversations.filter { !$0.isArchived }
    }

    var archivedConversations: [Conversation] {
        return conversations.filter { $0.isArchived }
    }

    var displayedConversations: [Conversation] {
        return showArchived ? archivedConversations : activeConversations
    }

    // MARK: - Search

    func search(query: String, in conversationsList: [Conversation] = []) -> [Conversation] {
        let list = conversationsList.isEmpty ? conversations : conversationsList

        if query.isEmpty {
            return list
        }

        return list.filter { conversation in
            conversation.displayName.localizedCaseInsensitiveContains(query) ||
            conversation.lastMessage.localizedCaseInsensitiveContains(query) ||
            conversation.address.contains(query)
        }
    }

    func searchMessages(query: String) -> [Message] {
        if query.isEmpty {
            return []
        }

        return messages.filter { message in
            message.body.localizedCaseInsensitiveContains(query) ||
            message.address.contains(query) ||
            (message.contactName?.localizedCaseInsensitiveContains(query) ?? false)
        }
    }

    deinit {
        stopListening()
    }
}
