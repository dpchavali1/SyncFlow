# Admin Testing Framework - Troubleshooting Guide

## Error: "Admin role required"

**Problem**: You see "You need admin role to modify user plans"

**Causes**:
1. Setup script wasn't run
2. Script failed silently
3. Firebase token didn't refresh after setup

**Solutions**:
```bash
# Step 1: Check your UID exists in Firebase Console
# Go to: Firebase Console → Authentication → Users → Look for your email

# Step 2: Run the setup script again
cd /Users/dchavali/GitHub/SyncFlow/scripts
node set-admin-user.js YOUR_UID YOUR_EMAIL

# Step 3: Refresh your token
# - Log out of https://sfweb.app
# - Close the browser
# - Wait 30 seconds
# - Log back in

# Step 4: Try again
# Go to /admin/cleanup → Testing tab
```

**What to check**:
- [ ] UID is correct format (long alphanumeric string)
- [ ] Email matches what's in Firebase
- [ ] Script ran without errors (check console output)
- [ ] You refreshed/relogged after script

---

## Error: "serviceAccountKey.json not found"

**Problem**: Script says "Cannot find module './serviceAccountKey.json'"

**Solutions**:

1. **Check file exists**:
   ```bash
   ls -la /Users/dchavali/GitHub/SyncFlow/scripts/serviceAccountKey.json
   ```
   If not found, download it:
   - Firebase Console → Project Settings → Service Accounts
   - Click "Generate New Private Key"
   - Save as `serviceAccountKey.json` in `/scripts` folder

2. **Check file location**:
   ```bash
   # Right location
   /Users/dchavali/GitHub/SyncFlow/scripts/serviceAccountKey.json ✅

   # Wrong locations
   ~/Downloads/serviceAccountKey.json ❌
   /Users/dchavali/GitHub/SyncFlow/serviceAccountKey.json ❌
   ```

3. **Copy to correct location**:
   ```bash
   cp ~/Downloads/serviceAccountKey.json /Users/dchavali/GitHub/SyncFlow/scripts/
   ```

---

## Error: "User does not exist"

**Problem**: "User does not exist" when trying to set a plan

**Causes**:
1. Wrong User ID format
2. Typo in User ID
3. User was deleted
4. User never signed up

**Solutions**:

1. **Get correct User ID**:
   - Go to https://sfweb.app
   - Make sure you're logged in
   - Go to `/admin/cleanup` → Testing tab
   - Look for "User ID" field hint or check Firebase Console

2. **Verify format**:
   - User IDs are long alphanumeric strings
   - Example: `8iHnek4WaEcE3qp4PhNtpKs1P0l2`
   - Should be 20+ characters

3. **Double-check spelling**:
   ```bash
   # Copy-paste from Firebase Console → Authentication → Users
   # Don't type manually (easy to make typos)
   ```

4. **Create test user if needed**:
   - Go to https://sfweb.app
   - Sign up as a new test user
   - Complete pairing (if prompted)
   - Get their UID from Firebase Console
   - Use that UID in Testing tab

---

## Error: "Rate limit exceeded"

**Problem**: "Rate limit exceeded. Maximum 10 requests per minute."

**Why**: Admin API limits to 10 requests/minute to prevent abuse

**Solution**: Wait 60 seconds and try again
```bash
# Option 1: Wait
sleep 60  # Wait 1 minute

# Option 2: Check what you're doing
# - Are you clicking "Set User Plan" multiple times?
# - Are you running scripts repeatedly?
# Just wait, then try again
```

---

## Error: "Invalid Firebase token"

**Problem**: API returns "Invalid or expired token"

**Causes**:
1. Token expired
2. You're not logged in
3. Browser cache issue

**Solutions**:

1. **Refresh token**:
   - Refresh the page (Cmd+R on Mac, Ctrl+R on Windows)
   - Wait 3 seconds
   - Try again

2. **Log out and back in**:
   - Go to https://sfweb.app
   - Click logout (top-right)
   - Log back in
   - Navigate back to `/admin/cleanup`

3. **Clear browser cache**:
   - Press Cmd+Shift+R (Mac) or Ctrl+Shift+R (Windows)
   - This clears cache and refreshes

4. **Try in private window**:
   - Open private/incognito window
   - Go to https://sfweb.app
   - Log in and try again

---

## Error: "Cannot find module 'firebase-admin'"

**Problem**: Script says "Error: Cannot find module 'firebase-admin'"

