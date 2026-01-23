# Device-Based Subscription Tracking Architecture

## Problem Statement

The previous `subscription_records/{uid}` architecture had a critical flaw:

**Scenario:**
1. User registers → Gets UID1 → Subscribes to monthly plan
2. User deletes account → `users/UID1` deleted, `subscription_records/UID1` orphaned
3. Same person re-registers → Gets NEW UID2 → No connection to previous subscription
4. Result: Subscription history lost, fraud detection fails, analytics broken

**The Root Cause:**
User IDs (UIDs) are not stable across account deletions. When users re-register, they get a completely new UID, breaking the link to their previous subscription history.

## Solution: Device-Based Subscription Tracking

Instead of keying subscriptions by UID (which changes), we key them by **Device ID** (which persists across account creation/deletion).

### Architecture Overview

```
TWO-TIER SUBSCRIPTION SYSTEM:

Tier 1: subscription_records/{uid}/
├── Purpose: Current subscription for active users
├── Lifecycle: Deleted when user deletes account
├── Use: Fast lookup by UID for active users
└── Fallback: Check device if UID deleted

Tier 2: subscription_accounts/{deviceId}/
├── Purpose: Persistent device-level history
├── Lifecycle: Survives user deletion and UID changes
├── Use: Track user across account recreations
└── Primary: Complete subscription lifecycle
```

### Data Structure

```
subscription_accounts/{deviceId}/
├── currentUid: "uid2"                    # Currently active user on this device
├── currentPlan: "monthly"                # Current plan tier
├── wasPremium: true                      # Ever been premium on this device?
├── firstPremiumDate: 1769188157835       # When first premium (device-wide)
├── totalPremiumDays: 365                 # Total premium days across all accounts
│
└── history/                              # All accounts created on this device
    ├── 1769188157835:                    # Timestamp = First account created
    │   ├── uid: "uid1"                   # User ID
    │   ├── plan: "monthly"               # Plan at that time
    │   ├── createdAt: 1769188157835
    │   ├── deletedAt: 1769274557835      # When user deleted this account
    │   └── status: "deleted"             # Account status
    │
    ├── 1769274557836:                    # Timestamp = Second account created
    │   ├── uid: "uid2"                   # New user ID (same person, new account)
    │   ├── plan: "monthly"
    │   ├── createdAt: 1769274557836
    │   └── status: "active"              # Currently active
    │
    └── ... more accounts on this device ...
```

## How It Works

### Scenario: User Lifecycle with Device Tracking

**Day 1: Initial Registration**
```
User action: Signs up on Android phone
Device ID: "android-device-uuid-123"
Firebase transaction:
  ✅ Create users/{uid1}/ with plan="free"
  ✅ Create subscription_records/{uid1}/active/plan="free"
  ✅ Create subscription_accounts/{android-device-uuid-123}/
     └── currentUid: "uid1"
     └── currentPlan: "free"
     └── history/{timestamp1}/uid="uid1", plan="free", status="active"
```

**Day 3: User Upgrades to Premium**
```
User action: Purchases monthly subscription
Firebase transaction:
  ✅ Update users/{uid1}/usage/plan="monthly"
  ✅ Update subscription_records/{uid1}/active/plan="monthly"
  ✅ Update subscription_accounts/{android-device-uuid-123}/
     └── currentPlan: "monthly"
     └── wasPremium: true
     └── firstPremiumDate: {timestamp}
     └── history/{timestamp1}/plan="monthly" (update existing)
```

**Day 30: User Deletes Account**
```
User action: Deletes account
Firebase transaction:
  ❌ Delete users/{uid1}/ (personal data, GDPR)
  ❌ Delete subscription_records/{uid1}/ (backup deleted)
  ✅ Update subscription_accounts/{android-device-uuid-123}/
     └── history/{timestamp1}/deletedAt={now}, status="deleted"

Result: subscription_accounts still exists! History preserved! ✅
```

**Day 35: User Re-registers**
```
User action: Registers new account with same email/phone
Device ID: Same (android-device-uuid-123)
Firebase transaction:
  ✅ Create users/{uid2}/ (new account)
  ✅ Create subscription_records/{uid2}/
  ✅ Update subscription_accounts/{android-device-uuid-123}/
     └── currentUid: "uid2" (updated to new user)
     └── history/{timestamp2}/uid="uid2", plan="free", status="active"

System can now see:
  "Same device previously had uid1 with plan=monthly"
  "This is likely same user re-registering"
  Can offer: "Welcome back! Your previous plan was Monthly"
```

## Firebase Security Rules

### subscription_accounts Collection

