# Multi-SIM Message Filtering

## Overview

This feature adds support for filtering SMS/MMS messages by SIM card on devices with multiple SIM cards (dual-SIM, eSIM + physical SIM, etc.). Users can view messages from all SIMs or filter by a specific SIM card.

## Problem Solved

Previously, the app showed messages from all SIM cards mixed together with no way to distinguish which SIM received each message. For users with dual-SIM phones (personal + work, local + international, etc.), this made it difficult to:
- See messages from only one SIM
- Identify which SIM received a specific message
- Manage messages separately per SIM

## Solution Implemented

### Android App Changes

#### 1. Data Model Update

**File**: `app/src/main/java/com/phoneintegration/app/SmsMessage.kt`

Added `subId` field to track which SIM card each message belongs to:

```kotlin
data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    var contactName: String? = null,
    var category: MessageCategory? = null,
    var otpInfo: OtpInfo? = null,
    val isMms: Boolean = false,
    val mmsAttachments: List<MmsAttachment> = emptyList(),
    val mmsSubject: String? = null,
    val subId: Int? = null  // NEW: Subscription ID (SIM card identifier)
)
```

#### 2. Repository Update

**File**: `app/src/main/java/com/phoneintegration/app/SmsRepository.kt`

- Added `Telephony.Sms.SUBSCRIPTION_ID` to all SMS queries
- Added optional `filterSubId` parameter to `getConversations()` method
- Filters SMS conversations by SIM when specified

**Key changes**:
```kotlin
suspend fun getConversations(filterSubId: Int? = null): List<ConversationInfo> {
    val selection = if (filterSubId != null) {
        "${Telephony.Sms.SUBSCRIPTION_ID} = ?"
    } else {
        null
    }
    val selectionArgs = if (filterSubId != null) {
        arrayOf(filterSubId.toString())
    } else {
        null
    }
    // Query with filter...
}
```

#### 3. ViewModel Update

**File**: `app/src/main/java/com/phoneintegration/app/SmsViewModel.kt`

Added SIM filtering state management:

```kotlin
// SIM filtering support
private val _selectedSimFilter = MutableStateFlow<Int?>(null)
val selectedSimFilter = _selectedSimFilter.asStateFlow()

private val _availableSims = MutableStateFlow<List<SimManager.SimInfo>>(emptyList())
val availableSims = _availableSims.asStateFlow()

fun loadAvailableSims() {
    viewModelScope.launch(Dispatchers.IO) {
        val simManager = SimManager(getApplication())
        val sims = simManager.getActiveSims()
        _availableSims.value = sims
    }
}

fun setSimFilter(subId: Int?) {
    _selectedSimFilter.value = subId
    loadConversations()  // Reload with new filter
}
```

#### 4. UI Update

**File**: `app/src/main/java/com/phoneintegration/app/ui/conversations/ConversationListScreen.kt`

Added SIM filter dropdown in conversation list screen:

**Features**:
- Only shows if device has multiple SIMs
- Displays "All SIMs" option to show messages from all SIMs
- Lists each SIM with name, carrier, and phone number
- Shows checkmark next to selected filter
- Updates conversation list in real-time when filter changes

**UI Example**:
```
SIM: [All SIMs (2) ▼]

Dropdown menu:
├─ All SIMs (2) ✓
├─ ─────────────
├─ Personal
│  T-Mobile • +1234567890
└─ Work
   Verizon • +0987654321
```

#### 5. Firebase Sync Update

**File**: `app/src/main/java/com/phoneintegration/app/desktop/DesktopSyncService.kt`

Updated message sync to include `subId`:

```kotlin
val messageData = mutableMapOf<String, Any?>(
    "id" to message.id,
    "address" to message.address,
    "body" to message.body,
    "date" to message.date,
    "type" to message.type,
    "timestamp" to ServerValue.TIMESTAMP
)

// Add SIM subscription ID if available
message.subId?.let {
    messageData["subId"] = it
}
```

## Usage

### Android App

1. **Viewing All Messages** (Default):
   - Open the app
   - Conversation list shows messages from all SIMs

2. **Filtering by Specific SIM**:
   - On conversation list screen, look for "SIM:" filter (only visible with 2+ SIMs)
   - Tap the filter chip
   - Select desired SIM from dropdown
   - Conversation list updates to show only messages from that SIM

3. **Returning to All SIMs**:
   - Tap the SIM filter chip
   - Select "All SIMs" option

### Web/macOS Apps

Messages synced to Firebase now include `subId` field, allowing web and macOS apps to:
- Display which SIM received each message
- Implement similar filtering functionality
- Show SIM information in message details

## Technical Details

### SIM Identification

Android uses `Telephony.Sms.SUBSCRIPTION_ID` (or `sub_id`) to identify which SIM card handled a message:

