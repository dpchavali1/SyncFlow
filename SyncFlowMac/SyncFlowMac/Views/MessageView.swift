//
//  MessageView.swift
//  SyncFlowMac
//
//  Main message view showing conversation and compose bar
//

import SwiftUI

struct MessageView: View {
    @EnvironmentObject var appState: AppState
    @EnvironmentObject var messageStore: MessageStore

    let conversation: Conversation

    @State private var messageText = ""
    @State private var isSending = false
    @State private var scrollToBottom = false
    @State private var showTemplates = false
    @State private var showEmojiPicker = false

    var conversationMessages: [Message] {
        messageStore.messages(for: conversation)
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            ConversationHeader(
                conversation: conversation,
                messageStore: messageStore
            )

            Divider()

            // Messages
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 16) {
                        ForEach(conversationMessages) { message in
                            MessageBubble(message: message)
                                .id(message.id)
                        }
                    }
                    .padding()
                }
                .onChange(of: conversationMessages.count) { _ in
                    // Auto-scroll to bottom when new message arrives
                    if let lastMessage = conversationMessages.last {
                        withAnimation {
                            proxy.scrollTo(lastMessage.id, anchor: .bottom)
                        }
                    }
                }
                .onAppear {
                    // Scroll to bottom on first appear
                    if let lastMessage = conversationMessages.last {
                        proxy.scrollTo(lastMessage.id, anchor: .bottom)
                    }

                    // Mark as read when opening conversation
                    messageStore.markConversationAsRead(conversation)
                }
            }

            Divider()

            // Templates popover
            if showTemplates {
                TemplatesView(
                    onSelect: { template in
                        messageText = template.content
                        showTemplates = false
                    },
                    onDismiss: { showTemplates = false }
                )
                .frame(height: 200)
                .transition(.move(edge: .bottom))
            }

            // Compose bar
            ComposeBar(
                messageText: $messageText,
                isSending: isSending,
                showTemplates: $showTemplates,
                showEmojiPicker: $showEmojiPicker
            ) {
                await sendMessage()
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
    }

    // MARK: - Send Message

    private func sendMessage() async {
        guard !messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let userId = appState.userId else {
            return
        }

        let body = messageText
        messageText = ""
        isSending = true

        do {
            try await messageStore.sendMessage(
                userId: userId,
                to: conversation.address,
                body: body
            )
        } catch {
            // Show error
            print("Error sending message: \(error)")
        }

        isSending = false
    }
}

// MARK: - Conversation Header

struct ConversationHeader: View {
    let conversation: Conversation
    let messageStore: MessageStore

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(Color(hex: conversation.avatarColor ?? "#2196F3"))
                .frame(width: 40, height: 40)
                .overlay(
                    Text(conversation.initials)
                        .font(.headline)
                        .foregroundColor(.white)
                )

            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(conversation.displayName)
                        .font(.headline)

                    if conversation.isPinned {
                        Image(systemName: "pin.fill")
                            .font(.caption)
                            .foregroundColor(.orange)
                    }
                }

                Text(conversation.address)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            // Action buttons
            Menu {
                Button(action: {
                    messageStore.togglePin(conversation)
                }) {
                    Label(conversation.isPinned ? "Unpin" : "Pin", systemImage: conversation.isPinned ? "pin.slash" : "pin")
                }

                Button(action: {
                    messageStore.toggleArchive(conversation)
                }) {
                    Label("Archive", systemImage: "archivebox")
                }

                Divider()

                Button(role: .destructive, action: {
                    messageStore.toggleBlock(conversation)
                }) {
                    Label("Block", systemImage: "hand.raised")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .buttonStyle(.borderless)
            .help("More options")
        }
        .padding()
    }
}

// MARK: - Message Bubble

struct MessageBubble: View {
    let message: Message

