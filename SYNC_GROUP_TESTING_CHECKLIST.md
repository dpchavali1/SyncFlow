# Sync Group Testing & Integration Checklist

## Pre-Deployment Checklist

### Firebase Setup
- [ ] Deploy database rules: `firebase deploy --only database`
- [ ] Deploy Cloud Functions: `firebase deploy --only functions`
- [ ] Verify rules in Firebase Console
- [ ] Test functions manually in Cloud Functions dashboard

### Code Compilation
- [ ] Android: `./gradlew build` - No errors
- [ ] macOS: `xcodebuild build` - No errors
- [ ] Web: `npm run build` - No errors
- [ ] No TypeScript errors in web app
- [ ] No Kotlin compilation errors in Android

### Dependency Installation
- [ ] Android: SyncGroupManager.kt in correct package
- [ ] macOS: SyncGroupManager.swift imported in FirebaseService
- [ ] Web: jsQR installed (`npm install jsqr`)
- [ ] Web: QR code library installed (`npm install qrcode.react`)

---

## Unit Test Checklist

### Android SyncGroupManager Tests

```kotlin
// Location: app/src/test/java/com/phoneintegration/app/sync/SyncGroupManagerTest.kt

class SyncGroupManagerTest {
    // TODO: Implement tests

    // Test device ID is stable across reinstalls
    fun testStableDeviceId() { }

    // Test join sync group validates device limit
    fun testJoinValidatesLimit() { }

    // Test recovery finds correct group
    fun testRecoverySyncsGroup() { }

    // Test create sync group initializes correctly
    fun testCreateGroupInitialization() { }
}
```

### macOS SyncGroupManager Tests

```swift
// Location: SyncFlowMac/SyncFlowMacTests/SyncGroupManagerTests.swift

class SyncGroupManagerTests: XCTestCase {
    // TODO: Implement tests

    // Test device ID persistence
    func testDeviceIdPersistence() { }

    // Test sync group creation
    func testCreateSyncGroup() { }

    // Test device limit enforcement
    func testDeviceLimitEnforcement() { }
}
```

### Web Firebase Functions Tests

```typescript
// Location: functions/src/sync-group.test.ts

describe('Sync Group Cloud Functions', () => {
  // TODO: Implement tests

  it('should update sync group plan', async () => {})
  it('should enforce device limits', async () => {})
  it('should track history', async () => {})
  it('should handle authorization', async () => {})
})
```

---

## Integration Testing

### Test 1: Android Device Registration

**Steps:**
1. Uninstall SyncFlow app if installed
2. Install app from build
3. Launch app
4. Complete pairing flow
5. Check Firebase: `syncGroups/{syncGroupId}/devices/`

**Expected:**
- [ ] Device registered in sync group
- [ ] syncGroupId stored in SharedPreferences
- [ ] Device count = 1
- [ ] Status = "active"

**Console Output:**
```
D/UnifiedIdentityManager: Redeeming pairing token...
D/UnifiedIdentityManager: Recovered existing sync group: sync_abc123
D/UnifiedIdentityManager: Device registered successfully
```

### Test 2: macOS Pairing & QR Code

**Steps:**
1. Launch macOS app first time
2. Check if sync group created
3. Verify QR code displays
4. Scan QR code with phone camera

**Expected:**
- [ ] QR code generates without errors
- [ ] QR code size is readable (256x256px minimum)
- [ ] QR code contains sync group ID
- [ ] User sees "Paired ✅" message

**Verification:**
```bash
# Check Firebase for group
firebase database:get /syncGroups/sync_abc123
```

### Test 3: Multi-Device Pairing

**Steps:**
1. Launch macOS app (creates group)
2. Scan QR on Android (joins group)
3. Scan QR on Web (joins group)
4. Verify all 3 devices in group

**Expected:**
- [ ] Android shows: "3/3 devices used"
- [ ] Web shows: "3/3 devices used"
- [ ] Firebase shows all 3 deviceIds in devices/
- [ ] All devices see same SMS/MMS

