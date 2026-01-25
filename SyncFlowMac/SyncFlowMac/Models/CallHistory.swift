//
//  CallHistory.swift
//  SyncFlowMac
//
//  Model for call history entries
//

import Foundation

struct CallHistoryEntry: Identifiable, Hashable {
    let id: String
    let phoneNumber: String
    let contactName: String?
    let callType: CallType
    let callDate: Date
    let duration: Int // in seconds
    let formattedDuration: String
    let formattedDate: String
    let simId: Int

    enum CallType: String, CaseIterable {
        case incoming = "Incoming"
        case outgoing = "Outgoing"
        case missed = "Missed"
        case rejected = "Rejected"
        case blocked = "Blocked"
        case voicemail = "Voicemail"

        var icon: String {
            switch self {
            case .incoming:
                return "phone.arrow.down.left.fill"
            case .outgoing:
                return "phone.arrow.up.right.fill"
            case .missed:
                return "phone.down.fill"
            case .rejected:
                return "phone.down.circle.fill"
            case .blocked:
                return "hand.raised.fill"
            case .voicemail:
                return "voicemail.fill"
            }
        }

        var color: String {
            switch self {
            case .incoming:
                return "blue"
            case .outgoing:
                return "green"
            case .missed:
                return "red"
            case .rejected:
                return "orange"
            case .blocked:
                return "gray"
            case .voicemail:
                return "purple"
            }
        }
    }

    var displayName: String {
        return contactName ?? phoneNumber
    }

    static func from(_ data: [String: Any], id: String) -> CallHistoryEntry? {
        guard let phoneNumber = data["phoneNumber"] as? String,
              let callTypeString = data["callType"] as? String,
              let callType = CallType(rawValue: callTypeString) else {
            return nil
        }

        // Handle callDate - Firebase stores Long as Double
        let callDate: Double
        if let dateDouble = data["callDate"] as? Double {
            callDate = dateDouble
        } else if let dateInt = data["callDate"] as? Int {
            callDate = Double(dateInt)
        } else if let dateInt64 = data["callDate"] as? Int64 {
            callDate = Double(dateInt64)
        } else {
            return nil
        }

        let contactName = data["contactName"] as? String

        // Handle duration - Firebase stores Long as Double
        let duration: Int
        if let durationInt = data["duration"] as? Int {
            duration = durationInt
        } else if let durationDouble = data["duration"] as? Double {
            duration = Int(durationDouble)
        } else if let durationInt64 = data["duration"] as? Int64 {
            duration = Int(durationInt64)
        } else {
            duration = 0
        }

        let formattedDuration = data["formattedDuration"] as? String ?? "0:00"
        let formattedDate = data["formattedDate"] as? String ?? ""

        // Handle simId - Firebase stores Long as Double
        let simId: Int
        if let simIdInt = data["simId"] as? Int {
            simId = simIdInt
        } else if let simIdDouble = data["simId"] as? Double {
            simId = Int(simIdDouble)
        } else {
            simId = 0
        }

        return CallHistoryEntry(
            id: id,
            phoneNumber: phoneNumber.isEmpty ? "Unknown" : phoneNumber,
            contactName: contactName?.isEmpty == false ? contactName : nil,
            callType: callType,
            callDate: Date(timeIntervalSince1970: callDate / 1000), // Convert from milliseconds
            duration: duration,
            formattedDuration: formattedDuration,
            formattedDate: formattedDate,
            simId: simId
        )
    }
}
