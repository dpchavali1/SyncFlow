//
//  FirebaseService.swift
//  SyncFlowMac
//
//  Firebase integration for real-time message sync
//

import Foundation
import IOKit
import CryptoKit
import FirebaseCore
import FirebaseDatabase
import FirebaseAuth
import FirebaseStorage
import FirebaseFunctions
import SyncGroupManager

class FirebaseService {

    static let shared = FirebaseService()

    fileprivate let database = Database.database()
    private let auth = Auth.auth()
    private let storage = Storage.storage()
    private let functions = Functions.functions(region: "us-central1")

    // Sync Group Manager for device pairing
    let syncGroupManager = SyncGroupManager.shared
    private var _syncGroupId: String? {
        didSet {
            if let id = _syncGroupId {
                UserDefaults.standard.set(id, forKey: "sync_group_id")
            }
        }
    }

    private init() {
        // Restore sync group ID on init
        self._syncGroupId = syncGroupManager.syncGroupId
    }

    // MARK: - Authentication

    func signInAnonymously() async throws -> String {
        let result = try await auth.signInAnonymously()
        guard let uid = result.user.uid as String? else {
            throw FirebaseError.authFailed
        }
        try? await E2EEManager.shared.initializeKeys()
        return uid
    }

    func getCurrentUser() -> String? {
        return auth.currentUser?.uid
    }

    func getDeviceId() -> String {
        if let pairedDeviceId = UserDefaults.standard.string(forKey: "syncflow_device_id"),
           !pairedDeviceId.isEmpty {
            return pairedDeviceId
        }
        return getOrCreateDeviceId()
    }

    var syncGroupId: String? {
        _syncGroupId
    }

    // MARK: - Sync Group Pairing

    /**
     * Initiate sync group pairing (called on first app launch or when not paired)
     */
    func initiateSyncGroupPairing(completion: @escaping (Result<String, Error>) -> Void) {
        // Check if already has sync group
        if let existing = syncGroupManager.syncGroupId {
            completion(.success(existing))
            return
        }

        // Create new sync group
        syncGroupManager.createSyncGroup(deviceName: "macOS") { result in
            switch result {
            case .success(let groupId):
                self._syncGroupId = groupId
                completion(.success(groupId))

            case .failure(let error):
                completion(.failure(error))
            }
        }
    }

    fileprivate func isRcsAddress(_ address: String) -> Bool {
        let lower = address.lowercased()
        return lower.contains("@rcs") ||
            lower.contains("rcs.google") ||
            lower.contains("rcs.goog") ||
            lower.hasPrefix("rcs:") ||
            lower.hasPrefix("rcs://")
    }

    // MARK: - Pairing

    func pairWithToken(_ token: String, deviceName: String) async throws -> String {
        do {
            let result = try await functions
                .httpsCallable("redeemPairingToken")
                .call([
                    "token": token,
                    "deviceName": deviceName,
                    "deviceType": "macos"
                ])

        guard let data = result.data as? [String: Any],
              let customToken = data["customToken"] as? String,
              let pairedUid = data["pairedUid"] as? String,
              let deviceId = data["deviceId"] as? String else {
            throw FirebaseError.invalidTokenData
        }

        _ = try await auth.signIn(withCustomToken: customToken)
        UserDefaults.standard.set(deviceId, forKey: "syncflow_device_id")
        try? await E2EEManager.shared.initializeKeys()

        return pairedUid
        } catch {
            let nsError = error as NSError
            if nsError.domain == FunctionsErrorDomain,
               let code = FunctionsErrorCode(rawValue: nsError.code) {
                let detail = nsError.userInfo[FunctionsErrorDetailsKey] ?? ""
                throw NSError(
                    domain: nsError.domain,
                    code: nsError.code,
                    userInfo: [
                        NSLocalizedDescriptionKey: "Pairing failed (\(code)): \(detail)"
                    ]
                )
            }
            throw error
        }
    }

    // MARK: - QR Code Pairing (New Flow: macOS generates QR, Android scans)

    /// Initiate a pairing session and get QR code data
    func initiatePairing(deviceName: String? = nil) async throws -> PairingSession {
        // Ensure we are authenticated so the pairing session is scoped to this requester.
        if auth.currentUser == nil {
            _ = try await auth.signInAnonymously()
        }
        let macDeviceName = deviceName ?? Host.current().localizedName ?? "Mac"

        let result = try await functions
            .httpsCallable("initiatePairing")
            .call([
                "deviceName": macDeviceName,
                "platform": "macos",
                "appVersion": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0"
            ])

        guard let data = result.data as? [String: Any],
              let token = data["token"] as? String,
              let qrPayload = data["qrPayload"] as? String,
              let expiresAt = data["expiresAt"] as? Double else {
            throw FirebaseError.invalidTokenData
        }

        return PairingSession(
            token: token,
            qrPayload: qrPayload,
            expiresAt: expiresAt
        )
    }

    /// Listen for pairing approval after Android user scans QR and approves
    func listenForPairingApproval(token: String, completion: @escaping (PairingStatus) -> Void) -> DatabaseHandle {
        let pairingRef = database.reference()
            .child("pending_pairings")
            .child(token)

        let handle = pairingRef.observe(.value) { [weak self] snapshot in
            guard let self = self else { return }

            guard snapshot.exists(),
                  let data = snapshot.value as? [String: Any] else {
                completion(.expired)
                return
            }

            let now = Date().timeIntervalSince1970 * 1000
            if let expiresAt = data["expiresAt"] as? Double, now > expiresAt {
                completion(.expired)
                return
            }

            guard let status = data["status"] as? String else {
                completion(.pending)
                return
            }

            switch status {
            case "approved":
                guard let customToken = data["customToken"] as? String,
                      let pairedUid = data["pairedUid"] as? String else {
                    completion(.expired)
                    return
                }

                // Sign in with the custom token
                Task {
                    do {
                        _ = try await self.auth.signIn(withCustomToken: customToken)
                        if let deviceId = data["deviceId"] as? String {
                            UserDefaults.standard.set(deviceId, forKey: "syncflow_device_id")
                        }
                        try? await E2EEManager.shared.initializeKeys()

                        await MainActor.run {
                            completion(.approved(pairedUid: pairedUid, deviceId: data["deviceId"] as? String))
                        }
                    } catch {
                        print("[Firebase] Failed to sign in with custom token: \(error)")
                        await MainActor.run {
                            completion(.expired)
                        }
                    }
                }

            case "rejected":
                completion(.rejected)

            default:
                completion(.pending)
            }
        }

        return handle
    }

    /// Remove pairing approval listener
    func removePairingApprovalListener(token: String, handle: DatabaseHandle) {
        let pairingRef = database.reference()
            .child("pending_pairings")
            .child(token)

        pairingRef.removeObserver(withHandle: handle)
    }

