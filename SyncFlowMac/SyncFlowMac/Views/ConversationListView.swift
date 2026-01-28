//
//  ConversationListView.swift
//  SyncFlowMac
//
//  Sidebar view showing all conversations
//

import SwiftUI

struct ConversationListView: View {
    @EnvironmentObject var messageStore: MessageStore
    @EnvironmentObject var appState: AppState
    @Binding var searchText: String
    @Binding var selectedConversation: Conversation?

    @State private var showMessageResults = false
    @State private var selectedLabelId: String? = nil
    @State private var availableLabels: [PreferencesService.ConversationLabel] = []
    @State private var isSelectionMode = false
    @State private var selectedConversationIds: Set<String> = []
    @State private var showBulkDeleteConfirmation = false

    private let preferences = PreferencesService.shared

    var filteredConversations: [Conversation] {
        var conversations = searchText.isEmpty
            ? messageStore.displayedConversations
            : messageStore.search(query: searchText, in: messageStore.displayedConversations)

        // Filter by label if selected
        if let labelId = selectedLabelId {
            let labeledAddresses = Set(preferences.getConversations(with: labelId))
            conversations = conversations.filter { labeledAddresses.contains($0.address) }
        }

        return conversations
    }

    var messageSearchResults: [Message] {
        guard searchText.count >= 2 else { return [] }
        return Array(messageStore.searchMessages(query: searchText).prefix(20))
    }

    private var pinnedConversations: [Conversation] {
        filteredConversations.filter { $0.isPinned }
    }

    private var regularConversations: [Conversation] {
        filteredConversations.filter { !$0.isPinned }
    }

    var emptyStateIcon: String {
        if !searchText.isEmpty {
            return "magnifyingglass"
        }
        switch messageStore.currentFilter {
        case .all:
            return "tray"
        case .unread:
            return "envelope.open"
        case .archived:
            return "archivebox"
        case .spam:
            return "shield.lefthalf.filled"
        }
    }

    var emptyStateMessage: String {
        if !searchText.isEmpty {
            return "No results found"
        }
        switch messageStore.currentFilter {
        case .all:
            return "No conversations yet"
        case .unread:
            return "All caught up!\nNo unread messages"
        case .archived:
            return "No archived conversations"
        case .spam:
            return "No spam messages"
        }
    }

    private func resolveConversation(for state: ContinuityService.ContinuityState) -> Conversation? {
        if let exact = messageStore.conversations.first(where: { $0.address == state.address }) {
            return exact
        }

        let normalizedTarget = normalizeAddress(state.address)
        if !normalizedTarget.isEmpty,
           let normalizedMatch = messageStore.conversations.first(where: {
               normalizeAddress($0.address) == normalizedTarget
           }) {
            return normalizedMatch
        }

        if let name = state.contactName?.lowercased(),
           let nameMatch = messageStore.conversations.first(where: {
               $0.contactName?.lowercased() == name
           }) {
            return nameMatch
        }

        return nil
    }

    private func isSameConversation(_ conversation: Conversation?, state: ContinuityService.ContinuityState) -> Bool {
        guard let conversation = conversation else { return false }
        if conversation.address == state.address {
            return true
        }
        let normalizedConversation = normalizeAddress(conversation.address)
        let normalizedState = normalizeAddress(state.address)
        return !normalizedConversation.isEmpty && normalizedConversation == normalizedState
    }

