# SyncFlow AI Support Chatbot - Knowledge Base

> This document is used to train the SyncFlow AI support chatbot. It contains comprehensive information about the app's features, setup, troubleshooting, and policies.

---

## ABOUT SYNCFLOW

SyncFlow is a cross-platform messaging app that syncs your Android SMS/MMS messages to your Mac and web browser. It allows you to send and receive text messages from your computer while your phone stays in your pocket.

**Supported Platforms:**
- Android phone (required - this is the source of messages)
- macOS app
- Web app (https://sfweb.app)

**Note:** There is NO iOS app and NO Windows/PC app. Users must have an Android phone.

---

## GETTING STARTED

### How do I set up SyncFlow?

1. **Install on Android**: Download SyncFlow from the Google Play Store
2. **Grant Permissions**: Allow SMS, Contacts, Phone, and Notification permissions
3. **Set as Default SMS App** (recommended): Go to Settings and tap "Set as Default"
4. **Pair with Mac or Web**:
   - Open https://sfweb.app in your browser, OR download the Mac app
   - A QR code will appear on screen
   - On Android: Go to Settings → Pair Device → Scan QR Code
   - Scan the QR code displayed on your Mac/Web browser
5. **Done!** Your messages will start syncing automatically

### How do I pair with the web app?

1. Open **https://sfweb.app** in any browser (Chrome, Safari, Firefox, Edge)
2. A QR code will be displayed on the website
3. On your Android phone, open SyncFlow
4. Go to **Settings → Pair Device**
5. Tap **"Scan QR Code"** and point your camera at the screen
6. Once scanned, your messages will sync to the web

### How do I pair with the Mac app?

1. Download SyncFlow for Mac from the Mac App Store
2. Open the Mac app - a QR code will appear
3. On your Android phone, open SyncFlow
4. Go to **Settings → Pair Device**
5. Tap **"Scan QR Code"** and scan the code on your Mac
6. Your messages will start syncing immediately

### Can I pair multiple devices?

Yes! You can pair multiple Mac computers and web browsers to the same Android phone. All devices will stay in sync with each other.

---

## RECOVERY CODE

### What is a recovery code?

Your recovery code is a unique code that lets you restore your account on new devices. It's created when you first pair a device and remains the same for your account.

### How do I find my recovery code?

**Method 1 - AI Support Chat:**
1. Go to Settings → AI Support Chat
2. Ask "What's my recovery code?"
3. Your code will be displayed

**Method 2 - Settings:**
1. On Android: Settings → Pair Device → Recovery Code
2. On Mac: Settings → Your recovery code is shown

### How do I use my recovery code?

If you get a new phone or need to restore your account:
1. Install SyncFlow on the new device
2. Tap "I have a recovery code"
3. Enter your 12-character recovery code
4. Your account and data will be restored

### My recovery code isn't working

If your recovery code doesn't work:
- Make sure you're entering it exactly (case-sensitive)
- Check for similar-looking characters (0 vs O, 1 vs l)
- If your account is scheduled for deletion, the recovery code is disabled
- Contact support at syncflow.contact@gmail.com for help

### Can I change my recovery code?

Yes! You can regenerate your recovery code:
1. Go to Settings → AI Support Chat
2. Ask "Regenerate my recovery code"
3. Confirm the action
4. Save your new code securely

**Warning:** Regenerating your code invalidates the old one immediately.

---

## FEATURES

### Messaging

**Q: Can I send and receive SMS from my Mac/Web?**
A: Yes! You can read all your messages and send new ones. Messages you send go through your Android phone's cellular connection.

**Q: Does SyncFlow support MMS (pictures/videos)?**
A: Yes! SyncFlow supports MMS messages including images, videos, and audio files.

**Q: Can I send group messages?**
A: Yes! Group MMS conversations are fully supported. You can view all participants and send messages to the group.

**Q: What about voice messages?**
A: You can record and send voice messages from both Android and Mac.

**Q: Can I schedule messages to send later?**
A: Yes! On Android, you can schedule messages to be sent at a specific time.

**Q: Does SyncFlow support RCS?**
A: Not yet. RCS support requires carrier integration and is planned for a future update.

### Calls

**Q: Can I make phone calls from my Mac?**
A: Yes! Click the phone icon next to any contact or conversation. This triggers your Android phone to dial the number. The actual call happens on your phone.

**Q: Can I make video calls?**
A: Yes! SyncFlow supports WebRTC audio and video calls between SyncFlow users over the internet.

**Q: Is call recording available?**
A: Call recording is available on macOS for WebRTC calls only.

### Contacts

**Q: Do my contacts sync to Mac/Web?**
A: Yes! All your Android contacts sync automatically to Mac and Web, including contact photos.

**Q: Can I create contacts on Mac?**
A: Yes! Contacts created on Mac sync back to your Android phone.

### Sync

**Q: How far back does message sync go?**
A: By default, the last 30 days of messages sync. You can load older messages by going to Settings → Sync Message History and selecting a longer time range.

**Q: How do I sync older messages?**
A: On Android, go to Settings → Sync Message History → Select time range → Tap "Sync". Note: This option only appears after you've paired at least one device.

**Q: Are messages synced in real-time?**
A: Yes! New messages appear instantly on all paired devices.

### File Transfer

**Q: Can I send files between my phone and Mac?**
A: Yes! You can send files in both directions:
- **Mac to Android:** Use the Quick Drop feature in the Mac sidebar or File → Send File menu
- **Android to Mac:** Go to Settings → Send Files to Mac (only visible when paired)

**Q: What are the file transfer limits?**
A: File transfers have generous limits with no daily restrictions:

| Plan | Max File Size | Daily Limit |
|------|---------------|-------------|
| Free | 50 MB | **Unlimited** |
| Pro | 500 MB | **Unlimited** |

You can check your usage in Settings → Usage & Limits.

**Q: Where do transferred files go?**
A: On Mac, received files are saved to your Downloads folder. On Android, they appear in your Downloads folder.

### AI Assistant

**Q: What can the AI Assistant do?**
A: The AI Assistant can analyze your messages locally (no data sent to servers) to:
- Track spending from transaction messages ("How much did I spend this month?")
- Find OTP/verification codes
- Search messages by content
- Filter transactions by merchant

**Q: Is my data sent to AI servers?**
A: No! All AI analysis happens on your device. Your messages are never sent to external AI services.

### AI Support Chat

**Q: What is AI Support Chat?**
A: AI Support Chat is an in-app support assistant that can help with account questions and self-service actions. Find it in Settings → AI Support Chat.

**Q: What can I ask the AI Support Chat?**
A: You can ask about your account:
- "What's my recovery code?" - Shows your recovery code
- "What's my user ID?" - Shows your unique user ID
- "How much data have I used?" - Shows storage and upload usage
- "Show my subscription" - Shows your current plan and expiry
- "Show my sync status" - Shows sync health and last sync time
- "How many messages synced?" - Shows message statistics
- "Show my devices" - Lists all paired devices
- "Show my spam stats" - Shows spam filtering statistics

**Q: Can AI Support Chat perform actions?**
A: Yes! You can request:
- "Unpair a device" - Remove a paired device
- "Reset my sync" - Clear and restart sync
- "Sign out all devices" - Sign out everywhere for security
- "Download my data" - Request GDPR data export
- "How do I delete my account?" - Get deletion instructions

**Q: Is my data safe with AI Support Chat?**
A: Yes! The AI Support Chat only accesses your own account data. It cannot see other users' information.

---

## SUBSCRIPTION & PRICING

### Plans

| Plan | Price | Features |
|------|-------|----------|
| **Free Trial** | 7 days free | Full access to all features |
| **Monthly** | $4.99/month | Full access |
| **Yearly** | $39.99/year | Full access, save 33% |
| **3-Year** | $79.99 one-time | Best value |

### What's included in Pro?

- Unlimited SMS & MMS
- Phone calls from Mac
- Photo sync (contact photos)
- 10GB uploads/month
- 2GB cloud storage
- File transfer: 500MB per file, unlimited transfers
- End-to-end encryption
- Priority support

### Free Tier Limits

- 500MB monthly uploads
- 100MB cloud storage
- File transfer: 50MB per file, unlimited transfers
- 7-day trial period

### How do I upgrade?

On Android: Go to Settings → the upgrade option
On Mac: Go to Settings → Subscription → Upgrade

### How do I restore my purchase?

On Mac: Go to Settings → Subscription → Restore Purchases
Make sure you're signed into the same Apple ID used for the original purchase.

### How do I cancel my subscription?

Subscriptions are managed through the App Store:
- On Mac: System Settings → Apple ID → Subscriptions → SyncFlow → Cancel

---

## PRIVACY & SECURITY

### Is SyncFlow secure?

Yes! SyncFlow uses multiple layers of security:
- **End-to-end encryption** using Signal Protocol
- **HTTPS/TLS** for all data transmission
- **Firebase secure connection** for cloud storage
- **On-device AI processing** (no data sent to AI servers)

### Where is my data stored?

Your messages are stored in Firebase (Google Cloud) with encryption. Data is stored in secure data centers.

### Can SyncFlow employees read my messages?

No. Messages are encrypted and we cannot access their content.

### How long is my data kept?

- **Active users**: Data is retained while you use the service
- **Inactive users**: Data is automatically deleted after 30 days of inactivity
- **Backups**: Retained up to 90 days for recovery purposes

### How do I delete my account?

SyncFlow offers a self-service account deletion with a 30-day grace period:

1. **On Android:** Go to Settings → Account → Delete Account
2. **On Mac:** Go to Settings → General → Delete Account
3. Choose a reason for leaving (optional)
4. Confirm deletion

**What happens when you request deletion:**
- Your account is **scheduled for deletion in 30 days**
- You can continue using the app during this period
- Your recovery code is **immediately disabled** (cannot pair new devices)
- After 30 days, all data is permanently deleted

**What gets deleted:**
- All synced messages
- All connected devices
- Your recovery code
- Subscription data
- All personal information

**Can I cancel the deletion?**
Yes! Within the 30-day grace period:
1. Go to Settings → Account → Delete Account
2. You'll see "Account Scheduled for Deletion"
3. Tap "Cancel Deletion & Keep Account"
4. Your account will be fully restored

**What if I deleted the app and changed my mind?**
Contact support at syncflow.contact@gmail.com with:
- Your device name (e.g., "John's iPhone", "Samsung S23")
- Phone numbers you frequently texted
- Approximate date you requested deletion

We can cancel the deletion if it's within the 30-day period.

### How do I delete just my data (not my account)?

To delete your synced data without deleting your account:
1. On Android: Go to Settings → Pair Device → Unpair all devices
2. This removes the connection and clears synced data
3. You can re-pair later and sync fresh

### What permissions does SyncFlow need?

**Required:**
- SMS: To read and send messages
- Contacts: To sync your contact list
- Phone: To handle calls

**Optional:**
- Camera: To scan QR codes for pairing
- Notifications: To alert you of new messages

---

## TROUBLESHOOTING

### Messages not syncing

**Try these steps:**
1. Check your internet connection on both devices
2. Make sure the Android app is running (not force closed)
3. On Android: Go to Settings → Sync Message History → Sync
4. Check if your trial has expired
5. Restart both the Android app and Mac/Web app

### Can't pair devices

**If QR code scanning fails:**
1. Make sure camera permission is granted
2. Ensure good lighting and hold phone steady
3. Try the manual pairing code option instead

**If pairing still fails:**
1. Check internet connection on both devices
2. Make sure you're using https://sfweb.app (not http)
3. Try a different browser (Chrome recommended)
4. Restart the Android app and try again

### Messages showing as "sending" forever

1. Check your phone's cellular signal
2. Make sure SyncFlow is set as default SMS app
3. Check if the recipient's number is correct
4. Try restarting the Android app

### Mac app not receiving notifications

1. Go to System Settings → Notifications → SyncFlow
2. Make sure notifications are enabled
3. Check that "Allow Notifications" is turned on
4. In SyncFlow settings, verify notifications are enabled

### Web app camera not working

- The web app requires HTTPS to access your camera
- Make sure you're on https://sfweb.app (not http)
- Grant camera permission when prompted
- Try a different browser if issues persist

### High battery usage on Android

SyncFlow is optimized for battery efficiency. The call monitoring service only runs on-demand (when you have an active call or initiate a call from Mac/Web), and automatically stops when calls end.

If you notice high battery usage:
1. Make sure you have the latest version of SyncFlow
2. Check that you don't have stuck/ongoing calls
3. Go to Android Settings → Battery → SyncFlow to see detailed usage
4. The app uses minimal battery when idle

### Spam messages appearing

SyncFlow has built-in spam filtering. To manage it:
1. Go to Settings → Spam Filter
2. Add trusted numbers to your whitelist
3. Report spam messages to improve detection

---

## FREQUENTLY ASKED QUESTIONS

### General

**Q: Do I need my phone nearby to use SyncFlow?**
A: Your phone needs to be powered on and connected to the internet, but it doesn't need to be nearby. Messages sync through the cloud.

**Q: Does SyncFlow work without internet?**
A: No. Both your phone and computer need internet to sync messages.

**Q: Can I use SyncFlow on multiple computers?**
A: Yes! Pair as many Mac computers and web browsers as you want.

**Q: Is there an iOS app?**
A: No. SyncFlow requires an Android phone because iOS doesn't allow third-party apps to access SMS messages.

**Q: Is there a Windows/PC app?**
A: There's no native Windows app, but you can use the web app at https://sfweb.app on any computer with a browser.

**Q: Can I use SyncFlow on a tablet?**
A: The web app works on any device with a browser, including tablets.

### Technical

**Q: What Android version is required?**
A: Android 8.0 (Oreo) or newer.

**Q: What macOS version is required?**
A: macOS 13 (Ventura) or newer.

**Q: Does SyncFlow use a lot of data?**
A: Text messages use minimal data. MMS with images/videos use more, similar to sending them normally.

**Q: Can I use SyncFlow on work Wi-Fi?**
A: Yes, as long as Firebase services (*.firebaseio.com) aren't blocked by your network.

---

## CONTACT & SUPPORT

**Email:** syncflow.contact@gmail.com

**GitHub Issues:** https://github.com/dpchavali1/SyncFlow/issues

**Website:** https://sfweb.app

**Response Time:** We aim to respond within 24-48 hours.

---

## QUICK ANSWERS

| Question | Answer |
|----------|--------|
| Is there an iOS app? | No, Android only |
| Is there a Windows app? | No, use web app at sfweb.app |
| How much does it cost? | Free 7-day trial, then $4.99/mo or $39.99/yr or $79.99/3yr |
| Is it secure? | Yes, end-to-end encrypted |
| Can I use multiple computers? | Yes, unlimited devices |
| Do I need my phone nearby? | No, just needs internet |
| Does it support MMS? | Yes, images and videos |
| Does it support group texts? | Yes |
| Can I make calls from Mac? | Yes |
| How do I sync old messages? | Settings → Sync Message History (pair first) |
| Can I send files to Mac? | Yes, Settings → Send Files to Mac (pair first) |
| File transfer limits? | Free: 50MB/file, unlimited. Pro: 500MB/file, unlimited |
| How do I cancel? | Through App Store subscriptions |
| How do I delete my account? | Settings → Account → Delete Account (30-day grace period) |
| What's my recovery code? | Settings → AI Support Chat → "What's my recovery code?" |
| Can I undo account deletion? | Yes, within 30 days via Settings → Account |
| How do I check my data usage? | Settings → AI Support Chat → "How much data have I used?" |
| Contact support | syncflow.contact@gmail.com |

---

## CHATBOT INSTRUCTIONS

When answering questions:
1. Be friendly and helpful
2. Keep answers concise but complete
3. If you don't know something, say so and suggest contacting support
4. Always mention the support email for complex issues: syncflow.contact@gmail.com
5. For subscription issues, direct users to App Store settings
6. Remind users that SyncFlow requires an Android phone - there is no iOS version
7. The web app URL is https://sfweb.app (always include https://)
8. Never make up features that don't exist
9. If asked about upcoming features, say "that feature is planned but not yet available"

---

*Last updated: January 28, 2026*

---

## CHANGELOG

### January 28, 2026
- Added **Account Deletion** feature with 30-day grace period
- Added **AI Support Chat** self-service features:
  - View recovery code, user ID, data usage
  - View subscription status, sync status, message stats
  - Unpair devices, reset sync, sign out all devices
  - Request data export (GDPR compliance)
- Added **Account Recovery** admin tools for support team
- Removed lifetime plan (replaced with 3-year plan)
