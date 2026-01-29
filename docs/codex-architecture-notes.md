# SyncFlow architecture notes (Codex reference)

This file is a high-level architecture and code map for the Android app, macOS app, and web app.
It is meant for quick orientation and future Codex work, not as a replacement for existing docs.
Last updated: January 29, 2026.

## Repository layout
- Android app: `app/`
- macOS app: `SyncFlowMac/`
- Web app: `web/`
- Firebase Cloud Functions: `functions/`
- Firebase rules/config: `firebase.json`, `database.rules.json`, `firebase-rules.json`, `storage.rules`

## Shared backend (Firebase + R2)
- Auth: anonymous + custom token flows with a unified UID across devices.
- Realtime Database: primary sync plane for messages, devices, contacts, calls, notifications, and cross-device services.
- Cloud Functions: pairing (V1/V2), secure sync writes, R2 presigned URLs, FCM notifications, cleanup/admin, sync groups, account lifecycle, support chat.
- Storage: Cloudflare R2 for MMS/media, photo sync, and file transfers; Firebase Storage for some web uploads/legacy flows.
- Push: FCM for Android wakeups (outgoing messages, calls, call status changes).

Common database paths used across clients (from code references):
- `users/{userId}/messages` (SMS/MMS records, attachments, E2EE metadata)
- `users/{userId}/outgoing_messages` (desktop/web -> Android send queue + status)
- `users/{userId}/devices` (paired devices, lastSeen, capabilities)
- `users/{userId}/contacts` (unified contacts feed; desktop/web edits mark `sync.pendingAndroidSync`)
- `users/{userId}/read_receipts`, `users/{userId}/message_reactions`, `users/{userId}/typing`
- `users/{userId}/notifications`, `users/{userId}/mirrored_notifications` (notification mirroring)
- `users/{userId}/clipboard` (clipboard sync)
- `users/{userId}/file_transfers` (Quick Drop style file sharing)
- `users/{userId}/photos` (photo sync; R2-backed)
- `users/{userId}/voicemails`
- `users/{userId}/call_history`
- `users/{userId}/syncflow_calls`, `users/{userId}/incoming_syncflow_calls`, `users/{userId}/outgoing_syncflow_calls`
- `users/{userId}/spam_messages`, `users/{userId}/spam_whitelist`, `users/{userId}/spam_blocklist`
- `users/{userId}/usage` (plan/quota tracking)
- `syncGroups/{syncGroupId}` (device group + plan + device limits)
- `e2ee_keys/{userId}/{deviceId}` (per-device public keys)
- `phone_to_uid` (phone number -> UID lookup for SyncFlow calls)
- Pairing: `pending_pairings` (V1), `pairing_requests` (V2), `pairing_tokens` (legacy)
- Push: `fcm_tokens/{userId}`, `fcm_notifications/{userId}/{notificationId}`

Cloud Functions (see `functions/index.js`):
- Pairing: `initiatePairing`, `completePairing`, `createPairingToken`, `redeemPairingToken`, `initiatePairingV2`, `approvePairingV2`, `checkPairingV2Status`.
- Sync writes: `syncMessage`, `syncMessageBatch`, `syncSpamMessage`.
- R2 storage: `getR2UploadUrl`, `confirmR2Upload`, `getR2DownloadUrl`, `deleteR2File`, analytics/cleanup/admin helpers.
- Push: call notifications and outgoing message wakeups (DB triggers on `fcm_notifications`, `incoming_syncflow_calls`).
- Cleanup/admin: scheduled cleanup jobs, user/device cleanup, sync group admin, account deletion workflow, support chat.

## Android app (Kotlin, Jetpack Compose)
Entry points and structure:
- Application: `app/src/main/java/com/phoneintegration/app/SyncFlowApp.kt`
- Main UI: `app/src/main/java/com/phoneintegration/app/MainActivity.kt`
- Manifest: `app/src/main/AndroidManifest.xml`
- UI modules: `app/src/main/java/com/phoneintegration/app/ui/`
- Data layer: `app/src/main/java/com/phoneintegration/app/data/` and `app/src/main/java/com/phoneintegration/app/data/database/`

Key responsibilities:
- System-of-record: reads SMS/MMS, contacts, call state, notifications; writes to Firebase.
- Runs cross-device services (clipboard, notifications, file transfer, photos, DND, hotspot, media, voicemail).
- Handles pairing/identity and sync groups (multi-device limits and recovery).
- Integrates with Firebase (Database/Auth/Functions/Messaging) and R2 via Functions.

Notable components (non-exhaustive):
- Messaging: `SmsReceiver.kt`, `MmsReceiver.kt`, `SmsRepository.kt`, `SmsViewModel.kt`, `MmsHelper.kt`.
- Sync: `desktop/DesktopSyncService.kt`, `desktop/SmsSyncWorker.kt`, `desktop/AllMessagesSyncWorker.kt`.
- Outgoing messages: `desktop/OutgoingMessageService.kt` (FCM-woken).
- Pairing/identity: `auth/UnifiedIdentityManager.kt`, `sync/SyncGroupManager.kt`.
- E2EE: `e2ee/SignalProtocolManager.kt` (Tink + Android Keystore, keyMap + per-device keys).
- Calls: `SyncFlowCallService.kt`, `webrtc/SyncFlowCallManager.kt`.
- Push: `SyncFlowMessagingService.kt`.
- Local data: Room + SQLCipher initialization in `SyncFlowApp.kt`.