```json
"subscription_accounts": {
  "$deviceId": {
    ".read": "auth != null && auth.token.admin === true",
    ".write": "auth != null && auth.token.admin === true",
    "currentUid": { ".validate": "newData.isString()" },
    "currentPlan": {
      ".validate": "!newData.exists() || newData.isString()"
    },
    "wasPremium": { ".validate": "!newData.exists() || newData.isBoolean()" },
    "firstPremiumDate": { ".validate": "!newData.exists() || newData.isNumber()" },
    "totalPremiumDays": { ".validate": "!newData.exists() || newData.isNumber()" },
    "history": {
      "$timestamp": {
        ".validate": "newData.hasChildren(['uid'])",
        "uid": { ".validate": "newData.isString()" },
        "plan": { ".validate": "!newData.exists() || newData.isString()" },
        "createdAt": { ".validate": "!newData.exists() || newData.isNumber()" },
        "deletedAt": { ".validate": "!newData.exists() || newData.isNumber()" },
        "status": {
          ".validate": "!newData.exists() || newData.isString()"
        }
      }
    }
  }
}
```

**Access Control:**
- ✅ Admins only can read/write
- ❌ Regular users cannot directly access (for privacy)
- ✅ Cloud Functions can write on signup/deletion
- ✅ Admins can view for analytics/fraud detection

## Helper Functions

All functions exported from `web/lib/firebase.ts`:

### 1. trackSubscriptionByDevice()

Called when user registers or changes plan.

```typescript
await trackSubscriptionByDevice(
  deviceId: string,        // Device ID from app
  userId: string,          // User's UID
  plan: string,            // "monthly", "yearly", "lifetime", "free"
  planExpiresAt: number    // Expiration timestamp (null for free/lifetime)
)
```

**What it does:**
- Creates/updates `subscription_accounts/{deviceId}/`
- Sets `currentUid` to this user
- Sets `currentPlan` to this plan
- Adds entry to `history/{timestamp}/`
- Auto-sets `wasPremium` if premium plan
- Sets `firstPremiumDate` on first premium subscription

**Example:**
```typescript
// User just registered
const deviceId = await getDeviceId() // From app
await trackSubscriptionByDevice(deviceId, uid, "free", null)

// User upgraded to monthly
await trackSubscriptionByDevice(deviceId, uid, "monthly", expiresAt)
```

### 2. getSubscriptionByDevice()

Look up device subscription history.

```typescript
const subscription = await getSubscriptionByDevice(deviceId: string)
// Returns: {
//   currentUid: "uid2",
//   currentPlan: "monthly",
//   wasPremium: true,
//   firstPremiumDate: 1769188157835,
//   totalPremiumDays: 365,
//   history: { ... }
// }
```

**What it does:**
- Retrieves complete subscription account for device
- Shows current and all historical accounts
- Useful for: Identifying re-registrations, fraud detection, support

**Example:**
```typescript
// Check if device previously had premium
const account = await getSubscriptionByDevice(deviceId)
if (account?.wasPremium) {
  console.log("User previously subscribed")
  // Offer win-back incentive
}
```

### 3. markAccountDeletedByDevice()

Mark a user account as deleted in device history.

```typescript
await markAccountDeletedByDevice(deviceId: string, userId: string)
```

**What it does:**
- Finds the history entry for this user
- Sets `status: "deleted"`
- Sets `deletedAt: {timestamp}`
- Keeps history for analytics

**Example:**
```typescript
// User deletes their account
const deviceId = await getDeviceId()
await markAccountDeletedByDevice(deviceId, uid)
// History preserved: subscription_accounts still exists
```

## Integration Points

### 1. User Registration

**File:** `app/src/main/java/com/phoneintegration/app/auth/` (Android)

```kotlin
// After user registers successfully
val deviceId = getDeviceId()  // Implementation needed
val userId = auth.currentUser?.uid
if (userId != null) {
  trackSubscriptionByDevice(deviceId, userId, "free", null)
}
```

**File:** `SyncFlowMac/SyncFlowMac/Services/` (macOS)

```swift
// After authentication succeeds
let deviceId = UIDevice.current.identifierForVendor?.uuidString
if let userId = Auth.auth().currentUser?.uid {
  await trackSubscriptionByDevice(deviceId, userId, "free", nil)
}
```

### 2. Plan Changes

**File:** `web/app/admin/cleanup/page.tsx` (Already integrated in Testing Tab)

```typescript
// When plan is assigned
await trackSubscriptionByDevice(
  deviceIdFromUser,
  testUserId,
  testPlan,
  planExpiresAt
)
```

### 3. Account Deletion

