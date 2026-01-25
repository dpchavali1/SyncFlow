//
//  SyncFlowMacApp.swift
//  SyncFlowMac
//
//  Main application entry point for SyncFlow macOS app
//

import SwiftUI
import FirebaseCore
import FirebaseDatabase
import FirebaseAuth
import Combine

extension ColorScheme {
    init?(rawValue: String) {
        switch rawValue {
        case "light": self = .light
        case "dark": self = .dark
        default: return nil
        }
    }
}

@main
struct SyncFlowMacApp: App {

    @StateObject private var appState = AppState()

    init() {
        // Configure Firebase
        FirebaseApp.configure()

        // Initialize Firebase authentication (sign in anonymously if needed)
        initializeFirebaseAuth()

        // Configure app appearance
        configureAppearance()

        // Initialize performance optimizations
        setupPerformanceOptimizations()
    }

    private func initializeFirebaseAuth() {
        let auth = Auth.auth()

        // If no current user, sign in anonymously
        if auth.currentUser == nil {
            Task {
                do {
                    let result = try await auth.signInAnonymously()
                    print("[App] Firebase anonymous authentication successful: \(result.user.uid)")
                } catch {
                    print("[App] Firebase anonymous authentication failed: \(error)")
                }
            }
        }
    }

    private func setupPerformanceOptimizations() {
        // Start battery-aware service management
        BatteryAwareServiceManager.shared.startBatteryMonitoring()

        // Start memory optimization
        _ = MemoryOptimizer.shared

        // Add state change handlers for performance optimization with weak reference to prevent retain cycle
        BatteryAwareServiceManager.shared.addStateChangeHandler { [weak appState] state in
            guard let appState = appState else { return }

            switch state {
            case .full:
                // Resume full performance - no action needed
                break
            case .reduced:
                // Reduce background activity
                appState.reduceBackgroundActivity()
            case .minimal:
                // Minimize activity
                appState.minimizeBackgroundActivity()
            case .suspended:
                // Suspend non-essential features
                appState.suspendNonEssentialFeatures()
            }
        }
    }