**Causes**:
1. npm dependencies not installed
2. Running from wrong directory

**Solutions**:

```bash
# Install firebase-admin globally
npm install -g firebase-admin

# OR install in scripts folder
cd /Users/dchavali/GitHub/SyncFlow/scripts
npm install firebase-admin
node create-admin-user.js
```

---

## Testing Tab Not Visible

**Problem**: You can't find the Testing tab in `/admin/cleanup`

**Causes**:
1. You're not logged in as admin
2. You're on wrong page
3. Page hasn't loaded fully
4. Wrong browser tab

**Solutions**:

1. **Check you're on correct page**:
   - URL should be: `https://sfweb.app/admin/cleanup` ✅
   - NOT: `https://sfweb.app/admin` ❌

2. **Check tabs are visible**:
   - Should see tabs: Overview | Users | Data | Costs | **Testing**
   - If only see "Overview", you're not logged in as admin

3. **Refresh page**:
   ```
   Cmd+R (Mac) or Ctrl+R (Windows)
   Wait 3 seconds
   Look for "Testing" tab
   ```

4. **Log out and back in**:
   - Click "Logout" (top-right)
   - Log back in
   - Go to `/admin/cleanup`

5. **Check browser console**:
   - Press F12 to open Developer Tools
   - Click Console tab
   - Look for red errors
   - Screenshot and check troubleshooting docs

---

## API Returns "Forbidden" (403)

**Problem**: "Forbidden. Admin role required to modify user plans."

**This is expected if**:
- ✅ User making request doesn't have admin claims
- ✅ You just promoted a user and haven't refreshed

**Solution**: Log out, close browser, wait 10 seconds, log back in

```bash
# Detailed fix:
1. Sign out of https://sfweb.app (top-right)
2. Close browser completely (Command+Q on Mac)
3. Wait 10 seconds
4. Open browser again
5. Go to https://sfweb.app
6. Log in with your email
7. Go to /admin/cleanup
8. Try again
```

---

## Auto Cleanup Not Running

**Problem**: "2am cleanup did not run last night"

**Info**: Auto cleanup runs at 2 AM UTC daily via Vercel Cron

**Verification**:
1. Check deployment status: https://vercel.com/dashboard
2. Look for failed deployments
3. Check function logs in Vercel

**Manual trigger**:
```bash
# Run cleanup manually to test
curl -X POST https://sfweb.app/api/cleanup/auto
```

**Expected response**:
```json
{
  "success": true,
  "type": "AUTO",
  "results": { ... },
  "message": "Automatic cleanup completed successfully"
}
```

---

## Debugging Checklist

When something doesn't work, go through this:

- [ ] Logged in as admin user?
- [ ] Testing tab visible?
- [ ] User ID format correct (long alphanumeric)?
- [ ] Plan selected (free/monthly/yearly/lifetime)?
- [ ] Days valid is 1-365?
- [ ] Firebase token valid (refresh page)?
- [ ] No rate limit (wait 60 sec)?
- [ ] Browser console shows no errors (F12)?
- [ ] Firestore/Database updated (check Firebase Console)?

---

## Still Stuck?

1. **Check detailed docs**:
   - `ADMIN_SETUP_GUIDE.md` - Full setup walkthrough
   - `IMPLEMENTATION_COMPLETE.md` - Architecture details

2. **Review browser console**:
   - Press F12
   - Click Console tab
   - Look for red error messages
   - Take screenshot

3. **Check Firebase Console**:
   - Authentication → Users (see all users)
   - Realtime Database (check plan updates)
   - Functions (check logs)

4. **Common solutions**:
   - Refresh browser page
   - Log out and back in
   - Clear browser cache (Cmd+Shift+R)
   - Try in private window
   - Wait 60 seconds and retry

---

## Success Indicators

✅ **You know it's working when**:
- [ ] You see "Testing" tab with ⚡ icon
- [ ] You can enter a User ID
- [ ] You can select a plan
- [ ] Click "Set User Plan" shows success message
- [ ] Firebase Console shows updated plan
- [ ] Test user logs in and sees correct plan

---

## Need Help?

- Check the error message carefully (it usually tells you what's wrong)
- Look at script console output (has error details)
- Review browser developer console (F12)
- Search this guide for the error
- Check Firebase Console logs
- Review `ADMIN_SETUP_GUIDE.md`

Good luck! 🎯
