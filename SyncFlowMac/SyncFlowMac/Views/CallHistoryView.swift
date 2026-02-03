//
//  CallHistoryView.swift
//  SyncFlowMac
//
//  View for displaying call history from Android phone
//

import SwiftUI
import FirebaseDatabase
import Combine

struct CallHistoryView: View {
    @EnvironmentObject var appState: AppState
    @StateObject private var callHistoryStore = CallHistoryStore()
    @State private var searchText = ""
    @State private var selectedFilter: CallHistoryEntry.CallType? = nil

    var filteredCalls: [CallHistoryEntry] {
        var calls = callHistoryStore.calls

        // Apply type filter
        if let filter = selectedFilter {
            calls = calls.filter { $0.callType == filter }
        }

        // Apply search filter
        if !searchText.isEmpty {
            calls = calls.filter { call in
                call.displayName.localizedCaseInsensitiveContains(searchText) ||
                call.phoneNumber.contains(searchText)
            }
        }

        return calls
    }

    var body: some View {
        VStack(spacing: 0) {
            // Filter bar
            HStack(spacing: 12) {
                // Dial button
                Button(action: { appState.showDialer = true }) {
                    Image(systemName: "phone.badge.plus")
                        .font(.title3)
                }
                .buttonStyle(.bordered)
                .help("Make a call")

                // Search bar
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.secondary)
                    TextField("Search calls", text: $searchText)
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

                // Type filters
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        FilterChip(
                            title: "All",
                            isSelected: selectedFilter == nil,
                            count: callHistoryStore.calls.count
                        ) {
                            selectedFilter = nil
                        }

                        ForEach(CallHistoryEntry.CallType.allCases, id: \.self) { type in
                            let count = callHistoryStore.calls.filter { $0.callType == type }.count
                            if count > 0 {
                                FilterChip(
                                    title: type.rawValue,
                                    isSelected: selectedFilter == type,
                                    count: count,
                                    icon: type.icon
                                ) {
                                    selectedFilter = type
                                }
                            }
                        }
                    }
                }
            }
            .padding()

            Divider()

            // Call history list
            if callHistoryStore.isLoading {
                VStack {
                    ProgressView()
                    Text("Loading call history...")
                        .foregroundColor(.secondary)
                        .padding(.top)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if filteredCalls.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: searchText.isEmpty ? "phone.slash" : "magnifyingglass")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)
                    Text(searchText.isEmpty ? "No call history" : "No calls found")
                        .font(.title3)
                        .foregroundColor(.secondary)
                    if searchText.isEmpty {
                        Text("Your call history from Android will appear here")
                            .font(.body)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(filteredCalls) { call in
                            CallHistoryRow(call: call)
                                .environmentObject(appState)
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(nsColor: .windowBackgroundColor))
        .onAppear {
            if let userId = appState.userId {
                callHistoryStore.startListening(userId: userId)
            }
        }
        .onChange(of: appState.userId) { newUserId in
            // Start listener when userId becomes available (e.g., after pairing)
            if let userId = newUserId {
                callHistoryStore.startListening(userId: userId)
            } else {
                callHistoryStore.stopListening()
            }
        }
        .sheet(isPresented: $appState.showDialer) {
            DialerView()
                .environmentObject(appState)
        }
    }
}

// MARK: - Filter Chip

struct FilterChip: View {
    let title: String
    let isSelected: Bool
    let count: Int
    var icon: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let icon = icon {
                    Image(systemName: icon)
                        .font(.caption)
                }
                Text(title)
                    .font(.caption)
                    .fontWeight(isSelected ? .semibold : .regular)
                Text("(\(count))")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(isSelected ? Color.accentColor : Color(nsColor: .controlBackgroundColor))
            .foregroundColor(isSelected ? .white : .primary)
            .cornerRadius(16)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Call History Row

struct CallHistoryRow: View {
    let call: CallHistoryEntry
    @EnvironmentObject var appState: AppState
    @State private var isHovered = false

