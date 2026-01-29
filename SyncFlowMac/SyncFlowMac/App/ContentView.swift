//
//  ContentView.swift
//  SyncFlowMac
//
//  Main content view - shows either pairing screen or main interface
//

import SwiftUI
import AppKit

struct ContentView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        ZStack {
            Group {
                if appState.isPaired {
                    MainView()
                } else {
                    PairingView()
                }
            }

            // Incoming call overlay
            if let incomingCall = appState.incomingCall {
                Color.black.opacity(0.4)
                    .ignoresSafeArea()

                IncomingCallView(
                    call: incomingCall,
                    onAnswer: {
                        appState.answerCall(incomingCall)
                    },
                    onReject: {
                        appState.rejectCall(incomingCall)
                    }
                )
                .transition(.scale.combined(with: .opacity))
                .zIndex(1000)
            }

            // Call in-progress banner (answered from macOS)
            if let bannerCall = appState.activeCalls.first(where: { $0.id == appState.lastAnsweredCallId && $0.callState == .active }) {
                VStack {
                    CallInProgressBanner(
                        call: bannerCall,
                        onEndCall: {
                            appState.endCall(bannerCall)
                        },
                        onDismiss: {
                            appState.lastAnsweredCallId = nil
                        }
                    )
                    .padding(.horizontal, 16)
                    .padding(.top, 20)

                    Spacer()
                }
                .transition(.move(edge: .top).combined(with: .opacity))
                .zIndex(900)
            }

            // SyncFlow incoming call overlay
            if let incomingSyncFlowCall = appState.incomingSyncFlowCall {
                IncomingSyncFlowCallView(
                    call: incomingSyncFlowCall,
                    onAcceptVideo: {
                        appState.answerSyncFlowCall(incomingSyncFlowCall, withVideo: true)
                    },
                    onAcceptAudio: {
                        appState.answerSyncFlowCall(incomingSyncFlowCall, withVideo: false)
                    },
                    onDecline: {
                        appState.rejectSyncFlowCall(incomingSyncFlowCall)
                    }
                )
                .transition(.opacity)
                .zIndex(1100)
            }

            // SyncFlow active call view
            if appState.showSyncFlowCallView {
                SyncFlowCallView(callManager: appState.syncFlowCallManager)
                    .transition(.opacity)
                    .zIndex(1050)
            }
        }
        .animation(.spring(), value: appState.incomingCall != nil)
        .animation(.spring(), value: appState.lastAnsweredCallId)
        .animation(.easeInOut, value: appState.incomingSyncFlowCall != nil)
        .animation(.easeInOut, value: appState.showSyncFlowCallView)
        .sheet(isPresented: $appState.showPhotoGallery) {
            PhotoGalleryView(photoService: appState.photoSyncService)
                .frame(minWidth: 500, minHeight: 400)
        }
        .sheet(isPresented: $appState.showNotifications) {
            NotificationListView(notificationService: appState.notificationMirrorService)
                .frame(minWidth: 400, minHeight: 350)
        }
        .sheet(isPresented: $appState.showScheduledMessages) {
            ScheduledMessagesView()
                .environmentObject(appState)
        }
    }
}

// MARK: - Main View (Split view with conversations and messages)

struct MainView: View {
    @EnvironmentObject var appState: AppState
    @StateObject private var messageStore = MessageStore()
    @StateObject private var subscriptionService = SubscriptionService.shared
    @StateObject private var usageTracker = UsageTracker.shared
    @ObservedObject private var mediaControlService = MediaControlService.shared
    @State private var searchText = ""
    @State private var showKeyboardShortcuts = false
    @State private var showAIAssistant = false
    @State private var showSupportChat = false

