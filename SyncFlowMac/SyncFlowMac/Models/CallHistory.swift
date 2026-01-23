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
              let callType = CallType(rawValue: callTypeString),
              let callDate = data["callDate"] as? Double else {
            return nil
        }

        let contactName = data["contactName"] as? String
        let duration = data["duration"] as? Int ?? 0
        let formattedDuration = data["formattedDuration"] as? String ?? "0:00"
        let formattedDate = data["formattedDate"] as? String ?? ""
        let simId = data["simId"] as? Int ?? 0

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
