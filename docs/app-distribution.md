# App Distribution Guide

This document outlines the recommended methods for hosting and distributing SyncFlow application binaries (DMG for macOS and APK for Android).

## Distribution Options Comparison

| Option | Cost | Storage Limit | Bandwidth | Best For |
|--------|------|---------------|-----------|----------|
| **GitHub Releases** | Free | Unlimited (public repos) | Unlimited | Primary distribution |
| **Cloudflare R2** | $0.015/GB storage | Pay-as-you-go | 10GB/month free egress | Custom download pages |
| **Firebase Storage** | Free tier: 5GB storage | 5GB free | 1GB/day free downloads | Firebase-integrated apps |
| **Vercel** | ❌ Not recommended | ~100MB deployment limit | N/A | Web apps only, not binaries |

## Recommended: GitHub Releases

### Why GitHub Releases?

1. **Free and Reliable** - Unlimited storage and bandwidth for public repositories
2. **Professional** - Users trust GitHub for software downloads
3. **Versioning** - Built-in version management and changelog
4. **Download Statistics** - Track download counts per release
5. **CI/CD Integration** - Easy automation with GitHub Actions
6. **Release Notes** - Add detailed changelogs for each version

### Creating a Release via CLI

```bash
# Install GitHub CLI if not already installed
brew install gh

# Authenticate (one-time setup)
gh auth login

# Create a new release with attached binaries
gh release create v1.0.0 \
  --title "SyncFlow v1.0.0" \
  --notes "## What's New
- Feature 1
- Feature 2
- Bug fixes" \
  SyncFlowMac/build/SyncFlow.dmg \
  app/build/outputs/apk/release/SyncFlow.apk
```

### Creating a Release via GitHub Web UI

1. Go to your repository on GitHub
2. Click "Releases" in the right sidebar
3. Click "Draft a new release"
4. Create a new tag (e.g., `v1.0.0`)
5. Add release title and description
6. Drag and drop DMG and APK files
7. Click "Publish release"

### Automating Releases with GitHub Actions

Create `.github/workflows/release.yml`:

```yaml
name: Build and Release

on:
  push:
    tags:
      - 'v*'

jobs:
  release:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v3

      - name: Build macOS DMG
        run: |
          # Add your Xcode build commands here
          xcodebuild -project SyncFlowMac/SyncFlowMac.xcodeproj -scheme SyncFlowMac -configuration Release

      - name: Build Android APK
        run: |
          cd app
          ./gradlew assembleRelease

      - name: Create Release
        uses: softprops/action-gh-release@v1
        with:
          files: |
            SyncFlowMac/build/SyncFlow.dmg
            app/build/outputs/apk/release/SyncFlow.apk
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

## Alternative: Cloudflare R2

Since SyncFlow already uses Cloudflare R2 for MMS/photo storage, you can leverage it for app distribution.

### Setup R2 for App Downloads

1. **Upload to R2** (via Cloud Functions or manual upload):

```javascript
// functions/index.js
exports.uploadAppBinary = functions.https.onCall(async (data, context) => {
  // Admin-only function
  if (!context.auth || context.auth.uid !== ADMIN_UID) {
    throw new functions.https.HttpsError('permission-denied', 'Admin only');
  }

  const { platform, version } = data; // 'mac' or 'android'
  const key = `apps/${platform}/SyncFlow-${version}.${platform === 'mac' ? 'dmg' : 'apk'}`;

  // Generate upload URL
  const uploadUrl = await r2.getSignedUrl(key, { expiresIn: 3600, method: 'PUT' });
  return { uploadUrl, key };
});
```

2. **Generate Download URLs**:

```javascript
// functions/index.js
exports.getAppDownloadUrl = functions.https.onCall(async (data, context) => {
  const { platform } = data; // 'mac' or 'android'

  // Get latest version from database or hardcode
  const version = '1.0.0';
  const key = `apps/${platform}/SyncFlow-${version}.${platform === 'mac' ? 'dmg' : 'apk'}`;

  // Generate presigned download URL (expires in 1 hour)
  const url = await r2.getSignedUrl(key, { expiresIn: 3600 });
  return { url, version };
});
```

3. **Add Download Page** in web app:

```typescript
// web/app/download/page.tsx
'use client'

import { getFunctions, httpsCallable } from 'firebase/functions'

