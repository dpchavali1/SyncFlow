# Admin Setup by Email - Fix for Changing UID Issue

## Problem: UID Changes When Pairing

When you pair your app, your Firebase UID might change, making it hard to keep admin status. This guide solves that by using your **email as the stable identifier**.

---

## Solution: Email-Based Admin Setup

### How It Works

Instead of using your UID (which can change), we use your email address. Your email stays the same across all pairing sessions.

```
Your Email (STABLE) → Firebase finds your user by email → Sets admin claims
```

---

## Setup (1 Step Only)

### Step 1: Run This Command

```bash
cd /Users/dchavali/GitHub/SyncFlow/scripts
node set-admin-by-email.js your-email@gmail.com
```

Replace `your-email@gmail.com` with your actual email.

**Example**:
```bash
node set-admin-by-email.js admin@gmail.com
```

**Expected Output**:
```
🔧 Setting admin claims for email: admin@gmail.com
✅ Found user with UID: abc123def456...
✅ Admin claims set successfully!
✅ Created admin user profile in database

👤 User Details:
   Email: admin@gmail.com
   UID: abc123def456...

💡 Next steps:
   1. Go to https://sfweb.app
   2. Sign in with: admin@gmail.com
   3. Go to /admin/cleanup
   4. Click the "Testing" tab
   5. Set user plans for testing

⚠️  Important: Always sign up with this email (admin@gmail.com) to keep admin access
```

---

## Requirements

Your email must already exist in Firebase Authentication. If it doesn't:

### Option A: Sign Up Through App (Recommended)
```
1. Go to https://sfweb.app
2. Click "Sign Up"
3. Enter your email and complete signup
4. Complete pairing if prompted
5. Then run the script above
```

### Option B: Create Manually in Firebase Console
```
1. Go to Firebase Console → Authentication → Users
2. Click "Create user"
3. Enter your email and a password
4. Click "Create"
5. Then run the script above
```

---

## Key Difference: Email vs UID

### Before (UID-based - Problem)
```
Sign up → UID: xyz123
Pair device → UID: abc456 (CHANGED!)
Script fails because UID changed ❌
```

### After (Email-based - Fixed)
```
Sign up → Email: admin@gmail.com, UID: xyz123
Pair device → Email: admin@gmail.com, UID: abc456 (changed)
Script works because email stays same ✅
```

---

## Important: Always Use Same Email

### ✅ DO THIS:
- Always sign up with `admin@gmail.com`
- Pair with same email
- Email stays stable across all sessions

### ❌ DON'T DO THIS:
- Sign up as `admin@gmail.com`
- Later pair as `different@gmail.com`
- This creates separate accounts

---

## What the Script Does

1. ✅ Finds your user by email in Firebase Authentication
2. ✅ Gets your current UID
3. ✅ Sets admin claims (`admin: true`)
4. ✅ Creates/updates user profile in database
5. ✅ Prints your UID for reference

---

## After Running Script

You can now:

1. **Access Admin Dashboard**
   - Go to https://sfweb.app
   - Sign in with your email
   - Navigate to `/admin/cleanup`
   - See the "Testing" tab

2. **Test User Plans Anytime**
   - Even after pairing again
   - Email stays stable
   - Admin access persists

3. **Create Test Users**
   - Use other emails to create test accounts
   - Switch their plans in Testing tab
   - Test free → paid flows

---

## Troubleshooting

### Error: "User with email not found"
**Solution**: User doesn't exist in Firebase yet
```bash
# Sign up first at https://sfweb.app
# Use your email
# Then run the script again
```

### UID Changed After Pairing
**This is expected!** Email-based setup handles this automatically
```bash
# No need to run script again
# Email is stable, admin access persists
# Just sign in with your email and it works
```

### Can't See Testing Tab
**Solution**:
1. Refresh page (Cmd+R or Ctrl+R)
2. Log out and back in
3. Check browser console (F12) for errors

### Different UID Each Time I Log In
This is normal! Email-based setup doesn't care about UID changing
```
Session 1: admin@gmail.com → UID: xyz123
Session 2: admin@gmail.com → UID: abc456
Session 3: admin@gmail.com → UID: def789

All have admin claims because email is consistent ✅
```

---

## Multiple Admin Users?

If you want multiple admins:

```bash
# First admin
node set-admin-by-email.js admin1@gmail.com

# Second admin
node set-admin-by-email.js admin2@gmail.com

# Third admin
node set-admin-by-email.js admin3@gmail.com
```

Each email automatically becomes admin!

---

## Security Notes

⚠️ **Keep Your Email Secret**
- Don't share your admin email publicly
- Only you should use it for admin access
- Other test users should use different emails

✅ **Why Email is Better**
- Stable across pairing sessions
- No UID chasing
- Works with your app's pairing system
- Easier to manage multiple admins

---

## Command Reference

### Set admin by email
```bash
node scripts/set-admin-by-email.js your-email@gmail.com
```

### Check if user exists in Firebase
```
Firebase Console → Authentication → Users → Search by email
```

### Verify admin claims were set
```
Firebase Console → Authentication → Users → Click user → Custom claims
Should show: {"admin": true}
```

---

## What Happens Behind the Scenes

```javascript
// 1. Find user by email
const userRecord = await admin.auth().getUserByEmail(email);
const uid = userRecord.uid;

// 2. Set admin claims
await admin.auth().setCustomUserClaims(uid, { admin: true });

// 3. Update database profile
await db.ref(`users/${uid}`).update({
  isAdmin: true,
  adminUser: true
});
```

---

## Example Workflow

```
1. You: "I want to be admin"

2. Me: "Sign up at sfweb.app with admin@gmail.com"
   You: ✅ Done, UID is xyz123

3. Me: "Run: node set-admin-by-email.js admin@gmail.com"
   You: ✅ Done, script found you by email

4. You: "I'm pairing with my phone"
   System: Creates new session, UID changes to abc456
   (But email is still admin@gmail.com)

5. You: "I signed in with admin@gmail.com on desktop"
   System: ✅ Found admin email, grants admin access
   You: Can access /admin/cleanup Testing tab

6. You: "Pair again with tablet"
   System: Creates another session, UID changes to def789
   (But email is still admin@gmail.com)

7. You: "Sign in on tablet"
   System: ✅ Found admin email, grants admin access
   You: Can access /admin/cleanup on tablet too!
```

---

## Summary

**Old way** (broken by pairing):
- Get UID from Firebase
- Run script with UID
- Pair device → UID changes → Script breaks ❌

**New way** (works with pairing):
- Use your email
- Run script with email
- Pair device → Email stays same → Still works ✅

---

## That's It!

One simple command with your email, and you're admin forever (across all pairing sessions).

```bash
node set-admin-by-email.js your-email@gmail.com
```

Done! 🎉
