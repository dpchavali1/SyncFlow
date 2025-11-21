//
//  FirebaseService.swift
//  SyncFlowMac
//
//  Firebase integration for real-time message sync
//

import Foundation
import FirebaseCore
import FirebaseDatabase
import FirebaseAuth

class FirebaseService {

    static let shared = FirebaseService()

    private let database = Database.database()
    private let auth = Auth.auth()

    private init() {}

    // MARK: - Authentication

    func signInAnonymously() async throws -> String {
        let result = try await auth.signInAnonymously()
        guard let uid = result.user.uid as String? else {
            throw FirebaseError.authFailed
        }
        return uid
    }

    func getCurrentUser() -> String? {
        return auth.currentUser?.uid
    }

    // MARK: - Pairing

    func pairWithToken(_ token: String, deviceName: String) async throws -> String {
        // Ensure we're authenticated for database access
        if auth.currentUser == nil {
            _ = try await signInAnonymously()
        }

        // Validate token exists in pending_pairings
        let pairingRef = database.reference().child("pending_pairings").child(token)

        let snapshot = try await pairingRef.getData()

        guard snapshot.exists() else {
            throw FirebaseError.invalidToken
        }

        guard let data = snapshot.value as? [String: Any] else {
            throw FirebaseError.invalidTokenData
        }

        // The Android app now includes the phone's userId in the token payload so the Mac can attach to the same account
        guard let userId = data["userId"] as? String, !userId.isEmpty else {
            throw FirebaseError.invalidTokenData
        }

        // Check if token is expired (5 minutes)
        if let expiresAt = data["expiresAt"] as? Double {
            if Date().timeIntervalSince1970 * 1000 > expiresAt {
                try await pairingRef.removeValue()
                throw FirebaseError.tokenExpired
            }
        }

        // Mark pairing complete but keep the existing userId intact
        try await pairingRef.updateChildValues([
            "completedAt": ServerValue.timestamp(),
            "platform": data["platform"] ?? "macos",
            "macDeviceName": deviceName
        ])

        // Add device to user's devices
        let deviceId = "\(Int(Date().timeIntervalSince1970 * 1000))"
        let deviceRef = database.reference()
            .child("users")
            .child(userId)
            .child("devices")
            .child(deviceId)

        try await deviceRef.setValue([
            "name": deviceName,
            "type": "macos",
            "platform": "macOS",
            "pairedAt": ServerValue.timestamp(),
            "lastSeen": ServerValue.timestamp()
        ])

        return userId
    }

    // MARK: - Messages

    func listenToMessages(userId: String, completion: @escaping ([Message]) -> Void) -> DatabaseHandle {
        let messagesRef = database.reference()
            .child("users")
            .child(userId)
            .child("messages")

        let handle = messagesRef.observe(.value) { snapshot in
            guard snapshot.exists(),
                  let messagesDict = snapshot.value as? [String: Any] else {
                completion([])
                return
            }

            var messages: [Message] = []

            for (key, value) in messagesDict {
                guard let messageData = value as? [String: Any],
                      let address = messageData["address"] as? String,
                      let body = messageData["body"] as? String,
                      let date = messageData["date"] as? Double,
                      let type = messageData["type"] as? Int else {
                    continue
                }

                let contactName = messageData["contactName"] as? String

                let message = Message(
                    id: key,
                    address: address,
                    body: body,
                    date: date,
                    type: type,
                    contactName: contactName
                )

                messages.append(message)
            }

            // Sort by date (newest first)
            messages.sort { $0.date > $1.date }

            completion(messages)
        }

        return handle
    }

    func removeMessageListener(userId: String, handle: DatabaseHandle) {
        let messagesRef = database.reference()
            .child("users")
            .child(userId)
            .child("messages")

        messagesRef.removeObserver(withHandle: handle)
    }

    // MARK: - Send Message

    func sendMessage(userId: String, to address: String, body: String) async throws {
        let outgoingRef = database.reference()
            .child("users")
            .child(userId)
            .child("outgoing_messages")
            .childByAutoId()

        try await outgoingRef.setValue([
            "address": address,
            "body": body,
            "timestamp": ServerValue.timestamp(),
            "status": "pending"
        ])
    }
}

// MARK: - Errors

enum FirebaseError: LocalizedError {
    case authFailed
    case invalidToken
    case invalidTokenData
    case tokenExpired
    case sendFailed

    var errorDescription: String? {
        switch self {
        case .authFailed:
            return "Failed to authenticate with Firebase"
        case .invalidToken:
            return "Invalid or expired pairing token"
        case .invalidTokenData:
            return "Invalid token data"
        case .tokenExpired:
            return "Pairing token has expired (5 minutes)"
        case .sendFailed:
            return "Failed to send message"
        }
    }
}