export default function DownloadPage() {
  const handleDownload = async (platform: 'mac' | 'android') => {
    const functions = getFunctions()
    const getDownloadUrl = httpsCallable(functions, 'getAppDownloadUrl')

    const result = await getDownloadUrl({ platform })
    const { url, version } = result.data as { url: string; version: string }

    // Trigger download
    window.location.href = url
  }

  return (
    <div>
      <button onClick={() => handleDownload('mac')}>Download for macOS</button>
      <button onClick={() => handleDownload('android')}>Download for Android</button>
    </div>
  )
}
```

### R2 Pricing

- **Storage**: $0.015/GB per month
- **Class A Operations** (uploads): $4.50 per million requests
- **Class B Operations** (downloads): $0.36 per million requests
- **Egress**: FREE up to 10GB/month, then $0.01/GB

**Example cost for 1000 downloads/month:**
- Storage (100MB app): ~$0.0015/month
- Downloads (1000): ~$0.0004/month
- Bandwidth (100GB): FREE (under 10GB limit per user)
- **Total**: < $0.01/month

## Alternative: Firebase Storage

```javascript
// Upload via Firebase Admin SDK
const bucket = admin.storage().bucket();
const file = bucket.file('apps/SyncFlow-v1.0.0.dmg');

await file.save(fileBuffer, {
  metadata: {
    contentType: 'application/octet-stream',
    metadata: {
      version: '1.0.0',
      platform: 'mac'
    }
  }
});

// Get download URL
const [url] = await file.getSignedUrl({
  action: 'read',
  expires: Date.now() + 24 * 60 * 60 * 1000 // 24 hours
});
```

## Integrating with Web App

Add a downloads page that fetches the latest release from GitHub:

```typescript
// web/app/download/page.tsx
export default async function DownloadPage() {
  // Fetch latest release from GitHub API
  const response = await fetch(
    'https://api.github.com/repos/YOUR_USERNAME/SyncFlow/releases/latest',
    { next: { revalidate: 3600 } } // Cache for 1 hour
  )
  const release = await response.json()

  // Extract download URLs
  const dmgAsset = release.assets.find((a: any) => a.name.endsWith('.dmg'))
  const apkAsset = release.assets.find((a: any) => a.name.endsWith('.apk'))

  return (
    <div>
      <h1>Download SyncFlow {release.tag_name}</h1>

      {dmgAsset && (
        <a href={dmgAsset.browser_download_url} download>
          Download for macOS ({(dmgAsset.size / 1024 / 1024).toFixed(1)} MB)
        </a>
      )}

      {apkAsset && (
        <a href={apkAsset.browser_download_url} download>
          Download for Android ({(apkAsset.size / 1024 / 1024).toFixed(1)} MB)
        </a>
      )}

      <div>
        <h2>Release Notes</h2>
        <div dangerouslySetInnerHTML={{ __html: release.body }} />
      </div>
    </div>
  )
}
```

## Best Practices

### Version Naming Convention

Use semantic versioning: `vMAJOR.MINOR.PATCH`

- **MAJOR**: Breaking changes (e.g., `v2.0.0`)
- **MINOR**: New features, backward compatible (e.g., `v1.1.0`)
- **PATCH**: Bug fixes (e.g., `v1.0.1`)

### File Naming Convention

```
SyncFlow-v1.0.0-macos.dmg
SyncFlow-v1.0.0-android.apk
```

### Release Checklist

- [ ] Update version number in `package.json` / `Info.plist` / `build.gradle`
- [ ] Update `CHANGELOG.md` with release notes
- [ ] Build production binaries
- [ ] Test installers on clean machines
- [ ] Create git tag: `git tag -a v1.0.0 -m "Version 1.0.0"`
- [ ] Push tag: `git push origin v1.0.0`
- [ ] Create GitHub release with binaries
- [ ] Update download page links (if not using GitHub API)
- [ ] Announce release to users

## Code Signing (Important!)

### macOS DMG Signing

```bash
# Sign the app bundle
codesign --force --deep --sign "Developer ID Application: Your Name" SyncFlow.app

# Notarize with Apple
xcrun notarytool submit SyncFlow.dmg --apple-id YOUR_APPLE_ID --password YOUR_APP_PASSWORD --team-id YOUR_TEAM_ID

# Staple the notarization
xcrun stapler staple SyncFlow.dmg
```

### Android APK Signing

```bash
# Generate keystore (one-time)
keytool -genkey -v -keystore syncflow-release.keystore -alias syncflow -keyalg RSA -keysize 2048 -validity 10000

# Sign APK
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 -keystore syncflow-release.keystore app-release-unsigned.apk syncflow

# Verify signature
jarsigner -verify -verbose -certs app-release-unsigned.apk
```

## Summary

**Primary Method**: GitHub Releases
- Best for open-source projects
- Free, reliable, professional
- Easy version management

**Backup Method**: Cloudflare R2
- For custom download pages
- Already integrated in your project
- Extremely cheap

**Not Recommended**: Vercel
- Designed for web apps, not binary distribution
- Deployment size limits make it unsuitable
