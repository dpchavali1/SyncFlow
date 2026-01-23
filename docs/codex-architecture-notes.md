# SyncFlow architecture notes (Codex reference)

This file is a high-level architecture and code map for the Android app, macOS app, and web app.
It is meant for quick orientation and future Codex work, not as a replacement for existing docs.

## Repository layout
- Android app: `app/`
- macOS app: `SyncFlowMac/`
- Web app: `web/`
- Firebase Cloud Functions: `functions/`
- Firebase rules/config: `firebase.json`, `database.rules.json`, `firebase-rules.json`, `storage.rules`

## Shared backend (Firebase)
- Auth: anonymous + custom token flows.
- Realtime Database: primary sync plane for messages, devices, contacts, and call state.
- Storage: MMS/media attachments.
- Cloud Functions: pairing and call notification dispatch.

Common database paths used across clients (from code references):
- `users/{userId}/messages` (SMS/MMS records, E2EE metadata)
- `users/{userId}/outgoing_messages` (desktop/web -> Android send queue)
- `users/{userId}/devices` (paired devices, lastSeen)
- `users/{userId}/contacts` (Android contacts)
- `users/{userId}/desktopContacts` (desktop-created contacts)
- `e2ee_keys/{userId}/{deviceId}` (per-device public keys)
- `pairing_tokens/{token}` (Cloud Function pairing tokens)
- `pending_pairings/{token}` (web-initiated QR pairing flow)
- `fcm_tokens/{userId}` (Android push token storage)
- `fcm_notifications/{userId}/{callId}` (call notification queue)

Cloud Functions (see `functions/index.js`):
- `createPairingToken` and `redeemPairingToken` (device pairing via custom tokens).
- `sendCallNotification` and `sendCallCancellation` (push notification dispatch for calls).

## Android app (Kotlin, Jetpack Compose)
Entry points and structure:
- Application: `app/src/main/java/com/phoneintegration/app/SyncFlowApp.kt`
- Main UI: `app/src/main/java/com/phoneintegration/app/MainActivity.kt`
- Manifest: `app/src/main/AndroidManifest.xml`
- UI modules: `app/src/main/java/com/phoneintegration/app/ui/`
- Data layer: `app/src/main/java/com/phoneintegration/app/data/` and `app/src/main/java/com/phoneintegration/app/data/database/`

Key responsibilities:
- Acts as the primary device: reads SMS/MMS, contacts, call state; writes to Firebase.
- Runs multiple services for sync and device features (clipboard, notifications, file transfer, etc.).
- Manages default SMS and dialer roles (role requests in `MainActivity.kt`).
- Integrates with Firebase (Database/Auth/Storage/Functions/Messaging).

Notable components (non-exhaustive):
- Messaging ingestion and sync: `SmsReceiver.kt`, `MmsReceiver.kt`, `SmsRepository.kt`, `SmsViewModel.kt`.
- Desktop integration services: `app/src/main/java/com/phoneintegration/app/desktop/`.
- Call handling: `SyncFlowCallService.kt`, `CallMonitorService.kt`, `DesktopInCallService.kt`.
- WebRTC audio routing: `app/src/main/java/com/phoneintegration/app/webrtc/`.
- E2EE: `app/src/main/java/com/phoneintegration/app/e2ee/SignalProtocolManager.kt` (Tink + Android Keystore).
- Local data: Room database (`data/database/*`) and SQLCipher (`SyncFlowApp.kt`).

Pairing flow (Android-initiated):
- `DesktopSyncService.generatePairingToken()` calls Cloud Function `createPairingToken`.
- Desktop/web clients redeem via `redeemPairingToken` (custom token + device entry).

## macOS app (SwiftUI)
Entry points and structure:
- App entry: `SyncFlowMac/SyncFlowMac/App/SyncFlowMacApp.swift`
- Root view: `SyncFlowMac/SyncFlowMac/App/ContentView.swift`
- Models: `SyncFlowMac/SyncFlowMac/Models/`
- Services: `SyncFlowMac/SyncFlowMac/Services/`
- Views: `SyncFlowMac/SyncFlowMac/Views/`

Key responsibilities:
- Pairs with Android via Firebase custom tokens and device registration.
- Listens to messages and call state in Realtime Database.
- Decrypts E2EE messages and attachments locally.
- Provides desktop features (clipboard, notification mirroring, photo sync, call control, etc.).

Notable components:
- `FirebaseService.swift` handles auth, pairing, message listeners, and storage access.
- `E2EEManager.swift` manages CryptoKit key agreement and Keychain storage.
- `MessageStore.swift` builds conversations, handles unread/read, notifications, and reactions.
- `AppState` in `SyncFlowMacApp.swift` wires services and exposes UI state.

## Web app (Next.js App Router)
Entry points and structure:
- App router: `web/app/layout.tsx`, `web/app/page.tsx`
- Main pages: `web/app/messages/page.tsx`, `web/app/contacts/page.tsx`
- State: `web/lib/store.ts` (zustand)
- Firebase client: `web/lib/firebase.ts`
- E2EE: `web/lib/e2ee.ts`
- Styling: `web/app/globals.css`, `web/tailwind.config.ts`

Key responsibilities:
- Web pairing and session persistence (localStorage for `syncflow_user_id`).
- Reads messages from Firebase and decrypts when per-device keys are available.
- Writes to `outgoing_messages` for Android to send.
- Supports contact management (desktopContacts + contacts read).

Pairing flows:
- Web-initiated QR flow uses `pending_pairings/{token}` (see `generatePairingToken`).
- Also supports redeeming pairing tokens via Cloud Function (`redeemPairingToken`).

## Cross-platform data flows (high level)
- SMS/MMS ingest: Android reads messages -> writes to `users/{uid}/messages` (encrypted when enabled).
- Message consumption: macOS/web clients listen to `users/{uid}/messages`, decrypt via `e2ee_keys`.
- Outgoing from desktop/web: write to `users/{uid}/outgoing_messages`; Android service sends and records.
- Contacts sync: Android writes `users/{uid}/contacts`; web reads and can write `desktopContacts`.
- Calls: Android updates call state and FCM queues; functions dispatch push via `fcm_notifications`.
- Media/attachments: stored in Firebase Storage; message entries carry attachment metadata.

## Useful starting points for future work
- Android entry: `app/src/main/java/com/phoneintegration/app/MainActivity.kt`
- Android messaging: `app/src/main/java/com/phoneintegration/app/SmsRepository.kt`
- macOS entry: `SyncFlowMac/SyncFlowMac/App/SyncFlowMacApp.swift`
- macOS messages: `SyncFlowMac/SyncFlowMac/Services/MessageStore.swift`
- Web firebase bridge: `web/lib/firebase.ts`
- Cloud Functions: `functions/index.js`
