//
//  SettingsView.swift
//  SyncFlowMac
//
//  Settings and preferences view
//

import SwiftUI
import ServiceManagement

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

            SyncSettingsView()
                .environmentObject(appState)
                .tabItem {
                    Label("Sync", systemImage: "arrow.triangle.2.circlepath")
                }

            NotificationSettingsView(
                notificationsEnabled: $notificationsEnabled,
                soundEnabled: $soundEnabled,
                showPreviews: $showPreviews
            )
            .tabItem {
                Label("Notifications", systemImage: "bell")
            }

            SubscriptionSettingsView()
                .tabItem {
                    Label("Subscription", systemImage: "creditcard")
                }

            UsageSettingsView()
                .environmentObject(appState)
                .tabItem {
                    Label("Usage", systemImage: "chart.bar")
                }

            RecordingsView()
                .tabItem {
                    Label("Recordings", systemImage: "waveform")
                }

            AboutView()
                .tabItem {
                    Label("About", systemImage: "info.circle")
                }
        }
        .frame(width: 550, height: 500)
    }
}

// MARK: - General Settings

struct GeneralSettingsView: View {
    @EnvironmentObject var appState: AppState
    @AppStorage("auto_start") private var autoStart = false

    @State private var showingUnpairAlert = false

    @AppStorage("chat_color_theme") private var chatColorTheme = ChatColorTheme.apple.rawValue
    @AppStorage("chat_use_system_accent") private var chatUseSystemAccent = false
    @AppStorage("chat_bubble_gradient_enabled") private var chatBubbleGradientEnabled = true
    @AppStorage("chat_custom_colors_enabled") private var chatCustomColorsEnabled = false
    @AppStorage("chat_sent_custom_color") private var chatSentCustomColorHex = "#0A84FF"
    @AppStorage("chat_received_custom_color") private var chatReceivedCustomColorHex = "#2C2C2E"
    @AppStorage("chat_received_text_color") private var chatReceivedTextColorHex = "#F8F8F8"
    @AppStorage("conversation_window_color") private var conversationWindowColorHex = "#0F1119"
    @State private var showThemeResetAlert = false
    @AppStorage("preferred_color_scheme") private var preferredColorScheme = "auto"

