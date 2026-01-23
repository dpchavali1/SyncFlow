//
//  ClipboardSyncService.swift
//  SyncFlowMac
//
//  Service to sync clipboard content between macOS and Android
//

import Foundation
import AppKit
import FirebaseDatabase
import Combine

class ClipboardSyncService: ObservableObject {
    static let shared = ClipboardSyncService()

    @Published var isEnabled: Bool = true
    @Published var lastSyncedContent: String?
    @Published var lastSyncTime: Date?

    private let database = Database.database()
    private var clipboardHandle: DatabaseHandle?
    private var currentUserId: String?
    private var clipboardCheckTimer: Timer?

    // Track last synced content to avoid loops
    private var lastKnownContent: String?
    private var lastKnownChangeCount: Int = 0
    private var isUpdatingFromRemote = false

    private let maxClipboardLength = 50000 // 50KB max

    private init() {}

    /// Start clipboard sync
    func startSync(userId: String) {
        guard isEnabled else { return }
        currentUserId = userId

        // Start monitoring local clipboard
        startMonitoringLocalClipboard()

        // Start listening for remote clipboard changes
        startListeningForRemoteClipboard(userId: userId)

        print("ClipboardSyncService: Started syncing for user \(userId)")
    }

    /// Stop clipboard sync
    func stopSync() {
        stopMonitoringLocalClipboard()
        stopListeningForRemoteClipboard()
        currentUserId = nil
        print("ClipboardSyncService: Stopped syncing")
    }