Pairing flow (Android involvement):
- V2: Desktop/web calls `initiatePairingV2` -> QR token in `pairing_requests`; Android approves via `completePairing`.
- V1: Android can generate tokens via `createPairingToken` and desktop/web redeem via `redeemPairingToken`.

## macOS app (SwiftUI)
Entry points and structure:
- App entry: `SyncFlowMac/SyncFlowMac/App/SyncFlowMacApp.swift`
- Root view: `SyncFlowMac/SyncFlowMac/App/ContentView.swift`
- Models: `SyncFlowMac/SyncFlowMac/Models/`
- Services: `SyncFlowMac/SyncFlowMac/Services/`
- Views: `SyncFlowMac/SyncFlowMac/Views/`

Key responsibilities:
- Pairs with Android via Firebase custom tokens and device registration.
- Listens to messages, contacts, notifications, file transfers, photo sync, and call state in RTDB.
- Decrypts E2EE messages and attachments locally (CryptoKit).
- Provides desktop features (clipboard sync, notification mirroring, hotspot/DND/media control, file transfer, photo sync, voicemail).

Notable components:
- `FirebaseService.swift` handles auth, pairing, message listeners, and storage access.
- `E2EEManager.swift` manages CryptoKit key agreement and Keychain storage.
- `MessageStore.swift` builds conversations, handles unread/read, reactions, notifications.
- `SyncFlowCallManager.swift` handles WebRTC calling (`incoming_syncflow_calls` / `outgoing_syncflow_calls`).
- `FileTransferService.swift`, `PhotoSyncService.swift`, `NotificationMirrorService.swift`, `ClipboardSyncService.swift`.

## Web app (Next.js App Router)
Entry points and structure:
- App router: `web/app/layout.tsx`, `web/app/page.tsx`
- Main pages: `web/app/messages/page.tsx`, `web/app/contacts/page.tsx`
- Admin pages: `web/app/admin/*`
- State: `web/lib/store.ts` (zustand)
- Firebase client: `web/lib/firebase.ts`
- E2EE: `web/lib/e2ee.ts`
- Notifications: `web/lib/notification-sync.ts`
- Styling: `web/app/globals.css`, `web/tailwind.config.ts`

Key responsibilities:
- Web pairing and session persistence (device ID + auth state in local storage/IndexedDB).
- Reads messages from Firebase and decrypts when per-device keys are available.
- Writes to `outgoing_messages` for Android to send.
- Supports contacts via the unified `contacts` node.
- Admin/ops UI for cleanup, R2 analytics, and device/user management.

Pairing flows:
- V2 (preferred): `initiatePairingV2` -> `pairing_requests/{token}` -> Android approval.
- V1 (fallback): `initiatePairing` / `pending_pairings/{token}`.

## Cross-platform data flows (high level)
- SMS/MMS ingest: Android reads messages -> writes to `users/{uid}/messages` (encrypted when enabled).
- E2EE: messages include `keyMap` + `nonce`; macOS/web decrypt using per-device keys in `e2ee_keys`.
- Attachments: Android uploads to R2 (or inline Base64 for small files) and writes `r2Key`. macOS/web message views currently render `url` or `inlineData`, so `r2Key` needs URL resolution. Web MMS uploads use Firebase Storage URLs; Android outgoing downloads only `url`/`inlineData` (no `r2Key` handling today).
- Outgoing from desktop/web: write to `users/{uid}/outgoing_messages`; Android sends and records status.
- Contacts sync: Android writes `users/{uid}/contacts`; desktop/web edits mark `sync.pendingAndroidSync`.
- File transfer + photos: R2-backed metadata in `users/{uid}/file_transfers` and `users/{uid}/photos`.
- Clipboard + notifications: `users/{uid}/clipboard`, `users/{uid}/mirrored_notifications`.
- Calls:
  - Carrier call control: Android receives FCM requests and dials locally.
  - SyncFlow WebRTC: signaling in `incoming_syncflow_calls` / `outgoing_syncflow_calls` with FCM notifications.
- Spam, read receipts, reactions, typing indicators are cross-device RTDB nodes.

## Useful starting points for future work
- Android entry: `app/src/main/java/com/phoneintegration/app/MainActivity.kt`
- Android messaging: `app/src/main/java/com/phoneintegration/app/SmsRepository.kt`
- Android sync: `app/src/main/java/com/phoneintegration/app/desktop/DesktopSyncService.kt`
- Android pairing: `app/src/main/java/com/phoneintegration/app/auth/UnifiedIdentityManager.kt`
- macOS entry: `SyncFlowMac/SyncFlowMac/App/SyncFlowMacApp.swift`
- macOS messages: `SyncFlowMac/SyncFlowMac/Services/MessageStore.swift`
- macOS Firebase bridge: `SyncFlowMac/SyncFlowMac/Services/FirebaseService.swift`
- Web Firebase bridge: `web/lib/firebase.ts`
- Cloud Functions: `functions/index.js`