    var body: some View {
        HStack(spacing: 0) {
            SideRail(
                selectedTab: $appState.selectedTab,
                onNewMessage: { appState.showNewMessage = true },
                onAIAssistant: { showAIAssistant = true },
                onSupportChat: { showSupportChat = true }
            )

            Divider()
                .background(SyncFlowColors.divider)

            VStack(spacing: 0) {
                // Usage limit warning banner
                if usageTracker.showLimitWarning, let stats = usageTracker.usageStats {
                    UsageLimitWarningBanner(stats: stats, onRefresh: {
                        usageTracker.refreshUsageStats()
                    })
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                }

                // Subscription status banner (shown for all non-premium users)
                if !subscriptionService.isPremium {
                    SubscriptionStatusBanner()
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                }

                if appState.showMediaBar {
                    MediaControlBar(mediaService: mediaControlService)
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                }

                // Content based on selected tab
                Group {
                    switch appState.selectedTab {
                    case .messages:
                        MessagesTabView(searchText: $searchText)
                            .environmentObject(messageStore)
                    case .contacts:
                        ContactsView()
                    case .callHistory:
                        CallHistoryView()
                    case .deals:
                        DealsView()
                    }
                }

                // Bottom upgrade banner for free/trial users
                if !subscriptionService.isPremium {
                    UpgradeBanner()
                }
            }
        }
        .navigationTitle("SyncFlow")
        .toolbar {
            ToolbarItem(placement: .navigation) {
                if appState.selectedTab == .messages {
                    Button(action: { appState.showNewMessage.toggle() }) {
                        Image(systemName: "square.and.pencil")
                    }
                    .help("New Message")
                }
            }
        }
        .sheet(isPresented: $appState.showNewMessage) {
            NewMessageView()
                .environmentObject(messageStore)
                .frame(width: 500, height: 400)
        }
        .onAppear {
            if let userId = appState.userId {
                messageStore.startListening(userId: userId)
                // Fetch usage stats
                Task {
                    await usageTracker.fetchUsageStats(userId: userId)
                }
            }
        }
        .onChange(of: appState.userId) { newUserId in
            // Start listening when user is paired (userId becomes available)
            if let userId = newUserId {
                print("[ContentView] User paired, starting message listener for userId: \(userId)")
                messageStore.startListening(userId: userId)
                // Fetch usage stats
                Task {
                    await usageTracker.fetchUsageStats(userId: userId)
                }
            }
        }
        // Keyboard shortcuts help overlay
        .overlay(
            Group {
                if showKeyboardShortcuts {
                    Color.black.opacity(0.3)
                        .ignoresSafeArea()
                        .onTapGesture {
                            showKeyboardShortcuts = false
                        }

                    KeyboardShortcutsView(isPresented: $showKeyboardShortcuts)
                        .transition(.scale.combined(with: .opacity))
                }
            }
        )
        .animation(.spring(), value: showKeyboardShortcuts)
        // Keyboard shortcut: Cmd+? to show help
        .background(
            Button("") {
                showKeyboardShortcuts = true
            }
            .keyboardShortcut("/", modifiers: [.command, .shift])
            .hidden()
        )
        // Keyboard shortcut: Cmd+Shift+A for AI Assistant
        .background(
            Button("") {
                showAIAssistant = true
            }
            .keyboardShortcut("a", modifiers: [.command, .shift])
            .hidden()
        )
        // AI Assistant sheet
        .sheet(isPresented: $showAIAssistant) {
            AIAssistantView()
                .environmentObject(messageStore)
                .frame(minWidth: 500, minHeight: 600)
        }
        // Support Chat sheet
        .sheet(isPresented: $showSupportChat) {
            SupportChatView()
                .environmentObject(appState)
        }
    }
}

// MARK: - Side Rail

struct SideRail: View {
    @Binding var selectedTab: AppTab
    let onNewMessage: () -> Void
    var onAIAssistant: (() -> Void)? = nil
    var onSupportChat: (() -> Void)? = nil
    @State private var dealsIconPulse = false