    func unregisterDevice(deviceId: String, completion: @escaping (Error?) -> Void) {
        let data: [String: Any] = ["deviceId": deviceId]

        functions.httpsCallable("unregisterDevice").call(data) { result, error in
            if let error = error {
                print("Failed to unregister device \(deviceId): \(error)")
                completion(error)
            } else {
                print("Successfully unregistered device: \(deviceId)")
                completion(nil)
            }
        }
    }

    /// Get or create a persistent device ID for this Mac
    private func getOrCreateDeviceId() -> String {
        let key = "SyncFlowMacDeviceId"

        // Try to get existing device ID from UserDefaults
        if let existingId = UserDefaults.standard.string(forKey: key), !existingId.isEmpty {
            return existingId
        }

        // Try to get hardware UUID
        if let hardwareUUID = getHardwareUUID() {
            // Use a hash of the hardware UUID for privacy
            let deviceId = hashedDeviceId(from: hardwareUUID)
            UserDefaults.standard.set(deviceId, forKey: key)
            return deviceId
        }

        // Fallback: generate a UUID and store it
        let newId = "mac_\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16))"
        UserDefaults.standard.set(newId, forKey: key)
        return newId
    }

    /// Get the Mac's hardware UUID
    private func getHardwareUUID() -> String? {
        let platformExpert = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("IOPlatformExpertDevice"))
        defer { IOObjectRelease(platformExpert) }

        guard platformExpert != 0,
              let serialNumberAsCFString = IORegistryEntryCreateCFProperty(platformExpert, kIOPlatformUUIDKey as CFString, kCFAllocatorDefault, 0)?.takeUnretainedValue() as? String else {
            return nil
        }

