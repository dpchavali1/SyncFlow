//
//  Message.swift
//  SyncFlowMac
//
//  Data models for messages and conversations
//

import Foundation

// MARK: - MMS Attachment

struct MmsAttachment: Identifiable, Codable, Hashable {
    let id: String
    let contentType: String
    let fileName: String?
    let url: String?
    let type: String  // "image", "video", "audio", "vcard", "file"
    let encrypted: Bool?
    let inlineData: String?  // Base64 encoded data for small files when storage is unavailable
    let isInline: Bool?

    init(
        id: String,
        contentType: String,
        fileName: String? = nil,
        url: String? = nil,
        type: String,
        encrypted: Bool? = nil,
        inlineData: String? = nil,
        isInline: Bool? = nil
    ) {
        self.id = id
        self.contentType = contentType
        self.fileName = fileName
        self.url = url
        self.type = type
        self.encrypted = encrypted
        self.inlineData = inlineData
        self.isInline = isInline
    }

    var isImage: Bool {
        return type == "image" || contentType.hasPrefix("image/")
    }

    var isVideo: Bool {
        return type == "video" || contentType.hasPrefix("video/")
    }

    var isAudio: Bool {
        return type == "audio" || contentType.hasPrefix("audio/")
    }

    var isVCard: Bool {
        return type == "vcard" || contentType.contains("vcard")
    }

    var isEncrypted: Bool {
        return encrypted ?? false
    }

    /// Returns the URL for playing/viewing the attachment
    /// For inline data, creates a temporary file and returns its URL
    var playableURL: URL? {
        if let urlString = url, let url = URL(string: urlString) {
            return url
        }

        // Handle inline base64 data
        if let base64Data = inlineData,
           let data = Data(base64Encoded: base64Data) {
            let finalData: Data
            if isEncrypted, let decrypted = try? E2EEManager.shared.decryptData(data) {
                finalData = decrypted
            } else {
                finalData = data
            }

            // Create temporary file
            let tempDir = FileManager.default.temporaryDirectory
            let fileExtension = extensionForContentType()
            let tempFile = tempDir.appendingPathComponent("\(id).\(fileExtension)")

            do {
                try finalData.write(to: tempFile)
                return tempFile
            } catch {
                print("Failed to write inline data to temp file: \(error)")
                return nil
            }
        }

        return nil
    }

    private func extensionForContentType() -> String {
        switch contentType {
        case "audio/mp4", "audio/m4a":
            return "m4a"
        case "audio/mpeg":
            return "mp3"
        case "audio/wav":
            return "wav"
        case "audio/aac":
            return "aac"
        case "image/jpeg":
            return "jpg"
        case "image/png":
            return "png"
        case "video/mp4":
            return "mp4"
        default:
            return "bin"
        }
    }
}

// MARK: - Message Model

struct Message: Identifiable, Codable, Hashable {
    let id: String
    let address: String
    let body: String
    let date: Double
    let type: Int  // 1 = received, 2 = sent
    let contactName: String?
    var isRead: Bool = true  // Default to read (for existing messages)
    var isMms: Bool = false
    var attachments: [MmsAttachment]? = nil
    var e2eeFailed: Bool = false  // E2EE encryption failed, sent as plaintext
    var e2eeFailureReason: String? = nil  // Reason for E2EE failure

    var isReceived: Bool {
        return type == 1
    }

    var hasAttachments: Bool {
        return isMms && !(attachments?.isEmpty ?? true)
    }

    var timestamp: Date {
        return Date(timeIntervalSince1970: date / 1000.0)
    }

