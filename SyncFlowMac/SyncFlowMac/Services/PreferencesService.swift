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

    private let preferenceOwnerKey = "preferences_owner_user_id"

    // Keys
    private let pinnedKey = "pinnedConversations"
    private let archivedKey = "archivedConversations"
    private let blockedKey = "blockedNumbers"
    private let readMessagesKey = "readMessages"
    private let avatarColorsKey = "avatarColors"
    private let templatesKey = "messageTemplates"
    private let reactionsKey = "messageReactions"
    private let labelsKey = "conversationLabels"
    private let labelAssignmentsKey = "labelAssignments"

    private init() {}

    private var currentUserId: String? {
        defaults.string(forKey: "syncflow_user_id")?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func scopedKey(for base: String) -> String {
        guard let userId = currentUserId, !userId.isEmpty else {
            return base
        }
        return "\(base)_\(userId)"
    }

    private func readStringArray(for base: String) -> [String] {
        let key = scopedKey(for: base)

        if key != base {
            if let stored = defaults.stringArray(forKey: key) {
                return stored
            }
            if defaults.string(forKey: preferenceOwnerKey) == currentUserId,
               let legacy = defaults.stringArray(forKey: base) {
                defaults.set(legacy, forKey: key)
                defaults.removeObject(forKey: base)
                return legacy
            }
            return []
        }

        return defaults.stringArray(forKey: base) ?? []
    }

    private func writeStringArray(_ values: [String], for base: String) {
        let key = scopedKey(for: base)
        defaults.set(values, forKey: key)
        if key != base {
            defaults.set(currentUserId, forKey: preferenceOwnerKey)
        }
    }

    // MARK: - Pinned Conversations

    func isPinned(_ address: String) -> Bool {
        let pinned = readStringArray(for: pinnedKey)
        return pinned.contains(address)
    }

    func setPinned(_ address: String, pinned: Bool) {
        var pinnedList = readStringArray(for: pinnedKey)

        if pinned {
            if !pinnedList.contains(address) {
                pinnedList.append(address)
            }
        } else {
            pinnedList.removeAll { $0 == address }
        }

        writeStringArray(pinnedList, for: pinnedKey)
    }

    func getAllPinned() -> [String] {
        return readStringArray(for: pinnedKey)
    }

    // MARK: - Archived Conversations

    func isArchived(_ address: String) -> Bool {
        let archived = readStringArray(for: archivedKey)
        return archived.contains(address)
    }

    func setArchived(_ address: String, archived: Bool) {
        var archivedList = readStringArray(for: archivedKey)

        if archived {
            if !archivedList.contains(address) {
                archivedList.append(address)
            }
        } else {
            archivedList.removeAll { $0 == address }
        }

        writeStringArray(archivedList, for: archivedKey)
    }

    func getAllArchived() -> [String] {
        return readStringArray(for: archivedKey)
    }

    // MARK: - Blocked Numbers

    func isBlocked(_ address: String) -> Bool {
        let blocked = readStringArray(for: blockedKey)
        return blocked.contains(address)
    }

    func setBlocked(_ address: String, blocked: Bool) {
        var blockedList = readStringArray(for: blockedKey)

        if blocked {
            if !blockedList.contains(address) {
                blockedList.append(address)
            }
        } else {
            blockedList.removeAll { $0 == address }
        }

        writeStringArray(blockedList, for: blockedKey)
    }

    func getAllBlocked() -> [String] {
        return readStringArray(for: blockedKey)
    }

    // MARK: - Read Messages

    func markMessageAsRead(_ messageId: String) {
        var readMessages = readStringArray(for: readMessagesKey)
        if !readMessages.contains(messageId) {
            readMessages.append(messageId)
            writeStringArray(readMessages, for: readMessagesKey)
        }
    }

    func isMessageRead(_ messageId: String) -> Bool {
        let readMessages = readStringArray(for: readMessagesKey)
        return readMessages.contains(messageId)
    }

    func markConversationAsRead(_ address: String, messageIds: [String]) {
        var readMessages = readStringArray(for: readMessagesKey)
        for id in messageIds where !readMessages.contains(id) {
            readMessages.append(id)
        }
        writeStringArray(readMessages, for: readMessagesKey)
    }

    // MARK: - Avatar Colors

    func getAvatarColor(for address: String) -> String {
        let colors = defaults.dictionary(forKey: avatarColorsKey) as? [String: String] ?? [:]
        return colors[address] ?? generateRandomColor()
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

    struct MessageTemplate: Codable, Identifiable {
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

    func updateTemplate(id: String, name: String, content: String) {
        var templates = getTemplates()
        if let index = templates.firstIndex(where: { $0.id == id }) {
            templates[index] = MessageTemplate(
                id: id,
                name: name,
                content: content,
                createdAt: templates[index].createdAt
            )
            if let data = try? JSONEncoder().encode(templates) {
                defaults.set(data, forKey: templatesKey)
            }
        }
    }

    func deleteTemplate(id: String) {
        var templates = getTemplates()
        templates.removeAll { $0.id == id }
        if let data = try? JSONEncoder().encode(templates) {
            defaults.set(data, forKey: templatesKey)
        }
    }

    // MARK: - Message Reactions

    func getReaction(for messageId: String) -> String? {
        let reactions = defaults.dictionary(forKey: reactionsKey) as? [String: String] ?? [:]
        return reactions[messageId]
    }

    func setReaction(_ reaction: String?, for messageId: String) {
        var reactions = defaults.dictionary(forKey: reactionsKey) as? [String: String] ?? [:]
        if let reaction = reaction, !reaction.isEmpty {
            reactions[messageId] = reaction
        } else {
            reactions.removeValue(forKey: messageId)
        }
        defaults.set(reactions, forKey: reactionsKey)
    }

    // MARK: - Conversation Labels

    struct ConversationLabel: Codable, Identifiable, Equatable {
        let id: String
        var name: String
        var color: String
        var icon: String
        let createdAt: Date

        static let defaultLabels: [ConversationLabel] = [
            ConversationLabel(id: "work", name: "Work", color: "#2196F3", icon: "briefcase.fill", createdAt: Date()),
            ConversationLabel(id: "personal", name: "Personal", color: "#4CAF50", icon: "person.fill", createdAt: Date()),
            ConversationLabel(id: "family", name: "Family", color: "#E91E63", icon: "heart.fill", createdAt: Date()),
            ConversationLabel(id: "important", name: "Important", color: "#FF9800", icon: "star.fill", createdAt: Date())
        ]

        static let availableColors: [String] = [
            "#2196F3", "#4CAF50", "#E91E63", "#FF9800",
            "#9C27B0", "#00BCD4", "#F44336", "#795548",
            "#607D8B", "#3F51B5", "#009688", "#FF5722"
        ]

        static let availableIcons: [String] = [
            "briefcase.fill", "person.fill", "heart.fill", "star.fill",
            "house.fill", "tag.fill", "folder.fill", "flag.fill",
            "bell.fill", "bookmark.fill", "cart.fill", "creditcard.fill",
            "airplane", "car.fill", "graduationcap.fill", "gamecontroller.fill"
        ]
    }

    /// Get all labels
    func getLabels() -> [ConversationLabel] {
        guard let data = defaults.data(forKey: labelsKey) else {
            // Return default labels on first run
            let defaultLabels = ConversationLabel.defaultLabels
            if let encoded = try? JSONEncoder().encode(defaultLabels) {
                defaults.set(encoded, forKey: labelsKey)
            }
            return defaultLabels
        }

        do {
            return try JSONDecoder().decode([ConversationLabel].self, from: data)
        } catch {
            print("Error decoding labels: \(error)")
            return ConversationLabel.defaultLabels
        }
    }

    /// Create a new label
    func createLabel(name: String, color: String, icon: String) -> ConversationLabel {
        var labels = getLabels()
        let label = ConversationLabel(
            id: UUID().uuidString,
            name: name,
            color: color,
            icon: icon,
            createdAt: Date()
        )
        labels.append(label)
        saveLabels(labels)
        return label
    }

    /// Update a label
    func updateLabel(_ label: ConversationLabel) {
        var labels = getLabels()
        if let index = labels.firstIndex(where: { $0.id == label.id }) {
            labels[index] = label
            saveLabels(labels)
        }
    }

    /// Delete a label
    func deleteLabel(id: String) {
        var labels = getLabels()
        labels.removeAll { $0.id == id }
        saveLabels(labels)

        // Remove all assignments for this label
        var assignments = getLabelAssignments()
        for (address, labelIds) in assignments {
            var updatedIds = labelIds
            updatedIds.removeAll { $0 == id }
            if updatedIds.isEmpty {
                assignments.removeValue(forKey: address)
            } else {
                assignments[address] = updatedIds
            }
        }
        saveLabelAssignments(assignments)
    }

    private func saveLabels(_ labels: [ConversationLabel]) {
        if let data = try? JSONEncoder().encode(labels) {
            defaults.set(data, forKey: labelsKey)
        }
    }

    // MARK: - Label Assignments

    /// Get all label assignments (address -> [labelId])
    func getLabelAssignments() -> [String: [String]] {
        guard let data = defaults.data(forKey: labelAssignmentsKey),
              let assignments = try? JSONDecoder().decode([String: [String]].self, from: data) else {
            return [:]
        }
        return assignments
    }

    /// Get labels for a conversation
    func getLabels(for address: String) -> [ConversationLabel] {
        let assignments = getLabelAssignments()
        let labelIds = assignments[address] ?? []
        let allLabels = getLabels()
        return allLabels.filter { labelIds.contains($0.id) }
    }

    /// Add a label to a conversation
    func addLabel(_ labelId: String, to address: String) {
        var assignments = getLabelAssignments()
        var labelIds = assignments[address] ?? []
        if !labelIds.contains(labelId) {
            labelIds.append(labelId)
            assignments[address] = labelIds
            saveLabelAssignments(assignments)
        }
    }

    /// Remove a label from a conversation
    func removeLabel(_ labelId: String, from address: String) {
        var assignments = getLabelAssignments()
        var labelIds = assignments[address] ?? []
        labelIds.removeAll { $0 == labelId }
        if labelIds.isEmpty {
            assignments.removeValue(forKey: address)
        } else {
            assignments[address] = labelIds
        }
        saveLabelAssignments(assignments)
    }

    /// Toggle a label for a conversation
    func toggleLabel(_ labelId: String, for address: String) {
        let assignments = getLabelAssignments()
        let labelIds = assignments[address] ?? []
        if labelIds.contains(labelId) {
            removeLabel(labelId, from: address)
        } else {
            addLabel(labelId, to: address)
        }
    }

    /// Get all conversations with a specific label
    func getConversations(with labelId: String) -> [String] {
        let assignments = getLabelAssignments()
        return assignments.compactMap { address, labelIds in
            labelIds.contains(labelId) ? address : nil
        }
    }

    private func saveLabelAssignments(_ assignments: [String: [String]]) {
        if let data = try? JSONEncoder().encode(assignments) {
            defaults.set(data, forKey: labelAssignmentsKey)
        }
    }

    // MARK: - Subscription/Plan Management

    private let userPlanKey = "user_plan"
    private let planExpiresAtKey = "plan_expires_at"
    private let freeTrialExpiresAtKey = "free_trial_expires_at"

    var userPlan: String {
        get {
            defaults.string(forKey: userPlanKey) ?? "free"
        }
        set {
            defaults.set(newValue, forKey: userPlanKey)
        }
    }

    var planExpiresAt: Int64 {
        get {
            defaults.object(forKey: planExpiresAtKey) as? Int64 ?? 0
        }
        set {
            defaults.set(newValue, forKey: planExpiresAtKey)
        }
    }

    var freeTrialExpiresAt: Int64 {
        get {
            defaults.object(forKey: freeTrialExpiresAtKey) as? Int64 ?? 0
        }
        set {
            defaults.set(newValue, forKey: freeTrialExpiresAtKey)
        }
    }

    func isPaidUser() -> Bool {
        let plan = userPlan.lowercased()
        let isPaid = ["monthly", "yearly", "lifetime"].contains(plan)
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // Check expiration
        if isPaid && planExpiresAt > 0 && planExpiresAt < now {
            return false // Plan expired, treat as free
        }
        return isPaid
    }

    // Check if free trial is still active (7 days)
    func isFreeTrial() -> Bool {
        if isPaidUser() { return false }

        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // Initialize trial on first use
        if freeTrialExpiresAt == 0 {
            let trialExpiry = now + (7 * 24 * 60 * 60 * 1000) // 7 days from now
            setFreeTrialExpiry(trialExpiry)
            return true
        }

        return freeTrialExpiresAt > now
    }

    // Get remaining trial days
    func getTrialDaysRemaining() -> Int {
        if isPaidUser() { return 0 }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let remaining = (freeTrialExpiresAt - now) / (24 * 60 * 60 * 1000)
        return max(0, Int(remaining))
    }

    func isSmsOnlyUser() -> Bool {
        return !isPaidUser()
    }

    func setUserPlan(_ plan: String, expiresAt: Int64 = 0) {
        userPlan = plan
        planExpiresAt = expiresAt
    }

    func setFreeTrialExpiry(_ expiryTime: Int64) {
        freeTrialExpiresAt = expiryTime
    }
}
