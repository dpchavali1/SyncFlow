//
//  SyncFlowMacApp.swift
//  SyncFlowMac
//
//  Main application entry point for SyncFlow macOS app
//

// =============================================================================
// MARK: - Architecture Overview
// =============================================================================
//
// SyncFlowMacApp is the main entry point for the SyncFlow macOS application.
// It serves as the companion app to the SyncFlow Android application, enabling
// users to send/receive SMS messages, manage calls, and sync data from their
// phone to their Mac.
//
// ARCHITECTURE:
// -------------
// The app follows a SwiftUI App lifecycle pattern with:
// - @main entry point (SyncFlowMacApp)
// - Central state management via AppState (ObservableObject)
// - Environment object injection for state propagation
// - Firebase backend for real-time sync with Android companion
//
// INITIALIZATION FLOW:
// --------------------
// 1. Firebase Configuration - Initializes Firebase SDK for real-time database
//    and authentication
// 2. Firebase Authentication - Signs in anonymously for unpaired devices,
//    or uses existing auth session for paired devices
// 3. Appearance Configuration - Sets up app-wide visual styling
// 4. Performance Optimization - Initializes battery-aware service management
//    and memory optimization utilities
//
// SCENE STRUCTURE:
// ----------------
// - WindowGroup: Main application window with ContentView
// - Settings: Preferences window accessible via Cmd+,
// - MenuBarExtra: System menu bar icon with quick actions
//
// DEPENDENCY INJECTION:
// ---------------------
// AppState is created as a @StateObject and injected into the view hierarchy
// via .environmentObject(). All views access shared state through
// @EnvironmentObject var appState: AppState
//
// SERVICES MANAGED BY APPSTATE:
// -----------------------------
// - PhoneStatusService: Monitors phone battery, signal, connectivity
// - ClipboardSyncService: Syncs clipboard between Mac and phone
// - FileTransferService: Handles Quick Drop file transfers
// - ContinuityService: Manages continuity features
// - PhotoSyncService: Syncs photos from phone
// - NotificationMirrorService: Mirrors phone notifications to Mac
// - HotspotControlService: Controls phone hotspot remotely
// - DNDSyncService: Syncs Do Not Disturb status
// - MediaControlService: Controls phone media playback
// - ScheduledMessageService: Manages scheduled SMS messages
// - VoicemailSyncService: Syncs voicemails from phone
// - SyncFlowCallManager: Handles WebRTC calling between devices
//
// LIFECYCLE CONSIDERATIONS:
// -------------------------
// - AppState is initialized once and persists for app lifetime
// - Firebase listeners are set up on pairing and torn down on unpair
// - Background activity token prevents suspension for incoming calls
// - Battery state monitoring adjusts sync frequency based on power status
//
// =============================================================================

import SwiftUI
import FirebaseCore
import FirebaseDatabase
import FirebaseAuth
import Combine

// =============================================================================
// MARK: - ColorScheme Extension
// =============================================================================
/// Extension to support initializing ColorScheme from user preference strings.
/// Used for persisting and restoring the user's preferred color scheme setting.
extension ColorScheme {
    /// Initializes a ColorScheme from a raw string value.
    /// - Parameter rawValue: "light", "dark", or any other value returns nil (system default)
    init?(rawValue: String) {
        switch rawValue {
        case "light": self = .light
        case "dark": self = .dark
        default: return nil
        }
    }
}

// =============================================================================
// MARK: - SyncFlowMacApp (Main Entry Point)
// =============================================================================
/// The main application struct and entry point for SyncFlow macOS.
///
/// This struct conforms to the SwiftUI `App` protocol and is marked with `@main`
/// to indicate it's the application entry point. It manages:
/// - Application-wide state via `AppState`
/// - Firebase initialization and authentication
/// - Performance optimization services
/// - Window and menu bar configuration
///
/// ## State Management
/// `AppState` is created as a `@StateObject` ensuring it persists across view
/// updates and is initialized only once. It's injected into the view hierarchy
/// via `.environmentObject()` for access throughout the app.
///
/// ## Scenes
/// The app provides three scenes:
/// 1. **WindowGroup**: Main application window containing `ContentView`
/// 2. **Settings**: Preferences window with `SettingsView`
/// 3. **MenuBarExtra**: System menu bar presence with quick actions
@main
struct SyncFlowMacApp: App {