    var formattedTime: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: timestamp)
    }

    var formattedDate: String {
        let formatter = DateFormatter()
        let calendar = Calendar.current

        if calendar.isDateInToday(timestamp) {
            return "Today"
        } else if calendar.isDateInYesterday(timestamp) {
            return "Yesterday"
        } else if calendar.isDate(timestamp, equalTo: Date(), toGranularity: .weekOfYear) {
            formatter.dateFormat = "EEEE"  // Day of week
            return formatter.string(from: timestamp)
        } else {
            formatter.dateStyle = .medium
            formatter.timeStyle = .none
            return formatter.string(from: timestamp)
        }
    }

    // Detect links, phone numbers, emails in message body
    var hasLinks: Bool {
        let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let matches = detector?.matches(in: body, range: NSRange(body.startIndex..., in: body))
        return !(matches?.isEmpty ?? true)
    }
}

// MARK: - Conversation Model

struct Conversation: Identifiable, Hashable {
    let id: String  // Use address as ID
    let address: String
    let contactName: String?
    let lastMessage: String
    let timestamp: Date
    let unreadCount: Int
    let allAddresses: [String]
    var isPinned: Bool = false
    var isArchived: Bool = false
    var isBlocked: Bool = false
    var avatarColor: String?  // Hex color for avatar

    var displayName: String {
        return contactName ?? address
    }

    var formattedTime: String {
        let formatter = DateFormatter()
        let calendar = Calendar.current

        if calendar.isDateInToday(timestamp) {
            formatter.dateStyle = .none
            formatter.timeStyle = .short
            return formatter.string(from: timestamp)
        } else if calendar.isDateInYesterday(timestamp) {
            return "Yesterday"
        } else if calendar.isDate(timestamp, equalTo: Date(), toGranularity: .weekOfYear) {
            formatter.dateFormat = "EEE"  // Short day
            return formatter.string(from: timestamp)
        } else if calendar.isDate(timestamp, equalTo: Date(), toGranularity: .year) {
            formatter.dateFormat = "MMM d"
            return formatter.string(from: timestamp)
        } else {
            formatter.dateFormat = "MM/dd/yy"
            return formatter.string(from: timestamp)
        }
    }

    var preview: String {
        if lastMessage.count > 50 {
            return String(lastMessage.prefix(50)) + "..."
        }
        return lastMessage
    }

    // Generate initials for avatar
    var initials: String {
        let name = displayName
        let components = name.split(separator: " ")
        if components.count >= 2 {
            let first = components[0].prefix(1)
            let last = components[1].prefix(1)
            return "\(first)\(last)".uppercased()
        }
        return String(name.prefix(2)).uppercased()
    }
}

// MARK: - Firebase Response Models

struct FirebaseAttachment: Codable {
    let id: Int?
    let contentType: String?
    let fileName: String?
    let url: String?
    let type: String?
    let encrypted: Bool?
    let inlineData: String?
    let isInline: Bool?

    func toMmsAttachment() -> MmsAttachment {
        return MmsAttachment(
            id: String(id ?? 0),
            contentType: contentType ?? "application/octet-stream",
            fileName: fileName,
            url: url,
            type: type ?? "file",
            encrypted: encrypted,
            inlineData: inlineData,
            isInline: isInline
        )
    }
}

struct FirebaseMessage: Codable {
    let id: String?
    let address: String
    let body: String
    let date: Double
    let type: Int
    let contactName: String?
    let isMms: Bool?
    let attachments: [FirebaseAttachment]?
    let e2eeFailed: Bool?
    let e2eeFailureReason: String?

    func toMessage(id: String) -> Message {
        let mmsAttachments = attachments?.map { $0.toMmsAttachment() }
        return Message(
            id: id,
            address: address,
            body: body,
            date: date,
            type: type,
            contactName: contactName,
            isMms: isMms ?? false,
            attachments: mmsAttachments,
            e2eeFailed: e2eeFailed ?? false,
            e2eeFailureReason: e2eeFailureReason
        )
    }
}

// MARK: - Read Receipts

struct ReadReceipt: Identifiable, Hashable {
    let id: String
    let readAt: Double
    let readBy: String
    let readDeviceName: String?
    let conversationAddress: String
    let sourceId: Int64?
    let sourceType: String?
}
