# Firebase Realtime Database Security Rules Documentation

This document provides comprehensive documentation for the Firebase Realtime Database security rules defined in `database.rules.json`. It explains the data structure hierarchy, security model, authentication requirements, and validation rules.

---

## Table of Contents

1. [Overview](#overview)
2. [Authentication Model](#authentication-model)
3. [Data Structure Hierarchy](#data-structure-hierarchy)
4. [Security Rules by Path](#security-rules-by-path)
5. [Validation Rules Summary](#validation-rules-summary)
6. [Database Indexes](#database-indexes)
7. [Common Patterns](#common-patterns)

---

## Overview

SyncFlow uses Firebase Realtime Database to synchronize data between Android devices and desktop/web clients (macOS, web). The security rules enforce:

- **User isolation**: Users can only access their own data
- **Device pairing**: Paired devices can access the primary user's data via custom token claims
- **Admin access**: Administrators have elevated permissions for user management
- **Data validation**: All data is validated for type and length constraints
- **Subscription management**: Premium features are gated by subscription status

---

## Authentication Model

### Authentication Requirements

All paths require Firebase Authentication (`auth != null`) unless explicitly noted. The rules use several authentication patterns:

### Custom Token Claims

The app uses custom JWT token claims for device pairing:

| Claim | Description |
|-------|-------------|
| `auth.uid` | The authenticated user's unique ID |
| `auth.token.pairedUid` | The UID of the primary user this device is paired to |
| `auth.token.deviceId` | The device ID for paired devices |
| `auth.token.admin` | Boolean flag for admin users |

### Access Level Hierarchy

1. **Owner Access**: `auth.uid == $uid` - Full read/write to own data
2. **Paired Device Access**: `auth.token.pairedUid == $uid` - Read (and often write) access to paired user's data
3. **Admin Access**: `auth.token.admin === true` - Administrative privileges

### Standard Access Pattern

Most user data paths follow this pattern:
```javascript
".read": "auth != null && (auth.uid == $uid || auth.token.pairedUid == $uid)"
".write": "auth != null && (auth.uid == $uid || auth.token.pairedUid == $uid)"
```

This allows both the owner and any paired devices to read and write data.

---

## Data Structure Hierarchy

### Top-Level Structure

```
/
├── users/                      # Per-user data (primary data store)
│   └── {uid}/                  # User's data container
│       ├── profile/            # User profile information
│       ├── subscription/       # Subscription/plan details
│       ├── devices/            # Paired devices
│       ├── usage/              # Usage tracking
│       ├── messages/           # SMS messages (synced)
│       ├── outgoing_messages/  # Outbound SMS queue
│       ├── read_receipts/      # Message read status
│       ├── message_reactions/  # Message reactions
│       ├── spam_messages/      # Spam-filtered messages
│       ├── spam_whitelist/     # Allowed senders
│       ├── spam_blocklist/     # Blocked senders
│       ├── file_transfers/     # File sharing between devices
│       ├── continuity_state/   # Cross-device state sync
│       ├── groups/             # Group messaging
│       ├── calls/              # Phone call records
│       ├── call_requests/      # Outbound call queue
│       ├── sims/               # SIM card information
│       ├── syncflow_calls/     # VoIP calls between devices
│       ├── call_commands/      # Remote call control
│       ├── audio_routing_requests/ # Audio routing control
│       ├── active_calls/       # Currently active calls
│       ├── call_history/       # Call log
│       ├── contacts/           # Synced contacts
│       ├── webrtc_signaling/   # WebRTC for audio streaming
│       ├── incoming_syncflow_calls/ # Incoming VoIP calls
│       ├── outgoing_syncflow_calls/ # Outgoing VoIP calls
│       ├── clipboard/          # Shared clipboard
│       ├── media_command/      # Remote media control
│       ├── media_status/       # Media playback status
│       ├── find_my_phone/      # Phone finder feature
│       ├── shared_links/       # Link sharing
│       ├── hotspot_status/     # Mobile hotspot status
│       ├── hotspot_command/    # Hotspot control
│       ├── dnd_status/         # Do Not Disturb status
│       ├── dnd_command/        # DND control
│       ├── scheduled_messages/ # Scheduled SMS
│       ├── voicemails/         # Voicemail records
│       ├── photos/             # Photo sync
│       ├── mirrored_notifications/ # Notification mirroring
│       ├── recovery_info/      # Account recovery codes
│       └── sync_requests/      # Manual sync requests
│
├── recovery_codes/             # Global recovery code lookup
│   └── {codeHash}/             # Hashed recovery code -> userId
│
├── e2ee_keys/                  # End-to-end encryption keys
│   └── {uid}/                  # User's public keys
│       └── {deviceId}/         # Per-device keys
│
├── phone_to_uid/               # Phone number -> UID lookup
│   └── {phoneNumber}/
│
├── pending_pairings/           # Device pairing requests (pending)
│   └── {token}/
│
├── pairing_tokens/             # Pairing tokens (admin only)
│
├── pairing_requests/           # Device pairing requests
│   └── {token}/
│
├── phone_users/                # Phone hash -> UID mapping
│   └── {phoneHash}/
│
├── fcm_tokens/                 # FCM push notification tokens
│   └── {uid}/
│
├── fcm_notifications/          # Push notification queue
│   └── {userId}/
│       └── {callId}/
│
├── syncGroups/                 # Multi-device sync groups
│   └── {syncGroupId}/
│       ├── devices/
│       └── history/
│
├── subscription_accounts/      # Admin: subscription management
│   └── {deviceId}/
│
├── scheduled_deletions/        # Admin: pending account deletions
│   └── {userId}/
│
├── deleted_accounts/           # Admin: deleted account records
│   └── {userId}/
│
└── subscription_records/       # Detailed subscription history
    └── {uid}/
```

---

## Security Rules by Path

### `/users` - Root Users Collection

```javascript
".read": "auth != null && auth.token.admin === true"
".write": "auth != null && auth.token.admin === true"
```

**Access**: Admin-only at the collection level. Individual user paths have their own rules.

---

### `/users/{uid}` - Individual User Data

```javascript
".read": "auth != null && (auth.uid == $uid || auth.token.pairedUid == $uid || auth.token.admin === true || auth.uid == 'durgaprasad.chavali@gmail.com' || auth.uid == 'admin')"
".write": "auth != null && auth.uid == $uid"
```

**Access**:
- **Read**: Owner, paired devices, admins, or hardcoded admin accounts
- **Write**: Owner only (at this level)

---

### `/users/{uid}/profile` - User Profile

| Field | Type | Max Length | Validation |
|-------|------|------------|------------|
| `phone` | string | 20 chars | Optional |
| `phoneHash` | string | 64 chars | Optional, for lookups |
| `phoneVerifiedAt` | number | - | Optional timestamp |
| `platform` | string | - | Must be "android" or "ios" |
| `email` | string | 100 chars | Optional |
| `displayName` | string | 100 chars | Optional |

**Access**: Owner and paired devices can read; only owner can write.

---

### `/users/{uid}/subscription` - Subscription Details

| Field | Type | Validation |
|-------|------|------------|
| `plan` | string | "free", "monthly", "yearly", "3-yearly", or "3yearly" |
| `deviceLimit` | number | Optional |
| `createdAt` | number | Optional timestamp |
| `planExpiresAt` | number | Optional timestamp |

**Access**:
- **Read**: Owner, paired devices, or admins
- **Write**: Owner or admins

---

### `/users/{uid}/devices/{deviceId}` - Paired Devices

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `name` | string | Yes | Max 50 chars |
| `type` | string | No | "android", "web", "macos", or "ios" |
| `platform` | string | No | "android", "web", "macos", "ios", or "macOS" |
| `pairedAt` | number | No | Timestamp |
| `isPaired` | boolean | No | - |
| `lastSeen` | number | No | Timestamp |

**Access**:
- **Read**: Owner or paired devices (collection level)
- **Write**: Owner, or paired device writing to its own device record

---

### `/users/{uid}/messages/{messageId}` - SMS Messages

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `address` | string | Yes | 1-20 chars (phone number) |
| `body` | string | Yes | Max 1600 chars |
| `date` | number | Yes | Timestamp |
| `type` | number | Yes | 1 (received) or 2 (sent) |
| `contactName` | string | No | Max 100 chars |

**Indexes**: `date`, `timestamp`

**Access**: Owner and paired devices.

---

### `/users/{uid}/outgoing_messages/{messageId}` - Outbound SMS Queue

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `address` | string | Yes | 1-20 chars |
| `body` | string | Yes | Max 1600 chars |
| `timestamp` | number | No | - |
| `status` | string | No | "pending", "sent", or "failed" |

**Indexes**: `timestamp`, `status`

**Access**: Owner and paired devices (allows desktop to queue SMS for phone to send).

---

### `/users/{uid}/spam_messages/{spamId}` - Spam Messages

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `address` | string | Yes | 1-20 chars |
| `body` | string | Yes | Max 1600 chars |
| `date` | number | Yes | Timestamp |
| `contactName` | string | No | Max 100 chars |
| `spamConfidence` | number | No | Spam score |
| `spamReasons` | string | No | Reason for spam classification |
| `detectedAt` | number | No | Timestamp |
| `isUserMarked` | boolean | No | User-reported spam |
| `isRead` | boolean | No | Read status |

**Indexes**: `date`, `timestamp`

---

### `/users/{uid}/file_transfers/{fileId}` - File Sharing

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `fileName` | string | Yes* | 1-255 chars |
| `fileSize` | number | Yes* | - |
| `contentType` | string | Yes* | MIME type |
| `source` | string | Yes* | "android", "macos", or "web" |
| `status` | string | Yes* | "pending", "uploading", "uploaded", "downloading", "downloaded", "failed" |
| `timestamp` | number | Yes* | - |
| `r2Key` | string | Conditional | Required if no downloadUrl |
| `downloadUrl` | string | Conditional | Required if no r2Key |
| `error` | string | No | Error message |

*Required for new records only.

**Indexes**: `timestamp`, `status`

---

### `/users/{uid}/contacts/{contactId}` - Synced Contacts

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `displayName` | string | Yes | 1-200 chars |
| `phoneNumbers` | object | Yes | Array of phone objects |
| `sync` | object | Yes | Sync metadata |
| `notes` | string | No | Max 1000 chars |
| `company` | string | No | Max 200 chars |
| `emails` | object | No | Array of email objects |
| `photo` | object | No | Photo data with base64 thumbnail |
| `sources` | object | No | Source platforms |
| `androidContactId` | number | No | Original Android contact ID |

**Phone Number Object**:
| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `number` | string | Yes | 1-50 chars |
| `normalizedNumber` | string | No | Max 20 chars |
| `type` | string | No | Max 20 chars |
| `label` | string | No | Max 20 chars |
| `isPrimary` | boolean | Yes | - |

**Sync Object**:
| Field | Type | Required |
|-------|------|----------|
| `lastUpdatedAt` | number | Yes |
| `lastUpdatedBy` | string | Yes (max 50 chars) |
| `version` | number | Yes |
| `pendingAndroidSync` | boolean | Yes |
| `desktopOnly` | boolean | Yes |
| `lastSyncedAt` | number | No |

**Index**: `displayName`

---

### `/users/{uid}/call_history/{callId}` - Call Log

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `phoneNumber` | string | Yes | Non-empty |
| `callType` | string | Yes | "Incoming", "Outgoing", "Missed", "Rejected", "Blocked", "Voicemail", or lowercase variants |
| `contactName` | string | No | - |
| `callTypeInt` | number | No | Android call type constant |
| `callDate` | number | No | Timestamp |
| `date` | number | No | Timestamp |
| `duration` | number | No | Seconds |
| `formattedDuration` | string | No | Human-readable duration |
| `formattedDate` | string | No | Human-readable date |
| `simId` | number | No | SIM subscription ID |
| `syncedAt` | number | No | Sync timestamp |
| `timestamp` | number | No | - |
| `isRead` | boolean | No | Read status |

**Indexes**: `callDate`, `date`, `callType`, `phoneNumber`

---

### `/users/{uid}/syncflow_calls/{callId}` - VoIP Calls

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `callerId` | string | Yes | Non-empty |
| `calleeId` | string | Yes | Non-empty |
| `callType` | string | Yes | "audio" or "video" |
| `status` | string | Yes | "ringing", "active", "ended", "rejected", "missed", "failed" |
| `callerName` | string | No | Max 100 chars |
| `callerPlatform` | string | No | "android", "macos", "ios", "web" |
| `calleeName` | string | No | Max 100 chars |
| `calleePlatform` | string | No | "android", "macos", "ios", "web" |
| `startedAt` | number | No | Timestamp |
| `answeredAt` | number | No | Timestamp |
| `endedAt` | number | No | Timestamp |
| `offer` | object | No | WebRTC SDP offer |
| `answer` | object | No | WebRTC SDP answer |
| `ice_caller` | object | No | ICE candidates from caller |
| `ice_callee` | object | No | ICE candidates from callee |

**Indexes**: `status`, `startedAt`, `callerId`, `calleeId`

---

### `/users/{uid}/recovery_info` - Account Recovery

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `code` | string | Yes | 1-20 chars |
| `createdAt` | number | Yes | Timestamp |
| `codeHash` | string | No | Max 64 chars |

**Access**: Owner only (no paired device access for security).

---

### `/recovery_codes/{codeHash}` - Recovery Code Lookup

```javascript
".read": "auth != null"
".write": "auth != null"
```

| Field | Type | Required |
|-------|------|----------|
| `userId` | string | Yes |
| `createdAt` | number | Yes |
| `platform` | string | No |
| `lastUsedAt` | number | No |

**Purpose**: Allows recovery code verification by any authenticated user during account recovery.

---

### `/e2ee_keys/{uid}` - End-to-End Encryption Keys

```javascript
".read": "auth != null && (auth.uid == $uid || auth.token.pairedUid == $uid)"
".write": "auth != null && auth.uid == $uid"
```

**User-Level Keys**:
| Field | Type | Required |
|-------|------|----------|
| `publicKey` | string | Yes |
| `algorithm` | string | No |
| `version` | number | No |
| `platform` | string | No |
| `timestamp` | number | No |

**Per-Device Keys** (`/e2ee_keys/{uid}/{deviceId}`):
| Field | Type | Required |
|-------|------|----------|
| `publicKeyX963` | string | Yes |
| `format` | string | No |
| `keyVersion` | number | No |
| `platform` | string | No |
| `timestamp` | number | No |

**Access**: Owner can write user-level keys; devices can only write their own device keys.

---

### `/pending_pairings/{token}` - Device Pairing

```javascript
".read": false  // Collection level
".write": false // Collection level
// Per-token:
".read": "auth != null && data.child('requesterUid').val() == auth.uid"
".write": false
```

**Purpose**: Managed by Cloud Functions only. Users can read their own pending pairing requests.

---

### `/pairing_requests/{token}` - Pairing Requests

```javascript
".read": "auth != null"  // Any authenticated user can read
".write": false          // Cloud Functions only
```

**Purpose**: Desktop clients can poll for pairing approval; writes handled by Cloud Functions.

---

### `/fcm_tokens/{uid}` - Push Notification Tokens

```javascript
".read": "auth != null && auth.uid == $uid"
".write": "auth != null && auth.uid == $uid"
```

**Validation**: String, 1-500 chars

**Access**: Owner only (no paired device access).

---

### `/syncGroups/{syncGroupId}` - Multi-Device Groups

| Field | Access | Description |
|-------|--------|-------------|
| `plan` | Admin write | Subscription plan |
| `deviceLimit` | Admin write | Max devices |
| `planExpiresAt` | Admin write | Expiration timestamp |
| `wasPremium` | Admin write | Premium history flag |
| `firstPremiumDate` | Admin write | First premium timestamp |
| `masterDevice` | Any auth | Primary device ID |
| `createdAt` | Any auth | Creation timestamp |
| `devices/` | Any auth | Device list |
| `history/` | Admin write | Change history |

**Purpose**: Groups multiple devices/accounts under one subscription.

---

### `/subscription_accounts/{deviceId}` - Admin Subscription Management

```javascript
".read": "auth != null && auth.token.admin === true"
".write": "auth != null && auth.token.admin === true"
```

**Access**: Admin only.

---

### `/scheduled_deletions/{userId}` - Pending Account Deletions

```javascript
".read": "auth != null && auth.token.admin === true"
".write": "auth != null && auth.token.admin === true"
```

**Access**: Admin only.

---

### `/deleted_accounts/{userId}` - Deleted Account Records

```javascript
".read": "auth != null && auth.token.admin === true"
".write": "auth != null && auth.token.admin === true"
```

**Indexes**: `deletedAt`, `status`

**Access**: Admin only.

---

### `/subscription_records/{uid}` - Subscription History

| Field | Access |
|-------|--------|
| `active/` | Owner or admin |
| `wasPremium` | Owner or admin |
| `firstPremiumDate` | Owner or admin |
| `totalPremiumDays` | Owner or admin |
| `history/` | Owner or admin |
| `purchases/` | Owner or admin |

**Collection-Level Read**: Admin only (for listing all users)

---

## Validation Rules Summary

### String Length Limits

| Field | Max Length |
|-------|------------|
| Phone numbers | 20 chars |
| Phone hashes | 64 chars |
| Display names | 100 chars |
| Email addresses | 100 chars |
| Device names | 50 chars |
| Message bodies | 1600 chars |
| Contact names | 100 chars |
| File names | 255 chars |
| Clipboard content | 100000 chars |
| Photo thumbnails (base64) | 150000 chars |
| FCM tokens | 500 chars |

### Enum Validations

| Field | Valid Values |
|-------|--------------|
| Platform | "android", "ios", "macos", "macOS", "web" |
| Plan | "free", "monthly", "yearly", "3-yearly", "3yearly" |
| Message type | 1 (received), 2 (sent) |
| Message status | "pending", "sent", "failed" |
| File status | "pending", "uploading", "uploaded", "downloading", "downloaded", "failed" |
| Call type | "incoming", "outgoing" |
| Call state | "ringing", "active", "ended", "rejected", "missed", "failed" |
| VoIP type | "audio", "video" |
| Media commands | "play", "pause", "next", "previous", "volume_up", "volume_down" |
| Call commands | "answer", "reject", "end", "mute", "unmute" |
| Pairing status | "pending", "approved", "rejected", "expired" |
| Sync status | "pending", "in_progress", "completed", "failed" |

---

## Database Indexes

The following indexes are defined for efficient querying:

| Path | Indexed Fields |
|------|----------------|
| `/users/{uid}/messages` | `date`, `timestamp` |
| `/users/{uid}/outgoing_messages` | `timestamp`, `status` |
| `/users/{uid}/spam_messages` | `date`, `timestamp` |
| `/users/{uid}/file_transfers` | `timestamp`, `status` |
| `/users/{uid}/continuity_state` | `timestamp` |
| `/users/{uid}/syncflow_calls` | `status`, `startedAt`, `callerId`, `calleeId` |
| `/users/{uid}/call_commands` | `timestamp`, `processed` |
| `/users/{uid}/audio_routing_requests` | `timestamp`, `processed` |
| `/users/{uid}/active_calls` | `timestamp`, `state` |
| `/users/{uid}/call_history` | `callDate`, `date`, `callType`, `phoneNumber` |
| `/users/{uid}/call_requests` | `requestedAt`, `status` |
| `/users/{uid}/contacts` | `displayName` |
| `/users/{uid}/webrtc_signaling` | `timestamp`, `status` |
| `/users/{uid}/incoming_syncflow_calls` | `status`, `startedAt`, `callerUid` |
| `/users/{uid}/outgoing_syncflow_calls` | `status`, `startedAt`, `recipientUid` |
| `/users/{uid}/scheduled_messages` | `scheduledTime`, `status` |
| `/users/{uid}/voicemails` | `date` |
| `/users/{uid}/sync_requests` | `status`, `requestedAt` |
| `/deleted_accounts` | `deletedAt`, `status` |

---

## Common Patterns

### Pattern 1: Owner + Paired Device Access

```javascript
".read": "auth != null && (auth.uid == $uid || auth.token.pairedUid == $uid)"
".write": "auth != null && (auth.uid == $uid || auth.token.pairedUid == $uid)"
```

Used for: messages, file_transfers, clipboard, media control, most user data.

### Pattern 2: Owner-Only Write with Paired Read

```javascript
".read": "auth != null && (auth.uid == $uid || auth.token.pairedUid == $uid)"
".write": "auth != null && auth.uid == $uid"
```

Used for: profile, ensuring only the primary device can update certain data.

### Pattern 3: Owner-Only (No Paired Access)

```javascript
".read": "auth != null && auth.uid == $uid"
".write": "auth != null && auth.uid == $uid"
```

Used for: recovery_info, fcm_tokens - sensitive data that paired devices shouldn't access.

### Pattern 4: Admin-Only Access

```javascript
".read": "auth != null && auth.token.admin === true"
".write": "auth != null && auth.token.admin === true"
```

Used for: subscription management, account deletion, user listing.

### Pattern 5: Conditional Required Fields

```javascript
".validate": "(!data.exists() && newData.hasChildren(['field1','field2'])) || data.exists()"
```

Used for: file_transfers - requires certain fields only on creation, allows updates.

### Pattern 6: Optional Field with Type Validation

```javascript
".validate": "!newData.exists() || (newData.isString() && newData.val().length <= 100)"
```

Pattern: Field is optional, but if provided, must match type and constraints.

---

## Security Best Practices Implemented

1. **Principle of Least Privilege**: Users can only access their own data unless explicitly paired.

2. **Data Validation**: All fields have type validation; strings have length limits.

3. **Enum Validation**: Status fields and types are validated against allowed values.

4. **Sensitive Data Protection**: Recovery codes and FCM tokens exclude paired device access.

5. **Admin Separation**: Administrative functions require explicit admin token claim.

6. **No Public Access**: All paths require authentication; no data is publicly readable.

7. **Write Protection for System Data**: Pairing requests are read-only for users; Cloud Functions handle writes.

---

## Rate Limiting

Firebase Realtime Database does not have built-in rate limiting in security rules. Rate limiting is implemented at the application level and through Cloud Functions that process sensitive operations.

---

## Notes for Developers

1. **Custom Tokens**: When creating pairing tokens via Cloud Functions, include `pairedUid` and `deviceId` claims.

2. **Validation Order**: Firebase validates data bottom-up. Child validations run before parent validations.

3. **Null Handling**: Use `!newData.exists()` to allow field deletion.

4. **Index Performance**: Only indexed fields should be used in `orderByChild()` queries.

5. **Timestamp Format**: All timestamps should be Unix milliseconds (JavaScript `Date.now()`).

6. **Testing**: Use the Firebase Emulator Suite to test rule changes before deployment.

---

*Last updated: January 2026*