    // =========================================================================
    // MARK: - State Properties
    // =========================================================================

    /// The central application state object.
    /// Created once at app launch and injected into the view hierarchy.
    /// Contains all shared state, service references, and business logic.
    @StateObject private var appState = AppState()

    // =========================================================================
    // MARK: - Initialization
    // =========================================================================

    /// Initializes the application and its core services.
    ///
    /// Initialization order is important:
    /// 1. Firebase must be configured before any Firebase services are used
    /// 2. Authentication follows to establish user session
    /// 3. Appearance and performance services are initialized last
    init() {
        // Configure Firebase - must be first before any Firebase SDK usage
        FirebaseApp.configure()

        // Initialize Firebase authentication (sign in anonymously if needed)
        initializeFirebaseAuth()

        // Configure app appearance
        configureAppearance()

        // Initialize performance optimizations
        setupPerformanceOptimizations()
    }

    // =========================================================================
    // MARK: - Firebase Authentication
    // =========================================================================

    /// Initializes Firebase authentication based on pairing status.
    ///
    /// Authentication Strategy:
    /// - **Paired Devices**: Use existing custom token authentication from pairing flow.
    ///   We don't create anonymous auth as it would create a new UID and break the
    ///   connection to the paired phone.
    /// - **Unpaired Devices**: Sign in anonymously to enable the pairing flow.
    ///   Anonymous auth provides a temporary UID for generating QR codes.
    ///
    /// The user ID is stored in UserDefaults under "syncflow_user_id" after successful pairing.
    private func initializeFirebaseAuth() {
        let auth = Auth.auth()

        // Check if we're already paired - if so, don't sign in anonymously
        // The paired auth session uses a custom token, not anonymous auth
        let isPaired = UserDefaults.standard.string(forKey: "syncflow_user_id") != nil

        if isPaired {
            // Already paired - don't create a new anonymous user
            // The existing auth session or re-pairing will handle authentication
            if let currentUser = auth.currentUser {
                print("[App] Firebase auth active for paired device: \(currentUser.uid)")
            } else {
                print("[App] Paired device but no Firebase auth - will need to re-authenticate")
            }
            return
        }

        // Only sign in anonymously if NOT paired (for initial pairing flow)
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

    // =========================================================================
    // MARK: - Performance Optimization
    // =========================================================================

    /// Sets up battery-aware service management and memory optimization.
    ///
    /// Performance Management Strategy:
    /// - **Battery Monitoring**: Tracks battery level and charging status
    /// - **Service State**: Adjusts sync frequency based on power conditions
    /// - **Memory Optimization**: Proactively manages memory to prevent issues
    ///
    /// Service States:
    /// - `.full`: Normal operation, all features active
    /// - `.reduced`: Lower sync frequency, reduce background activity
    /// - `.minimal`: Pause non-essential syncs (clipboard, photo)
    /// - `.suspended`: Stop most services, only essential features remain
    ///
    /// This helps extend battery life on MacBooks when unplugged while ensuring
    /// critical features (incoming calls, messages) remain functional.
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

    // =========================================================================
    // MARK: - Scene Body
    // =========================================================================

    /// The main scene body defining the application's window structure.
    ///
    /// Scene Hierarchy:
    /// 1. **WindowGroup**: Main app window containing ContentView
    ///    - Minimum size: 900x600 for optimal layout
    ///    - Color scheme follows user preference or system setting
    ///    - AppState injected as environment object
    /// 2. **Settings**: Preferences window (Cmd+,)
    /// 3. **MenuBarExtra**: System tray icon with quick actions
    ///
    /// Custom Menu Commands:
    /// - Messages: New message (Cmd+N), templates (Cmd+T), search (Cmd+F)
    /// - Calls: Dialer, answer/reject/end call shortcuts
    /// - Phone: Photos, notifications, scheduled messages, Find My Phone, etc.
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

// =============================================================================
// MARK: - App Tab Enumeration
// =============================================================================

/// Represents the main navigation tabs in the application.
/// Each tab corresponds to a major feature area in the side rail navigation.
enum AppTab: String, CaseIterable {
    case messages = "Messages"    /// SMS/MMS message conversations
    case contacts = "Contacts"    /// Phone contacts synced from device
    case callHistory = "Calls"    /// Call log and dialer
    case deals = "Deals"          /// Promotional deals and offers

    /// SF Symbol icon name for each tab
    var icon: String {
        switch self {
        case .messages:
            return "message.fill"
        case .contacts:
            return "person.2.fill"
        case .callHistory:
            return "phone.fill"
        case .deals:
            return "tag.fill"
        }
    }
}

// =============================================================================
// MARK: - AppState (Central State Management)
// =============================================================================

/// The central state management class for the SyncFlow macOS application.
///
/// AppState serves as the single source of truth for all application state and
/// coordinates communication between various services. It follows the ObservableObject
/// pattern for SwiftUI integration.
///
/// ## Architecture Role
/// AppState acts as a coordinator/mediator between:
/// - UI layer (SwiftUI views)
/// - Service layer (sync services, Firebase)
/// - Data layer (UserDefaults, Firebase Realtime Database)
///
/// ## State Categories
///
/// ### Authentication & Pairing
/// - `userId`: Firebase user ID (set after successful pairing)
/// - `isPaired`: Whether the Mac is paired with a phone
///
/// ### Navigation & UI
/// - `selectedTab`: Current main navigation tab
/// - `selectedConversation`: Currently selected message thread
/// - `showNewMessage`, `showDialer`, etc.: Modal/sheet presentation state
///
/// ### Phone Calls
/// - `activeCalls`: List of active phone calls on the paired device
/// - `incomingCall`: Current incoming call (triggers UI overlay)
/// - `incomingSyncFlowCall`: Incoming WebRTC call from another SyncFlow device
/// - `activeSyncFlowCall`: Currently active WebRTC call
///
/// ### Services (Singleton References)
/// Each service is initialized as a shared singleton and configured with userId on pairing.
/// Services are started/stopped based on pairing status and battery conditions.
///
/// ## Lifecycle
/// 1. **Init**: Check for existing pairing, restore state, start services if paired
/// 2. **Pairing**: Call `setPaired(userId:)` to configure services and start listeners
/// 3. **Unpairing**: Call `unpair()` to stop services and clear state
/// 4. **Deallocation**: Clean up listeners and background activity tokens
///
/// ## Thread Safety
/// All @Published properties must be updated on the main thread.
/// Service callbacks use `DispatchQueue.main.async` for UI updates.
class AppState: ObservableObject {

    // =========================================================================
    // MARK: - Authentication & Pairing State
    // =========================================================================

    /// Firebase user ID, set after successful pairing with phone
    @Published var userId: String?

    /// Whether the Mac is successfully paired with a phone
    @Published var isPaired: Bool = false

    // =========================================================================
    // MARK: - UI State
    // =========================================================================

    /// Whether to show the media control bar in main view
    @Published var showMediaBar: Bool = UserDefaults.standard.object(forKey: "syncflow_show_media_bar") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(showMediaBar, forKey: "syncflow_show_media_bar")
        }
    }