        return serialNumberAsCFString.replacingOccurrences(of: "-", with: "")
    }

    private func hashedDeviceId(from hardwareUUID: String) -> String {
        let digest = SHA256.hash(data: Data(hardwareUUID.utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return "mac_\(hex.prefix(16))"
    }

    /// Clean up old device entries for this Mac (by matching device name)
    private func cleanupOldDeviceEntries(userId: String, currentDeviceId: String, deviceName: String) async throws {
        let devicesRef = database.reference()
            .child("users")
            .child(userId)
            .child("devices")

        let snapshot = try await devicesRef.getData()

        guard let devicesDict = snapshot.value as? [String: Any] else {
            return
        }

        // Find and remove old entries with the same device name but different ID
        for (deviceId, value) in devicesDict {
            guard let deviceData = value as? [String: Any],
                  let name = deviceData["name"] as? String,
                  let platform = deviceData["platform"] as? String ?? deviceData["type"] as? String else {
                continue
            }

            // Remove old macOS entries with the same name (or generic "Desktop" name)
            let isMacDevice = platform.lowercased().contains("mac") || platform == "macos"
            let isSameName = name == deviceName || name == "Desktop" || name == Host.current().localizedName

            if deviceId != currentDeviceId && isMacDevice && isSameName {
                print("[Firebase] Removing old device entry: \(deviceId) (\(name))")
                try await devicesRef.child(deviceId).removeValue()
            }
        }
    }

    // MARK: - Messages

    func listenToMessages(userId: String, completion: @escaping ([Message]) -> Void) -> DatabaseHandle {
        let messagesRef = database.reference()
            .child("users")
            .child(userId)
            .child("messages")

        let handle = messagesRef.queryOrdered(byChild: "date").observe(.value) { [weak self] snapshot in
            guard let self = self else { return }

            guard snapshot.exists(),
                  let messagesDict = snapshot.value as? [String: Any] else {
                DispatchQueue.main.async {
                    completion([])
                }
                return
            }

            // Process messages on background thread to avoid blocking UI
            DispatchQueue.global(qos: .userInitiated).async {
                var messages: [Message] = []
                let deviceId = self.getDeviceId()

                for (key, value) in messagesDict {
                    guard let messageData = value as? [String: Any],
                          let address = messageData["address"] as? String,
                          let body = messageData["body"] as? String,
                          let date = messageData["date"] as? Double,
                          let type = messageData["type"] as? Int else {
                        continue
                    }

                    if self.isRcsAddress(address) {
                        continue
                    }

                    let contactName = messageData["contactName"] as? String
                    let isEncrypted = messageData["encrypted"] as? Bool ?? false
                    let isMms = messageData["isMms"] as? Bool ?? false

                    // Decrypt message body if encrypted
                    var decryptedBody = body
                    var decryptionFailed = false
                    if isEncrypted {
                        if let keyMap = messageData["keyMap"] as? [String: Any],
                           let nonceBase64 = messageData["nonce"] as? String,
                           let deviceEnvelope = keyMap[deviceId] as? String,
                           let nonceData = Data(base64Encoded: nonceBase64),
                           let ciphertextData = Data(base64Encoded: body) {
                            do {
                                let dataKey = try E2EEManager.shared.decryptDataKey(from: deviceEnvelope)
                                decryptedBody = try E2EEManager.shared.decryptMessageBody(
                                    dataKey: dataKey,
                                    ciphertextWithTag: ciphertextData,
                                    nonce: nonceData
                                )
                            } catch {
                                decryptionFailed = true
                            }
                        } else {
                            do {
                                decryptedBody = try E2EEManager.shared.decryptMessage(body)
                            } catch {
                                decryptionFailed = true
                            }
                        }

                        // If decryption failed, show a user-friendly message instead of garbled text
                        if decryptionFailed {
                            decryptedBody = "[🔒 Encrypted message - re-pair device to decrypt]"
                        }
                    }

                    // Parse MMS attachments if present (support array or dictionary payloads)
                    var attachments: [MmsAttachment]? = nil
                    if isMms, let attachmentsData = self.extractAttachmentList(from: messageData["attachments"]) {
                        attachments = attachmentsData.compactMap { attachData in
                            guard let id = self.parseAttachmentId(attachData["id"]),
                                  let contentType = attachData["contentType"] as? String else {
                                return nil
                            }
                            let attachType = (attachData["type"] as? String) ?? self.inferAttachmentType(from: contentType)
                            let encrypted: Bool?
                            if let encryptedBool = attachData["encrypted"] as? Bool {
                                encrypted = encryptedBool
                            } else if let encryptedString = attachData["encrypted"] as? String {
                                encrypted = (encryptedString as NSString).boolValue
                            } else {
                                encrypted = nil
                            }
                            return MmsAttachment(
                                id: id,
                                contentType: contentType,
                                fileName: attachData["fileName"] as? String,
                                url: attachData["url"] as? String,
                                type: attachType,
                                encrypted: encrypted,
                                inlineData: attachData["inlineData"] as? String,
                                isInline: attachData["isInline"] as? Bool
                            )
                        }
                    }

                    let message = Message(
                        id: key,
                        address: address,
                        body: decryptedBody,
                        date: date,
                        type: type,
                        contactName: contactName,
                        isMms: isMms,
                        attachments: attachments
                    )

                    messages.append(message)
                }

                // Sort by date (newest first)
                messages.sort { $0.date > $1.date }

                // Return to main thread for UI update
                DispatchQueue.main.async {
                    completion(messages)
                }
            }
        }

        return handle
    }

    private func parseAttachmentId(_ rawId: Any?) -> String? {
        if let idString = rawId as? String, !idString.isEmpty {
            return idString
        }
        if let number = rawId as? NSNumber {
            return number.stringValue
        }
        if let intValue = rawId as? Int {
            return String(intValue)
        }
        if let int64Value = rawId as? Int64 {
            return String(int64Value)
        }
        if let doubleValue = rawId as? Double {
            return String(Int64(doubleValue))
        }
        return nil
    }

    private func inferAttachmentType(from contentType: String) -> String {
        if contentType.hasPrefix("image/") { return "image" }
        if contentType.hasPrefix("video/") { return "video" }
        if contentType.hasPrefix("audio/") { return "audio" }
        if contentType.contains("vcard") { return "vcard" }
        return "file"
    }

    private func extractAttachmentList(from raw: Any?) -> [[String: Any]]? {
        if let list = raw as? [[String: Any]], !list.isEmpty {
            return list
        }
        if let dict = raw as? [String: Any], !dict.isEmpty {
            let sorted = dict.sorted { lhs, rhs in
                let leftIndex = Int(lhs.key) ?? Int.max
                let rightIndex = Int(rhs.key) ?? Int.max
                if leftIndex != rightIndex {
                    return leftIndex < rightIndex
                }
                return lhs.key < rhs.key
            }
            let normalized = sorted.compactMap { $0.value as? [String: Any] }
            return normalized.isEmpty ? nil : normalized
        }
        return nil
    }

    func removeMessageListener(userId: String, handle: DatabaseHandle) {
        let messagesRef = database.reference()
            .child("users")
            .child(userId)
            .child("messages")

        messagesRef.removeObserver(withHandle: handle)
    }

    // MARK: - Spam Messages

    func listenToSpamMessages(userId: String, completion: @escaping ([SpamMessage]) -> Void) -> DatabaseHandle {
        let spamRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_messages")

        let handle = spamRef.queryOrdered(byChild: "date").observe(.value) { snapshot in
            guard snapshot.exists(),
                  let spamDict = snapshot.value as? [String: Any] else {
                DispatchQueue.main.async {
                    completion([])
                }
                return
            }

            DispatchQueue.global(qos: .userInitiated).async {
                var messages: [SpamMessage] = []

                for (key, value) in spamDict {
                    guard let data = value as? [String: Any] else { continue }
                    let address = data["address"] as? String ?? ""
                    let body = data["body"] as? String ?? ""
                    let date = data["date"] as? Double ?? 0
                    let contactName = data["contactName"] as? String
                    let spamConfidence = data["spamConfidence"] as? Double ?? 0.5
                    let spamReasons = data["spamReasons"] as? String
                    let detectedAt = data["detectedAt"] as? Double ?? 0
                    let isUserMarked = data["isUserMarked"] as? Bool ?? false
                    let isRead = data["isRead"] as? Bool ?? false

                    messages.append(
                        SpamMessage(
                            id: key,
                            address: address,
                            body: body,
                            date: date,
                            contactName: contactName,
                            spamConfidence: spamConfidence,
                            spamReasons: spamReasons,
                            detectedAt: detectedAt,
                            isUserMarked: isUserMarked,
                            isRead: isRead
                        )
                    )
                }

                messages.sort { $0.date > $1.date }
                DispatchQueue.main.async {
                    completion(messages)
                }
            }
        }

        return handle
    }

    func removeSpamMessagesListener(userId: String, handle: DatabaseHandle) {
        let spamRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_messages")
        spamRef.removeObserver(withHandle: handle)
    }

    func deleteSpamMessage(userId: String, messageId: String) async throws {
        let spamRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_messages")
            .child(messageId)
        try await spamRef.removeValue()
    }

    func clearAllSpamMessages(userId: String) async throws {
        let spamRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_messages")
        try await spamRef.removeValue()
    }

    func markMessageAsSpam(userId: String, message: Message) async throws {
        let messageId = Int64(message.id) ?? Int64(message.date)
        let spamRef = database.reference()
            .child("users")
            .child(userId)
            .child("spam_messages")
            .child(String(messageId))

        let payload: [String: Any] = [
            "messageId": messageId,
            "address": message.address,
            "body": message.body,
            "date": message.date,
            "contactName": message.contactName as Any,
            "spamConfidence": 1.0,
            "spamReasons": "Marked by user",
            "detectedAt": ServerValue.timestamp(),
            "isUserMarked": true,
            "isRead": true,
            "originalMessageId": message.id
        ]

        try await spamRef.setValue(payload)
    }

    // MARK: - Send Message

    func sendMessage(userId: String, to address: String, body: String) async throws {
        let outgoingRef = database.reference()
            .child("users")
            .child(userId)
            .child("outgoing_messages")
            .childByAutoId()

        // Encrypt message body if E2EE is initialized
        var messageBody = body
        var isEncrypted = false

        if E2EEManager.shared.isInitialized {
            do {
                messageBody = try await E2EEManager.shared.encryptMessage(body, for: userId)
                isEncrypted = true
                print("[Firebase] Message encrypted for sending")
            } catch {
                print("[Firebase] Failed to encrypt message, sending plaintext: \(error)")
            }
        }

        try await outgoingRef.setValue([
            "address": address,
            "body": messageBody,
            "timestamp": ServerValue.timestamp(),
            "status": "pending",
            "encrypted": isEncrypted
        ])
    }

    // MARK: - Send MMS Message

    func sendMmsMessage(
        userId: String,
        to address: String,
        body: String,
        attachmentData: Data,
        fileName: String,
        contentType: String,
        attachmentType: String
    ) async throws {
        // Ensure user is authenticated for storage access
        var currentUser = auth.currentUser
        if currentUser == nil {
            print("[Firebase] No authenticated user, signing in anonymously for storage upload...")
            _ = try await signInAnonymously()
            currentUser = auth.currentUser
        }

        guard let user = currentUser else {
            print("[Firebase] Error: Failed to authenticate for storage upload")
            throw FirebaseError.authFailed
        }

        print("[Firebase] Uploading MMS as user: \(user.uid), isAnonymous: \(user.isAnonymous)")

        // Generate unique attachment ID
        let attachmentId = UUID().uuidString

        // Create storage path: users/{userId}/mms_attachments/{attachmentId}
        let storagePath = "users/\(userId)/mms_attachments/\(attachmentId)/\(fileName)"
        let storageRef = storage.reference().child(storagePath)

        print("[Firebase] Storage path: \(storagePath)")

        // Encrypt attachment data if E2EE is available
        var dataToUpload = attachmentData
        var isEncrypted = false

        if E2EEManager.shared.isInitialized {
            do {
                dataToUpload = try await E2EEManager.shared.encryptData(attachmentData, for: userId)
                isEncrypted = true
                print("[Firebase] MMS attachment encrypted for sending")
            } catch {
                if case E2EEError.recipientKeyNotFound = error {
                    print("[Firebase] No recipient key available, sending attachment unencrypted")
                } else {
                    print("[Firebase] Failed to encrypt attachment, sending unencrypted: \(error)")
                }
            }
        }

        let usageDecision = await UsageTracker.shared.isUploadAllowed(
            userId: userId,
            bytes: Int64(dataToUpload.count),
            countsTowardStorage: true
        )
        if !usageDecision.allowed {
            throw FirebaseError.quotaExceeded(reason: usageDecision.reason ?? "quota")
        }

        // Set metadata
        let metadata = StorageMetadata()
        metadata.contentType = contentType
        metadata.customMetadata = [
            "encrypted": String(isEncrypted),
            "originalFileName": fileName,
            "attachmentType": attachmentType
        ]

        // Upload to Firebase Storage with retry on auth failure
        var downloadURLString: String? = nil
        var useInlineData = false

        do {
            print("[Firebase] Starting upload of \(dataToUpload.count) bytes...")
            _ = try await storageRef.putDataAsync(dataToUpload, metadata: metadata)
            print("[Firebase] Upload completed successfully")

            // Get download URL
            let downloadURL = try await storageRef.downloadURL()
            downloadURLString = downloadURL.absoluteString
            print("[Firebase] Download URL obtained: \(downloadURLString?.prefix(50) ?? "nil")...")
        } catch {
            print("[Firebase] Upload failed: \(error)")

            // Check if it's an auth error and try to refresh token
            let nsError = error as NSError
            if nsError.domain == "FIRStorageErrorDomain" && nsError.code == -13021 {
                print("[Firebase] Auth error detected, refreshing token and retrying...")

                // Force token refresh
                if let user = auth.currentUser {
                    _ = try? await user.getIDTokenResult(forcingRefresh: true)
                    print("[Firebase] Token refreshed, retrying upload...")

                    do {
                        _ = try await storageRef.putDataAsync(dataToUpload, metadata: metadata)
                        let downloadURL = try await storageRef.downloadURL()
                        downloadURLString = downloadURL.absoluteString
                        print("[Firebase] Retry upload completed successfully")
                    } catch {
                        // If retry fails and file is small enough, fall back to inline data
                        if dataToUpload.count < 500_000 {
                            print("[Firebase] Storage failed, using inline base64 for small file")
                            useInlineData = true
                        } else {
                            throw error
                        }
                    }
                } else {
                    throw error
                }
            } else if dataToUpload.count < 500_000 {
                // For small files, fall back to inline base64 encoding
                print("[Firebase] Storage unavailable, using inline base64 for small file (\(dataToUpload.count) bytes)")
                useInlineData = true
            } else {
                throw error
            }
        }

        // Create the attachment metadata
        var attachment: [String: Any] = [
            "id": attachmentId,
            "contentType": contentType,
            "fileName": fileName,
            "type": attachmentType,
            "encrypted": isEncrypted,
            "size": attachmentData.count
        ]

        if let url = downloadURLString {
            attachment["url"] = url
        } else if useInlineData {
            // For small files when storage fails, include inline data
            attachment["inlineData"] = dataToUpload.base64EncodedString()
            attachment["isInline"] = true
        }

        let countsTowardStorage = downloadURLString != nil
        await UsageTracker.shared.recordUpload(
            userId: userId,
            bytes: Int64(dataToUpload.count),
            category: .mms,
            countsTowardStorage: countsTowardStorage
        )

        // Encrypt message body if E2EE is available
        var messageBody = body
        var bodyEncrypted = false

        if E2EEManager.shared.isInitialized && !body.isEmpty {
            do {
                messageBody = try await E2EEManager.shared.encryptMessage(body, for: userId)
                bodyEncrypted = true
                print("[Firebase] MMS body encrypted for sending")
            } catch {
                print("[Firebase] Failed to encrypt MMS body, sending plaintext: \(error)")
            }
        }

        // Create outgoing MMS message
        let outgoingRef = database.reference()
            .child("users")
            .child(userId)
            .child("outgoing_messages")
            .childByAutoId()

        try await outgoingRef.setValue([
            "address": address,
            "body": messageBody,
            "timestamp": ServerValue.timestamp(),
            "status": "pending",
            "encrypted": bodyEncrypted,
            "isMms": true,
            "attachments": [attachment]
        ])

        print("[Firebase] MMS message sent with attachment: \(fileName)")
    }

    // MARK: - Delivery Tracking

    enum DeliveryStatus: String {
        case pending
        case sending
        case sent
        case failed
        case delivered
    }

    struct DeliveryResult {
        let messageId: String
        let status: DeliveryStatus
        let error: String?
    }

    /// Default delivery timeout in seconds
    private let deliveryTimeoutSeconds: TimeInterval = 60

    /// Send message and return message key for tracking
    func sendMessageWithKey(userId: String, to address: String, body: String) async throws -> String? {
        let outgoingRef = database.reference()
            .child("users")
            .child(userId)
            .child("outgoing_messages")
            .childByAutoId()

        // Encrypt message body if E2EE is initialized
        var messageBody = body
        var isEncrypted = false

        if E2EEManager.shared.isInitialized {
            do {
                messageBody = try await E2EEManager.shared.encryptMessage(body, for: userId)
                isEncrypted = true
                print("[Firebase] Message encrypted for sending")
            } catch {
                print("[Firebase] Failed to encrypt message, sending plaintext: \(error)")
            }
        }

        try await outgoingRef.setValue([
            "address": address,
            "body": messageBody,
            "timestamp": ServerValue.timestamp(),
            "status": "pending",
            "encrypted": isEncrypted,
            "createdAt": Date().timeIntervalSince1970 * 1000
        ])

        return outgoingRef.key
    }

    /// Wait for message delivery with timeout
    func waitForDelivery(userId: String, messageId: String, timeout: TimeInterval? = nil) async -> DeliveryResult {
        let timeoutDuration = timeout ?? deliveryTimeoutSeconds
        let messageRef = database.reference()
            .child("users")
            .child(userId)
            .child("outgoing_messages")
            .child(messageId)

        return await withCheckedContinuation { continuation in
            var handle: DatabaseHandle?
            var resumed = false
            var timeoutTask: Task<Void, Never>?

            // Set up timeout
            timeoutTask = Task {
                try? await Task.sleep(nanoseconds: UInt64(timeoutDuration * 1_000_000_000))
                guard !resumed else { return }
                resumed = true

                if let h = handle {
                    messageRef.removeObserver(withHandle: h)
                }

                continuation.resume(returning: DeliveryResult(
                    messageId: messageId,
                    status: .failed,
                    error: "Delivery timeout - phone may be offline or not running the app"
                ))
            }

            // Listen for status changes
            handle = messageRef.observe(.value) { snapshot in
                guard !resumed else { return }

                if !snapshot.exists() {
                    // Message was deleted - means it was processed successfully
                    resumed = true
                    timeoutTask?.cancel()
                    if let h = handle {
                        messageRef.removeObserver(withHandle: h)
                    }
                    continuation.resume(returning: DeliveryResult(
                        messageId: messageId,
                        status: .sent,
                        error: nil
                    ))
                    return
                }

                guard let data = snapshot.value as? [String: Any],
                      let statusString = data["status"] as? String else {
                    return
                }

                let status = DeliveryStatus(rawValue: statusString) ?? .pending

                switch status {
                case .sent, .delivered:
                    resumed = true
                    timeoutTask?.cancel()
                    if let h = handle {
                        messageRef.removeObserver(withHandle: h)
                    }
                    continuation.resume(returning: DeliveryResult(
                        messageId: messageId,
                        status: status,
                        error: nil
                    ))

                case .failed:
                    resumed = true
                    timeoutTask?.cancel()
                    if let h = handle {
                        messageRef.removeObserver(withHandle: h)
                    }
                    let errorMsg = data["error"] as? String ?? "Message delivery failed"
                    continuation.resume(returning: DeliveryResult(
                        messageId: messageId,
                        status: .failed,
                        error: errorMsg
                    ))

                case .pending, .sending:
                    // Keep waiting
                    break
                }
            }
        }
    }

    /// Send message with full delivery tracking
    func sendMessageWithDeliveryTracking(
        userId: String,
        to address: String,
        body: String,
        onStatusChange: ((DeliveryStatus) -> Void)? = nil
    ) async throws -> DeliveryResult {
        guard let messageId = try await sendMessageWithKey(userId: userId, to: address, body: body) else {
            return DeliveryResult(messageId: "", status: .failed, error: "Failed to queue message")
        }

        onStatusChange?(.pending)

        let result = await waitForDelivery(userId: userId, messageId: messageId)
        onStatusChange?(result.status)

        return result
    }

    // MARK: - Call Functionality

    /// Request Android to make a phone call with optional SIM selection
    func requestCall(userId: String, to phoneNumber: String, contactName: String? = nil, simSubscriptionId: Int? = nil) async throws {
        let callRequestRef = database.reference()
            .child("users")
            .child(userId)
            .child("call_requests")
            .childByAutoId()

        var requestData: [String: Any] = [
            "phoneNumber": phoneNumber,
            "requestedAt": ServerValue.timestamp(),
            "status": "pending"
        ]

        if let name = contactName {
            requestData["contactName"] = name
        }

        if let simId = simSubscriptionId {
            requestData["simSubscriptionId"] = simId
        }

        try await callRequestRef.setValue(requestData)
    }

    /// Get available SIM cards from Android device
    func getAvailableSims(userId: String) async throws -> [SimInfo] {
        let simsRef = database.reference()
            .child("users")
            .child(userId)
            .child("sims")

        let snapshot = try await simsRef.getData()

        guard snapshot.exists(),
              let simsArray = snapshot.value as? [[String: Any]] else {
            return []
        }

        return simsArray.compactMap { simData in
            guard let subscriptionId = simData["subscriptionId"] as? Int,
                  let displayName = simData["displayName"] as? String,
                  let carrierName = simData["carrierName"] as? String else {
                return nil
            }

            return SimInfo(
                subscriptionId: subscriptionId,
                slotIndex: simData["slotIndex"] as? Int ?? 0,
                displayName: displayName,
                carrierName: carrierName,
                phoneNumber: simData["phoneNumber"] as? String,
                isEmbedded: simData["isEmbedded"] as? Bool ?? false,
                isActive: simData["isActive"] as? Bool ?? true
            )
        }
    }

    func watchCurrentDeviceStatus(userId: String, callback: @escaping (Bool) -> Void) -> (DatabaseReference, DatabaseHandle) {
        let deviceId = getDeviceId()
        let deviceRef = database.reference()
            .child("users")
            .child(userId)
            .child("devices")
            .child(deviceId)

        let handle = deviceRef.observe(.value) { snapshot in
            let data = snapshot.value as? [String: Any]
            let isPaired = data?["isPaired"] as? Bool ?? snapshot.exists()
            DispatchQueue.main.async {
                callback(isPaired)
            }
        }

        return (deviceRef, handle)
    }

    /// Listen for call status updates
    func observeCallRequest(requestId: String, userId: String, completion: @escaping (CallRequestStatus) -> Void) -> DatabaseHandle {
        let requestRef = database.reference()
            .child("users")
            .child(userId)
            .child("call_requests")
            .child(requestId)

        let handle = requestRef.observe(.value) { snapshot in
            guard snapshot.exists(),
                  let data = snapshot.value as? [String: Any],
                  let status = data["status"] as? String else {
                completion(.failed(error: "Request not found"))
                return
            }

            switch status {
            case "pending":
                completion(.pending)
            case "calling":
                completion(.calling)
            case "completed":
                completion(.completed)
            case "failed":
                let error = data["error"] as? String ?? "Unknown error"
                completion(.failed(error: error))
            default:
                completion(.failed(error: "Unknown status: \(status)"))
            }
        }

        return handle
    }

    /// Stop observing a call request
    func removeCallRequestObserver(requestId: String, userId: String, handle: DatabaseHandle) {
        let requestRef = database.reference()
            .child("users")
            .child(userId)
            .child("call_requests")
            .child(requestId)

        requestRef.removeObserver(withHandle: handle)
    }

    // MARK: - Contacts

    /// Get all contacts from Firebase
    func getContacts(userId: String) async throws -> [Contact] {
        let contactsRef = database.reference()
            .child("users")
            .child(userId)
            .child("contacts")

        let snapshot = try await contactsRef.getData()

        guard snapshot.exists(),
              let contactsDict = snapshot.value as? [String: [String: Any]] else {
            return []
        }

        var contacts: [Contact] = []

        for (contactId, contactData) in contactsDict {
            if let contact = Contact.from(contactData, id: contactId) {
                contacts.append(contact)
            }
        }

        // Sort by display name
        contacts.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }

        return contacts
    }

    /// Listen for contacts changes
    func listenToContacts(userId: String, completion: @escaping ([Contact]) -> Void) -> DatabaseHandle {
        let contactsRef = database.reference()
            .child("users")
            .child(userId)
            .child("contacts")

        let handle = contactsRef.observe(.value) { snapshot in
            guard snapshot.exists(),
                  let contactsDict = snapshot.value as? [String: [String: Any]] else {
                completion([])
                return
            }

            var contacts: [Contact] = []

            for (contactId, contactData) in contactsDict {
                if let contact = Contact.from(contactData, id: contactId) {
                    contacts.append(contact)
                }
            }

            // Sort by display name
            contacts.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }

            completion(contacts)
        }

        return handle
    }

    /// Remove contacts listener
    func removeContactsListener(userId: String, handle: DatabaseHandle) {
        let contactsRef = database.reference()
            .child("users")
            .child(userId)
            .child("contacts")

        contactsRef.removeObserver(withHandle: handle)
    }

    // MARK: - Desktop Contact Creation (Two-Way Sync)

    /// Create a new contact from macOS/web that will sync to Android
    func createDesktopContact(
        userId: String,
        displayName: String,
        phoneNumber: String,
        phoneType: String = "Mobile",
        email: String? = nil,
        notes: String? = nil,
        photoBase64: String? = nil
    ) async throws -> String {
        let desktopContactsRef = database.reference()
            .child("users")
            .child(userId)
            .child("desktopContacts")
            .childByAutoId()

        guard let contactId = desktopContactsRef.key else {
            throw FirebaseError.sendFailed
        }

        // Normalize phone number
        let normalizedNumber = phoneNumber.components(separatedBy: CharacterSet.decimalDigits.inverted).joined()

        var contactData: [String: Any] = [
            "displayName": displayName,
            "phoneNumber": phoneNumber,
            "normalizedNumber": normalizedNumber,
            "phoneType": phoneType,
            "createdAt": ServerValue.timestamp(),
            "updatedAt": ServerValue.timestamp(),
            "source": "macos",
            "syncedToAndroid": false
        ]

        if let email = email, !email.isEmpty {
            contactData["email"] = email
        }

        if let notes = notes, !notes.isEmpty {
            contactData["notes"] = notes
        }

        if let photoBase64 = photoBase64, !photoBase64.isEmpty {
            contactData["photoBase64"] = photoBase64
        }

        try await desktopContactsRef.setValue(contactData)
        print("[Firebase] Desktop contact created: \(displayName) with ID: \(contactId)")

        return contactId
    }

    /// Update an existing desktop-created contact
    func updateDesktopContact(
        userId: String,
        contactId: String,
        displayName: String,
        phoneNumber: String,
        phoneType: String = "Mobile",
        email: String? = nil,
        notes: String? = nil,
        photoBase64: String? = nil
    ) async throws {
        let contactRef = database.reference()
            .child("users")
            .child(userId)
            .child("desktopContacts")
            .child(contactId)

        // Normalize phone number
        let normalizedNumber = phoneNumber.components(separatedBy: CharacterSet.decimalDigits.inverted).joined()

        var updates: [String: Any] = [
            "displayName": displayName,
            "phoneNumber": phoneNumber,
            "normalizedNumber": normalizedNumber,
            "phoneType": phoneType,
            "updatedAt": ServerValue.timestamp(),
            "syncedToAndroid": false  // Reset to trigger re-sync to Android
        ]

        if let email = email {
            updates["email"] = email
        }

        if let notes = notes {
            updates["notes"] = notes
        }

        if let photoBase64 = photoBase64 {
            updates["photoBase64"] = photoBase64
        }

        try await contactRef.updateChildValues(updates)
        print("[Firebase] Desktop contact updated: \(displayName)")
    }

    /// Delete a desktop-created contact
    func deleteDesktopContact(userId: String, contactId: String) async throws {
        let contactRef = database.reference()
            .child("users")
            .child(userId)
            .child("desktopContacts")
            .child(contactId)

        try await contactRef.removeValue()
        print("[Firebase] Desktop contact deleted: \(contactId)")
    }

    /// Listen for desktop contacts (created on macOS/web)
    func listenToDesktopContacts(userId: String, completion: @escaping ([DesktopContact]) -> Void) -> DatabaseHandle {
        let contactsRef = database.reference()
            .child("users")
            .child(userId)
            .child("desktopContacts")

        let handle = contactsRef.observe(.value) { snapshot in
            guard snapshot.exists(),
                  let contactsDict = snapshot.value as? [String: [String: Any]] else {
                completion([])
                return
            }

            var contacts: [DesktopContact] = []

            for (contactId, contactData) in contactsDict {
                if let contact = DesktopContact.from(contactData, id: contactId) {
                    contacts.append(contact)
                }
            }

            // Sort by display name
            contacts.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }

            completion(contacts)
        }

        return handle
    }

    /// Remove desktop contacts listener
    func removeDesktopContactsListener(userId: String, handle: DatabaseHandle) {
        let contactsRef = database.reference()
            .child("users")
            .child(userId)
            .child("desktopContacts")

        contactsRef.removeObserver(withHandle: handle)
    }

    // MARK: - Call History

    /// Listen for call history changes
    func listenToCallHistory(userId: String, completion: @escaping ([CallHistoryEntry]) -> Void) -> DatabaseHandle {
        let callHistoryRef = database.reference()
            .child("users")
            .child(userId)
            .child("call_history")

        let handle = callHistoryRef.observe(.value) { snapshot in
            guard snapshot.exists(),
                  let callsDict = snapshot.value as? [String: [String: Any]] else {
                completion([])
                return
            }

            var calls: [CallHistoryEntry] = []

            for (callId, callData) in callsDict {
                if let call = CallHistoryEntry.from(callData, id: callId) {
                    calls.append(call)
                }
            }

            // Sort by date (newest first)
            calls.sort { $0.callDate > $1.callDate }

            completion(calls)
        }

        return handle
    }

    /// Listen for active/incoming calls
    func listenToActiveCalls(userId: String, completion: @escaping ([ActiveCall]) -> Void) -> DatabaseHandle {
        let activeCallsRef = database.reference()
            .child("users")
            .child(userId)
            .child("active_calls")

        let handle = activeCallsRef.observe(.value) { snapshot in
            guard snapshot.exists(),
                  let callsDict = snapshot.value as? [String: [String: Any]] else {
                completion([])
                return
            }

            var calls: [ActiveCall] = []
            for (callId, callData) in callsDict {
                if let call = ActiveCall.from(callData, id: callId) {
                    calls.append(call)
                }
            }

            // Sort by timestamp, most recent first
            calls.sort { $0.timestamp > $1.timestamp }
            completion(calls)
        }

        return handle
    }

    /// Remove active calls listener
    func removeActiveCallsListener(userId: String, handle: DatabaseHandle) {
        let activeCallsRef = database.reference()
            .child("users")
            .child(userId)
            .child("active_calls")

        activeCallsRef.removeObserver(withHandle: handle)
    }

    /// Send command to answer/reject call
    func sendCallCommand(userId: String, callId: String, command: String) async throws {
        let commandRef = database.reference()
            .child("users")
            .child(userId)
            .child("call_commands")
            .childByAutoId()

        let commandData: [String: Any] = [
            "command": command,
            "callId": callId,
            "processed": false,
            "timestamp": ServerValue.timestamp()
        ]

        try await commandRef.setValue(commandData)
    }

    /// Make an outgoing call
    func makeCall(userId: String, phoneNumber: String, simSubscriptionId: Int? = nil) async throws {
        let callRequestRef = database.reference()
            .child("users")
            .child(userId)
            .child("call_requests")
            .childByAutoId()

        var requestData: [String: Any] = [
            "phoneNumber": phoneNumber,
            "requestedAt": ServerValue.timestamp(),
            "status": "pending"
        ]

        if let simId = simSubscriptionId {
            requestData["simSubscriptionId"] = simId
        }

        try await callRequestRef.setValue(requestData)
    }

    // MARK: - Audio Routing (Call Transfer)

    func requestAudioRouting(userId: String, callId: String, enable: Bool) async throws {
        let routingRef = database.reference()
            .child("users")
            .child(userId)
            .child("audio_routing_requests")
            .childByAutoId()

        let payload: [String: Any] = [
            "callId": callId,
            "enable": enable,
            "processed": false,
            "timestamp": ServerValue.timestamp()
        ]

        try await routingRef.setValue(payload)
    }

    // MARK: - SyncFlow Calls

    /// Listen for incoming SyncFlow calls
    func listenForIncomingSyncFlowCalls(userId: String, completion: @escaping (SyncFlowCall) -> Void) -> DatabaseHandle {
        let callsRef = database.reference()
            .child("users")
            .child(userId)
            .child("syncflow_calls")

        let handle = callsRef.queryOrdered(byChild: "status").queryEqual(toValue: "ringing")
            .observe(.childAdded) { snapshot in
                guard let callData = snapshot.value as? [String: Any],
                      let call = SyncFlowCall.from(id: snapshot.key, dict: callData) else {
                    return
                }
                completion(call)
            }

        return handle
    }

    // MARK: - Message Reactions

    func listenToMessageReactions(userId: String, completion: @escaping ([String: String]) -> Void) -> DatabaseHandle {
        let reactionsRef = database.reference()
            .child("users")
            .child(userId)
            .child("message_reactions")

        let handle = reactionsRef.observe(.value) { snapshot in
            guard snapshot.exists() else {
                completion([:])
                return
            }

            var reactions: [String: String] = [:]
            snapshot.children.forEach { child in
                guard let childSnapshot = child as? DataSnapshot else { return }
                let messageId = childSnapshot.key
                let reaction = (childSnapshot.childSnapshot(forPath: "reaction").value as? String)
                    ?? (childSnapshot.value as? String)
                if let reaction = reaction {
                    reactions[messageId] = reaction
                }
            }

            completion(reactions)
        }

        return handle
    }

    func removeMessageReactionsListener(userId: String, handle: DatabaseHandle) {
        let reactionsRef = database.reference()
            .child("users")
            .child(userId)
            .child("message_reactions")

        reactionsRef.removeObserver(withHandle: handle)
    }

    func setMessageReaction(userId: String, messageId: String, reaction: String?) async throws {
        let reactionRef = database.reference()
            .child("users")
            .child(userId)
            .child("message_reactions")
            .child(messageId)

        if let reaction = reaction, !reaction.isEmpty {
            let payload: [String: Any] = [
                "reaction": reaction,
                "updatedAt": ServerValue.timestamp(),
                "updatedBy": "macos"
            ]
            try await reactionRef.setValue(payload)
        } else {
            try await reactionRef.removeValue()
        }
    }

    // MARK: - Read Receipts

    func listenToReadReceipts(userId: String, completion: @escaping ([String: ReadReceipt]) -> Void) -> DatabaseHandle {
        let receiptsRef = database.reference()
            .child("users")
            .child(userId)
            .child("read_receipts")

        let handle = receiptsRef.observe(.value) { snapshot in
            guard snapshot.exists() else {
                completion([:])
                return
            }

            var receipts: [String: ReadReceipt] = [:]
            snapshot.children.forEach { child in
                guard let childSnapshot = child as? DataSnapshot else { return }
                let key = childSnapshot.key

                let messageId = (childSnapshot.childSnapshot(forPath: "messageId").value as? String)
                    ?? (childSnapshot.childSnapshot(forPath: "messageId").value as? Int64).map { String($0) }
                    ?? key

                let readAt = (childSnapshot.childSnapshot(forPath: "readAt").value as? Double)
                    ?? (childSnapshot.childSnapshot(forPath: "readAt").value as? Int64).map { Double($0) }
                    ?? 0
                let readBy = childSnapshot.childSnapshot(forPath: "readBy").value as? String ?? "unknown"
                let readDeviceName = childSnapshot.childSnapshot(forPath: "readDeviceName").value as? String
                let conversationAddress = childSnapshot.childSnapshot(forPath: "conversationAddress").value as? String ?? ""
                let sourceId = childSnapshot.childSnapshot(forPath: "sourceId").value as? Int64
                let sourceType = childSnapshot.childSnapshot(forPath: "sourceType").value as? String

                receipts[messageId] = ReadReceipt(
                    id: messageId,
                    readAt: readAt,
                    readBy: readBy,
                    readDeviceName: readDeviceName,
                    conversationAddress: conversationAddress,
                    sourceId: sourceId,
                    sourceType: sourceType
                )
            }

            completion(receipts)
        }

        return handle
    }

    func removeReadReceiptsListener(userId: String, handle: DatabaseHandle) {
        let receiptsRef = database.reference()
            .child("users")
            .child(userId)
            .child("read_receipts")

        receiptsRef.removeObserver(withHandle: handle)
    }

    func markMessagesRead(
        userId: String,
        messageIds: [String],
        conversationAddress: String,
        readBy: String,
        readDeviceName: String?
    ) async throws {
        guard !messageIds.isEmpty else { return }

        var updates: [String: Any] = [:]
        for messageId in messageIds {
            updates["users/\(userId)/read_receipts/\(messageId)"] = [
                "messageId": messageId,
                "readAt": ServerValue.timestamp(),
                "readBy": readBy,
                "readDeviceName": readDeviceName as Any,
                "conversationAddress": conversationAddress
            ]
        }

        try await database.reference().updateChildValues(updates)
    }

    /// Delete a message from Firebase
    func deleteMessage(userId: String, messageId: String) async throws {
        // Delete the message from Firebase
        let messageRef = database.reference()
            .child("users")
            .child(userId)
            .child("messages")
            .child(messageId)

        try await messageRef.removeValue()

        // Also delete any reactions for this message
        let reactionRef = database.reference()
            .child("users")
            .child(userId)
            .child("message_reactions")
            .child(messageId)

        try await reactionRef.removeValue()

        print("[Firebase] Message \(messageId) deleted successfully")
    }

    /// Delete multiple messages from Firebase (and related metadata)
    func deleteMessages(userId: String, messageIds: [String]) async throws {
        guard !messageIds.isEmpty else { return }

        var updates: [String: Any] = [:]
        for messageId in messageIds {
            updates["users/\(userId)/messages/\(messageId)"] = NSNull()
            updates["users/\(userId)/message_reactions/\(messageId)"] = NSNull()
            updates["users/\(userId)/read_receipts/\(messageId)"] = NSNull()
        }

        try await database.reference().updateChildValues(updates)
        print("[Firebase] Deleted \(messageIds.count) message(s) successfully")
    }

    /// Get paired devices
    func getPairedDevices(userId: String) async throws -> [SyncFlowDevice] {
        let devicesRef = database.reference()
            .child("users")
            .child(userId)
            .child("devices")

        let snapshot = try await devicesRef.getData()
        guard let devicesData = snapshot.value as? [String: [String: Any]] else {
            return []
        }

        return devicesData.compactMap { (id, dict) in
            SyncFlowDevice.from(id: id, dict: dict)
        }.filter { $0.isAndroid && $0.online }
    }

    // MARK: - Find My Phone

    /// Ring the phone to help locate it
    func ringPhone(userId: String) async throws {
        let findRef = database.reference()
            .child("users")
            .child(userId)
            .child("find_my_phone")
            .childByAutoId()

        let requestData: [String: Any] = [
            "action": "ring",
            "timestamp": ServerValue.timestamp(),
            "source": "macos",
            "status": "pending"
        ]

        try await findRef.setValue(requestData)
        print("[Firebase] Find My Phone: Ring request sent")
    }

    /// Stop ringing the phone
    func stopRingingPhone(userId: String) async throws {
        let findRef = database.reference()
            .child("users")
            .child(userId)
            .child("find_my_phone")
            .childByAutoId()

        let requestData: [String: Any] = [
            "action": "stop",
            "timestamp": ServerValue.timestamp(),
            "source": "macos",
            "status": "pending"
        ]

        try await findRef.setValue(requestData)
        print("[Firebase] Find My Phone: Stop request sent")
    }

    // MARK: - Link Sharing

    /// Send a URL to the phone to open in browser
    func sendLink(userId: String, url: String, title: String? = nil) async throws {
        let linkRef = database.reference()
            .child("users")
            .child(userId)
            .child("shared_links")
            .childByAutoId()

        var linkData: [String: Any] = [
            "url": url,
            "timestamp": ServerValue.timestamp(),
            "source": "macos",
            "status": "pending"
        ]

        if let title = title {
            linkData["title"] = title
        }

        try await linkRef.setValue(linkData)
        print("[Firebase] Link shared to phone: \(url)")
    }
}

