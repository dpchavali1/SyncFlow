# Quick Start: Distribution Setup

## 🎯 Goal
Get your macOS and Android apps packaged, signed, and ready for download from sfweb.app

---

## ⚡ Quick Setup (30 minutes)

### Prerequisites Checklist

#### For macOS:
- [ ] Mac running macOS 13+ with Xcode installed
- [ ] Apple Developer account ($99/year) - **Sign up at:** https://developer.apple.com/programs/
- [ ] Developer ID certificate installed (follow guide below)

#### For Android:
- [ ] Java JDK 11+ installed
- [ ] Android Studio or Android SDK installed
- [ ] Keystore file created (we'll create this)

---

## 📋 Step-by-Step Quick Guide

### PART 1: Apple Developer Setup (15 min)

1. **Join Apple Developer Program**
   - Go to: https://developer.apple.com/programs/
   - Click "Enroll" and pay $99/year
   - Wait 24-48 hours for approval

2. **Create Certificates**
   - Open Keychain Access → Certificate Assistant → Request Certificate
   - Save the `.certSigningRequest` file
   - Go to: https://developer.apple.com/account
   - Certificates → + → Developer ID Application
   - Upload your request, download certificate
   - Double-click to install

3. **Get Your Team ID**
   - Go to: https://developer.apple.com/account
   - Look for "Team ID" at top right
   - Copy this (format: ABC123DEF4)

4. **Create App-Specific Password**
   - Go to: https://appleid.apple.com
   - Security → App-Specific Passwords
   - Generate new password
   - Name it "SyncFlow Notarization"
   - Copy the password (xxxx-xxxx-xxxx-xxxx)
   - Store in keychain:
   ```bash
   xcrun notarytool store-credentials \
     --apple-id "your@email.com" \
     --team-id "YOUR_TEAM_ID" \
     --password "xxxx-xxxx-xxxx-xxxx"
   ```
   - Name it: `AC_PASSWORD`

### PART 2: Configure Build Scripts (5 min)

1. **Update `scripts/ExportOptions.plist`**
   ```xml
   <key>teamID</key>
   <string>ABC123DEF4</string>  <!-- Your Team ID -->
   ```

2. **Update `scripts/build-macos.sh`**
   ```bash
   DEVELOPER_ID="Developer ID Application: Your Name (ABC123DEF4)"
   APPLE_ID="your@email.com"
   TEAM_ID="ABC123DEF4"
   ```

3. **Create Android Keystore**
   ```bash
   cd app
   keytool -genkey -v \
     -keystore syncflow-upload.keystore \
     -alias syncflow-upload \
     -keyalg RSA \
     -keysize 2048 \
     -validity 10000
   ```
   - Enter password (SAVE THIS!)
   - Fill in your info

4. **Create `app/keystore.properties`**
   ```properties
   storeFile=syncflow-upload.keystore
   storePassword=YOUR_PASSWORD
   keyAlias=syncflow-upload
   keyPassword=YOUR_PASSWORD
   ```

### PART 3: Build Apps (10 min)

1. **Build macOS App**
   ```bash
   cd /Users/dchavali/GitHub/SyncFlow
   ./scripts/build-macos.sh
   ```

   **Wait:** 5-15 minutes for notarization

   **Output:** `SyncFlow-1.0.0.dmg` + checksum

2. **Build Android App**
   ```bash
   cd /Users/dchavali/GitHub/SyncFlow
   ./scripts/build-android.sh
   ```

   **Output:** `SyncFlow-1.0.0.apk` + checksum

### PART 4: Deploy Download Page (5 min)

1. **Update Download Page**

   Edit `web/app/download/page.tsx`:
   ```typescript
   const version = '1.0.0'
   const fileSize = '45 MB'  // From your actual DMG
   const sha256 = 'abc123...'  // From .sha256 file
   ```

2. **Upload Build Files**

   Create `public/downloads/` in your web folder:
   ```bash
   mkdir -p web/public/downloads
   cp SyncFlow-1.0.0.dmg web/public/downloads/
   cp SyncFlow-1.0.0.apk web/public/downloads/
   ```

3. **Test Locally**
   ```bash
   cd web
   npm run dev
   ```
   Visit: http://localhost:3000/download

4. **Deploy to Production**
   ```bash
   cd web
   vercel --prod  # or your deployment method
   ```

---

## ✅ Verification Checklist

### macOS:
- [ ] DMG file created
- [ ] Code signed (check with: `codesign --verify SyncFlow-1.0.0.dmg`)
- [ ] Notarized (check with: `xcrun stapler validate SyncFlow-1.0.0.dmg`)
- [ ] SHA-256 checksum generated
- [ ] DMG opens on test Mac without security warnings

### Android:
- [ ] APK file created
- [ ] APK signed (check with: `jarsigner -verify SyncFlow-1.0.0.apk`)
- [ ] SHA-256 checksum generated
- [ ] APK installs on test Android device

### Web:
- [ ] Download page accessible at sfweb.app/download
- [ ] Privacy policy at sfweb.app/privacy
- [ ] Terms at sfweb.app/terms
- [ ] Download links work
- [ ] Checksums match

---

## 🚨 Common Issues & Solutions

### "Code signing failed"
**Solution:** Verify your certificate is installed
```bash
security find-identity -v -p codesigning
```
Should show: "Developer ID Application: Your Name"

### "Notarization failed"
**Solution:** Check the logs
```bash
xcrun notarytool log --apple-id your@email.com --team-id YOUR_TEAM_ID
```

### "Keystore not found" (Android)
**Solution:** Ensure `keystore.properties` exists in `app/` folder

### "App won't open" on user's Mac
**Solution:** Make sure notarization completed successfully

---

## 📚 Full Documentation

For detailed information, see: [`DISTRIBUTION_GUIDE.md`](./DISTRIBUTION_GUIDE.md)

---

## 🎉 Ready to Launch!

Once you complete these steps:

1. ✅ Share download link on social media
2. ✅ Post on Product Hunt
3. ✅ Submit to Reddit (r/macapps, r/Android)
4. ✅ Email friends and family for beta testing

---

## 💡 Tips

**For faster builds:**
- Cache Gradle dependencies: `export GRADLE_USER_HOME=~/.gradle`
- Use Xcode build cache: Don't clean unless needed

**For updates:**
- Increment version numbers in scripts
- Update download page version
- Keep old versions available for a week

**For testing:**
- Test DMG on fresh Mac (use a VM if possible)
- Test APK on multiple Android versions
- Verify deep links work
- Test pairing between devices

---

## 📞 Need Help?

If you get stuck:

1. Check [`DISTRIBUTION_GUIDE.md`](./DISTRIBUTION_GUIDE.md) for detailed steps
2. Check Apple Developer forums: https://developer.apple.com/forums/
3. Check Android developer docs: https://developer.android.com/studio/publish

---

## 🔄 Regular Updates

**Monthly:**
- Check for security updates
- Review crash reports
- Update dependencies

**Quarterly:**
- Renew certificates if needed
- Review storage/bandwidth costs
- Plan new features based on feedback

---

**Estimated Total Time:** 30-45 minutes (excluding Apple approval wait time)

Good luck! 🚀