    /// Modal presentation flags
    @Published var showNewMessage: Bool = false
    @Published var showDialer: Bool = false
    @Published var showTemplates: Bool = false
    @Published var focusSearch: Bool = false

    /// Currently selected conversation for message detail view
    @Published var selectedConversation: Conversation?

    /// Currently selected navigation tab
    @Published var selectedTab: AppTab = .messages

    /// Continuity handoff suggestion from phone
    @Published var continuitySuggestion: ContinuityService.ContinuityState?

    // =========================================================================
    // MARK: - Phone Call State
    // =========================================================================

    /// Active phone calls on the paired Android device
    @Published var activeCalls: [ActiveCall] = []

    /// Current incoming phone call (triggers full-screen overlay)
    @Published var incomingCall: ActiveCall? = nil {
        didSet {
            handleIncomingCallChange(oldValue: oldValue, newValue: incomingCall)
        }
    }

    /// ID of call that was answered from Mac (shows in-progress banner)
    @Published var lastAnsweredCallId: String? = nil

    // =========================================================================
    // MARK: - SyncFlow WebRTC Calling
    // =========================================================================

    /// Incoming device-to-device WebRTC call
    @Published var incomingSyncFlowCall: SyncFlowCall?

    /// Currently active WebRTC call
    @Published var activeSyncFlowCall: SyncFlowCall?

    /// Whether to show the call view overlay
    @Published var showSyncFlowCallView: Bool = false

    /// WebRTC call manager for device-to-device audio/video calls
    let syncFlowCallManager = SyncFlowCallManager()