    var body: some View {
        HStack {
            if !message.isReceived {
                Spacer()
            }

            VStack(alignment: message.isReceived ? .leading : .trailing, spacing: 4) {
                // Message content with link detection
                if message.hasLinks {
                    ClickableMessageText(text: message.body)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(message.isReceived ? Color(nsColor: .controlBackgroundColor) : Color.blue)
                        .foregroundColor(message.isReceived ? .primary : .white)
                        .cornerRadius(16)
                } else {
                    Text(message.body)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(message.isReceived ? Color(nsColor: .controlBackgroundColor) : Color.blue)
                        .foregroundColor(message.isReceived ? .primary : .white)
                        .cornerRadius(16)
                }

                Text(message.formattedTime)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            .frame(maxWidth: 500, alignment: message.isReceived ? .leading : .trailing)
            .contextMenu {
                Button(action: {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(message.body, forType: .string)
                }) {
                    Label("Copy", systemImage: "doc.on.doc")
                }
            }

            if message.isReceived {
                Spacer()
            }
        }
    }
}

// MARK: - Clickable Message Text

struct ClickableMessageText: View {
    let text: String

    var body: some View {
        Text(attributedString)
            .textSelection(.enabled)
    }

    private var attributedString: AttributedString {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue | NSTextCheckingResult.CheckingType.phoneNumber.rawValue) else {
            return AttributedString(text)
        }

        let matches = detector.matches(in: text, range: NSRange(text.startIndex..., in: text))
        var attributedString = AttributedString(text)

        for match in matches {
            if let range = Range(match.range, in: text) {
                let substring = String(text[range])
                if let attrRange = attributedString.range(of: substring) {
                    attributedString[attrRange].foregroundColor = .blue
                    attributedString[attrRange].underlineStyle = .single

                    if let url = match.url {
                        attributedString[attrRange].link = url
                    } else if match.resultType == .phoneNumber {
                        // Make phone numbers clickable
                        if let phoneURL = URL(string: "tel:\(substring.filter { $0.isNumber })") {
                            attributedString[attrRange].link = phoneURL
                        }
                    }
                }
            }
        }

        return attributedString
    }
}

// MARK: - Compose Bar

struct ComposeBar: View {
    @Binding var messageText: String
    let isSending: Bool
    @Binding var showTemplates: Bool
    @Binding var showEmojiPicker: Bool
    let onSend: () async -> Void

    @FocusState private var isTextFieldFocused: Bool

    var body: some View {
        HStack(spacing: 12) {
            // Templates button
            Button(action: {
                showTemplates.toggle()
            }) {
                Image(systemName: "text.badge.star")
                    .foregroundColor(showTemplates ? .blue : .secondary)
            }
            .buttonStyle(.plain)
            .help("Message Templates")

            // Emoji picker button (macOS native)
            Button(action: {
                NSApp.orderFrontCharacterPalette(nil)
            }) {
                Image(systemName: "face.smiling")
                    .foregroundColor(.secondary)
            }
            .buttonStyle(.plain)
            .help("Emoji & Symbols")

            // Text editor
            ZStack(alignment: .leading) {
                if messageText.isEmpty {
                    Text("Type a message...")
                        .foregroundColor(.secondary)
                        .padding(.leading, 8)
                }

                TextEditor(text: $messageText)
                    .focused($isTextFieldFocused)
                    .font(.body)
                    .frame(minHeight: 36, maxHeight: 100)
                    .scrollContentBackground(.hidden)
            }
            .padding(6)
            .background(Color(nsColor: .controlBackgroundColor))
            .cornerRadius(18)

            // Send button
            Button {
                Task {
                    await onSend()
                }
            } label: {
                if isSending {
                    ProgressView()
                        .scaleEffect(0.7)
                        .frame(width: 24, height: 24)
                } else {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 28))
                }
            }
            .buttonStyle(.plain)
            .disabled(messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSending)
        }
        .padding()
        .onAppear {
            isTextFieldFocused = true
        }
    }
}

// MARK: - Templates View

struct TemplatesView: View {
    let onSelect: (PreferencesService.MessageTemplate) -> Void
    let onDismiss: () -> Void

    @State private var templates: [PreferencesService.MessageTemplate] = []
    @State private var showAddTemplate = false
    @State private var newTemplateName = ""
    @State private var newTemplateContent = ""

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Message Templates")
                    .font(.headline)
                    .padding()

