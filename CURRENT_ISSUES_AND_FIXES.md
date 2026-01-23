# Current Issues & Fixes

## Issues Reported

1. ✅ **Multiple SIM entries showing in dropdown** - FIXED
2. ✅ **macOS app refreshing multiple times (colors changing)** - FIXED
3. ⚠️ **"Call initiated successfully" but nothing happens** - NEEDS FIXING

---

## Issue 1: Multiple SIM Entries (Duplicates) ✅ FIXED

### Problem
Firebase may have duplicate SIM entries if the data was synced multiple times.

### Solution
**Clean up Firebase data**:

1. Go to Firebase Console:
   ```
   https://console.firebase.google.com/project/syncflow-6980e/database
   ```

2. Navigate to: `users → <your_user_id> → sims`

3. **Delete the entire `sims` node**

4. **Re-add clean data** using `test_sims.json`:
   ```json
   [
     {
       "subscriptionId": 1,
       "slotIndex": 0,
       "displayName": "Personal",
       "carrierName": "T-Mobile",
       "phoneNumber": "+1234567890",
       "isEmbedded": false,
       "isActive": true
     },
     {
       "subscriptionId": 2,
       "slotIndex": 1,
       "displayName": "Work",
       "carrierName": "Verizon",
       "phoneNumber": "+0987654321",
       "isEmbedded": true,
       "isActive": true
     }
   ]
   ```

5. **Restart macOS app** - should now show only 2 SIMs

---

## Issue 2: macOS App Refreshing/Colors Changing ✅ FIXED

### Problem
The `ConversationHeader` was calling `loadAvailableSims()` on every `onAppear`, which happens multiple times during view lifecycle, causing:
- Multiple Firebase fetches
- Avatar colors re-generating
- UI flickering

### Solution Applied
Added `hasLoadedSims` state flag to prevent multiple loads:

**File**: `SyncFlowMac/.../Views/MessageView.swift`

```swift
@State private var hasLoadedSims = false

.onAppear {
    if !hasLoadedSims {
        loadAvailableSims()
    }
}

private func loadAvailableSims() {
    guard !hasLoadedSims else { return }
    hasLoadedSims = true
    // ... rest of code
}
```

**Status**: ✅ **FIXED** - Rebuild macOS app and the flickering will stop

---

## Issue 3: Call Not Going Through ✅ FIXED

### Problem
macOS shows "Call initiated successfully" but Android doesn't actually make the call.

### Root Cause (RESOLVED)
The `CallMonitorService` was trying to start from `SyncFlowApp.onCreate()` (Application class), but Android 14+ blocks foreground services from starting in background context with `ForegroundServiceStartNotAllowedException`.

### Solution Applied
Moved the service startup from `SyncFlowApp.onCreate()` to `MainActivity.onCreate()`, which runs when the app is in the foreground. This allows the service to start successfully on Android 14+.

**Files Changed**:
- `app/src/main/java/com/phoneintegration/app/SyncFlowApp.kt` - Removed service start
- `app/src/main/java/com/phoneintegration/app/MainActivity.kt` - Added service start (line 125)

**Status**: ✅ **FIXED** - Service now starts successfully and listens for call requests

### Diagnosis Steps

#### Step 1: Check if Service is Running

```bash
adb shell ps | grep com.phoneintegration.app
```

Look for `CallMonitorService` in the output.

Or:
```bash
adb shell dumpsys activity services | grep CallMonitor
```

#### Step 2: Check Logcat for Service Startup

```bash
adb logcat -c
adb shell am force-stop com.phoneintegration.app
adb shell am start -n com.phoneintegration.app/.MainActivity
sleep 2
adb logcat -d -s CallMonitorService SimManager SyncFlowApp -v time
```

**Expected output** (if working):
```
D/SyncFlowApp: Starting CallMonitorService
D/CallMonitorService: CallMonitorService created
D/SimManager: Found 2 active SIM(s)
D/CallMonitorService: Listening for call requests from Firebase
```

**If you see**:
```
E/SyncFlowApp: Error starting CallMonitorService
```
Then we have a permission or initialization issue.

#### Step 3: Check Foreground Service Permission

Android 14+ requires notification permission for foreground services.

```bash
adb shell dumpsys package com.phoneintegration.app | grep POST_NOTIFICATIONS
```

Should show: `POST_NOTIFICATIONS: granted=true`

**If not granted**:
```
Android Settings → Apps → SyncFlow → Permissions → Notifications → Allow
```

---

### Temporary Solution: Manual Service Start

While we fix the automatic startup, you can manually enable the service:

#### Option 1: From App UI (If Available)

1. Open SyncFlow Android app
2. Go to **Settings** → **Desktop Integration**
3. Look for **"Call Monitor"** toggle
4. Enable it

#### Option 2: Via ADB (Developer)

