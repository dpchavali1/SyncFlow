//
//  NotificationService.swift
//  SyncFlowMac
//
//  Handles desktop notifications for incoming messages
//

import Foundation
import UserNotifications
import AppKit

class NotificationService: NSObject, UNUserNotificationCenterDelegate {

    static let shared = NotificationService()

    private override init() {
        super.init()
        setupNotifications()
    }

    // MARK: - Setup

    private func setupNotifications() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self

        // Request authorization
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if let error = error {
                print("❌ Notification authorization error: \(error)")
            } else if granted {
                print("✅ Notification authorization granted")
            }
        }

        // Define quick reply action
        let replyAction = UNTextInputNotificationAction(
            identifier: "REPLY_ACTION",
            title: "Reply",
            options: [.foreground],
            textInputButtonTitle: "Send",
            textInputPlaceholder: "Type a message..."
        )

        let category = UNNotificationCategory(
            identifier: "MESSAGE_CATEGORY",
            actions: [replyAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )

        center.setNotificationCategories([category])
    }

    // MARK: - Show Notification

    func showMessageNotification(
        from address: String,
        contactName: String?,
        body: String,
        messageId: String
    ) {
        let content = UNMutableNotificationContent()
        content.title = contactName ?? address
        content.body = body
        content.sound = .default
        content.categoryIdentifier = "MESSAGE_CATEGORY"
        content.userInfo = [
            "address": address,
            "messageId": messageId
        ]

        let request = UNNotificationRequest(
            identifier: messageId,
            content: content,
            trigger: nil  // Deliver immediately
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("❌ Error showing notification: \(error)")
            }
        }

        // Update badge count
        updateBadgeCount()
    }

    // MARK: - Badge Management

    func updateBadgeCount() {
        // Badge shows total unread count
        // This will be called from MessageStore
    }

    func setBadgeCount(_ count: Int) {
        DispatchQueue.main.async {
            NSApp.dockTile.badgeLabel = count > 0 ? "\(count)" : nil
        }
    }

    func clearBadge() {
        setBadgeCount(0)
    }

    // MARK: - UNUserNotificationCenterDelegate

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        // Show notification even when app is in foreground
        return [.banner, .sound, .badge]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let userInfo = response.notification.request.content.userInfo

        if response.actionIdentifier == "REPLY_ACTION",
           let textResponse = response as? UNTextInputNotificationResponse {
            // Handle quick reply
            if let address = userInfo["address"] as? String {
                await handleQuickReply(to: address, body: textResponse.userText)
            }
        } else if response.actionIdentifier == UNNotificationDefaultActionIdentifier {
            // User tapped notification - bring app to foreground and select conversation
            if let address = userInfo["address"] as? String {
                await showConversation(address: address)
            }
        }
    }

    // MARK: - Actions

    private func handleQuickReply(to address: String, body: String) async {
        // This will be handled by MessageStore
        NotificationCenter.default.post(
            name: .quickReply,
            object: nil,
            userInfo: ["address": address, "body": body]
        )
    }

    private func showConversation(address: String) async {
        // Activate app and select conversation
        DispatchQueue.main.async {
            NSApp.activate(ignoringOtherApps: true)

            NotificationCenter.default.post(
                name: .selectConversation,
                object: nil,
                userInfo: ["address": address]
            )
        }
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let quickReply = Notification.Name("quickReply")
    static let selectConversation = Notification.Name("selectConversation")
    static let newMessageReceived = Notification.Name("newMessageReceived")
}
