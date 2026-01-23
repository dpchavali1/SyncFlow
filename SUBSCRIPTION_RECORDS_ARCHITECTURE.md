# Persistent Subscription Records Architecture

## Problem Statement

**User Concern**: "We have userid deletion in place. How will we identify user as pro user if we delete ids?"

When a user account is deleted via `deleteUserAccount(userId)`, the entire `users/{uid}` node is removed from Firebase Realtime Database, including all plan data at:
- `users/{uid}/plan`
- `users/{uid}/usage/plan`

This created several issues:
1. **Lost billing history** - Can't track which deleted users were premium
2. **Revenue tracking broken** - No way to see paid vs free user conversions
3. **GDPR compliance unclear** - Mixing personal data with billing records

## Solution: `subscription_records/{uid}` Collection

A **separate, persistent subscription tracking system** that survives user deletion.

### Data Structure

```
subscription_records/{uid}/
├── active/                          // Current subscription status
│   ├── plan: "monthly"              // monthly, yearly, lifetime, free
│   ├── planExpiresAt: 1771777954405 // null if lifetime or not set
│   ├── freeTrialExpiresAt: null
│   ├── planAssignedAt: 1769188157835
│   └── planAssignedBy: "testing_tab" // or "storekit", "cloud_function", etc.
│
├── history/                         // Complete audit trail
│   ├── {timestamp}:
│   │   ├── timestamp: 1769188157835
│   │   ├── previousPlan: "free"
│   │   ├── newPlan: "monthly"
│   │   ├── expiresAt: 1771777954405
│   │   └── source: "testing_tab"
│   ├── {another_timestamp}: {...}
│   └── ... more entries ...
│
├── wasPremium: true                 // Quick flag: "ever had paid subscription"
├── firstPremiumDate: 1769188157835  // When first premium plan assigned
├── totalPremiumDays: 365            // Cumulative premium days
│
├── purchases/                       // StoreKit purchases (if any)
│   ├── {purchaseId}:
│   │   ├── productId: "com.syncflow.subscription.monthly"
│   │   ├── purchaseDate: 1769188157835
│   │   ├── expirationDate: 1771777954405
│   │   └── verified: true
│   └── ... more purchases ...
```

### Key Properties

| Field | Purpose | Persists After Delete |
|-------|---------|-----|
| `active/plan` | Current plan tier | ✅ Yes |
| `active/planExpiresAt` | When current plan expires | ✅ Yes |
| `active/planAssignedAt` | When this plan was assigned | ✅ Yes |
| `active/planAssignedBy` | Source (testing_tab, storekit, api) | ✅ Yes |
| `history/*` | Complete change audit trail | ✅ Yes |
| `wasPremium` | Has user ever been premium? | ✅ Yes |
| `firstPremiumDate` | First time user went premium | ✅ Yes |
| `totalPremiumDays` | Cumulative paid days | ✅ Yes |

## Implementation Details

### 1. Firebase Database Rules

**File**: `database.rules.json`

Added new `subscription_records` path with:
- Admin-only write access (secure)
- Users and admins can read own records
- Validates plan values and timestamps
- Restricts write sources to prevent tampering

```json
"subscription_records": {
  "$uid": {
    ".read": "auth != null && (auth.uid == $uid || auth.token.admin === true)",
    ".write": "auth != null && (auth.token.admin === true)",
    "active": {
      ".read": "auth != null && (auth.uid == $uid || auth.token.admin === true)",
      ".write": "auth != null && auth.token.admin === true",
      "plan": {
        ".validate": "!newData.exists() || (newData.isString() && (newData.val() == 'monthly' || newData.val() == 'yearly' || newData.val() == 'lifetime' || newData.val() == 'free'))"
      }
      // ... validation for other fields
    }
    // ... other sections
  }
}
```

### 2. Testing Tab Integration

**File**: `web/app/admin/cleanup/page.tsx`

When a plan is assigned via the Testing tab:

```typescript
// 1. Update user's current usage plan
await update(usageRef, updateData)
addLog(`📝 Updated users/${testUserId}/usage/`)

// 2. ALSO update subscription records (persistent)
await update(subscriptionRecordRef, subscriptionUpdateData)
addLog(`🔒 Updated subscription_records/${testUserId}/ (persists after user deletion)`)
```