    /// Monitor local clipboard for changes
    private func startMonitoringLocalClipboard() {
        // macOS doesn't have a notification for clipboard changes
        // So we poll the clipboard periodically
        DispatchQueue.main.async { [weak self] in
            self?.lastKnownChangeCount = NSPasteboard.general.changeCount

            self?.clipboardCheckTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                self?.checkLocalClipboard()
            }
            // Ensure timer runs on main run loop
            if let timer = self?.clipboardCheckTimer {
                RunLoop.main.add(timer, forMode: .common)
            }
            print("ClipboardSyncService: Local clipboard monitoring started")
        }
    }

    private func stopMonitoringLocalClipboard() {
        clipboardCheckTimer?.invalidate()
        clipboardCheckTimer = nil
    }

    /// Check if local clipboard has changed
    private func checkLocalClipboard() {
        guard isEnabled, !isUpdatingFromRemote else { return }
        guard currentUserId != nil else { return }

        let pasteboard = NSPasteboard.general
        let currentChangeCount = pasteboard.changeCount

        // Check if clipboard changed
        guard currentChangeCount != lastKnownChangeCount else { return }
        lastKnownChangeCount = currentChangeCount

        // Get text content
        guard let text = pasteboard.string(forType: .string) else {
            print("ClipboardSyncService: No text in clipboard")
            return
        }
        guard !text.isEmpty else { return }
        guard text.count <= maxClipboardLength else {
            print("ClipboardSyncService: Content too large (\(text.count) chars), skipping")
            return
        }

        // Check if content actually changed
        guard text != lastKnownContent else { return }

        print("ClipboardSyncService: Local clipboard changed, syncing: \(text.prefix(50))...")
        syncToFirebase(text: text)
    }

    /// Listen for clipboard changes from other devices
    private func startListeningForRemoteClipboard(userId: String) {
        let clipboardRef = database.reference()
            .child("users")
            .child(userId)
            .child("clipboard")

        clipboardHandle = clipboardRef.observe(.value) { [weak self] snapshot in
            guard let self = self else { return }

            guard let data = snapshot.value as? [String: Any] else { return }
            guard let source = data["source"] as? String else { return }

            // Only process if from another device (not macOS)
            guard source != "macos" else { return }

            guard let text = data["text"] as? String else { return }
            guard let timestamp = data["timestamp"] as? Double else { return }

            // Check if this is newer than what we synced
            if let lastSync = self.lastSyncTime,
               Date(timeIntervalSince1970: timestamp / 1000) <= lastSync {
                return
            }

            print("ClipboardSyncService: Received clipboard from \(source)")
            self.updateLocalClipboard(text: text, timestamp: timestamp)
        }
    }

    private func stopListeningForRemoteClipboard() {
        guard let userId = currentUserId, let handle = clipboardHandle else { return }

        database.reference()
            .child("users")
            .child(userId)
            .child("clipboard")
            .removeObserver(withHandle: handle)

        clipboardHandle = nil
    }

    /// Sync local clipboard to Firebase
    private func syncToFirebase(text: String) {
        guard let userId = currentUserId else { return }

        let clipboardRef = database.reference()
            .child("users")
            .child(userId)
            .child("clipboard")

        let timestamp = Date().timeIntervalSince1970 * 1000

        let data: [String: Any] = [
            "text": text,
            "timestamp": timestamp,
            "source": "macos",
            "type": "text"
        ]

        clipboardRef.setValue(data) { [weak self] error, _ in
            if let error = error {
                print("ClipboardSyncService: Error syncing to Firebase: \(error)")
            } else {
                DispatchQueue.main.async {
                    self?.lastKnownContent = text
                    self?.lastSyncedContent = text
                    self?.lastSyncTime = Date()
                }
                print("ClipboardSyncService: Synced to Firebase")
            }
        }
    }

    /// Update local clipboard with remote content
    private func updateLocalClipboard(text: String, timestamp: Double) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            self.isUpdatingFromRemote = true

            let pasteboard = NSPasteboard.general
            pasteboard.clearContents()
            pasteboard.setString(text, forType: .string)

            self.lastKnownContent = text
            self.lastKnownChangeCount = pasteboard.changeCount
            self.lastSyncedContent = text
            self.lastSyncTime = Date(timeIntervalSince1970: timestamp / 1000)

            // Show notification
            self.showClipboardNotification(text: text)

            print("ClipboardSyncService: Updated local clipboard from Android")

            // Reset flag after delay
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                self.isUpdatingFromRemote = false
            }
        }
    }

    /// Show notification when clipboard is updated from phone
    private func showClipboardNotification(text: String) {
        let notification = NSUserNotification()
        notification.title = "Clipboard Synced"
        notification.informativeText = text.count > 50 ? String(text.prefix(50)) + "..." : text
        notification.soundName = nil

        NSUserNotificationCenter.default.deliver(notification)
    }

    /// Manually sync current clipboard
    func syncNow() {
        guard let text = NSPasteboard.general.string(forType: .string),
              !text.isEmpty,
              text.count <= maxClipboardLength else { return }

        syncToFirebase(text: text)
    }

    /// Get current clipboard content
    func getCurrentClipboard() -> String? {
        return NSPasteboard.general.string(forType: .string)
    }

    /// Reduce sync frequency for battery saving
    func reduceFrequency() {
        // Increase check interval from 1.0 to 5.0 seconds
        stopMonitoringLocalClipboard()
        if isEnabled && currentUserId != nil {
            startMonitoringLocalClipboardWithInterval(5.0)
        }
    }

    /// Pause clipboard sync temporarily
    func pauseSync() {
        isEnabled = false
        stopMonitoringLocalClipboard()
        print("ClipboardSyncService: Paused syncing")
    }

    /// Resume clipboard sync
    func resumeSync() {
        isEnabled = true
        if let userId = currentUserId {
            startSync(userId: userId)
        }
    }

    /// Start monitoring with custom interval
    private func startMonitoringLocalClipboardWithInterval(_ interval: TimeInterval) {
        DispatchQueue.main.async { [weak self] in
            self?.clipboardCheckTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
                self?.checkLocalClipboard()
            }
            if let timer = self?.clipboardCheckTimer {
                RunLoop.main.add(timer, forMode: .common)
            }
            print("ClipboardSyncService: Local clipboard monitoring started with \(interval)s interval")
        }
    }
}
