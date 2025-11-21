//
//  Message.swift
//  SyncFlowMac
//
//  Data models for messages and conversations
//

import Foundation

// MARK: - Message Model

struct Message: Identifiable, Codable, Hashable {
    let id: String
    let address: String
    let body: String
    let date: Double
    let type: Int  // 1 = received, 2 = sent
    let contactName: String?
    var isRead: Bool = true  // Default to read (for existing messages)

    var isReceived: Bool {
        return type == 1
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

struct FirebaseMessage: Codable {
    let id: String?
    let address: String
    let body: String
    let date: Double
    let type: Int
    let contactName: String?

    func toMessage(id: String) -> Message {
        return Message(
            id: id,
            address: address,
            body: body,
            date: date,
            type: type,
            contactName: contactName
        )
    }
}
