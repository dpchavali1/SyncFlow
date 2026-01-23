//
//  FileTransferService.swift
//  SyncFlowMac
//
//  Uploads files to Firebase Storage and notifies Android via Realtime Database.
//

import Foundation
import Combine
import FirebaseAuth
import FirebaseDatabase
import FirebaseStorage
import UniformTypeIdentifiers

class FileTransferService: ObservableObject {
    static let shared = FileTransferService()

    enum TransferState: String {
        case uploading
        case sent
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

    @Published private(set) var latestTransfer: TransferStatus?

    private let auth = Auth.auth()
    private let database = Database.database()
    private let storage = Storage.storage()
    private let maxFileSize: Int64 = 50 * 1024 * 1024

    private var currentUserId: String?

    private init() {}

    func configure(userId: String?) {
        currentUserId = userId
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
        if fileSize > maxFileSize {
            updateStatus(id: fileId, fileName: fileName, state: .failed, error: "File too large (max 50MB)")
            return
        }

        let usageDecision = await UsageTracker.shared.isUploadAllowed(
            userId: userId,
            bytes: fileSize,
            countsTowardStorage: true
        )
        if !usageDecision.allowed {
            updateStatus(
                id: fileId,
                fileName: fileName,
                state: .failed,
                error: usageLimitMessage(reason: usageDecision.reason)
            )
            return
        }

        let contentType = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        let storagePath = "users/\(userId)/transfers/\(fileId)/\(fileName)"
        let storageRef = storage.reference().child(storagePath)

        let metadata = StorageMetadata()
        metadata.contentType = contentType

        updateStatus(id: fileId, fileName: fileName, state: .uploading, progress: 0, error: nil)

        let uploadTask = storageRef.putFile(from: url, metadata: metadata)
        let progressHandle = uploadTask.observe(.progress) { [weak self] snapshot in
            guard let self = self else { return }
            let progress = snapshot.progress?.fractionCompleted ?? 0
            self.updateStatus(id: fileId, fileName: fileName, state: .uploading, progress: progress, error: nil)
        }

        do {
            try await withCheckedThrowingContinuation { continuation in
                uploadTask.observe(.success) { _ in
                    continuation.resume()
                }
                uploadTask.observe(.failure) { snapshot in
                    let error = snapshot.error ?? NSError(domain: "FileTransfer", code: 1)
                    continuation.resume(throwing: error)
                }
            }

            let downloadURL = try await storageRef.downloadURL()

            let transferRef = database.reference()
                .child("users")
                .child(userId)
                .child("file_transfers")
                .child(fileId)

            let transferData: [String: Any] = [
                "fileName": fileName,
                "fileSize": fileSize,
                "contentType": contentType,
                "downloadUrl": downloadURL.absoluteString,
                "source": "macos",
                "status": "pending",
                "timestamp": ServerValue.timestamp()
            ]

            try await transferRef.setValue(transferData)
            updateStatus(id: fileId, fileName: fileName, state: .sent, progress: 1, error: nil)

            await UsageTracker.shared.recordUpload(
                userId: userId,
                bytes: fileSize,
                category: .file,
                countsTowardStorage: true
            )
        } catch {
            updateStatus(id: fileId, fileName: fileName, state: .failed, error: error.localizedDescription)
        }

        uploadTask.removeObserver(withHandle: progressHandle)
        uploadTask.removeAllObservers()
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

    private func usageLimitMessage(reason: String?) -> String {
        switch reason {
        case "trial_expired":
            return "Free trial expired. Upgrade to keep sharing files."
        case "monthly_quota":
            return "Monthly upload limit reached. Try again next month or upgrade."
        case "storage_quota":
            return "Storage limit reached. Free up space or upgrade your plan."
        default:
            return "Upload limit reached. Please try again later."
        }
    }
}
