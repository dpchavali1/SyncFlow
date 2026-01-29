# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PhoneIntegration is an Android application built with Kotlin and Jetpack Compose that provides SMS message integration functionality. The app displays SMS messages in a Material Design 3 UI and monitors incoming SMS messages via a BroadcastReceiver.

## Build and Development Commands

### Building the Project
```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install debug build on connected device/emulator
./gradlew installDebug

# Clean build artifacts
./gradlew clean
```

### Running Tests
```bash
# Run all unit tests
./gradlew test

# Run unit tests for debug variant
./gradlew testDebugUnitTest

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Run a specific test class
./gradlew test --tests com.phoneintegration.app.ExampleUnitTest

# Run a specific test method
./gradlew test --tests com.phoneintegration.app.ExampleUnitTest.addition_isCorrect
```

### Code Quality
```bash
# Run lint checks
./gradlew lint

# Generate lint report
./gradlew lintDebug
```

## Architecture

### Data Flow
The app follows an MVVM (Model-View-ViewModel) pattern:

1. **View Layer** (`MainActivity.kt`): Jetpack Compose UI that displays SMS messages in a LazyColumn. Handles runtime permissions for SMS access.

2. **ViewModel Layer** (`SmsViewModel.kt`): AndroidViewModel that manages UI state using StateFlow. Exposes `messages` and `isLoading` state to the UI. Coordinates data loading through the repository.

3. **Repository Layer** (`SmsRepository.kt`): Handles data access from the Android SMS ContentProvider (`content://sms/`). Queries and parses SMS messages, limiting results to 50 most recent messages sorted by date descending.

4. **BroadcastReceiver** (`SmsReceiver.kt`): Registered in AndroidManifest with priority 999 to receive incoming SMS messages. Logs messages to Logcat and displays Toast notifications. Operates independently from the ViewModel/Repository flow.

### Key Components

**SmsMessage** (`SmsMessage.kt`): Data class representing an SMS message with id, address (phone number), body, date timestamp, and type (1=received, 2=sent).

**Permission Handling**: The app requests SMS permissions (RECEIVE_SMS, READ_SMS, SEND_SMS, READ_PHONE_STATE) at runtime in MainActivity using ActivityResultContracts.

**SMS Receiver Registration**: The BroadcastReceiver is registered in AndroidManifest.xml with `android:exported="true"` to receive system broadcasts for incoming SMS messages.

## Configuration Details

- **Package Name**: `com.phoneintegration.app`
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36
- **Compile SDK**: 36
- **Java Compatibility**: Java 11
- **Kotlin JVM Target**: 11

## Important Implementation Notes

### SMS Permissions
All SMS-related operations require runtime permission checks. The app requests permissions on startup but should verify permissions before accessing SMS data.

### ContentProvider Queries
SMS messages are accessed via `content://sms/` ContentProvider. The repository currently limits queries to 50 messages with "date DESC" sorting. Column indices are retrieved dynamically to avoid hardcoding.

### BroadcastReceiver Lifecycle
The SmsReceiver operates independently of the MainActivity lifecycle. Incoming messages are logged and displayed via Toast, but the app does not automatically refresh the message list in the UI when new messages arrive. The user must manually tap the refresh button.

### Compose UI State Management
The UI uses `collectAsStateWithLifecycle()` to safely collect StateFlow emissions while respecting the Compose lifecycle. This prevents memory leaks and ensures state collection stops when the UI is not visible.

## CRITICAL: Data Safety Rules

### NEVER DELETE USER DATA WITHOUT EXPLICIT PERMISSION

This is a **mandatory rule** for all code in this project:

1. **NO automatic deletions** - Never write code that automatically deletes SMS, MMS, contacts, or any user data
2. **NO sync-based deletions** - Never delete local data because it's "missing" from Firebase/cloud
3. **NO reconciliation deletions** - Never compare local vs remote and delete differences
4. **NO cleanup functions** that touch user content without explicit user action
5. **EVERY delete action** must require explicit user tap/confirmation in the UI

**Why:** A bug in `reconcileDeletedMessages()` caused loss of 17,000+ user messages. This function incorrectly deleted local messages that weren't yet synced to Firebase.

**The only acceptable deletion patterns:**
- User explicitly taps "Delete" button on a specific message/conversation
- User explicitly confirms deletion in a dialog
- Admin explicitly triggers cleanup from admin panel with confirmation

**Never write code that:**
- Deletes based on "not found in cloud"
- Deletes based on timestamps or age
- Deletes as part of "sync" operations
- Deletes in background services without user action
