# MMS R2 Storage - Architecture & Troubleshooting

## Overview

SyncFlow stores MMS attachments (photos, videos, etc.) in **Cloudflare R2** instead of Firebase Storage for better cost efficiency and performance.

## Architecture

### Upload Flow (Android)

```
1. Android detects MMS with attachment
2. Reads attachment data from ContentProvider
3. Optionally encrypts with E2EE
4. Calls Cloud Function: getR2UploadUrl(fileName, contentType, fileSize, transferType="mms")
5. Gets presigned upload URL + r2Key
6. Uploads directly to R2 via PUT request
7. Calls confirmR2Upload(r2Key, fileSize) to record usage
8. Stores r2Key in Firebase: /users/{userId}/messages/{messageId}/attachments[]/r2Key
```

### Download Flow (macOS/Web)

```
1. Message sync receives attachment with r2Key
2. Detects r2Key field exists
3. Calls Cloud Function: getR2DownloadUrl(fileKey=r2Key)
4. Gets presigned download URL (valid for 1 hour)
5. Downloads attachment data
6. Optionally decrypts if encrypted=true
7. Caches locally for faster subsequent loads
```

## R2 Key Structure

```
MMS:    mms/{userId}/{messageId}/{fileId}.{ext}
Photos: photos/{userId}/{fileId}.jpg
Files:  files/{userId}/{fileId}/{fileName}
```

## Firebase Database Schema

```json
{
  "users": {
    "{userId}": {
      "messages": {
        "{messageId}": {
          "attachments": [
            {
              "id": "12345",
              "contentType": "image/jpeg",
              "fileName": "IMG_2024.jpg",
              "type": "image",
              "r2Key": "mms/{userId}/{messageId}/1234567890_abc123.jpg",
              "encrypted": false,
              "originalSize": 524288
            }
          ]
        }
      }
    }
  }
}
```

## Data Models

### Android: MmsAttachment (Kotlin)

```kotlin
data class MmsAttachment(
    val id: Long,
    val contentType: String,
    val fileName: String?,
    val data: ByteArray? = null,
    val filePath: String? = null
)

// When uploading, Android creates:
Map<String, Any?> metadata = {
    "id": attachment.id,
    "contentType": attachment.contentType,
    "fileName": attachment.fileName,
    "type": "image",
    "r2Key": "mms/.../...jpg",  // <-- Key for R2 download
    "encrypted": false,
    "originalSize": bytes.size
}
```

### macOS: MmsAttachment (Swift)

```swift
struct MmsAttachment: Codable {
    let id: String
    let contentType: String
    let fileName: String?
    let url: String?           // Legacy Firebase Storage URL
    let r2Key: String?         // NEW: R2 storage key
    let type: String
    let encrypted: Bool?
    let inlineData: String?    // Base64 fallback for small files
    let isInline: Bool?
}
```

## Cloud Functions

### getR2UploadUrl

**Purpose**: Generate presigned URL for uploading files to R2

**Input**:
```javascript
{
  fileName: "IMG_2024.jpg",
  contentType: "image/jpeg",
  fileSize: 524288,
  transferType: "mms",      // "mms" | "photos" | "files"
  messageId: "1234567890"   // Optional, for MMS
}
```

**Output**:
```javascript
{
  success: true,
  uploadUrl: "https://syncflow-bucket.account-id.r2.cloudflarestorage.com/...",
  fileKey: "mms/{userId}/{messageId}/{fileId}.jpg",
  fileId: "1234567890_abc123",
  expiresIn: 3600
}
```

**Validation**:
- Checks user authentication
- Validates file size against plan limits (50MB free, 500MB pro)
- Checks storage quota (500MB free, 2GB pro)
- Throws error if R2 not configured

### confirmR2Upload

**Purpose**: Confirm successful upload and record usage stats

**Input**:
```javascript
{
  fileKey: "mms/{userId}/{messageId}/{fileId}.jpg",
  fileSize: 524288,
  transferType: "mms"
}
```

**Side Effects**:
- Increments `users/{userId}/usage/storageBytes`
- Increments `users/{userId}/usage/monthly/mmsBytes`
- Records timestamp in `users/{userId}/usage/lastUploadAt`

