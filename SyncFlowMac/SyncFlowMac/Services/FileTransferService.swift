//
//  FileTransferService.swift
//  SyncFlowMac
//
//  Handles file transfers between Mac and Android via Cloudflare R2.
//  - Uploads files to R2 via presigned URLs and notifies Android via Realtime Database.
//  - Listens for incoming files from Android and downloads them from R2.
//

import Foundation
import Combine
import FirebaseAuth
import FirebaseDatabase
import FirebaseFunctions
import UniformTypeIdentifiers
import UserNotifications

class FileTransferService: ObservableObject {
    static let shared = FileTransferService()

    enum TransferState: String {
        case uploading
        case downloading
        case sent
        case received
        case failed
    }

    struct TransferStatus: Identifiable {
        let id: String
        let fileName: String
        let state: TransferState
        let progress: Double
        let timestamp: Date
        let error: String?
    }

    struct IncomingFile: Identifiable {
        let id: String
        let fileName: String
        let fileSize: Int64
        let contentType: String
        let r2Key: String
        let timestamp: Date
    }

    // Tiered limits (no daily limits - R2 has free egress)
    static let maxFileSizeFree: Int64 = 50 * 1024 * 1024      // 50MB for free users
    static let maxFileSizePro: Int64 = 1024 * 1024 * 1024     // 1GB for pro users

    struct TransferLimits {
        let maxFileSize: Int64
        let isPro: Bool
    }

    @Published private(set) var latestTransfer: TransferStatus?
    @Published private(set) var incomingFiles: [IncomingFile] = []
    @Published private(set) var transferLimits: TransferLimits?

    private let auth = Auth.auth()
    private let database = Database.database()
    private let functions = Functions.functions()
    private let maxFileSize: Int64 = 50 * 1024 * 1024  // Legacy, use tiered limits

    private var currentUserId: String?
    private var fileTransferListener: DatabaseHandle?
    private var processingFileIds: Set<String> = []

    private init() {}

    // MARK: - Transfer Limits

    func refreshLimits() async {
        let isPro = await checkIsPro()
        let maxSize = isPro ? Self.maxFileSizePro : Self.maxFileSizeFree

        await MainActor.run {
            self.transferLimits = TransferLimits(
                maxFileSize: maxSize,
                isPro: isPro
            )
        }
    }

    private func checkIsPro() async -> Bool {
        guard let userId = currentUserId else { return false }

        do {
            // Check user's subscription directly
            let snapshot = try await database.reference()
                .child("users")
                .child(userId)
                .child("subscription")
                .child("plan")
                .getData()

            if let plan = snapshot.value as? String {
                return plan != "free"
            }
            return false
        } catch {
            print("[FileTransfer] Error checking pro status: \(error)")
            // Default to allowing transfers if we can't check (connectivity issues)
            return true
        }
    }

    func canTransfer(fileSize: Int64) async -> (allowed: Bool, reason: String?) {
        await refreshLimits()

        guard let limits = transferLimits else {
            return (false, "Unable to check limits")
        }

        if fileSize > limits.maxFileSize {
            let maxMB = limits.maxFileSize / (1024 * 1024)
            let upgradeHint = limits.isPro ? "" : " (Upgrade to Pro for 1GB)"
            return (false, "File too large. Max size: \(maxMB)MB\(upgradeHint)")
        }

        return (true, nil)
    }

    func configure(userId: String?) {
        // Stop existing listener if user changed
        if currentUserId != userId {
            stopListening()
        }
        currentUserId = userId

        // Start listening for incoming files from Android
        if userId != nil {
            startListening()
        }
    }

    // MARK: - Incoming File Listener (Android → Mac)

    private func startListening() {
        guard let userId = currentUserId else { return }
        guard fileTransferListener == nil else { return }

        let transfersRef = database.reference()
            .child("users")
            .child(userId)
            .child("file_transfers")

        fileTransferListener = transfersRef.observe(.childAdded) { [weak self] snapshot in
            self?.handleIncomingTransfer(snapshot)
        }

        print("[FileTransfer] Started listening for incoming files")
    }

