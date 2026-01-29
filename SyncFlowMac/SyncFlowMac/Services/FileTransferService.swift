//
//  FileTransferService.swift
//  SyncFlowMac
//
//  Created for SyncFlow cross-platform file transfer functionality.
//
//  OVERVIEW:
//  =========
//  This service handles bidirectional file transfers between macOS and Android devices
//  using Cloudflare R2 for cloud storage and Firebase Realtime Database for coordination.
//
//  SYNC MECHANISM:
//  ===============
//  1. Mac to Android (Upload):
//     - User initiates file upload via sendFile(url:)
//     - Service requests a presigned upload URL from R2 via Cloud Functions
//     - File is uploaded directly to R2 using the presigned URL
//     - Upload is confirmed via Cloud Function to record usage metrics
//     - Transfer record is created in Firebase Realtime Database under users/{userId}/file_transfers
//     - Android device listens for this record and downloads the file
//
//  2. Android to Mac (Download):
//     - Service maintains a real-time listener on users/{userId}/file_transfers
//     - When Android creates a transfer record with source="android" and status="pending",
//       the listener triggers handleIncomingTransfer()
//     - Service requests presigned download URL from R2 via Cloud Functions
//     - File is downloaded and saved to ~/Downloads/SyncFlow/
//     - Status is updated in Firebase, and the R2 file is deleted after successful download
//
//  FIREBASE REAL-TIME LISTENER PATTERNS:
//  =====================================
//  - Uses .childAdded observer on file_transfers node to detect new incoming files
//  - Listener is started when user is configured and stopped on user change or logout
//  - Only processes transfers where source="android" and status="pending"
//  - Includes a 5-minute recency check to ignore stale transfer records
//
//  THREADING/ASYNC CONSIDERATIONS:
//  ===============================
//  - Firebase listeners fire on the main thread by default
//  - Async Task blocks are used for download/upload operations to avoid blocking
//  - UI state updates (latestTransfer, incomingFiles) are dispatched to main thread
//  - processingFileIds Set prevents duplicate processing of the same transfer
//
//  ERROR HANDLING:
//  ===============
//  - File size validation occurs before upload/download
//  - Network errors are caught and reflected in TransferStatus
//  - Failed transfers update status in Firebase with error message
//  - User notifications are shown for both success and failure states
//
//  RETRY/CONFLICT RESOLUTION:
//  ==========================
//  - No automatic retry mechanism implemented; failures require user re-initiation
//  - Duplicate file name conflicts are resolved by appending " (n)" suffix
//  - processingFileIds Set prevents concurrent processing of the same transfer
//

import Foundation
import Combine
import FirebaseAuth
import FirebaseDatabase
import FirebaseFunctions
import UniformTypeIdentifiers
import UserNotifications

// MARK: - FileTransferService

/// Service responsible for bidirectional file transfers between macOS and Android.
///
/// This singleton service manages:
/// - Uploading files from Mac to Android via Cloudflare R2
/// - Downloading files from Android to Mac via Cloudflare R2
/// - Transfer progress tracking and status updates
/// - File size limit enforcement based on subscription tier
///
/// Usage:
/// ```swift
/// // Configure with user ID after authentication
/// FileTransferService.shared.configure(userId: "user123")
///
/// // Send a file to Android
/// FileTransferService.shared.sendFile(url: fileURL)
///
/// // Observe transfer status
/// FileTransferService.shared.$latestTransfer
///     .sink { status in /* handle status updates */ }
/// ```
class FileTransferService: ObservableObject {

    // MARK: - Singleton

    /// Shared singleton instance for app-wide file transfer operations
    static let shared = FileTransferService()

    // MARK: - Types

    /// Represents the current state of a file transfer operation.
    /// Used to track progress and display appropriate UI feedback.
    enum TransferState: String {
        case uploading      // File is being uploaded to R2
        case downloading    // File is being downloaded from R2
        case sent           // Upload completed successfully
        case received       // Download completed successfully
        case failed         // Transfer failed with error
    }

    /// Tracks the status of an active or completed transfer.
    /// Published via latestTransfer for UI observation.
    struct TransferStatus: Identifiable {
        let id: String
        let fileName: String
        let state: TransferState
        let progress: Double
        let timestamp: Date
        let error: String?
    }