### getR2DownloadUrl

**Purpose**: Generate presigned URL for downloading files from R2

**Input**:
```javascript
{
  fileKey: "mms/{userId}/{messageId}/{fileId}.jpg"
}
```

**Output**:
```javascript
{
  success: true,
  downloadUrl: "https://syncflow-bucket.account-id.r2.cloudflarestorage.com/...",
  expiresIn: 3600
}
```

**Security**:
- Validates user owns the file (checks `/{userId}/` in fileKey)
- Throws permission denied if accessing another user's file

## Troubleshooting

### MMS Images Show "Failed to Load"

**Symptoms**:
- Android sends MMS successfully
- macOS shows "Failed to load" placeholder with retry button
- Console shows: `Error loading image: ...`

**Cause**:
- MmsAttachment has `r2Key` but macOS doesn't handle it
- OR `getR2DownloadUrl` Cloud Function returns error
- OR presigned URL expired (> 1 hour old)

**Fix**:
1. Check macOS app has `r2Key` field in MmsAttachment model ✅ (Fixed in commit 24fdb54)
2. Check `loadAttachmentData()` calls `getR2DownloadUrl()` ✅ (Fixed in commit 24fdb54)
3. Verify R2 config exists:
   ```bash
   cd functions
   firebase functions:config:get r2
   ```
4. Check logs for R2 errors:
   ```bash
   firebase functions:log --only getR2DownloadUrl
   ```

### R2 Config Missing

**Symptoms**:
```
Error: R2 storage not configured
```

**Fix**:
```bash
cd functions

# Set R2 credentials
firebase functions:config:set \
  r2.account_id="YOUR_ACCOUNT_ID" \
  r2.access_key="YOUR_ACCESS_KEY" \
  r2.secret_key="YOUR_SECRET_KEY" \
  r2.bucket="syncflow-cloudflare-bucket"

# Deploy functions
firebase deploy --only functions
```

### Check R2 Config

```bash
cd /Users/dchavali/GitHub/SyncFlow/functions
firebase functions:config:get
```

Expected output:
```json
{
  "r2": {
    "account_id": "244a173...",
    "access_key": "cc66489...",
    "secret_key": "057b605...",
    "bucket": "syncflow-cloudflare-bucket"
  }
}
```

### MMS Uploads Failing on Android

**Symptoms**:
- Android logs: `Error uploading attachment to R2`
- Falls back to inline data (base64)
- Only small attachments (<500KB) sync

**Causes**:
1. R2 credentials invalid
2. Network timeout
3. File size exceeds plan limit

**Debug**:
```bash
# Check Android logs
adb logcat -s DesktopSyncService:D

# Look for:
# - "Generated upload URL for..."
# - "Uploaded attachment to R2: ... -> mms/..."
# - "Error uploading attachment ... to R2"
```

### Legacy Firebase Storage URLs

**Backward Compatibility**:

The system supports both:
- **New R2 attachments**: `r2Key` field → call `getR2DownloadUrl`
- **Legacy Firebase attachments**: `url` field → download directly

Detection logic in `loadAttachmentData()`:
```swift
if let r2Key = attachment.r2Key {
    // New R2 flow
    let downloadUrl = try await getR2DownloadUrl(r2Key: r2Key)
    rawData = try await AttachmentCacheManager.shared.loadData(from: downloadUrl)
} else if let urlString = attachment.url {
    // Legacy Firebase Storage flow
    rawData = try await AttachmentCacheManager.shared.loadData(from: urlString)
}
```

## Cost Comparison

### Firebase Storage
- **Storage**: $0.026/GB/month
- **Downloads**: $0.12/GB
- **Uploads**: $0.05/GB

Example: 100 MMS/month (50MB each)
- Storage: 5GB × $0.026 = $0.13/month
- Downloads: 5GB × $0.12 = $0.60
- **Total**: ~$0.73/month

### Cloudflare R2
- **Storage**: $0.015/GB/month
- **Downloads**: FREE (first 10GB/month/user)
- **Uploads**: $4.50/million operations (negligible)