    private func stopListening() {
        guard let userId = currentUserId, let handle = fileTransferListener else { return }

        database.reference()
            .child("users")
            .child(userId)
            .child("file_transfers")
            .removeObserver(withHandle: handle)

        fileTransferListener = nil
        print("[FileTransfer] Stopped listening for incoming files")
    }

    private func handleIncomingTransfer(_ snapshot: DataSnapshot) {
        guard let data = snapshot.value as? [String: Any] else { return }

        let fileId = snapshot.key
        let source = data["source"] as? String ?? ""
        let status = data["status"] as? String ?? ""

        // Only process files from Android that are pending
        guard source == "android", status == "pending" else { return }

        // Prevent duplicate processing
        guard !processingFileIds.contains(fileId) else { return }

        guard let fileName = data["fileName"] as? String else { return }

        // Support both R2 (r2Key) and legacy Firebase Storage (downloadUrl)
        let r2Key = data["r2Key"] as? String
        let downloadUrl = data["downloadUrl"] as? String

        guard r2Key != nil || downloadUrl != nil else { return }

        let fileSize = data["fileSize"] as? Int64 ?? 0
        let contentType = data["contentType"] as? String ?? "application/octet-stream"
        let timestamp = data["timestamp"] as? Double ?? Date().timeIntervalSince1970 * 1000

        // Check if file is recent (within last 5 minutes)
        let fileDate = Date(timeIntervalSince1970: timestamp / 1000)
        guard Date().timeIntervalSince(fileDate) < 300 else {
            print("[FileTransfer] Ignoring old file transfer: \(fileId)")
            return
        }

        print("[FileTransfer] Incoming file from Android: \(fileName) (\(fileSize) bytes)")

        processingFileIds.insert(fileId)

        // Show notification and download
        Task {
            await downloadIncomingFile(
                fileId: fileId,
                fileName: fileName,
                fileSize: fileSize,
                contentType: contentType,
                r2Key: r2Key,
                legacyDownloadUrl: downloadUrl
            )
            processingFileIds.remove(fileId)
        }
    }