    private func normalizeAddress(_ value: String) -> String {
        return value.filter { $0.isNumber }
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
                .background(SyncFlowColors.surfaceSecondary)
                .cornerRadius(8)

                // Filter buttons
                HStack(spacing: 8) {
                    ForEach(MessageStore.ConversationFilter.allCases, id: \.self) { filter in
                        FilterButton(
                            filter: filter,
                            isSelected: messageStore.currentFilter == filter,
                            badgeCount: filter == .unread ? messageStore.totalUnreadCount : (filter == .spam ? messageStore.spamMessages.count : 0)
                        ) {
                            messageStore.currentFilter = filter
                        }
                    }

                    Spacer()
                }

                // Label filter chips
                if !availableLabels.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            // Clear filter button
                            if selectedLabelId != nil {
                                Button(action: { selectedLabelId = nil }) {
                                    HStack(spacing: 4) {
                                        Image(systemName: "xmark")
                                            .font(.caption2)
                                        Text("Clear")
                                            .font(.caption)
                                    }
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(Color.secondary.opacity(0.2))
                                    .cornerRadius(12)
                                }
                                .buttonStyle(.plain)
                            }

                            ForEach(availableLabels) { label in
                                LabelFilterChip(
                                    label: label,
                                    isSelected: selectedLabelId == label.id
                                ) {
                                    if selectedLabelId == label.id {
                                        selectedLabelId = nil
                                    } else {
                                        selectedLabelId = label.id
                                    }
                                }
                            }
                        }
                    }
                }

                HStack {
                    if isSelectionMode {
                        Text("\(selectedConversationIds.count) selected")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Spacer()
                        Button("Select All") {
                            selectedConversationIds = Set(filteredConversations.map { $0.id })
                        }
                        Button("Clear") {
                            selectedConversationIds.removeAll()
                        }
                        Button("Done") {
                            selectedConversationIds.removeAll()
                            isSelectionMode = false
                        }
                        Button("Delete") {
                            showBulkDeleteConfirmation = true
                        }
                        .disabled(selectedConversationIds.isEmpty)
                    } else {
                        Spacer()
                        Button("Select") {
                            isSelectionMode = true
                        }
                    }
                }
            }
            .padding()

            if let state = appState.continuitySuggestion,
               !isSameConversation(selectedConversation, state: state) {
                ContinuityBannerView(
                    state: state,
                    onOpen: {
                        if let conversation = resolveConversation(for: state) {
                            selectedConversation = conversation
                        }
                    },
                    onDismiss: {
                        appState.dismissContinuitySuggestion()
                    }
                )
                .padding(.horizontal)
                .padding(.bottom, 8)
            }

            QuickDropView()
                .padding(.horizontal)
                .padding(.bottom, 8)

            Divider()

            // Conversations list
            if messageStore.isLoading && messageStore.conversations.isEmpty && messageStore.spamMessages.isEmpty {
                VStack(spacing: 10) {
                    ProgressView()
                    Text("Loading conversations...")
                        .foregroundColor(.secondary)
                        .font(.caption)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if messageStore.currentFilter == .spam {
                if messageStore.spamConversations.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: emptyStateIcon)
                            .font(.system(size: 40))
                            .foregroundColor(.secondary)
                        Text(emptyStateMessage)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(Array(messageStore.spamConversations.enumerated()), id: \.element.id) { index, spamConversation in
                                SpamConversationRow(
                                    conversation: spamConversation,
                                    isSelected: messageStore.selectedSpamAddress == spamConversation.address,
                                    onSelect: {
                                        messageStore.selectedSpamAddress = spamConversation.address
                                        appState.selectedConversation = nil
                                    },
                                    onMarkNotSpam: {
                                        Task {
                                            await messageStore.markSpamAsNotSpam(address: spamConversation.address)
                                        }
                                    },
                                    onDelete: {
                                        Task {
                                            await messageStore.deleteSpamMessages(for: spamConversation.address)
                                        }
                                    }
                                )

                                if index < messageStore.spamConversations.count - 1 {
                                    Divider()
                                        .padding(.leading, SyncFlowSpacing.dividerInsetStart)
                                }
                            }
                        }
                    }
                }
            } else if filteredConversations.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: emptyStateIcon)
                        .font(.system(size: 40))
                        .foregroundColor(.secondary)
                    Text(emptyStateMessage)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)

                    if messageStore.currentFilter != .all {
                        Button("Show All") {
                            messageStore.currentFilter = .all
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        // Show message results when searching
                        if !searchText.isEmpty && !messageSearchResults.isEmpty {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    SidebarSectionTitle(title: "Messages")
                                    Spacer()
                                    Text("\(messageSearchResults.count) results")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                                .padding(.horizontal, 12)
                                .padding(.top, 8)

                                ForEach(messageSearchResults) { message in
                                    MessageSearchResultRow(
                                        message: message,
                                        searchText: searchText
                                    )
                                    .onTapGesture {
                                        // Find and select the conversation for this message
                                        if let conversation = messageStore.conversations.first(where: { $0.address == message.address }) {
                                            selectedConversation = conversation
                                        }
                                    }
                                }

                                Divider()
                                    .padding(.vertical, 8)

                                SidebarSectionHeader(title: "Conversations")
                            }
                        }

                        if !pinnedConversations.isEmpty {
                            SidebarSectionHeader(title: "Pinned")
                                .padding(.top, searchText.isEmpty ? 6 : 2)

                            ForEach(Array(pinnedConversations.enumerated()), id: \.element.id) { index, conversation in
                                ConversationRow(
                                    conversation: conversation,
                                    isSelected: selectedConversation?.id == conversation.id,
                                    selectionMode: isSelectionMode,
                                    isBulkSelected: selectedConversationIds.contains(conversation.id),
                                    onToggleSelect: {
                                        if selectedConversationIds.contains(conversation.id) {
                                            selectedConversationIds.remove(conversation.id)
                                        } else {
                                            selectedConversationIds.insert(conversation.id)
                                        }
                                    }
                                )
                                .onTapGesture {
                                    if isSelectionMode {
                                        if selectedConversationIds.contains(conversation.id) {
                                            selectedConversationIds.remove(conversation.id)
                                        } else {
                                            selectedConversationIds.insert(conversation.id)
                                        }
                                    } else {
                                        selectedConversation = conversation
                                        messageStore.markConversationAsRead(conversation)
                                    }
                                }
                                .contextMenu {
                                    ConversationContextMenu(
                                        conversation: conversation,
                                        messageStore: messageStore
                                    )
                                }

                                if index < pinnedConversations.count - 1 {
                                    ConversationSeparator()
                                }
                            }
                        }

                        if !regularConversations.isEmpty {
                            SidebarSectionHeader(title: "Conversations")
                                .padding(.top, pinnedConversations.isEmpty ? 6 : 4)

                            ForEach(Array(regularConversations.enumerated()), id: \.element.id) { index, conversation in
                                ConversationRow(
                                    conversation: conversation,
                                    isSelected: selectedConversation?.id == conversation.id,
                                    selectionMode: isSelectionMode,
                                    isBulkSelected: selectedConversationIds.contains(conversation.id),
                                    onToggleSelect: {
                                        if selectedConversationIds.contains(conversation.id) {
                                            selectedConversationIds.remove(conversation.id)
                                        } else {
                                            selectedConversationIds.insert(conversation.id)
                                        }
                                    }
                                )
                                .onTapGesture {
                                    if isSelectionMode {
                                        if selectedConversationIds.contains(conversation.id) {
                                            selectedConversationIds.remove(conversation.id)
                                        } else {
                                            selectedConversationIds.insert(conversation.id)
                                        }
                                    } else {
                                        selectedConversation = conversation
                                        messageStore.markConversationAsRead(conversation)
                                    }
                                }
                                .contextMenu {
                                    ConversationContextMenu(
                                        conversation: conversation,
                                        messageStore: messageStore
                                    )
                                }

                                if index < regularConversations.count - 1 {
                                    ConversationSeparator()
                                }
                            }
                        }

                        // Load More button for pagination
                        if messageStore.canLoadMore {
                            VStack(spacing: 8) {
                                Divider()
                                    .padding(.vertical, 8)

                                if messageStore.isLoadingMore {
                                    HStack {
                                        ProgressView()
                                            .scaleEffect(0.8)
                                        Text("Loading older messages...")
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                    }
                                    .padding()
                                } else {
                                    Button(action: {
                                        messageStore.loadMoreMessages()
                                    }) {
                                        HStack {
                                            Image(systemName: "arrow.clockwise")
                                            Text("Load More Conversations (30 days)")
                                        }
                                        .font(.callout)
                                        .padding(.vertical, 10)
                                        .padding(.horizontal, 16)
                                        .background(Color.accentColor.opacity(0.1))
                                        .cornerRadius(10)
                                    }
                                    .buttonStyle(.plain)
                                    .padding()
                                }
                            }
                        }
                    }
                    .padding(.bottom, 6)
                }
            }
        }
        .background(SyncFlowColors.sidebarBackground)
        .alert("Delete Selected Conversations?", isPresented: $showBulkDeleteConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                let toDelete = messageStore.conversations.filter { selectedConversationIds.contains($0.id) }
                messageStore.deleteConversations(toDelete)
                selectedConversationIds.removeAll()
                isSelectionMode = false
                if let selected = selectedConversation, toDelete.contains(where: { $0.id == selected.id }) {
                    selectedConversation = nil
                }
            }
        } message: {
            Text("This will permanently delete the selected conversations and sync the deletion to connected devices.")
        }
        .onAppear {
            availableLabels = preferences.getLabels()
        }
    }
}