    var body: some View {
        HStack(spacing: 12) {
            // Call type icon
            Image(systemName: call.callType.icon)
                .font(.system(size: 20))
                .foregroundColor(colorForType(call.callType))
                .frame(width: 32, height: 32)

            // Call info
            VStack(alignment: .leading, spacing: 4) {
                Text(call.displayName)
                    .font(.body)
                    .fontWeight(.medium)

                HStack(spacing: 6) {
                    Text(call.phoneNumber)
                        .font(.caption)
                        .foregroundColor(.secondary)

                    if call.duration > 0 {
                        Text("•")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text(call.formattedDuration)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }

            Spacer()

            // Date
            Text(call.formattedDate)
                .font(.caption)
                .foregroundColor(.secondary)
                .frame(minWidth: 120, alignment: .trailing)

            // Actions (shown on hover)
            if isHovered {
                HStack(spacing: 8) {
                    Button(action: {
                        initiateCall(to: call.phoneNumber, contactName: call.contactName)
                    }) {
                        Image(systemName: "phone.fill")
                            .foregroundColor(.blue)
                    }
                    .buttonStyle(.borderless)
                    .help("Call \(call.displayName)")

                    Button(action: {
                        startMessage(to: call.phoneNumber, contactName: call.contactName)
                    }) {
                        Image(systemName: "message.fill")
                            .foregroundColor(.blue)
                    }
                    .buttonStyle(.borderless)
                    .help("Message \(call.displayName)")
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(isHovered ? Color(nsColor: .controlBackgroundColor) : Color.clear)
        .onHover { hovering in
            isHovered = hovering
        }
    }

    private func colorForType(_ type: CallHistoryEntry.CallType) -> Color {
        switch type.color {
        case "blue": return .blue
        case "green": return .green
        case "red": return .red
        case "orange": return .orange
        case "gray": return .gray
        case "purple": return .purple
        default: return .blue
        }
    }

    private func initiateCall(to phoneNumber: String, contactName: String?) {
        appState.makeCall(to: phoneNumber)
    }

    private func startMessage(to phoneNumber: String, contactName: String?) {
        let conversation = Conversation(
            id: phoneNumber,
            address: phoneNumber,
            contactName: contactName,
            lastMessage: "",
            timestamp: Date(),
            unreadCount: 0,
            allAddresses: [phoneNumber],
            isPinned: false,
            isArchived: false,
            isBlocked: false,
            avatarColor: nil
        )

        appState.selectedConversation = conversation
        appState.selectedTab = .messages
    }
}

// MARK: - Call History Store

class CallHistoryStore: ObservableObject {
    @Published var calls: [CallHistoryEntry] = []
    @Published var isLoading = true

    // BANDWIDTH OPTIMIZATION: Use optimized child observers for delta-only sync
    private var listenerHandles: (added: DatabaseHandle, changed: DatabaseHandle, removed: DatabaseHandle)?
    private var currentUserId: String?

    func startListening(userId: String) {
        currentUserId = userId
        isLoading = true

        // Remove existing listener if any
        stopListening()

        // BANDWIDTH OPTIMIZATION: Use child observers for delta-only sync (~95% bandwidth reduction)
        // Instead of fetching all 500 calls on every change, only receives individual changes
        listenerHandles = FirebaseService.shared.listenToCallHistoryOptimized(
            userId: userId,
            limit: 200,  // Reduced from 500 - most users don't need that many
            onAdded: { [weak self] call in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    // Add new call, avoiding duplicates
                    if !self.calls.contains(where: { $0.id == call.id }) {
                        self.calls.append(call)
                        // Sort by date (newest first)
                        self.calls.sort { $0.callDate > $1.callDate }
                        // Trim to 200 entries to prevent memory issues
                        if self.calls.count > 200 {
                            self.calls = Array(self.calls.prefix(200))
                        }
                    }
                    self.isLoading = false
                }
            },
            onChanged: { [weak self] call in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    // Update existing call
                    if let index = self.calls.firstIndex(where: { $0.id == call.id }) {
                        self.calls[index] = call
                    }
                }
            },
            onRemoved: { [weak self] callId in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    // Remove deleted call
                    self.calls.removeAll { $0.id == callId }
                }
            }
        )
    }

    func stopListening() {
        if let handles = listenerHandles, let userId = currentUserId {
            FirebaseService.shared.removeCallHistoryOptimizedListeners(userId: userId, handles: handles)
        }
        listenerHandles = nil
    }

    deinit {
        stopListening()
    }
}