**File:** `web/lib/firebase.ts` - deleteUserAccount()

```typescript
// When user deletes account
const deviceId = userDeviceId  // Need to fetch this
await markAccountDeletedByDevice(deviceId, userId)
// Then delete users/{uid}
```

## Use Cases Enabled

### 1. Fraud Detection
```
"Device X has 10 accounts created in past week, all deleted after trial"
→ Flag as trial fraud, block device or require payment
```

### 2. Churn Analysis
```
"Premium user (uid1) deleted, same device re-registered (uid2) as free"
→ "User churned to free tier, try win-back offer"
```

### 3. Revenue Tracking
```
"totalPremiumDays: 365"
→ "This device generated 1 year of premium revenue"
→ Accurate LTV even across account deletions
```

### 4. Customer Support
```
"Help, I lost access to my paid subscription!"
→ Check subscription_accounts
→ "You had monthly plan on this device"
→ Restore or offer credit
```

### 5. Analytics
```
Query: Count devices with wasPremium=true
→ How many devices have generated revenue
→ Unbroken by user deletions
```

### 6. Retention Campaigns
```
Account deleted → Check subscription_accounts
If wasPremium=true → Send "We miss you" email
→ Win-back campaign for lapsed premium users
```

## Comparison: Before vs After

| Scenario | subscription_records/{uid} | subscription_accounts/{deviceId} |
|----------|----------------------|--------------------------|
| User deletes account | ❌ Deleted with user | ✅ Survives |
| User re-registers (new UID) | ❌ No connection | ✅ Linked in history |
| Identify same person | ❌ Impossible | ✅ By deviceId |
| Fraud detection | ❌ Lost on deletion | ✅ Complete history |
| Revenue tracking | ❌ Lost subscriptions | ✅ totalPremiumDays |
| Support lookup | ❌ No history | ✅ Full lifecycle |

## Implementation Roadmap

### Phase 1: Foundation (DONE)
- ✅ Add subscription_accounts to Firebase rules
- ✅ Create helper functions (trackSubscriptionByDevice, etc.)
- ✅ Document architecture

### Phase 2: Integration (TODO)
- [ ] Add deviceId tracking when user registers (all platforms)
- [ ] Call trackSubscriptionByDevice on signup/upgrade
- [ ] Call markAccountDeletedByDevice on account deletion
- [ ] Update Testing tab to use device tracking

### Phase 3: Analytics (TODO)
- [ ] Create dashboard showing device subscription metrics
- [ ] Implement fraud detection queries
- [ ] Build win-back campaigns
- [ ] Revenue analytics by device

### Phase 4: User Features (TODO)
- [ ] Show "Welcome back" message for returning users
- [ ] Restore previous plan option
- [ ] Device management UI (show all accounts on device)

## Getting Device ID

Each platform needs to get a stable device ID:

**Android:**
```kotlin
val deviceId = Settings.Secure.getString(
  context.contentResolver,
  Settings.Secure.ANDROID_ID
)
```

**iOS/macOS:**
```swift
let deviceId = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
```

**Web:**
```typescript
const getDeviceId = () => {
  let deviceId = localStorage.getItem('device_id')
  if (!deviceId) {
    deviceId = 'web_' + UUID.v4()
    localStorage.setItem('device_id', deviceId)
  }
  return deviceId
}
```

## GDPR Compliance

This system maintains GDPR compliance:

**Deleted Data (when user deletes account):**
- Personal data (name, email, phone) ❌
- Messages, calls, contacts ❌
- Device information ❌

**Retained Data (legitimate business purpose - billing):**
- Device ID (no PII) ✅
- Subscription tier and dates ✅
- Account creation/deletion timestamps ✅
- Complete audit trail ✅

Users can request deletion of subscription_accounts via support if needed.

## Troubleshooting

**Q: Why is deviceId sometimes null?**
A: Device IDs must be captured at signup. If not implemented, check integration points.

**Q: Can users bypass device tracking?**
A: No - tracking happens server-side in Cloud Functions, not on client.

**Q: What if user signs in on multiple devices?**
A: Each device gets its own subscription_accounts entry. currentUid will be the most recent UID on that device.

**Q: How to migrate existing users?**
A: Cloud Function job to scan existing subscription_records and create subscription_accounts entries based on device (if available in user data).

## References

- **Database Rules:** `database.rules.json` (subscription_accounts section)
- **Helper Functions:** `web/lib/firebase.ts` (trackSubscriptionByDevice, etc.)
- **Previous Architecture:** SUBSCRIPTION_RECORDS_ARCHITECTURE.md (still valid, now with device layer)