    // =========================================================================
    // MARK: - Message State
    // =========================================================================

    /// Count of unread messages (shown in menu bar badge)
    @Published var unreadCount: Int = 0

    /// Recent conversations for quick access
    @Published var recentConversations: [Conversation] = []

    // =========================================================================
    // MARK: - Phone Status
    // =========================================================================

    /// Current status of paired phone (battery, signal, etc.)
    @Published var phoneStatus = PhoneStatus()

    /// Service for monitoring phone status
    let phoneStatusService = PhoneStatusService.shared

    // =========================================================================
    // MARK: - Sync Services
    // =========================================================================

    /// Clipboard sync between Mac and phone
    @Published var clipboardSyncEnabled: Bool = true
    let clipboardSyncService = ClipboardSyncService.shared

    /// File transfer (Quick Drop) service
    let fileTransferService = FileTransferService.shared

    /// Continuity/handoff service
    let continuityService = ContinuityService.shared

    /// Find My Phone feature state
    @Published var isPhoneRinging: Bool = false

    /// Photo sync service
    let photoSyncService = PhotoSyncService.shared
    @Published var showPhotoGallery: Bool = false

    /// Notification mirroring service
    let notificationMirrorService = NotificationMirrorService.shared
    @Published var showNotifications: Bool = false

    /// Remote hotspot control service
    let hotspotControlService = HotspotControlService.shared

    /// Do Not Disturb sync service
    let dndSyncService = DNDSyncService.shared

    /// Remote media control service
    let mediaControlService = MediaControlService.shared

    /// Scheduled message service
    let scheduledMessageService = ScheduledMessageService.shared
    @Published var showScheduledMessages: Bool = false

    /// Voicemail sync service
    let voicemailSyncService = VoicemailSyncService.shared
    @Published var showVoicemails: Bool = false

    // =========================================================================
    // MARK: - Private Properties
    // =========================================================================

    /// Firebase database listener handles (for cleanup)
    private var activeCallsListenerHandle: DatabaseHandle?
    private var phoneStatusCancellable: AnyCancellable?
    private var messagesListenerHandle: DatabaseHandle?
    private var deviceStatusHandle: DatabaseHandle?
    private var deviceStatusRef: DatabaseReference?

    /// Combine subscriptions for reactive updates
    private var cancellables = Set<AnyCancellable>()

    /// Ringtone manager for incoming call sounds
    let ringtoneManager = CallRingtoneManager.shared
    private var pendingCallNotificationId: String?

    /// Background activity token to prevent app suspension when minimized.
    /// This ensures Firebase listeners remain active for incoming calls.
    /// Critical for receiving calls when the app is not in foreground.
    private var backgroundActivity: NSObjectProtocol?

    // =========================================================================
    // MARK: - Lifecycle
    // =========================================================================

    /// Cleanup when AppState is deallocated.
    /// NOTE: This only cleans up local resources (listeners, background activity).
    /// Do NOT call unpair() here - that should only happen on explicit user request.
    /// Otherwise the app will unpair every time it restarts or AppState is recreated.
    deinit {
        stopBackgroundActivity()
        stopListeningForCalls()
    }

    /// Initializes AppState and restores previous pairing if available.
    ///
    /// Initialization Flow:
    /// 1. Check UserDefaults for existing pairing (syncflow_user_id)
    /// 2. If paired, start all services with stored userId
    /// 3. Set up notification observers for call actions
    /// 4. Subscribe to call state changes for UI updates
    /// 5. Subscribe to continuity state for handoff suggestions
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

