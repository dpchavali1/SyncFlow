# Sync Group Implementation Guide

## Overview

The Sync Group architecture enables device-based pairing where multiple devices (Android, macOS, Web) can be linked together to share SMS/MMS data. The system enforces device limits based on subscription tier and maintains complete audit trails.

**Key Features:**
- ✅ 3 device limit for free users, unlimited for pro
- ✅ Device-stable IDs that survive app reinstall/uninstall
- ✅ Subscription tracking per sync group
- ✅ Complete audit history of all group changes
- ✅ Device recovery on app reinstall

---

## Architecture Overview

### Data Model

```
syncGroups/
└── {syncGroupId}/
    ├── plan: "free" | "monthly" | "yearly" | "lifetime"
    ├── deviceLimit: 3 (free) or 999 (pro)
    ├── masterDevice: "{deviceId}"              # Device that created the group
    ├── createdAt: {timestamp}
    ├── planExpiresAt: {timestamp} | null
    ├── wasPremium: true
    ├── firstPremiumDate: {timestamp}
    │
    ├── devices/
    │   ├── {deviceId}:
    │   │   ├── deviceType: "android" | "macos" | "web"
    │   │   ├── joinedAt: {timestamp}
    │   │   ├── lastSyncedAt: {timestamp}
    │   │   ├── status: "active" | "inactive"
    │   │   └── deviceName: "User's Phone"
    │   └── ... more devices
    │
    └── history/
        ├── {timestamp}: {action, deviceId, deviceType, ...}
        └── ... more entries
```

### Device IDs (Platform-Specific)

**Android:**
```kotlin
// Stable: Android device hardware ID
val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
// Format: 32-character alphanumeric string
// Survives: app uninstall/reinstall
// Survives: factory reset? NO (will get new ID)
```

**macOS:**
```swift
// Stable: Hardware UUID or generated UUID
let deviceId = "mac_" + getMacHardwareUUID() // or "mac_" + UUID.uuidString
// Format: "mac_" + 16 chars hex or full UUID
// Survives: app reinstall
// Survives: app restart
```

**Web:**
```typescript
// Stable: localStorage-based UUID
let deviceId = localStorage.getItem('device_id') || 'web_' + crypto.randomUUID()
// Format: "web_" + UUID
// Survives: browser restart
// Lost on: clear browser data/cache (recovery via QR code scan)
```

---

## Pairing Flows

### Flow 1: User Gets First Device (macOS/Web)

```
macOS app first launch:
  1. Check: Is sync_group_id in UserDefaults? NO
  2. Generate: syncGroupId = "sync_" + UUID()
  3. Save locally: UserDefaults.set(syncGroupId)
  4. Create in Firebase: syncGroups/{syncGroupId}/ {
       plan: "free",
       deviceLimit: 3,
       masterDevice: {deviceId},
       devices: { {deviceId}: {deviceType: "macos", ...} }
     }
  5. Generate QR code containing: syncGroupId
  6. Display QR code on screen

Result: macOS device is now the "master" of sync group
```

### Flow 2: User Pairs Android (Scans macOS QR Code)

```
Android app:
  1. User launches app, sees "Scan QR code to pair"
  2. User scans macOS screen → extracts syncGroupId
  3. Call: SyncGroupManager.joinSyncGroup(scannedSyncGroupId)

     In joinSyncGroup:
       a. Check Firebase: syncGroups/{syncGroupId}/
       b. Get plan, calculate deviceLimit (free=3, pro=999)
       c. Count current devices in group
       d. Verify: currentDevices < deviceLimit
          - If false: ❌ Show error "Device limit reached. Upgrade to Pro."
       e. Register device in Firebase:
          syncGroups/{syncGroupId}/devices/{androidDeviceId}/ = {...}
       f. Save locally: SharedPreferences.putString("sync_group_id", syncGroupId)
       g. Add to history: action: "device_joined"

  4. Result: Android device now part of sync group ✅

Visible to User:
  ✅ "Connected to macOS device"
  ✅ "You can now see SMS/MMS from Android on macOS"
  ✅ "3/3 devices used (free tier)" or "2/unlimited (pro)"
```

### Flow 3: User Gets Second Android (Already Has One + macOS)

