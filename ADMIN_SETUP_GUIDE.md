# Admin Setup Guide for Testing User Plans

This guide walks you through setting up your Firebase admin account so you can access the Testing tab to switch between free/paid user plans for testing.

## Current Status

You've been set up with two approaches:

1. **Option A**: Create a brand new admin user from scratch
2. **Option B**: Convert your existing user to admin (if you've already signed up via the app)

Choose **Option A** if you haven't signed up yet. Choose **Option B** if you've already created an account.

---

## Option A: Create New Admin User (No Prior Signup Required)

### Step 1: Get Firebase Service Account Key

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select your **SyncFlow** project
3. Click **⚙️ Project Settings** (top-left, next to "Project Overview")
4. Go to **Service Accounts** tab
5. Click **Generate New Private Key** button
6. Save the JSON file as `serviceAccountKey.json`

### Step 2: Place the Key File

Move the downloaded `serviceAccountKey.json` to the scripts directory:

```bash
# Copy the file to the scripts folder
cp ~/Downloads/serviceAccountKey.json /Users/dchavali/GitHub/SyncFlow/scripts/serviceAccountKey.json

# Verify it's there
ls -la /Users/dchavali/GitHub/SyncFlow/scripts/serviceAccountKey.json
```

### Step 3: Edit the Create Script

Edit `/Users/dchavali/GitHub/SyncFlow/scripts/create-admin-user.js`:

```bash
nano /Users/dchavali/GitHub/SyncFlow/scripts/create-admin-user.js
```

Change these lines (around line 11-12):
```javascript
const adminEmail = 'your-admin-email@gmail.com'; // CHANGE THIS
const adminPassword = 'SecurePassword123!'; // CHANGE THIS
```

To your desired admin credentials:
```javascript
const adminEmail = 'your-actual-email@gmail.com'; // Your email
const adminPassword = 'YourSecurePassword123!'; // Your password
```

### Step 4: Run the Script

```bash
cd /Users/dchavali/GitHub/SyncFlow/scripts
node create-admin-user.js
```

**Expected Output**:
```
🔧 Creating admin user: your-email@gmail.com
✅ User created with UID: abc123def456...
✅ Admin claims set for: your-email@gmail.com
✅ User profile created in database

📋 Admin User Details:
   Email: your-email@gmail.com
   Password: YourSecurePassword123!
   UID: abc123def456...

💡 Next steps:
   1. Go to https://sfweb.app
   2. Sign up with: your-email@gmail.com / YourSecurePassword123!
   3. You'll have admin access to /admin/cleanup
```

**⚠️ Save the output!** You need the email/password to log in.

### Step 5: Log In and Access Admin Dashboard

1. Go to https://sfweb.app
2. Sign up with the email and password from the script output
3. Complete the pairing process if prompted
4. Go to `/admin/cleanup` in your browser
5. Look for the **Testing** tab

---

## Option B: Convert Existing User to Admin (Already Signed Up)

### Step 1: Get Your Firebase UID

If you've already signed up through the app:

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select your **SyncFlow** project
3. Go to **Authentication** → **Users**
4. Find your email in the list
5. Click on your user
6. Copy the **User UID** (long string like `abc123def456...`)

### Step 2: Get Firebase Service Account Key

Same as Option A, Step 1:

1. Go to **⚙️ Project Settings**
2. Go to **Service Accounts** tab
3. Click **Generate New Private Key**
4. Save as `serviceAccountKey.json`

### Step 3: Place the Key File

```bash
cp ~/Downloads/serviceAccountKey.json /Users/dchavali/GitHub/SyncFlow/scripts/serviceAccountKey.json
```

### Step 4: Run the Script

```bash
cd /Users/dchavali/GitHub/SyncFlow/scripts
node set-admin-user.js YOUR_UID YOUR_EMAIL
```

Replace:
- `YOUR_UID` with your actual UID from Firebase Console (e.g., `abc123def456`)
- `YOUR_EMAIL` with your email (e.g., `user@gmail.com`)

**Example**:
```bash
node set-admin-user.js 8iHnek4WaEcE3qp4PhNtpKs1P0l2 user@gmail.com
```

**Expected Output**:
```
🔧 Setting admin claims for UID: 8iHnek4WaEcE3qp4PhNtpKs1P0l2
✅ Admin claims set successfully!

👤 User Details:
   UID: 8iHnek4WaEcE3qp4PhNtpKs1P0l2
   Email: user@gmail.com

💡 You can now:
   1. Go to https://sfweb.app/admin/cleanup
   2. Click the "Testing" tab
   3. Set user plans for testing
```

### Step 5: Access Admin Dashboard

1. Log in at https://sfweb.app with your email
2. Go to `/admin/cleanup`
3. Look for the **Testing** tab

---

## Using the Testing Tab

Once you're an admin, you can use the Testing tab to switch user plans:

### Test Users Setup

1. Create test users by signing up multiple times with different emails
2. Copy their **User ID** from the Testing tab's user lookup

### Switch Between Plans

| Plan | Duration | Use Case |
|------|----------|----------|
| **free** | 7 days | Test trial expiration logic |
| **monthly** | 30 days | Test monthly subscription UI |
| **yearly** | 30 days | Test annual subscription |
| **lifetime** | Never expires | Test premium-only features |

### Steps to Change a User's Plan

1. Go to `/admin/cleanup` → **Testing** tab
2. Enter user ID (or search for email)
3. Select desired plan
4. Click **Set User Plan**
5. Confirm in the response that it worked
6. Sign in as that user to verify the plan

---

## Troubleshooting

### "serviceAccountKey.json not found"
- Make sure the file is in `/scripts/serviceAccountKey.json`
- Check the path is correct: `ls /Users/dchavali/GitHub/SyncFlow/scripts/serviceAccountKey.json`

### Script shows "Cannot find module 'firebase-admin'"
- Install dependencies: `npm install -g firebase-admin` or `cd scripts && npm install firebase-admin`

### "User does not exist" when running set-admin-user.js
- Double-check the UID from Firebase Console → Authentication → Users
- Make sure you copied the exact UID (it's a long string)

### Can't access /admin/cleanup after setup
- Make sure you're logged in (check top-right of https://sfweb.app)
- Verify you see "Admin" badge in your profile
- Try refreshing the page (Ctrl+R or Cmd+R)

### Testing tab is empty or shows errors
- Check browser console (F12 → Console tab) for errors
- Make sure your Firebase token was sent with the request
- Try logging out and back in to refresh your token

---

## Security Notes

⚠️ **Keep serviceAccountKey.json secret!**
- Don't commit it to Git
- Don't share it publicly
- Treat it like a password
- After running the scripts, you can delete it

✅ **The admin endpoint has security:**
- Requires Firebase authentication
- Checks admin role
- Rate limited (10 requests/minute)
- All changes logged with who made them
- Input validation on all fields

---

## Next Steps

After setting up your admin account:

1. **Test the free-to-paid flow** by switching a test user from free → monthly
2. **Check trial expiration** by setting a test user's free plan to expire soon
3. **Verify premium features** work for users on paid plans
4. **Test plan upgrades** by switching users from monthly → yearly → lifetime

---

## Architecture Reference

### API Endpoint
- **URL**: `POST /api/admin/set-user-plan`
- **Security**: Firebase ID token required in `Authorization: Bearer <token>` header
- **Rate Limit**: 10 requests per minute
- **Body**:
  ```json
  {
    "userId": "user_firebase_uid",
    "plan": "free|monthly|yearly|lifetime",
    "daysValid": 30
  }
  ```

### Database Updates
When you set a plan, this data is updated:
```json
{
  "plan": "monthly",
  "planExpiresAt": 1234567890000,
  "updatedAt": "server timestamp",
  "lastPlanModifiedBy": "admin_user_id",
  "lastPlanModifiedAt": 1234567890000
}
```

### Audit Trail
All admin actions are logged to console:
```
🔧 [ADMIN ACTION] admin_id set user user_id to monthly plan
```

---

## Support

If you encounter issues:

1. Check the troubleshooting section above
2. Review the browser console (F12)
3. Check `/admin/cleanup` logs section
4. Review the output of the setup scripts carefully

Good luck testing! 🚀
