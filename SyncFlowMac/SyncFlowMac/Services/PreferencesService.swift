//
//  PreferencesService.swift
//  SyncFlowMac
//
//  Manages user preferences for conversations (pinned, archived, blocked)
//

import Foundation

class PreferencesService {

    static let shared = PreferencesService()

    private let defaults = UserDefaults.standard

    // Keys
    private let pinnedKey = "pinnedConversations"
    private let archivedKey = "archivedConversations"
    private let blockedKey = "blockedNumbers"
    private let readMessagesKey = "readMessages"
    private let avatarColorsKey = "avatarColors"
    private let templatesKey = "messageTemplates"

    private init() {}

    // MARK: - Pinned Conversations

    func isPinned(_ address: String) -> Bool {
        let pinned = defaults.stringArray(forKey: pinnedKey) ?? []
        return pinned.contains(address)
    }

    func setPinned(_ address: String, pinned: Bool) {
        var pinnedList = defaults.stringArray(forKey: pinnedKey) ?? []

        if pinned {
            if !pinnedList.contains(address) {
                pinnedList.append(address)
            }
        } else {
            pinnedList.removeAll { $0 == address }
        }

        defaults.set(pinnedList, forKey: pinnedKey)
    }

    func getAllPinned() -> [String] {
        return defaults.stringArray(forKey: pinnedKey) ?? []
    }

    // MARK: - Archived Conversations

    func isArchived(_ address: String) -> Bool {
        let archived = defaults.stringArray(forKey: archivedKey) ?? []
        return archived.contains(address)
    }

    func setArchived(_ address: String, archived: Bool) {
        var archivedList = defaults.stringArray(forKey: archivedKey) ?? []

        if archived {
            if !archivedList.contains(address) {
                archivedList.append(address)
            }
        } else {
            archivedList.removeAll { $0 == address }
        }

        defaults.set(archivedList, forKey: archivedKey)
    }

    func getAllArchived() -> [String] {
        return defaults.stringArray(forKey: archivedKey) ?? []
    }

    // MARK: - Blocked Numbers

    func isBlocked(_ address: String) -> Bool {
        let blocked = defaults.stringArray(forKey: blockedKey) ?? []
        return blocked.contains(address)
    }

    func setBlocked(_ address: String, blocked: Bool) {
        var blockedList = defaults.stringArray(forKey: blockedKey) ?? []

        if blocked {
            if !blockedList.contains(address) {
                blockedList.append(address)
            }
        } else {
            blockedList.removeAll { $0 == address }
        }

        defaults.set(blockedList, forKey: blockedKey)
    }

    func getAllBlocked() -> [String] {
        return defaults.stringArray(forKey: blockedKey) ?? []
    }

    // MARK: - Read Messages

    func markMessageAsRead(_ messageId: String) {
        var readMessages = defaults.stringArray(forKey: readMessagesKey) ?? []
        if !readMessages.contains(messageId) {
            readMessages.append(messageId)
            defaults.set(readMessages, forKey: readMessagesKey)
        }
    }

    func isMessageRead(_ messageId: String) -> Bool {
        let readMessages = defaults.stringArray(forKey: readMessagesKey) ?? []
        return readMessages.contains(messageId)
    }

    func markConversationAsRead(_ address: String, messageIds: [String]) {
        var readMessages = defaults.stringArray(forKey: readMessagesKey) ?? []
        for id in messageIds where !readMessages.contains(id) {
            readMessages.append(id)
        }
        defaults.set(readMessages, forKey: readMessagesKey)
    }

    // MARK: - Avatar Colors

    func getAvatarColor(for address: String) -> String {
        let colors = defaults.dictionary(forKey: avatarColorsKey) as? [String: String] ?? [:]
        return colors[address] ?? generateRandomColor()
    }

    func setAvatarColor(for address: String, color: String) {
        var colors = defaults.dictionary(forKey: avatarColorsKey) as? [String: String] ?? [:]
        colors[address] = color
        defaults.set(colors, forKey: avatarColorsKey)
    }

    private func generateRandomColor() -> String {
        let colors = [
            "#4CAF50", "#2196F3", "#9C27B0", "#FF9800",
            "#F44336", "#009688", "#3F51B5", "#FF5722",
            "#795548", "#607D8B", "#E91E63", "#00BCD4"
        ]
        return colors.randomElement() ?? "#2196F3"
    }

    // MARK: - Message Templates

    struct MessageTemplate: Codable {
        let id: String
        let name: String
        let content: String
        let createdAt: Date
    }

    func getTemplates() -> [MessageTemplate] {
        guard let data = defaults.data(forKey: templatesKey) else {
            return []
        }

        do {
            return try JSONDecoder().decode([MessageTemplate].self, from: data)
        } catch {
            print("❌ Error decoding templates: \(error)")
            return []
        }
    }

    func saveTemplate(name: String, content: String) {
        var templates = getTemplates()
        let template = MessageTemplate(
            id: UUID().uuidString,
            name: name,
            content: content,
            createdAt: Date()
        )
        templates.append(template)

        if let data = try? JSONEncoder().encode(templates) {
            defaults.set(data, forKey: templatesKey)
        }
    }

    func deleteTemplate(id: String) {
        var templates = getTemplates()
        templates.removeAll { $0.id == id }

        if let data = try? JSONEncoder().encode(templates) {
            defaults.set(data, forKey: templatesKey)
        }
    }
}