    private func handleBatteryStateChange(_ state: BatteryAwareServiceManager.ServiceState) {
        print("[App] Battery state changed to: \(state)")

        switch state {
        case .full:
            // Resume full performance
            break
        case .reduced:
            // Reduce background activity
            appState.reduceBackgroundActivity()
        case .minimal:
            // Minimize activity
            appState.minimizeBackgroundActivity()
        case .suspended:
            // Suspend non-essential features
            appState.suspendNonEssentialFeatures()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(appState)
                .frame(minWidth: 900, minHeight: 600)
                .preferredColorScheme(colorScheme)
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

                Button("Message Templates") {
                    appState.showTemplates = true
                }
                .keyboardShortcut("t", modifiers: .command)

                Divider()

                Button("Search Conversations") {
                    appState.focusSearch = true
                }
                .keyboardShortcut("f", modifiers: .command)
            }

            // Call commands
            CommandMenu("Calls") {
                Button("Open Dialer") {
                    appState.selectedTab = .callHistory
                    appState.showDialer = true
                }
                .keyboardShortcut("d", modifiers: [.command, .shift])

                Divider()

                Button("Answer Call") {
                    if let incomingCall = appState.incomingCall {
                        appState.answerCall(incomingCall)
                    }
                }
                .keyboardShortcut(.return, modifiers: .command)
                .disabled(appState.incomingCall == nil)

                Button("Reject Call") {
                    if let incomingCall = appState.incomingCall {
                        appState.rejectCall(incomingCall)
                    }
                }
                .keyboardShortcut(.escape, modifiers: .command)
                .disabled(appState.incomingCall == nil)

                Button("End Call") {
                    if let activeCall = appState.activeCalls.first(where: { $0.callState == .active }) {
                        appState.endCall(activeCall)
                    }
                }
                .keyboardShortcut("e", modifiers: [.command, .shift])
                .disabled(appState.activeCalls.isEmpty)

                Divider()

                Button("Messages") {
                    appState.selectedTab = .messages
                }
                .keyboardShortcut("1", modifiers: .command)

                Button("Contacts") {
                    appState.selectedTab = .contacts
                }
                .keyboardShortcut("2", modifiers: .command)

                Button("Call History") {
                    appState.selectedTab = .callHistory
                }
                .keyboardShortcut("3", modifiers: .command)
            }

            // Phone features menu
            CommandMenu("Phone") {
                Button("View Photos") {
                    appState.showPhotoGallery = true
                }
                .keyboardShortcut("p", modifiers: [.command, .shift])

                Button("View Notifications") {
                    appState.showNotifications = true
                }
                .keyboardShortcut("n", modifiers: [.command, .shift])

                Button("Scheduled Messages (\(appState.scheduledMessageService.pendingCount))") {
                    appState.showScheduledMessages = true
                }
                .keyboardShortcut("s", modifiers: [.command, .shift])

                Button("Voicemails (\(appState.voicemailSyncService.unreadCount))") {
                    appState.showVoicemails = true
                }
                .keyboardShortcut("v", modifiers: [.command, .shift])

                Divider()

                if appState.isPhoneRinging {
                    Button("Stop Ringing Phone") {
                        appState.stopFindingPhone()
                    }
                } else {
                    Button("Find My Phone") {
                        appState.findMyPhone()
                    }
                    .keyboardShortcut("l", modifiers: [.command, .shift])
                }

                Divider()

                Toggle("Clipboard Sync", isOn: Binding(
                    get: { appState.clipboardSyncEnabled },
                    set: { appState.toggleClipboardSync(enabled: $0) }
                ))

                Toggle("Notification Mirroring", isOn: Binding(
                    get: { appState.notificationMirrorService.isEnabled },
                    set: { appState.notificationMirrorService.setEnabled($0) }
                ))

                Divider()

                // Hotspot control
                if appState.hotspotControlService.isHotspotEnabled {
                    Button("Disable Hotspot") {
                        appState.hotspotControlService.toggleHotspot()
                    }
                } else {
                    Button("Enable Hotspot") {
                        appState.hotspotControlService.toggleHotspot()
                    }
                }

                // DND control
                if appState.dndSyncService.isPhoneDndEnabled {
                    Button("Disable Phone DND") {
                        appState.dndSyncService.togglePhoneDnd()
                    }
                } else {
                    Button("Enable Phone DND") {
                        appState.dndSyncService.togglePhoneDnd()
                    }
                }

                Divider()

                // Media control submenu
                Menu("Media Control") {
                    if let trackInfo = appState.mediaControlService.trackInfo {
                        Text(trackInfo)
                            .foregroundColor(.secondary)
                        Divider()
                    }

                    Button(appState.mediaControlService.isPlaying ? "Pause" : "Play") {
                        appState.mediaControlService.playPause()
                    }
                    .keyboardShortcut(.space, modifiers: [.command, .shift])

                    Button("Previous Track") {
                        appState.mediaControlService.previous()
                    }
                    .keyboardShortcut(.leftArrow, modifiers: [.command, .shift])

                    Button("Next Track") {
                        appState.mediaControlService.next()
                    }
                    .keyboardShortcut(.rightArrow, modifiers: [.command, .shift])

                    Divider()

                    Button("Volume Up") {
                        appState.mediaControlService.volumeUp()
                    }
                    .keyboardShortcut(.upArrow, modifiers: [.command, .shift])

                    Button("Volume Down") {
                        appState.mediaControlService.volumeDown()
                    }
                    .keyboardShortcut(.downArrow, modifiers: [.command, .shift])
                }
            }

            CommandGroup(after: .sidebar) {
                Toggle("Show Media Bar", isOn: Binding(
                    get: { appState.showMediaBar },
                    set: { appState.showMediaBar = $0 }
                ))
                .keyboardShortcut("m", modifiers: [.command, .shift])
            }
        }

        // Settings window
        Settings {
            SettingsView()
                .environmentObject(appState)
                .preferredColorScheme(colorScheme)
        }

        // Menu bar extra
        MenuBarExtra {
            MenuBarView()
                .environmentObject(appState)
        } label: {
            Label {
                Text("SyncFlow")
            } icon: {
                if appState.unreadCount > 0 {
                    Image(systemName: "message.badge.fill")
                } else {
                    Image(systemName: "message.fill")
                }
            }
        }
        .menuBarExtraStyle(.menu)
    }

    private func configureAppearance() {
        // Configure app-wide appearance here if needed
    }

    private var colorScheme: ColorScheme? {
        let preferred = UserDefaults.standard.string(forKey: "preferred_color_scheme") ?? "auto"
        return preferred == "auto" ? nil : ColorScheme(rawValue: preferred)
    }
}