                Spacer()

                Button(action: { showAddTemplate = true }) {
                    Image(systemName: "plus.circle.fill")
                }
                .buttonStyle(.borderless)
                .padding()

                Button(action: onDismiss) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.borderless)
                .padding()
            }
            .background(Color(nsColor: .controlBackgroundColor))

            Divider()

            if templates.isEmpty {
                VStack {
                    Text("No templates yet")
                        .foregroundColor(.secondary)
                    Button("Create Template") {
                        showAddTemplate = true
                    }
                    .buttonStyle(.borderedProminent)
                }
                .frame(maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(templates, id: \.id) { template in
                            TemplateRow(template: template, onSelect: {
                                onSelect(template)
                            })
                        }
                    }
                    .padding()
                }
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .onAppear {
            templates = PreferencesService.shared.getTemplates()
        }
        .sheet(isPresented: $showAddTemplate) {
            AddTemplateView(
                templateName: $newTemplateName,
                templateContent: $newTemplateContent,
                onSave: {
                    PreferencesService.shared.saveTemplate(name: newTemplateName, content: newTemplateContent)
                    templates = PreferencesService.shared.getTemplates()
                    newTemplateName = ""
                    newTemplateContent = ""
                    showAddTemplate = false
                }
            )
        }
    }
}

struct TemplateRow: View {
    let template: PreferencesService.MessageTemplate
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            VStack(alignment: .leading, spacing: 4) {
                Text(template.name)
                    .font(.headline)
                Text(template.content)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(Color(nsColor: .controlBackgroundColor))
            .cornerRadius(8)
        }
        .buttonStyle(.plain)
    }
}

struct AddTemplateView: View {
    @Binding var templateName: String
    @Binding var templateContent: String
    let onSave: () -> Void

    @Environment(\.dismiss) var dismiss

    var body: some View {
        VStack(spacing: 16) {
            Text("New Template")
                .font(.title2)
                .fontWeight(.bold)

            VStack(alignment: .leading, spacing: 8) {
                Text("Name:")
                TextField("e.g., Running late", text: $templateName)
                    .textFieldStyle(.roundedBorder)
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Message:")
                TextEditor(text: $templateContent)
                    .frame(height: 100)
                    .border(Color.secondary.opacity(0.3))
            }

            HStack {
                Button("Cancel") {
                    dismiss()
                }

                Spacer()

                Button("Save") {
                    onSave()
                }
                .disabled(templateName.isEmpty || templateContent.isEmpty)
            }
        }
        .padding()
        .frame(width: 400)
    }
}

// MARK: - New Message View

struct NewMessageView: View {
    @EnvironmentObject var appState: AppState
    @EnvironmentObject var messageStore: MessageStore
    @Environment(\.dismiss) var dismiss

    @State private var phoneNumber = ""
    @State private var messageText = ""
    @State private var isSending = false

    var body: some View {
        VStack(spacing: 20) {
            Text("New Message")
                .font(.title2)
                .fontWeight(.bold)

            VStack(alignment: .leading, spacing: 8) {
                Text("To:")
                    .foregroundColor(.secondary)
                TextField("Phone number", text: $phoneNumber)
                    .textFieldStyle(.roundedBorder)
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Message:")
                    .foregroundColor(.secondary)
                TextEditor(text: $messageText)
                    .frame(height: 150)
                    .border(Color.secondary.opacity(0.3))
            }

            HStack {
                Button("Cancel") {
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)

                Spacer()

                Button("Send") {
                    Task {
                        await sendMessage()
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(phoneNumber.isEmpty || messageText.isEmpty || isSending)
            }
        }
        .padding()
    }

    private func sendMessage() async {
        guard let userId = appState.userId else { return }

        isSending = true

        do {
            try await messageStore.sendMessage(
                userId: userId,
                to: phoneNumber,
                body: messageText
            )
            dismiss()
        } catch {
            print("Error sending message: \(error)")
        }

        isSending = false
    }
}