```
New Android app first launch:
  1. Check: sync_group_id in SharedPreferences? NO (new install)
  2. User scans macOS QR code → gets syncGroupId
  3. Call: SyncGroupManager.joinSyncGroup(scannedSyncGroupId)

     Device count check:
       Current: macOS (1) + old Android (2) = 2/3 devices
       ✅ Can add new device

  4. Register: new Android added to devices list
  5. Firebase now has: {macOS, old Android, new Android}
  6. Both Androids can sync SMS/MMS from same group

Result: 3/3 devices used (if free tier)
        User cannot add 4th device without upgrading to Pro
```

### Flow 4: User Uninstalls/Reinstalls Android (Same Phone)

```
Old Android uninstalled:
  → syncGroups/{syncGroupId}/devices/{androidDeviceId}/ still exists ✅
  → Data still there, just device is offline

New Android installed on SAME HARDWARE:
  1. Check: sync_group_id in SharedPreferences? NO (fresh install)
  2. Get device ID: Settings.Secure.ANDROID_ID = "same_as_before"
  3. Call: SyncGroupManager.recoverSyncGroup()

     In recoverSyncGroup:
       a. Query: ALL syncGroups looking for one with this deviceId in devices/
       b. Find match: syncGroupId
       c. Save locally: SharedPreferences.putString("sync_group_id", found_id)
       d. ✅ Group recovered!

  4. No manual pairing needed, app continues as if never uninstalled

Result: Subscription never lost, all SMS/MMS still accessible
```

### Flow 5: User Upgrades from Free to Pro

```
Admin changes sync group plan:
  1. Admin dashboard → Select sync group
  2. Click "Upgrade to Pro Plan"
  3. Calls: updateSyncGroupPlan(syncGroupId, "monthly")

     In Cloud Function:
       a. Validate admin access
       b. Update: syncGroups/{syncGroupId}/plan = "monthly"
       c. Set: planExpiresAt = now + 30 days
       d. Update: deviceLimit = 999 (unlimited)
       e. Add to history: action: "plan_updated"

  4. All devices see: "3/unlimited devices"
  5. Can now pair 4th, 5th, etc. devices

Result: Subscription follows the sync group, not individual devices
```

---

## Integration Points

### 1. Android - Pairing Flow Integration

**File:** `app/src/main/java/com/phoneintegration/app/auth/UnifiedIdentityManager.kt`

**Current Flow (Existing):**
```kotlin
fun redeemPairingToken(token, deviceName) {
    // Calls Cloud Function, gets custom token
    // Signs in with custom token
}
```

**New Integration (TODO):**
```kotlin
// After successful pairing:
val syncGroupId = pairingResponse.syncGroupId  // From QR code or server
val syncGroupManager = SyncGroupManager(context)

// Option A: Scan QR code
val scannedGroupId = scanQRCode()  // User action
val result = syncGroupManager.joinSyncGroup(scannedGroupId)
if (result.success) {
    showMessage("Connected! Using ${result.deviceCount}/${result.limit} devices")
} else {
    showError(result.error)  // "Device limit reached"
}

// Option B: Auto-recover on reinstall
val recovered = syncGroupManager.recoverSyncGroup()
if (recovered.success) {
    showMessage("Reconnected to sync group")
} else {
    showMessage("Scan QR code to create/join group")
}
```

### 2. macOS - Pairing Flow Integration

**File:** `SyncFlowMac/SyncFlowMac/Views/PairingView.swift` (or similar)

**Current Flow (Existing):**
```swift
@State var pairingSession: PairingSession?

func initiatePairing() {
    FirebaseService.shared.initiatePairing { session in
        self.pairingSession = session
        // Show QR code
    }
}
```

**New Integration (TODO):**
```swift
@State var syncGroupManager = SyncGroupManager.shared

func initiatePairing() {
    // Check if already has sync group
    if syncGroupManager.isPaired {
        showMessage("Already paired to: \(syncGroupManager.syncGroupId)")
        return
    }

    // Create new sync group
    syncGroupManager.createSyncGroup { result in
        switch result {
        case .success(let syncGroupId):
            // Show QR code with syncGroupId
            showQRCode(syncGroupManager.getQRCodeContent()!)

        case .failure(let error):
            showError("Failed to create pairing: \(error)")
        }
    }
}
```

### 3. Web - Pairing Flow Integration

**File:** `web/components/PairingScreen.tsx`

**Current Flow (Existing):**
```typescript
const [pairingToken, setPairingToken] = useState<string>('')

async function initiatePairing() {
    const token = await initiatePairingToken()
    setPairingToken(token)
    // Show QR code with token
}
```