    var body: some View {
        VStack(spacing: 18) {
            Button(action: onNewMessage) {
                Image(systemName: "square.and.pencil")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(SyncFlowColors.primary)
            }
            .buttonStyle(.plain)
            .padding(.top, 14)

            Divider()
                .background(SyncFlowColors.divider)

            ForEach(AppTab.allCases, id: \.self) { tab in
                if tab == .deals {
                    // Prominent Deals button with gradient and animation
                    Button(action: { selectedTab = tab }) {
                        ZStack {
                            // Animated glow effect
                            Circle()
                                .fill(
                                    RadialGradient(
                                        colors: [Color.orange.opacity(0.4), Color.clear],
                                        center: .center,
                                        startRadius: 0,
                                        endRadius: 25
                                    )
                                )
                                .frame(width: 44, height: 44)
                                .scaleEffect(dealsIconPulse ? 1.2 : 1.0)
                                .opacity(dealsIconPulse ? 0.6 : 0.3)

                            // Icon with gradient
                            Image(systemName: selectedTab == tab ? "tag.fill" : "tag.fill")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundStyle(
                                    LinearGradient(
                                        colors: [Color.orange, Color.pink],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                                .frame(width: 36, height: 36)
                                .background(
                                    Circle()
                                        .fill(
                                            selectedTab == tab
                                                ? LinearGradient(colors: [Color.orange.opacity(0.25), Color.pink.opacity(0.2)], startPoint: .topLeading, endPoint: .bottomTrailing)
                                                : LinearGradient(colors: [Color.orange.opacity(0.12), Color.pink.opacity(0.08)], startPoint: .topLeading, endPoint: .bottomTrailing)
                                        )
                                )
                        }
                    }
                    .buttonStyle(.plain)
                    .help("Discover Deals")
                    .onAppear {
                        // Subtle pulse animation
                        withAnimation(.easeInOut(duration: 2).repeatForever(autoreverses: true)) {
                            dealsIconPulse = true
                        }
                    }
                } else {
                    Button(action: { selectedTab = tab }) {
                        Image(systemName: tab.icon)
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(selectedTab == tab ? SyncFlowColors.primary : SyncFlowColors.textSecondary)
                            .frame(width: 36, height: 36)
                            .background(
                                Circle()
                                    .fill(selectedTab == tab ? SyncFlowColors.primary.opacity(0.18) : Color.clear)
                            )
                    }
                    .buttonStyle(.plain)
                }
            }

            Spacer()

            // AI Assistant button
            if let onAIAssistant = onAIAssistant {
                Button(action: onAIAssistant) {
                    Image(systemName: "sparkles")
                        .font(.system(size: 16))
                        .foregroundColor(SyncFlowColors.textSecondary)
                        .frame(width: 36, height: 36)
                        .background(
                            Circle()
                                .fill(Color.clear)
                        )
                }
                .buttonStyle(.plain)
                .help("AI Assistant (Cmd+Shift+A)")
            }

            // Support Chat button
            if let onSupportChat = onSupportChat {
                Button(action: onSupportChat) {
                    Image(systemName: "questionmark.bubble")
                        .font(.system(size: 16))
                        .foregroundColor(SyncFlowColors.textSecondary)
                        .frame(width: 36, height: 36)
                        .background(
                            Circle()
                                .fill(Color.clear)
                        )
                }
                .buttonStyle(.plain)
                .help("AI Support Chat")
                .padding(.bottom, 8)
            }

            SettingsLink {
                Image(systemName: "gearshape")
                    .font(.system(size: 16))
                    .foregroundColor(SyncFlowColors.textSecondary)
            }
            .buttonStyle(.plain)
            .padding(.bottom, 16)
        }
        .frame(width: 52)
        .background(SyncFlowColors.sidebarRailBackground)
    }
}

// MARK: - Messages Tab View

struct MessagesTabView: View {
    @EnvironmentObject var appState: AppState
    @EnvironmentObject var messageStore: MessageStore
    @Binding var searchText: String

    var body: some View {
        NavigationSplitView {
            // Conversations List (Sidebar)
            ConversationListView(
                searchText: $searchText,
                selectedConversation: $appState.selectedConversation
            )
            .environmentObject(messageStore)
            .frame(minWidth: 250)
        } detail: {
            // Message View (Detail)
            if messageStore.currentFilter == .spam {
                SpamDetailView()
                    .environmentObject(messageStore)
            } else if let conversation = appState.selectedConversation {
                MessageView(conversation: conversation)
                    .environmentObject(messageStore)
                    .id(conversation.id) // Force fresh view instance when conversation changes
            } else {
                EmptyStateView()
            }
        }
    }
}

struct SpamDetailView: View {
    @EnvironmentObject var messageStore: MessageStore

    private var selectedAddress: String? {
        messageStore.selectedSpamAddress
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Spam")
                        .font(.title2)
                        .fontWeight(.semibold)
                    if let address = selectedAddress {
                        Text(address)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                }

                Spacer()

                if let address = selectedAddress {
                    Button("Remove Sender") {
                        Task { await messageStore.deleteSpamMessages(for: address) }
                    }
                    .buttonStyle(.bordered)
                }

                Button("Clear All") {
                    Task { await messageStore.clearAllSpam() }
                }
                .buttonStyle(.bordered)
            }
            .padding()
            Divider()

            if let address = selectedAddress {
                let messages = messageStore.spamMessages(for: address)

                if messages.isEmpty {
                    Spacer()
                    Text("No spam messages for this sender")
                        .foregroundColor(.secondary)
                    Spacer()
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 12) {
                            ForEach(messages) { message in
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(message.body)
                                        .font(.body)
                                    Text(message.timestamp, style: .time)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                .padding()
                                .background(Color.red.opacity(0.06))
                                .cornerRadius(10)
                            }
                        }
                        .padding()
                    }
                }
            } else {
                Spacer()
                Text("Select a spam sender to view messages")
                    .foregroundColor(.secondary)
                Spacer()
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

// MARK: - Empty State

struct EmptyStateView: View {
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "message.fill")
                .font(.system(size: 60))
                .foregroundColor(.secondary)

            Text("Select a conversation")
                .font(.title2)
                .foregroundColor(.secondary)

            Text("Choose a conversation from the sidebar to view messages")
                .font(.body)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

// MARK: - Usage Limit Warning Banner

struct UsageLimitWarningBanner: View {
    let stats: UsageStats
    let onRefresh: () -> Void
    @State private var isExpanded = false
    @State private var isRefreshing = false

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.title2)
                    .foregroundColor(.orange)

                VStack(alignment: .leading, spacing: 2) {
                    Text(stats.isMonthlyLimitExceeded ? "Monthly Upload Limit Reached" : "Storage Limit Reached")
                        .font(.headline)

                    Text("MMS and attachments won't sync. Clear data in Settings → Usage or upgrade.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                // Refresh button
                Button(action: {
                    isRefreshing = true
                    onRefresh()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                        isRefreshing = false
                    }
                }) {
                    if isRefreshing {
                        ProgressView()
                            .scaleEffect(0.7)
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
                .buttonStyle(.plain)
                .help("Refresh usage stats")

                Button(action: { isExpanded.toggle() }) {
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                }
                .buttonStyle(.plain)
            }
            .padding(12)

            if isExpanded {
                Divider()

                VStack(alignment: .leading, spacing: 8) {
                    // Monthly usage
                    HStack {
                        Text("Monthly Upload:")
                            .font(.caption)
                        Spacer()
                        Text(stats.formattedMonthlyUsage)
                            .font(.caption)
                            .foregroundColor(stats.isMonthlyLimitExceeded ? .red : .secondary)
                    }

                    ProgressView(value: min(stats.monthlyUsagePercent / 100, 1.0))
                        .tint(stats.isMonthlyLimitExceeded ? .red : .blue)

                    // Storage usage
                    HStack {
                        Text("Storage:")
                            .font(.caption)
                        Spacer()
                        Text(stats.formattedStorageUsage)
                            .font(.caption)
                            .foregroundColor(stats.isStorageLimitExceeded ? .red : .secondary)
                    }

                    ProgressView(value: min(stats.storageUsagePercent / 100, 1.0))
                        .tint(stats.isStorageLimitExceeded ? .red : .blue)

                    HStack {
                        Text("Go to Settings → Usage to clear MMS data and free up storage.")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                        Spacer()
                    }
                    .padding(.top, 4)
                }
                .padding(12)
            }
        }
        .background(Color.orange.opacity(0.15))
        .cornerRadius(8)
    }
}

// MARK: - Upgrade Banner (Bottom of screen for free users)

struct UpgradeBanner: View {
    @StateObject private var subscriptionService = SubscriptionService.shared
    @State private var showPaywall = false

    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: "sparkles")
                .font(.title2)
                .foregroundColor(.blue)

            VStack(alignment: .leading, spacing: 2) {
                Text("Upgrade to SyncFlow Pro")
                    .font(.headline)
                Text("Unlock photo sync, 500MB storage, and remove this banner")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Text("$4.99/mo")
                .font(.subheadline)
                .foregroundColor(.secondary)

            Button("Upgrade") {
                showPaywall = true
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(
            LinearGradient(
                colors: [Color.blue.opacity(0.1), Color.purple.opacity(0.1)],
                startPoint: .leading,
                endPoint: .trailing
            )
        )
        .sheet(isPresented: $showPaywall) {
            PaywallView()
        }
    }
}
