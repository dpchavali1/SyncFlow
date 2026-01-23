# macOS SyncGroupManager Integration Guide

## Overview

Integrate SyncGroupManager into the macOS pairing flow to enable QR code generation and device group management.

## Integration Steps

### Step 1: Update FirebaseService.swift

**File:** `SyncFlowMac/SyncFlowMac/Services/FirebaseService.swift`

Add SyncGroupManager import and property:

```swift
import SyncGroupManager  // Add this import

class FirebaseService: NSObject, ObservableObject {
    static let shared = FirebaseService()

    // Existing properties...

    // Add this new property
    @Published var syncGroupId: String? {
        didSet {
            if let id = syncGroupId {
                UserDefaults.standard.set(id, forKey: "sync_group_id")
            }
        }
    }

    let syncGroupManager = SyncGroupManager.shared

    override init() {
        super.init()
        // Restore sync group ID on init
        self.syncGroupId = syncGroupManager.syncGroupId
    }
}
```

### Step 2: Update Pairing Initiation

**File:** `SyncFlowMac/SyncFlowMac/Services/FirebaseService.swift`

Add method to initiate sync group (called on first app launch):

```swift
/**
 * Initiate sync group pairing (called on first app launch or when not paired)
 */
func initiateSyncGroupPairing(completion: @escaping (Result<String, Error>) -> Void) {
    // Check if already has sync group
    if let existing = syncGroupManager.syncGroupId {
        completion(.success(existing))
        return
    }

    // Create new sync group
    syncGroupManager.createSyncGroup(deviceName: "macOS") { result in
        switch result {
        case .success(let groupId):
            self.syncGroupId = groupId
            completion(.success(groupId))

        case .failure(let error):
            completion(.failure(error))
        }
    }
}
```

### Step 3: Update PairingView.swift

**File:** `SyncFlowMac/SyncFlowMac/Views/PairingView.swift`

Replace the pairing view logic:

```swift
import SwiftUI
import QRCode

struct PairingView: View {
    @StateObject var firebaseService = FirebaseService.shared
    @State var qrCodeImage: NSImage?
    @State var isPaired = false
    @State var syncGroupId: String?
    @State var errorMessage: String?

    var body: some View {
        VStack(spacing: 20) {
            Text(isPaired ? "Device Paired ✅" : "Pair with Other Devices")
                .font(.title)

            if let qrImage = qrCodeImage {
                // Display QR code
                Image(nsImage: qrImage)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 300, height: 300)
                    .border(Color.gray)

                Text("Scan this code on Android or Web")
                    .font(.caption)
                    .foregroundColor(.secondary)

            } else if isPaired, let groupId = syncGroupId {
                VStack(spacing: 10) {
                    Text("✅ Paired to Group")
                        .font(.headline)
                    Text(groupId)
                        .font(.caption)
                        .monospaced()

                    Button("Show QR Code Again") {
                        generateQRCode()
                    }
                }
            } else {
                ProgressView()
            }

            if let error = errorMessage {
                Text(error)
                    .font(.caption)
                    .foregroundColor(.red)
            }

            Spacer()
        }
        .padding()
        .onAppear {
            initiatePairing()
        }
    }

    private func initiatePairing() {
        firebaseService.initiateSyncGroupPairing { result in
            switch result {
            case .success(let groupId):
                self.syncGroupId = groupId
                self.isPaired = true
                generateQRCode()

            case .failure(let error):
                self.errorMessage = "Pairing failed: \(error.localizedDescription)"
            }
        }
    }

    private func generateQRCode() {
        guard let groupId = syncGroupId else { return }

        // Create QR code
        let qrGenerator = QRCode(groupId)
        qrGenerator?.design.style.background = QRCode.DesignStyle.Background(color: .white)
        qrGenerator?.design.style.onPixels = QRCode.DesignStyle.OnPixels(color: .black)

        if let cgImage = qrGenerator?.cgImage(dimension: 300) {
            self.qrCodeImage = NSImage(cgImage: cgImage, size: NSZeroSize)
        }
    }
}
```

### Step 4: Handle QR Code Scanning on macOS

**File:** `SyncFlowMac/SyncFlowMac/Views/PairingView.swift`

Add method to scan QR codes (for testing/manual input):

