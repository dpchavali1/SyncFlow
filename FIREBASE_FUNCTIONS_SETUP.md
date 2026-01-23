# Firebase Functions Setup for SyncFlow Push Notifications

## Problem
When the app is closed or in the background, incoming call notifications don't work because Firebase Realtime Database listeners stop when the app is killed.

## Solution: Firebase Cloud Functions
Cloud Functions run on the server and send FCM push notifications when a call is initiated.

## Setup Instructions

### 1. Fix IAM Permissions (One-time setup)

Go to the Google Cloud Console and grant the necessary permissions:

1. Open https://console.cloud.google.com/iam-admin/iam?project=syncflow-6980e
2. Click "+ Grant Access"
3. Add the service account: `service-947940485830@gcf-admin-robot.iam.gserviceaccount.com`
4. Assign the role: `Artifact Registry Reader`
5. Click "Save"

Alternatively, run this command if you have gcloud CLI installed:
```bash
gcloud projects add-iam-policy-binding syncflow-6980e \
  --member="serviceAccount:service-947940485830@gcf-admin-robot.iam.gserviceaccount.com" \
  --role="roles/artifactregistry.reader"
```

### 2. Deploy Functions

After fixing permissions, run:
```bash
cd /Users/dchavali/Documents/GitHub/SyncFlow
firebase deploy --only functions
```

### 3. How It Works

1. **Android app initiates call** → Writes to `fcm_notifications/{recipientUid}/{callId}`
2. **Cloud Function triggers** → Reads the notification data
3. **Cloud Function sends FCM** → High-priority data message to recipient's device
4. **Recipient's device wakes up** → SyncFlowMessagingService receives the FCM
5. **Shows incoming call notification** → User can answer/decline

## Files Created

- `functions/package.json` - Dependencies
- `functions/index.js` - Cloud Functions code
- `functions/.eslintrc.js` - Lint configuration

## Functions Deployed

1. **sendCallNotification** - Sends FCM when a call is initiated
2. **sendCallCancellation** - Sends FCM when a call is cancelled
3. **cleanupOldNotifications** - Removes stale notifications every 5 minutes

## Database Paths

- `fcm_tokens/{userId}` - FCM tokens for each user
- `fcm_notifications/{userId}/{callId}` - Queue for outgoing notifications

## Testing

1. Make sure both devices are online and app is running
2. Make a call from macOS to Android
3. Check Firebase Console > Functions > Logs for function execution
4. Check device for FCM notification
