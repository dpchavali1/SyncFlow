//
//  Contact.swift
//  SyncFlowMac
//
//  Contact model for displaying Android contacts on macOS
//

import Foundation

struct Contact: Identifiable, Hashable {
    let id: String
    let displayName: String
    let phoneNumber: String
    let normalizedNumber: String
    let phoneType: String
    let photoUri: String?
    let photoBase64: String?
    let syncedAt: Double?

    var initials: String {
        let components = displayName.components(separatedBy: " ")
        if components.count >= 2 {
            let first = components.first?.prefix(1).uppercased() ?? ""
            let last = components.last?.prefix(1).uppercased() ?? ""
            return "\(first)\(last)"
        } else {
            return String(displayName.prefix(2)).uppercased()
        }
    }

    var formattedPhoneNumber: String {
        // Format phone number for display
        if phoneNumber.starts(with: "+") {
            return phoneNumber
        }
        // Add basic formatting for US numbers
        let digits = phoneNumber.filter { $0.isNumber }
        if digits.count == 10 {
            let areaCode = digits.prefix(3)
            let middle = digits.dropFirst(3).prefix(3)
            let last = digits.suffix(4)
            return "(\(areaCode)) \(middle)-\(last)"
        }
        return phoneNumber
    }

    static func from(_ data: [String: Any], id: String) -> Contact? {
        guard let displayName = data["displayName"] as? String,
              let phoneNumber = data["phoneNumber"] as? String,
              let normalizedNumber = data["normalizedNumber"] as? String,
              let phoneType = data["phoneType"] as? String else {
            return nil
        }

        let photoUri = data["photoUri"] as? String
        let photoBase64 = data["photoBase64"] as? String
        let syncedAt = data["syncedAt"] as? Double

        return Contact(
            id: id,
            displayName: displayName,
            phoneNumber: phoneNumber,
            normalizedNumber: normalizedNumber,
            phoneType: phoneType,
            photoUri: photoUri,
            photoBase64: photoBase64,
            syncedAt: syncedAt
        )
    }
}

// MARK: - Desktop Contact (Created on macOS/Web, syncs to Android)

/// Contact created on desktop/web that syncs to Android device
struct DesktopContact: Identifiable, Hashable {
    let id: String
    var displayName: String
    var phoneNumber: String
    var normalizedNumber: String
    var phoneType: String
    var email: String?
    var notes: String?
    var photoBase64: String?
    let createdAt: Double?
    var updatedAt: Double?
    let source: String  // "macos", "web", "desktop"
    var syncedToAndroid: Bool
    var androidContactId: Int?

    var initials: String {
        let components = displayName.components(separatedBy: " ")
        if components.count >= 2 {
            let first = components.first?.prefix(1).uppercased() ?? ""
            let last = components.last?.prefix(1).uppercased() ?? ""
            return "\(first)\(last)"
        } else {
            return String(displayName.prefix(2)).uppercased()
        }
    }

    var formattedPhoneNumber: String {
        if phoneNumber.starts(with: "+") {
            return phoneNumber
        }
        let digits = phoneNumber.filter { $0.isNumber }
        if digits.count == 10 {
            let areaCode = digits.prefix(3)
            let middle = digits.dropFirst(3).prefix(3)
            let last = digits.suffix(4)
            return "(\(areaCode)) \(middle)-\(last)"
        }
        return phoneNumber
    }

    var syncStatusText: String {
        if syncedToAndroid {
            return "Synced to Android"
        } else {
            return "Pending sync..."
        }
    }

    static func from(_ data: [String: Any], id: String) -> DesktopContact? {
        guard let displayName = data["displayName"] as? String,
              let phoneNumber = data["phoneNumber"] as? String else {
            return nil
        }

        let normalizedNumber = data["normalizedNumber"] as? String ?? phoneNumber.filter { $0.isNumber }

        return DesktopContact(
            id: id,
            displayName: displayName,
            phoneNumber: phoneNumber,
            normalizedNumber: normalizedNumber,
            phoneType: data["phoneType"] as? String ?? "Mobile",
            email: data["email"] as? String,
            notes: data["notes"] as? String,
            photoBase64: data["photoBase64"] as? String,
            createdAt: data["createdAt"] as? Double,
            updatedAt: data["updatedAt"] as? Double,
            source: data["source"] as? String ?? "desktop",
            syncedToAndroid: data["syncedToAndroid"] as? Bool ?? false,
            androidContactId: data["androidContactId"] as? Int
        )
    }
}

// MARK: - Phone Type Options

enum PhoneType: String, CaseIterable {
    case mobile = "Mobile"
    case home = "Home"
    case work = "Work"
    case main = "Main"
    case other = "Other"

    var displayName: String { rawValue }
}
