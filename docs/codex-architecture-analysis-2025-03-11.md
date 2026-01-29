# Codex Architecture Analysis (SyncFlow)

Purpose: One-time architecture snapshot for Codex reference. This file does not replace existing docs.
Last updated: January 29, 2026.

## High-level topology
- Android app is the system-of-record for SMS/MMS, contacts, call state, and device capabilities.
- macOS and web clients are secondary devices that read from Firebase RTDB and write actions back (outgoing messages, contact edits, clipboard, etc.).
- Firebase Realtime Database is the primary sync plane; Cloud Functions mediate secure writes, pairing, R2 presigned URLs, notifications, cleanup/admin.
- Cloudflare R2 stores MMS/media attachments, photo sync, and file transfers (Firebase Storage is still used for some outgoing web uploads and legacy flows).
- FCM is used to wake Android for time-sensitive events (outgoing messages, incoming calls, call status changes).

## Android app (app/src/main/java/com/phoneintegration/app)

### Core layers
- UI: Jetpack Compose in `ui/`, entry `MainActivity.kt`, app init in `SyncFlowApp.kt`.
- Data: `SmsRepository` (Telephony providers), `MmsHelper`, Room/SQLCipher for local state.
- Identity/Pairing: `AuthManager`, `UnifiedIdentityManager`, `sync/SyncGroupManager`.
- Sync + transport: `desktop/DesktopSyncService`, workers (`SmsSyncWorker`, `AllMessagesSyncWorker`, `ContactsSyncWorker`, `CallHistorySyncWorker`).
- Push/calls: `SyncFlowMessagingService` (FCM), `SyncFlowCallService`, `webrtc/SyncFlowCallManager`.

### Key flows
- Read messages: `SmsRepository` + `SmsReceiver`/`MmsReceiver` ingest SMS/MMS from Telephony providers.
- Send SMS/MMS: `SmsViewModel` -> `SmsRepository.sendSms` or `MmsHelper.sendMms` (persists parts if needed).
- Sync to Firebase: `DesktopSyncService.syncMessage` uses Cloud Function `syncMessage` (batch via `syncMessageBatch`), writes attachments metadata and E2EE fields.
- MMS attachments: uploaded to Cloudflare R2 via Functions (`getR2UploadUrl` + `confirmR2Upload`); fallback to inline Base64 for small files.
- Outgoing from desktop/web: `OutgoingMessageService` listens to `users/{uid}/outgoing_messages`, sends SMS/MMS, removes queue entry, then syncs the sent message.
- Pairing:
  - V2: web/mac uses `pairing_requests/{token}` via `initiatePairingV2`; Android approves via `completePairing`.
  - V1: `pending_pairings/{token}` + `createPairingToken`/`redeemPairingToken`.
- Sync groups: Android joins/creates `syncGroups/{syncGroupId}` for multi-device limits and recovery.

### Cross-device services (Android -> desktop/web)
- Clipboard: `desktop/ClipboardSyncService` (`users/{uid}/clipboard`).
- File transfer: `desktop/FileTransferService` (`users/{uid}/file_transfers`) with R2 storage.
- Photo sync: `desktop/PhotoSyncService` (`users/{uid}/photos`) with R2 storage.
- Notification mirroring: `desktop/NotificationMirrorService` (`users/{uid}/mirrored_notifications`).
- Phone status, hotspot, DND, media control, voicemail, find-my-phone, link sharing, scheduled messages.

### Security / E2EE
- `e2ee/SignalProtocolManager` (Tink + Android Keystore) manages per-device keys in `e2ee_keys/{uid}/{deviceId}`.
- Encrypted messages include `keyMap`, `nonce`, `keyVersion`, `senderDeviceId`; attachments optionally encrypted before upload.
- If E2EE fails, messages still sync with `e2eeFailed` and `e2eeFailureReason`.

### Key files
- `app/src/main/java/com/phoneintegration/app/SyncFlowApp.kt`
- `app/src/main/java/com/phoneintegration/app/MainActivity.kt`
- `app/src/main/java/com/phoneintegration/app/SmsViewModel.kt`
- `app/src/main/java/com/phoneintegration/app/SmsRepository.kt`
- `app/src/main/java/com/phoneintegration/app/MmsHelper.kt`
- `app/src/main/java/com/phoneintegration/app/desktop/DesktopSyncService.kt`
- `app/src/main/java/com/phoneintegration/app/desktop/OutgoingMessageService.kt`
- `app/src/main/java/com/phoneintegration/app/SyncFlowMessagingService.kt`
- `app/src/main/java/com/phoneintegration/app/webrtc/SyncFlowCallManager.kt`
- `app/src/main/java/com/phoneintegration/app/auth/UnifiedIdentityManager.kt`
- `app/src/main/java/com/phoneintegration/app/sync/SyncGroupManager.kt`

## macOS app (SyncFlowMac)

### Core layers
- SwiftUI UI in `Views/`, state via `AppState` + `MessageStore`.
- Firebase access and pairing via `Services/FirebaseService.swift`.
- E2EE via `Services/E2EEManager.swift` (CryptoKit, v2 key envelopes).
- Sync group handling via `Services/SyncGroupManager.swift`.