    /// Represents a file transfer initiated from Android that is pending download.
    /// Parsed from Firebase Realtime Database transfer records.
    struct IncomingFile: Identifiable {
        let id: String          // Firebase record key
        let fileName: String    // Original file name from Android
        let fileSize: Int64     // File size in bytes
        let contentType: String // MIME type (e.g., "image/jpeg")
        let r2Key: String       // R2 storage key for download
        let timestamp: Date     // When the transfer was initiated
    }

    // MARK: - Transfer Limits

    // Tiered limits (no daily limits - R2 has free egress)
    /// Maximum file size for free tier users (50MB)
    static let maxFileSizeFree: Int64 = 50 * 1024 * 1024      // 50MB for free users
    /// Maximum file size for pro/paid tier users (1GB)
    static let maxFileSizePro: Int64 = 1024 * 1024 * 1024     // 1GB for pro users

    /// Encapsulates the current user's transfer limits based on subscription
    struct TransferLimits {
        let maxFileSize: Int64  // Maximum allowed file size in bytes
        let isPro: Bool         // Whether user has pro subscription
    }

    // MARK: - Published Properties

    /// The most recent transfer status, observed by UI for progress/status display
    @Published private(set) var latestTransfer: TransferStatus?
    /// List of files pending download from Android (currently unused but available for UI)
    @Published private(set) var incomingFiles: [IncomingFile] = []
    /// Current user's transfer limits based on subscription tier
    @Published private(set) var transferLimits: TransferLimits?

    // MARK: - Private Properties

    /// Firebase Auth instance for authentication state
    private let auth = Auth.auth()
    /// Firebase Realtime Database instance for transfer coordination
    private let database = Database.database()
    /// Firebase Cloud Functions instance for R2 presigned URL generation
    private let functions = Functions.functions()
    /// Legacy max file size (use tiered limits instead via transferLimits)
    private let maxFileSize: Int64 = 50 * 1024 * 1024  // Legacy, use tiered limits

    /// Current authenticated user's ID (sync group user ID)
    private var currentUserId: String?
    /// Handle for the Firebase listener on incoming file transfers
    private var fileTransferListener: DatabaseHandle?
    /// Set of file IDs currently being processed to prevent duplicate downloads
    /// This is important because Firebase may deliver the same childAdded event multiple times
    private var processingFileIds: Set<String> = []

    // MARK: - Initialization

    /// Private initializer enforces singleton pattern
    private init() {}

    // MARK: - Transfer Limit Validation

    /// Refreshes the user's transfer limits by checking their subscription status.
    /// Called before each transfer to ensure limits are current.
    ///
    /// Threading: Uses MainActor.run to update published property on main thread.
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

    /// Checks Firebase to determine if user has a pro subscription.
    /// Reads from users/{userId}/subscription/plan in Realtime Database.
    ///
    /// - Returns: true if user has any non-free plan, false otherwise
    /// - Note: Defaults to true on connectivity errors to allow transfers
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

    /// Validates whether a file of the given size can be transferred.
    /// Refreshes limits before checking to ensure current subscription status.
    ///
    /// - Parameter fileSize: Size of the file in bytes
    /// - Returns: Tuple indicating if transfer is allowed and reason if not
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

    // MARK: - Configuration

    /// Configures the service for a specific user.
    /// Call this after user authentication or when sync group changes.
    ///
    /// - Parameter userId: The sync group user ID, or nil to stop listening
    ///
    /// Behavior:
    /// - If userId changes, stops existing listener before starting new one
    /// - If userId is nil, only stops listener (logout scenario)
    /// - If userId is the same, no action is taken
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

    // MARK: - Firebase Real-Time Listeners (Android to Mac)

    /// Starts the Firebase listener for incoming file transfers from Android.
    ///
    /// Firebase Listener Pattern:
    /// - Observes .childAdded on users/{userId}/file_transfers
    /// - Each new child triggers handleIncomingTransfer()
    /// - Only one listener active at a time (checked via fileTransferListener != nil)
    ///
    /// Threading: Firebase callbacks execute on main thread by default
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

