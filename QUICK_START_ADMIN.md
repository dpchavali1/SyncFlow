# Quick Start: Admin Testing Framework

## In 5 Minutes

### 1️⃣ Get Firebase Service Account (1 min)
```
Firebase Console → Your Project → ⚙️ Project Settings
→ Service Accounts → Generate New Private Key
→ Save as serviceAccountKey.json in /scripts folder
```

### 2️⃣ Run Setup (1 min)

**If you haven't signed up yet:**
```bash
cd /Users/dchavali/GitHub/SyncFlow/scripts
# Edit email/password in create-admin-user.js first!
node create-admin-user.js
```

**If you already signed up:**
```bash
# Get your UID from Firebase Console → Authentication → Users
cd /Users/dchavali/GitHub/SyncFlow/scripts
node set-admin-user.js YOUR_UID YOUR_EMAIL
```

### 3️⃣ Test It (3 min)
1. Go to https://sfweb.app
2. Sign in with your credentials
3. Navigate to `/admin/cleanup`
4. Click **Testing** tab (⚡ icon)
5. Enter user ID, select plan, click "Set User Plan"

---

## Testing Tab Features

```
User ID:        [Enter user Firebase UID]
Plan:           [free | monthly | yearly | lifetime]
Days Valid:     [1-365]

[Set User Plan] → See real-time success/error messages
```

### Quick Test Cases

| Test | Steps |
|------|-------|
| Free Trial | Set to `free`, `days=1` |
| Monthly Sub | Set to `monthly`, `days=30` |
| Upgrade | Change `free` → `monthly` |
| Lifetime | Set to `lifetime` |

---

## Common Issues

| Issue | Fix |
|-------|-----|
| "Admin role required" | Run setup script again, log out/in |
| "Can't find Testing tab" | Make sure you're logged in, refresh page |
| "User not found" | Verify UID is correct from Firebase Console |
| "serviceAccountKey.json not found" | Save it to `/scripts/serviceAccountKey.json` |

---

## What Each Command Does

### `create-admin-user.js`
Creates a brand new admin user from scratch:
- Creates user in Firebase Authentication
- Sets admin claims
- Creates user profile in database
- Gives lifetime plan access

### `set-admin-user.js`
Converts an existing user to admin:
- Sets admin claims on existing user
- Enables admin dashboard access
- Keeps their existing user data

### API Endpoint: `/api/admin/set-user-plan`
Secures user plan modifications:
- Requires Firebase authentication
- Checks admin role
- Rate limited (10 requests/min)
- Logs all changes

---

## Files Summary

```
/scripts/
  ├── create-admin-user.js     ← Create new admin
  ├── set-admin-user.js        ← Make existing user admin
  └── serviceAccountKey.json   ← Downloaded from Firebase (SECRET!)

/web/app/
  ├── api/admin/set-user-plan/  ← Secured API endpoint
  ├── api/cleanup/auto/         ← Auto cleanup (fixed)
  └── admin/cleanup/            ← Admin dashboard with Testing tab

/
  ├── ADMIN_SETUP_GUIDE.md      ← Detailed instructions
  ├── IMPLEMENTATION_COMPLETE.md ← Architecture docs
  └── QUICK_START_ADMIN.md       ← This file
```

---

## After Setup

You can now:
- ✅ Switch test users between plans instantly
- ✅ Test trial expiration flows
- ✅ Test premium features for paid tiers
- ✅ Verify plan upgrade/downgrade logic
- ✅ Create multiple test scenarios

---

## Next Steps

1. Download serviceAccountKey.json
2. Run setup script (choose Option A or B)
3. Log in and access /admin/cleanup
4. Click Testing tab
5. Switch a test user's plan
6. Start testing!

---

## Troubleshooting Links

- **Full Setup Guide**: `ADMIN_SETUP_GUIDE.md`
- **Architecture Details**: `IMPLEMENTATION_COMPLETE.md`
- **Firebase Docs**: https://firebase.google.com/docs

---

## Support

If stuck:
1. Check browser console (F12)
2. Review script output carefully
3. Verify serviceAccountKey.json location
4. Check ADMIN_SETUP_GUIDE.md troubleshooting

**You're all set! 🚀**