**New Integration (TODO):**
```typescript
const [syncGroupId, setSyncGroupId] = useState<string>('')
const [isJoined, setIsJoined] = useState(false)

async function createOrJoinSyncGroup() {
    // Try to recover existing group
    const recovered = await recoverSyncGroup('web')
    if (recovered.success && recovered.syncGroupId) {
        setSyncGroupId(recovered.syncGroupId)
        setIsJoined(true)
        return
    }

    // Create new group
    const newGroupId = getSyncGroupId()  // Auto-generates if not exists
    const created = await createSyncGroup(newGroupId, 'web')
    if (created) {
        setSyncGroupId(newGroupId)
        setIsJoined(true)
        // Show QR code with newGroupId
    }
}

// When user scans QR from another device
async function joinScannedGroup() {
    const scannedGroupId = scanQRCode()  // User scans with camera
    const result = await joinSyncGroup(scannedGroupId, 'web')

    if (result.success) {
        setSyncGroupId(scannedGroupId)
        setIsJoined(true)
        showMessage(`Connected! Using ${result.deviceCount}/${result.limit} devices`)
    } else {
        showError(result.error)
    }
}
```

---

## Subscription Tracking

### Subscription Persistence Per Sync Group

**Current (Old - UID-Based):**
```
subscription_records/{userId}/
  ├── active/plan: "monthly"
  └── history/...

Problem: Deleted when user deletes account, breaks on UID change
```

**New (Sync Group-Based):**
```
syncGroups/{syncGroupId}/
  ├── plan: "monthly"                    # Subscription for entire group
  ├── planExpiresAt: {timestamp}
  ├── wasPremium: true
  └── history/...                        # Plan change history

Benefits:
  ✅ Survives device removal
  ✅ Survives UID changes
  ✅ One subscription per group, shared across all devices
  ✅ Automatic upgrade path (free → pro)
```

### Reading Subscription in Apps

**Android:**
```kotlin
// Instead of: users/{uid}/usage/plan
// Now: syncGroups/{syncGroupId}/plan

val syncGroupManager = SyncGroupManager(context)
syncGroupManager.getSyncGroupInfo().onSuccess { info ->
    val plan = info.plan  // "free", "monthly", etc.
    val deviceLimit = info.deviceLimit  // 3 or 999
    val deviceCount = info.deviceCount  // How many devices in group

    updateUI(plan, deviceLimit, deviceCount)
}
```

**macOS:**
```swift
// Same pattern
syncGroupManager.getSyncGroupInfo { result in
    switch result {
    case .success(let info):
        self.plan = info.plan
        self.deviceCount = info.devices.count
        self.deviceLimit = info.deviceLimit

    case .failure:
        self.plan = "free"  // Fallback
    }
}
```

**Web:**
```typescript
// Same pattern
const info = await getSyncGroupInfo(syncGroupId)
setPlan(info.data.plan)
setDeviceCount(info.data.deviceCount)
setDeviceLimit(info.data.deviceLimit)
```

---

## Admin Dashboard Updates

### New Admin Tab: "Sync Groups"

**Features:**
1. **List All Sync Groups**
   - Show: syncGroupId, plan, device count/limit, created date, master device
   - Actions: View details, upgrade plan, delete group
   - Filter: By plan, by device count, by creation date

2. **View Group Details**
   - All devices in group with join dates
   - Complete audit history
   - Plan information with expiry
   - Option to add/remove specific devices

3. **Manage Plans**
   - Upgrade sync group: free → monthly/yearly/lifetime
   - View which devices benefit from upgrade
   - Bulk upgrade (multiple groups)

4. **Device Management**
   - Remove device from group
   - View device sync status
   - See last sync time
   - Block device (admin feature)

### Implementation Files (TODO)

- `/web/app/admin/sync-groups/page.tsx` - List view
- `/web/app/admin/sync-groups/[groupId]/page.tsx` - Detail view
- `/web/lib/firebase.ts` - Already has functions, just need UI

---

## Testing Checklist

### Unit Tests

- [ ] SyncGroupManager.joinSyncGroup() validates device limit
- [ ] SyncGroupManager.recoverSyncGroup() finds correct group
- [ ] Cloud Functions reject invalid plans
- [ ] Cloud Functions check admin access

