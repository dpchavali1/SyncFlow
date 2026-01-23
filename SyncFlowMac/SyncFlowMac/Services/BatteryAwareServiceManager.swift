//
//  BatteryAwareServiceManager.swift
//  SyncFlowMac
//
//  Manages services based on battery level, charging status, and system conditions
//

import Foundation
import Combine

class BatteryAwareServiceManager {
    static let shared = BatteryAwareServiceManager()

    // MARK: - Service States

    enum ServiceState {
        case full        // All services active
        case reduced     // Reduced background activity
        case minimal     // Critical services only
        case suspended   // Most services suspended
    }

    // MARK: - Properties

    @Published private(set) var currentState: ServiceState = .full
    private var cancellables = Set<AnyCancellable>()
    private var batteryMonitor: Timer?
    private var systemLoadMonitor: Timer?
    private var stateChangeHandlers: [(ServiceState) -> Void] = []

    // MARK: - Initialization

    init() {
        setupBatteryMonitoring()
        setupNotificationObservers()
        updateServiceState()
    }

    deinit {
        batteryMonitor?.invalidate()
        systemLoadMonitor?.invalidate()
        // Remove notification observers to prevent memory leaks
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - Service State Management

    func addStateChangeHandler(_ handler: @escaping (ServiceState) -> Void) {
        stateChangeHandlers.append(handler)
        handler(currentState) // Call immediately with current state
    }

    private func updateServiceState() {
        let batteryLevel = getBatteryLevel()
        let isCharging = isDeviceCharging()
        let isLowPowerMode = isLowPowerModeEnabled()
        let systemLoad = getSystemLoad()

        let newState = determineServiceState(
            batteryLevel: batteryLevel,
            isCharging: isCharging,
            isLowPowerMode: isLowPowerMode,
            systemLoad: systemLoad
        )

        if newState != currentState {
            print("[BatteryManager] State change: \(currentState) -> \(newState) (Battery: \(batteryLevel)%, Charging: \(isCharging))")
            currentState = newState

            // Notify all handlers
            DispatchQueue.main.async {
                self.stateChangeHandlers.forEach { $0(newState) }
            }

            // Apply state changes
            applyServiceState(newState)
        }
    }

    private func determineServiceState(
        batteryLevel: Int,
        isCharging: Bool,
        isLowPowerMode: Bool,
        systemLoad: Double
    ) -> ServiceState {
        // Critical conditions
        if isLowPowerMode || (batteryLevel < 10 && !isCharging) {
            return .suspended
        }

        // Low battery conditions
        if batteryLevel < 20 && !isCharging {
            return .minimal
        }

        // Medium battery or light load
        if batteryLevel < 40 && !isCharging {
            return .reduced
        }

        // High load conditions
        if systemLoad > 2.0 && batteryLevel < 50 && !isCharging {
            return .reduced
        }

        // Good conditions
        return .full
    }

    private func applyServiceState(_ state: ServiceState) {
        switch state {
        case .full:
            resumeAllServices()
        case .reduced:
            reduceServiceActivity()
        case .minimal:
            minimizeServiceActivity()
        case .suspended:
            suspendNonEssentialServices()
        }
    }

    // MARK: - Service Control

    private func resumeAllServices() {
        // Resume all background sync operations
        NotificationCenter.default.post(name: .servicesResumeFull, object: nil)

        // Adjust Firebase listener frequencies
        adjustFirebaseListeners(interval: 1.0) // Normal frequency

        // Resume real-time features
        enableRealTimeSync()
    }

    private func reduceServiceActivity() {
        // Reduce background sync frequency
        NotificationCenter.default.post(name: .servicesReduceActivity, object: nil)

        // Throttle Firebase listeners
        adjustFirebaseListeners(interval: 5.0) // Reduced frequency

        // Reduce real-time update frequency
        reduceRealTimeUpdates()
    }

    private func minimizeServiceActivity() {
        // Pause most background operations
        NotificationCenter.default.post(name: .servicesMinimizeActivity, object: nil)

        // Significantly throttle Firebase listeners
        adjustFirebaseListeners(interval: 15.0) // Minimal frequency

        // Disable real-time features
        disableRealTimeSync()
    }

    private func suspendNonEssentialServices() {
        // Suspend almost all background activity
        NotificationCenter.default.post(name: .servicesSuspendNonEssential, object: nil)

        // Pause Firebase listeners
        pauseFirebaseListeners()

        // Disable all real-time sync
        disableAllRealTimeFeatures()
    }

    // MARK: - Firebase Listener Management

    private func adjustFirebaseListeners(interval: TimeInterval) {
        // This would need integration with FirebaseService to adjust polling intervals
        // For now, we post notification to allow other components to adjust
        NotificationCenter.default.post(name: .firebaseListenerFrequencyChanged, object: nil, userInfo: ["interval": interval])
    }

    private func pauseFirebaseListeners() {
        NotificationCenter.default.post(name: .firebaseListenersPaused, object: nil)
    }

    // MARK: - Real-time Feature Management

    private func enableRealTimeSync() {
        NotificationCenter.default.post(name: .realTimeSyncEnabled, object: nil)
    }

    private func reduceRealTimeUpdates() {
        NotificationCenter.default.post(name: .realTimeSyncReduced, object: nil)
    }

    private func disableRealTimeSync() {
        NotificationCenter.default.post(name: .realTimeSyncDisabled, object: nil)
    }

    private func disableAllRealTimeFeatures() {
        NotificationCenter.default.post(name: .allRealTimeFeaturesDisabled, object: nil)
    }

    // MARK: - System Monitoring

    private func setupBatteryMonitoring() {
        // Monitor battery level every 30 seconds
        batteryMonitor = Timer.scheduledTimer(withTimeInterval: 30.0, repeats: true) { [weak self] _ in
            self?.updateServiceState()
        }

        // Also monitor when device is charging/unplugged
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(powerSourceChanged),
            name: NSNotification.Name(rawValue: "com.apple.system.powermanagement.powerSourceChanged"),
            object: nil
        )
    }