    /// Stops the Firebase listener and cleans up the observer handle.
    /// Safe to call even if no listener is active.
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

    // MARK: - Incoming Transfer Processing

    /// Handles a new transfer record from Firebase.
    ///
    /// Validation checks:
    /// 1. Data must be valid dictionary with required fields
    /// 2. Source must be "android" (ignore Mac-initiated transfers)
    /// 3. Status must be "pending" (ignore already-processed transfers)
    /// 4. File ID must not be in processingFileIds (prevent duplicates)
    /// 5. Transfer must be recent (within 5 minutes) to ignore stale records
    ///
    /// - Parameter snapshot: Firebase DataSnapshot containing transfer record
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

    // MARK: - Download Logic

    /// Downloads a file from R2 storage to the local Downloads folder.
    ///
    /// Download Flow:
    /// 1. Validate file size against maxFileSize limit
    /// 2. Update Firebase status to "downloading"
    /// 3. Get presigned download URL (from R2 via Cloud Function or legacy Firebase Storage)
    /// 4. Download file to temp directory using URLSession
    /// 5. Move file to ~/Downloads/SyncFlow/ with duplicate name handling
    /// 6. Update Firebase status to "downloaded"
    /// 7. Delete file from R2 to free storage
    /// 8. Clean up transfer record after 5 second delay
    ///
    /// Error Handling:
    /// - File size exceeded: Updates status to "failed" in Firebase
    /// - Download failures: Caught and reflected in status with error message
    /// - Notifications shown for both success and failure
    ///
    /// Threading: Runs in async Task context, UI updates via DispatchQueue.main
    ///
    /// - Parameters:
    ///   - fileId: Unique identifier for this transfer
    ///   - fileName: Original file name from Android
    ///   - fileSize: Expected file size in bytes
    ///   - contentType: MIME type of the file
    ///   - r2Key: R2 storage key (nil if using legacy Firebase Storage)
    ///   - legacyDownloadUrl: Direct download URL for legacy transfers
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

    // MARK: - Firebase Status Updates

    /// Updates the status of an incoming transfer in Firebase.
    /// Used to coordinate state between devices (e.g., "downloading", "downloaded", "failed").
    ///
    /// - Parameters:
    ///   - fileId: The transfer record ID
    ///   - status: New status string
    ///   - error: Optional error message for failed transfers
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

    // MARK: - User Notifications

    /// Displays a local macOS notification to the user.
    /// Used to notify of transfer progress, completion, or failure.
    ///
    /// - Parameters:
    ///   - title: Notification title
    ///   - body: Notification body text
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

    // MARK: - Upload Logic (Mac to Android)

    /// Public entry point for sending a file to Android.
    /// Initiates an async upload task.
    ///
    /// - Parameter url: Local file URL to upload
    func sendFile(url: URL) {
        Task {
            await uploadFile(url: url)
        }
    }

    /// Uploads a file to R2 storage and creates a transfer record in Firebase.
    ///
    /// Upload Flow:
    /// 1. Validate user is paired and authenticated
    /// 2. Validate file exists and get file size
    /// 3. Check file size against tiered limits (canTransfer)
    /// 4. Get presigned upload URL from R2 via Cloud Function
    /// 5. Upload file directly to R2 using PUT request
    /// 6. Confirm upload via Cloud Function (records usage metrics)
    /// 7. Create transfer record in Firebase with source="macos", status="pending"
    /// 8. Android device listens for this record and initiates download
    ///
    /// Error Handling:
    /// - Pre-flight validation errors fail immediately with status update
    /// - Cloud Function errors are parsed for user-friendly messages
    /// - Network errors caught and reflected in TransferStatus
    ///
    /// Threading: Async function, UI updates dispatched to main thread
    ///
    /// - Parameter url: Local file URL to upload
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

    // MARK: - UI State Management

    /// Updates the published latestTransfer property for UI observation.
    /// Dispatches to main thread to ensure safe UI updates.
    ///
    /// - Parameters:
    ///   - id: Transfer identifier
    ///   - fileName: Name of the file being transferred
    ///   - state: Current transfer state
    ///   - progress: Progress value from 0.0 to 1.0
    ///   - error: Error message if transfer failed
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