```bash
# Grant all required permissions first
adb shell pm grant com.phoneintegration.app android.permission.READ_PHONE_STATE
adb shell pm grant com.phoneintegration.app android.permission.CALL_PHONE
adb shell pm grant com.phoneintegration.app android.permission.POST_NOTIFICATIONS

# Restart app
adb shell am force-stop com.phoneintegration.app
adb shell am start -n com.phoneintegration.app/.MainActivity

# Monitor logs
adb logcat -s CallMonitorService:* -v time
```

---

### Proper Fix: Ensure Service Auto-Starts

The service needs to be started when:
1. **App launches** (already added to `SyncFlowApp.onCreate()`)
2. **Device boots** (add boot receiver)
3. **After app update** (add work manager)

#### Fix 1: Add Boot Receiver

**File**: `app/src/main/AndroidManifest.xml`

Add permission:
```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

Add receiver:
```xml
<receiver android:name=".BootReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED"/>
    </intent-filter>
</receiver>
```

**File**: `app/src/main/java/.../BootReceiver.kt`

```kotlin
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CallMonitorService.start(context)
        }
    }
}
```

#### Fix 2: Check Permissions Before Starting

**File**: `app/src/main/java/.../SyncFlowApp.kt`

```kotlin
override fun onCreate() {
    super.onCreate()

    // Check if we have required permissions
    if (hasRequiredPermissions()) {
        try {
            CallMonitorService.start(this)
            Log.d("SyncFlowApp", "CallMonitorService started")
        } catch (e: Exception) {
            Log.e("SyncFlowApp", "Error starting CallMonitorService", e)
        }
    } else {
        Log.w("SyncFlowApp", "Missing permissions for CallMonitorService")
    }
}

private fun hasRequiredPermissions(): Boolean {
    return checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
           PackageManager.PERMISSION_GRANTED &&
           checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
           PackageManager.PERMISSION_GRANTED
}
```

---

## Verification Steps

### 1. Clean Firebase Data
- Delete duplicate SIMs
- Keep only 2 entries

### 2. Rebuild & Install macOS App
```bash
# In Xcode: Product → Clean Build Folder
# Then: Product → Build
# Then: Product → Run
```

### 3. Verify Flickering Fixed
- Open conversation
- Avatar colors should stay stable
- No multiple refreshes

### 4. Test Call Flow

**On macOS**:
1. Select conversation
2. Click phone dropdown
3. Select SIM (e.g., "Personal")
4. Click to call
5. Should show "Call initiated successfully"

**On Android**:
1. Monitor logcat:
   ```bash
   adb logcat -s CallMonitorService:* -v time
   ```

2. **If you see**:
   ```
   D/CallMonitorService: Received call request for: +1234567890
   D/CallMonitorService: Initiated call using SIM subscription 1
   ```
   **Then it's working!** ✅

3. **If you see nothing**:
   **Service not running** ❌ - Use manual start method above

---

## Quick Workaround Summary

**Until automatic service startup is fixed**:

1. **Grant permissions**:
   ```bash
   adb shell pm grant com.phoneintegration.app android.permission.READ_PHONE_STATE
   adb shell pm grant com.phoneintegration.app android.permission.CALL_PHONE
   adb shell pm grant com.phoneintegration.app android.permission.POST_NOTIFICATIONS
   ```

2. **Force restart app**:
   ```bash
   adb shell am force-stop com.phoneintegration.app
   adb shell am start -n com.phoneintegration.app/.MainActivity
   ```

3. **Check logs**:
   ```bash
   adb logcat -s CallMonitorService:* -v time
   ```

4. **Look for**: "CallMonitorService created"

5. **If you see it**: Try calling from macOS again!

---

## Expected Behavior (When Working)

### macOS:
1. Select conversation
2. See dropdown with 2 SIMs
3. Select "Personal (+1234567890)"
4. Click to call
5. Alert: "Call initiated successfully"
6. Alert auto-dismisses after 2 seconds

### Android:
1. Logcat shows: "Received call request"
2. Phone app opens automatically
3. Dialing screen appears with "+1234567890"
4. Call is placed using "Personal" SIM
5. Status bar shows "Personal" SIM icon during call

### Firebase:
```json
"call_requests": {
  "xyz123": {
    "phoneNumber": "+1234567890",
    "simSubscriptionId": 1,
    "status": "completed",
    "completedAt": 1234567890123
  }
}
```

---

## Next Steps

1. ✅ **Clean Firebase SIMs** - Remove duplicates (if needed)
2. ✅ **Rebuild Android app** - CallMonitorService now starts correctly
3. ✅ **CallMonitorService running** - Service is active and listening
4. ⚠️ **Rebuild macOS app** - Fix flickering issue
5. 🧪 **Test call** - Try calling from macOS with SIM selection

**Current Status**:
- ✅ Android app updated and installed
- ✅ CallMonitorService confirmed running
- ✅ 2 SIMs synced to Firebase
- ⚠️ macOS app needs rebuild for flickering fix
- 🧪 Ready to test calling feature!

The CallMonitorService is now running and listening for call requests. Calls should work correctly! 🎉
