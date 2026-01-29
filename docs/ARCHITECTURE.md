# SyncFlow Architecture Documentation

## Overview

SyncFlow is a cross-platform phone integration system that syncs SMS messages, calls, files, and more between Android phones and Mac computers. The system consists of four main components:

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Android App   │────▶│    Firebase     │◀────│    macOS App    │
│    (Kotlin)     │     │  (Realtime DB)  │     │    (Swift)      │
└─────────────────┘     └─────────────────┘     └─────────────────┘
                               │
                               ▼
                        ┌─────────────────┐
                        │  Cloud Functions │
                        │   (Node.js)     │
                        └─────────────────┘
                               │
                               ▼
                        ┌─────────────────┐
                        │    Web Admin    │
                        │  (Next.js/React)│
                        └─────────────────┘
```

## Project Structure

```
SyncFlow/
├── app/                          # Android app (Kotlin + Jetpack Compose)
│   └── src/main/java/com/phoneintegration/app/
│       ├── ai/                   # AI Assistant service
│       ├── continuity/           # Handoff/continuity features
│       ├── data/                 # Room database, preferences
│       ├── deals/                # Deals feature
│       ├── desktop/              # Sync services (SMS, Files, Photos, etc.)
│       ├── services/             # Background services
│       ├── ui/                   # Compose UI screens
│       ├── usage/                # Usage tracking
│       ├── utils/                # Utilities (SpamFilter, etc.)
│       └── webrtc/               # WebRTC calling
│
├── SyncFlowMac/                  # macOS app (Swift + SwiftUI)
│   └── SyncFlowMac/
│       ├── App/                  # App entry point, ContentView
│       ├── Models/               # Data models (Message, Conversation, etc.)
│       ├── Services/             # Sync services, Firebase, AI
│       ├── Views/                # SwiftUI views
│       └── Resources/            # Assets, StoreKit config
│
├── web/                          # Web admin panel (Next.js)
│   ├── app/                      # Next.js app router pages
│   ├── components/               # React components
│   └── lib/                      # Firebase utilities
│
├── functions/                    # Firebase Cloud Functions (Node.js)
│   └── index.js                  # All cloud functions
│
└── database.rules.json           # Firebase Realtime Database security rules
```

## Data Flow

### Message Sync (Android → Mac)

1. **SMS Received** (Android)
   - `SmsReceiver.kt` broadcasts incoming SMS
   - `DesktopSyncService.kt` uploads to Firebase: `users/{userId}/messages/{messageId}`

2. **Firebase Listener** (Mac)
   - `FirebaseService.swift` has real-time listener on messages path
   - New messages trigger `MessageStore.swift` update

3. **UI Update** (Mac)
   - `MessageStore` is an `@ObservableObject`
   - SwiftUI views automatically update via `@EnvironmentObject`

### Message Sync (Mac → Android)

1. **User Sends Message** (Mac)
   - `MessageView.swift` calls `FirebaseService.sendMessage()`
   - Message written to Firebase with `status: "pending"`

2. **Pending Message Listener** (Android)
   - `DesktopSyncService.kt` listens for pending messages
   - Sends via `SmsManager` and updates status to "sent"

## Key Services

### Android Services

| Service | Purpose |
|---------|---------|
| `DesktopSyncService` | Main sync orchestrator - SMS, calls, contacts |
| `SmsSyncWorker` | WorkManager-based background SMS sync |
| `FileTransferService` | File sharing between devices |
| `PhotoSyncService` | Photo gallery sync |
| `ClipboardSyncService` | Clipboard sharing |
| `SyncFlowMessagingService` | FCM push notifications |

### macOS Services

| Service | Purpose |
|---------|---------|
| `FirebaseService` | Firebase connection, real-time listeners |
| `MessageStore` | Central message state management |
| `PreferencesService` | User preferences (pinned, archived, labels) |
| `SyncFlowCallManager` | WebRTC calling |
| `FileTransferService` | File receiving and sending |
| `PhotoSyncService` | Photo thumbnail loading |

## Firebase Database Structure

```
users/
  {userId}/
    messages/           # SMS messages
      {messageId}/
        address         # Phone number
        body            # Message text
        date            # Timestamp (ms)
        type            # 1=received, 2=sent
        status          # pending/sent/delivered/failed

    calls/              # Call history
      {callId}/
        number
        callType        # incoming/outgoing/missed
        duration
        callDate

    contacts/           # Synced contacts
      {contactId}/
        name
        phones[]

    files/              # File transfers
      {fileId}/
        fileName
        fileSize
        r2Key           # Cloudflare R2 storage key

    photos/             # Synced photos
      {photoId}/
        r2Key
        thumbnailUrl

    preferences/        # User settings
    devices/            # Connected devices
    subscription/       # Plan info
```

## Authentication Flow

1. User signs in with Firebase Auth (Email/Password or Google)
2. Android generates unique `userId` stored in SharedPreferences
3. Mac retrieves `userId` from Firebase Auth
4. All data paths scoped to `users/{userId}/`

## Security Model

- All database paths require authentication (`auth != null`)
- Users can only read/write their own data (`auth.uid == $userId`)
- Cloud Functions have admin access for cross-user operations
- E2EE available for sensitive sync features

## Storage Architecture

### Cloudflare R2 (Primary)
- MMS attachments: `mms/{userId}/{messageId}/{attachmentId}.{ext}`
- Photos: `photos/{userId}/{photoId}.jpg`
- Files: `files/{userId}/{fileId}/{fileName}`

### Firebase Realtime Database
- Message metadata
- User preferences
- Device state
- Sync coordination

## Background Execution

### Android
- **WorkManager**: Periodic sync jobs with battery optimization
- **Foreground Service**: Active file transfers, calls
- **FCM**: Push-triggered sync for immediate updates

### macOS
- **App Nap Prevention**: During active sync
- **Background App Refresh**: Periodic updates
- **Push Notifications**: APNs for immediate alerts

## Error Handling

1. **Network Errors**: Automatic retry with exponential backoff
2. **Auth Errors**: Re-authentication prompt
3. **Sync Conflicts**: Last-write-wins with timestamp comparison
4. **Storage Quota**: Warning notifications, old file cleanup

## Performance Considerations

1. **Query Limits**: Messages limited to 3000, calls to 500
2. **Pagination**: Load more on scroll for large datasets
3. **Background Processing**: Heavy operations off main thread
4. **Caching**: Local cache for frequently accessed data

## Testing

### Android
```bash
./gradlew test                    # Unit tests
./gradlew connectedAndroidTest    # Instrumented tests
```

### macOS
```bash
xcodebuild test -scheme SyncFlowMac
```

### Cloud Functions
```bash
cd functions && npm test
```

## Deployment

### Android
1. Update version in `app/build.gradle.kts`
2. Build release: `./gradlew assembleRelease`
3. Upload to Play Console

### macOS
1. Update version in Xcode
2. Archive and upload to App Store Connect

### Cloud Functions
```bash
cd functions && firebase deploy --only functions
```

### Web Admin
```bash
cd web && npm run build && firebase deploy --only hosting
```

## Environment Variables

### Android (`local.properties`)
```
MAPS_API_KEY=xxx
ADMOB_APP_ID=xxx
```

### Cloud Functions (Firebase Config)
```bash
firebase functions:config:set r2.account_id=xxx r2.access_key=xxx
```

## Contributing

1. Follow existing code style
2. Add documentation comments for new code
3. Test on both Android and macOS
4. Update this architecture doc for significant changes