extension FirebaseService {
    /// Optimized message listener with batched updates for macOS
    func listenToMessagesOptimized(
        userId: String,
        completion: @escaping ([Message]) -> Void
    ) -> DatabaseHandle {
        let messagesRef = database.reference()
            .child("users")
            .child(userId)
            .child("messages")

        return PerformanceOptimizer.shared.createBatchedListener(
            for: messagesRef.queryOrdered(byChild: "date"),
            transform: { [weak self] snapshots in
                guard let self = self else { return [] }
                return self.processMessageSnapshots(snapshots)
            },
            update: completion
        )
    }

    private func processMessageSnapshots(_ snapshots: [DataSnapshot]) -> [Message] {
        var messages: [Message] = []

        for snapshot in snapshots {
            guard snapshot.exists(),
                  let messageData = snapshot.value as? [String: Any],
                  let address = messageData["address"] as? String,
                  let body = messageData["body"] as? String,
                  let date = messageData["date"] as? Double,
                  let type = messageData["type"] as? Int else {
                continue
            }

            if isRcsAddress(address) { continue }

            let contactName = messageData["contactName"] as? String
            let isEncrypted = messageData["encrypted"] as? Bool ?? false
            let isMms = messageData["isMms"] as? Bool ?? false

            let message = Message(
                id: snapshot.key,
                address: address,
                body: isEncrypted ? "[🔒 Encrypted message]" : body,
                date: date,
                type: type,
                contactName: contactName,
                isMms: isMms,
                attachments: nil
            )

            messages.append(message)
        }

        messages.sort { $0.date > $1.date }
        return messages
    }
}