**Console Check:**
```
// Android logcat
D/SyncGroupManager: Successfully joined sync group

// macOS console
Sync Group ID: sync_abc123

// Web console
Sync Group ID: sync_abc123
```

### Test 4: Device Limit Enforcement

**Steps:**
1. Pair macOS (1/3)
2. Pair Android (2/3)
3. Pair Web (3/3)
4. Try to pair 4th device

**Expected:**
- [ ] 4th device shows: "Device limit reached: 3/3"
- [ ] 4th device NOT added to Firebase
- [ ] Error message displayed to user
- [ ] Suggestion to upgrade to Pro

**Test Code:**
```typescript
// Web: Try to join when limit reached
const result = await joinSyncGroup(groupId, 'web')
console.log(result.error) // Should contain "Device limit reached"
```

### Test 5: Sync Group Recovery on Reinstall

**Steps:**
1. Pair Android device
2. Get syncGroupId from Firebase
3. Uninstall app
4. Reinstall app
5. Check if group recovered

**Expected:**
- [ ] syncGroupId automatically recovered
- [ ] No manual pairing needed
- [ ] Device still in group
- [ ] SMS/MMS still synced

**Verify:**
```bash
adb shell am force-stop com.phoneintegration.app
adb uninstall com.phoneintegration.app
# Reinstall via Android Studio
# Launch app
# Check: Should see same sync group
```

### Test 6: Plan Upgrade

**Steps:**
1. Create sync group (free tier, 3 device limit)
2. Add 3 devices (1/3, 2/3, 3/3)
3. Upgrade to Pro in admin dashboard
4. Try to add 4th device

**Expected:**
- [ ] Plan changes to "monthly"
- [ ] deviceLimit changes to 999
- [ ] 4th device can now join
- [ ] History shows: "plan_updated"

**Admin Dashboard:**
```
Sync Groups → Select group → Upgrade to Pro
Expected: Device limit increases to unlimited
```

### Test 7: Device Removal

**Steps:**
1. Have 3 devices in group
2. Remove one device from admin dashboard
3. Verify removed device sees error

**Expected:**
- [ ] Device removed from Firebase
- [ ] Device count decreases (3 → 2)
- [ ] Removed device can no longer sync
- [ ] History shows: "device_removed"

### Test 8: Subscription Per Sync Group

**Steps:**
1. Pair 3 devices to one group
2. Assign plan: "monthly"
3. All 3 devices check subscription

**Expected:**
- [ ] All devices see: plan = "monthly"
- [ ] All devices see: deviceLimit = 999 (if pro)
- [ ] All devices see: deviceCount = 3
- [ ] Subscription shared across devices

**Verify in App:**
```kotlin
// Android
val info = syncGroupManager.getSyncGroupInfo()
println("Plan: ${info.plan}")  // Should be "monthly"

// macOS
syncGroupManager.getSyncGroupInfo { result in
    print("Plan: \(result.plan)")  // Should be "monthly"
}
```

---

## End-to-End Scenarios

### Scenario 1: Fresh User, 2 Devices

**Flow:**
1. Download macOS app → Creates sync group
2. Download Android app → Scans QR → Joins group
3. Both devices should see shared SMS/MMS

**Checklist:**
- [ ] macOS generates QR code
- [ ] Android scans QR successfully
- [ ] Both in same sync group
- [ ] Can send/receive messages from either device
- [ ] Message counts match

### Scenario 2: Upgrade from Free to Pro

**Flow:**
1. User has 3 devices (free tier limit reached)
2. Admin upgrades to Pro
3. User should be able to add 4th device

**Checklist:**
- [ ] Plan shows "monthly" in admin
- [ ] Device limit changes to 999
- [ ] 4th device can join without error
- [ ] All 4 devices sync properly

### Scenario 3: Uninstall and Reinstall

**Flow:**
1. Android has 3-device group
2. User uninstalls app
3. Reinstalls app
4. App should auto-recover group

**Checklist:**
- [ ] No manual pairing required
- [ ] Same sync group ID
- [ ] Device shows in group
- [ ] Devices can still sync