- Each active SIM has a unique subscription ID
- Physical SIM cards typically have IDs like 1, 2, etc.
- eSIMs have their own unique IDs
- The ID persists across app restarts
- IDs can change if SIM cards are swapped or reset

### Database Query Performance

The filtering is implemented at the database query level using `ContentResolver.query()` with WHERE clause:

```sql
SELECT * FROM sms WHERE sub_id = ? ORDER BY date DESC
```

This is very efficient because:
- Native SQLite query
- sub_id is indexed in Android's SMS database
- No need to filter in memory
- Minimal performance impact

### Firebase Schema

Messages in Firebase now include:

```json
{
  "messages": {
    "message_id": {
      "id": 12345,
      "address": "+1234567890",
      "body": "Message text",
      "date": 1764730258294,
      "type": 1,
      "subId": 4,
      "contactName": "John Doe",
      "timestamp": 1764730259000
    }
  }
}
```

## Benefits

1. **Better Message Organization**: Users can focus on messages from one SIM at a time

2. **Work/Personal Separation**: Easily separate work and personal messages

3. **International Travel**: Filter by local SIM vs home SIM

4. **Cross-Platform Consistency**: Web and macOS apps can display SIM information

5. **No Performance Impact**: Filtering happens at database level, not in memory

6. **Automatic Detection**: No manual configuration needed - detects all active SIMs

## Compatibility

- **Minimum API Level**: API 22+ (Android 5.1+) - required for multi-SIM support
- **Single SIM Devices**: Filter UI hidden, all messages shown normally
- **Dual SIM Devices**: Filter UI visible, can select either SIM
- **eSIM + Physical SIM**: Both detected and filterable
- **Triple SIM** (rare): All SIMs detected and filterable

## Future Enhancements

Potential improvements for future versions:

1. **Default SIM Filter**: Remember last selected filter across app restarts

2. **Per-Contact SIM Preference**: Remember which SIM to use for each contact

3. **SIM-Specific Statistics**: Show message stats broken down by SIM

4. **Color Coding**: Visual indicator (color/icon) for each SIM in message list

5. **Quick SIM Switch**: Swipe gesture to switch between SIM filters

6. **Web/macOS Filtering**: Implement same filtering UI in web and macOS apps

## Testing

To test the multi-SIM feature:

1. **Check SIM Detection**:
   ```bash
   adb shell content query --uri content://sms --projection sub_id | head -20
   ```
   Look for different `sub_id` values (e.g., 4, 5)

2. **Verify Filter UI**:
   - Open app conversation list
   - Should see "SIM:" filter if multiple SIMs detected
   - Dropdown should list all active SIMs with correct names

3. **Test Filtering**:
   - Select a specific SIM
   - Verify only messages from that SIM appear
   - Switch to "All SIMs"
   - Verify all messages appear again

4. **Check Firebase Sync**:
   - Send/receive test message
   - Check Firebase Console
   - Message should have `subId` field

5. **Test Single SIM Device**:
   - On single SIM device, filter UI should be hidden
   - All messages should show normally

## Troubleshooting

### SIM Filter Not Appearing

**Cause**: Only one SIM detected or SIM detection failed

**Solutions**:
1. Check SIM cards are installed and active
2. Grant READ_PHONE_STATE permission
3. Restart app to re-detect SIMs
4. Check logs: `adb logcat -s SmsViewModel:* SimManager:*`

### Wrong SIM SubID

**Cause**: SIM subscription IDs changed (SIM swapped, device reset)

**Solution**: subId is assigned by Android system, cannot be manually set. If SIM is swapped, it will get a new ID.

### Messages Missing After Filter

**Cause**: Messages actually belong to different SIM

**Solution**: This is expected - filter is working correctly. Select "All SIMs" to see all messages.

### Firebase Missing subId

**Cause**: Old messages synced before this feature was added

**Solution**: Only new messages will have `subId`. Historical messages won't have it unless re-synced.

## Files Modified

1. `app/src/main/java/com/phoneintegration/app/SmsMessage.kt` - Added subId field
2. `app/src/main/java/com/phoneintegration/app/SmsRepository.kt` - Added filtering support
3. `app/src/main/java/com/phoneintegration/app/SmsViewModel.kt` - Added filter state management
4. `app/src/main/java/com/phoneintegration/app/ui/conversations/ConversationListScreen.kt` - Added filter UI
5. `app/src/main/java/com/phoneintegration/app/desktop/DesktopSyncService.kt` - Added subId to Firebase sync

## Summary

Multi-SIM message filtering is now fully implemented in the Android app with:
- ✅ Automatic SIM detection
- ✅ Database-level filtering (efficient)
- ✅ Clean, intuitive UI
- ✅ Firebase sync support
- ✅ Backward compatible
- ✅ Zero configuration required

The feature seamlessly integrates with existing code and provides a much-needed capability for dual-SIM users.