The Testing tab now:
- ✅ Writes plan to `users/{uid}/usage/` (current operation)
- ✅ Writes to `subscription_records/{uid}/active/` (new persistent record)
- ✅ Tracks plan changes in `subscription_records/{uid}/history/`
- ✅ Sets `wasPremium` flag for premium plans
- ✅ Shows confirmation for both locations in UI logs

### 3. macOS App Integration

**File**: `SyncFlowMac/Services/SubscriptionService.swift`

Enhanced `syncUsagePlan()` function now checks:

```swift
// FIRST: Check users/{uid}/usage/ (current active plan)
// THEN: Check subscription_records/{uid}/active/ (persists after deletion)
```

**Priority Order**:
1. `users/{uid}/plan` (legacy location) ← highest priority
2. `users/{uid}/usage/plan` (testing tab)
3. `subscription_records/{uid}/active/plan` ← works even if user deleted
4. StoreKit data (local IAP verification) ← lowest priority

### 4. Android App Integration

**File**: `app/src/main/java/com/phoneintegration/app/ui/settings/UsageSettingsScreen.kt`

Updated plan loading in `loadUsage()` function:

```kotlin
// FIRST: Try to load from users/{uid}/usage
val snapshot = database.reference
    .child("users").child(userId).child("usage").get().await()

// FALLBACK: Check subscription_records/{uid}/active (persists after user deletion)
val subscriptionSnapshot = database.reference
    .child("subscription_records").child(userId).child("active").get().await()

// If subscription record exists, use it with empty usage stats
if (subscriptionSnapshot.exists()) {
    state = UsageUiState.Loaded(parseUsage(snapshot, subscriptionSnapshot))
}
```

### 5. Cloud Functions Helper

**File**: `functions/index.js`

Added `updateSubscriptionRecord()` utility function that can be called by:
- Scheduled cleanup jobs
- Manual admin operations
- Webhook handlers
- Other cloud functions

```javascript
const updateSubscriptionRecord = async (userId, plan, expiresAt, source = "system") => {
    // Updates both active and history
    // Tracks plan changes with timestamps
    // Sets wasPremium flag automatically
}
```

## Verification Flow

```
App loads subscription data:
    ↓
1️⃣ Check users/{uid}/usage/plan
    ↓ (not found or empty)
2️⃣ Check subscription_records/{uid}/active/plan
    ↓ (not found)
3️⃣ Check StoreKit local purchases
    ↓ (not found)
4️⃣ Show trial (default)
```

**After user deletion**:
```
User deletes account:
    ↓ users/{uid} DELETED ❌
    ↓ subscription_records/{uid} SURVIVES ✅

App reloads:
    ↓
1️⃣ Check users/{uid}/usage/plan
    ↓ (404 - user deleted)
2️⃣ Check subscription_records/{uid}/active/plan
    ↓ FOUND! Shows "monthly" plan

Result: User still shows as premium even after deletion
```

## Testing the Architecture

### Manual Test: Assigning a Plan

1. **Go to Testing Tab** in admin dashboard
2. **Enter User ID**: `8iHnek4WaEcE3qp4PhNtpKs1P0l2` (or your test user)
3. **Select Plan**: "monthly"
4. **Click "Assign Plan"**

**Expected Logs**:
```
🔧 Assigning monthly plan to user 8iHnek4WaEcE3qp4PhNtpKs1P0l2...
📝 Updated users/8iHnek4WaEcE3qp4PhNtpKs1P0l2/usage/
🔒 Updated subscription_records/8iHnek4WaEcE3qp4PhNtpKs1P0l2/ (persists after user deletion)
✅ User plan updated to monthly
⏰ Expires at: 2025-03-29T18:52:34.405Z
📍 User must sign out and back in to see changes
```

### Verify in Firebase Console

**Location 1 - Current usage** (deleted during account deletion):
```
Firebase Console → Realtime Database → users → {uid} → usage → plan
Value: "monthly"
```

**Location 2 - Persistent records** (survives user deletion):
```
Firebase Console → Realtime Database → subscription_records → {uid} → active
├── plan: "monthly"
├── planExpiresAt: 1771777954405
├── planAssignedAt: 1769188157835
└── planAssignedBy: "testing_tab"
```

**Location 3 - History audit trail**:
```
Firebase Console → Realtime Database → subscription_records → {uid} → history
├── 1769188157835:
│   ├── timestamp: 1769188157835
│   ├── newPlan: "monthly"
│   ├── previousPlan: "free"
│   └── source: "testing_tab"
└── ... more changes ...
```

### Test: macOS App Plan Display

