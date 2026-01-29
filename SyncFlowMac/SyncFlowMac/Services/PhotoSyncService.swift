//
//  PhotoSyncService.swift
//  SyncFlowMac
//
//  Service to receive and display synced photos from Android
//

import Foundation
import AppKit
import Combine
import FirebaseDatabase
import FirebaseFunctions

class PhotoSyncService: ObservableObject {
    static let shared = PhotoSyncService()

    @Published var recentPhotos: [SyncedPhoto] = []
    @Published var isLoading: Bool = false
    @Published var lastSyncTime: Date?
    @Published var isPremiumFeature: Bool = true // Photo sync is now a premium feature

    private let database = Database.database()
    private let functions = Functions.functions(region: "us-central1")
    private var photosHandle: DatabaseHandle?
    private var currentUserId: String?

    // Local cache directory
    private let cacheDirectory: URL

    private init() {
        // Create cache directory
        let cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        cacheDirectory = cacheDir.appendingPathComponent("SyncFlowPhotos", isDirectory: true)

        try? FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
    }

    /// Check if user has premium access for photo sync
    var hasPremiumAccess: Bool {
        let status = SubscriptionService.shared.subscriptionStatus
        switch status {
        case .subscribed, .threeYear:
            return true
        case .trial, .notSubscribed, .expired:
            return false
        }
    }

    /// Start listening for synced photos (requires premium)
    func startSync(userId: String) {
        // Check subscription status - photo sync is premium only
        guard hasPremiumAccess else {
            return
        }

        currentUserId = userId
        startListeningForPhotos(userId: userId)
    }

    /// Stop syncing
    func stopSync() {
        stopListeningForPhotos()
        currentUserId = nil
    }

    /// Listen for photo updates from Firebase
    private func startListeningForPhotos(userId: String) {
        let photosRef = database.reference()
            .child("users")
            .child(userId)
            .child("photos")

        photosHandle = photosRef.observe(.value) { [weak self] snapshot in
            guard let self = self else { return }

            var photos: [SyncedPhoto] = []

            for child in snapshot.children {
                guard let snapshot = child as? DataSnapshot,
                      let data = snapshot.value as? [String: Any] else { continue }

                if let photo = self.parsePhoto(id: snapshot.key, data: data) {
                    photos.append(photo)
                }
            }

            // Sort by date taken, newest first
            photos.sort { $0.dateTaken > $1.dateTaken }

            DispatchQueue.main.async {
                self.recentPhotos = photos
                self.lastSyncTime = Date()
            }

            // Download thumbnails
            for photo in photos {
                self.downloadThumbnail(photo: photo)
            }
        }
    }

    private func stopListeningForPhotos() {
        guard let userId = currentUserId, let handle = photosHandle else { return }

        database.reference()
            .child("users")
            .child(userId)
            .child("photos")
            .removeObserver(withHandle: handle)

        photosHandle = nil
    }

    /// Parse photo data from Firebase
    private func parsePhoto(id: String, data: [String: Any]) -> SyncedPhoto? {
        guard let fileName = data["fileName"] as? String,
              let dateTaken = data["dateTaken"] as? Double,
              let r2Key = data["r2Key"] as? String else {
            return nil
        }

        let width = data["width"] as? Int ?? 0
        let height = data["height"] as? Int ?? 0
        let size = data["size"] as? Int64 ?? 0
        let mimeType = data["mimeType"] as? String ?? "image/jpeg"
        let syncedAt = data["syncedAt"] as? Double ?? Date().timeIntervalSince1970 * 1000

        return SyncedPhoto(
            id: id,
            fileName: fileName,
            dateTaken: Date(timeIntervalSince1970: dateTaken / 1000),
            thumbnailUrl: r2Key,
            width: width,
            height: height,
            size: size,
            mimeType: mimeType,
            syncedAt: Date(timeIntervalSince1970: syncedAt / 1000),
            localPath: cacheDirectory.appendingPathComponent("\(id).jpg")
        )
    }

