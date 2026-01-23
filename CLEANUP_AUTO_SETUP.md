# 🤖 Automatic Daily Cleanup Setup Guide

## Overview

SyncFlow now supports **both manual and automatic daily cleanup** of orphaned user accounts and duplicate data.

- **Manual**: Click buttons in `/admin/cleanup` dashboard (anytime)
- **Automatic**: Vercel Cron runs daily at **2 AM UTC** (no GitHub quota used!)

## Why Vercel Cron?

✅ **No GitHub Actions quota** - Your deals scraper can run freely
✅ **Built into Vercel** - Included with free tier
✅ **Simpler setup** - No secrets, no external workflow
✅ **Reliable** - Vercel infrastructure handles scheduling

---

## Prerequisites

1. Deployed on **Vercel** (or similar platform)
2. RESEND_API_KEY configured (for emails)
3. ADMIN_EMAIL environment variable set

---

## Setup Steps

### Step 1: Add Environment Variables to Vercel

Go to: **Vercel Dashboard → Settings → Environment Variables**

Add these:

```
RESEND_API_KEY=your_existing_key
ADMIN_EMAIL=your-admin-email@example.com
```

### Step 2: Update vercel.json (Already Done!)

The cron configuration is already in your `vercel.json`:

```json
"crons": [
  {
    "path": "/api/cleanup/auto",
    "schedule": "0 2 * * *"
  }
]
```

This runs cleanup daily at **2 AM UTC**.

### Step 3: Deploy Code

Push these files to your repository:

```
✅ web/app/api/cleanup/auto/route.ts (UPDATED)
✅ web/vercel.json (UPDATED with cron config)
```

### Step 4: Verify Deployment

After deploying to Vercel:

1. Go to your Vercel dashboard
2. Open your project
3. Go to **Settings → Cron Jobs**
4. You should see: `/api/cleanup/auto` scheduled for `0 2 * * *` (2 AM UTC)

---

## Testing Auto Cleanup

### Test the API Endpoint

```bash
# Test GET (health check)
curl https://your-domain.vercel.app/api/cleanup/auto

# Test POST (simulate cleanup)
curl -X POST https://your-domain.vercel.app/api/cleanup/auto
```

You should see:
```json
{
  "success": true,
  "timestamp": "2024-01-22T...",
  "type": "AUTO",
  "message": "Automatic cleanup completed successfully"
}
```

### Verify Email Sent

After testing, check your admin email for:
- **Subject**: `[AUTO] SyncFlow Cleanup Report - Jan 22, 2024`
- Contains cleanup results and cost savings

---

## How Auto Cleanup Works

### Schedule

- **Time**: 2 AM UTC daily
- **Duration**: 2-5 minutes
- **Frequency**: Once per day

### Execution Steps

1. Vercel Cron calls `/api/cleanup/auto` at 2 AM UTC
2. Endpoint detects orphaned users and duplicates
3. Deletes old accounts (keeping newest per device)
4. Cleans empty nodes
5. **Sends email report** with `[AUTO]` tag

### Email Format

```
Subject: [AUTO] SyncFlow Cleanup Report - Jan 22, 2024

Cleanup Results:
  Orphaned Users Deleted: 12
  Empty Nodes Cleaned: 15
  Duplicate Accounts Merged: 8

Cost Savings:
  Estimated Monthly Savings: $2.50

Status: ✅ Completed
```

---

## Changing the Schedule

To change when cleanup runs, edit `vercel.json`:

```json
"crons": [
  {
    "path": "/api/cleanup/auto",
    "schedule": "0 2 * * *"    // Change this
  }
]
```

### Schedule Examples

```
"0 2 * * *"    → 2 AM UTC daily
"0 0 * * *"    → Midnight UTC daily
"0 6 * * 0"    → 6 AM UTC Sundays only
"0 */6 * * *"  → Every 6 hours
"0 1 * * 1"    → 1 AM UTC Mondays
```

[Cron syntax reference](https://crontab.guru/)

---

## Email Differentiation

### Manual Cleanup (Dashboard)
```
Subject: [MANUAL] SyncFlow Cleanup Report - Jan 22, 2024
Triggered by: Admin clicking buttons
```

### Automatic Cleanup (Vercel Cron)
```
Subject: [AUTO] SyncFlow Cleanup Report - Jan 22, 2024
Triggered by: Vercel Cron scheduler
```

---

## Cost Breakdown

| Component | Cost | Notes |
|-----------|------|-------|
| Vercel Cron | FREE | Included with Vercel |
| API Endpoint | FREE | Serverless function |
| Firebase Operations | VARIABLE | Only pay for DB operations |
| Email via RESEND | FREE | Included with your plan |
| **TOTAL MONTHLY** | ~$0 | Just Firebase savings |

---

## Monitoring Auto Cleanup

### View Cron Job Status

**Vercel Dashboard:**
1. Go to your project
2. Settings → Cron Jobs
3. See last execution time and status

### Check Email Inbox

- Filter emails by `[AUTO]` tag
- Verify reports arrive daily at ~2:05 AM UTC

### Monitor Vercel Logs

1. Vercel Dashboard → Functions
2. Filter: `/api/cleanup/auto`
3. See each execution and results

---

## Troubleshooting

### Cron job not running

1. ✅ Verify `vercel.json` has correct cron config
2. ✅ Check Vercel project deployed successfully
3. ✅ Wait until 2 AM UTC for first execution
4. ✅ Check Vercel dashboard → Cron Jobs status

### Email not received

1. ✅ Verify `RESEND_API_KEY` is set
2. ✅ Verify `ADMIN_EMAIL` is correct
3. ✅ Check email spam folder
4. ✅ Test manually: `curl -X POST https://your-domain.vercel.app/api/cleanup/auto`

### Wrong time format

1. ✅ Ensure schedule is in 5-field cron format: `minute hour day month weekday`
2. ✅ Time is UTC - adjust based on your timezone
3. ✅ Commit and redeploy after changing `vercel.json`

---

## Disabling Auto Cleanup

To disable, edit `vercel.json` and remove the crons section:

```json
{
  "crons": []
}
```

Or delete the entire crons block and redeploy.

---

## FAQ

**Q: Will this affect my deals scraper?**
A: No! Vercel Cron is separate. No GitHub Actions quota used.

**Q: Can I run cleanup manually too?**
A: Yes! Manual cleanup via dashboard still works. You'll get separate emails.

**Q: What if cleanup takes longer than expected?**
A: Vercel allows up to 10 minutes for cron jobs. Our cleanup is 2-5 min.

**Q: How do I skip a day?**
A: Temporarily remove crons from `vercel.json`, redeploy, then add back.

**Q: Can I run more frequently?**
A: Yes, change schedule to `"0 */6 * * *"` for every 6 hours.

---

## Next Steps

1. ✅ Add environment variables to Vercel (Step 1)
2. ✅ Deploy code (Step 3)
3. ✅ Wait for first auto-run at 2 AM UTC
4. ✅ Verify email receipt
5. ✅ Monitor future runs in Vercel dashboard

---

## Support

For issues:
- Check Vercel deployment logs
- Check Vercel Cron Jobs status
- Review Firebase console for errors
- Check email spam folder for reports
- Test manually with curl command
