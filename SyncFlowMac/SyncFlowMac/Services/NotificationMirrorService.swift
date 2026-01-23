//
//  NotificationMirrorService.swift
//  SyncFlowMac
//
//  Service to receive and display mirrored notifications from Android
//

import Foundation
import AppKit
import UserNotifications
import Combine
import FirebaseDatabase

class NotificationMirrorService: ObservableObject {
    static let shared = NotificationMirrorService()

    @Published var recentNotifications: [MirroredNotification] = []
    @Published var isEnabled: Bool = true
    @Published var lastSyncTime: Date?

    private let database = Database.database()
    private var notificationsHandle: DatabaseHandle?
    private var notificationsRemovedHandle: DatabaseHandle?
    private var notificationsQuery: DatabaseQuery?
    private var notificationsRef: DatabaseReference?
    private var currentUserId: String?

    // Track displayed notifications to avoid duplicates
    private var displayedNotificationIds = Set<String>()

    private init() {
        // Request notification permissions
        requestNotificationPermission()
    }

    /// Request permission to show notifications
    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if granted {
                print("NotificationMirrorService: Notification permission granted")
            } else if let error = error {
                print("NotificationMirrorService: Permission error: \(error)")
            }
        }
    }

    /// Start listening for mirrored notifications
    func startSync(userId: String) {
        currentUserId = userId
        startListeningForNotifications(userId: userId)
        print("NotificationMirrorService: Started syncing for user \(userId)")
    }

    /// Stop syncing
    func stopSync() {
        stopListeningForNotifications()
        currentUserId = nil
        print("NotificationMirrorService: Stopped syncing")
    }

    /// Listen for notification updates from Firebase
    private func startListeningForNotifications(userId: String) {
        let notificationsRef = database.reference()
            .child("users")
            .child(userId)
            .child("mirrored_notifications")
        self.notificationsRef = notificationsRef

        // Only listen for new notifications (ordered by syncedAt)
        let query = notificationsRef
            .queryOrdered(byChild: "syncedAt")
            .queryLimited(toLast: 20)
        notificationsQuery = query
        notificationsHandle = query.observe(.childAdded) { [weak self] snapshot, _ in
                guard let self = self,
                      self.isEnabled else { return }

                guard let data = snapshot.value as? [String: Any] else { return }

                if let notification = self.parseNotification(id: snapshot.key, data: data) {
                    // Check if we've already displayed this
                    if !self.displayedNotificationIds.contains(notification.id) {
                        self.displayedNotificationIds.insert(notification.id)

                        DispatchQueue.main.async {
                            // Add to list (keep most recent at top)
                            self.recentNotifications.insert(notification, at: 0)

                            // Limit list size
                            if self.recentNotifications.count > 50 {
                                self.recentNotifications.removeLast()
                            }

                            self.lastSyncTime = Date()
                        }

                        // Show macOS notification
                        self.showNotification(notification)
                    }
                }
            }

        // Also listen for removals
        notificationsRemovedHandle = notificationsRef.observe(.childRemoved) { [weak self] snapshot, _ in
            DispatchQueue.main.async {
                self?.recentNotifications.removeAll { $0.id == snapshot.key }
            }
            let identifier = "mirrored-\(snapshot.key)"
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [identifier])
        }
    }

    private func stopListeningForNotifications() {
        guard currentUserId != nil else { return }

        if let handle = notificationsHandle {
            notificationsQuery?.removeObserver(withHandle: handle)
        }
        if let handle = notificationsRemovedHandle {
            notificationsRef?.removeObserver(withHandle: handle)
        }

        notificationsHandle = nil
        notificationsRemovedHandle = nil
        notificationsQuery = nil
        notificationsRef = nil
    }

    /// Parse notification data from Firebase
    private func parseNotification(id: String, data: [String: Any]) -> MirroredNotification? {
        guard let appPackage = data["appPackage"] as? String,
              let appName = data["appName"] as? String,
              let title = data["title"] as? String,
              let timestamp = data["timestamp"] as? Double else {
            return nil
        }

        let text = data["text"] as? String ?? ""
        let appIconBase64 = data["appIcon"] as? String
        let syncedAt = data["syncedAt"] as? Double ?? Date().timeIntervalSince1970 * 1000

        // Decode icon if available
        var appIcon: NSImage? = nil
        if let iconData = appIconBase64,
           let data = Data(base64Encoded: iconData) {
            appIcon = NSImage(data: data)
        }

        return MirroredNotification(
            id: id,
            appPackage: appPackage,
            appName: appName,
            appIcon: appIcon,
            title: title,
            text: text,
            timestamp: Date(timeIntervalSince1970: timestamp / 1000),
            syncedAt: Date(timeIntervalSince1970: syncedAt / 1000)
        )
    }

    /// Show a macOS notification
    private func showNotification(_ notification: MirroredNotification) {
        let content = UNMutableNotificationContent()
        content.title = notification.appName
        content.subtitle = notification.title
        content.body = notification.text
        content.sound = .default
        content.categoryIdentifier = "MIRRORED_NOTIFICATION"

        // Add app icon if available
        // Note: UNNotificationAttachment requires a file URL, so we'd need to save the icon first
        // For simplicity, we skip this for now

        let request = UNNotificationRequest(
            identifier: "mirrored-\(notification.id)",
            content: content,
            trigger: nil // Deliver immediately
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("NotificationMirrorService: Error showing notification: \(error)")
            } else {
                print("NotificationMirrorService: Showed notification from \(notification.appName)")
            }
        }
    }

    /// Clear all mirrored notifications
    func clearNotifications() {
        guard let userId = currentUserId else { return }

        let notificationsRef = database.reference()
            .child("users")
            .child(userId)
            .child("mirrored_notifications")

        notificationsRef.removeValue { error, _ in
            if let error = error {
                print("NotificationMirrorService: Error clearing notifications: \(error)")
            } else {
                DispatchQueue.main.async {
                    self.recentNotifications.removeAll()
                    self.displayedNotificationIds.removeAll()
                }
                print("NotificationMirrorService: Cleared all notifications")
            }
        }
    }

    /// Dismiss a single notification
    func dismissNotification(_ notification: MirroredNotification) {
        guard let userId = currentUserId else { return }

        let notificationRef = database.reference()
            .child("users")
            .child(userId)
            .child("mirrored_notifications")
            .child(notification.id)

        notificationRef.removeValue { error, _ in
            if let error = error {
                print("NotificationMirrorService: Error dismissing notification: \(error)")
            } else {
                DispatchQueue.main.async {
                    self.recentNotifications.removeAll { $0.id == notification.id }
                }
            }
        }
    }

    /// Toggle notification mirroring
    func setEnabled(_ enabled: Bool) {
        isEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "notification_mirror_enabled")
    }

    /// Reduce update frequency for battery saving
    func reduceUpdateFrequency() {
        // Reduce the frequency of notification updates
        print("NotificationMirrorService: Reducing update frequency")
    }

    /// Pause notification mirroring temporarily
    func pauseMirroring() {
        isEnabled = false
        print("NotificationMirrorService: Paused mirroring")
    }

    /// Resume notification mirroring
    func resumeMirroring() {
        isEnabled = true
        if let userId = currentUserId {
            startSync(userId: userId)
        }
    }
}

/// Model for mirrored notification
struct MirroredNotification: Identifiable {
    let id: String
    let appPackage: String
    let appName: String
    let appIcon: NSImage?
    let title: String
    let text: String
    let timestamp: Date
    let syncedAt: Date

    var formattedTime: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: timestamp, relativeTo: Date())
    }
}