    private func setupNotificationObservers() {
        // Monitor system load changes
        systemLoadMonitor = Timer.scheduledTimer(withTimeInterval: 60.0, repeats: true) { [weak self] _ in
            self?.updateServiceState()
        }

        // Monitor low power mode changes
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(lowPowerModeChanged),
            name: NSNotification.Name(rawValue: "NSProcessInfoPowerStateDidChangeNotification"),
            object: nil
        )
    }

    @objc private func powerSourceChanged() {
        // Small delay to allow system to settle
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            self.updateServiceState()
        }
    }

    @objc private func lowPowerModeChanged() {
        updateServiceState()
    }

    // MARK: - System Information

    private func getBatteryLevel() -> Int {
        let task = Process()
        task.launchPath = "/usr/bin/pmset"
        task.arguments = ["-g", "batt"]

        let pipe = Pipe()
        task.standardOutput = pipe

        do {
            try task.run()
            task.waitUntilExit()

            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            if let output = String(data: data, encoding: .utf8) {
                // Parse battery percentage from output like "Battery Power: 85%"
                if let range = output.range(of: "\\d+(?=%)", options: .regularExpression),
                   let percentage = Int(output[range]) {
                    return percentage
                }
            }
        } catch {
            print("[BatteryManager] Failed to get battery level: \(error)")
        }

        return 100 // Default to 100% if unable to determine
    }

    private func isDeviceCharging() -> Bool {
        let task = Process()
        task.launchPath = "/usr/bin/pmset"
        task.arguments = ["-g", "batt"]

        let pipe = Pipe()
        task.standardOutput = pipe

        do {
            try task.run()
            task.waitUntilExit()

            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            if let output = String(data: data, encoding: .utf8) {
                return output.contains("AC Power") || output.contains("charging") || output.contains("charged")
            }
        } catch {
            print("[BatteryManager] Failed to check charging status: \(error)")
        }

        return false
    }

    private func isLowPowerModeEnabled() -> Bool {
        let task = Process()
        task.launchPath = "/usr/bin/pmset"
        task.arguments = ["-g"]

        let pipe = Pipe()
        task.standardOutput = pipe

        do {
            try task.run()
            task.waitUntilExit()

            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            if let output = String(data: data, encoding: .utf8) {
                return output.contains("lowpowermode") && output.contains("1")
            }
        } catch {
            print("[BatteryManager] Failed to check low power mode: \(error)")
        }

        return false
    }

    private func getSystemLoad() -> Double {
        var loadavg = [Double](repeating: 0.0, count: 3)
        let count = getloadavg(&loadavg, 3)
        return count > 0 ? loadavg[0] : 0.0
    }

    // MARK: - Public API

    func startBatteryMonitoring() {
        // Already started in init, but this method allows manual restart
        if batteryMonitor == nil {
            setupBatteryMonitoring()
        }
    }

    func getCurrentBatteryInfo() -> (level: Int, isCharging: Bool, isLowPowerMode: Bool, systemLoad: Double) {
        return (
            level: getBatteryLevel(),
            isCharging: isDeviceCharging(),
            isLowPowerMode: isLowPowerModeEnabled(),
            systemLoad: getSystemLoad()
        )
    }

    func forceUpdate() {
        updateServiceState()
    }
}

// MARK: - Notification Extensions

extension Notification.Name {
    static let servicesResumeFull = Notification.Name("com.syncflowmac.servicesResumeFull")
    static let servicesReduceActivity = Notification.Name("com.syncflowmac.servicesReduceActivity")
    static let servicesMinimizeActivity = Notification.Name("com.syncflowmac.servicesMinimizeActivity")
    static let servicesSuspendNonEssential = Notification.Name("com.syncflowmac.servicesSuspendNonEssential")

    static let firebaseListenerFrequencyChanged = Notification.Name("com.syncflowmac.firebaseListenerFrequencyChanged")
    static let firebaseListenersPaused = Notification.Name("com.syncflowmac.firebaseListenersPaused")

    static let realTimeSyncEnabled = Notification.Name("com.syncflowmac.realTimeSyncEnabled")
    static let realTimeSyncReduced = Notification.Name("com.syncflowmac.realTimeSyncReduced")
    static let realTimeSyncDisabled = Notification.Name("com.syncflowmac.realTimeSyncDisabled")
    static let allRealTimeFeaturesDisabled = Notification.Name("com.syncflowmac.allRealTimeFeaturesDisabled")
}