    var body: some View {
        Form {
            Section {
                if appState.isPaired {
                    LabeledContent("Status") {
                        HStack {
                            Circle()
                                .fill(SyncFlowColors.success)
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
            } footer: {
                Text("Automatically start SyncFlow when you log in to your Mac")
            }
            .onAppear {
                // Sync toggle state with actual system state
                if #available(macOS 13.0, *) {
                    autoStart = SMAppService.mainApp.status == .enabled
                }
            }

            Section {
                Button("Unpair Device", role: .destructive) {
                    showingUnpairAlert = true
                }
                .disabled(!appState.isPaired)
            } header: {
                Text("Actions")
            }

            Section {
                Picker("Chat theme", selection: $chatColorTheme) {
                    ForEach(ChatColorTheme.allCases) { theme in
                        Text(theme.displayName).tag(theme.rawValue)
                    }
                }
                Toggle("Use macOS accent for sent bubbles", isOn: $chatUseSystemAccent)
                Toggle("Enable gradient tint on bubbles", isOn: $chatBubbleGradientEnabled)
            } header: {
                Text("Chat Colors")
            } footer: {
                Text("Customize your message bubble colors and gradients")
            }

            Section {
                Toggle("Enable custom chat colors", isOn: $chatCustomColorsEnabled)

                if chatCustomColorsEnabled {
                    ColorPicker(
                        "Sent bubble color",
                        selection: colorBinding(for: $chatSentCustomColorHex)
                    )
                    ColorPicker(
                        "Received bubble color",
                        selection: colorBinding(for: $chatReceivedCustomColorHex)
                    )
                    ColorPicker(
                        "Received text color",
                        selection: colorBinding(for: $chatReceivedTextColorHex)
                    )
                }
            } header: {
                Text("Custom Colors")
            } footer: {
                Text("Pick your own sent/received bubble colors and text across macOS.")
            }

            Section {
                Picker("Appearance", selection: $preferredColorScheme) {
                    Text("System").tag("auto")
                    Text("Light").tag("light")
                    Text("Dark").tag("dark")
                }
            } header: {
                Text("Appearance")
            } footer: {
                Text("Choose the appearance mode for the app interface.")
            }

            Section {
                HStack {
                    ColorPicker(
                        "Conversation background",
                        selection: colorBinding(for: $conversationWindowColorHex)
                    )
                    if !conversationWindowColorHex.isEmpty {
                        Button(action: {
                            conversationWindowColorHex = ""
                            UserDefaults.standard.removeObject(forKey: "conversation_window_color")
                        }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.secondary)
                        }
                        .buttonStyle(.plain)
                        .help("Clear custom color")
                    }
                }

                Button(action: { showThemeResetAlert = true }) {
                    Label("Reset to Theme Default", systemImage: "arrow.clockwise")
                }
                .foregroundColor(SyncFlowColors.primary)
            } header: {
                Text("Conversation window")
            } footer: {
                Text("Customize the conversation background. The app automatically adapts to light/dark themes. Use 'Reset to Theme Default' to match your current theme.")
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
        .alert("Reset Conversation Background?", isPresented: $showThemeResetAlert) {
            Button("Cancel", role: .cancel) {}
            Button("Reset", role: .destructive) {
                // Clear the stored color to use theme default
                UserDefaults.standard.removeObject(forKey: "conversation_window_color")
                conversationWindowColorHex = "" // Reset to empty to use theme default

                // Force UI update by triggering a state change
                DispatchQueue.main.async {
                    self.conversationWindowColorHex = ""
                }
            }
        } message: {
            Text("This will reset the conversation background to match your current theme (light or dark). Your custom color will be lost.")
        }
    }

    private func toggleLaunchAtLogin(_ enabled: Bool) {
        // Use SMAppService for macOS 13+
        if #available(macOS 13.0, *) {
            do {
                if enabled {
                    try SMAppService.mainApp.register()
                    print("[Settings] Launch at login enabled")
                } else {
                    try SMAppService.mainApp.unregister()
                    print("[Settings] Launch at login disabled")
                }
            } catch {
                print("[Settings] Failed to toggle launch at login: \(error)")
                // Revert the toggle on failure
                DispatchQueue.main.async {
                    self.autoStart = !enabled
                }
            }
        } else {
            // Fallback for older macOS versions - not supported
            print("[Settings] Launch at login requires macOS 13 or later")
            DispatchQueue.main.async {
                self.autoStart = false
            }
        }
    }

    private func colorBinding(for hexStorage: Binding<String>) -> Binding<Color> {
        Binding(
            get: { Color(hex: hexStorage.wrappedValue) },
            set: { newColor in
                if let newHex = newColor.toHex() {
                    hexStorage.wrappedValue = newHex
                }
            }
        )
    }

    /// Check if app is currently set to launch at login
    static func isLaunchAtLoginEnabled() -> Bool {
        if #available(macOS 13.0, *) {
            return SMAppService.mainApp.status == .enabled
        }
        return false
    }
}

// MARK: - Notification Settings

struct NotificationSettingsView: View {
    @Binding var notificationsEnabled: Bool
    @Binding var soundEnabled: Bool
    @Binding var showPreviews: Bool

