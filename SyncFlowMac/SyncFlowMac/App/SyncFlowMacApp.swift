//
//  SyncFlowMacApp.swift
//  SyncFlowMac
//
//  Main application entry point for SyncFlow macOS app
//

import SwiftUI
import FirebaseCore
import Combine

@main
struct SyncFlowMacApp: App {

    @StateObject private var appState = AppState()

    init() {
        // Configure Firebase
        FirebaseApp.configure()

        // Configure app appearance
        configureAppearance()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(appState)
                .frame(minWidth: 900, minHeight: 600)
        }
        .commands {
            // Add custom menu commands
            CommandGroup(replacing: .newItem) {}

            CommandGroup(after: .appInfo) {
                Button("Check for Updates...") {
                    // TODO: Implement update checking
                }
            }

            // Conversation commands
            CommandGroup(after: .textEditing) {
                Button("New Message") {
                    appState.showNewMessage = true
                }
                .keyboardShortcut("n", modifiers: .command)

                Divider()

                Button("Search Conversations") {
                    appState.focusSearch = true
                }
                .keyboardShortcut("f", modifiers: .command)
            }
        }

        // Settings window
        Settings {
            SettingsView()
                .environmentObject(appState)
        }
    }

    private func configureAppearance() {
        // Configure app-wide appearance here if needed
    }
}

// MARK: - App State

class AppState: ObservableObject {
    @Published var userId: String?
    @Published var isPaired: Bool = false
    @Published var showNewMessage: Bool = false
    @Published var focusSearch: Bool = false
    @Published var selectedConversation: Conversation?

    init() {
        // Check for existing pairing
        if let storedUserId = UserDefaults.standard.string(forKey: "syncflow_user_id") {
            self.userId = storedUserId
            self.isPaired = true
        }
    }

    func setPaired(userId: String) {
        self.userId = userId
        self.isPaired = true
        UserDefaults.standard.set(userId, forKey: "syncflow_user_id")
    }

    func unpair() {
        self.userId = nil
        self.isPaired = false
        UserDefaults.standard.removeObject(forKey: "syncflow_user_id")
    }
}