            // Start background activity to keep Firebase listeners active when minimized
            startBackgroundActivity()
        }

        // Observe notification actions for answering/declining calls from macOS notifications
        setupNotificationActionObservers()

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
                    self?.ringtoneManager.stopRinging()  // Stop ringback for outgoing calls
                    self?.showSyncFlowCallView = false
                    self?.activeSyncFlowCall = nil
                    self?.incomingSyncFlowCall = nil  // Also clear any incoming call state
                    if let callId = self?.pendingCallNotificationId {
                        NotificationService.shared.clearCallNotification(callId: callId)
                        self?.pendingCallNotificationId = nil
                    }
                case .idle:
                    // Also hide on idle (cleanup completed)
                    self?.ringtoneManager.stopRinging()  // Ensure ringtone is stopped
                    if self?.showSyncFlowCallView == true {
                        self?.showSyncFlowCallView = false
                        self?.activeSyncFlowCall = nil
                    }
                    self?.incomingSyncFlowCall = nil
                default:
                    break
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

    // MARK: - Background Activity

    /// Starts a background activity to prevent macOS from suspending the app when minimized.
    /// This ensures Firebase listeners remain active for incoming calls.
    private func startBackgroundActivity() {
        guard backgroundActivity == nil else { return }

        backgroundActivity = ProcessInfo.processInfo.beginActivity(
            options: [.userInitiatedAllowingIdleSystemSleep, .idleSystemSleepDisabled],
            reason: "SyncFlow needs to receive incoming calls"
        )
        print("AppState: Started background activity for incoming calls")
    }

    private func stopBackgroundActivity() {
        if let activity = backgroundActivity {
            ProcessInfo.processInfo.endActivity(activity)
            backgroundActivity = nil
            print("AppState: Stopped background activity")
        }
    }

    // MARK: - Notification Action Observers

    private func setupNotificationActionObservers() {
        // Answer call from notification
        NotificationCenter.default.addObserver(
            forName: .answerCallFromNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self = self,
                  let userInfo = notification.userInfo,
                  let callId = userInfo["callId"] as? String,
                  let callerName = userInfo["callerName"] as? String else {
                return
            }

            let withVideo = userInfo["withVideo"] as? Bool ?? false
            let isVideoCall = userInfo["isVideoCall"] as? Bool ?? false

            print("AppState: Answering call from notification - callId: \(callId), withVideo: \(withVideo)")

            // Find the incoming call or create one from the notification data
            if let existingCall = self.incomingSyncFlowCall, existingCall.id == callId {
                self.answerSyncFlowCall(existingCall, withVideo: withVideo)
            } else {
                // Create a temporary call object to answer
                let call = SyncFlowCall(
                    id: callId,
                    callerId: "",
                    callerName: callerName,
                    callerPlatform: "android",
                    calleeId: self.userId ?? "",
                    calleeName: "Me",
                    calleePlatform: "macos",
                    callType: isVideoCall ? .video : .audio,
                    status: .ringing,
                    startedAt: Date(),
                    answeredAt: nil,
                    endedAt: nil,
                    offer: nil,
                    answer: nil
                )
                self.answerSyncFlowCall(call, withVideo: withVideo)
            }
        }

        // Decline call from notification
        NotificationCenter.default.addObserver(
            forName: .declineCallFromNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self = self,
                  let userInfo = notification.userInfo,
                  let callId = userInfo["callId"] as? String else {
                return
            }

            print("AppState: Declining call from notification - callId: \(callId)")

            // Find the incoming call or create one from the notification data
            if let existingCall = self.incomingSyncFlowCall, existingCall.id == callId {
                self.rejectSyncFlowCall(existingCall)
            } else {
                // Create a temporary call object to reject
                let call = SyncFlowCall(
                    id: callId,
                    callerId: "",
                    callerName: "Unknown",
                    callerPlatform: "android",
                    calleeId: self.userId ?? "",
                    calleeName: "Me",
                    calleePlatform: "macos",
                    callType: .audio,
                    status: .ringing,
                    startedAt: Date(),
                    answeredAt: nil,
                    endedAt: nil,
                    offer: nil,
                    answer: nil
                )
                self.rejectSyncFlowCall(call)
            }
        }

        // Show incoming call UI from notification tap
        NotificationCenter.default.addObserver(
            forName: .showIncomingCallUI,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self = self,
                  let userInfo = notification.userInfo,
                  let callId = userInfo["callId"] as? String,
                  let callerName = userInfo["callerName"] as? String else {
                return
            }

            let isVideo = userInfo["isVideo"] as? Bool ?? false

            print("AppState: Showing incoming call UI from notification - callId: \(callId)")

            // If we don't already have this incoming call showing, create one
            if self.incomingSyncFlowCall?.id != callId {
                let call = SyncFlowCall(
                    id: callId,
                    callerId: "",
                    callerName: callerName,
                    callerPlatform: "android",
                    calleeId: self.userId ?? "",
                    calleeName: "Me",
                    calleePlatform: "macos",
                    callType: isVideo ? .video : .audio,
                    status: .ringing,
                    startedAt: Date(),
                    answeredAt: nil,
                    endedAt: nil,
                    offer: nil,
                    answer: nil
                )
                self.incomingSyncFlowCall = call
            }
        }

        // Answer phone call from notification
        NotificationCenter.default.addObserver(
            forName: .answerPhoneCallFromNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self = self,
                  let userInfo = notification.userInfo,
                  let callId = userInfo["callId"] as? String else {
                return
            }

            print("AppState: Answering phone call from notification - callId: \(callId)")

            // Find the incoming phone call and answer it
            if let call = self.activeCalls.first(where: { $0.id == callId && $0.callState == .ringing }) {
                self.answerCall(call)
            } else if let call = self.incomingCall, call.id == callId {
                self.answerCall(call)
            }
        }

        // Decline phone call from notification
        NotificationCenter.default.addObserver(
            forName: .declinePhoneCallFromNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self = self,
                  let userInfo = notification.userInfo,
                  let callId = userInfo["callId"] as? String else {
                return
            }

            print("AppState: Declining phone call from notification - callId: \(callId)")

            // Find the incoming phone call and reject it
            if let call = self.activeCalls.first(where: { $0.id == callId && $0.callState == .ringing }) {
                self.rejectCall(call)
            } else if let call = self.incomingCall, call.id == callId {
                self.rejectCall(call)
            }
        }
    }

    func setPaired(userId: String) {
        self.userId = userId
        self.isPaired = true
        UserDefaults.standard.set(userId, forKey: "syncflow_user_id")

        // Update subscription status with the newly paired user's plan from Firebase
        Task {
            await SubscriptionService.shared.updateSubscriptionStatus()
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

        // Start background activity to keep Firebase listeners active when minimized
        startBackgroundActivity()
    }

    func unpair() {
        guard isPaired || userId != nil else { return }

        // Get device info before clearing
        let currentUserId = userId
        let deviceId = UserDefaults.standard.string(forKey: "syncflow_device_id")

        stopListeningForCalls()
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
        stopBackgroundActivity()

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

                // Find NEWEST ringing call by timestamp (not just first one)
                let ringingCalls = calls.filter { $0.callState == .ringing }
                let newestRingingCall = ringingCalls.max(by: { $0.timestamp < $1.timestamp })

                if newestRingingCall != nil {
                    // Clear notifications for any OLD ringing calls that are being replaced
                    for oldCall in ringingCalls where oldCall.id != newestRingingCall?.id {
                        NotificationService.shared.clearPhoneCallNotification(callId: oldCall.id)
                    }
                }
                self?.incomingCall = newestRingingCall

                // Clear banner reference when call is gone or ended
                if let bannerId = self?.lastAnsweredCallId {
                    let stillActive = calls.contains { $0.id == bannerId && $0.callState != .ended }
                    if !stillActive {
                        self?.lastAnsweredCallId = nil
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
        if let call = newValue, oldValue == nil {
            // New incoming phone call - start ringing and show notification
            ringtoneManager.startRinging()
            NotificationService.shared.showIncomingPhoneCallNotification(
                callerName: call.displayName,
                phoneNumber: call.formattedPhoneNumber,
                callId: call.id
            )
        } else if newValue == nil, let oldCall = oldValue {
            // Incoming phone call ended/answered - stop ringing and clear notification
            ringtoneManager.stopRinging()
            NotificationService.shared.clearPhoneCallNotification(callId: oldCall.id)
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
        print("AppState: Starting to listen for incoming user calls for userId: \(userId)")

        // Use the SyncFlowCallManager to listen for incoming user calls
        syncFlowCallManager.startListeningForIncomingUserCalls(userId: userId)

        // Observe incoming user calls from the call manager
        syncFlowCallManager.$incomingUserCall
            .receive(on: DispatchQueue.main)
            .sink { [weak self] incomingCall in
                if let call = incomingCall {
                    print("🔔 AppState: Incoming user call detected from \(call.callerName), callId: \(call.callId)")
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
                        callerPhone: call.callerPhone,
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
        // Stop ringtone immediately (don't wait for async call)
        ringtoneManager.stopRinging()

        Task {
            do {
                try await syncFlowCallManager.endCall()
                await MainActor.run {
                    activeSyncFlowCall = nil
                    showSyncFlowCallView = false
                    incomingSyncFlowCall = nil
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