    @StateObject private var soundService = NotificationSoundService.shared
    @State private var showSoundPicker = false

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
                HStack {
                    Text("Default Sound")
                    Spacer()
                    Button(action: { showSoundPicker = true }) {
                        HStack {
                            Text(currentSoundName)
                                .foregroundColor(.secondary)
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                }
                .disabled(!soundEnabled || !notificationsEnabled)

                Button(action: {
                    soundService.previewSound(id: soundService.defaultSoundId)
                }) {
                    HStack {
                        Image(systemName: "speaker.wave.2")
                        Text("Preview Sound")
                    }
                }
                .disabled(!soundEnabled || !notificationsEnabled)
            } header: {
                Text("Sound")
            } footer: {
                Text("You can also set custom sounds per contact from the conversation view")
            }

            Section {
                if !soundService.contactSounds.isEmpty {
                    ForEach(Array(soundService.contactSounds.keys.sorted()), id: \.self) { address in
                        HStack {
                            Text(address)
                            Spacer()
                            Text(soundService.getSound(for: address).name)
                                .foregroundColor(.secondary)
                            Button(action: {
                                soundService.removeSound(for: address)
                            }) {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundColor(.secondary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                } else {
                    Text("No custom sounds set")
                        .foregroundColor(.secondary)
                }
            } header: {
                Text("Custom Contact Sounds")
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
        .sheet(isPresented: $showSoundPicker) {
            NotificationSoundPicker(
                soundService: soundService,
                contactAddress: nil,
                contactName: nil
            )
        }
    }

    private var currentSoundName: String {
        NotificationSoundService.builtInSounds.first { $0.id == soundService.defaultSoundId }?.name ?? "Default"
    }
}

// MARK: - Subscription Settings

struct SubscriptionSettingsView: View {
    @StateObject private var subscriptionService = SubscriptionService.shared
    @State private var showPaywall = false

    var body: some View {
        Form {
            Section {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Current Plan")
                            .font(.headline)
                        Text(subscriptionService.subscriptionStatus.displayText)
                            .foregroundColor(.secondary)
                    }

                    Spacer()

                    statusBadge
                }
            } header: {
                Text("Status")
            }

            Section {
                switch subscriptionService.subscriptionStatus {
                case .trial(let days):
                    HStack {
                        Image(systemName: "clock.fill")
                            .foregroundColor(.orange)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(days) days remaining in trial")
                                .font(.subheadline)
                            Text("Subscribe to continue using SyncFlow after your trial ends")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }

                    Button("Upgrade to Pro") {
                        showPaywall = true
                    }
                    .buttonStyle(.borderedProminent)

                case .expired:
                    HStack {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.red)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Your trial has expired")
                                .font(.subheadline)
                            Text("Subscribe to continue using SyncFlow")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }

                    Button("Subscribe Now") {
                        showPaywall = true
                    }
                    .buttonStyle(.borderedProminent)

                case .notSubscribed:
                    HStack {
                        Image(systemName: "sparkles")
                            .foregroundColor(.blue)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Start your free 7-day trial")
                                .font(.subheadline)
                            Text("Try all Pro features for free")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }

                    Button("Start Free Trial") {
                        subscriptionService.startTrialIfNeeded()
                    }
                    .buttonStyle(.borderedProminent)

                case .subscribed(let plan, let expires):
                    HStack {
                        Image(systemName: "checkmark.seal.fill")
                            .foregroundColor(.green)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("You're subscribed to \(plan)")
                                .font(.subheadline)
                            if let expires = expires {
                                Text("Renews on \(expires.formatted(date: .abbreviated, time: .omitted))")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }

                case .lifetime:
                    HStack {
                        Image(systemName: "star.fill")
                            .foregroundColor(.yellow)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Lifetime Access")
                                .font(.subheadline)
                            Text("Thank you for your support!")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            } header: {
                Text("Plan Details")
            }

            Section {
                Button("Restore Purchases") {
                    Task {
                        await subscriptionService.restorePurchases()
                    }
                }
                .disabled(subscriptionService.isLoading)

                Button("Manage Subscription") {
                    if let url = URL(string: "macappstores://apps.apple.com/account/subscriptions") {
                        NSWorkspace.shared.open(url)
                    }
                }
            } header: {
                Text("Account")
            } footer: {
                Text("Manage your subscription in the App Store")
            }

            Section {
                VStack(alignment: .leading, spacing: 8) {
                    pricingRow("Monthly", price: "$3.99/month")
                    pricingRow("Yearly", price: "$29.99/year", badge: "Save 37%")
                    pricingRow("Lifetime", price: "$99.99", badge: "One-time")
                }
            } header: {
                Text("Pricing")
            }

            Section {
                VStack(alignment: .leading, spacing: 8) {
                    featureRow("Unlimited SMS & MMS")
                    featureRow("Phone calls from Mac")
                    featureRow("Photo sync (Premium)")
                    featureRow("3GB uploads/month")
                    featureRow("15GB cloud storage")
                    featureRow("End-to-end encryption")
                    featureRow("Priority support")
                }
            } header: {
                Text("Pro Features")
            }
        }
        .formStyle(.grouped)
        .sheet(isPresented: $showPaywall) {
            PaywallView()
        }
    }

    @ViewBuilder
    private var statusBadge: some View {
        switch subscriptionService.subscriptionStatus {
        case .trial:
            Text("TRIAL")
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundColor(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.orange)
                .cornerRadius(4)
        case .subscribed, .lifetime:
            Text("PRO")
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundColor(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.green)
                .cornerRadius(4)
        case .expired:
            Text("EXPIRED")
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundColor(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.red)
                .cornerRadius(4)
        case .notSubscribed:
            Text("FREE")
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundColor(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.gray)
                .cornerRadius(4)
        }
    }

    private func pricingRow(_ name: String, price: String, badge: String? = nil) -> some View {
        HStack {
            Text(name)
            Spacer()
            if let badge = badge {
                Text(badge)
                    .font(.caption2)
                    .foregroundColor(.green)
            }
            Text(price)
                .foregroundColor(.secondary)
        }
        .font(.subheadline)
    }

    private func featureRow(_ feature: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundColor(.green)
                .font(.caption)
            Text(feature)
                .font(.subheadline)
        }
    }
}

// MARK: - Sync Settings View

struct SyncSettingsView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Image(systemName: "iphone.and.arrow.forward")
                            .font(.title)
                            .foregroundColor(.blue)
                        Text("Sync from Android")
                            .font(.headline)
                    }

                    Text("To load older messages, open SyncFlow on your Android phone:")
                        .font(.callout)
                        .foregroundColor(.secondary)

                    VStack(alignment: .leading, spacing: 8) {
                        Label("Open SyncFlow app on your phone", systemImage: "1.circle.fill")
                        Label("Go to Settings", systemImage: "2.circle.fill")
                        Label("Tap \"Sync Message History\"", systemImage: "3.circle.fill")
                        Label("Select the time range and tap Sync", systemImage: "4.circle.fill")
                    }
                    .font(.callout)
                    .padding(.vertical, 8)

                    Text("Messages will automatically appear here once synced.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .padding(.vertical, 8)
            } header: {
                Text("Load Older Messages")
            }

            Section {
                HStack {
                    Text("Default sync range")
                    Spacer()
                    Text("Last 30 days")
                        .foregroundColor(.secondary)
                }

                HStack {
                    Text("Connection status")
                    Spacer()
                    if appState.isPaired {
                        HStack(spacing: 4) {
                            Circle()
                                .fill(Color.green)
                                .frame(width: 8, height: 8)
                            Text("Connected")
                                .foregroundColor(.secondary)
                        }
                    } else {
                        HStack(spacing: 4) {
                            Circle()
                                .fill(Color.red)
                                .frame(width: 8, height: 8)
                            Text("Not paired")
                                .foregroundColor(.secondary)
                        }
                    }
                }
            } header: {
                Text("Status")
            } footer: {
                Text("New messages sync automatically when your Android phone is connected to the internet.")
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
