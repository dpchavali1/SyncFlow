# SyncFlow App Store Submission Guide

This guide covers all steps required to publish SyncFlow on the Google Play Store (Android) and Mac App Store (macOS).

---

## Table of Contents

1. [Pre-Submission Checklist](#pre-submission-checklist)
2. [Google Play Store (Android)](#google-play-store-android)
3. [Mac App Store (macOS)](#mac-app-store-macos)
4. [Post-Submission](#post-submission)

---

## Pre-Submission Checklist

### Both Platforms

- [ ] Privacy Policy URL (required by both stores)
- [ ] Support URL or email
- [ ] App icon in all required sizes
- [ ] Screenshots for all device sizes
- [ ] App description (short and long)
- [ ] Keywords/tags for discoverability
- [ ] Test all features thoroughly
- [ ] Remove all debug code and test credentials

### Legal Requirements

- [ ] Terms of Service document
- [ ] Privacy Policy document
- [ ] GDPR compliance (if serving EU users)
- [ ] Data collection disclosure

---

## Google Play Store (Android)

### 1. Developer Account Setup

1. Go to [Google Play Console](https://play.google.com/console)
2. Pay one-time $25 registration fee
3. Complete identity verification
4. Accept Developer Distribution Agreement

### 2. Create App Listing

1. Click "Create app" in Play Console
2. Fill in:
   - App name: `SyncFlow - SMS & Calls on Desktop`
   - Default language: English
   - App or game: App
   - Free or paid: **Free**

### 3. Store Listing

#### Main Store Listing

| Field | Value |
|-------|-------|
| App name | SyncFlow - SMS & Calls on Desktop |
| Short description (80 chars) | Access Android SMS, calls & notifications from your Mac or PC |
| Full description (4000 chars) | See below |

**Full Description:**
```
SyncFlow brings your Android phone's messages, calls, and notifications to your Mac or Windows PC.

KEY FEATURES:

📱 SMS & MMS Messaging
• Send and receive text messages from your computer
• Full MMS support with images and group messaging
• Message search and conversation history
• Schedule messages for later

📞 Phone Calls
• Make and receive calls from your desktop
• See caller ID and call history
• Hands-free calling with your computer's mic and speakers

🔔 Notification Mirroring
• See all phone notifications on your desktop
• Reply directly from notification
• Filter which apps to mirror

📋 Clipboard Sync
• Copy on phone, paste on computer (and vice versa)
• Automatic sync across devices

📸 Photo Sync
• Access recent photos from your phone
• Quick transfer without cables

🔒 Security & Privacy
• End-to-end encryption for all data
• Secure Firebase authentication
• No data stored on external servers
• Open source code for security audits

REQUIREMENTS:
• Android 8.0 or higher
• macOS 14.0+ or Windows 10+ (desktop app)
• Internet connection on both devices

FREE TO USE:
The Android app is completely free. Desktop apps include a 7-day free trial, then $3.99/month or $29.99/year.

⚠️ ANTIVIRUS NOTICE:
Some security software may flag SyncFlow due to the permissions needed for messaging features. This is a common false positive - SyncFlow is legitimate software that uses the same permissions as WhatsApp and Signal. Your data is encrypted and never shared externally.

PERMISSIONS EXPLAINED:
• SMS: To read and send messages (standard for messaging apps)
• Phone: To handle calls and identify numbers (required for calls)
• Contacts: To show contact names instead of just numbers
• Camera/Microphone: For video calling features (only when you use them)
• Storage: To access photos for MMS attachments
• Notifications: To mirror notifications to desktop
• Internet: To securely sync with your desktop (HTTPS only)
```

#### Graphics Assets

| Asset | Dimensions | Format |
|-------|------------|--------|
| App icon | 512 x 512 px | PNG (32-bit) |
| Feature graphic | 1024 x 500 px | PNG or JPEG |
| Phone screenshots | 16:9 or 9:16 | PNG or JPEG |
| Tablet screenshots (7") | 16:9 or 9:16 | PNG or JPEG |
| Tablet screenshots (10") | 16:9 or 9:16 | PNG or JPEG |

**Required Screenshots:**
- Minimum 2, maximum 8 per device type
- Show key features: messaging, calls, settings
- Add captions highlighting features

### 4. App Content

#### Content Rating

1. Go to "App content" > "Content rating"
2. Complete IARC questionnaire:
   - Violence: None
   - Sexual content: None
   - Language: None
   - Controlled substances: None
   - User interaction: Yes (messaging)

#### Data Safety

1. Go to "App content" > "Data safety"
2. Declare data collected:

| Data Type | Collected | Shared | Purpose |
|-----------|-----------|--------|---------|
| Phone number | Yes | No | App functionality |
| SMS messages | Yes | No | App functionality |
| Call logs | Yes | No | App functionality |
| Contacts | Yes | No | App functionality |
| Device ID | Yes | No | Analytics |

3. Security practices:
   - Data encrypted in transit: Yes
   - Data can be deleted: Yes (provide instructions)

#### Ads Declaration

- Select "No, my app does not contain ads"

#### App Category

- Category: **Communication**
- Tags: messaging, sms, phone, sync, desktop

### 5. App Release

#### Generate Signed APK/AAB

```bash
# In project root
./gradlew bundleRelease
```

The AAB will be at: `app/build/outputs/bundle/release/app-release.aab`

#### Signing Configuration

1. Create keystore (if not exists):
```bash
keytool -genkey -v -keystore syncflow-release.keystore \
  -alias syncflow -keyalg RSA -keysize 2048 -validity 10000
```

2. Add to `app/build.gradle.kts`:
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("syncflow-release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "syncflow"
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

#### Upload to Play Console

1. Go to "Release" > "Production"
2. Click "Create new release"
3. Upload AAB file
4. Add release notes
5. Review and roll out

### 6. Pricing & Distribution

1. Go to "Monetization" > "App pricing"
2. Select **Free**
3. Countries: Select all (or specific regions)

### 7. Review Checklist

- [ ] App icon uploaded
- [ ] Feature graphic uploaded
- [ ] At least 2 phone screenshots
- [ ] Short and full descriptions
- [ ] Privacy policy URL
- [ ] Content rating completed
- [ ] Data safety completed
- [ ] Target audience selected (13+)
- [ ] App signed with release key
- [ ] AAB uploaded

---

## Mac App Store (macOS)

### 1. Developer Account Setup

1. Enroll in [Apple Developer Program](https://developer.apple.com/programs/) ($99/year)
2. Accept agreements in App Store Connect
3. Set up banking info for paid apps

### 2. App Store Connect Setup

1. Go to [App Store Connect](https://appstoreconnect.apple.com)
2. Click "My Apps" > "+" > "New App"
3. Fill in:
   - Platform: macOS
   - Name: `SyncFlow`
   - Primary language: English
   - Bundle ID: `com.syncflow.mac`
   - SKU: `syncflow-mac-001`

### 3. App Information

#### General Information

| Field | Value |
|-------|-------|
| Name | SyncFlow |
| Subtitle | Android SMS & Calls on Mac |
| Category | Utilities |
| Secondary Category | Productivity |
| Content Rights | Does not contain third-party content |

#### Age Rating

Complete the questionnaire:
- No objectionable content
- Unrestricted web access: No
- Gambling: No
- Result: **4+**

### 4. Pricing and Availability

#### Subscription Setup

1. Go to "Features" > "In-App Purchases" > "Manage"
2. Click "+" to create subscription group: `SyncFlow Pro`

**Create Subscriptions:**

| Reference Name | Product ID | Duration | Price |
|----------------|------------|----------|-------|
| Monthly | com.syncflow.subscription.monthly | 1 Month | $2.99 |
| Yearly | com.syncflow.subscription.yearly | 1 Year | $19.99 |
| Lifetime | com.syncflow.lifetime | Non-consumable | $59.99 |

3. For each subscription, add:
   - Display name
   - Description
   - App Store localization

#### Pricing

1. Base app: **Free**
2. In-App Purchases: As configured above
3. Availability: All territories

### 5. App Store Listing

#### Screenshots

| Device | Size | Required |
|--------|------|----------|
| Mac | 1280 x 800 px (min) | Yes |
| Mac | 1440 x 900 px | Recommended |
| Mac | 2560 x 1600 px | Recommended |
| Mac | 2880 x 1800 px | Recommended |

**Screenshot Requirements:**
- PNG or JPEG
- No alpha transparency
- Minimum 3 screenshots
- Show: pairing, messaging, calls, settings

#### App Preview (Optional)

- 15-30 second video
- 1920 x 1080 or higher
- Shows app functionality

#### Description

**Promotional Text (170 chars):**
```
Seamlessly access your Android phone's messages, calls, and notifications from your Mac. Free 7-day trial!
```

**Description:**
```
SyncFlow brings your Android phone to your Mac. Send texts, make calls, and stay connected without touching your phone.

MESSAGING
• Send and receive SMS & MMS from your Mac
• Full conversation history synced in real-time
• Search through all your messages
• Schedule messages for later delivery
• Quick replies from notifications

PHONE CALLS
• Make and receive calls using your Mac
• See incoming caller ID
• Full call history
• Use your Mac's microphone and speakers

NOTIFICATIONS
• Mirror all phone notifications to your Mac
• Reply directly from notification banners
• Choose which apps to mirror

CLIPBOARD SYNC
• Copy on your phone, paste on your Mac
• Works both ways automatically

PHOTO ACCESS
• View recent photos from your phone
• Quick access without cables

SECURITY FIRST
• End-to-end encryption for all data
• Secure pairing with QR code
• Your data never stored on external servers

REQUIREMENTS
• Android 8.0+ phone with SyncFlow app (free)
• macOS 14.0 or later
• Both devices connected to internet

PRICING
• 7-day free trial with full features
• $3.99/month or $29.99/year (save 37%)
• $99.99 lifetime option available

Download the free Android companion app from Google Play to get started!
```

**Keywords (100 chars max):**
```
sms,text,message,android,phone,call,sync,notification,clipboard,desktop
```

**Support URL:** `https://syncflow.app/support`

**Privacy Policy URL:** `https://syncflow.app/privacy`

### 6. Build & Upload

#### Xcode Configuration

1. **Signing & Capabilities:**
   - Team: Your Developer Team
   - Signing Certificate: Distribution
   - Provisioning Profile: App Store

2. **Required Capabilities:**
   - App Sandbox: Yes
   - Network (Outgoing): Yes
   - In-App Purchase: Yes

3. **Info.plist Requirements:**
   ```xml
   <key>ITSAppUsesNonExemptEncryption</key>
   <false/>
   ```

#### Archive & Upload

1. In Xcode: Product > Archive
2. Window > Organizer
3. Select archive > "Distribute App"
4. Choose "App Store Connect"
5. Upload

**Or use command line:**
```bash
# Build archive
xcodebuild -workspace SyncFlowMac.xcworkspace \
  -scheme SyncFlowMac \
  -configuration Release \
  -archivePath build/SyncFlowMac.xcarchive \
  archive

# Export for App Store
xcodebuild -exportArchive \
  -archivePath build/SyncFlowMac.xcarchive \
  -exportOptionsPlist ExportOptions.plist \
  -exportPath build/AppStore
```

**ExportOptions.plist:**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>app-store</string>
    <key>teamID</key>
    <string>YOUR_TEAM_ID</string>
    <key>uploadSymbols</key>
    <true/>
    <key>uploadBitcode</key>
    <false/>
</dict>
</plist>
```

### 7. App Review Information

**Contact Information:**
- First name
- Last name
- Phone number
- Email

**Demo Account (if needed):**
- Not required for SyncFlow (uses QR pairing)

**Notes for Review:**
```
SyncFlow requires an Android phone with our companion app installed to function.
The app connects to Android devices via Firebase for real-time synchronization.

To test:
1. Install SyncFlow Android app from Google Play on an Android phone
2. Open Mac app and scan the QR code shown with your Android
3. Messages and features will sync automatically

No demo account is needed as pairing is done via QR code.
The app uses a 7-day free trial - no payment required for testing.
```

### 8. Submit for Review

1. Go to App Store Connect > Your App
2. Select the build you uploaded
3. Complete all required fields
4. Click "Add for Review"
5. Submit

**Review Timeline:**
- Initial review: 24-48 hours typically
- May take longer if issues found

### 9. Review Checklist

- [ ] App icon (1024x1024)
- [ ] At least 3 Mac screenshots
- [ ] Description and keywords
- [ ] Privacy policy URL
- [ ] Support URL
- [ ] Age rating completed
- [ ] In-App Purchases configured
- [ ] Build uploaded via Xcode
- [ ] Contact info for review team
- [ ] Review notes provided

---

## Post-Submission

### After Approval

1. **Monitor Reviews:**
   - Respond to user reviews promptly
   - Address common issues in updates

2. **Analytics:**
   - Google Play Console: Track installs, crashes, ratings
   - App Store Connect: Track downloads, proceeds, crashes

3. **Updates:**
   - Maintain regular update cadence
   - Fix bugs promptly
   - Add requested features

### Common Rejection Reasons

**Google Play:**
- Missing privacy policy
- Incorrect permissions declared
- Policy violations (spam, deceptive)
- Broken functionality

**Mac App Store:**
- Crashes on launch
- Missing required metadata
- Sandbox violations
- Guideline 4.2 (minimum functionality)
- In-App Purchase issues

### Support Resources

- [Google Play Console Help](https://support.google.com/googleplay/android-developer)
- [App Store Connect Help](https://developer.apple.com/app-store-connect/)
- [App Store Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- [Google Play Policies](https://play.google.com/about/developer-content-policy/)

---

## Quick Reference: Product IDs

| Platform | Product | ID |
|----------|---------|-----|
| macOS | Monthly | com.syncflow.subscription.monthly |
| macOS | Yearly | com.syncflow.subscription.yearly |
| macOS | Lifetime | com.syncflow.lifetime |
| Android | N/A | Free app |

---

*Last updated: January 2026*
