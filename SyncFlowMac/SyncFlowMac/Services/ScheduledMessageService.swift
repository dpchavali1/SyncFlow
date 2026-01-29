//
//  ScheduledMessageService.swift
//  SyncFlowMac
//
//  Service to schedule SMS messages to be sent later via Android
//

import Foundation
import Combine
import FirebaseDatabase

class ScheduledMessageService: ObservableObject {
    static let shared = ScheduledMessageService()

    @Published var scheduledMessages: [ScheduledMessage] = []
    @Published var statusMessage: String?

    private let database = Database.database()
    private var messagesHandle: DatabaseHandle?
    private var currentUserId: String?

    private init() {}

    // MARK: - ScheduledMessage Model

    struct ScheduledMessage: Identifiable, Hashable {
        let id: String
        let recipientNumber: String
        let recipientName: String?
        let message: String
        let scheduledTime: Date
        let createdAt: Date
        let status: Status
        let sentAt: Date?
        let errorMessage: String?

        enum Status: String {
            case pending
            case sent
            case failed
            case cancelled
        }
    }

    /// Start listening for scheduled messages
    func startListening(userId: String) {
        currentUserId = userId

        let messagesRef = database.reference()
            .child("users")
            .child(userId)
            .child("scheduled_messages")
            .queryOrdered(byChild: "scheduledTime")

        messagesHandle = messagesRef.observe(.value) { [weak self] snapshot in
            guard let self = self else { return }

            var messages: [ScheduledMessage] = []

            for child in snapshot.children {
                guard let childSnapshot = child as? DataSnapshot,
                      let data = childSnapshot.value as? [String: Any] else { continue }

                if let msg = self.parseMessage(id: childSnapshot.key, data: data) {
                    messages.append(msg)
                }
            }

            // Sort by scheduled time
            messages.sort { $0.scheduledTime < $1.scheduledTime }

            DispatchQueue.main.async {
                self.scheduledMessages = messages
            }
        }

    }

    /// Stop listening
    func stopListening() {
        guard let userId = currentUserId, let handle = messagesHandle else { return }

        database.reference()
            .child("users")
            .child(userId)
            .child("scheduled_messages")
            .removeObserver(withHandle: handle)

        messagesHandle = nil
        currentUserId = nil
        scheduledMessages = []
    }

    // MARK: - Schedule Messages

    /// Schedule a new message
    func scheduleMessage(
        recipientNumber: String,
        recipientName: String?,
        message: String,
        scheduledTime: Date,
        simSlot: Int? = nil
    ) async throws {
        guard let userId = currentUserId else {
            throw ScheduledMessageError.notAuthenticated
        }

        database.goOnline()

        let messageId = UUID().uuidString
        let messagesRef = database.reference()
            .child("users")
            .child(userId)
            .child("scheduled_messages")
            .child(messageId)

        var messageData: [String: Any] = [
            "recipientNumber": recipientNumber,
            "message": message,
            "scheduledTime": scheduledTime.timeIntervalSince1970 * 1000, // Convert to milliseconds
            "createdAt": ServerValue.timestamp(),
            "status": "pending"
        ]

        if let name = recipientName {
            messageData["recipientName"] = name
        }

        if let slot = simSlot {
            messageData["simSlot"] = slot
        }

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            messagesRef.setValue(messageData) { error, _ in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    /// Cancel a scheduled message
    func cancelMessage(_ messageId: String) async throws {
        guard let userId = currentUserId else {
            throw ScheduledMessageError.notAuthenticated
        }

        database.goOnline()

        let messageRef = database.reference()
            .child("users")
            .child(userId)
            .child("scheduled_messages")
            .child(messageId)

        let updates: [String: Any] = [
            "status": "cancelled",
            "updatedAt": ServerValue.timestamp()
        ]

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            messageRef.updateChildValues(updates) { error, _ in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    /// Delete a scheduled message (removes from history)
    func deleteMessage(_ messageId: String) async throws {
        guard let userId = currentUserId else {
            throw ScheduledMessageError.notAuthenticated
        }

        database.goOnline()

        let messageRef = database.reference()
            .child("users")
            .child(userId)
            .child("scheduled_messages")
            .child(messageId)

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            messageRef.removeValue { error, _ in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    /// Update a scheduled message
    func updateMessage(
        _ messageId: String,
        newMessage: String? = nil,
        newScheduledTime: Date? = nil
    ) async throws {
        guard let userId = currentUserId else {
            throw ScheduledMessageError.notAuthenticated
        }

        database.goOnline()

        let messageRef = database.reference()
            .child("users")
            .child(userId)
            .child("scheduled_messages")
            .child(messageId)

        var updates: [String: Any] = [
            "updatedAt": ServerValue.timestamp()
        ]

        if let message = newMessage {
            updates["message"] = message
        }

        if let time = newScheduledTime {
            updates["scheduledTime"] = time.timeIntervalSince1970 * 1000
        }

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            messageRef.updateChildValues(updates) { error, _ in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    print("ScheduledMessageService: Message updated: \(messageId)")
                    continuation.resume()
                }
            }
        }
    }

    // MARK: - Computed Properties

    /// Get pending messages only
    var pendingMessages: [ScheduledMessage] {
        scheduledMessages.filter { $0.status == .pending }
    }

    /// Get sent messages history
    var sentMessages: [ScheduledMessage] {
        scheduledMessages.filter { $0.status == .sent }
    }

    /// Count of pending messages
    var pendingCount: Int {
        pendingMessages.count
    }

    // MARK: - Private Helpers

    private func parseMessage(id: String, data: [String: Any]) -> ScheduledMessage? {
        guard let recipientNumber = data["recipientNumber"] as? String,
              let message = data["message"] as? String,
              let scheduledTimeMs = data["scheduledTime"] as? Double,
              let statusString = data["status"] as? String else {
            return nil
        }

        let status = ScheduledMessage.Status(rawValue: statusString) ?? .pending
        let scheduledTime = Date(timeIntervalSince1970: scheduledTimeMs / 1000)

        var createdAt = Date()
        if let createdAtMs = data["createdAt"] as? Double {
            createdAt = Date(timeIntervalSince1970: createdAtMs / 1000)
        }

        var sentAt: Date? = nil
        if let sentAtMs = data["sentAt"] as? Double {
            sentAt = Date(timeIntervalSince1970: sentAtMs / 1000)
        }

        return ScheduledMessage(
            id: id,
            recipientNumber: recipientNumber,
            recipientName: data["recipientName"] as? String,
            message: message,
            scheduledTime: scheduledTime,
            createdAt: createdAt,
            status: status,
            sentAt: sentAt,
            errorMessage: data["errorMessage"] as? String
        )
    }

    // MARK: - Errors

    enum ScheduledMessageError: Error, LocalizedError {
        case notAuthenticated
        case invalidData

        var errorDescription: String? {
            switch self {
            case .notAuthenticated:
                return "User not authenticated"
            case .invalidData:
                return "Invalid message data"
            }
        }
    }

    /// Pause all scheduled messages temporarily
    func pauseAll() {
        // This would cancel all pending scheduled messages
    }
}
