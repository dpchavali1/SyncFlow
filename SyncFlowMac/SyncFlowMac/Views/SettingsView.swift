//
//  SettingsView.swift
//  SyncFlowMac
//
//  Settings and preferences view
//

import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var appState: AppState

    @AppStorage("notifications_enabled") private var notificationsEnabled = true
    @AppStorage("sound_enabled") private var soundEnabled = true
    @AppStorage("show_previews") private var showPreviews = true
    @AppStorage("auto_start") private var autoStart = false

    var body: some View {
        TabView {
            GeneralSettingsView()
                .environmentObject(appState)
                .tabItem {
                    Label("General", systemImage: "gear")
                }

            NotificationSettingsView(
                notificationsEnabled: $notificationsEnabled,
                soundEnabled: $soundEnabled,
                showPreviews: $showPreviews
            )
            .tabItem {
                Label("Notifications", systemImage: "bell")
            }

            AboutView()
                .tabItem {
                    Label("About", systemImage: "info.circle")
                }
        }
        .frame(width: 500, height: 400)
    }
}

// MARK: - General Settings

struct GeneralSettingsView: View {
    @EnvironmentObject var appState: AppState
    @AppStorage("auto_start") private var autoStart = false

    @State private var showingUnpairAlert = false

    var body: some View {
        Form {
            Section {
                if appState.isPaired {
                    LabeledContent("Status") {
                        HStack {
                            Circle()
                                .fill(Color.green)
                                .frame(width: 8, height: 8)
                            Text("Connected")
                                .foregroundColor(.secondary)
                        }
                    }

                    LabeledContent("User ID") {
                        Text(appState.userId ?? "Unknown")
                            .foregroundColor(.secondary)
                            .font(.caption)
                            .textSelection(.enabled)
                    }
                } else {
                    LabeledContent("Status") {
                        Text("Not paired")
                            .foregroundColor(.secondary)
                    }
                }
            } header: {
                Text("Connection")
            }

            Section {
                Toggle("Launch at login", isOn: $autoStart)
                    .onChange(of: autoStart) { newValue in
                        toggleLaunchAtLogin(newValue)
                    }
            } header: {
                Text("Startup")
            }

            Section {
                Button("Unpair Device", role: .destructive) {
                    showingUnpairAlert = true
                }
                .disabled(!appState.isPaired)
            } header: {
                Text("Actions")
            }
        }
        .formStyle(.grouped)
        .alert("Unpair Device?", isPresented: $showingUnpairAlert) {
            Button("Cancel", role: .cancel) {}
            Button("Unpair", role: .destructive) {
                appState.unpair()
            }
        } message: {
            Text("This will disconnect your Mac from the Android phone. You'll need to pair again to continue using SyncFlow.")
        }
    }

    private func toggleLaunchAtLogin(_ enabled: Bool) {
        // TODO: Implement launch at login
        // This requires setting up a helper app or using SMLoginItemSetEnabled
    }
}

// MARK: - Notification Settings

struct NotificationSettingsView: View {
    @Binding var notificationsEnabled: Bool
    @Binding var soundEnabled: Bool
    @Binding var showPreviews: Bool

    var body: some View {
        Form {
            Section {
                Toggle("Enable notifications", isOn: $notificationsEnabled)
                Toggle("Play sound", isOn: $soundEnabled)
                    .disabled(!notificationsEnabled)
                Toggle("Show message previews", isOn: $showPreviews)
                    .disabled(!notificationsEnabled)
            } header: {
                Text("Notifications")
            } footer: {
                Text("Configure how you receive notifications for new messages")
            }

            Section {
                Button("Open System Preferences") {
                    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.notifications") {
                        NSWorkspace.shared.open(url)
                    }
                }
            }
        }
        .formStyle(.grouped)
    }
}

// MARK: - About View

struct AboutView: View {
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "message.and.waveform.fill")
                .font(.system(size: 60))
                .foregroundColor(.blue)

            Text("SyncFlow for macOS")
                .font(.title)
                .fontWeight(.bold)

            Text("Version 1.0.0")
                .foregroundColor(.secondary)

            Divider()
                .padding(.horizontal, 40)

            VStack(spacing: 10) {
                Text("Access your Android SMS messages on your Mac")
                    .multilineTextAlignment(.center)

                Text("Built with SwiftUI and Firebase")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            VStack(spacing: 8) {
                Link("GitHub", destination: URL(string: "https://github.com/dpchavali1/SyncFlow")!)
                Link("Report an Issue", destination: URL(string: "https://github.com/dpchavali1/SyncFlow/issues")!)
            }
            .font(.caption)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