struct SpamConversationRow: View {
    let conversation: SpamConversation
    let isSelected: Bool
    let onSelect: () -> Void
    let onMarkNotSpam: () -> Void
    let onDelete: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 12) {
                Circle()
                    .fill(Color.red.opacity(0.15))
                    .frame(width: 36, height: 36)
                    .overlay(
                        Text(String(conversation.contactName.prefix(2)).uppercased())
                            .font(.caption)
                            .foregroundColor(.red)
                    )

                VStack(alignment: .leading, spacing: 2) {
                    Text(conversation.contactName)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundColor(.primary)
                        .lineLimit(1)
                    Text(conversation.latestMessage)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }

                Spacer()

                Text("\(conversation.messageCount)")
                    .font(.caption2)
                    .foregroundColor(.red)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(Color.red.opacity(0.12)))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(isSelected ? Color.red.opacity(0.08) : Color.clear)
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(action: onMarkNotSpam) {
                Label("Not Spam", systemImage: "checkmark.shield")
            }

            Divider()

            Button(role: .destructive, action: onDelete) {
                Label("Delete", systemImage: "trash")
            }
        }
    }
}

// MARK: - Message Search Result Row

struct MessageSearchResultRow: View {
    let message: Message
    let searchText: String

    var body: some View {
        HStack(spacing: 10) {
            // Avatar
            Circle()
                .fill(Color.blue.opacity(0.2))
                .frame(width: 32, height: 32)
                .overlay(
                    Image(systemName: "text.bubble")
                        .font(.system(size: 14))
                        .foregroundColor(.blue)
                )

            VStack(alignment: .leading, spacing: 2) {
                // Contact name
                Text(message.contactName ?? message.address)
                    .font(.caption)
                    .fontWeight(.medium)
                    .lineLimit(1)

                // Message preview with highlighted search term
                highlightedText(message.body, searchText: searchText)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .lineLimit(2)
            }

            Spacer()

            // Timestamp
            Text(formatDate(message.date))
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(SyncFlowColors.surfaceSecondary)
        .contentShape(Rectangle())
    }

    private func highlightedText(_ text: String, searchText: String) -> Text {
        guard !searchText.isEmpty else {
            return Text(text)
        }

        let lowercasedText = text.lowercased()
        let lowercasedSearch = searchText.lowercased()

        guard let range = lowercasedText.range(of: lowercasedSearch) else {
            return Text(text)
        }

        let startIndex = text.index(text.startIndex, offsetBy: lowercasedText.distance(from: lowercasedText.startIndex, to: range.lowerBound))
        let endIndex = text.index(startIndex, offsetBy: searchText.count)

        let before = String(text[..<startIndex])
        let match = String(text[startIndex..<endIndex])
        let after = String(text[endIndex...])

        return Text(before) + Text(match).bold().foregroundColor(.blue) + Text(after)
    }

    private func formatDate(_ timestamp: Double) -> String {
        let date = Date(timeIntervalSince1970: timestamp / 1000)
        let formatter = DateFormatter()

        if Calendar.current.isDateInToday(date) {
            formatter.dateFormat = "h:mm a"
        } else if Calendar.current.isDateInYesterday(date) {
            return "Yesterday"
        } else {
            formatter.dateFormat = "MMM d"
        }

        return formatter.string(from: date)
    }
}

// MARK: - Conversation Context Menu

struct ConversationContextMenu: View {
    let conversation: Conversation
    let messageStore: MessageStore

