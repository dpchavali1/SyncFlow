# Multi-SIM Support - Troubleshooting & Workaround

## Issue: SIM Selector Not Showing on macOS

### Root Cause

The `CallMonitorService` needs to be running to detect and sync SIM information to Firebase. However, there are several possible reasons why SIMs might not be detected:

1. **Service Not Running**: CallMonitorService hasn't started yet
2. **Permissions Missing**: Android doesn't have READ_PHONE_STATE permission
3. **API Level**: Device is running older Android version
4. **Timing**: SIMs haven't synced to Firebase yet

---

## ✅ Quick Workaround: Manual SIM Data (For Testing macOS UI)

While we fix the automatic detection, you can manually add SIM data to Firebase to test the macOS SIM selector:

### Option 1: Firebase Console (Recommended)

1. **Go to Firebase Console**:
   ```
   https://console.firebase.google.com/project/syncflow-6980e/database
   ```

2. **Navigate to your user**:
   ```
   users → <your_user_id> → sims
   ```

3. **Add SIM data**:
   - Click the **"+"** button next to `sims`
   - Select **"Import JSON"**
   - Paste the contents of `test_sims.json` (see below)
   - Click **"Import"**

4. **Refresh macOS app**:
   - Close conversation (if open)
   - Re-open conversation
   - Phone icon should now be a **dropdown menu**

### Option 2: Firebase CLI

```bash
# Get your user ID from Firebase Console first
firebase database:set /users/YOUR_USER_ID/sims test_sims.json
```

### test_sims.json Content:

```json
[
  {
    "subscriptionId": 1,
    "slotIndex": 0,
    "displayName": "Personal",
    "carrierName": "T-Mobile",
    "phoneNumber": "+1234567890",
    "iccId": "89011234567890123456",
    "isEmbedded": false,
    "isActive": true
  },
  {
    "subscriptionId": 2,
    "slotIndex": 1,
    "displayName": "Work",
    "carrierName": "Verizon",
    "phoneNumber": "+0987654321",
    "iccId": "89019876543210987654",
    "isEmbedded": true,
    "isActive": true
  }
]
```

**Replace with your actual SIM details**:
- `displayName`: Your label for the SIM
- `phoneNumber`: Your actual phone number
- `carrierName`: Your carrier (T-Mobile, Verizon, AT&T, etc.)
- `isEmbedded`: `true` for eSIM, `false` for physical SIM

---

## 🔧 Proper Fix: Enable CallMonitorService

### Method 1: From Android App UI

**Note**: This should work after the next app update.

1. Open SyncFlow Android app
2. Go to **Settings** → **Desktop Integration**
3. Enable **"Background Sync"** or **"Call Monitor"**
4. SIMs should sync automatically

### Method 2: Check if Service is Running

```bash
adb shell dumpsys activity services | grep CallMonitorService
```

**Expected output** (if running):
```
* ServiceRecord{...CallMonitorService...}
```

**If not running**, check logcat:
```bash
adb logcat -s CallMonitorService:* SimManager:* -v time
```

### Method 3: Manually Trigger (Dev Only)

From Android device with debugging enabled:

```bash
# Force app restart
adb shell am force-stop com.phoneintegration.app
adb shell am start -n com.phoneintegration.app/.MainActivity

# Check logs
adb logcat -s CallMonitorService:D SimManager:D -v time
```

**Expected logs**:
```
D/CallMonitorService: CallMonitorService created
D/SimManager: Found 2 active SIM(s)
D/SimManager: SIM 0: Personal (+1234567890)
D/SimManager: SIM 1: Work (+0987654321) [eSIM]
D/CallMonitorService: Detected 2 active SIM(s)
D/CallMonitorService: Synced 2 SIM(s) to Firebase
```

---

## 🔍 Debugging Steps

### Step 1: Check Android Permissions

```bash
adb shell dumpsys package com.phoneintegration.app | grep permission
```

**Look for**:
- `android.permission.READ_PHONE_STATE: granted=true`
- `android.permission.READ_PHONE_NUMBERS: granted=true`

**If not granted**, enable in:
```
Android Settings → Apps → SyncFlow → Permissions → Phone → Allow
```

### Step 2: Check Android API Level

```bash
adb shell getprop ro.build.version.sdk
```

**Required**:
- **22+** for multi-SIM detection
- **23+** for specific SIM calling
- **28+** for eSIM detection

