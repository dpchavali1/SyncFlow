# 🔥 Get Fresh Firebase Configuration Files

## For Android

1. **Go to Firebase Console:**
   ```
   https://console.firebase.google.com/project/syncflow-6980e/settings/general
   ```

2. **Scroll down to "Your apps" section**

3. **Find your Android app** (package name: `com.phoneintegration.app`)
   - If you don't see it, click "Add app" → Android

4. **Click the gear icon ⚙️ next to your Android app**

5. **Scroll down and click "Download google-services.json"**

6. **Save the file to:**
   ```
   /Users/dchavali/Documents/GitHub/SyncFlow/app/google-services.json
   ```

---

## For iOS/macOS

1. **Same Firebase Console:**
   ```
   https://console.firebase.google.com/project/syncflow-6980e/settings/general
   ```

2. **In "Your apps" section:**

3. **Find your iOS app** (bundle ID: `com.syncflow.mac`)
   - If you don't see it, click "Add app" → iOS
   - Enter bundle ID: `com.syncflow.mac`
   - Register app

4. **Click the gear icon ⚙️ next to your iOS app**

5. **Scroll down and click "Download GoogleService-Info.plist"**

6. **Save the file to:**
   ```
   /Users/dchavali/Documents/GitHub/SyncFlow/SyncFlowMac/SyncFlowMac/Resources/GoogleService-Info.plist
   ```

---

## For Web

1. **Same Firebase Console**

2. **Find your Web app** (or create one if missing)
   - Click "Add app" → Web
   - Nickname: "SyncFlow Web"

3. **Copy the configuration:**
   ```javascript
   const firebaseConfig = {
     apiKey: "...",
     authDomain: "...",
     databaseURL: "...",
     projectId: "...",
     storageBucket: "...",
     messagingSenderId: "...",
     appId: "..."
   };
   ```

4. **Create/Update `.env.local`:**
   ```bash
   cd /Users/dchavali/Documents/GitHub/SyncFlow/web

   cat > .env.local << 'EOF'
   NEXT_PUBLIC_FIREBASE_API_KEY=your_api_key_here
   NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=syncflow-6980e.firebaseapp.com
   NEXT_PUBLIC_FIREBASE_PROJECT_ID=syncflow-6980e
   NEXT_PUBLIC_FIREBASE_DATABASE_URL=https://syncflow-6980e-default-rtdb.firebaseio.com
   NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET=syncflow-6980e.appspot.com
   NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID=your_sender_id
   NEXT_PUBLIC_FIREBASE_APP_ID=your_app_id
   EOF
   ```

---

## Verification

After downloading:

```bash
# Check Android config exists
ls -l app/google-services.json

# Check macOS config exists
ls -l SyncFlowMac/SyncFlowMac/Resources/GoogleService-Info.plist

# Check web config exists
ls -l web/.env.local
```

---

## Important Notes

⚠️ **These files contain API keys** - they will NOT be committed to git (protected by .gitignore)

✅ **Why this works:**
- Firebase automatically generates fresh API keys for new downloads
- Old keys in GitHub history become less useful
- With Firebase Security Rules active, unauthorized access is blocked anyway

✅ **Best practice:**
- Keep these files secure locally
- Never commit them to git
- Share via secure channels if needed

---

## After Getting New Files

1. **Rebuild Android:**
   ```bash
   ./gradlew clean assembleDebug
   ./gradlew installDebug
   ```

2. **Rebuild macOS:**
   - Open in Xcode
   - Product → Clean Build Folder
   - Product → Build

3. **Restart Web:**
   ```bash
   cd web
   npm run dev
   ```
