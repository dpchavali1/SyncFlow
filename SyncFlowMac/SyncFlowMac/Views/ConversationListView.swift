//
//  ConversationListView.swift
//  SyncFlowMac
//
//  Sidebar view showing all conversations
//

import SwiftUI

struct ConversationListView: View {
    @EnvironmentObject var messageStore: MessageStore
    @Binding var searchText: String
    @Binding var selectedConversation: Conversation?

    var filteredConversations: [Conversation] {
        if searchText.isEmpty {
            return messageStore.displayedConversations
        }
        return messageStore.search(query: searchText, in: messageStore.displayedConversations)
    }

    var body: some View {
        VStack(spacing: 0) {
            // Search bar and filter toggle
            VStack(spacing: 8) {
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.secondary)
                    TextField("Search conversations", text: $searchText)
                        .textFieldStyle(.plain)

                    if !searchText.isEmpty {
                        Button(action: { searchText = "" }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(8)
                .background(Color(nsColor: .controlBackgroundColor))
                .cornerRadius(8)

                // Archive toggle
                HStack {
                    Button(action: { messageStore.showArchived.toggle() }) {
                        HStack {
                            Image(systemName: messageStore.showArchived ? "tray.fill" : "tray")
                            Text(messageStore.showArchived ? "Archived" : "Active")
                                .font(.caption)
                        }
                    }
                    .buttonStyle(.borderless)
                    .foregroundColor(messageStore.showArchived ? .orange : .secondary)

                    Spacer()

                    if !messageStore.showArchived && messageStore.totalUnreadCount > 0 {
                        Text("\(messageStore.totalUnreadCount) unread")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }
            .padding()

            Divider()

            // Conversations list
            if messageStore.isLoading && messageStore.conversations.isEmpty {
                VStack(spacing: 10) {
                    ProgressView()
                    Text("Loading conversations...")
                        .foregroundColor(.secondary)
                        .font(.caption)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if filteredConversations.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: messageStore.showArchived ? "archivebox" : "tray")
                        .font(.system(size: 40))
                        .foregroundColor(.secondary)
                    Text(messageStore.showArchived ? "No archived conversations" : (searchText.isEmpty ? "No conversations yet" : "No results"))
                        .foregroundColor(.secondary)

                    if messageStore.showArchived && !messageStore.archivedConversations.isEmpty {
                        Button("Show Active") {
                            messageStore.showArchived = false
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(filteredConversations) { conversation in
                            ConversationRow(
                                conversation: conversation,
                                isSelected: selectedConversation?.id == conversation.id
                            )
                            .onTapGesture {
                                selectedConversation = conversation
                                messageStore.markConversationAsRead(conversation)
                            }
                            .contextMenu {
                                ConversationContextMenu(
                                    conversation: conversation,
                                    messageStore: messageStore
                                )
                            }
                        }
                    }
                }
            }
        }
        .background(Color(nsColor: .controlBackgroundColor))
    }
}

// MARK: - Conversation Context Menu

struct ConversationContextMenu: View {
    let conversation: Conversation
    let messageStore: MessageStore

    var body: some View {
        Button(action: {
            messageStore.togglePin(conversation)
        }) {
            Label(conversation.isPinned ? "Unpin" : "Pin", systemImage: conversation.isPinned ? "pin.slash" : "pin")
        }

        Button(action: {
            messageStore.markConversationAsRead(conversation)
        }) {
            Label("Mark as Read", systemImage: "envelope.open")
        }
        .disabled(conversation.unreadCount == 0)

        Divider()

        Button(action: {
            messageStore.toggleArchive(conversation)
        }) {
            Label(conversation.isArchived ? "Unarchive" : "Archive", systemImage: conversation.isArchived ? "tray.and.arrow.up" : "archivebox")
        }

        Button(action: {
            messageStore.toggleBlock(conversation)
        }) {
            Label(conversation.isBlocked ? "Unblock" : "Block", systemImage: conversation.isBlocked ? "hand.raised.slash" : "hand.raised")
        }

        Divider()

        Button(role: .destructive, action: {
            messageStore.deleteConversation(conversation)
        }) {
            Label("Delete Conversation", systemImage: "trash")
        }
    }
}

// MARK: - Conversation Row

struct ConversationRow: View {
    let conversation: Conversation
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 12) {
            // Avatar with color
            ZStack(alignment: .topTrailing) {
                Circle()
                    .fill(Color(hex: conversation.avatarColor ?? "#2196F3"))
                    .frame(width: 44, height: 44)
                    .overlay(
                        Text(conversation.initials)
                            .font(.headline)
                            .foregroundColor(.white)
                    )

                // Pin indicator
                if conversation.isPinned {
                    Circle()
                        .fill(Color.orange)
                        .frame(width: 16, height: 16)
                        .overlay(
                            Image(systemName: "pin.fill")
                                .font(.system(size: 8))
                                .foregroundColor(.white)
                        )
                        .offset(x: 4, y: -4)
                }
            }

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(conversation.displayName)
                        .font(.headline)
                        .fontWeight(conversation.unreadCount > 0 ? .bold : .regular)
                        .lineLimit(1)

                    Spacer()

                    Text(conversation.formattedTime)
                        .font(.caption)
                        .foregroundColor(conversation.unreadCount > 0 ? .blue : .secondary)
                        .fontWeight(conversation.unreadCount > 0 ? .semibold : .regular)
                }

                HStack(alignment: .center, spacing: 4) {
                    Text(conversation.preview)
                        .font(.subheadline)
                        .foregroundColor(conversation.unreadCount > 0 ? .primary : .secondary)
                        .fontWeight(conversation.unreadCount > 0 ? .medium : .regular)
                        .lineLimit(2)

                    Spacer()

                    if conversation.unreadCount > 0 {
                        ZStack {
                            Capsule()
                                .fill(Color.blue)
                                .frame(minWidth: 20, maxHeight: 18)

                            Text("\(conversation.unreadCount)")
                                .font(.caption2)
                                .fontWeight(.bold)
                                .foregroundColor(.white)
                                .padding(.horizontal, 6)
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(isSelected ? Color.blue.opacity(0.15) : Color.clear)
        .contentShape(Rectangle())
    }
}

// MARK: - Color Extension

extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 3: // RGB (12-bit)
            (a, r, g, b) = (255, (int >> 8) * 17, (int >> 4 & 0xF) * 17, (int & 0xF) * 17)
        case 6: // RGB (24-bit)
            (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8: // ARGB (32-bit)
            (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:
            (a, r, g, b) = (255, 0, 0, 0)
        }

        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue:  Double(b) / 255,
            opacity: Double(a) / 255
        )
    }
}
