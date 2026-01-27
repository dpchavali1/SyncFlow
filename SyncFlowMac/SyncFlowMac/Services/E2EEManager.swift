//
//  E2EEManager.swift
//  SyncFlowMac
//
//  End-to-end encryption manager using CryptoKit
//  Compatible with Android's Tink ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM
//

import Foundation
import CryptoKit
import FirebaseDatabase
import FirebaseAuth

class E2EEManager {

    static let shared = E2EEManager()

    private let database = Database.database()
    private let auth = Auth.auth()

    private let keychainService = "com.syncflow.e2ee"
    private let privateKeyTag = "e2ee_private_key"
    private let publicKeyTag = "e2ee_public_key"

    private var privateKey: P256.KeyAgreement.PrivateKey?
    private var publicKey: P256.KeyAgreement.PublicKey?

    private let contextInfo = "SyncFlow-E2EE-v1".data(using: .utf8)!
    private let contextInfoV2 = "SyncFlow-E2EE-v2".data(using: .utf8)!

    private init() {
        loadExistingKeys()
    }

    // MARK: - Key Management

    /// Initialize E2EE keys - generates new keys if not exists
    func initializeKeys() async throws {
        if privateKey != nil {
            try await publishDevicePublicKey()
            return
        }

        // Generate new key pair
        let newPrivateKey = P256.KeyAgreement.PrivateKey()
        let newPublicKey = newPrivateKey.publicKey

        // Store keys in Keychain
        try storePrivateKey(newPrivateKey)
        try storePublicKey(newPublicKey)

        self.privateKey = newPrivateKey
        self.publicKey = newPublicKey

        // Publish public key to Firebase
        try await publishPublicKeyToFirebase()
        try await publishDevicePublicKey()

    }

    /// Check if E2EE is initialized
    var isInitialized: Bool {
        return privateKey != nil
    }

    /// Load existing keys from Keychain
    private func loadExistingKeys() {
        guard let privateKeyData = loadFromKeychain(tag: privateKeyTag) else {
            return
        }

        do {
            let rawRepresentation = privateKeyData
            privateKey = try P256.KeyAgreement.PrivateKey(rawRepresentation: rawRepresentation)
            publicKey = privateKey?.publicKey
        } catch {
            print("[E2EE] Error loading keys: \(error)")
            // Clear corrupt keys
            deleteFromKeychain(tag: privateKeyTag)
            deleteFromKeychain(tag: publicKeyTag)
        }
    }

    // MARK: - Keychain Operations

    private func storePrivateKey(_ key: P256.KeyAgreement.PrivateKey) throws {
        let rawKey = key.rawRepresentation
        try storeInKeychain(data: rawKey, tag: privateKeyTag)
    }

    private func storePublicKey(_ key: P256.KeyAgreement.PublicKey) throws {
        let rawKey = key.rawRepresentation
        try storeInKeychain(data: rawKey, tag: publicKeyTag)
    }