    private func downloadIncomingFile(
        fileId: String,
        fileName: String,
        fileSize: Int64,
        contentType: String,
        r2Key: String?,
        legacyDownloadUrl: String?
    ) async {
        guard let userId = currentUserId else { return }

        // Check file size
        if fileSize > maxFileSize {
            print("[FileTransfer] File too large: \(fileSize) bytes")
            await updateIncomingStatus(fileId: fileId, status: "failed", error: "File too large")
            return
        }

        updateStatus(id: fileId, fileName: fileName, state: .downloading, progress: 0, error: nil)
        showNotification(title: "Downloading file", body: fileName)

        // Update status to downloading
        await updateIncomingStatus(fileId: fileId, status: "downloading")

        do {
            // Get download URL - either from R2 or legacy Firebase Storage
            let downloadUrl: URL

            if let r2Key = r2Key {
                // Get presigned download URL from R2
                let callable = functions.httpsCallable("getR2DownloadUrl")
                let result = try await callable.call([
                    "syncGroupUserId": userId,
                    "fileKey": r2Key
                ])

                guard let data = result.data as? [String: Any],
                      let urlString = data["downloadUrl"] as? String,
                      let url = URL(string: urlString) else {
                    throw NSError(domain: "FileTransfer", code: 2, userInfo: [NSLocalizedDescriptionKey: "Failed to get download URL"])
                }
                downloadUrl = url
            } else if let legacyUrl = legacyDownloadUrl, let url = URL(string: legacyUrl) {
                downloadUrl = url
            } else {
                throw NSError(domain: "FileTransfer", code: 3, userInfo: [NSLocalizedDescriptionKey: "No download URL available"])
            }

            // Download to temp file
            let tempDir = FileManager.default.temporaryDirectory
            let tempFile = tempDir.appendingPathComponent("download_\(fileName)")

            // Download using URLSession
            let (localUrl, response) = try await URLSession.shared.download(from: downloadUrl)

            guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
                throw NSError(domain: "FileTransfer", code: 4, userInfo: [NSLocalizedDescriptionKey: "Download failed"])
            }

            // Move from temp download location to our temp file
            try? FileManager.default.removeItem(at: tempFile)
            try FileManager.default.moveItem(at: localUrl, to: tempFile)

            updateStatus(id: fileId, fileName: fileName, state: .downloading, progress: 0.9, error: nil)

            // Move to Downloads folder
            let downloadsURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
            let syncFlowDir = downloadsURL.appendingPathComponent("SyncFlow")

            try? FileManager.default.createDirectory(at: syncFlowDir, withIntermediateDirectories: true)

            var destURL = syncFlowDir.appendingPathComponent(fileName)

            // Handle duplicate file names
            var counter = 1
            let fileExtension = destURL.pathExtension
            let baseName = destURL.deletingPathExtension().lastPathComponent
            while FileManager.default.fileExists(atPath: destURL.path) {
                let newName = "\(baseName) (\(counter)).\(fileExtension)"
                destURL = syncFlowDir.appendingPathComponent(newName)
                counter += 1
            }

            try FileManager.default.moveItem(at: tempFile, to: destURL)

            print("[FileTransfer] File saved to: \(destURL.path)")

            // Update status to downloaded
            await updateIncomingStatus(fileId: fileId, status: "downloaded")
            updateStatus(id: fileId, fileName: fileName, state: .received, progress: 1, error: nil)
            showNotification(title: "File received", body: "\(fileName) saved to Downloads/SyncFlow")

            // Delete file from R2 after successful download
            if let r2Key = r2Key {
                let deleteCallable = functions.httpsCallable("deleteR2File")
                try? await deleteCallable.call([
                    "syncGroupUserId": userId,
                    "fileKey": r2Key
                ])
            }

            // Clean up transfer record after delay
            try? await Task.sleep(nanoseconds: 5_000_000_000) // 5 seconds
            try? await database.reference()
                .child("users")
                .child(userId)
                .child("file_transfers")
                .child(fileId)
                .removeValue()

        } catch {
            print("[FileTransfer] Download error: \(error)")
            await updateIncomingStatus(fileId: fileId, status: "failed", error: error.localizedDescription)
            updateStatus(id: fileId, fileName: fileName, state: .failed, error: error.localizedDescription)
            showNotification(title: "Download failed", body: fileName)
        }
    }

    private func updateIncomingStatus(fileId: String, status: String, error: String? = nil) async {
        guard let userId = currentUserId else { return }

        var updates: [String: Any] = ["status": status]
        if let error = error {
            updates["error"] = error
        }

        try? await database.reference()
            .child("users")
            .child(userId)
            .child("file_transfers")
            .child(fileId)
            .updateChildValues(updates)
    }

    private func showNotification(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request)
    }

    func sendFile(url: URL) {
        Task {
            await uploadFile(url: url)
        }
    }

    private func uploadFile(url: URL) async {
        guard let userId = currentUserId else {
            updateStatus(id: UUID().uuidString, fileName: url.lastPathComponent, state: .failed, error: "Not paired")
            return
        }

        guard auth.currentUser != nil else {
            updateStatus(id: UUID().uuidString, fileName: url.lastPathComponent, state: .failed, error: "Not authenticated")
            return
        }

        let fileId = UUID().uuidString
        let fileName = url.lastPathComponent

        let fileSize = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
        if fileSize <= 0 {
            updateStatus(id: fileId, fileName: fileName, state: .failed, error: "Invalid file")
            return
        }

        // Check tiered limits (file size)
        let transferCheck = await canTransfer(fileSize: fileSize)
        if !transferCheck.allowed {
            updateStatus(id: fileId, fileName: fileName, state: .failed, error: transferCheck.reason)
            return
        }

        let contentType = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"

        updateStatus(id: fileId, fileName: fileName, state: .uploading, progress: 0, error: nil)

        do {
            // Step 1: Get presigned upload URL from R2
            let getUrlCallable = functions.httpsCallable("getR2UploadUrl")
            let urlResult = try await getUrlCallable.call([
                "syncGroupUserId": userId,
                "fileName": fileName,
                "contentType": contentType,
                "fileSize": fileSize,
                "transferType": "files"
            ])

            guard let urlData = urlResult.data as? [String: Any],
                  let uploadUrlString = urlData["uploadUrl"] as? String,
                  let uploadUrl = URL(string: uploadUrlString),
                  let r2Key = urlData["fileKey"] as? String else {
                throw NSError(domain: "FileTransfer", code: 10, userInfo: [NSLocalizedDescriptionKey: "Failed to get upload URL"])
            }

            updateStatus(id: fileId, fileName: fileName, state: .uploading, progress: 0.1, error: nil)

            // Step 2: Upload file directly to R2 using presigned URL
            let fileData = try Data(contentsOf: url)

            var request = URLRequest(url: uploadUrl)
            request.httpMethod = "PUT"
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
            request.setValue("\(fileSize)", forHTTPHeaderField: "Content-Length")

            let (_, response) = try await URLSession.shared.upload(for: request, from: fileData)

            guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
                throw NSError(domain: "FileTransfer", code: 11, userInfo: [NSLocalizedDescriptionKey: "Upload to R2 failed"])
            }

            updateStatus(id: fileId, fileName: fileName, state: .uploading, progress: 0.8, error: nil)

            // Step 3: Confirm upload to record usage
            let confirmCallable = functions.httpsCallable("confirmR2Upload")
            try await confirmCallable.call([
                "syncGroupUserId": userId,
                "fileKey": r2Key,
                "fileSize": fileSize,
                "transferType": "files"
            ])

            updateStatus(id: fileId, fileName: fileName, state: .uploading, progress: 0.9, error: nil)

            // Step 4: Create transfer record in database
            database.goOnline()

            let transferRef = database.reference()
                .child("users")
                .child(userId)
                .child("file_transfers")
                .child(fileId)

            let transferData: [String: Any] = [
                "fileName": fileName,
                "fileSize": fileSize,
                "contentType": contentType,
                "r2Key": r2Key,
                "source": "macos",
                "status": "pending",
                "timestamp": ServerValue.timestamp()
            ]

            try await transferRef.setValue(transferData)
            updateStatus(id: fileId, fileName: fileName, state: .sent, progress: 1, error: nil)

            // Refresh limits after upload
            await refreshLimits()

        } catch let error as NSError {
            // Check for specific R2 errors
            if let functionsError = error.userInfo[FunctionsErrorDetailsKey] as? String {
                updateStatus(id: fileId, fileName: fileName, state: .failed, error: functionsError)
            } else if error.domain == FunctionsErrorDomain {
                // Firebase Functions error
                let message = error.localizedDescription
                updateStatus(id: fileId, fileName: fileName, state: .failed, error: message)
            } else {
                updateStatus(id: fileId, fileName: fileName, state: .failed, error: error.localizedDescription)
            }
        }
    }

    private func updateStatus(
        id: String,
        fileName: String,
        state: TransferState,
        progress: Double = 0,
        error: String?
    ) {
        let timestamp = (latestTransfer?.id == id ? latestTransfer?.timestamp : Date()) ?? Date()
        DispatchQueue.main.async {
            self.latestTransfer = TransferStatus(
                id: id,
                fileName: fileName,
                state: state,
                progress: progress,
                timestamp: timestamp,
                error: error
            )
        }
    }

}
