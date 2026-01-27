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
import FirebaseStorage

class PhotoSyncService: ObservableObject {
    static let shared = PhotoSyncService()

    @Published var recentPhotos: [SyncedPhoto] = []
    @Published var isLoading: Bool = false
    @Published var lastSyncTime: Date?
    @Published var isPremiumFeature: Bool = true // Photo sync is now a premium feature

    private let database = Database.database()
    private let storage = Storage.storage()
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
        case .subscribed, .lifetime:
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
              let thumbnailUrl = data["thumbnailUrl"] as? String,
              let dateTaken = data["dateTaken"] as? Double else {
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
            thumbnailUrl: thumbnailUrl,
            width: width,
            height: height,
            size: size,
            mimeType: mimeType,
            syncedAt: Date(timeIntervalSince1970: syncedAt / 1000),
            localPath: cacheDirectory.appendingPathComponent("\(id).jpg")
        )
    }

    /// Download photo thumbnail
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

        // Download from URL
        guard let url = URL(string: photo.thumbnailUrl) else { return }

        let task = URLSession.shared.downloadTask(with: url) { [weak self] tempUrl, response, error in
            guard let self = self,
                  let tempUrl = tempUrl,
                  error == nil else {
                print("PhotoSyncService: Error downloading thumbnail: \(error?.localizedDescription ?? "unknown")")
                return
            }

            do {
                // Move to cache
                if FileManager.default.fileExists(atPath: photo.localPath.path) {
                    try FileManager.default.removeItem(at: photo.localPath)
                }
                try FileManager.default.moveItem(at: tempUrl, to: photo.localPath)

                // Update UI
                DispatchQueue.main.async {
                    if let index = self.recentPhotos.firstIndex(where: { $0.id == photo.id }) {
                        self.recentPhotos[index].isDownloaded = true
                        self.recentPhotos[index].thumbnail = NSImage(contentsOf: photo.localPath)
                    }
                }

            } catch {
                print("PhotoSyncService: Error saving thumbnail: \(error)")
            }
        }

        task.resume()
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
