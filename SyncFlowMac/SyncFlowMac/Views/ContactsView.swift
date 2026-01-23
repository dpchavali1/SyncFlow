//
//  ContactsView.swift
//  SyncFlowMac
//
//  View for browsing and calling contacts from Android phone
//

import SwiftUI
import FirebaseDatabase
import Combine

struct ContactsView: View {
    @EnvironmentObject var appState: AppState
    @StateObject private var contactsStore = ContactsStore()
    @State private var searchText = ""
    @State private var selectedContact: Contact?
    @State private var showNewContactSheet = false
    @State private var editingDesktopContact: DesktopContact? = nil
    @State private var showDesktopContacts = true

    var filteredContacts: [Contact] {
        if searchText.isEmpty {
            return contactsStore.contacts
        }
        return contactsStore.contacts.filter { contact in
            matchesSearch(
                name: contact.displayName,
                phoneNumber: contact.phoneNumber,
                normalizedNumber: contact.normalizedNumber
            )
        }
    }

    var filteredDesktopContacts: [DesktopContact] {
        if searchText.isEmpty {
            return contactsStore.desktopContacts
        }
        return contactsStore.desktopContacts.filter { contact in
            matchesSearch(
                name: contact.displayName,
                phoneNumber: contact.phoneNumber,
                normalizedNumber: contact.normalizedNumber
            )
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header with search and new contact button
            HStack {
                // Search bar
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.secondary)
                    TextField("Search contacts", text: $searchText)
                        .textFieldStyle(.plain)
                    if !searchText.isEmpty {
                        Button(action: { searchText = "" }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(12)
                .background(Color(nsColor: .controlBackgroundColor))
                .cornerRadius(8)

                // New contact button
                Button(action: { showNewContactSheet = true }) {
                    Label("New Contact", systemImage: "person.badge.plus")
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()

            Divider()

            // Contacts list
            if contactsStore.isLoading {
                VStack {
                    ProgressView()
                    Text("Loading contacts...")
                        .foregroundColor(.secondary)
                        .padding(.top)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if filteredContacts.isEmpty && filteredDesktopContacts.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: searchText.isEmpty ? "person.2.slash" : "magnifyingglass")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)
                    Text(searchText.isEmpty ? "No contacts synced" : "No contacts found")
                        .font(.title3)
                        .foregroundColor(.secondary)
                    if searchText.isEmpty {
                        Text("Contacts from your Android phone will appear here.\nYou can also create new contacts that sync to your phone.")
                            .font(.body)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)

                        Button(action: { showNewContactSheet = true }) {
                            Label("Create New Contact", systemImage: "person.badge.plus")
                        }
                        .buttonStyle(.borderedProminent)
                        .padding(.top)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                        // Desktop-created contacts section (syncs to Android)
                        if !filteredDesktopContacts.isEmpty {
                            Section {
                                ForEach(filteredDesktopContacts) { contact in
                                    DesktopContactRow(
                                        contact: contact,
                                        onEdit: { editingDesktopContact = contact },
                                        onDelete: { deleteDesktopContact(contact) }
                                    )
                                    .environmentObject(appState)
                                }
                            } header: {
                                HStack {
                                    Image(systemName: "laptopcomputer")
                                        .foregroundColor(.blue)
                                    Text("Created on Mac")
                                        .font(.headline)
                                    Spacer()
                                    Text("\(filteredDesktopContacts.count)")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 2)
                                        .background(Color.secondary.opacity(0.2))
                                        .cornerRadius(8)
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 8)
                                .background(Color(nsColor: .windowBackgroundColor))
                            }
                        }

                        // Android contacts section
                        if !filteredContacts.isEmpty {
                            Section {
                                ForEach(filteredContacts) { contact in
                                    ContactRow(contact: contact, selectedContact: $selectedContact)
                                        .environmentObject(appState)
                                }
                            } header: {
                                HStack {
                                    Image(systemName: "iphone")
                                        .foregroundColor(.green)
                                    Text("From Android")
                                        .font(.headline)
                                    Spacer()
                                    Text("\(filteredContacts.count)")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 2)
                                        .background(Color.secondary.opacity(0.2))
                                        .cornerRadius(8)
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 8)
                                .background(Color(nsColor: .windowBackgroundColor))
                            }
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(nsColor: .windowBackgroundColor))
        .onAppear {
            if let userId = appState.userId {
                contactsStore.startListening(userId: userId)
            }
        }
        .sheet(isPresented: $showNewContactSheet) {
            ContactEditSheet(
                mode: .create,
                onSave: { name, phone, phoneType, email, notes in
                    Task {
                        await createContact(name: name, phone: phone, phoneType: phoneType, email: email, notes: notes)
                    }
                }
            )
        }
        .sheet(item: $editingDesktopContact) { contact in
            ContactEditSheet(
                mode: .edit(contact),
                onSave: { name, phone, phoneType, email, notes in
                    Task {
                        await updateContact(contact: contact, name: name, phone: phone, phoneType: phoneType, email: email, notes: notes)
                    }
                }
            )
        }
    }

    private func createContact(name: String, phone: String, phoneType: String, email: String?, notes: String?) async {
        guard let userId = appState.userId else { return }

        do {
            _ = try await FirebaseService.shared.createDesktopContact(
                userId: userId,
                displayName: name,
                phoneNumber: phone,
                phoneType: phoneType,
                email: email,
                notes: notes
            )
        } catch {
            print("Error creating contact: \(error)")
        }
    }

    private func updateContact(contact: DesktopContact, name: String, phone: String, phoneType: String, email: String?, notes: String?) async {
        guard let userId = appState.userId else { return }

        do {
            try await FirebaseService.shared.updateDesktopContact(
                userId: userId,
                contactId: contact.id,
                displayName: name,
                phoneNumber: phone,
                phoneType: phoneType,
                email: email,
                notes: notes
            )
        } catch {
            print("Error updating contact: \(error)")
        }
    }

    private func deleteDesktopContact(_ contact: DesktopContact) {
        guard let userId = appState.userId else { return }

        Task {
            do {
                try await FirebaseService.shared.deleteDesktopContact(userId: userId, contactId: contact.id)
            } catch {
                print("Error deleting contact: \(error)")
            }
        }
    }

    private func matchesSearch(name: String, phoneNumber: String, normalizedNumber: String) -> Bool {
        if searchText.isEmpty {
            return true
        }

        if name.localizedCaseInsensitiveContains(searchText) {
            return true
        }

        if phoneNumber.localizedCaseInsensitiveContains(searchText) {
            return true
        }

        let queryDigits = searchText.filter { $0.isNumber }
        if queryDigits.isEmpty {
            return false
        }

        let numberDigitsSource = normalizedNumber.isEmpty ? phoneNumber : normalizedNumber
        let numberDigits = numberDigitsSource.filter { $0.isNumber }
        if numberDigits.contains(queryDigits) {
            return true
        }

        return !numberDigits.isEmpty && queryDigits.contains(numberDigits)
    }
}

// MARK: - Contact Row

struct ContactRow: View {
    let contact: Contact
    @Binding var selectedContact: Contact?
    @EnvironmentObject var appState: AppState

    @State private var isHovered = false
    @State private var showCallAlert = false
    @State private var callStatus: CallRequestStatus? = nil
    @State private var isCallInProgress = false
    @State private var availableSims: [SimInfo] = []
    @State private var selectedSim: SimInfo? = nil
    @State private var hasLoadedSims = false
    @State private var pairedDevices: [SyncFlowDevice] = []
    @State private var hasLoadedDevices = false

    var body: some View {
        HStack(spacing: 12) {
            // Avatar with photo or initials
            Group {
                if let photoBase64 = contact.photoBase64,
                   let imageData = Data(base64Encoded: photoBase64, options: .ignoreUnknownCharacters),
                   let nsImage = NSImage(data: imageData) {
                    Image(nsImage: nsImage)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 44, height: 44)
                        .clipShape(Circle())
                } else {
                    Circle()
                        .fill(Color.blue.opacity(0.2))
                        .frame(width: 44, height: 44)
                        .overlay(
                            Text(contact.initials)
                                .font(.headline)
                                .foregroundColor(.blue)
                        )
                }
            }

            // Contact info
            VStack(alignment: .leading, spacing: 4) {
                Text(contact.displayName)
                    .font(.body)
                    .fontWeight(.medium)
                HStack(spacing: 6) {
                    Text(contact.formattedPhoneNumber)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text("•")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(contact.phoneType)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            Spacer()

            // Call button
            if isHovered {
                if availableSims.count > 1 {
                    Menu {
                        ForEach(availableSims) { sim in
                            Button(action: {
                                selectedSim = sim
                                initiateCall()
                            }) {
                                HStack {
                                    Text(sim.formattedDisplayName)
                                    if selectedSim?.id == sim.id {
                                        Image(systemName: "checkmark")
                                    }
                                }
                            }
                        }
                    } label: {
                        Image(systemName: isCallInProgress ? "phone.fill.arrow.up.right" : "phone.fill")
                            .foregroundColor(isCallInProgress ? .green : .blue)
                            .frame(width: 32, height: 32)
                    }
                    .buttonStyle(.borderless)
                    .help("Choose SIM card to call from")
                    .disabled(isCallInProgress)
                } else {
                    Button(action: {
                        initiateCall()
                    }) {
                        Image(systemName: isCallInProgress ? "phone.fill.arrow.up.right" : "phone.fill")
                            .foregroundColor(isCallInProgress ? .green : .blue)
                            .frame(width: 32, height: 32)
                    }
                    .buttonStyle(.borderless)
                    .help("Call via Android phone")
                    .disabled(isCallInProgress)
                }

                // SyncFlow Video Call button
                Button(action: {
                    initiateSyncFlowCall(isVideo: true)
                }) {
                    Image(systemName: "video.fill")
                        .foregroundColor(.green)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.borderless)
                .help("SyncFlow video call to Android device")

                // Message button
                Button(action: {
                    startConversation()
                }) {
                    Image(systemName: "message.fill")
                        .foregroundColor(.blue)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.borderless)
                .help("Send message")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(isHovered ? Color(nsColor: .controlBackgroundColor) : Color.clear)
        .onHover { hovering in
            isHovered = hovering
            if hovering && !hasLoadedSims {
                loadAvailableSims()
            }
            if hovering && !hasLoadedDevices {
                loadPairedDevices()
            }
        }
        .alert("Calling \(contact.displayName)", isPresented: $showCallAlert) {
            Button("OK") {
                showCallAlert = false
                isCallInProgress = false
            }
        } message: {
            if let status = callStatus {
                Text(status.description)
            }
        }
    }

    private func loadAvailableSims() {
        guard let userId = appState.userId else { return }
        guard !hasLoadedSims else { return }

        hasLoadedSims = true

        Task {
            do {
                let sims = try await FirebaseService.shared.getAvailableSims(userId: userId)
                await MainActor.run {
                    availableSims = sims
                    if selectedSim == nil {
                        selectedSim = sims.first
                    }
                }
            } catch {
                print("Error loading SIMs: \(error)")
                hasLoadedSims = false
            }
        }
    }

    private func initiateCall() {
        isCallInProgress = true
        appState.makeCall(to: contact.phoneNumber)

        callStatus = .completed
        showCallAlert = true

        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            showCallAlert = false
            isCallInProgress = false
        }
    }

    private func startConversation() {
        // Create a conversation object for this contact
        let conversation = Conversation(
            id: contact.normalizedNumber,
            address: contact.phoneNumber,
            contactName: contact.displayName,
            lastMessage: "",
            timestamp: Date(),
            unreadCount: 0,
            allAddresses: [contact.phoneNumber],
            isPinned: false,
            isArchived: false,
            isBlocked: false,
            avatarColor: nil
        )

        // Update app state to show conversation and switch to messages tab
        appState.selectedConversation = conversation
        appState.selectedTab = .messages
    }

    private func loadPairedDevices() {
        guard let userId = appState.userId else { return }
        guard !hasLoadedDevices else { return }

        hasLoadedDevices = true

        Task {
            do {
                let devices = try await FirebaseService.shared.getPairedDevices(userId: userId)
                await MainActor.run {
                    pairedDevices = devices
                }
            } catch {
                print("Error loading paired devices: \(error)")
                hasLoadedDevices = false
            }
        }
    }

    private func initiateSyncFlowCall(isVideo: Bool) {
        // Start user-to-user video call using the contact's phone number
        let phoneNumber = contact.phoneNumber
        let recipientName = contact.displayName

        Task {
            do {
                let callId = try await appState.syncFlowCallManager.startCallToUser(
                    recipientPhoneNumber: phoneNumber,
                    recipientName: recipientName,
                    isVideo: isVideo
                )
                print("Started \(isVideo ? "video" : "audio") call to \(recipientName): \(callId)")

                // Show the call view
                await MainActor.run {
                    appState.showSyncFlowCallView = true
                }
            } catch {
                print("Failed to start call: \(error.localizedDescription)")
            }
        }
    }
}

// MARK: - Desktop Contact Row

struct DesktopContactRow: View {
    let contact: DesktopContact
    let onEdit: () -> Void
    let onDelete: () -> Void
    @EnvironmentObject var appState: AppState

    @State private var isHovered = false
    @State private var showDeleteConfirmation = false

    var body: some View {
        HStack(spacing: 12) {
            // Avatar with initials
            Circle()
                .fill(Color.blue.opacity(0.2))
                .frame(width: 44, height: 44)
                .overlay(
                    Text(contact.initials)
                        .font(.headline)
                        .foregroundColor(.blue)
                )

            // Contact info
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(contact.displayName)
                        .font(.body)
                        .fontWeight(.medium)

                    // Sync status indicator
                    if contact.syncedToAndroid {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.green)
                            .font(.caption)
                            .help("Synced to Android")
                    } else {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .foregroundColor(.orange)
                            .font(.caption)
                            .help("Pending sync to Android")
                    }
                }
                HStack(spacing: 6) {
                    Text(contact.formattedPhoneNumber)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text("•")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(contact.phoneType)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    if let email = contact.email, !email.isEmpty {
                        Text("•")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text(email)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                    }
                }
            }

            Spacer()

            // Action buttons on hover
            if isHovered {
                Button(action: onEdit) {
                    Image(systemName: "pencil")
                        .foregroundColor(.blue)
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.borderless)
                .help("Edit contact")

                Button(action: { showDeleteConfirmation = true }) {
                    Image(systemName: "trash")
                        .foregroundColor(.red)
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.borderless)
                .help("Delete contact")

                // Call button
                Button(action: {
                    appState.makeCall(to: contact.phoneNumber)
                }) {
                    Image(systemName: "phone.fill")
                        .foregroundColor(.green)
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.borderless)
                .help("Call contact")

                // Message button
                Button(action: {
                    let conversation = Conversation(
                        id: contact.normalizedNumber,
                        address: contact.phoneNumber,
                        contactName: contact.displayName,
                        lastMessage: "",
                        timestamp: Date(),
                        unreadCount: 0,
                        allAddresses: [contact.phoneNumber],
                        isPinned: false,
                        isArchived: false,
                        isBlocked: false,
                        avatarColor: nil
                    )
                    appState.selectedConversation = conversation
                    appState.selectedTab = .messages
                }) {
                    Image(systemName: "message.fill")
                        .foregroundColor(.blue)
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.borderless)
                .help("Send message")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(isHovered ? Color(nsColor: .controlBackgroundColor) : Color.clear)
        .onHover { hovering in
            isHovered = hovering
        }
        .alert("Delete Contact", isPresented: $showDeleteConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                onDelete()
            }
        } message: {
            Text("Are you sure you want to delete \"\(contact.displayName)\"? This will also remove it from your Android phone.")
        }
    }
}

// MARK: - Contact Edit Sheet

enum ContactEditMode {
    case create
    case edit(DesktopContact)
}

struct ContactEditSheet: View {
    let mode: ContactEditMode
    let onSave: (String, String, String, String?, String?) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var name: String = ""
    @State private var phoneNumber: String = ""
    @State private var phoneType: PhoneType = .mobile
    @State private var email: String = ""
    @State private var notes: String = ""
    @State private var isSaving = false

    var isValid: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty &&
        !phoneNumber.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var title: String {
        switch mode {
        case .create: return "New Contact"
        case .edit: return "Edit Contact"
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Button("Cancel") {
                    dismiss()
                }
                .keyboardShortcut(.escape)

                Spacer()

                Text(title)
                    .font(.headline)

                Spacer()

                Button("Save") {
                    saveContact()
                }
                .keyboardShortcut(.return)
                .disabled(!isValid || isSaving)
            }
            .padding()

            Divider()

            // Form
            Form {
                Section {
                    TextField("Name", text: $name)
                        .textFieldStyle(.roundedBorder)

                    TextField("Phone Number", text: $phoneNumber)
                        .textFieldStyle(.roundedBorder)

                    Picker("Phone Type", selection: $phoneType) {
                        ForEach(PhoneType.allCases, id: \.self) { type in
                            Text(type.displayName).tag(type)
                        }
                    }
                }

                Section {
                    TextField("Email (optional)", text: $email)
                        .textFieldStyle(.roundedBorder)

                    TextField("Notes (optional)", text: $notes, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                        .lineLimit(3...6)
                }

                Section {
                    HStack {
                        Image(systemName: "info.circle")
                            .foregroundColor(.blue)
                        Text("This contact will automatically sync to your Android phone.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }
            .padding()
        }
        .frame(width: 400, height: 380)
        .onAppear {
            if case .edit(let contact) = mode {
                name = contact.displayName
                phoneNumber = contact.phoneNumber
                phoneType = PhoneType(rawValue: contact.phoneType) ?? .mobile
                email = contact.email ?? ""
                notes = contact.notes ?? ""
            }
        }
    }

    private func saveContact() {
        isSaving = true

        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        let trimmedPhone = phoneNumber.trimmingCharacters(in: .whitespaces)
        let trimmedEmail = email.trimmingCharacters(in: .whitespaces)
        let trimmedNotes = notes.trimmingCharacters(in: .whitespaces)

        onSave(
            trimmedName,
            trimmedPhone,
            phoneType.rawValue,
            trimmedEmail.isEmpty ? nil : trimmedEmail,
            trimmedNotes.isEmpty ? nil : trimmedNotes
        )

        dismiss()
    }
}

// MARK: - Contacts Store

class ContactsStore: ObservableObject {
    @Published var contacts: [Contact] = []
    @Published var desktopContacts: [DesktopContact] = []
    @Published var isLoading = true

    private var contactsListenerHandle: DatabaseHandle?
    private var desktopContactsListenerHandle: DatabaseHandle?
    private var currentUserId: String?

    func startListening(userId: String) {
        currentUserId = userId
        isLoading = true

        // Remove existing listeners if any
        stopListening()

        // Start listening for Android contacts
        contactsListenerHandle = FirebaseService.shared.listenToContacts(userId: userId) { [weak self] contacts in
            DispatchQueue.main.async {
                self?.contacts = contacts
                self?.updateLoadingState()
            }
        }

        // Start listening for desktop-created contacts
        desktopContactsListenerHandle = FirebaseService.shared.listenToDesktopContacts(userId: userId) { [weak self] contacts in
            DispatchQueue.main.async {
                self?.desktopContacts = contacts
                self?.updateLoadingState()
            }
        }
    }

    private func updateLoadingState() {
        // Consider loading complete once we've received at least one update
        isLoading = false
    }

    func stopListening() {
        if let handle = contactsListenerHandle, let userId = currentUserId {
            FirebaseService.shared.removeContactsListener(userId: userId, handle: handle)
        }
        if let handle = desktopContactsListenerHandle, let userId = currentUserId {
            FirebaseService.shared.removeDesktopContactsListener(userId: userId, handle: handle)
        }
        contactsListenerHandle = nil
        desktopContactsListenerHandle = nil
    }

    deinit {
        stopListening()
    }
}