    /// Download photo thumbnail from R2 storage
    private func downloadThumbnail(photo: SyncedPhoto) {
        // Check if already cached
        if FileManager.default.fileExists(atPath: photo.localPath.path) {
            DispatchQueue.main.async { [weak self] in
                if let index = self?.recentPhotos.firstIndex(where: { $0.id == photo.id }) {
                    self?.recentPhotos[index].isDownloaded = true
                    self?.recentPhotos[index].thumbnail = NSImage(contentsOf: photo.localPath)
                }
            }
            return
        }

        // Get presigned download URL from R2 via Cloud Function
        let r2Key = photo.thumbnailUrl
        Task {
            do {
                let result = try await functions.httpsCallable("getR2DownloadUrl").call(["r2Key": r2Key])
                guard let response = result.data as? [String: Any],
                      let downloadUrl = response["downloadUrl"] as? String,
                      let url = URL(string: downloadUrl) else {
                    print("PhotoSyncService: Failed to get R2 download URL for \(r2Key)")
                    return
                }

                // Download from presigned URL
                let (tempUrl, _) = try await URLSession.shared.download(from: url)

                // Move to cache
                if FileManager.default.fileExists(atPath: photo.localPath.path) {
                    try FileManager.default.removeItem(at: photo.localPath)
                }
                try FileManager.default.moveItem(at: tempUrl, to: photo.localPath)

                // Update UI
                await MainActor.run {
                    if let index = self.recentPhotos.firstIndex(where: { $0.id == photo.id }) {
                        self.recentPhotos[index].isDownloaded = true
                        self.recentPhotos[index].thumbnail = NSImage(contentsOf: photo.localPath)
                    }
                }
            } catch {
                print("PhotoSyncService: Error downloading thumbnail: \(error)")
            }
        }
    }

    /// Open photo in Preview
    func openPhoto(_ photo: SyncedPhoto) {
        if photo.isDownloaded {
            NSWorkspace.shared.open(photo.localPath)
        }
    }

    /// Save photo to Downloads
    func savePhoto(_ photo: SyncedPhoto) {
        guard photo.isDownloaded else { return }

        let downloadsUrl = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
        let destination = downloadsUrl.appendingPathComponent(photo.fileName)

        do {
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.copyItem(at: photo.localPath, to: destination)

            // Show in Finder
            NSWorkspace.shared.selectFile(destination.path, inFileViewerRootedAtPath: "")

        } catch {
            print("PhotoSyncService: Error saving photo: \(error)")
        }
    }

    /// Clear photo cache
    func clearCache() {
        do {
            let files = try FileManager.default.contentsOfDirectory(at: cacheDirectory, includingPropertiesForKeys: nil)
            for file in files {
                try FileManager.default.removeItem(at: file)
            }

            DispatchQueue.main.async {
                for index in self.recentPhotos.indices {
                    self.recentPhotos[index].isDownloaded = false
                    self.recentPhotos[index].thumbnail = nil
                }
            }

        } catch {
            print("PhotoSyncService: Error clearing cache: \(error)")
        }
    }

    /// Reduce sync frequency for battery saving
    func reduceSyncFrequency() {
        // Reduce sync frequency by increasing polling interval
        // This would typically reduce the frequency of photo sync operations
    }

    /// Pause photo sync temporarily
    func pauseSync() {
        stopSync()
    }

    /// Resume photo sync
    func resumeSync() {
        if let userId = currentUserId {
            startSync(userId: userId)
        }
    }
}

/// Model for synced photo
struct SyncedPhoto: Identifiable {
    let id: String
    let fileName: String
    let dateTaken: Date
    let thumbnailUrl: String
    let width: Int
    let height: Int
    let size: Int64
    let mimeType: String
    let syncedAt: Date
    let localPath: URL

    var isDownloaded: Bool = false
    var thumbnail: NSImage? = nil

    var formattedDate: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: dateTaken)
    }

    var formattedSize: String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: size)
    }
}