// MARK: - App State

enum AppTab: String, CaseIterable {
    case messages = "Messages"
    case contacts = "Contacts"
    case callHistory = "Calls"

    var icon: String {
        switch self {
        case .messages:
            return "message.fill"
        case .contacts:
            return "person.2.fill"
        case .callHistory:
            return "phone.fill"
        }
    }
}

class AppState: ObservableObject {
    @Published var userId: String?
    @Published var isPaired: Bool = false
    @Published var showMediaBar: Bool = UserDefaults.standard.object(forKey: "syncflow_show_media_bar") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(showMediaBar, forKey: "syncflow_show_media_bar")
        }
    }
    @Published var showNewMessage: Bool = false
    @Published var showDialer: Bool = false
    @Published var showTemplates: Bool = false
    @Published var focusSearch: Bool = false
    @Published var selectedConversation: Conversation?
    @Published var selectedTab: AppTab = .messages
    @Published var activeCalls: [ActiveCall] = []
    @Published var audioRoutingState: CallAudioRoutingManager.State = .idle
    @Published var audioRoutingCallId: String? = nil
    @Published var continuitySuggestion: ContinuityService.ContinuityState?

    // SyncFlow calling
    @Published var incomingSyncFlowCall: SyncFlowCall?
    @Published var activeSyncFlowCall: SyncFlowCall?
    @Published var showSyncFlowCallView: Bool = false
    let syncFlowCallManager = SyncFlowCallManager()
    @Published var incomingCall: ActiveCall? = nil {
        didSet {
            handleIncomingCallChange(oldValue: oldValue, newValue: incomingCall)
        }
    }
    @Published var lastAnsweredCallId: String? = nil
    @Published var unreadCount: Int = 0
    @Published var recentConversations: [Conversation] = []

    // Phone status
    @Published var phoneStatus = PhoneStatus()
    let phoneStatusService = PhoneStatusService.shared

    // Clipboard sync
    @Published var clipboardSyncEnabled: Bool = true
    let clipboardSyncService = ClipboardSyncService.shared

    // File transfer (Quick Drop)
    let fileTransferService = FileTransferService.shared
    let continuityService = ContinuityService.shared

    // Find My Phone
    @Published var isPhoneRinging: Bool = false

    // Photo Sync
    let photoSyncService = PhotoSyncService.shared
    @Published var showPhotoGallery: Bool = false

    // Notification Mirroring
    let notificationMirrorService = NotificationMirrorService.shared
    @Published var showNotifications: Bool = false

    // Hotspot Control
    let hotspotControlService = HotspotControlService.shared

    // DND Sync
    let dndSyncService = DNDSyncService.shared

    // Media Control
    let mediaControlService = MediaControlService.shared

    // Scheduled Messages
    let scheduledMessageService = ScheduledMessageService.shared
    @Published var showScheduledMessages: Bool = false

    // Voicemail Sync
    let voicemailSyncService = VoicemailSyncService.shared
    @Published var showVoicemails: Bool = false

    private var activeCallsListenerHandle: DatabaseHandle?
    private var phoneStatusCancellable: AnyCancellable?
    private var messagesListenerHandle: DatabaseHandle?
    private var cancellables = Set<AnyCancellable>()
    private var deviceStatusHandle: DatabaseHandle?
    private var deviceStatusRef: DatabaseReference?

    // Ringtone manager
    let ringtoneManager = CallRingtoneManager.shared
    private let audioRoutingManager = CallAudioRoutingManager()
    private var pendingCallNotificationId: String?

    deinit {
        // Ensure proper cleanup when AppState is deallocated
        unpair()
    }

    init() {
        // Check for existing pairing
        if let storedUserId = UserDefaults.standard.string(forKey: "syncflow_user_id") {
            self.userId = storedUserId
            self.isPaired = true
            fileTransferService.configure(userId: storedUserId)
            continuityService.configure(userId: storedUserId)
            continuityService.startListening()
            startListeningForCalls(userId: storedUserId)
            startListeningForIncomingUserCalls(userId: storedUserId)
            updateDeviceOnlineStatus(userId: storedUserId, online: true)
            startListeningForPhoneStatus(userId: storedUserId)
            startClipboardSync(userId: storedUserId)
            startPhotoSync(userId: storedUserId)
            startNotificationMirroring(userId: storedUserId)
            startHotspotControl(userId: storedUserId)
            startDndSync(userId: storedUserId)
            startMediaControl(userId: storedUserId)
            startScheduledMessages(userId: storedUserId)
            startVoicemailSync(userId: storedUserId)
        }

        // Observe call state changes to auto-dismiss call view
        syncFlowCallManager.$callState
            .receive(on: DispatchQueue.main)
            .sink { [weak self] state in
                switch state {
                case .ringing:
                    // Outgoing ringback (avoid double-ring on incoming)
                    if self?.incomingSyncFlowCall == nil,
                       self?.syncFlowCallManager.currentCall?.isOutgoing == true {
                        self?.ringtoneManager.startRinging()
                    }
                case .connecting, .connected:
                    self?.ringtoneManager.stopRinging()
                    self?.incomingSyncFlowCall = nil
                    if let callId = self?.pendingCallNotificationId {
                        NotificationService.shared.clearCallNotification(callId: callId)
                        self?.pendingCallNotificationId = nil
                    }
                case .ended, .failed:
                    self?.showSyncFlowCallView = false
                    self?.activeSyncFlowCall = nil
                    if let callId = self?.pendingCallNotificationId {
                        NotificationService.shared.clearCallNotification(callId: callId)
                        self?.pendingCallNotificationId = nil
                    }
                case .idle:
                    // Also hide on idle (cleanup completed)
                    if self?.showSyncFlowCallView == true {
                        self?.showSyncFlowCallView = false
                        self?.activeSyncFlowCall = nil
                    }
                default:
                    break
                }
            }
            .store(in: &cancellables)

        audioRoutingManager.$state
            .receive(on: DispatchQueue.main)
            .sink { [weak self] state in
                self?.audioRoutingState = state
                if case .idle = state {
                    self?.audioRoutingCallId = nil
                }
            }
            .store(in: &cancellables)

        continuityService.$latestState
            .receive(on: DispatchQueue.main)
            .sink { [weak self] state in
                self?.continuitySuggestion = state
            }
            .store(in: &cancellables)
    }

    func setPaired(userId: String) {
        self.userId = userId
        self.isPaired = true
        UserDefaults.standard.set(userId, forKey: "syncflow_user_id")

        // Update subscription status with the newly paired user's plan from Firebase
        print("AppState.setPaired: Setting user \(userId), updating subscription status")
        Task {
            print("AppState.setPaired: Calling updateSubscriptionStatus...")
            await SubscriptionService.shared.updateSubscriptionStatus()
            print("AppState.setPaired: updateSubscriptionStatus completed")
            print("AppState.setPaired: Current subscriptionStatus = \(SubscriptionService.shared.subscriptionStatus.displayText)")
            print("AppState.setPaired: isPremium = \(SubscriptionService.shared.isPremium)")
        }

        fileTransferService.configure(userId: userId)
        continuityService.configure(userId: userId)
        continuityService.startListening()
        startListeningForCalls(userId: userId)
        startListeningForSyncFlowCalls(userId: userId)
        startListeningForIncomingUserCalls(userId: userId)
        updateDeviceOnlineStatus(userId: userId, online: true)
        startListeningForPhoneStatus(userId: userId)
        startClipboardSync(userId: userId)
        startPhotoSync(userId: userId)
        startNotificationMirroring(userId: userId)
        startHotspotControl(userId: userId)
        startDndSync(userId: userId)
        startMediaControl(userId: userId)
        startScheduledMessages(userId: userId)
        startVoicemailSync(userId: userId)
        startDeviceStatusListener(userId: userId)
    }

    func unpair() {
        guard isPaired || userId != nil else { return }

        // Get device info before clearing
        let currentUserId = userId
        let deviceId = UserDefaults.standard.string(forKey: "syncflow_device_id")

        stopListeningForCalls()
        stopAudioRouting()
        stopListeningForPhoneStatus()
        stopClipboardSync()
        stopPhotoSync()
        stopNotificationMirroring()
        stopHotspotControl()
        stopDndSync()
        stopMediaControl()
        stopScheduledMessages()
        stopVoicemailSync()
        stopDeviceStatusListener()

        // Cancel all Combine subscriptions to prevent memory leaks
        cancellables.forEach { $0.cancel() }
        cancellables.removeAll()

        fileTransferService.configure(userId: nil)
        continuityService.stopListening()
        continuityService.configure(userId: nil)
        continuitySuggestion = nil
        self.userId = nil
        self.isPaired = false
        self.activeCalls = []
        self.incomingCall = nil
        self.phoneStatus = PhoneStatus()

        // Unregister device from Firebase if we have the required info
        print("Unpairing: userId=\(currentUserId ?? "nil"), deviceId=\(deviceId ?? "nil")")
        if let userId = currentUserId, let deviceId = deviceId {
            print("Calling Firebase unregisterDevice with deviceId: \(deviceId)")
            FirebaseService.shared.unregisterDevice(deviceId: deviceId) { error in
                if let error = error {
                    print("Failed to unregister device from Firebase: \(error)")
                } else {
                    print("Successfully unregistered device from Firebase")
                }
            }
        } else {
            print("Cannot unregister device: missing userId or deviceId")
        }

        UserDefaults.standard.removeObject(forKey: "syncflow_user_id")
        UserDefaults.standard.removeObject(forKey: "syncflow_device_id")
    }

    func dismissContinuitySuggestion() {
        continuitySuggestion = nil
    }

    private func startListeningForCalls(userId: String) {
        activeCallsListenerHandle = FirebaseService.shared.listenToActiveCalls(userId: userId) { [weak self] calls in
            DispatchQueue.main.async {
                self?.activeCalls = calls

                // Find first ringing call for incoming notification
                self?.incomingCall = calls.first { $0.callState == .ringing }

                // Clear banner reference when call is gone or ended
                if let bannerId = self?.lastAnsweredCallId {
                    let stillActive = calls.contains { $0.id == bannerId && $0.callState != .ended }
                    if !stillActive {
                        self?.lastAnsweredCallId = nil
                    }
                }

                if let routingCallId = self?.audioRoutingCallId {
                    let stillActive = calls.contains { $0.id == routingCallId && $0.callState == .active }
                    if !stillActive {
                        self?.stopAudioRouting()
                    }
                }
            }
        }
    }

    private func startDeviceStatusListener(userId: String) {
        stopDeviceStatusListener()
        let (ref, handle) = FirebaseService.shared.watchCurrentDeviceStatus(userId: userId) { [weak self] isPaired in
            guard let self = self else { return }
            if !isPaired {
                self.handleRemoteUnpair()
            }
        }
        deviceStatusRef = ref
        deviceStatusHandle = handle
    }

    private func stopDeviceStatusListener() {
        if let ref = deviceStatusRef, let handle = deviceStatusHandle {
            ref.removeObserver(withHandle: handle)
        }
        deviceStatusRef = nil
        deviceStatusHandle = nil
    }

    private func handleRemoteUnpair() {
        DispatchQueue.main.async {
            self.unpair()
        }
    }

    private func handleIncomingCallChange(oldValue: ActiveCall?, newValue: ActiveCall?) {
        if newValue != nil && oldValue == nil {
            // New incoming call - start ringing
            print("AppState: Incoming call detected, starting ringtone")
            ringtoneManager.startRinging()
        } else if newValue == nil && oldValue != nil {
            // Incoming call ended/answered - stop ringing
            print("AppState: Incoming call ended, stopping ringtone")
            ringtoneManager.stopRinging()
        }
    }

    private func stopListeningForCalls() {
        if let handle = activeCallsListenerHandle, let userId = userId {
            FirebaseService.shared.removeActiveCallsListener(userId: userId, handle: handle)
            activeCallsListenerHandle = nil
        }
    }

    // MARK: - Phone Status

    private func startListeningForPhoneStatus(userId: String) {
        phoneStatusService.startListening(userId: userId)

        // Subscribe to phone status updates
        phoneStatusCancellable = phoneStatusService.$phoneStatus
            .receive(on: DispatchQueue.main)
            .sink { [weak self] status in
                self?.phoneStatus = status
            }
    }

    private func stopListeningForPhoneStatus() {
        phoneStatusCancellable?.cancel()
        phoneStatusCancellable = nil
        phoneStatusService.stopListening()
    }

    func refreshPhoneStatus() {
        phoneStatusService.requestRefresh()
    }

    // MARK: - Clipboard Sync

    private func startClipboardSync(userId: String) {
        if clipboardSyncEnabled {
            clipboardSyncService.startSync(userId: userId)
        }
    }

    private func stopClipboardSync() {
        clipboardSyncService.stopSync()
    }

    func toggleClipboardSync(enabled: Bool) {
        clipboardSyncEnabled = enabled
        if enabled, let userId = userId {
            clipboardSyncService.startSync(userId: userId)
        } else {
            clipboardSyncService.stopSync()
        }
    }

    // MARK: - Photo Sync

    private func startPhotoSync(userId: String) {
        photoSyncService.startSync(userId: userId)
    }

    private func stopPhotoSync() {
        photoSyncService.stopSync()
    }

    // MARK: - Notification Mirroring

    private func startNotificationMirroring(userId: String) {
        notificationMirrorService.startSync(userId: userId)
    }

    private func stopNotificationMirroring() {
        notificationMirrorService.stopSync()
    }

    // MARK: - Hotspot Control

    private func startHotspotControl(userId: String) {
        hotspotControlService.startListening(userId: userId)
    }

    private func stopHotspotControl() {
        hotspotControlService.stopListening()
    }

    // MARK: - DND Sync

    private func startDndSync(userId: String) {
        dndSyncService.startListening(userId: userId)
    }

    private func stopDndSync() {
        dndSyncService.stopListening()
    }

    // MARK: - Media Control

    private func startMediaControl(userId: String) {
        mediaControlService.startListening(userId: userId)
    }

    private func stopMediaControl() {
        mediaControlService.stopListening()
    }

    // MARK: - Scheduled Messages

    private func startScheduledMessages(userId: String) {
        scheduledMessageService.startListening(userId: userId)
    }

    private func stopScheduledMessages() {
        scheduledMessageService.stopListening()
    }

    // MARK: - Voicemail Sync

    private func startVoicemailSync(userId: String) {
        voicemailSyncService.startListening(userId: userId)
    }

    private func stopVoicemailSync() {
        voicemailSyncService.stopListening()
    }

    // MARK: - Find My Phone

    func findMyPhone() {
        guard let userId = userId else { return }

        Task {
            do {
                try await FirebaseService.shared.ringPhone(userId: userId)
                await MainActor.run {
                    isPhoneRinging = true
                }
                print("Find My Phone: Ring request sent")
            } catch {
                print("Error finding phone: \(error)")
            }
        }
    }

    func stopFindingPhone() {
        guard let userId = userId else { return }

        Task {
            do {
                try await FirebaseService.shared.stopRingingPhone(userId: userId)
                await MainActor.run {
                    isPhoneRinging = false
                }
                print("Find My Phone: Stop request sent")
            } catch {
                print("Error stopping find phone: \(error)")
            }
        }
    }

    // MARK: - Link Sharing

    func sendLinkToPhone(url: String, title: String? = nil) {
        guard let userId = userId else { return }

        Task {
            do {
                try await FirebaseService.shared.sendLink(userId: userId, url: url, title: title)
                print("Link sent to phone: \(url)")
            } catch {
                print("Error sending link: \(error)")
            }
        }
    }

    func answerCall(_ call: ActiveCall) {
        guard let userId = userId else { return }

        // Stop ringtone immediately when answering
        ringtoneManager.stopRinging()

        // Track that this call was answered from macOS so we can show in-progress notice
        DispatchQueue.main.async {
            self.lastAnsweredCallId = call.id
        }
        Task {
            do {
                try await FirebaseService.shared.sendCallCommand(
                    userId: userId,
                    callId: call.id,
                    command: "answer"
                )
                print("Answer command sent")
            } catch {
                print("Error sending answer command: \(error)")
            }
        }
    }

    func rejectCall(_ call: ActiveCall) {
        guard let userId = userId else { return }

        // Stop ringtone immediately when rejecting
        ringtoneManager.stopRinging()

        Task {
            do {
                try await FirebaseService.shared.sendCallCommand(
                    userId: userId,
                    callId: call.id,
                    command: "reject"
                )
                print("Reject command sent")
                // Clear incoming call immediately for better UX
                DispatchQueue.main.async {
                    if self.incomingCall?.id == call.id {
                        self.incomingCall = nil
                    }
                }
            } catch {
                print("Error sending reject command: \(error)")
            }
        }
    }

    func endCall(_ call: ActiveCall) {
        guard let userId = userId else { return }
        Task {
            do {
                try await FirebaseService.shared.sendCallCommand(
                    userId: userId,
                    callId: call.id,
                    command: "end"
                )
                print("End call command sent")
                DispatchQueue.main.async {
                    if self.lastAnsweredCallId == call.id {
                        self.lastAnsweredCallId = nil
                    }
                }
            } catch {
                print("Error sending end call command: \(error)")
            }
        }
    }

    func makeCall(to phoneNumber: String) {
        guard let userId = userId else {
            print("Error: No user ID for making call")
            return
        }

        Task {
            do {
                try await FirebaseService.shared.makeCall(userId: userId, phoneNumber: phoneNumber)
                print("Call request sent to: \(phoneNumber)")
            } catch {
                print("Error making call: \(error)")
            }
        }
    }

    // MARK: - Call Audio Transfer

    func startAudioRouting(for call: ActiveCall) {
        guard let userId = userId else { return }
        audioRoutingCallId = call.id
        audioRoutingManager.start(userId: userId, callId: call.id)
        Task {
            try? await FirebaseService.shared.requestAudioRouting(
                userId: userId,
                callId: call.id,
                enable: true
            )
        }
    }

    func stopAudioRouting() {
        guard let userId = userId, let callId = audioRoutingCallId else { return }
        Task {
            try? await FirebaseService.shared.requestAudioRouting(
                userId: userId,
                callId: callId,
                enable: false
            )
        }
        audioRoutingManager.stop()
        audioRoutingCallId = nil
    }

    // MARK: - SyncFlow Calls

    private var syncFlowCallsListenerHandle: DatabaseHandle?

    private func startListeningForSyncFlowCalls(userId: String) {
        syncFlowCallsListenerHandle = FirebaseService.shared.listenForIncomingSyncFlowCalls(userId: userId) { [weak self] call in
            DispatchQueue.main.async {
                // Only show if we are the callee (on macOS)
                if call.calleePlatform == "macos" && call.isRinging {
                    self?.incomingSyncFlowCall = call
                    self?.ringtoneManager.startRinging()
                    self?.pendingCallNotificationId = call.id
                    NotificationService.shared.showIncomingCallNotification(
                        callerName: call.callerName,
                        isVideo: call.callType == .video,
                        callId: call.id
                    )
                }
            }
        }
    }

    /// Listen for incoming user-to-user calls (from incoming_syncflow_calls path)
    private func startListeningForIncomingUserCalls(userId: String) {
        // Use the SyncFlowCallManager to listen for incoming user calls
        syncFlowCallManager.startListeningForIncomingUserCalls(userId: userId)

        // Observe incoming user calls from the call manager
        syncFlowCallManager.$incomingUserCall
            .receive(on: DispatchQueue.main)
            .sink { [weak self] incomingCall in
                if let call = incomingCall {
                    print("AppState: Incoming user call from \(call.callerName)")
                    // Create a SyncFlowCall from the incoming user call data
                    let syncFlowCall = SyncFlowCall(
                        id: call.callId,
                        callerId: call.callerUid,
                        callerName: call.callerName,
                        callerPlatform: call.callerPlatform,
                        calleeId: userId,
                        calleeName: "Me",
                        calleePlatform: "macos",
                        callType: call.isVideo ? .video : .audio,
                        status: .ringing,
                        startedAt: Date(),
                        answeredAt: nil,
                        endedAt: nil,
                        offer: nil,
                        answer: nil
                    )
                    self?.incomingSyncFlowCall = syncFlowCall
                    self?.ringtoneManager.startRinging()
                    self?.pendingCallNotificationId = syncFlowCall.id
                    NotificationService.shared.showIncomingCallNotification(
                        callerName: syncFlowCall.callerName,
                        isVideo: syncFlowCall.callType == .video,
                        callId: syncFlowCall.id
                    )
                } else {
                    // Call was cancelled or answered elsewhere
                    if self?.incomingSyncFlowCall != nil {
                        print("AppState: Incoming user call cancelled")
                        self?.incomingSyncFlowCall = nil
                        self?.ringtoneManager.stopRinging()
                        if let callId = self?.pendingCallNotificationId {
                            NotificationService.shared.clearCallNotification(callId: callId)
                            self?.pendingCallNotificationId = nil
                        }
                    }
                }
            }
            .store(in: &cancellables)
    }

    private func updateDeviceOnlineStatus(userId: String, online: Bool) {
        Task {
            do {
                try await syncFlowCallManager.updateDeviceStatus(userId: userId, online: online)
            } catch {
                print("Error updating device status: \(error)")
            }
        }
    }

    func answerSyncFlowCall(_ call: SyncFlowCall, withVideo: Bool) {
        guard let userId = userId else { return }

        ringtoneManager.stopRinging()
        incomingSyncFlowCall = nil

        Task {
            do {
                // Check if this is a user-to-user call (from incoming_syncflow_calls)
                // by checking if the callerId is a UID (not a device ID)
                if call.callerPlatform == "android" || call.callerPlatform == "macos" {
                    // This is a user-to-user call - pass userId explicitly
                    try await syncFlowCallManager.answerUserCall(callId: call.id, withVideo: withVideo, userId: userId)
                } else {
                    // This is a device-to-device call
                    try await syncFlowCallManager.answerCall(userId: userId, callId: call.id, withVideo: withVideo)
                }
                await MainActor.run {
                    activeSyncFlowCall = call
                    showSyncFlowCallView = true
                }
            } catch {
                print("Error answering SyncFlow call: \(error)")
            }
        }
    }

    func rejectSyncFlowCall(_ call: SyncFlowCall) {
        guard let userId = userId else { return }

        ringtoneManager.stopRinging()
        incomingSyncFlowCall = nil

        Task {
            do {
                // Check if this is a user-to-user call - pass userId explicitly
                if call.callerPlatform == "android" || call.callerPlatform == "macos" {
                    try await syncFlowCallManager.rejectUserCall(callId: call.id, userId: userId)
                } else {
                    try await syncFlowCallManager.rejectCall(userId: userId, callId: call.id)
                }
            } catch {
                print("Error rejecting SyncFlow call: \(error)")
            }
        }
    }

    func startSyncFlowCall(to deviceId: String, deviceName: String, isVideo: Bool) {
        guard let userId = userId else { return }

        Task {
            do {
                let callId = try await syncFlowCallManager.startCall(
                    userId: userId,
                    calleeDeviceId: deviceId,
                    calleeName: deviceName,
                    isVideo: isVideo
                )
                await MainActor.run {
                    showSyncFlowCallView = true
                }
            } catch {
                print("Error starting SyncFlow call: \(error)")
            }
        }
    }

    func endSyncFlowCall() {
        Task {
            do {
                try await syncFlowCallManager.endCall()
                await MainActor.run {
                    activeSyncFlowCall = nil
                    showSyncFlowCallView = false
                }
            } catch {
                print("Error ending SyncFlow call: \(error)")
            }
        }
    }

    // MARK: - Performance Optimization Methods

    func reduceBackgroundActivity() {
        // Reduce frequency of background sync operations
        print("[AppState] Reducing background activity")

        // Throttle clipboard sync
        clipboardSyncService.reduceFrequency()

        // Reduce photo sync frequency
        photoSyncService.reduceSyncFrequency()

        // Throttle notification mirroring
        notificationMirrorService.reduceUpdateFrequency()

        // Reduce real-time sync intervals
        // This would be implemented in the respective services
    }

    func minimizeBackgroundActivity() {
        // Further reduce background activity for low battery
        print("[AppState] Minimizing background activity")

        // Pause clipboard sync temporarily
        clipboardSyncService.pauseSync()

        // Pause photo sync
        photoSyncService.pauseSync()

        // Reduce notification mirroring frequency significantly
        notificationMirrorService.pauseMirroring()

        // Disable non-essential real-time features
        dndSyncService.pauseSync()
        mediaControlService.reduceUpdates()
    }

    func suspendNonEssentialFeatures() {
        // Suspend most background features for critical battery
        print("[AppState] Suspending non-essential features")

        // Suspend all sync services
        clipboardSyncService.stopSync()
        photoSyncService.stopSync()
        notificationMirrorService.stopSync()
        dndSyncService.stopSync()
        mediaControlService.stopListening()
        scheduledMessageService.pauseAll()
        voicemailSyncService.pauseSync()

        // Show low battery warning to user
        showLowBatteryWarning()
    }

    private func showLowBatteryWarning() {
        // This would show a system notification or alert
        // For now, just log it
        print("[AppState] Low battery warning - most features suspended")
    }
}
