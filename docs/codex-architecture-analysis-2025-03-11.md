# Codex Architecture Analysis (SyncFlow)

Purpose: One-time architecture snapshot for Codex reference. This file does not replace existing docs.

## High-level topology
- Android app is the system-of-record for SMS/MMS and contacts.
- macOS and web clients are read/write views backed by Firebase.
- Firebase Realtime Database stores message metadata; Firebase Storage stores MMS media; Firebase Functions handle pairing token redemption.
- End-to-end encryption (E2EE) is supported for message bodies and attachments.

## Android app (app/src/main/java/com/phoneintegration/app)

### Core layers
- UI: Jetpack Compose screens under `ui/`.
- Data: `SmsRepository` reads from Telephony providers; Room DB used for local state (drafts, pinned, spam, etc.).
- Messaging: `SmsViewModel` orchestrates send/receive, MMS persistence, and sync.
- Sync: `desktop/` services push messages/contacts to Firebase and pull outgoing messages.

### Key flows
- Read messages: `SmsRepository` queries `Telephony.Sms` and `content://mms` to build `SmsMessage` objects and MMS attachments metadata.
- Send SMS: `SmsViewModel.sendSms` -> `SmsManager`.
- Send MMS: `SmsViewModel.sendMms` -> `MmsHelper.sendMms` -> persist parts if missing (insert into MMS provider) -> patch attachments/body.
- Message sync (to desktop/web): `DesktopSyncService.syncMessage` uploads metadata to Realtime DB; uploads media to Storage with optional E2EE.
- Background sync:
  - `SmsSyncWorker` for recent messages.
  - `AllMessagesSyncWorker` for full history sync with foreground notification and progress.
- Contacts sync: `ContactsSyncService` reads Contacts provider and writes to `users/{uid}/contacts`.
- Outgoing from desktop/web: `OutgoingMessageService` listens to `users/{uid}/outgoing_messages`, sends via SMS/MMS, then writes result.

### Security/E2EE
- `SignalProtocolManager` manages device keys and encrypts message bodies and attachments.
- Firebase message records include `keyMap`, `nonce`, and `senderDeviceId` when encrypted.

### Key files
- `app/src/main/java/com/phoneintegration/app/SmsViewModel.kt`
- `app/src/main/java/com/phoneintegration/app/SmsRepository.kt`
- `app/src/main/java/com/phoneintegration/app/MmsHelper.kt`
- `app/src/main/java/com/phoneintegration/app/desktop/DesktopSyncService.kt`
- `app/src/main/java/com/phoneintegration/app/desktop/OutgoingMessageService.kt`
- `app/src/main/java/com/phoneintegration/app/desktop/AllMessagesSyncWorker.kt`

## macOS app (SyncFlowMac)

### Core layers
- SwiftUI UI in `Views/` (conversation list, message view, contacts, settings).
- State and data flow in `AppState` + `MessageStore`.
- Firebase access via `FirebaseService`.

### Key flows
- Listen for messages: `MessageStore.startListening` -> `FirebaseService.listenToMessages`.
- Message decryption: `FirebaseService` applies E2EE decryption; fallback message shown if key missing.
- MMS attachments: parsed from Firebase `attachments` field; supports URL downloads and inline base64 data.
- Contacts: `MessageStore` listens to `users/{uid}/contacts` (a single unified feed that flags desktop/web edits via `sync.pendingAndroidSync`) and surfaces contact photos/notes.
- Outgoing messages: `FirebaseService.sendMessage` writes to `outgoing_messages` for Android to process.

### Key files
- `SyncFlowMac/SyncFlowMac/Services/FirebaseService.swift`
- `SyncFlowMac/SyncFlowMac/Services/MessageStore.swift`
- `SyncFlowMac/SyncFlowMac/Views/MessageView.swift`
- `SyncFlowMac/SyncFlowMac/Models/Message.swift`

## Web app (web)

### Core layers
- Next.js app under `web/app` with React components in `web/components`.
- Firebase client + E2EE helpers in `web/lib`.
- State store in `web/lib/store.ts` (zustand).

### Key flows
- Pairing: `generatePairingToken` creates token in `pending_pairings`; `redeemPairingToken` signs in with custom token.
- Messages: `listenToMessages` reads `users/{uid}/messages` and decrypts if key available.
- Outgoing: `sendSmsFromWeb` writes `outgoing_messages`.
- Attachments: `MessageView` and related components read `attachments` metadata from Firebase; media via storage URLs.

### Key files
- `web/lib/firebase.ts`
- `web/lib/e2ee.ts`
- `web/lib/store.ts`
- `web/app/messages/page.tsx`
- `web/components/MessageView.tsx`

## Firebase data model (high level)
- `users/{uid}/messages/{messageKey}`: SMS/MMS metadata; MMS attachments list; encryption fields.
- `users/{uid}/outgoing_messages`: queued messages from macOS/web to Android.
- `users/{uid}/contacts`: contacts synced from Android.
- `pending_pairings/{token}`: pairing tokens for device linking.
- `users/{uid}/attachments/...`: MMS media in Storage (plus inline base64 fallback in message metadata).

## Notes for troubleshooting
- MMS display depends on attachment metadata being present in `SmsMessage.mmsAttachments` (Android) and `attachments` in Firebase (macOS/web).
- Contact resolution relies on phone number normalization; mismatches often trace to `insert-address-token` or inconsistent normalization.
