//
//  ContentView.swift
//  SyncFlowMac
//
//  Main content view - shows either pairing screen or main interface
//

import SwiftUI

struct ContentView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        Group {
            if appState.isPaired {
                MainView()
            } else {
                PairingView()
            }
        }
    }
}

// MARK: - Main View (Split view with conversations and messages)

struct MainView: View {
    @EnvironmentObject var appState: AppState
    @StateObject private var messageStore = MessageStore()
    @State private var searchText = ""

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
            if let conversation = appState.selectedConversation {
                MessageView(conversation: conversation)
                    .environmentObject(messageStore)
            } else {
                EmptyStateView()
            }
        }
        .navigationTitle("SyncFlow")
        .toolbar {
            ToolbarItem(placement: .navigation) {
                Button(action: { appState.showNewMessage.toggle() }) {
                    Image(systemName: "square.and.pencil")
                }
                .help("New Message")
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
            }
        }
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