### Step 3: Verify Firebase Structure

Go to Firebase Console and check:
```
users/
  └── <your_user_id>/
      ├── sims/  ← Should exist with array
      │   ├── 0: { subscriptionId, displayName, ... }
      │   └── 1: { subscriptionId, displayName, ... }
      └── call_requests/
```

### Step 4: Check macOS Console

In Xcode or Console.app, look for:
```
Loaded X SIM(s)
Error loading SIMs: ...
```

### Step 5: Network Connectivity

Ensure both devices can reach Firebase:
- Android has internet (WiFi/Data)
- macOS has internet
- Firebase is reachable

---

## 📱 Manual SIM Detection (Temporary)

If automatic detection isn't working, you can manually get your SIM info:

### On Android:

1. **Open Phone Dialer**
2. **Dial**: `*#*#4636#*#*`
3. **Select**: "Phone Information"
4. **Look for**:
   - "Subscription ID"
   - "Phone Number"
   - "Carrier"

5. **For eSIM**: Settings → Network & Internet → SIMs
   - Check which is physical vs eSIM

6. **Create JSON** using `test_sims.json` template above
7. **Upload to Firebase** using Option 1

---

## ✅ Verify SIM Selector Working

### Expected macOS Behavior:

**Single SIM**:
```
☎️ (simple button)
```

**Dual SIM**:
```
☎️ ▼ (dropdown menu)
```

Click dropdown:
```
☎️ Personal (+1234567890)
☎️ Work (+0987654321) [eSIM] ✓
```

### Test Flow:

1. **macOS**: Select SIM from dropdown
2. **macOS**: Click to call
3. **Android**: Should dial using selected SIM
4. **Check status bar**: Verify correct SIM icon

---

## 🐛 Known Issues & Workarounds

### Issue 1: "No SIMs detected"

**Symptoms**: `availableSims.count == 0` in macOS

**Causes**:
- Android device has no SIMs (WiFi only tablet)
- Permissions denied
- Service not running

**Workaround**: Use manual SIM data (see above)

### Issue 2: "Phone number not showing"

**Symptoms**: SIM shows as "SIM 1 - T-Mobile" instead of number

**Causes**:
- Carrier doesn't expose number via API
- Permission `READ_PHONE_NUMBERS` not granted

**Workaround**: Shows carrier name instead (acceptable)

### Issue 3: "eSIM not labeled"

**Symptoms**: eSIM doesn't show `[eSIM]` tag

**Causes**:
- Android < 9.0 (API 28)
- `isEmbedded` always false on older versions

**Workaround**: Still works, just no label

### Issue 4: "Wrong SIM used for call"

**Symptoms**: Call made from different SIM than selected

**Causes**:
- Android < 6.0 (always uses default)
- Carrier restrictions
- `TelecomManager` not available

**Fix**: Update Android to 6.0+ if possible

---

## 📊 Expected Firebase Data (Example)

```json
{
  "users": {
    "ABC123USER": {
      "sims": [
        {
          "subscriptionId": 1,
          "slotIndex": 0,
          "displayName": "Personal",
          "carrierName": "T-Mobile",
          "phoneNumber": "+1234567890",
          "iccId": "89011234567890123456",
          "isEmbedded": false,
          "isActive": true
        },
        {
          "subscriptionId": 2,
          "slotIndex": 1,
          "displayName": "Work",
          "carrierName": "Verizon",
          "phoneNumber": "+0987654321",
          "iccId": "89019876543210987654",
          "isEmbedded": true,
          "isActive": true
        }
      ],
      "call_requests": { /* ... */ },
      "messages": { /* ... */ }
    }
  }
}
```

---

## 🚀 Next Steps

1. **Add test SIM data** to Firebase (Option 1 above)
2. **Open macOS app** and navigate to conversation
3. **Verify dropdown appears** with SIM options
4. **Select a SIM** and make test call
5. **Check Android** to see which SIM was used

Once you see the dropdown working, we know the macOS UI is correct, and we just need to fix automatic Android SIM detection.

---

## 📞 Support

If you still don't see the SIM selector after adding test data:

1. **Restart macOS app completely**
2. **Check Firebase Console** for the data
3. **Check macOS Console.app** for errors
4. **Share logs** from Console.app

The SIM selector should appear immediately after adding the test data to Firebase!