### Scenario 4: Device Removal and Replacement

**Flow:**
1. User has macOS (old)
2. Gets new macOS
3. Scans QR from Android
4. Old macOS still in group

**Checklist:**
- [ ] New macOS joins same group
- [ ] Group now has both machines
- [ ] Admin can manually remove old one
- [ ] Device count stays consistent

---

## Performance Testing

### Load Testing

- [ ] Create 100 sync groups
- [ ] Add 10 devices to each group
- [ ] List groups takes < 2 seconds
- [ ] Device list loads < 1 second

### Stress Testing

- [ ] Rapid join/leave: 50 devices
- [ ] Concurrent SMS from 10 devices
- [ ] Plan upgrade under load
- [ ] Device removal under load

---

## Security Testing

### Authorization

- [ ] Non-admin cannot list all groups
- [ ] Users can only remove their own devices
- [ ] Admin can remove any device
- [ ] Only admin can change plans

**Test:**
```typescript
// Try to list groups as non-admin
const listGroups = httpsCallable(functions, 'listSyncGroups')
await listGroups({})
// Should fail with permission-denied
```

### Data Validation

- [ ] Invalid plan rejected
- [ ] Invalid device type rejected
- [ ] Invalid timestamps rejected
- [ ] Malicious data sanitized

---

## UI/UX Testing

### macOS Pairing View

- [ ] QR code renders correctly
- [ ] Text is readable
- [ ] Error messages are clear
- [ ] Loading state is visible
- [ ] Works in dark mode

### Android Pairing

- [ ] Device limit message is clear
- [ ] Error messages are helpful
- [ ] UI doesn't crash on error
- [ ] Back button works

### Web QR Scanner

- [ ] Camera permission prompt works
- [ ] QR scanning is responsive
- [ ] Error messages are helpful
- [ ] Mobile camera works correctly
- [ ] Fallback manual input works

### Admin Dashboard

- [ ] Sync groups list loads
- [ ] Filtering works (free/premium)
- [ ] Upgrade button works
- [ ] Device removal confirmation works
- [ ] History displays correctly

---

## Deployment Checklist

### Pre-Production

- [ ] All tests passing
- [ ] No console errors in any app
- [ ] Firebase rules deployed
- [ ] Cloud Functions deployed
- [ ] Admin pages tested
- [ ] Documentation complete

### Production Deploy Steps

1. **Firebase Rules**
   ```bash
   firebase deploy --only database
   # Wait for deployment to complete
   ```

2. **Cloud Functions**
   ```bash
   firebase deploy --only functions
   # Wait for deployment to complete
   ```

3. **Web App**
   ```bash
   npm run build
   vercel deploy --prod
   # Or your hosting provider
   ```

4. **Android App**
   - [ ] Bump version code
   - [ ] Build release APK
   - [ ] Upload to Play Store

5. **macOS App**
   - [ ] Bump version
   - [ ] Code sign and notarize
   - [ ] Upload to TestFlight

6. **Verify Post-Deployment**
   - [ ] Create new sync group
   - [ ] Pair 2 devices
   - [ ] Test message syncing
   - [ ] Test device limit
   - [ ] Test plan upgrade

---

## Monitoring Checklist

### Firebase Console

- [ ] Monitor sync group creation rate
- [ ] Track device join success rate
- [ ] Watch for Cloud Function errors
- [ ] Monitor database size growth

### Application Monitoring

- [ ] Set up crash reporting
- [ ] Track user pairing success
- [ ] Monitor sync latency
- [ ] Track feature usage

---

## Known Issues & Workarounds

| Issue | Workaround | Status |
|-------|-----------|--------|
| QR code not scanning | Increase lighting, clean camera | Known |
| Device not recovering on reinstall | Manual QR scan | Acceptable |
| Sync lag > 5 seconds | Check network connection | Expected |

---

## Sign-Off

- [ ] All tests passed
- [ ] Security review complete
- [ ] Performance acceptable
- [ ] Ready for production
- [ ] Documentation complete

**Tested By:** ________________
**Date:** ________________
**Build Version:** ________________