```swift
/**
 * Manually join a sync group (in case user can't scan QR)
 */
func joinManually(groupId: String) {
    syncGroupManager.joinSyncGroup(scannedSyncGroupId: groupId, deviceName: "macOS") { result in
        switch result {
        case .success(let joinResult):
            self.syncGroupId = groupId
            self.isPaired = true
            self.errorMessage = joinResult.success ?
                "Connected! Using \(joinResult.deviceCount)/\(joinResult.limit) devices" :
                joinResult.success ? nil : joinResult.success

        case .failure(let error):
            self.errorMessage = error.localizedDescription
        }
    }
}
```

### Step 5: Add Pairing State to App

**File:** `SyncFlowMac/SyncFlowMac/App/SyncFlowMacApp.swift`

Add sync group state to main app:

```swift
@main
struct SyncFlowMacApp: App {
    @StateObject var firebaseService = FirebaseService.shared

    var body: some Scene {
        WindowGroup {
            Group {
                if firebaseService.syncGroupId != nil {
                    // Show main app
                    ContentView()
                } else {
                    // Show pairing view
                    PairingView()
                }
            }
        }
    }
}
```

### Step 6: Add Pairing Status to ContentView

**File:** `SyncFlowMac/SyncFlowMac/App/ContentView.swift`

Add header showing pairing status:

```swift
struct ContentView: View {
    @StateObject var firebaseService = FirebaseService.shared

    var body: some View {
        VStack {
            // Pairing status header
            HStack {
                if let groupId = firebaseService.syncGroupId {
                    Label("Paired", systemImage: "checkmark.circle.fill")
                        .font(.caption)
                        .foregroundColor(.green)
                } else {
                    Label("Not Paired", systemImage: "exclamationmark.circle")
                        .font(.caption)
                        .foregroundColor(.orange)
                }
                Spacer()
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
            .background(Color.secondary.opacity(0.1))

            // Rest of content...
            MessageView()
        }
    }
}
```

## Device Limit Handling

When user is on free tier and limit reached:

```swift
private func handleDeviceLimitError(_ message: String) {
    let alert = NSAlert()
    alert.messageText = "Device Limit Reached"
    alert.informativeText = "You're using 3 devices on the free tier. Upgrade to Pro for unlimited devices."
    alert.addButton(withTitle: "Upgrade to Pro")
    alert.addButton(withTitle: "Cancel")

    if alert.runModal() == .alertFirstButtonReturn {
        // Open upgrade page
        openUpgradePage()
    }
}
```

## Testing

Test the integration with:

1. **First Launch:** macOS app creates sync group, generates QR code
2. **QR Display:** Verify QR code is readable and contains correct group ID
3. **QR Decode:** Print group ID to console to verify encoding

```swift
// Add this for debugging QR code
print("Sync Group ID: \(syncGroupId ?? "nil")")
print("QR Code Data: \(firebaseService.syncGroupManager.getQRCodeContent() ?? "nil")")
```

## Subscription Display

Add method to display subscription status:

```swift
func updateSubscriptionStatus() {
    firebaseService.syncGroupManager.getSyncGroupInfo { result in
        switch result {
        case .success(let info):
            DispatchQueue.main.async {
                // Update UI with plan info
                print("Plan: \(info.plan)")
                print("Devices: \(info.deviceCount)/\(info.deviceLimit)")
            }

        case .failure:
            // Show trial status
            break
        }
    }
}
```

## Error Handling

Comprehensive error scenarios:

```swift
enum SyncGroupError: LocalizedError {
    case groupNotFound
    case deviceLimitReached(current: Int, limit: Int)
    case networkError
    case invalidGroupId

    var errorDescription: String? {
        switch self {
        case .groupNotFound:
            return "Sync group not found. Please check the QR code."
        case .deviceLimitReached(let current, let limit):
            return "Device limit reached: \(current)/\(limit). Upgrade to Pro for unlimited."
        case .networkError:
            return "Network error. Please check your connection and try again."
        case .invalidGroupId:
            return "Invalid group ID. Please scan a valid QR code."
        }
    }
}
```

## Files Modified

1. `SyncFlowMac/SyncFlowMac/Services/FirebaseService.swift` - Add sync group methods
2. `SyncFlowMac/SyncFlowMac/Views/PairingView.swift` - Update pairing UI
3. `SyncFlowMac/SyncFlowMac/App/SyncFlowMacApp.swift` - Add pairing state
4. `SyncFlowMac/SyncFlowMac/App/ContentView.swift` - Show pairing status

## Next Steps

1. Implement the code changes above
2. Test QR code generation with real group IDs
3. Test pairing between macOS and Android
4. Verify device limit enforcement
5. Test subscription display per sync group