Example: 100 MMS/month (50MB each)
- Storage: 5GB × $0.015 = $0.075/month
- Downloads: FREE
- **Total**: ~$0.075/month

**Savings**: ~90% cheaper 💰

## Testing

### Test MMS Upload

1. **Android**:
   ```kotlin
   // Send MMS with image
   // Check logs for:
   // [DesktopSyncService] Uploaded attachment to R2: 12345 -> mms/.../...jpg
   ```

2. **Verify in Firebase**:
   ```bash
   # Check attachment has r2Key
   firebase database:get /users/{userId}/messages/{messageId}/attachments/0/r2Key
   ```

3. **macOS**:
   - Open conversation
   - Image should load from R2
   - Console should show:
     ```
     [AttachmentView] Loading from R2: mms/.../...jpg
     [AttachmentView] Got R2 download URL: https://...
     [Cache] Miss - downloading: https://...
     ```

### Test Fallback

1. **Delete R2 config**:
   ```bash
   firebase functions:config:unset r2
   firebase deploy --only functions
   ```

2. **Send MMS**: Should fall back to inline data (base64)

3. **Restore R2**:
   ```bash
   firebase functions:config:set r2.account_id=... r2.access_key=... r2.secret_key=... r2.bucket=...
   firebase deploy --only functions
   ```

## Monitoring

### Check R2 Usage

```bash
# List files in bucket
wrangler r2 object list syncflow-cloudflare-bucket --prefix="mms/"

# Get file info
wrangler r2 object get syncflow-cloudflare-bucket/mms/{userId}/{messageId}/{fileId}.jpg
```

### Check Firebase Usage Stats

```bash
firebase database:get /users/{userId}/usage
```

Expected output:
```json
{
  "storageBytes": 52428800,
  "monthly": {
    "mmsBytes": 10485760,
    "photoBytes": 5242880,
    "filesBytes": 1048576
  },
  "lastUploadAt": 1706544000000
}
```

## Migration from Firebase Storage

If you have existing MMS in Firebase Storage:

1. **They will continue to work** (legacy `url` field)
2. **New MMS** will use R2 (`r2Key` field)
3. **Optional migration script**:

```javascript
// Migrate old attachments to R2
const migrateAttachmentToR2 = async (userId, messageId, attachment) => {
  if (attachment.url && !attachment.r2Key) {
    // Download from Firebase Storage
    const data = await fetch(attachment.url).then(r => r.arrayBuffer());

    // Upload to R2
    const { uploadUrl, r2Key } = await getR2UploadUrl({
      fileName: attachment.fileName,
      contentType: attachment.contentType,
      fileSize: data.byteLength,
      transferType: "mms",
      messageId: messageId
    });

    await fetch(uploadUrl, {
      method: 'PUT',
      body: data,
      headers: { 'Content-Type': attachment.contentType }
    });

    // Update database
    await admin.database().ref(`users/${userId}/messages/${messageId}/attachments`)
      .child(attachment.id)
      .update({ r2Key, url: null });
  }
};
```

## Security

### Access Control

- **Upload**: Only authenticated users can upload
- **Download**: Only file owner can download (checked via `/{userId}/` in fileKey)
- **Presigned URLs**: Expire after 1 hour
- **E2EE**: Optional end-to-end encryption for attachments

### Best Practices

1. **Never expose R2 credentials** in client code
2. **Always validate fileKey** contains user ID before generating download URL
3. **Use presigned URLs** instead of direct bucket access
4. **Enable E2EE** for sensitive attachments
5. **Rotate R2 access keys** periodically

## Useful Commands

```bash
# Check R2 config
cd functions && firebase functions:config:get r2

# View Cloud Functions logs
firebase functions:log

# Deploy only R2-related functions
firebase deploy --only functions:getR2UploadUrl,functions:getR2DownloadUrl,functions:confirmR2Upload

# Test R2 upload locally
cd functions
npm run serve
# Then call: http://localhost:5001/{project}/us-central1/getR2UploadUrl

# Clear attachment cache on macOS
# Settings → Privacy → Clear Cache → Select "Attachment Cache"
```