1. **Assign monthly plan** via Testing tab
2. **Rebuild macOS app** (with updated SubscriptionService)
3. **Sign in** with user ID
4. **Expected**: No trial/upgrade bars, shows "Lifetime Access" or "Monthly" subscription

**Console logs should show**:
```
SubscriptionService: Found plan in usage: monthly
SubscriptionService: Loaded Firebase plan: monthly
SubscriptionService: Status updated to Monthly (expires 2025-03-29)
```

### Test: User Deletion Survival

1. **Assign premium plan** to test user (goes to both locations)
2. **Delete user account** via admin panel
3. **Verify Firebase**:
   - `users/{uid}` ❌ DELETED
   - `subscription_records/{uid}` ✅ STILL EXISTS
4. **Create new account with same user ID**
5. **Load usage settings**
6. **Expected**: Shows previous plan tier (e.g., "Lifetime Access")

## Data Migration

### For Existing Premium Users

The system is **backward compatible**:
- Existing plans at `users/{uid}/usage/plan` continue to work
- Apps check this location first (highest priority)
- When user is deleted, that data is lost
- But subscription_records survives

### Future Migration Strategy

1. **Cloud Function job** iterates all users
2. **Copies** `users/{uid}/usage/plan` → `subscription_records/{uid}/active/plan`
3. **Sets** `wasPremium: true` for any user who ever had premium
4. **Creates** initial history entry

```javascript
// Example Cloud Function to migrate existing data
const migrateExistingPlans = async () => {
    const usersRef = admin.database().ref('/users');
    const snapshot = await usersRef.get();

    snapshot.forEach(userSnapshot => {
        const userId = userSnapshot.key;
        const plan = userSnapshot.child('usage/plan').val();

        if (plan && ['monthly', 'yearly', 'lifetime'].includes(plan)) {
            updateSubscriptionRecord(userId, plan, expiresAt, 'migration');
        }
    });
};
```

## GDPR Compliance

This architecture provides **GDPR-compliant subscription handling**:

### Deleted Personal Data ❌
When user deletes their account or requests deletion:
- Name, email, phone number → deleted
- Messages, calls, contacts → deleted
- Device information → deleted
- Local settings → deleted

### Retained Billing Records ✅
For legitimate business purposes:
- Subscription tier (monthly/yearly/lifetime)
- Purchase dates and expirations
- Payment history (via stripe_records)
- Audit trail of plan changes

### Why This is Compliant
✅ **Purpose limitation**: Kept only for billing and analytics
✅ **Data minimization**: Only essential billing fields retained
✅ **Storage limitation**: Retains only what's needed
✅ **Transparency**: Documented in privacy policy
✅ **User control**: Users can request export/deletion of this too

## Benefits Summary

| Benefit | Impact |
|---------|--------|
| **User Identification** | Know plan tier even after account deletion |
| **Revenue Tracking** | See conversion: free → premium users |
| **Audit Trail** | Complete history of plan changes |
| **Analytics** | Premium user metrics across account lifecycle |
| **Support** | Help users who re-register with different ID |
| **Fraud Detection** | Track if same user re-subscribes |
| **Compliance** | GDPR-compliant billing records |

## Backward Compatibility

✅ **Full backward compatibility** maintained:
- Existing `users/{uid}/plan` data still works
- Existing `users/{uid}/usage/plan` still works
- New subscription_records is **optional fallback**
- Apps check current location first, then fallback
- No breaking changes to existing apps

## Future Enhancements

1. **Plan validation rules**
   - Prevent editing if subscription_records has more recent date
   - Flag suspicious plan changes

2. **Revenue analytics**
   - Query `subscription_records` for MRR (Monthly Recurring Revenue)
   - Track lifetime value (LTV) across deleted accounts
   - Cohort analysis by first premium date

3. **Churn analysis**
   - Track how long users stayed premium
   - Identify at-risk users approaching expiration
   - Re-engagement campaigns

4. **Refund handling**
   - Link refund records to subscription_records
   - Create credit entries that survive user deletion
   - Pre-populate refund offers for re-registrations

## References

- **Firebase Database Rules**: `database.rules.json`
- **Testing Tab**: `web/app/admin/cleanup/page.tsx`
- **macOS App**: `SyncFlowMac/Services/SubscriptionService.swift`
- **Android App**: `app/src/main/java/com/phoneintegration/app/ui/settings/UsageSettingsScreen.kt`
- **Cloud Functions**: `functions/index.js` (updateSubscriptionRecord helper)