// MARK: - Pairing Session

struct PairingSession {
    let token: String
    let qrPayload: String
    let expiresAt: Double

    var expiresAtDate: Date {
        Date(timeIntervalSince1970: expiresAt / 1000)
    }

    var timeRemaining: TimeInterval {
        max(0, expiresAtDate.timeIntervalSinceNow)
    }

    var isExpired: Bool {
        timeRemaining <= 0
    }
}

// MARK: - Pairing Status

enum PairingStatus {
    case pending
    case approved(pairedUid: String, deviceId: String?)
    case rejected
    case expired
}

// MARK: - SIM Info

struct SimInfo: Identifiable, Hashable {
    let subscriptionId: Int
    let slotIndex: Int
    let displayName: String
    let carrierName: String
    let phoneNumber: String?
    let isEmbedded: Bool
    let isActive: Bool

    var id: Int { subscriptionId }

    var formattedDisplayName: String {
        var name = displayName
        if let number = phoneNumber, !number.isEmpty {
            name += " (\(number))"
        } else {
            name += " - \(carrierName)"
        }
        if isEmbedded {
            name += " [eSIM]"
        }
        return name
    }
}

// MARK: - Call Request Status

enum CallRequestStatus {
    case pending
    case calling
    case completed
    case failed(error: String)

    var description: String {
        switch self {
        case .pending:
            return "Sending request to phone..."
        case .calling:
            return "Phone is dialing..."
        case .completed:
            return "Call initiated successfully"
        case .failed(let error):
            return "Failed: \(error)"
        }
    }
}

// MARK: - Errors

enum FirebaseError: LocalizedError {
    case authFailed
    case invalidToken
    case invalidTokenData
    case tokenExpired
    case sendFailed
    case quotaExceeded(reason: String)

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
        case .quotaExceeded(let reason):
            switch reason {
            case "trial_expired":
                return "Free trial expired. Upgrade to keep sending MMS."
            case "monthly_quota":
                return "Monthly upload limit reached. Try again next month or upgrade."
            case "storage_quota":
                return "Storage limit reached. Free up space or upgrade your plan."
            default:
                 return "Upload limit reached. Please try again later."
             }
         }
     }
}