    private func storeInKeychain(data: Data, tag: String) throws {
        // Delete existing item first
        deleteFromKeychain(tag: tag)

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: tag,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlocked
        ]

        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw E2EEError.keychainError(status)
        }
    }

    private func loadFromKeychain(tag: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: tag,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess else {
            return nil
        }

        return result as? Data
    }

    private func deleteFromKeychain(tag: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: tag
        ]

        SecItemDelete(query as CFDictionary)
    }

    // MARK: - Firebase Operations

    /// Publish public key to Firebase for key exchange
    private func publishPublicKeyToFirebase() async throws {
        guard let uid = auth.currentUser?.uid,
              let publicKey = publicKey else {
            throw E2EEError.notAuthenticated
        }

        // Convert public key to Base64 for storage
        let publicKeyBase64 = publicKey.rawRepresentation.base64EncodedString()

        let keyData: [String: Any] = [
            "publicKey": publicKeyBase64,
            "algorithm": "ECIES_P256_CryptoKit",
            "version": 1,
            "platform": "macos",
            "timestamp": ServerValue.timestamp()
        ]

        let keyRef = database.reference()
            .child("e2ee_keys")
            .child(uid)

        // IMPORTANT: Use updateChildValues instead of setValue to preserve per-device keys
        // setValue would delete all child nodes including per-device key entries
        try await keyRef.updateChildValues(keyData)
    }

    func publishDevicePublicKey() async throws {
        guard let uid = auth.currentUser?.uid,
              let publicKey = publicKey else {
            throw E2EEError.notAuthenticated
        }

        let deviceId = FirebaseService.shared.getDeviceId()
        let publicKeyX963 = publicKey.x963Representation.base64EncodedString()

        let keyData: [String: Any] = [
            "publicKeyX963": publicKeyX963,
            "format": "x963",
            "keyVersion": 2,
            "platform": "macos",
            "timestamp": ServerValue.timestamp()
        ]

        let keyRef = database.reference()
            .child("e2ee_keys")
            .child(uid)
            .child(deviceId)

        try await keyRef.setValue(keyData)
    }

    /// Get recipient's public key from Firebase
    func getPublicKey(for uid: String) async throws -> P256.KeyAgreement.PublicKey? {
        let keyRef = database.reference()
            .child("e2ee_keys")
            .child(uid)

        let snapshot = try await keyRef.getData()

        guard snapshot.exists(),
              let keyData = snapshot.value as? [String: Any],
              let publicKeyString = keyData["publicKey"] as? String else {
            return nil
        }

        // Check if it's Tink format (JSON) or raw Base64
        if publicKeyString.hasPrefix("{") {
            // Tink JSON format - need to parse
            return try parseTinkPublicKey(publicKeyString)
        } else {
            // Raw Base64 format
            guard let publicKeyData = Data(base64Encoded: publicKeyString) else {
                throw E2EEError.invalidPublicKey
            }
            return try P256.KeyAgreement.PublicKey(rawRepresentation: publicKeyData)
        }
    }

    /// Parse Tink JSON format public key
    private func parseTinkPublicKey(_ jsonString: String) throws -> P256.KeyAgreement.PublicKey {
        // Tink stores keys in a specific JSON format
        // For now, we'll need to extract the raw key bytes
        // This is a simplified parser - in production, use proper Tink SDK

        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let keyArray = json["key"] as? [[String: Any]],
              let firstKey = keyArray.first,
              let keyData = firstKey["keyData"] as? [String: Any],
              let value = keyData["value"] as? String,
              let keyBytes = Data(base64Encoded: value) else {
            throw E2EEError.invalidPublicKey
        }

        // Tink ECIES key format: skip first few bytes (metadata) to get raw P256 key
        // The exact format depends on Tink version, this may need adjustment
        let rawKeyStart = keyBytes.count - 65  // P256 uncompressed public key is 65 bytes
        if rawKeyStart >= 0 {
            let rawKey = keyBytes.suffix(65)
            return try P256.KeyAgreement.PublicKey(x963Representation: rawKey)
        }

        throw E2EEError.invalidPublicKey
    }

    // MARK: - Encryption/Decryption

    /// Encrypt a message for a recipient
    func encryptMessage(_ message: String, for recipientUid: String) async throws -> String {
        guard let privateKey = privateKey else {
            throw E2EEError.notInitialized
        }

        guard let recipientPublicKey = try await getPublicKey(for: recipientUid) else {
            throw E2EEError.recipientKeyNotFound
        }

        // Perform ECDH key agreement
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: recipientPublicKey)

        // Derive symmetric key using HKDF
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfo,
            outputByteCount: 32
        )

        // Encrypt with AES-GCM
        guard let messageData = message.data(using: .utf8) else {
            throw E2EEError.encodingError
        }

        let sealedBox = try AES.GCM.seal(messageData, using: symmetricKey)

        guard let combined = sealedBox.combined else {
            throw E2EEError.encryptionFailed
        }

        // Include our ephemeral public key in the output for the recipient
        var output = Data()
        output.append(publicKey!.rawRepresentation)  // 32 bytes
        output.append(combined)

        return output.base64EncodedString()
    }

    /// Decrypt a message from a sender
    /// Supports both CryptoKit format and Android Tink ECIES format
    func decryptMessage(_ encryptedMessage: String) throws -> String {
        guard let privateKey = privateKey else {
            throw E2EEError.notInitialized
        }

        guard let encryptedData = Data(base64Encoded: encryptedMessage) else {
            throw E2EEError.decodingError
        }

        // Try Tink ECIES format first (Android)
        // Tink ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM format:
        // - 4 bytes: key ID (we skip)
        // - 1 byte: length of ephemeral public key
        // - 65 bytes: ephemeral public key (uncompressed P256)
        // - remaining: AES-GCM ciphertext (nonce + ciphertext + tag)
        if encryptedData.count > 70 {
            do {
                return try decryptTinkMessage(encryptedData)
            } catch {
                print("[E2EE] Tink decryption failed, trying CryptoKit format: \(error)")
            }
        }

        // Fall back to CryptoKit format
        // Format: 32 bytes public key + AES-GCM ciphertext
        guard encryptedData.count > 32 else {
            throw E2EEError.invalidCiphertext
        }

        let senderPublicKeyData = encryptedData.prefix(32)
        let ciphertext = encryptedData.dropFirst(32)

        // Reconstruct sender's public key
        let senderPublicKey = try P256.KeyAgreement.PublicKey(rawRepresentation: senderPublicKeyData)

        // Perform ECDH key agreement
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: senderPublicKey)

        // Derive symmetric key using HKDF
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfo,
            outputByteCount: 32
        )

        // Decrypt with AES-GCM
        let sealedBox = try AES.GCM.SealedBox(combined: ciphertext)
        let decryptedData = try AES.GCM.open(sealedBox, using: symmetricKey)

        guard let decryptedMessage = String(data: decryptedData, encoding: .utf8) else {
            throw E2EEError.decodingError
        }

        return decryptedMessage
    }

    func decryptDataKey(from envelope: String) throws -> Data {
        guard let privateKey = privateKey else {
            throw E2EEError.notInitialized
        }

        let payload = envelope.replacingOccurrences(of: "v2:", with: "")
        guard let bytes = Data(base64Encoded: payload), bytes.count > 65 + 12 + 16 else {
            throw E2EEError.invalidCiphertext
        }

        let ephemeralPublicKeyData = bytes.prefix(65)
        let nonce = bytes.dropFirst(65).prefix(12)
        let ciphertextAndTag = bytes.dropFirst(77)

        let ephemeralPublicKey = try P256.KeyAgreement.PublicKey(x963Representation: ephemeralPublicKeyData)
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: ephemeralPublicKey)

        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfoV2,
            outputByteCount: 32
        )

        let sealedBox = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonce),
            ciphertext: ciphertextAndTag.dropLast(16),
            tag: ciphertextAndTag.suffix(16)
        )

        return try AES.GCM.open(sealedBox, using: symmetricKey)
    }

    func decryptMessageBody(dataKey: Data, ciphertextWithTag: Data, nonce: Data) throws -> String {
        let sealedBox = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonce),
            ciphertext: ciphertextWithTag.dropLast(16),
            tag: ciphertextWithTag.suffix(16)
        )

        let decrypted = try AES.GCM.open(sealedBox, using: SymmetricKey(data: dataKey))
        guard let message = String(data: decrypted, encoding: .utf8) else {
            throw E2EEError.decodingError
        }
        return message
    }

    /// Decrypt a message encrypted with Android's Tink ECIES
    private func decryptTinkMessage(_ encryptedData: Data) throws -> String {
        guard let privateKey = privateKey else {
            throw E2EEError.notInitialized
        }

        // Tink ECIES format:
        // - 4 bytes: key ID prefix
        // - 1 byte: public key length (should be 65 for uncompressed P256)
        // - 65 bytes: ephemeral public key (uncompressed format: 0x04 + X + Y)
        // - 12 bytes: AES-GCM nonce/IV
        // - N bytes: ciphertext
        // - 16 bytes: AES-GCM tag

        guard encryptedData.count > 4 + 1 + 65 + 12 + 16 else {
            throw E2EEError.invalidCiphertext
        }

        var offset = 4  // Skip key ID prefix

        // Read public key length
        let pubKeyLength = Int(encryptedData[offset])
        offset += 1

        guard pubKeyLength == 65 else {
            throw E2EEError.invalidPublicKey
        }

        // Extract ephemeral public key (uncompressed P256: 0x04 + 32 bytes X + 32 bytes Y)
        let ephemeralPublicKeyData = encryptedData[offset..<(offset + 65)]
        offset += 65

        let ephemeralPublicKey = try P256.KeyAgreement.PublicKey(x963Representation: ephemeralPublicKeyData)

        // Perform ECDH key agreement
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: ephemeralPublicKey)

        // Tink uses HKDF with:
        // - Hash: SHA256
        // - Salt: empty (all zeros)
        // - Info: "SyncFlow-E2EE-v1" (our context) - but Tink might use empty
        // - Output: 32 bytes for AES-256 or 16 bytes for AES-128

        // Try with our context info first
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfo,
            outputByteCount: 16  // AES-128 as per Tink template
        )

        // Extract nonce (12 bytes) and ciphertext+tag
        let nonce = encryptedData[offset..<(offset + 12)]
        offset += 12

        let ciphertextAndTag = encryptedData[offset...]

        // Construct AES-GCM sealed box
        let sealedBox = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonce),
            ciphertext: ciphertextAndTag.dropLast(16),
            tag: ciphertextAndTag.suffix(16)
        )

        let decryptedData = try AES.GCM.open(sealedBox, using: symmetricKey)

        guard let decryptedMessage = String(data: decryptedData, encoding: .utf8) else {
            throw E2EEError.decodingError
        }

        return decryptedMessage
    }

    /// Get my public key as Base64 string
    func getMyPublicKey() -> String? {
        return publicKey?.rawRepresentation.base64EncodedString()
    }

    // MARK: - Binary Data Encryption/Decryption (for MMS attachments)

    /// Encrypt binary data (for MMS attachments)
    func encryptData(_ data: Data, for recipientUid: String) async throws -> Data {
        guard let privateKey = privateKey,
              let publicKey = publicKey else {
            throw E2EEError.notInitialized
        }

        guard let recipientPublicKey = try await getPublicKey(for: recipientUid) else {
            throw E2EEError.recipientKeyNotFound
        }

        // Perform ECDH key agreement
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: recipientPublicKey)

        // Derive symmetric key using HKDF
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfo,
            outputByteCount: 32
        )

        // Encrypt with AES-GCM
        let sealedBox = try AES.GCM.seal(data, using: symmetricKey)

        guard let combined = sealedBox.combined else {
            throw E2EEError.encryptionFailed
        }

        // Include our public key in the output for the recipient to decrypt
        var output = Data()
        output.append(publicKey.rawRepresentation)  // 32 bytes
        output.append(combined)

        return output
    }

    /// Decrypt binary data (for MMS attachments)
    /// Supports both CryptoKit format and Android Tink ECIES format
    func decryptData(_ encryptedData: Data) throws -> Data {
        guard let privateKey = privateKey else {
            throw E2EEError.notInitialized
        }

        // Try Tink ECIES format first (Android)
        if encryptedData.count > 70 {
            do {
                return try decryptTinkData(encryptedData)
            } catch {
                print("[E2EE] Tink data decryption failed, trying CryptoKit format: \(error)")
            }
        }

        // Fall back to CryptoKit format
        guard encryptedData.count > 32 else {
            throw E2EEError.invalidCiphertext
        }

        let senderPublicKeyData = encryptedData.prefix(32)
        let ciphertext = encryptedData.dropFirst(32)

        let senderPublicKey = try P256.KeyAgreement.PublicKey(rawRepresentation: senderPublicKeyData)

        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: senderPublicKey)

        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfo,
            outputByteCount: 32
        )

        let sealedBox = try AES.GCM.SealedBox(combined: ciphertext)
        return try AES.GCM.open(sealedBox, using: symmetricKey)
    }

    /// Decrypt binary data encrypted with Android's Tink ECIES
    private func decryptTinkData(_ encryptedData: Data) throws -> Data {
        guard let privateKey = privateKey else {
            throw E2EEError.notInitialized
        }

        guard encryptedData.count > 4 + 1 + 65 + 12 + 16 else {
            throw E2EEError.invalidCiphertext
        }

        var offset = 4  // Skip key ID prefix

        let pubKeyLength = Int(encryptedData[offset])
        offset += 1

        guard pubKeyLength == 65 else {
            throw E2EEError.invalidPublicKey
        }

        let ephemeralPublicKeyData = encryptedData[offset..<(offset + 65)]
        offset += 65

        let ephemeralPublicKey = try P256.KeyAgreement.PublicKey(x963Representation: ephemeralPublicKeyData)

        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: ephemeralPublicKey)

        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: contextInfo,
            outputByteCount: 16  // AES-128 as per Tink template
        )

        let nonce = encryptedData[offset..<(offset + 12)]
        offset += 12

        let ciphertextAndTag = encryptedData[offset...]

        let sealedBox = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonce),
            ciphertext: ciphertextAndTag.dropLast(16),
            tag: ciphertextAndTag.suffix(16)
        )

        return try AES.GCM.open(sealedBox, using: symmetricKey)
    }

    /// Clear all E2EE keys (for logout/reset)
    func clearKeys() {
        deleteFromKeychain(tag: privateKeyTag)
        deleteFromKeychain(tag: publicKeyTag)
        privateKey = nil
        publicKey = nil

        // Remove from Firebase
        if let uid = auth.currentUser?.uid {
            database.reference().child("e2ee_keys").child(uid).removeValue()
        }

    }
}

// MARK: - E2EE Errors

enum E2EEError: LocalizedError {
    case notInitialized
    case notAuthenticated
    case keychainError(OSStatus)
    case invalidPublicKey
    case recipientKeyNotFound
    case encodingError
    case decodingError
    case encryptionFailed
    case decryptionFailed
    case invalidCiphertext

    var errorDescription: String? {
        switch self {
        case .notInitialized:
            return "E2EE not initialized"
        case .notAuthenticated:
            return "Not authenticated with Firebase"
        case .keychainError(let status):
            return "Keychain error: \(status)"
        case .invalidPublicKey:
            return "Invalid public key format"
        case .recipientKeyNotFound:
            return "Recipient's public key not found"
        case .encodingError:
            return "Message encoding error"
        case .decodingError:
            return "Message decoding error"
        case .encryptionFailed:
            return "Encryption failed"
        case .decryptionFailed:
            return "Decryption failed"
        case .invalidCiphertext:
            return "Invalid ciphertext"
        }
    }
}