    private let preferences = PreferencesService.shared

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

        // Labels submenu
        Menu {
            let allLabels = preferences.getLabels()
            let assignedIds = Set((preferences.getLabelAssignments()[conversation.address] ?? []))

            ForEach(allLabels) { label in
                Button(action: {
                    preferences.toggleLabel(label.id, for: conversation.address)
                }) {
                    HStack {
                        Image(systemName: label.icon)
                        Text(label.name)
                        if assignedIds.contains(label.id) {
                            Spacer()
                            Image(systemName: "checkmark")
                        }
                    }
                }
            }

            Divider()

            Button(action: {
                messageStore.markConversationAsSpam(conversation)
            }) {
                Label("Spam", systemImage: "shield.lefthalf.filled")
            }
        } label: {
            Label("Labels", systemImage: "tag")
        }

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
    let selectionMode: Bool
    let isBulkSelected: Bool
    let onToggleSelect: () -> Void

    private let preferences = PreferencesService.shared
    private var rowShape: RoundedRectangle {
        RoundedRectangle(cornerRadius: 12, style: .continuous)
    }

    var conversationLabels: [PreferencesService.ConversationLabel] {
        preferences.getLabels(for: conversation.address)
    }

    var body: some View {
        HStack(spacing: 12) {
            if selectionMode {
                Button(action: onToggleSelect) {
                    Image(systemName: isBulkSelected ? "checkmark.circle.fill" : "circle")
                        .foregroundColor(isBulkSelected ? .blue : .secondary)
                }
                .buttonStyle(.plain)
            }
            // Avatar with color
            ZStack(alignment: .topTrailing) {
                Circle()
                    .fill(Color(hex: conversation.avatarColor ?? "#2196F3") ?? .blue)
                    .frame(width: 40, height: 40)
                    .overlay(
                        Text(conversation.initials)
                            .font(.subheadline)
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
                    VStack(alignment: .leading, spacing: 2) {
                        Text(conversation.preview)
                            .font(.subheadline)
                            .foregroundColor(conversation.unreadCount > 0 ? .primary : .secondary)
                            .fontWeight(conversation.unreadCount > 0 ? .medium : .regular)
                            .lineLimit(1)

                        // Labels row
                        if !conversationLabels.isEmpty {
                            HStack(spacing: 4) {
                                ForEach(conversationLabels.prefix(3)) { label in
                                    LabelBadge(label: label)
                                }
                                if conversationLabels.count > 3 {
                                    Text("+\(conversationLabels.count - 3)")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                            }
                        }
                    }

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
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(
            ZStack(alignment: .leading) {
                if isSelected {
                    rowShape
                        .fill(Color.accentColor.opacity(0.12))

                    rowShape
                        .fill(Color.accentColor)
                        .frame(width: 3)
                }
            }
        )
        .clipShape(rowShape)
        .contentShape(Rectangle())
    }
}

struct ConversationSeparator: View {
    var body: some View {
        Rectangle()
            .fill(SyncFlowColors.divider)
            .frame(height: 1)
            .padding(.leading, SyncFlowSpacing.avatarMd + SyncFlowSpacing.listItemContentGap + SyncFlowSpacing.listItemHorizontal)
            .padding(.trailing, SyncFlowSpacing.listItemHorizontal)
    }
}

// MARK: - Sidebar Section Header

struct SidebarSectionHeader: View {
    let title: String

    var body: some View {
        SidebarSectionTitle(title: title)
            .padding(.horizontal, 12)
            .padding(.top, 4)
            .padding(.bottom, 6)
    }
}

struct SidebarSectionTitle: View {
    let title: String

    var body: some View {
        Text(title.uppercased())
            .font(.caption2)
            .fontWeight(.semibold)
            .foregroundColor(.secondary)
            .tracking(0.8)
    }
}

// MARK: - Filter Button

struct FilterButton: View {
    let filter: MessageStore.ConversationFilter
    let isSelected: Bool
    let badgeCount: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: isSelected ? "\(filter.icon).fill" : filter.icon)
                    .font(.system(size: 12))
                Text(filter.rawValue)
                    .font(.caption)

                if badgeCount > 0 && (filter == .unread || filter == .spam) {
                    let badgeColor = filter == .spam ? Color.red : Color.blue
                    Text("\(badgeCount)")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(Capsule().fill(badgeColor))
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(isSelected ? Color.accentColor.opacity(0.15) : Color.clear)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(isSelected ? Color.accentColor : Color.gray.opacity(0.3), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .foregroundColor(isSelected ? .accentColor : .secondary)
    }
}

// MARK: - Continuity Banner

struct ContinuityBannerView: View {
    let state: ContinuityService.ContinuityState
    let onOpen: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "rectangle.on.rectangle")
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(.blue)

            VStack(alignment: .leading, spacing: 2) {
                Text("Continue from \(state.deviceName)")
                    .font(.subheadline)
                    .fontWeight(.semibold)
                Text(state.contactName ?? state.address)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Button("Open", action: onOpen)
                .buttonStyle(.borderedProminent)

            Button("Dismiss", action: onDismiss)
                .buttonStyle(.bordered)
        }
        .padding(10)
        .background(Color.blue.opacity(0.08))
        .cornerRadius(12)
    }
}