### Key flows
- Listen for messages: `MessageStore.startListening` -> `FirebaseService.listenToMessages`.
- Decrypt: `E2EEManager` resolves per-device keyMap; fallback placeholder on failure.
- Attachments: message attachments use URL or inline Base64; R2 download URLs are resolved in file/photo services. MMS attachments may arrive as `r2Key` and need URL resolution.
- Outgoing messages: `FirebaseService.sendMessage` writes to `outgoing_messages` for Android.
- Calls: `SyncFlowCallManager` uses WebRTC signaling in `incoming_syncflow_calls` / `outgoing_syncflow_calls` plus FCM notifications.

### Key services
- Clipboard sync, file transfer, photo sync (R2), notification mirroring, DND, hotspot control, media control, voicemail sync, scheduled messages, usage/subscription gating.

### Key files
- `SyncFlowMac/SyncFlowMac/App/SyncFlowMacApp.swift`
- `SyncFlowMac/SyncFlowMac/Services/FirebaseService.swift`
- `SyncFlowMac/SyncFlowMac/Services/MessageStore.swift`
- `SyncFlowMac/SyncFlowMac/Services/E2EEManager.swift`
- `SyncFlowMac/SyncFlowMac/Services/SyncFlowCallManager.swift`
- `SyncFlowMac/SyncFlowMac/Services/FileTransferService.swift`
- `SyncFlowMac/SyncFlowMac/Services/NotificationMirrorService.swift`

## Web app (web)

### Core layers
- Next.js App Router in `web/app`, shared components in `web/components`.
- Firebase client in `web/lib/firebase.ts`, E2EE in `web/lib/e2ee.ts`, state in `web/lib/store.ts`.
- Notification mirroring (web) in `web/lib/notification-sync.ts`.

### Key flows
- Pairing: `initiatePairingV2` (preferred) with fallback to `initiatePairing` (V1); listens on `pairing_requests` + `pending_pairings`.
- Messages: `listenToMessages` reads `users/{uid}/messages` and decrypts using keyMap.
- Outgoing: `sendSmsFromWeb`/`sendMmsFromWeb` write to `outgoing_messages`.
- MMS uploads from web: `uploadMmsImage` uses Firebase Storage and includes download `url` in outgoing metadata.

### Admin/ops
- Admin pages under `web/app/admin/*` call Cloud Functions for cleanup, R2 analytics, device/user management, sync group tools.

### Key files
- `web/lib/firebase.ts`
- `web/lib/e2ee.ts`
- `web/lib/store.ts`
- `web/lib/notification-sync.ts`
- `web/app/messages/page.tsx`
- `web/app/contacts/page.tsx`
- `web/app/admin/cleanup/page.tsx`

## Firebase data model (high level)
- `users/{uid}/messages/{messageId}`: SMS/MMS metadata, attachments, E2EE fields (`keyMap`, `nonce`, `keyVersion`, `senderDeviceId`, `e2eeFailed`).
- `users/{uid}/outgoing_messages/{messageId}`: desktop/web -> Android send queue + status.
- `users/{uid}/contacts`: unified contacts feed (desktop/web edits marked via `sync.pendingAndroidSync`).
- `users/{uid}/devices`: paired device registry + capabilities.
- `users/{uid}/read_receipts`, `users/{uid}/message_reactions`, `users/{uid}/typing`.
- `users/{uid}/notifications` and `users/{uid}/mirrored_notifications` (notification mirroring).
- `users/{uid}/clipboard`, `users/{uid}/file_transfers`, `users/{uid}/photos`, `users/{uid}/voicemails`.
- `users/{uid}/call_history`, `users/{uid}/syncflow_calls`, `users/{uid}/incoming_syncflow_calls`, `users/{uid}/outgoing_syncflow_calls`.
- `users/{uid}/spam_messages`, `users/{uid}/spam_whitelist`, `users/{uid}/spam_blocklist`.
- `users/{uid}/usage` (plan/quota, storage).
- `syncGroups/{syncGroupId}`: multi-device group + plan + device limits.
- `e2ee_keys/{uid}/{deviceId}`: per-device public keys.
- `phone_to_uid`: phone number -> UID mapping for SyncFlow calls.
- Pairing: `pending_pairings` (V1), `pairing_requests` (V2), `pairing_tokens` (legacy).
- Push: `fcm_tokens/{uid}`, `fcm_notifications/{uid}/{notificationId}`.
- Storage (R2): keys under `mms/{uid}/...`, `files/{uid}/...`, `photos/{uid}/...`.

## Notes / troubleshooting
- Android -> desktop/web MMS attachments are written with `r2Key` (R2) or `inlineData`. macOS/web message views currently render `url` or `inlineData`, so `r2Key`-only attachments will not display without additional download URL resolution.
- macOS MMS sending uploads attachments to R2 and queues `r2Key` in `outgoing_messages`; Android's sender currently only downloads `url` or `inlineData`, so `r2Key`-only outbound MMS can fail unless handled.
- If a device is missing from a message `keyMap`, the client shows an encrypted placeholder; re-pairing regenerates/publishes keys.
- Outgoing messages depend on FCM to wake Android; if the phone is offline, the queue remains in `outgoing_messages`.