### Integration Tests

- [ ] macOS creates sync group and shows QR code
- [ ] Android scans QR and joins group successfully
- [ ] Web scans QR and joins group successfully
- [ ] Android reinstall recovers sync group
- [ ] Web clear cache → can recover via QR scan
- [ ] Device limit enforced (can't add 4th to free tier)
- [ ] Upgrading plan changes deviceLimit from 3 to 999

### End-to-End Scenarios

- [ ] **Scenario 1:** macOS creates group → Android scans → Web scans
  - Expected: All 3 devices in sync group, can share SMS

- [ ] **Scenario 2:** macOS creates, Android joins, user upgrades to Pro
  - Expected: Can add 4th device (Web)

- [ ] **Scenario 3:** macOS creates, Android joins, Android uninstalls
  - Expected: Android reinstall automatically recovers group

- [ ] **Scenario 4:** macOS creates, Android joins, macOS deleted
  - Expected: Web can still scan Android's screen to verify group

- [ ] **Scenario 5:** Free tier user gets 3 devices, tries to add 4th
  - Expected: ❌ "Device limit reached. Upgrade to Pro."

---

## Error Handling

### Common Error Scenarios

| Scenario | Error Code | User Message | Recovery |
|----------|-----------|--------------|----------|
| Scan expired QR code | GROUP_NOT_FOUND | "Sync group expired, scan current code" | Rescan from other device |
| Device limit reached | DEVICE_LIMIT_EXCEEDED | "3/3 devices used. Upgrade to Pro for unlimited." | Upgrade plan |
| Network error during join | NETWORK_ERROR | "Connection lost. Please try again." | Auto-retry or manual retry |
| Sync group deleted | GROUP_DELETED | "Sync group was removed. Create new one." | Create new group |
| Corrupted device ID | INVALID_DEVICE_ID | "Device ID invalid. Reinstall app." | Reinstall app |

### Logging

All operations are logged to Firebase history:
```
syncGroups/{groupId}/history/{timestamp}/ = {
  action: "device_joined" | "device_removed" | "plan_updated" | ...,
  deviceId: "{deviceId}",
  deviceType: "android" | "macos" | "web",
  timestamp: {ms},
  ...metadata
}
```

Admin can query history for debugging device issues.

---

## Security Considerations

### Authentication & Authorization

- ✅ Only authenticated users can join groups
- ✅ Admin-only functions require `auth.token.admin === true`
- ✅ Device removal requires user auth (can remove own device)
- ✅ Device removal by admin doesn't need user permission

### Rate Limiting (Recommended - Future)

- [ ] Limit group creations per user per day
- [ ] Limit join attempts per sync group
- [ ] Limit plan changes per sync group per month

### Data Privacy

- ✅ Device IDs stored but not personal data
- ✅ Sync group not linked to user account (privacy advantage)
- ✅ History pruned after 90 days (configurable)

---

## Future Enhancements

1. **Device Naming**
   - Let users name devices ("John's iPhone", "Work Laptop")
   - Shows in admin dashboard

2. **Device Blocking**
   - Admin can block device from group
   - Prevents reconnection until unblocked

3. **Sync Group Recovery**
   - If master device deleted, promote oldest device to master
   - Preserve group data and devices

4. **Usage Analytics**
   - Track sync group creation rate
   - Track upgrade conversion (free → pro)
   - Track average devices per group

5. **Fraud Detection**
   - Alert on >10 devices in group (likely reseller)
   - Alert on multiple groups created from same IP
   - Flag devices creating many groups with short lifespans

---

## References

- **Architecture Decision:** See `SYNC_GROUP_IMPLEMENTATION.md` (this file)
- **Firebase Rules:** `database.rules.json` (syncGroups section)
- **SyncGroupManager (Android):** `app/src/main/java/com/phoneintegration/app/sync/SyncGroupManager.kt`
- **SyncGroupManager (macOS):** `SyncFlowMac/SyncFlowMac/Services/SyncGroupManager.swift`
- **SyncGroupManager (Web):** `web/lib/firebase.ts` (getSyncGroupId, joinSyncGroup, etc.)
- **Cloud Functions:** `functions/index.js` (updateSyncGroupPlan, getSyncGroupInfo, etc.)
- **Previous Architecture:** `SUBSCRIPTION_RECORDS_ARCHITECTURE.md` (still valid for subscription tracking)
