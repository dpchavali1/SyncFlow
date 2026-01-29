# SyncFlow Distribution Guide
## Complete Step-by-Step Guide for Packaging & Distributing macOS and Android Apps

Last Updated: January 29, 2026

---

## 📋 Table of Contents
- [Part 1: macOS App Packaging & Distribution](#part-1-macos-app-packaging--distribution)
- [Part 2: Android App Packaging & Distribution](#part-2-android-app-packaging--distribution)
- [Part 3: Web Download Page Setup](#part-3-web-download-page-setup)
- [Part 4: Hosting & CDN Setup](#part-4-hosting--cdn-setup)
- [Part 5: Marketing & Launch](#part-5-marketing--launch)

---

## Part 1: macOS App Packaging & Distribution

### Step 1: Join Apple Developer Program (REQUIRED)

**Cost:** $99/year
**Required for:** Code signing and notarization

1. **Go to:** https://developer.apple.com/programs/
2. **Click** "Enroll"
3. **Complete enrollment** with your Apple ID
4. **Pay** $99/year fee
5. **Wait** 24-48 hours for approval

**What you get:**
- Developer ID certificate (for code signing)
- Ability to notarize apps
- Access to TestFlight (for beta testing)
- Ability to submit to Mac App Store

---

### Step 2: Create Certificates & Identifiers

#### 2.1 Create App ID

1. Go to https://developer.apple.com/account
2. Navigate to **Certificates, Identifiers & Profiles**
3. Click **Identifiers** → **+** (plus icon)
4. Select **App IDs** → Continue
5. Fill in details:
   - **Description:** SyncFlow macOS
   - **Bundle ID:** com.syncflow.mac (explicit)
   - **Capabilities:** Enable Push Notifications, Sign in with Apple (if needed)
6. Click **Register**

#### 2.2 Create Developer ID Application Certificate

1. **Open Keychain Access** on your Mac
2. Go to **Keychain Access** → **Certificate Assistant** → **Request a Certificate from a Certificate Authority**
3. Fill in:
   - **User Email:** your@email.com
   - **Common Name:** Your Name
   - **CA Email:** Leave blank
   - Select **Saved to disk**
4. Click **Continue** and save the `.certSigningRequest` file

5. **Go back to** https://developer.apple.com/account
6. Click **Certificates** → **+** (plus icon)
7. Select **Developer ID Application** → Continue
8. Upload your `.certSigningRequest` file
9. Download the certificate (`.cer` file)
10. **Double-click** the downloaded certificate to install it in Keychain Access

**Verify:** Open Keychain Access → My Certificates. You should see "Developer ID Application: Your Name"

---

### Step 3: Configure Xcode Project

1. **Open** `SyncFlowMac.xcodeproj` in Xcode
2. Select the **SyncFlowMac** target
3. Go to **Signing & Capabilities** tab

4. **General Settings:**
   - Bundle Identifier: `com.syncflow.mac`
   - Version: `1.0.0`
   - Build: `1`
   - Deployment Target: `macOS 13.0`

5. **Signing:**
   - ☑️ Automatically manage signing
   - Team: Select your Apple Developer team
   - Signing Certificate: Developer ID Application

6. **Add Capabilities:**
   - Push Notifications (if using)
   - Sign in with Apple (if using)

---

### Step 4: Create ExportOptions.plist

Create file: `scripts/ExportOptions.plist`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>developer-id</string>
    <key>teamID</key>
    <string>YOUR_TEAM_ID</string>
    <key>signingStyle</key>
    <string>automatic</string>
    <key>uploadBitcode</key>
    <false/>
    <key>uploadSymbols</key>
    <true/>
    <key>compileBitcode</key>
    <false/>
</dict>
</plist>
```

**Replace:**
- `YOUR_TEAM_ID` with your Apple Developer Team ID (find it at developer.apple.com/account)

---

### Step 5: Create Build Script

Create file: `scripts/build-macos.sh`

```bash
#!/bin/bash
set -e

# Configuration
VERSION="1.0.0"
APP_NAME="SyncFlow"
BUNDLE_ID="com.syncflow.mac"
DEVELOPER_ID="Developer ID Application: Your Name (TEAM_ID)"
APPLE_ID="your@email.com"
TEAM_ID="YOUR_TEAM_ID"
PROJECT_PATH="./SyncFlowMac/SyncFlowMac.xcodeproj"
SCHEME="SyncFlowMac"

echo "🚀 Building $APP_NAME v$VERSION..."

# Clean previous builds
rm -rf build
rm -rf "$APP_NAME.xcarchive"
rm -f "$APP_NAME-$VERSION.dmg"

# Build and archive the app
echo "📦 Archiving..."
xcodebuild \
  -project "$PROJECT_PATH" \
  -scheme "$SCHEME" \
  -configuration Release \
  -archivePath "$APP_NAME.xcarchive" \
  archive

# Export the app
echo "📤 Exporting..."
xcodebuild \
  -exportArchive \
  -archivePath "$APP_NAME.xcarchive" \
  -exportPath ./build \
  -exportOptionsPlist ./scripts/ExportOptions.plist

# Sign the app
echo "✍️  Signing..."
codesign --deep --force --verify --verbose \
  --sign "$DEVELOPER_ID" \
  --options runtime \
  --timestamp \
  "./build/$APP_NAME.app"

# Verify signature
echo "🔍 Verifying signature..."
codesign --verify --deep --strict --verbose=2 "./build/$APP_NAME.app"
spctl --assess --type execute --verbose ./build/$APP_NAME.app

# Create DMG
echo "💿 Creating DMG..."
hdiutil create \
  -volname "$APP_NAME" \
  -srcfolder "./build/$APP_NAME.app" \
  -ov \
  -format UDZO \
  "$APP_NAME-$VERSION.dmg"

# Sign the DMG
echo "✍️  Signing DMG..."
codesign --sign "$DEVELOPER_ID" "$APP_NAME-$VERSION.dmg"

# Notarize
echo "📮 Submitting for notarization..."
echo "⏳ This may take 5-15 minutes..."

xcrun notarytool submit "$APP_NAME-$VERSION.dmg" \
  --apple-id "$APPLE_ID" \
  --team-id "$TEAM_ID" \
  --password "@keychain:AC_PASSWORD" \
  --wait

# Check notarization status
echo "📋 Checking notarization status..."
xcrun notarytool log --apple-id "$APPLE_ID" --team-id "$TEAM_ID" --password "@keychain:AC_PASSWORD" | tail -20

# Staple the ticket
echo "📎 Stapling notarization ticket..."
xcrun stapler staple "$APP_NAME-$VERSION.dmg"

# Verify stapling
echo "🔍 Verifying staple..."
xcrun stapler validate "$APP_NAME-$VERSION.dmg"

# Generate checksum
echo "🔐 Generating SHA-256 checksum..."
shasum -a 256 "$APP_NAME-$VERSION.dmg" > "$APP_NAME-$VERSION.dmg.sha256"
cat "$APP_NAME-$VERSION.dmg.sha256"

# File size
FILE_SIZE=$(du -h "$APP_NAME-$VERSION.dmg" | cut -f1)
echo ""
echo "✅ Build complete!"
echo "📦 File: $APP_NAME-$VERSION.dmg"
echo "💾 Size: $FILE_SIZE"
echo "🔐 SHA-256: $(cat "$APP_NAME-$VERSION.dmg.sha256" | cut -d' ' -f1)"
echo ""
echo "🎉 Ready to upload to sfweb.app/downloads/"
```

**Make it executable:**
```bash
chmod +x scripts/build-macos.sh
```

---

### Step 6: Set Up App-Specific Password (for notarization)

1. Go to https://appleid.apple.com
2. Sign in with your Apple ID
3. Go to **Security** → **App-Specific Passwords**
4. Click **Generate Password**
5. Name it: "SyncFlow Notarization"
6. **Copy the generated password** (it looks like: xxxx-xxxx-xxxx-xxxx)

7. **Store it in Keychain** (on your Mac):
```bash
xcrun notarytool store-credentials \
  --apple-id "your@email.com" \
  --team-id "YOUR_TEAM_ID" \
  --password "xxxx-xxxx-xxxx-xxxx"
```

When prompted, name it: `AC_PASSWORD`

---

### Step 7: Build and Notarize

**Before running:**
1. Update `scripts/ExportOptions.plist` with your Team ID
2. Update `scripts/build-macos.sh` with your:
   - Developer ID name
   - Apple ID email
   - Team ID

**Run the build:**
```bash
cd /Users/dchavali/GitHub/SyncFlow
./scripts/build-macos.sh
```

**What happens:**
1. ✅ Cleans previous builds
2. ✅ Archives the app
3. ✅ Exports as release build
4. ✅ Signs with Developer ID
5. ✅ Verifies signature
6. ✅ Creates DMG file
7. ✅ Signs the DMG
8. ✅ Submits to Apple for notarization (wait 5-15 min)
9. ✅ Staples notarization ticket
10. ✅ Generates SHA-256 checksum

**Expected output:**
```
✅ Build complete!
📦 File: SyncFlow-1.0.0.dmg
💾 Size: 45M
🔐 SHA-256: abc123def456...
```

---

## Part 2: Android App Packaging & Distribution

### Step 1: Create Upload Keystore

**One-time setup:**

```bash
cd /Users/dchavali/GitHub/SyncFlow/app

# Generate keystore
keytool -genkey -v \
  -keystore syncflow-upload.keystore \
  -alias syncflow-upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000

# You'll be prompted for:
# - Keystore password (remember this!)
# - Your name
# - Organization
# - City, State, Country
```

**Important:** Back up this keystore! If you lose it, you can't update your app.

---

### Step 2: Configure Gradle for Signing

Create file: `app/keystore.properties` (DO NOT commit this to git!)

```properties
storeFile=syncflow-upload.keystore
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=syncflow-upload
keyPassword=YOUR_KEY_PASSWORD
```

Add to `.gitignore`:
```
app/keystore.properties
app/*.keystore
```

---

### Step 3: Update `app/build.gradle.kts`

Add signing config (if not already present):

```kotlin
android {
    // ... existing config

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("app/keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))

                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

---

### Step 4: Create Android Build Script

Create file: `scripts/build-android.sh`

```bash
#!/bin/bash
set -e

VERSION="1.0.0"
VERSION_CODE=1
APP_NAME="SyncFlow"

echo "🚀 Building Android APK v$VERSION..."

cd app

# Clean previous builds
echo "🧹 Cleaning..."
../gradlew clean

# Build release APK
echo "📦 Building release APK..."
../gradlew assembleRelease

# Build release AAB (for Play Store)
echo "📦 Building release AAB..."
../gradlew bundleRelease

cd ..

# Find the built files
APK_PATH="app/build/outputs/apk/release/app-release.apk"
AAB_PATH="app/build/outputs/bundle/release/app-release.aab"

# Rename with version
cp "$APK_PATH" "SyncFlow-$VERSION.apk"
cp "$AAB_PATH" "SyncFlow-$VERSION.aab"

# Generate checksums
echo "🔐 Generating checksums..."
shasum -a 256 "SyncFlow-$VERSION.apk" > "SyncFlow-$VERSION.apk.sha256"
shasum -a 256 "SyncFlow-$VERSION.aab" > "SyncFlow-$VERSION.aab.sha256"

# File sizes
APK_SIZE=$(du -h "SyncFlow-$VERSION.apk" | cut -f1)
AAB_SIZE=$(du -h "SyncFlow-$VERSION.aab" | cut -f1)

echo ""
echo "✅ Build complete!"
echo "📦 APK: SyncFlow-$VERSION.apk ($APK_SIZE)"
echo "📦 AAB: SyncFlow-$VERSION.aab ($AAB_SIZE)"
echo "🔐 APK SHA-256: $(cat "SyncFlow-$VERSION.apk.sha256" | cut -d' ' -f1)"
echo "🔐 AAB SHA-256: $(cat "SyncFlow-$VERSION.aab.sha256" | cut -d' ' -f1)"
echo ""
echo "📱 APK ready for sfweb.app/downloads/"
echo "📱 AAB ready for Google Play Console"
```

**Make it executable:**
```bash
chmod +x scripts/build-android.sh
```

---

### Step 5: Build Android App

```bash
cd /Users/dchavali/GitHub/SyncFlow
./scripts/build-android.sh
```

**What happens:**
1. ✅ Cleans previous builds
2. ✅ Builds signed release APK (for direct download)
3. ✅ Builds signed release AAB (for Play Store)
4. ✅ Generates SHA-256 checksums

**Output files:**
- `SyncFlow-1.0.0.apk` - Direct download version
- `SyncFlow-1.0.0.aab` - Play Store version
- Checksum files

---

## Part 3: Web Download Page Setup

### Step 1: Create Downloads Directory on Server

On your server (or Vercel/Netlify):

```bash
mkdir -p public/downloads
```

### Step 2: Upload Build Files

**Upload to your server:**
```bash
# macOS
scp SyncFlow-1.0.0.dmg user@sfweb.app:/var/www/public/downloads/
scp SyncFlow-1.0.0.dmg.sha256 user@sfweb.app:/var/www/public/downloads/

# Android
scp SyncFlow-1.0.0.apk user@sfweb.app:/var/www/public/downloads/
scp SyncFlow-1.0.0.apk.sha256 user@sfweb.app:/var/www/public/downloads/
```

**Or use a CDN like Cloudflare R2:**
1. Go to Cloudflare dashboard
2. Create R2 bucket: `syncflow-downloads`
3. Upload files
4. Make bucket public
5. Get public URL: `https://downloads.syncflow.app/SyncFlow-1.0.0.dmg`

---

### Step 3: Update Download Page

Edit `web/app/download/page.tsx`:

Update these values:
```typescript
const version = '1.0.0'  // Your version
const fileSize = '45 MB'  // Actual DMG size
const sha256 = 'abc123...' // From .sha256 file
```

---

### Step 4: Test Download Page Locally

```bash
cd web
npm run dev
```

Visit: http://localhost:3000/download

**Verify:**
- ✅ Download button works
- ✅ Checksum displayed
- ✅ Installation guide readable
- ✅ Links to privacy/terms work

---

### Step 5: Deploy to Production

**If using Vercel:**
```bash
cd web
vercel --prod
```

**If using custom server:**
```bash
npm run build
# Copy .next/ to your server
```

---

## Part 4: Hosting & CDN Setup

### Option A: Cloudflare R2 (Recommended - Cheap)

**Costs:** $0.015/GB storage + $0.36/million requests

1. Go to https://dash.cloudflare.com
2. Navigate to **R2 Object Storage**
3. Click **Create bucket**
4. Name: `syncflow-downloads`
5. Click **Create bucket**

6. **Upload files:**
   - Click **Upload**
   - Select your DMG and APK files
   - Upload checksums too

7. **Make public:**
   - Go to **Settings** → **Public access**
   - Enable **Public bucket**
   - Get public URL: `https://pub-xxxxx.r2.dev/`

8. **Custom domain (optional):**
   - Go to **Settings** → **Custom domains**
   - Add: `downloads.syncflow.app`
   - Update DNS CNAME record

---

### Option B: AWS S3 + CloudFront

**Costs:** $0.023/GB storage + CloudFront fees

1. Create S3 bucket: `syncflow-downloads`
2. Upload files
3. Create CloudFront distribution
4. Point to S3 bucket
5. Update download URLs

---

### Option C: Host on Web Server

**If you already have a web server:**

1. Create directory: `/var/www/syncflow.app/public/downloads`
2. Upload files via SCP/SFTP
3. Ensure files are readable: `chmod 644 *.dmg *.apk`
4. URLs will be: `https://sfweb.app/downloads/SyncFlow-1.0.0.dmg`

---

## Part 5: Marketing & Launch

### Step 1: Soft Launch (Week 1)

**Goal:** Get first 10-50 users

1. **Friends & Family:**
   - Send download link
   - Ask for feedback
   - Fix critical bugs

2. **Social Media:**
   - Post on Twitter/X
   - Post on LinkedIn
   - Share in relevant Slack communities

3. **Direct Outreach:**
   - Email existing contacts
   - Share in tech groups

---

### Step 2: Public Launch (Week 2-4)

**Goal:** Get first 100-500 users

1. **Product Hunt:**
   - Create account
   - Submit your app
   - Best day: Tuesday-Thursday

2. **Reddit:**
   - r/macapps
   - r/Android
   - r/SideProject

3. **Hacker News:**
   - Show HN post
   - Explain what you built

4. **Tech Blogs:**
   - Submit to The Verge tips
   - Submit to TechCrunch
   - Submit to 9to5Mac

---

### Step 3: App Store Submission (Month 2-3)

**After gathering feedback and fixing bugs:**

#### Mac App Store:
1. Create App Store Connect listing
2. Add screenshots
3. Write description
4. Submit for review (7-14 days)

#### Google Play Store:
1. Create Play Console listing
2. Add screenshots
3. Complete content rating
4. Upload AAB file
5. Submit for review (3-7 days)

---

## 📝 Checklist Before Launch

### Pre-Launch Checklist

- [ ] Apple Developer account active ($99/year paid)
- [ ] Code signing certificate installed
- [ ] App notarized successfully
- [ ] Android keystore created and backed up
- [ ] Privacy policy published at sfweb.app/privacy
- [ ] Terms of service published at sfweb.app/terms
- [ ] Download page tested and working
- [ ] Files uploaded to CDN/server
- [ ] SHA-256 checksums verified
- [ ] DMG opens correctly on test Mac
- [ ] APK installs correctly on test Android device
- [ ] Both apps pair successfully
- [ ] Message sync works
- [ ] File transfer works
- [ ] No crashes in basic testing

### Launch Day Checklist

- [ ] Post on Product Hunt
- [ ] Post on Reddit (3+ subreddits)
- [ ] Tweet about launch
- [ ] Email friends/family
- [ ] Post in tech communities
- [ ] Monitor for bug reports
- [ ] Respond to user feedback within 24h

---

## 🆘 Troubleshooting

### macOS Build Issues

**"Code signing failed"**
- Verify certificate in Keychain Access
- Check Team ID is correct
- Ensure automatic signing is enabled

**"Notarization failed"**
- Check Apple ID credentials
- Verify app-specific password
- Review notarization logs: `xcrun notarytool log`

**"App can't be opened" on user's Mac**
- Ensure notarization succeeded
- Verify stapling: `xcrun stapler validate`
- Check code signature: `codesign --verify`

### Android Build Issues

**"Keystore not found"**
- Ensure `keystore.properties` exists
- Check file path in properties file

**"Build failed"**
- Run `./gradlew clean`
- Update Gradle: `./gradlew wrapper --gradle-version 8.4`

---

## 📧 Support Contacts

**Need help?**
- Technical issues: Create GitHub issue
- Urgent problems: Email yourself notes for debugging

**Resources:**
- Apple Notarization: https://developer.apple.com/documentation/security/notarizing_macos_software_before_distribution
- Android Signing: https://developer.android.com/studio/publish/app-signing

---

## 🎉 You're Ready!

Follow this guide step by step, and you'll have:
- ✅ Signed and notarized macOS app
- ✅ Signed Android APK
- ✅ Professional download page
- ✅ Privacy policy & terms
- ✅ Ready to launch!

**Estimated time:**
- Initial setup: 4-6 hours
- Each subsequent build: 30 minutes

Good luck with your launch! 🚀
