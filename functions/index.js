/**
 * SyncFlow Firebase Cloud Functions
 *
 * Handles sending FCM push notifications for incoming calls
 * when the app is in the background or closed.
 */

const functions = require("firebase-functions");
const admin = require("firebase-admin");
const { Resend } = require("resend");
const { S3Client, PutObjectCommand, GetObjectCommand, DeleteObjectCommand, ListObjectsV2Command } = require("@aws-sdk/client-s3");
const { getSignedUrl } = require("@aws-sdk/s3-request-presigner");

admin.initializeApp();
const crypto = require("crypto");

const PAIRING_TTL_MS = 5 * 60 * 1000;
const ADMIN_EMAIL = "syncflow.contact@gmail.com";

// ============================================================================
// CLOUDFLARE R2 CONFIGURATION
// ============================================================================
// R2 credentials are stored in Firebase environment config
// Set them with: firebase functions:config:set r2.account_id="xxx" r2.access_key="xxx" r2.secret_key="xxx" r2.bucket="xxx"

const getR2Client = () => {
    const config = typeof functions.config === "function" ? functions.config() : {};
    const r2Config = config?.r2 || {};

    const accountId = r2Config.account_id || process.env.R2_ACCOUNT_ID;
    const accessKey = r2Config.access_key || process.env.R2_ACCESS_KEY;
    const secretKey = r2Config.secret_key || process.env.R2_SECRET_KEY;

    if (!accountId || !accessKey || !secretKey) {
        console.warn("[R2] Missing R2 credentials");
        return null;
    }

    return new S3Client({
        region: "auto",
        endpoint: `https://${accountId}.r2.cloudflarestorage.com`,
        credentials: {
            accessKeyId: accessKey,
            secretAccessKey: secretKey,
        },
    });
};

const getR2Bucket = () => {
    const config = typeof functions.config === "function" ? functions.config() : {};
    return config?.r2?.bucket || process.env.R2_BUCKET || "syncflow-files";
};

// Initialize Resend for email notifications
const getResend = () => {
    const config = typeof functions.config === "function" ? functions.config() : {};
    const apiKey = config?.resend?.api_key || process.env.RESEND_API_KEY;
    return apiKey ? new Resend(apiKey) : null;
};

/**
 * Send cleanup report email
 */
const sendCleanupReportEmail = async (stats, type = "AUTO") => {
    const resend = getResend();
    if (!resend) {
        console.log("Resend API key not configured, skipping email");
        return false;
    }

    const timestamp = new Date().toLocaleString();
    const totalCleaned = Object.values(stats).reduce((sum, count) => sum + (typeof count === 'number' ? count : 0), 0);

    const report = `
SYNCFLOW AUTO CLEANUP REPORT
============================

Report Generated: ${timestamp}
Cleanup Type: ${type}

CLEANUP STATISTICS
==================
Total Records Cleaned: ${totalCleaned}
Users Processed: ${stats.usersProcessed || 0}

Detailed Breakdown:
• Stale Outgoing Messages: ${stats.outgoingMessages || 0}
• Old Call Requests: ${stats.callRequests || 0}
• Old Spam Messages: ${stats.spamMessages || 0}
• Old Read Receipts: ${stats.readReceipts || 0}
• Inactive Devices: ${stats.inactiveDevices || 0}
• Old Notifications: ${stats.oldNotifications || 0}
• Stale Typing Indicators: ${stats.staleTypingIndicators || 0}
• Expired Sessions: ${stats.expiredSessions || 0}
• Old File Transfers: ${stats.oldFileTransfers || 0}
• Abandoned Pairings: ${stats.abandonedPairings || 0}

NEXT CLEANUP SCHEDULED
======================
Auto cleanup runs daily at 3 AM UTC.

---
SyncFlow Admin System
Automated Maintenance Report
`;

    try {
        const result = await resend.emails.send({
            from: "SyncFlow Admin <noreply@resend.dev>",
            to: [ADMIN_EMAIL],
            subject: `[${type}] SyncFlow Cleanup Report - ${new Date().toLocaleDateString()}`,
            text: report,
        });
        console.log("Cleanup report email sent:", result);
        return true;
    } catch (error) {
        console.error("Failed to send cleanup report email:", error);
        return false;
    }
};

const requireAuth = (context) => {
    if (!context.auth) {
        throw new functions.https.HttpsError("unauthenticated", "Authentication required");
    }
    return context.auth.uid;
};

/**
 * Send notification email for account/data deletion requests
 */
const sendDeletionNotificationEmail = async (userId, requestType, requestId = null) => {
    const resend = getResend();
    if (!resend) {
        console.log("Resend API key not configured, skipping deletion notification email");
        return false;
    }

    const timestamp = new Date().toLocaleString();

    // Get user info for context
    let userInfo = "";
    let tierTag = "[UNKNOWN]";
    try {
        const [usageSnap, devicesSnap, subSnap] = await Promise.all([
            admin.database().ref(`users/${userId}/usage`).once('value'),
            admin.database().ref(`users/${userId}/devices`).once('value'),
            admin.database().ref(`subscription_records/${userId}/active`).once('value')
        ]);

        const usage = usageSnap.val() || {};
        const devices = devicesSnap.val() || {};
        const subscription = subSnap.val() || {};

        const deviceCount = Object.keys(devices).length;
        const plan = usage.plan || subscription.plan || "free";
        const storageBytes = usage.storageBytes || 0;
        const storageMB = (storageBytes / (1024 * 1024)).toFixed(2);

        // Set tier tag for subject
        const tierMap = { "free": "FREE", "monthly": "MONTHLY", "yearly": "YEARLY", "3-yearly": "3-YEARLY", "3yearly": "3-YEARLY" };
        tierTag = `[${tierMap[plan.toLowerCase()] || plan.toUpperCase()}]`;

        userInfo = `
User Details:
• User ID: ${userId}
• Current Plan: ${plan}
• Connected Devices: ${deviceCount}
• Storage Used: ${storageMB} MB
`;
    } catch (e) {
        userInfo = `User ID: ${userId}\n(Could not fetch additional details)`;
    }

    let subject, body;

    if (requestType === "account_deletion_inquiry") {
        subject = `${tierTag} [Action Required] Account Deletion Inquiry`;
        body = `
SYNCFLOW ACCOUNT DELETION INQUIRY
==================================

A user has inquired about deleting their account via AI Support Chat.

Request Time: ${timestamp}

${userInfo}

Note: The user has only viewed deletion instructions. They may:
1. Delete via in-app Settings > Account > Delete Account
2. Email a formal deletion request

No action needed unless they send a formal request.

---
SyncFlow Admin System
Automated Notification
`;
    } else if (requestType === "data_export_request") {
        subject = `${tierTag} [Action Required] Data Export Request (GDPR)`;
        body = `
SYNCFLOW DATA EXPORT REQUEST
=============================

A user has requested a full data export via AI Support Chat.

Request Time: ${timestamp}
Request ID: ${requestId || "N/A"}

${userInfo}

ACTION REQUIRED:
1. Prepare data export for this user
2. Upload to secure location
3. Send download link to user
4. Update request status in database: data_export_requests/${requestId}

Data to include:
• All synced messages
• Contact information
• Device settings
• Account preferences
• Subscription history

Timeline: Complete within 24-48 hours (GDPR compliance)

---
SyncFlow Admin System
Automated Notification
`;
    } else {
        subject = `${tierTag} [Info] User Data/Account Activity`;
        body = `
SYNCFLOW USER ACTIVITY NOTIFICATION
====================================

Activity Type: ${requestType}
Time: ${timestamp}

${userInfo}

---
SyncFlow Admin System
Automated Notification
`;
    }

    try {
        const result = await resend.emails.send({
            from: "SyncFlow Admin <noreply@resend.dev>",
            to: [ADMIN_EMAIL],
            subject: subject,
            text: body,
        });
        console.log("Deletion notification email sent:", result);
        return true;
    } catch (error) {
        console.error("Failed to send deletion notification email:", error);
        return false;
    }
};

/**
 * Send security alert email (sign out all devices, etc.)
 */
const sendSecurityAlertEmail = async (userId, alertType) => {
    const resend = getResend();
    if (!resend) {
        console.log("Resend API key not configured, skipping security alert email");
        return false;
    }

    const timestamp = new Date().toLocaleString();

    // Get user info and tier for context
    const [userInfo, tierTag] = await Promise.all([
        getUserInfoForEmail(userId),
        getUserTierForSubject(userId)
    ]);

    let subject, body;

    if (alertType === "signout_all_devices") {
        subject = `${tierTag} [Security Alert] User Signed Out All Devices`;
        body = `
SYNCFLOW SECURITY ALERT
========================

A user has signed out of all devices via AI Support Chat.

Alert Time: ${timestamp}
Alert Type: Sign Out All Devices

${userInfo}

This could indicate:
• User securing their account after potential compromise
• Lost or stolen device
• Routine security hygiene

No action required unless user reports issues.

---
SyncFlow Admin System
Security Alert
`;
    } else {
        subject = `${tierTag} [Security Alert] User Security Action`;
        body = `
SYNCFLOW SECURITY ALERT
========================

Alert Time: ${timestamp}
Alert Type: ${alertType}

${userInfo}

---
SyncFlow Admin System
Security Alert
`;
    }

    try {
        const result = await resend.emails.send({
            from: "SyncFlow Admin <noreply@resend.dev>",
            to: [ADMIN_EMAIL],
            subject: subject,
            text: body,
        });
        console.log("Security alert email sent:", result);
        return true;
    } catch (error) {
        console.error("Failed to send security alert email:", error);
        return false;
    }
};

/**
 * Send churn alert email (cancel subscription inquiry)
 */
const sendChurnAlertEmail = async (userId, alertType) => {
    const resend = getResend();
    if (!resend) {
        console.log("Resend API key not configured, skipping churn alert email");
        return false;
    }

    const timestamp = new Date().toLocaleString();

    // Get user info for context
    const userInfo = await getUserInfoForEmail(userId);

    // Get subscription details and tier
    let subscriptionInfo = "";
    let tierTag = "[UNKNOWN]";
    try {
        const subSnap = await admin.database().ref(`subscription_records/${userId}`).once('value');
        const subRecord = subSnap.val() || {};
        const active = subRecord.active || {};
        const history = subRecord.history || {};

        const plan = active.plan || "free";
        const planExpiresAt = active.planExpiresAt;
        const firstPremiumDate = subRecord.firstPremiumDate;

        // Set tier tag for subject
        const tierMap = { "free": "FREE", "monthly": "MONTHLY", "yearly": "YEARLY", "3-yearly": "3-YEARLY", "3yearly": "3-YEARLY" };
        tierTag = `[${tierMap[plan.toLowerCase()] || plan.toUpperCase()}]`;

        subscriptionInfo = `
Subscription Details:
• Current Plan: ${plan}
• Plan Expires: ${planExpiresAt ? new Date(planExpiresAt).toLocaleDateString() : "N/A"}
• Premium Since: ${firstPremiumDate ? new Date(firstPremiumDate).toLocaleDateString() : "N/A"}
• Plan Changes: ${Object.keys(history).length}
`;
    } catch (e) {
        subscriptionInfo = "(Could not fetch subscription details)";
    }

    const subject = `${tierTag} [Churn Alert] User Inquired About Cancellation`;
    const body = `
SYNCFLOW CHURN ALERT
=====================

A user has asked about canceling their subscription via AI Support Chat.

Alert Time: ${timestamp}

${userInfo}

${subscriptionInfo}

POTENTIAL SAVE OPPORTUNITY:
This user is considering cancellation. Consider:
1. Reaching out to understand their concerns
2. Offering a discount or extended trial
3. Addressing any service issues they may have

Note: User has only viewed cancellation instructions.
They still need to cancel through Google Play/App Store.

---
SyncFlow Admin System
Churn Prevention Alert
`;

    try {
        const result = await resend.emails.send({
            from: "SyncFlow Admin <noreply@resend.dev>",
            to: [ADMIN_EMAIL],
            subject: subject,
            text: body,
        });
        console.log("Churn alert email sent:", result);
        return true;
    } catch (error) {
        console.error("Failed to send churn alert email:", error);
        return false;
    }
};

/**
 * Send service health email (sync reset, etc.)
 */
const sendServiceHealthEmail = async (userId, alertType) => {
    const resend = getResend();
    if (!resend) {
        console.log("Resend API key not configured, skipping service health email");
        return false;
    }

    const timestamp = new Date().toLocaleString();

    // Get user info and tier for context
    const [userInfo, tierTag] = await Promise.all([
        getUserInfoForEmail(userId),
        getUserTierForSubject(userId)
    ]);

    // Get recent sync errors if any
    let syncErrorInfo = "";
    try {
        const errorsSnap = await admin.database().ref(`users/${userId}/sync_errors`).limitToLast(5).once('value');
        const errors = errorsSnap.val() || {};
        const errorList = Object.values(errors);

        if (errorList.length > 0) {
            syncErrorInfo = `\nRecent Sync Errors (before reset):\n`;
            errorList.forEach((err) => {
                const errTime = err.timestamp ? new Date(err.timestamp).toLocaleString() : "Unknown";
                const errMsg = err.message || err.error || "Unknown error";
                syncErrorInfo += `• ${errTime}: ${errMsg}\n`;
            });
        } else {
            syncErrorInfo = "\nNo recent sync errors recorded.";
        }
    } catch (e) {
        syncErrorInfo = "\n(Could not fetch sync error history)";
    }

    let subject, body;

    if (alertType === "sync_reset") {
        subject = `${tierTag} [Service Health] User Reset Sync`;
        body = `
SYNCFLOW SERVICE HEALTH ALERT
==============================

A user has reset their sync state via AI Support Chat.

Alert Time: ${timestamp}
Action: Full Sync Reset

${userInfo}
${syncErrorInfo}

This could indicate:
• Sync issues or stuck sync
• Data inconsistency problems
• User troubleshooting on their own

Consider checking:
1. Are there widespread sync issues?
2. Is this user having repeated problems?
3. Any recent backend changes affecting sync?

---
SyncFlow Admin System
Service Health Monitor
`;
    } else {
        subject = `${tierTag} [Service Health] User Service Action`;
        body = `
SYNCFLOW SERVICE HEALTH ALERT
==============================

Alert Time: ${timestamp}
Action: ${alertType}

${userInfo}

---
SyncFlow Admin System
Service Health Monitor
`;
    }

    try {
        const result = await resend.emails.send({
            from: "SyncFlow Admin <noreply@resend.dev>",
            to: [ADMIN_EMAIL],
            subject: subject,
            text: body,
        });
        console.log("Service health email sent:", result);
        return true;
    } catch (error) {
        console.error("Failed to send service health email:", error);
        return false;
    }
};

/**
 * Helper to get user info for email notifications
 */
const getUserInfoForEmail = async (userId) => {
    if (!userId) return "User ID: Unknown (not authenticated)";

    try {
        const [usageSnap, devicesSnap, subSnap] = await Promise.all([
            admin.database().ref(`users/${userId}/usage`).once('value'),
            admin.database().ref(`users/${userId}/devices`).once('value'),
            admin.database().ref(`subscription_records/${userId}/active`).once('value')
        ]);

        const usage = usageSnap.val() || {};
        const devices = devicesSnap.val() || {};
        const subscription = subSnap.val() || {};

        const deviceCount = Object.keys(devices).length;
        const plan = usage.plan || subscription.plan || "free";
        const storageBytes = usage.storageBytes || 0;
        const storageMB = (storageBytes / (1024 * 1024)).toFixed(2);

        // Get device names
        let deviceList = "";
        Object.values(devices).forEach((d) => {
            const name = d.deviceName || d.name || "Unknown";
            const platform = d.platform || "unknown";
            deviceList += `  - ${name} (${platform})\n`;
        });

        return `
User Details:
• User ID: ${userId}
• Current Plan: ${plan}
• Connected Devices: ${deviceCount}
${deviceList}• Storage Used: ${storageMB} MB
`;
    } catch (e) {
        return `User ID: ${userId}\n(Could not fetch additional details)`;
    }
};

/**
 * Get user tier for email subject line
 * Returns formatted tier string like [FREE], [MONTHLY], [YEARLY], [3-YEARLY]
 */
const getUserTierForSubject = async (userId) => {
    if (!userId) return "[UNKNOWN]";

    try {
        const [usageSnap, subSnap] = await Promise.all([
            admin.database().ref(`users/${userId}/usage`).once('value'),
            admin.database().ref(`subscription_records/${userId}/active`).once('value')
        ]);

        const usage = usageSnap.val() || {};
        const subscription = subSnap.val() || {};
        const plan = (usage.plan || subscription.plan || "free").toLowerCase();

        // Map plan to display name
        const tierMap = {
            "free": "FREE",
            "monthly": "MONTHLY",
            "yearly": "YEARLY",
            "3-yearly": "3-YEARLY",
            "3yearly": "3-YEARLY",
                    };

        return `[${tierMap[plan] || plan.toUpperCase()}]`;
    } catch (e) {
        return "[UNKNOWN]";
    }
};

/**
 * Get identifying information for account recovery
 * Gathers device names, phone numbers from calls/contacts, etc.
 */
const getUserIdentifyingInfo = async (userId) => {
    if (!userId) return null;

    try {
        const [devicesSnap, callHistorySnap, contactsSnap, usageSnap, recoverySnap] = await Promise.all([
            admin.database().ref(`users/${userId}/devices`).once('value'),
            admin.database().ref(`users/${userId}/call_history`).limitToLast(100).once('value'),
            admin.database().ref(`users/${userId}/contacts`).limitToLast(50).once('value'),
            admin.database().ref(`users/${userId}/usage`).once('value'),
            admin.database().ref(`users/${userId}/recovery_info`).once('value')
        ]);

        const devices = devicesSnap.val() || {};
        const callHistory = callHistorySnap.val() || {};
        const contacts = contactsSnap.val() || {};
        const usage = usageSnap.val() || {};
        const recoveryInfo = recoverySnap.val() || {};

        // Extract device names
        const deviceNames = [];
        Object.values(devices).forEach((d) => {
            const name = d.deviceName || d.name;
            const platform = d.platform || "unknown";
            if (name) {
                deviceNames.push({ name, platform, lastActive: d.lastSeen || d.lastActive });
            }
        });

        // Extract unique phone numbers from call history
        const phoneNumbers = new Set();
        Object.values(callHistory).forEach((call) => {
            if (call.phoneNumber) {
                phoneNumbers.add(call.phoneNumber);
            }
        });

        // Extract phone numbers from contacts
        Object.values(contacts).forEach((contact) => {
            if (contact.phoneNumbers) {
                Object.values(contact.phoneNumbers).forEach((phone) => {
                    if (typeof phone === 'string') {
                        phoneNumbers.add(phone);
                    } else if (phone?.number) {
                        phoneNumbers.add(phone.number);
                    }
                });
            }
            if (contact.phoneNumber) {
                phoneNumbers.add(contact.phoneNumber);
            }
        });

        // Convert to array and limit to most relevant (first 20)
        const phoneNumberList = Array.from(phoneNumbers).slice(0, 20);

        return {
            userId,
            deviceNames,
            phoneNumbers: phoneNumberList,
            deviceCount: deviceNames.length,
            contactCount: Object.keys(contacts).length,
            messageCount: usage.messageCount || 0,
            storageBytes: usage.storageBytes || 0,
            plan: usage.plan || "free",
            accountCreatedAt: recoveryInfo.createdAt || null,
            lastSyncAt: usage.lastSyncAt || null
        };
    } catch (error) {
        console.error("[getUserIdentifyingInfo] Error:", error);
        return { userId, error: "Could not fetch identifying info" };
    }
};

const requireAdmin = (context) => {
    if (!context.auth) {
        throw new functions.https.HttpsError("unauthenticated", "Authentication required");
    }
    if (context.auth.token?.admin !== true) {
        throw new functions.https.HttpsError("permission-denied", "Admin access required");
    }
    return context.auth.uid;
};

/**
 * Helper function to update subscription records
 * This creates a persistent record that survives user deletion
 * Tracks both active subscription and full history
 */
const updateSubscriptionRecord = async (userId, plan, expiresAt, source = "system") => {
    const now = Date.now();
    const recordRef = admin.database().ref(`/subscription_records/${userId}`);
    const snapshot = await recordRef.get();
    const previousPlan = snapshot.exists() ? snapshot.child("active/plan").val() : null;

    const updates = {
        "active/plan": plan,
        "active/planAssignedAt": now,
        "active/planAssignedBy": source,
    };

    if (plan === "free") {
        updates["active/freeTrialExpiresAt"] = expiresAt;
        updates["active/planExpiresAt"] = null;
    } else {
        updates["active/planExpiresAt"] = expiresAt;
        updates["active/freeTrialExpiresAt"] = null;
    }

    // Track premium status
    const isPremium = ["monthly", "yearly", "3-yearly", "3yearly"].includes(plan);
    if (isPremium) {
        updates["wasPremium"] = true;
        if (!snapshot.exists() || !snapshot.child("firstPremiumDate").exists()) {
            updates["firstPremiumDate"] = now;
        }
    }

    // Add history entry
    updates[`history/${now}`] = {
        timestamp: now,
        newPlan: plan,
        previousPlan: previousPlan || null,
        expiresAt: expiresAt,
        source: source,
    };

    await recordRef.update(updates);
};

exports.adminLogin = functions.https.onCall(async (data) => {
    try {
        const username = data && data.username ? String(data.username) : "";
        const password = data && data.password ? String(data.password) : "";

        const config = typeof functions.config === "function" ? functions.config() : {};
        const cfgAdmin = config?.syncflow || {};
        const expectedUsername = cfgAdmin.admin_username || process.env.SYNCFLOW_ADMIN_USERNAME || "";
        const expectedPasswordHash = cfgAdmin.admin_password_hash || process.env.SYNCFLOW_ADMIN_PASSWORD_HASH || "";

        if (!expectedUsername || !expectedPasswordHash) {
            throw new functions.https.HttpsError("failed-precondition", "Admin credentials not configured");
        }

        if (username !== expectedUsername || !password) {
            throw new functions.https.HttpsError("permission-denied", "Invalid credentials");
        }

        const computedHash = crypto.createHash("sha256").update(password).digest("hex");
        const expectedHashBuf = Buffer.from(expectedPasswordHash, "hex");
        const computedHashBuf = Buffer.from(computedHash, "hex");
        const hashMatches = expectedHashBuf.length === computedHashBuf.length &&
            crypto.timingSafeEqual(expectedHashBuf, computedHashBuf);

        if (!hashMatches) {
            throw new functions.https.HttpsError("permission-denied", "Invalid credentials");
        }

        const adminUid = `admin_${username}`;
        const customToken = await admin.auth().createCustomToken(adminUid, { admin: true });
        return { customToken, uid: adminUid };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error("adminLogin error:", error);
        throw new functions.https.HttpsError("internal", error.message || "Admin login failed");
    }
});

/**
 * Initiates a pairing request from Web/macOS.
 * Creates a pending pairing session that Android can approve.
 *
 * Called by: Web/macOS apps
 * Returns: { token, qrPayload, expiresAt }
 */
exports.initiatePairing = functions.https.onCall(async (data, context) => {
    try {
        const requesterUid = requireAuth(context);

        const deviceName = data && data.deviceName ? String(data.deviceName) : "Desktop";
        const platform = data && data.platform ? String(data.platform) : "web";
        const appVersion = data && data.appVersion ? String(data.appVersion) : "1.0.0";
        const existingDeviceId = data && data.existingDeviceId ? String(data.existingDeviceId) : null;
        const syncGroupId = data && data.syncGroupId ? String(data.syncGroupId) : null;

        // Pairing session created

        // Generate a secure random token
        const token = crypto.randomUUID().replace(/-/g, "");
        const now = Date.now();
        const expiresAt = now + PAIRING_TTL_MS;

        // Store the pending pairing session
        const pairingData = {
            token,
            deviceName,
            platform,
            appVersion,
            existingDeviceId,
            syncGroupId,
            requesterUid,
            status: "pending", // pending | approved | rejected | expired
            createdAt: now,
            expiresAt,
        };

        await admin.database().ref(`/pending_pairings/${token}`).set(pairingData);

        // Generate QR payload that Android will scan
        const qrPayload = JSON.stringify({
            token,
            name: deviceName,
            platform,
            version: appVersion,
            syncGroupId,
        });

        return { token, qrPayload, expiresAt };
    } catch (error) {
        console.error("initiatePairing failed");
        throw new functions.https.HttpsError("internal", "Failed to initiate pairing");
    }
});

/**
 * Completes a pairing request after Android user approval.
 * Creates the device entry and generates a custom auth token for Web/macOS.
 *
 * Called by: Android app after scanning QR and user confirms
 * Returns: { success: true } or throws error
 *
 * Note: This function checks BOTH V1 (pending_pairings) and V2 (pairing_requests) paths
 * to handle cases where Mac initiated with V2 but Android fallback to V1.
 */
exports.completePairing = functions.https.onCall(async (data, context) => {
    try {
        requireAuth(context);

        const token = data && data.token ? String(data.token) : "";
        const approved = data && data.approved === true;

        if (!token) {
            throw new functions.https.HttpsError("invalid-argument", "Missing token");
        }

        // Try V1 path first, then V2 path
        let pairingRef = admin.database().ref(`/pending_pairings/${token}`);
        let snapshot = await pairingRef.once("value");
        let isV2Path = false;

        if (!snapshot.exists()) {
            // Try V2 path as fallback (for when Mac initiated with V2)
            pairingRef = admin.database().ref(`/pairing_requests/${token}`);
            snapshot = await pairingRef.once("value");
            isV2Path = true;

            if (!snapshot.exists()) {
                throw new functions.https.HttpsError("not-found", "Pairing request not found or expired");
            }
        }

        const pairingData = snapshot.val();

        // Check if already processed
        if (pairingData.status !== "pending") {
            throw new functions.https.HttpsError("failed-precondition", `Pairing already ${pairingData.status}`);
        }

        // Check expiration
        if (Date.now() > pairingData.expiresAt) {
            await pairingRef.update({ status: "expired" });
            throw new functions.https.HttpsError("deadline-exceeded", "Pairing request expired");
        }

        const pairedUid = context.auth.uid;

        if (!approved) {
            // User rejected the pairing
            await pairingRef.update({
                status: "rejected",
                rejectedAt: admin.database.ServerValue.TIMESTAMP,
                rejectedBy: pairedUid,
            });
            return { success: true, status: "rejected" };
        }

        // User approved - create device and custom token
        // For V2: deviceId is the persistent device ID (mac_xxxx, web_xxxx)
        // For V1: use existingDeviceId or generate new one
        const deviceId = pairingData.deviceId || pairingData.existingDeviceId || `dev_${crypto.randomUUID().replace(/-/g, "").slice(0, 12)}`;
        const deviceUid = `device_${deviceId}`;

        // Handle V2 vs V1 field naming (V2 uses deviceType, V1 uses platform)
        const platform = pairingData.deviceType || pairingData.platform || "web";
        const deviceName = pairingData.deviceName || "Desktop";

        // Create or update device entry
        await admin.database()
            .ref(`/users/${pairedUid}/devices/${deviceId}`)
            .set({
                name: deviceName,
                type: platform,
                platform: platform,
                isPaired: true,
                pairedAt: admin.database.ServerValue.TIMESTAMP,
                lastSeen: admin.database.ServerValue.TIMESTAMP,
                pairingVersion: isV2Path ? 2 : 1,
            });

        // Generate custom auth token for Web/macOS device
        const customToken = await admin.auth().createCustomToken(deviceUid, {
            pairedUid,
            deviceId,
            deviceType: platform,
            pairingVersion: isV2Path ? 2 : 1,
        });

        // Update pairing status with the auth token
        await pairingRef.update({
            status: "approved",
            approvedAt: admin.database.ServerValue.TIMESTAMP,
            approvedBy: pairedUid,
            pairedUid,
            deviceId,
            customToken, // Web/macOS will read this to complete auth
        });

        return {
            success: true,
            status: "approved",
            deviceId,
            customToken,
            userId: pairedUid
        };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error("completePairing failed");
        throw new functions.https.HttpsError("internal", "Failed to complete pairing");
    }
});

/**
 * Cleanup old/unused device entries (runs daily)
 */
exports.cleanupOldDevices = functions.pubsub.schedule('every 24 hours').onRun(async () => {
    try {
        const usersRef = admin.database().ref('users');
        const usersSnapshot = await usersRef.once('value');

        if (!usersSnapshot.exists()) return;

        const now = Date.now();
        const thirtyDaysAgo = now - (30 * 24 * 60 * 60 * 1000);
        let totalCleaned = 0;

        const users = usersSnapshot.val();
        for (const [userId, userData] of Object.entries(users)) {
            if (!userData.devices) continue;

            const devices = userData.devices;
            const devicesToClean = [];
            const devicesByPlatform = new Map();

            for (const [deviceId, deviceData] of Object.entries(devices)) {
                const platform = deviceData.platform || 'unknown';
                const lastSeen = deviceData.lastSeen || 0;

                if (!devicesByPlatform.has(platform)) {
                    devicesByPlatform.set(platform, []);
                }
                devicesByPlatform.get(platform).push({ deviceId, lastSeen });
            }

            for (const [platform, deviceList] of devicesByPlatform) {
                if (deviceList.length > 1) {
                    deviceList.sort((a, b) => b.lastSeen - a.lastSeen);
                    for (let i = 1; i < deviceList.length; i++) {
                        if (deviceList[i].lastSeen < thirtyDaysAgo) {
                            devicesToClean.push(deviceList[i].deviceId);
                        }
                    }
                }
            }

            for (const deviceId of devicesToClean) {
                await usersRef.child(userId).child('devices').child(deviceId).remove();
                totalCleaned++;
            }
        }
    } catch (error) {
        console.error('cleanupOldDevices failed');
    }
});

/**
 * Unregister a device (called when device unpairs itself)
 */
exports.unregisterDevice = functions.https.onCall(async (data, context) => {
    try {
        if (!context.auth) {
            throw new functions.https.HttpsError("unauthenticated", "Authentication required");
        }

        const deviceId = data && data.deviceId ? String(data.deviceId) : "";
        if (!deviceId) {
            throw new functions.https.HttpsError("invalid-argument", "Device ID required");
        }

        const userId = context.auth.token?.pairedUid || context.auth.uid;
        const deviceRef = admin.database().ref(`/users/${userId}/devices/${deviceId}`);
        const deviceSnapshot = await deviceRef.once('value');

        if (!deviceSnapshot.exists()) {
            return { success: true, deviceId, message: "Device not found (already removed)" };
        }

        await deviceRef.remove();

        // Clean up device-specific data
        const cleanupPaths = ['device_cache', 'device_temp', 'device_sessions'];
        for (const path of cleanupPaths) {
            try {
                await admin.database().ref(`/users/${userId}/${path}/${deviceId}`).remove();
            } catch (e) {
                // Ignore - paths may not exist
            }
        }

        return { success: true, deviceId };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error('unregisterDevice failed');
        throw new functions.https.HttpsError("internal", "Failed to unregister device");
    }
});

/**
 * Sync call history from Android to Firebase
 * Used to avoid OOM from Firebase WebSocket sync on Android
 */
exports.syncCallHistory = functions.https.onCall(async (data, context) => {
    try {
        if (!context.auth) {
            throw new functions.https.HttpsError("unauthenticated", "Authentication required");
        }

        const userId = data?.userId || context.auth.uid;
        const callLogs = data?.callLogs;

        if (!callLogs || !Array.isArray(callLogs)) {
            throw new functions.https.HttpsError("invalid-argument", "callLogs array required");
        }

        if (callLogs.length === 0) {
            return { success: true, count: 0 };
        }

        // Convert array to object keyed by call ID
        const callLogsMap = {};
        for (const call of callLogs) {
            const callId = call.id || `${call.phoneNumber}_${call.callDate}`;
            callLogsMap[callId] = {
                phoneNumber: call.phoneNumber || "",
                contactName: call.contactName || "",
                callType: call.callType || "Unknown",
                callDate: call.callDate || Date.now(),
                duration: call.duration || 0,
                formattedDuration: call.formattedDuration || "0:00",
                formattedDate: call.formattedDate || "",
                simId: call.simId || 0,
                syncedAt: admin.database.ServerValue.TIMESTAMP
            };
        }

        // Write to Firebase
        await admin.database()
            .ref(`/users/${userId}/call_history`)
            .update(callLogsMap);

        return { success: true, count: callLogs.length };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error('syncCallHistory failed:', error.message);
        throw new functions.https.HttpsError("internal", "Failed to sync call history");
    }
});

/**
 * Sync contacts from Android to Firebase
 * Used to avoid OOM from Firebase WebSocket sync on Android
 */
exports.syncContacts = functions.https.onCall(async (data, context) => {
    try {
        if (!context.auth) {
            throw new functions.https.HttpsError("unauthenticated", "Authentication required");
        }

        const userId = data?.userId || context.auth.uid;
        const contacts = data?.contacts;

        if (!contacts || !Array.isArray(contacts)) {
            throw new functions.https.HttpsError("invalid-argument", "contacts array required");
        }

        if (contacts.length === 0) {
            return { success: true, count: 0 };
        }

        // Convert array to object keyed by contact ID
        const contactsMap = {};
        for (const contact of contacts) {
            const contactId = contact.id || contact.phoneNumber;
            contactsMap[contactId] = {
                displayName: contact.displayName || "",
                phoneNumbers: contact.phoneNumbers || {},
                photo: contact.photo || null,
                email: contact.email || null,
                notes: contact.notes || null,
                syncedAt: admin.database.ServerValue.TIMESTAMP
            };
        }

        // Write to Firebase
        await admin.database()
            .ref(`/users/${userId}/contacts`)
            .update(contactsMap);

        return { success: true, count: contacts.length };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error('syncContacts failed:', error.message);
        throw new functions.https.HttpsError("internal", "Failed to sync contacts");
    }
});

/**
 * Recover account using recovery code
 * This is fast because it uses Cloud Functions instead of direct Firebase access
 */
exports.recoverAccount = functions.https.onCall(async (data, context) => {
    try {
        const codeHash = data?.codeHash;

        if (!codeHash || typeof codeHash !== 'string') {
            throw new functions.https.HttpsError("invalid-argument", "codeHash required");
        }

        console.log(`Recovery attempt with hash: ${codeHash.substring(0, 8)}...`);

        // Look up the recovery code
        const recoveryRef = admin.database().ref(`recovery_codes/${codeHash}`);
        const snapshot = await recoveryRef.once('value');

        if (!snapshot.exists()) {
            console.log('Recovery code not found');
            throw new functions.https.HttpsError("not-found", "Invalid recovery code");
        }

        const recoveryData = snapshot.val();
        const userId = recoveryData.userId;

        if (!userId) {
            throw new functions.https.HttpsError("internal", "Recovery code is corrupted");
        }

        // Check if account is marked for deletion
        const deletionSnap = await admin.database().ref(`users/${userId}/deletion_scheduled`).once('value');
        if (deletionSnap.exists()) {
            const deletion = deletionSnap.val();
            const daysRemaining = Math.ceil((deletion.scheduledDeletionAt - Date.now()) / (24 * 60 * 60 * 1000));
            console.log(`Recovery blocked - account ${userId} is scheduled for deletion`);
            throw new functions.https.HttpsError(
                "failed-precondition",
                `This account is scheduled for deletion in ${Math.max(0, daysRemaining)} days. Recovery is disabled. If you want to keep your account, please contact support.`
            );
        }

        // Update last used timestamp
        await recoveryRef.child('lastUsedAt').set(admin.database.ServerValue.TIMESTAMP);

        console.log(`Recovery successful for user: ${userId}`);

        return {
            success: true,
            userId: userId
        };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error('recoverAccount failed:', error.message);
        throw new functions.https.HttpsError("internal", "Recovery failed");
    }
});

/**
 * Get user usage data (fast via Cloud Function)
 */
exports.getUserUsage = functions.https.onCall(async (data, context) => {
    try {
        const userId = data?.userId || context.auth?.uid;

        if (!userId) {
            throw new functions.https.HttpsError("unauthenticated", "User ID required");
        }

        // Get usage data
        const usageRef = admin.database().ref(`users/${userId}/usage`);
        const usageSnapshot = await usageRef.once('value');

        // Get subscription records as fallback
        const subscriptionRef = admin.database().ref(`subscription_records/${userId}/active`);
        const subscriptionSnapshot = await subscriptionRef.once('value');

        const usage = usageSnapshot.val() || {};
        const subscription = subscriptionSnapshot.val() || {};

        // Merge data, preferring usage but falling back to subscription
        return {
            success: true,
            usage: {
                plan: usage.plan || subscription.plan || null,
                planExpiresAt: usage.planExpiresAt || subscription.planExpiresAt || null,
                trialStartedAt: usage.trialStartedAt || null,
                storageBytes: usage.storageBytes || 0,
                lastUpdatedAt: usage.lastUpdatedAt || null,
                monthly: usage.monthly || {}
            }
        };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error('getUserUsage failed:', error.message);
        throw new functions.https.HttpsError("internal", "Failed to get usage data");
    }
});

/**
 * Clear MMS media data from Firebase Storage
 * This frees up storage quota by deleting synced MMS attachments
 * Note: This does NOT delete the message text, only the media files
 */
exports.clearMmsData = functions.https.onCall(async (data, context) => {
    try {
        // Use syncGroupUserId if provided (Mac/Web), otherwise fall back to auth uid
        const syncGroupUserId = data?.syncGroupUserId;
        const authUserId = context.auth?.uid;
        const userId = syncGroupUserId || authUserId;

        if (!userId) {
            throw new functions.https.HttpsError("unauthenticated", "User ID required");
        }

        console.log(`[clearMmsData] Clearing MMS data for user: ${userId}`);

        // Get list of all MMS media files in Storage
        const bucket = admin.storage().bucket();

        let deletedCount = 0;
        let freedBytes = 0;

        // MMS attachments are stored at: users/{userId}/attachments/
        const attachmentsPrefix = `users/${userId}/attachments/`;
        try {
            const [files] = await bucket.getFiles({ prefix: attachmentsPrefix });
            console.log(`[clearMmsData] Found ${files.length} attachment files`);

            for (const file of files) {
                try {
                    // Get file metadata for size tracking
                    const [metadata] = await file.getMetadata();
                    const fileSize = parseInt(metadata.size || '0', 10);
                    freedBytes += fileSize;

                    await file.delete();
                    deletedCount++;
                } catch (err) {
                    console.warn(`[clearMmsData] Failed to delete file ${file.name}:`, err.message);
                }
            }
        } catch (storageError) {
            console.warn(`[clearMmsData] Error listing attachment files:`, storageError.message);
        }

        // Also check legacy mms_attachments path (if any files exist there)
        const legacyMmsPrefix = `mms_attachments/${userId}/`;
        try {
            const [legacyFiles] = await bucket.getFiles({ prefix: legacyMmsPrefix });
            for (const file of legacyFiles) {
                try {
                    const [metadata] = await file.getMetadata();
                    const fileSize = parseInt(metadata.size || '0', 10);
                    freedBytes += fileSize;
                    await file.delete();
                    deletedCount++;
                } catch (err) {
                    console.warn(`[clearMmsData] Failed to delete legacy file:`, err.message);
                }
            }
        } catch (err) {
            // Ignore errors for legacy path
        }

        // File transfers are stored at: users/{userId}/transfers/
        const transfersPrefix = `users/${userId}/transfers/`;
        try {
            const [transferFiles] = await bucket.getFiles({ prefix: transfersPrefix });
            for (const file of transferFiles) {
                try {
                    const [metadata] = await file.getMetadata();
                    const fileSize = parseInt(metadata.size || '0', 10);
                    freedBytes += fileSize;

                    await file.delete();
                    deletedCount++;
                } catch (err) {
                    console.warn(`[clearMmsData] Failed to delete transfer file:`, err.message);
                }
            }
        } catch (err) {
            console.warn(`[clearMmsData] Error listing transfer files:`, err.message);
        }

        // Reset storage counter in database
        const usageRef = admin.database().ref(`users/${userId}/usage`);
        await usageRef.child('storageBytes').set(0);

        // Also reset monthly mmsBytes and fileBytes counters
        const usageSnapshot = await usageRef.once('value');
        const usage = usageSnapshot.val() || {};
        const monthly = usage.monthly || {};

        const updates = {};
        for (const periodKey of Object.keys(monthly)) {
            updates[`monthly/${periodKey}/mmsBytes`] = 0;
            updates[`monthly/${periodKey}/fileBytes`] = 0;
        }
        updates['lastUpdatedAt'] = admin.database.ServerValue.TIMESTAMP;

        if (Object.keys(updates).length > 1) {
            await usageRef.update(updates);
        }

        // Also clear R2 files if R2 is configured
        let r2DeletedCount = 0;
        let r2FreedBytes = 0;
        const r2Client = getR2Client();

        if (r2Client) {
            const bucket = getR2Bucket();
            let continuationToken = null;

            // Clear files/ prefix
            do {
                try {
                    const listCommand = new ListObjectsV2Command({
                        Bucket: bucket,
                        Prefix: `files/${userId}/`,
                        ContinuationToken: continuationToken,
                    });
                    const listResponse = await r2Client.send(listCommand);

                    if (listResponse.Contents) {
                        for (const obj of listResponse.Contents) {
                            try {
                                await r2Client.send(new DeleteObjectCommand({ Bucket: bucket, Key: obj.Key }));
                                r2DeletedCount++;
                                r2FreedBytes += obj.Size || 0;
                            } catch (err) {
                                console.warn(`[clearMmsData] Failed to delete R2 file:`, err.message);
                            }
                        }
                    }
                    continuationToken = listResponse.NextContinuationToken;
                } catch (err) {
                    console.warn(`[clearMmsData] Error listing R2 files:`, err.message);
                    break;
                }
            } while (continuationToken);

            // Clear mms/ prefix
            continuationToken = null;
            do {
                try {
                    const listCommand = new ListObjectsV2Command({
                        Bucket: bucket,
                        Prefix: `mms/${userId}/`,
                        ContinuationToken: continuationToken,
                    });
                    const listResponse = await r2Client.send(listCommand);

                    if (listResponse.Contents) {
                        for (const obj of listResponse.Contents) {
                            try {
                                await r2Client.send(new DeleteObjectCommand({ Bucket: bucket, Key: obj.Key }));
                                r2DeletedCount++;
                                r2FreedBytes += obj.Size || 0;
                            } catch (err) {
                                console.warn(`[clearMmsData] Failed to delete R2 mms file:`, err.message);
                            }
                        }
                    }
                    continuationToken = listResponse.NextContinuationToken;
                } catch (err) {
                    console.warn(`[clearMmsData] Error listing R2 mms files:`, err.message);
                    break;
                }
            } while (continuationToken);

            console.log(`[clearMmsData] Cleared ${r2DeletedCount} R2 files, freed ~${Math.round(r2FreedBytes / 1024 / 1024)}MB`);
        }

        const totalDeleted = deletedCount + r2DeletedCount;
        const totalFreed = freedBytes + r2FreedBytes;

        console.log(`[clearMmsData] Total cleared: ${totalDeleted} files, freed ~${Math.round(totalFreed / 1024 / 1024)}MB`);

        return {
            success: true,
            deletedFiles: totalDeleted,
            freedBytes: totalFreed,
            message: `Cleared ${totalDeleted} media files. Storage usage has been reset.`
        };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error('[clearMmsData] Error:', error);
        throw new functions.https.HttpsError("internal", "Failed to clear MMS data");
    }
});

// ============================================================================
// CLOUDFLARE R2 FILE TRANSFER FUNCTIONS
// ============================================================================

/**
 * Generate a presigned URL for uploading a file to R2
 * This allows clients to upload directly to R2 without going through Firebase
 */
exports.getR2UploadUrl = functions.https.onCall(async (data, context) => {
    try {
        const syncGroupUserId = data?.syncGroupUserId;
        const authUserId = context.auth?.uid;
        const userId = syncGroupUserId || authUserId;

        if (!userId) {
            throw new functions.https.HttpsError("unauthenticated", "User ID required");
        }

        const { fileName, contentType, fileSize, transferType } = data;

        if (!fileName || !contentType || !fileSize) {
            throw new functions.https.HttpsError("invalid-argument", "fileName, contentType, and fileSize are required");
        }

        // Check file size limits
        const usageRef = admin.database().ref(`users/${userId}/usage`);
        const usageSnapshot = await usageRef.once('value');
        const usage = usageSnapshot.val() || {};

        const plan = usage.plan?.toLowerCase();
        const planExpiresAt = usage.planExpiresAt;
        const now = Date.now();

        const isPaid = (plan === "lifetime" || plan === "3year") ||
            ((plan === "monthly" || plan === "yearly" || plan === "paid") &&
             (!planExpiresAt || planExpiresAt > now));

        const maxFileSize = isPaid ? 500 * 1024 * 1024 : 50 * 1024 * 1024; // 500MB pro, 50MB free

        if (fileSize > maxFileSize) {
            const maxMB = maxFileSize / (1024 * 1024);
            throw new functions.https.HttpsError(
                "resource-exhausted",
                `File too large. Max size: ${maxMB}MB${isPaid ? "" : " (Upgrade to Pro for 500MB)"}`
            );
        }

        // Check storage quota
        const storageLimit = isPaid ? 2 * 1024 * 1024 * 1024 : 100 * 1024 * 1024; // 2GB pro, 100MB free
        const currentStorage = usage.storageBytes || 0;

        if (currentStorage + fileSize > storageLimit) {
            throw new functions.https.HttpsError(
                "resource-exhausted",
                "Storage limit reached. Clear old files or upgrade your plan."
            );
        }

        const r2Client = getR2Client();
        if (!r2Client) {
            throw new functions.https.HttpsError("unavailable", "R2 storage not configured");
        }

        // Generate unique file key based on transfer type
        const fileId = `${Date.now()}_${crypto.randomBytes(8).toString('hex')}`;
        const messageId = data.messageId; // Optional, for MMS
        let fileKey;

        switch (transferType) {
            case 'photos':
            case 'photo':
                // Photos: photos/{userId}/{fileId}.{ext}
                const photoExt = fileName.includes('.') ? fileName.split('.').pop() : 'jpg';
                fileKey = `photos/${userId}/${fileId}.${photoExt}`;
                break;
            case 'mms':
                // MMS: mms/{userId}/{messageId}/{fileId}.{ext}
                const mmsExt = fileName.includes('.') ? fileName.split('.').pop() : 'bin';
                const msgId = messageId || fileId;
                fileKey = `mms/${userId}/${msgId}/${fileId}.${mmsExt}`;
                break;
            default:
                // Files: files/{userId}/{fileId}/{fileName}
                fileKey = `files/${userId}/${fileId}/${fileName}`;
        }

        // Generate presigned upload URL (valid for 1 hour)
        const command = new PutObjectCommand({
            Bucket: getR2Bucket(),
            Key: fileKey,
            ContentType: contentType,
            ContentLength: fileSize,
        });

        const uploadUrl = await getSignedUrl(r2Client, command, { expiresIn: 3600 });

        console.log(`[R2] Generated upload URL for ${fileName} (${fileSize} bytes) -> ${fileKey}`);

        return {
            success: true,
            uploadUrl: uploadUrl,
            fileKey: fileKey,
            fileId: fileId,
            expiresIn: 3600
        };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error('[getR2UploadUrl] Error:', error);
        throw new functions.https.HttpsError("internal", "Failed to generate upload URL");
    }
});

/**
 * Confirm R2 upload completed and record usage
 */
exports.confirmR2Upload = functions.https.onCall(async (data, context) => {
    try {
        const syncGroupUserId = data?.syncGroupUserId;
        const authUserId = context.auth?.uid;
        const userId = syncGroupUserId || authUserId;

        if (!userId) {
            throw new functions.https.HttpsError("unauthenticated", "User ID required");
        }

        const { fileKey, fileSize, transferType, photoMetadata } = data;

        if (!fileKey || !fileSize) {
            throw new functions.https.HttpsError("invalid-argument", "fileKey and fileSize are required");
        }

        // Record usage in Firebase
        const periodKey = new Date().toISOString().slice(0, 7).replace('-', ''); // YYYYMM
        const usageRef = admin.database().ref(`users/${userId}/usage`);

        const updates = {
            [`monthly/${periodKey}/uploadBytes`]: admin.database.ServerValue.increment(fileSize),
            'storageBytes': admin.database.ServerValue.increment(fileSize),
            'lastUpdatedAt': admin.database.ServerValue.TIMESTAMP
        };

        if (transferType === 'mms') {
            updates[`monthly/${periodKey}/mmsBytes`] = admin.database.ServerValue.increment(fileSize);
        } else if (transferType === 'photo' || transferType === 'photos') {
            updates[`monthly/${periodKey}/photoBytes`] = admin.database.ServerValue.increment(fileSize);

            // For photos, also store the photo metadata in the database
            if (photoMetadata) {
                const photoId = photoMetadata.id || fileKey.split('/').pop()?.split('.')[0];
                const photoRef = admin.database().ref(`users/${userId}/photos/${photoId}`);
                await photoRef.set({
                    id: photoId,
                    r2Key: fileKey,
                    originalId: photoMetadata.originalId || null,
                    fileName: photoMetadata.fileName || null,
                    dateTaken: photoMetadata.dateTaken || null,
                    width: photoMetadata.width || null,
                    height: photoMetadata.height || null,
                    size: fileSize,
                    mimeType: photoMetadata.mimeType || 'image/jpeg',
                    syncedAt: admin.database.ServerValue.TIMESTAMP
                });
            }
        } else {
            updates[`monthly/${periodKey}/fileBytes`] = admin.database.ServerValue.increment(fileSize);
        }

        await usageRef.update(updates);

        console.log(`[R2] Confirmed upload: ${fileKey} (${fileSize} bytes, type: ${transferType})`);

        return { success: true };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error('[confirmR2Upload] Error:', error);
        throw new functions.https.HttpsError("internal", "Failed to confirm upload");
    }
});

/**
 * Generate a presigned URL for downloading a file from R2
 */
exports.getR2DownloadUrl = functions.https.onCall(async (data, context) => {
    try {
        const syncGroupUserId = data?.syncGroupUserId;
        const authUserId = context.auth?.uid;
        const userId = syncGroupUserId || authUserId;

        if (!userId) {
            throw new functions.https.HttpsError("unauthenticated", "User ID required");
        }

        const { fileKey } = data;

        if (!fileKey) {
            throw new functions.https.HttpsError("invalid-argument", "fileKey is required");
        }

        // Verify the file belongs to this user
        if (!fileKey.includes(`/${userId}/`)) {
            throw new functions.https.HttpsError("permission-denied", "Access denied to this file");
        }

        const r2Client = getR2Client();
        if (!r2Client) {
            throw new functions.https.HttpsError("unavailable", "R2 storage not configured");
        }

        // Generate presigned download URL (valid for 1 hour)
        const command = new GetObjectCommand({
            Bucket: getR2Bucket(),
            Key: fileKey,
        });

        const downloadUrl = await getSignedUrl(r2Client, command, { expiresIn: 3600 });

        return {
            success: true,
            downloadUrl: downloadUrl,
            expiresIn: 3600
        };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error('[getR2DownloadUrl] Error:', error);
        throw new functions.https.HttpsError("internal", "Failed to generate download URL");
    }
});

/**
 * Delete a file from R2 and update usage
 */
exports.deleteR2File = functions.https.onCall(async (data, context) => {
    try {
        const syncGroupUserId = data?.syncGroupUserId;
        const authUserId = context.auth?.uid;
        const userId = syncGroupUserId || authUserId;

        if (!userId) {
            throw new functions.https.HttpsError("unauthenticated", "User ID required");
        }

        const { fileKey, fileSize } = data;

        if (!fileKey) {
            throw new functions.https.HttpsError("invalid-argument", "fileKey is required");
        }

        // Verify the file belongs to this user
        if (!fileKey.includes(`/${userId}/`)) {
            throw new functions.https.HttpsError("permission-denied", "Access denied to this file");
        }

        const r2Client = getR2Client();
        if (!r2Client) {
            throw new functions.https.HttpsError("unavailable", "R2 storage not configured");
        }

        // Delete the file
        const command = new DeleteObjectCommand({
            Bucket: getR2Bucket(),
            Key: fileKey,
        });

        await r2Client.send(command);

        // Update storage usage if fileSize provided
        if (fileSize && fileSize > 0) {
            const usageRef = admin.database().ref(`users/${userId}/usage`);
            await usageRef.update({
                'storageBytes': admin.database.ServerValue.increment(-fileSize),
                'lastUpdatedAt': admin.database.ServerValue.TIMESTAMP
            });
        }

        console.log(`[R2] Deleted file: ${fileKey}`);

        return { success: true };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error('[deleteR2File] Error:', error);
        throw new functions.https.HttpsError("internal", "Failed to delete file");
    }
});

/**
 * Clear all R2 files for a user (called from clearMmsData)
 */
exports.clearR2Files = functions.https.onCall(async (data, context) => {
    try {
        const syncGroupUserId = data?.syncGroupUserId;
        const authUserId = context.auth?.uid;
        const userId = syncGroupUserId || authUserId;

        if (!userId) {
            throw new functions.https.HttpsError("unauthenticated", "User ID required");
        }

        const r2Client = getR2Client();
        if (!r2Client) {
            // R2 not configured, return success (nothing to delete)
            return { success: true, deletedFiles: 0, freedBytes: 0 };
        }

        const bucket = getR2Bucket();
        let deletedCount = 0;
        let freedBytes = 0;
        let continuationToken = null;

        // List and delete all files for this user
        do {
            const listCommand = new ListObjectsV2Command({
                Bucket: bucket,
                Prefix: `files/${userId}/`,
                ContinuationToken: continuationToken,
            });

            const listResponse = await r2Client.send(listCommand);

            if (listResponse.Contents) {
                for (const obj of listResponse.Contents) {
                    try {
                        const deleteCommand = new DeleteObjectCommand({
                            Bucket: bucket,
                            Key: obj.Key,
                        });
                        await r2Client.send(deleteCommand);
                        deletedCount++;
                        freedBytes += obj.Size || 0;
                    } catch (err) {
                        console.warn(`[clearR2Files] Failed to delete ${obj.Key}:`, err.message);
                    }
                }
            }

            continuationToken = listResponse.NextContinuationToken;
        } while (continuationToken);

        // Also clear MMS files
        continuationToken = null;
        do {
            const listCommand = new ListObjectsV2Command({
                Bucket: bucket,
                Prefix: `mms/${userId}/`,
                ContinuationToken: continuationToken,
            });

            const listResponse = await r2Client.send(listCommand);

            if (listResponse.Contents) {
                for (const obj of listResponse.Contents) {
                    try {
                        const deleteCommand = new DeleteObjectCommand({
                            Bucket: bucket,
                            Key: obj.Key,
                        });
                        await r2Client.send(deleteCommand);
                        deletedCount++;
                        freedBytes += obj.Size || 0;
                    } catch (err) {
                        console.warn(`[clearR2Files] Failed to delete ${obj.Key}:`, err.message);
                    }
                }
            }

            continuationToken = listResponse.NextContinuationToken;
        } while (continuationToken);

        // Also clear photos files
        continuationToken = null;
        do {
            const listCommand = new ListObjectsV2Command({
                Bucket: bucket,
                Prefix: `photos/${userId}/`,
                ContinuationToken: continuationToken,
            });

            const listResponse = await r2Client.send(listCommand);

            if (listResponse.Contents) {
                for (const obj of listResponse.Contents) {
                    try {
                        const deleteCommand = new DeleteObjectCommand({
                            Bucket: bucket,
                            Key: obj.Key,
                        });
                        await r2Client.send(deleteCommand);
                        deletedCount++;
                        freedBytes += obj.Size || 0;
                    } catch (err) {
                        console.warn(`[clearR2Files] Failed to delete ${obj.Key}:`, err.message);
                    }
                }
            }

            continuationToken = listResponse.NextContinuationToken;
        } while (continuationToken);

        // Reset user's storage counter
        if (freedBytes > 0) {
            const usageRef = admin.database().ref(`users/${userId}/usage`);
            await usageRef.update({
                'storageBytes': 0,
                'lastUpdatedAt': admin.database.ServerValue.TIMESTAMP
            });
        }

        console.log(`[clearR2Files] Cleared ${deletedCount} R2 files, freed ~${Math.round(freedBytes / 1024 / 1024)}MB`);

        return {
            success: true,
            deletedFiles: deletedCount,
            freedBytes: freedBytes
        };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error('[clearR2Files] Error:', error);
        throw new functions.https.HttpsError("internal", "Failed to clear R2 files");
    }
});

/**
 * Get R2 storage analytics (admin function)
 * Lists all files and calculates storage statistics
 */
exports.getR2Analytics = functions.https.onCall(async (data, context) => {
    requireAdmin(context);

    try {
        const r2Client = getR2Client();
        if (!r2Client) {
            return {
                success: true,
                totalFiles: 0,
                totalSize: 0,
                fileCounts: { files: 0, mms: 0, photos: 0 },
                sizeCounts: { files: 0, mms: 0, photos: 0 },
                largestFiles: [],
                oldestFiles: [],
                estimatedCost: 0,
                userStorage: []
            };
        }

        const bucket = getR2Bucket();
        const allFiles = [];
        const fileCounts = { files: 0, mms: 0, photos: 0 };
        const sizeCounts = { files: 0, mms: 0, photos: 0 };

        // List all objects with pagination
        let continuationToken = null;
        do {
            const listCommand = new ListObjectsV2Command({
                Bucket: bucket,
                ContinuationToken: continuationToken,
            });
            const response = await r2Client.send(listCommand);

            if (response.Contents) {
                response.Contents.forEach(obj => {
                    const key = obj.Key || '';
                    const size = obj.Size || 0;
                    const uploadedAt = obj.LastModified?.getTime() || 0;

                    // Determine type from key prefix
                    let type = 'files';
                    if (key.startsWith('mms/')) {
                        type = 'mms';
                        fileCounts.mms++;
                        sizeCounts.mms += size;
                    } else if (key.startsWith('photos/')) {
                        type = 'photos';
                        fileCounts.photos++;
                        sizeCounts.photos += size;
                    } else {
                        fileCounts.files++;
                        sizeCounts.files += size;
                    }

                    allFiles.push({ key, size, uploadedAt, type });
                });
            }
            continuationToken = response.NextContinuationToken;
        } while (continuationToken);

        // Calculate totals
        const totalSize = sizeCounts.files + sizeCounts.mms + sizeCounts.photos;

        // Get top 10 largest files
        const largestFiles = [...allFiles]
            .sort((a, b) => b.size - a.size)
            .slice(0, 10);

        // Get top 10 oldest files
        const oldestFiles = [...allFiles]
            .filter(f => f.uploadedAt > 0)
            .sort((a, b) => a.uploadedAt - b.uploadedAt)
            .slice(0, 10);

        // R2 pricing: $0.015 per GB/month
        const estimatedCost = (totalSize / (1024 * 1024 * 1024)) * 0.015;

        // Get per-user storage from Firebase (tracked in real-time)
        const usersSnapshot = await admin.database().ref('users').once('value');
        const userStorage = [];

        usersSnapshot.forEach(userSnapshot => {
            const userId = userSnapshot.key;
            const usage = userSnapshot.child('usage').val();
            if (usage && usage.storageBytes > 0) {
                userStorage.push({
                    userId,
                    storageBytes: usage.storageBytes || 0,
                    lastUpdatedAt: usage.lastUpdatedAt || 0
                });
            }
        });

        // Sort by storage size descending, take top 20
        userStorage.sort((a, b) => b.storageBytes - a.storageBytes);
        const topUserStorage = userStorage.slice(0, 20);

        console.log(`[getR2Analytics] Found ${allFiles.length} files, ${(totalSize / 1024 / 1024).toFixed(2)}MB total, ${userStorage.length} users with storage`);

        return {
            success: true,
            totalFiles: allFiles.length,
            totalSize,
            fileCounts,
            sizeCounts,
            largestFiles,
            oldestFiles,
            estimatedCost,
            userStorage: topUserStorage,
            totalUsersWithStorage: userStorage.length
        };
    } catch (error) {
        console.error('[getR2Analytics] Error:', error);
        throw new functions.https.HttpsError("internal", "Failed to get R2 analytics");
    }
});

/**
 * Cleanup old R2 files (admin function)
 * Deletes files older than specified days threshold
 */
exports.cleanupOldR2Files = functions.https.onCall(async (data, context) => {
    requireAdmin(context);

    try {
        const daysThreshold = data?.daysThreshold || 90;
        const fileType = data?.fileType; // Optional: 'files', 'mms', 'photos', or null for all
        const cutoffDate = Date.now() - (daysThreshold * 24 * 60 * 60 * 1000);

        const r2Client = getR2Client();
        if (!r2Client) {
            return { success: true, deletedFiles: 0, freedBytes: 0 };
        }

        const bucket = getR2Bucket();
        let deletedCount = 0;
        let freedBytes = 0;
        let continuationToken = null;

        // Track deleted bytes per user for storage counter updates
        const userDeletedBytes = {};

        // Build prefix filter if specific type requested
        const prefix = fileType ? `${fileType}/` : undefined;

        do {
            const listCommand = new ListObjectsV2Command({
                Bucket: bucket,
                Prefix: prefix,
                ContinuationToken: continuationToken,
            });

            const listResponse = await r2Client.send(listCommand);

            if (listResponse.Contents) {
                for (const obj of listResponse.Contents) {
                    const objDate = obj.LastModified?.getTime() || 0;
                    if (objDate > 0 && objDate < cutoffDate) {
                        try {
                            await r2Client.send(new DeleteObjectCommand({
                                Bucket: bucket,
                                Key: obj.Key,
                            }));
                            deletedCount++;
                            const fileSize = obj.Size || 0;
                            freedBytes += fileSize;

                            // Track per-user deleted bytes
                            const userId = extractUserIdFromR2Key(obj.Key);
                            if (userId && fileSize > 0) {
                                userDeletedBytes[userId] = (userDeletedBytes[userId] || 0) + fileSize;
                            }
                        } catch (err) {
                            console.warn(`[cleanupOldR2Files] Failed to delete ${obj.Key}:`, err.message);
                        }
                    }
                }
            }

            continuationToken = listResponse.NextContinuationToken;
        } while (continuationToken);

        // Update storage counters for affected users
        const updatePromises = Object.entries(userDeletedBytes).map(([userId, bytes]) => {
            const usageRef = admin.database().ref(`users/${userId}/usage`);
            return usageRef.update({
                'storageBytes': admin.database.ServerValue.increment(-bytes),
                'lastUpdatedAt': admin.database.ServerValue.TIMESTAMP
            }).catch(err => console.warn(`[cleanupOldR2Files] Failed to update usage for ${userId}:`, err.message));
        });
        await Promise.all(updatePromises);

        console.log(`[cleanupOldR2Files] Deleted ${deletedCount} files older than ${daysThreshold} days, freed ${(freedBytes / 1024 / 1024).toFixed(2)}MB, updated ${Object.keys(userDeletedBytes).length} users`);

        return {
            success: true,
            deletedFiles: deletedCount,
            freedBytes,
            daysThreshold,
            usersAffected: Object.keys(userDeletedBytes).length
        };
    } catch (error) {
        console.error('[cleanupOldR2Files] Error:', error);
        throw new functions.https.HttpsError("internal", "Failed to cleanup old R2 files");
    }
});

/**
 * Get paginated R2 file list (admin function)
 */
exports.getR2FileList = functions.https.onCall(async (data, context) => {
    requireAdmin(context);

    try {
        const fileType = data?.fileType; // Optional: 'files', 'mms', 'photos'
        const limit = Math.min(data?.limit || 100, 1000);
        const continuationToken = data?.continuationToken;

        const r2Client = getR2Client();
        if (!r2Client) {
            return { success: true, files: [], nextToken: null, totalCount: 0 };
        }

        const bucket = getR2Bucket();
        const prefix = fileType ? `${fileType}/` : undefined;

        const listCommand = new ListObjectsV2Command({
            Bucket: bucket,
            Prefix: prefix,
            MaxKeys: limit,
            ContinuationToken: continuationToken,
        });

        const response = await r2Client.send(listCommand);

        const files = (response.Contents || []).map(obj => {
            const key = obj.Key || '';
            let type = 'files';
            if (key.startsWith('mms/')) type = 'mms';
            else if (key.startsWith('photos/')) type = 'photos';

            return {
                key,
                size: obj.Size || 0,
                uploadedAt: obj.LastModified?.getTime() || 0,
                type
            };
        });

        return {
            success: true,
            files,
            nextToken: response.NextContinuationToken || null,
            hasMore: response.IsTruncated || false
        };
    } catch (error) {
        console.error('[getR2FileList] Error:', error);
        throw new functions.https.HttpsError("internal", "Failed to get R2 file list");
    }
});

/**
 * Helper to extract userId from R2 file key
 * Keys follow pattern: {type}/{userId}/...
 */
function extractUserIdFromR2Key(fileKey) {
    const parts = fileKey.split('/');
    if (parts.length >= 2) {
        return parts[1]; // userId is always second segment
    }
    return null;
}

/**
 * Delete a specific R2 file by key (admin function)
 */
exports.deleteR2FileAdmin = functions.https.onCall(async (data, context) => {
    requireAdmin(context);

    try {
        const { fileKey } = data;
        if (!fileKey) {
            throw new functions.https.HttpsError("invalid-argument", "fileKey is required");
        }

        const r2Client = getR2Client();
        if (!r2Client) {
            throw new functions.https.HttpsError("unavailable", "R2 storage not configured");
        }

        const bucket = getR2Bucket();

        // Get file size before deletion for reporting
        let fileSize = 0;
        try {
            const headCommand = new GetObjectCommand({
                Bucket: bucket,
                Key: fileKey,
            });
            const headResponse = await r2Client.send(headCommand);
            fileSize = headResponse.ContentLength || 0;
        } catch (e) {
            // File may not exist
        }

        await r2Client.send(new DeleteObjectCommand({
            Bucket: bucket,
            Key: fileKey,
        }));

        // Update user's storage counter
        if (fileSize > 0) {
            const userId = extractUserIdFromR2Key(fileKey);
            if (userId) {
                const usageRef = admin.database().ref(`users/${userId}/usage`);
                await usageRef.update({
                    'storageBytes': admin.database.ServerValue.increment(-fileSize),
                    'lastUpdatedAt': admin.database.ServerValue.TIMESTAMP
                });
            }
        }

        console.log(`[deleteR2FileAdmin] Deleted: ${fileKey} (${fileSize} bytes)`);

        return {
            success: true,
            deletedKey: fileKey,
            freedBytes: fileSize
        };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error('[deleteR2FileAdmin] Error:', error);
        throw new functions.https.HttpsError("internal", "Failed to delete R2 file");
    }
});

/**
 * Recalculate user's storage based on actual R2 files
 * Admin function to fix storage counter mismatches
 */
exports.recalculateUserStorage = functions.https.onCall(async (data, context) => {
    try {
        const userId = data?.userId;

        if (!userId) {
            throw new functions.https.HttpsError("invalid-argument", "User ID required");
        }

        // Verify admin
        if (!context.auth?.token?.admin) {
            throw new functions.https.HttpsError("permission-denied", "Admin access required");
        }

        // Get R2 client
        const r2Client = getR2Client();
        if (!r2Client) {
            throw new functions.https.HttpsError("failed-precondition", "R2 not configured");
        }

        // Get current storage counter
        const usageRef = admin.database().ref(`users/${userId}/usage`);
        const usageSnapshot = await usageRef.once('value');
        const currentStorageBytes = usageSnapshot.child('storageBytes').val() || 0;

        // List all files for this user in R2
        let actualBytes = 0;
        let fileCount = 0;
        const prefixes = [`mms/${userId}/`, `files/${userId}/`, `photos/${userId}/`];

        for (const prefix of prefixes) {
            let continuationToken = null;

            do {
                const listParams = {
                    Bucket: getR2Bucket(),
                    Prefix: prefix,
                    MaxKeys: 1000
                };

                if (continuationToken) {
                    listParams.ContinuationToken = continuationToken;
                }

                const listResult = await r2Client.send(new ListObjectsV2Command(listParams));

                if (listResult.Contents) {
                    for (const obj of listResult.Contents) {
                        actualBytes += obj.Size || 0;
                        fileCount++;
                    }
                }

                continuationToken = listResult.IsTruncated ? listResult.NextContinuationToken : null;
            } while (continuationToken);
        }

        // Update storage counter to actual value
        await usageRef.update({
            'storageBytes': actualBytes,
            'lastUpdatedAt': admin.database.ServerValue.TIMESTAMP
        });

        const diff = actualBytes - currentStorageBytes;
        console.log(`[recalculateUserStorage] User ${userId}: ${fileCount} files, ${actualBytes} bytes actual, was ${currentStorageBytes} bytes (diff: ${diff})`);

        return {
            success: true,
            userId,
            fileCount,
            actualBytes,
            previousBytes: currentStorageBytes,
            difference: diff
        };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error('[recalculateUserStorage] Error:', error);
        throw new functions.https.HttpsError("internal", "Failed to recalculate storage");
    }
});

/**
 * Get user's paired devices (fast via Cloud Function)
 */
exports.getDevices = functions.https.onCall(async (data, context) => {
    try {
        const userId = data?.userId || context.auth?.uid;

        if (!userId) {
            throw new functions.https.HttpsError("unauthenticated", "User ID required");
        }

        const devicesRef = admin.database().ref(`users/${userId}/devices`);
        const snapshot = await devicesRef.once('value');

        const devices = {};
        if (snapshot.exists()) {
            snapshot.forEach((child) => {
                devices[child.key] = child.val();
            });
        }

        return {
            success: true,
            devices: devices
        };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error('getDevices failed:', error.message);
        throw new functions.https.HttpsError("internal", "Failed to get devices");
    }
});

/**
 * Manual cleanup of old devices (callable function)
 */
exports.cleanupOldDevicesManual = functions.https.onCall(async (data, context) => {
    requireAdmin(context);
    try {
        const usersRef = admin.database().ref('users');
        const usersSnapshot = await usersRef.once('value');

        if (!usersSnapshot.exists()) {
            return { success: true, cleaned: 0, message: 'No users found' };
        }

        const now = Date.now();
        // 7 days threshold for production
        const thresholdMs = 7 * 24 * 60 * 60 * 1000;
        const threshold = now - thresholdMs;
        let totalCleaned = 0;
        let totalDevices = 0;

        const users = usersSnapshot.val();
        for (const [userId, userData] of Object.entries(users)) {
            if (!userData.devices) continue;

            const devices = userData.devices;
            const devicesByPlatform = new Map();

            for (const [deviceId, deviceData] of Object.entries(devices)) {
                totalDevices++;
                const platform = deviceData.platform || 'unknown';
                const lastSeen = deviceData.lastSeen || 0;

                if (!devicesByPlatform.has(platform)) {
                    devicesByPlatform.set(platform, []);
                }
                devicesByPlatform.get(platform).push({
                    deviceId,
                    lastSeen,
                    userId
                });
            }

            // For each platform, keep only the most recent device, clean others
            for (const [platform, deviceList] of devicesByPlatform) {
                if (deviceList.length > 1) {
                    deviceList.sort((a, b) => b.lastSeen - a.lastSeen);

                    for (let i = 1; i < deviceList.length; i++) {
                        const device = deviceList[i];
                        if (device.lastSeen < threshold) {
                            await usersRef.child(device.userId).child('devices').child(device.deviceId).remove();
                            totalCleaned++;
                        }
                    }
                }
            }
        }

        return {
            success: true,
            cleaned: totalCleaned,
            totalDevices,
            message: `Cleaned up ${totalCleaned} old device entries`
        };
    } catch (error) {
        console.error('cleanupOldDevicesManual failed');
        return {
            success: false,
            cleaned: 0,
            message: 'Failed to cleanup old devices'
        };
    }
});

/**
 * Clean up expired pending pairings
 * Runs with the existing cleanup job
 */
exports.cleanupExpiredPairings = functions.pubsub
    .schedule("every 5 minutes")
    .onRun(async () => {
        const now = Date.now();

        try {
            const pairingsRef = admin.database().ref("/pending_pairings");
            const snapshot = await pairingsRef.once("value");

            if (!snapshot.exists()) {
                return null;
            }

            const deletePromises = [];

            snapshot.forEach((pairingSnapshot) => {
                const data = pairingSnapshot.val();
                // Remove if expired (past expiration + 5 min grace) or old completed pairings
                const isExpired = data.expiresAt && (now > data.expiresAt + PAIRING_TTL_MS);
                const isOldCompleted = data.status !== "pending" &&
                    data.createdAt && (now - data.createdAt > 10 * 60 * 1000); // 10 min

                if (isExpired || isOldCompleted) {
                    deletePromises.push(pairingSnapshot.ref.remove());
                }
            });

            if (deletePromises.length > 0) {
                await Promise.all(deletePromises);
            }

            return null;
        } catch (error) {
            console.error("cleanupExpiredPairings failed");
            return null;
        }
    });

exports.createPairingToken = functions.https.onCall(async (data, context) => {
    try {
        if (!context.auth) {
            throw new functions.https.HttpsError("unauthenticated", "Authentication required");
        }

        const deviceType = data && data.deviceType ? String(data.deviceType) : "desktop";
        const uid = context.auth.uid;
        const token = crypto.randomUUID().replace(/-/g, "").slice(0, 12);
        const now = Date.now();

        const payload = {
            uid,
            deviceType,
            createdAt: now,
            expiresAt: now + PAIRING_TTL_MS,
            used: false,
        };

        await admin.database().ref(`/pairing_tokens/${token}`).set(payload);

        return { token, expiresAt: payload.expiresAt };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error("createPairingToken failed");
        throw new functions.https.HttpsError("internal", "Failed to create pairing token");
    }
});

exports.redeemPairingToken = functions.https.onCall(async (data) => {
    try {
        const token = data && data.token ? String(data.token) : "";
        const deviceName = data && data.deviceName ? String(data.deviceName) : "Desktop";
        const deviceType = data && data.deviceType ? String(data.deviceType) : "desktop";

        if (!token) {
            throw new functions.https.HttpsError("invalid-argument", "Missing token");
        }

        const tokenRef = admin.database().ref(`/pairing_tokens/${token}`);
        const snapshot = await tokenRef.once("value");
        if (!snapshot.exists()) {
            throw new functions.https.HttpsError("not-found", "Invalid or expired token");
        }

        const tokenData = snapshot.val();

        if (tokenData.used) {
            throw new functions.https.HttpsError("failed-precondition", "Token already used");
        }

        if (Date.now() > tokenData.expiresAt) {
            await tokenRef.remove();
            throw new functions.https.HttpsError("deadline-exceeded", "Token expired");
        }

        if (tokenData.deviceType &&
            tokenData.deviceType !== deviceType &&
            tokenData.deviceType !== "desktop") {
            throw new functions.https.HttpsError("permission-denied", "Token not valid for this device type");
        }

        const pairedUid = tokenData.uid;
        const deviceId = `dev_${crypto.randomUUID().replace(/-/g, "").slice(0, 12)}`;
        const deviceUid = `device_${deviceId}`;

        await admin.database()
            .ref(`/users/${pairedUid}/devices/${deviceId}`)
            .set({
                name: deviceName,
                type: deviceType,
                platform: deviceType,
                isPaired: true,
                pairedAt: admin.database.ServerValue.TIMESTAMP,
                lastSeen: admin.database.ServerValue.TIMESTAMP,
            });

        const customToken = await admin.auth().createCustomToken(deviceUid, {
            pairedUid,
            deviceId,
            deviceType,
        });

        await tokenRef.update({
            used: true,
            redeemedAt: Date.now(),
            redeemedBy: deviceId,
        });

        return { customToken, pairedUid, deviceId };
    } catch (error) {
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        console.error("redeemPairingToken failed");
        throw new functions.https.HttpsError("internal", "Failed to redeem pairing token");
    }
});

/**
 * Triggered when a new call notification is added to the fcm_notifications queue.
 * Sends an FCM high-priority data message to wake up the recipient's device.
 *
 * Path: fcm_notifications/{userId}/{callId}
 *
 * Data structure:
 * {
 *   type: "incoming_call",
 *   callId: string,
 *   callerName: string,
 *   callerPhone: string,
 *   isVideo: "true" | "false",
 *   timestamp: number
 * }
 */
exports.sendCallNotification = functions.database
    .ref("/fcm_notifications/{userId}/{callId}")
    .onCreate(async (snapshot, context) => {
        const { userId, callId } = context.params;
        const data = snapshot.val();

        if (!data || !data.type || data.type !== "incoming_call") {
            return null;
        }

        try {
            const tokenSnapshot = await admin.database()
                .ref(`/fcm_tokens/${userId}`)
                .once("value");
            const fcmToken = tokenSnapshot.val();

            if (!fcmToken) {
                await snapshot.ref.remove();
                return null;
            }

            const message = {
                token: fcmToken,
                data: {
                    type: data.type,
                    callId: data.callId || callId,
                    callerName: data.callerName || "Unknown",
                    callerPhone: data.callerPhone || "",
                    isVideo: String(data.isVideo || false),
                    timestamp: String(data.timestamp || Date.now()),
                },
                android: {
                    priority: "high",
                    ttl: 60000,
                },
                apns: {
                    headers: {
                        "apns-priority": "10",
                        "apns-push-type": "voip",
                    },
                    payload: {
                        aps: {
                            "content-available": 1,
                            sound: "default",
                        },
                    },
                },
            };

            const response = await admin.messaging().send(message);
            await snapshot.ref.remove();
            return response;
        } catch (error) {
            console.error("sendCallNotification failed");
            if (error.code === "messaging/invalid-registration-token" ||
                error.code === "messaging/registration-token-not-registered") {
                await admin.database().ref(`/fcm_tokens/${userId}`).remove();
            }
            await snapshot.ref.remove();
            return null;
        }
    });

/**
 * Triggered when a call is cancelled or ended.
 * Sends a notification to dismiss the incoming call UI.
 *
 * Path: fcm_notifications/{userId}/{callId}
 * with type: "call_cancelled"
 */
exports.sendCallCancellation = functions.database
    .ref("/fcm_notifications/{userId}/{callId}")
    .onWrite(async (change, context) => {
        // Only handle updates (not creates or deletes)
        if (!change.before.exists() || !change.after.exists()) {
            return null;
        }

        const { userId, callId } = context.params;
        const data = change.after.val();

        if (!data || data.type !== "call_cancelled") {
            return null;
        }

        console.log(`Call cancellation for user ${userId}, call ${callId}`);

        try {
            const tokenSnapshot = await admin.database()
                .ref(`/fcm_tokens/${userId}`)
                .once("value");
            const fcmToken = tokenSnapshot.val();

            if (!fcmToken) {
                await change.after.ref.remove();
                return null;
            }

            const message = {
                token: fcmToken,
                data: {
                    type: "call_cancelled",
                    callId: callId,
                },
                android: {
                    priority: "high",
                },
            };

            await admin.messaging().send(message);
            await change.after.ref.remove();

            return null;
        } catch (error) {
            console.error("Error sending call cancellation:", error);
            await change.after.ref.remove();
            return null;
        }
    });

/**
 * Triggered when a new SyncFlow call is added to a user's incoming_syncflow_calls.
 * Sends an FCM high-priority data message to wake up the Android device.
 *
 * Path: users/{userId}/incoming_syncflow_calls/{callId}
 */
exports.notifyIncomingSyncFlowCall = functions.database
    .ref("/users/{userId}/incoming_syncflow_calls/{callId}")
    .onCreate(async (snapshot, context) => {
        const { userId, callId } = context.params;
        const data = snapshot.val();

        // Only process ringing calls
        if (!data || data.status !== "ringing") {
            console.log(`Ignoring non-ringing call ${callId} with status: ${data ? data.status : "undefined"}`);
            return null;
        }

        console.log(`New incoming SyncFlow call for user ${userId}, call ${callId}:`, JSON.stringify(data));

        try {
            // Get the user's FCM token
            const tokenSnapshot = await admin.database()
                .ref(`/fcm_tokens/${userId}`)
                .once("value");
            const fcmToken = tokenSnapshot.val();

            if (!fcmToken) {
                console.log(`No FCM token found for user ${userId}`);
                return null;
            }

            const isVideo = data.callType === "video";
            const callerName = data.callerName || "Unknown";
            const callerPhone = data.callerPhone || "";

            // Construct the FCM message
            const message = {
                token: fcmToken,
                data: {
                    type: "incoming_call",
                    callId: callId,
                    callerName: callerName,
                    callerPhone: callerPhone,
                    isVideo: String(isVideo),
                    timestamp: String(Date.now()),
                },
                android: {
                    priority: "high",
                    ttl: 60000, // 60 seconds
                },
            };

            console.log("Sending FCM for SyncFlow call:", JSON.stringify(message));

            const response = await admin.messaging().send(message);
            console.log(`Successfully sent FCM message for SyncFlow call: ${response}`);

            return response;
        } catch (error) {
            console.error("Error sending FCM for SyncFlow call:", error);

            if (error.code === "messaging/invalid-registration-token" ||
                error.code === "messaging/registration-token-not-registered") {
                console.log(`Removing invalid FCM token for user ${userId}`);
                await admin.database().ref(`/fcm_tokens/${userId}`).remove();
            }

            return null;
        }
    });

/**
 * Triggered when a SyncFlow call status changes.
 * Sends an FCM message to dismiss the incoming call notification on Android
 * when the call is answered, ended, or rejected.
 *
 * Path: users/{userId}/incoming_syncflow_calls/{callId}
 */
exports.notifySyncFlowCallStatusChange = functions.database
    .ref("/users/{userId}/incoming_syncflow_calls/{callId}/status")
    .onUpdate(async (change, context) => {
        const { userId, callId } = context.params;
        const beforeStatus = change.before.val();
        const afterStatus = change.after.val();

        // Only send notification when status changes FROM "ringing" to something else
        if (beforeStatus !== "ringing" || afterStatus === "ringing") {
            return null;
        }

        console.log(`SyncFlow call ${callId} status changed: ${beforeStatus} -> ${afterStatus}`);

        try {
            // Get the user's FCM token
            const tokenSnapshot = await admin.database()
                .ref(`/fcm_tokens/${userId}`)
                .once("value");
            const fcmToken = tokenSnapshot.val();

            if (!fcmToken) {
                console.log(`No FCM token found for user ${userId}`);
                return null;
            }

            // Construct the FCM message to dismiss the incoming call notification
            const message = {
                token: fcmToken,
                data: {
                    type: "call_status_changed",
                    callId: callId,
                    status: afterStatus,
                    timestamp: String(Date.now()),
                },
                android: {
                    priority: "high",
                    ttl: 30000, // 30 seconds
                },
            };

            console.log("Sending FCM for call status change:", JSON.stringify(message));

            const response = await admin.messaging().send(message);
            console.log(`Successfully sent FCM message for call status change: ${response}`);

            return response;
        } catch (error) {
            console.error("Error sending FCM for call status change:", error);

            if (error.code === "messaging/invalid-registration-token" ||
                error.code === "messaging/registration-token-not-registered") {
                console.log(`Removing invalid FCM token for user ${userId}`);
                await admin.database().ref(`/fcm_tokens/${userId}`).remove();
            }

            return null;
        }
    });

/**
 * Triggered when an outgoing SyncFlow call status changes (receiver ended the call).
 * Sends an FCM message to the caller to end their call.
 *
 * Path: users/{userId}/outgoing_syncflow_calls/{callId}/status
 */
exports.notifyOutgoingSyncFlowCallStatusChange = functions.database
    .ref("/users/{userId}/outgoing_syncflow_calls/{callId}/status")
    .onUpdate(async (change, context) => {
        const { userId, callId } = context.params;
        const beforeStatus = change.before.val();
        const afterStatus = change.after.val();

        // Only send notification when status changes to "ended" (receiver ended the call)
        if (afterStatus !== "ended" || beforeStatus === "ended") {
            return null;
        }

        console.log(`Outgoing SyncFlow call ${callId} status changed: ${beforeStatus} -> ${afterStatus}`);

        try {
            // Get the caller's FCM token
            const tokenSnapshot = await admin.database()
                .ref(`/fcm_tokens/${userId}`)
                .once("value");
            const fcmToken = tokenSnapshot.val();

            if (!fcmToken) {
                console.log(`No FCM token found for caller ${userId}`);
                return null;
            }

            // Construct the FCM message to end the call on the caller's device
            const message = {
                token: fcmToken,
                data: {
                    type: "call_ended_by_remote",
                    callId: callId,
                    status: afterStatus,
                    timestamp: String(Date.now()),
                },
                android: {
                    priority: "high",
                    ttl: 30000, // 30 seconds
                },
            };

            console.log("Sending FCM for outgoing call status change:", JSON.stringify(message));

            const response = await admin.messaging().send(message);
            console.log(`Successfully sent FCM message for outgoing call status change: ${response}`);

            return response;
        } catch (error) {
            console.error("Error sending FCM for outgoing call status change:", error);

            if (error.code === "messaging/invalid-registration-token" ||
                error.code === "messaging/registration-token-not-registered") {
                console.log(`Removing invalid FCM token for user ${userId}`);
                await admin.database().ref(`/fcm_tokens/${userId}`).remove();
            }

            return null;
        }
    });

/**
 * Triggered when a SyncFlow call is deleted (caller hung up or call cleaned up).
 * Sends an FCM message to dismiss the incoming call notification on Android.
 *
 * Path: users/{userId}/incoming_syncflow_calls/{callId}
 */
exports.notifySyncFlowCallDeleted = functions.database
    .ref("/users/{userId}/incoming_syncflow_calls/{callId}")
    .onDelete(async (snapshot, context) => {
        const { userId, callId } = context.params;
        const data = snapshot.val();

        // Only send notification if the call was still ringing when deleted
        if (!data || data.status !== "ringing") {
            return null;
        }

        console.log(`SyncFlow call ${callId} deleted while ringing`);

        try {
            // Get the user's FCM token
            const tokenSnapshot = await admin.database()
                .ref(`/fcm_tokens/${userId}`)
                .once("value");
            const fcmToken = tokenSnapshot.val();

            if (!fcmToken) {
                console.log(`No FCM token found for user ${userId}`);
                return null;
            }

            // Construct the FCM message to dismiss the incoming call notification
            const message = {
                token: fcmToken,
                data: {
                    type: "call_status_changed",
                    callId: callId,
                    status: "cancelled",
                    timestamp: String(Date.now()),
                },
                android: {
                    priority: "high",
                    ttl: 30000, // 30 seconds
                },
            };

            console.log("Sending FCM for call deletion:", JSON.stringify(message));

            const response = await admin.messaging().send(message);
            console.log(`Successfully sent FCM message for call deletion: ${response}`);

            return response;
        } catch (error) {
            console.error("Error sending FCM for call deletion:", error);

            if (error.code === "messaging/invalid-registration-token" ||
                error.code === "messaging/registration-token-not-registered") {
                console.log(`Removing invalid FCM token for user ${userId}`);
                await admin.database().ref(`/fcm_tokens/${userId}`).remove();
            }

            return null;
        }
    });

/**
 * Triggered when a new outgoing message is added for desktop-to-phone SMS.
 * Sends an FCM high-priority data message to wake up the Android device
 * so it can send the SMS without requiring a persistent foreground service.
 *
 * Path: users/{userId}/outgoing_messages/{messageId}
 */
exports.notifyOutgoingMessage = functions.database
    .ref("/users/{userId}/outgoing_messages/{messageId}")
    .onCreate(async (snapshot, context) => {
        const { userId, messageId } = context.params;
        const data = snapshot.val();

        console.log(`New outgoing message for user ${userId}, message ${messageId}:`, JSON.stringify(data));

        try {
            // Get the user's FCM token
            const tokenSnapshot = await admin.database()
                .ref(`/fcm_tokens/${userId}`)
                .once("value");
            const fcmToken = tokenSnapshot.val();

            if (!fcmToken) {
                console.log(`No FCM token found for user ${userId}`);
                return null;
            }

            // Construct the FCM message to wake up Android
            const message = {
                token: fcmToken,
                data: {
                    type: "outgoing_message",
                    messageId: messageId,
                    address: data.address || "",
                    timestamp: String(Date.now()),
                },
                android: {
                    priority: "high",
                    ttl: 300000, // 5 minutes
                },
            };

            console.log("Sending FCM for outgoing message:", JSON.stringify(message));

            const response = await admin.messaging().send(message);
            console.log(`Successfully sent FCM message for outgoing message: ${response}`);

            return response;
        } catch (error) {
            console.error("Error sending FCM for outgoing message:", error);

            if (error.code === "messaging/invalid-registration-token" ||
                error.code === "messaging/registration-token-not-registered") {
                console.log(`Removing invalid FCM token for user ${userId}`);
                await admin.database().ref(`/fcm_tokens/${userId}`).remove();
            }

            return null;
        }
    });

/**
 * Clean up old notifications (older than 2 minutes)
 * Runs every 5 minutes
 */
exports.cleanupOldNotifications = functions.pubsub
    .schedule("every 5 minutes")
    .onRun(async () => {
        const cutoffTime = Date.now() - (2 * 60 * 1000); // 2 minutes ago

        try {
            const notificationsRef = admin.database().ref("/fcm_notifications");
            const snapshot = await notificationsRef.once("value");

            if (!snapshot.exists()) {
                return null;
            }

            const deletePromises = [];

            snapshot.forEach((userSnapshot) => {
                userSnapshot.forEach((notificationSnapshot) => {
                    const data = notificationSnapshot.val();
                    if (data && data.timestamp && data.timestamp < cutoffTime) {
                        console.log(`Cleaning up old notification: ${notificationSnapshot.key}`);
                        deletePromises.push(notificationSnapshot.ref.remove());
                    }
                });


            });

            if (deletePromises.length > 0) {
                await Promise.all(deletePromises);
                console.log(`Cleaned up ${deletePromises.length} old notifications`);
            }

            return null;
        } catch (error) {
            console.error("Error cleaning up notifications:", error);
            return null;
        }
    });

// ============================================
// SCHEDULED DATA CLEANUP FUNCTIONS
// Runs daily at 3 AM UTC to clean orphan data
// ============================================

/**
 * Daily cleanup job - runs at 3 AM UTC
 * Cleans up all orphan data across all users
 */
exports.scheduledDailyCleanup = functions.pubsub
    .schedule("0 3 * * *") // 3 AM UTC daily
    .timeZone("UTC")
    .onRun(async () => {
        console.log("Starting scheduled daily cleanup...");
        const now = Date.now();
        const stats = {
            outgoingMessages: 0,
            callRequests: 0,
            spamMessages: 0,
            readReceipts: 0,
            inactiveDevices: 0,
            usersProcessed: 0,
            // New cleanup categories
            oldNotifications: 0,
            staleTypingIndicators: 0,
            expiredSessions: 0,
            oldFileTransfers: 0,
            abandonedPairings: 0,
        };

        try {
            // Get all users
            const usersSnapshot = await admin.database().ref("/users").once("value");
            if (!usersSnapshot.exists()) {
                console.log("No users found");
                return null;
            }

            const userIds = [];
            usersSnapshot.forEach((child) => {
                if (child.key) userIds.push(child.key);
            });

            console.log(`Processing ${userIds.length} users...`);

            // Process each user
            for (const userId of userIds) {
                try {
                    const userStats = await cleanupUserData(userId, now);
                    stats.outgoingMessages += userStats.outgoingMessages;
                    stats.callRequests += userStats.callRequests;
                    stats.spamMessages += userStats.spamMessages;
                    stats.readReceipts += userStats.readReceipts;
                    stats.inactiveDevices += userStats.inactiveDevices;
                    // New categories
                    stats.oldNotifications += userStats.oldNotifications;
                    stats.staleTypingIndicators += userStats.staleTypingIndicators;
                    stats.expiredSessions += userStats.expiredSessions;
                    stats.oldFileTransfers += userStats.oldFileTransfers;
                    stats.abandonedPairings += userStats.abandonedPairings;
                    stats.usersProcessed++;
                } catch (error) {
                    console.error(`Error cleaning up user ${userId}:`, error);
                }
            }

            console.log("Daily cleanup complete:", JSON.stringify(stats));

            // Send email report
            await sendCleanupReportEmail(stats, "AUTO");

            return stats;
        } catch (error) {
            console.error("Error in scheduled daily cleanup:", error);
            return null;
        }
    });

/**
 * Helper function to clean up data for a single user
 */
async function cleanupUserData(userId, now) {
    const ONE_HOUR = 60 * 60 * 1000;
    const ONE_DAY = 24 * ONE_HOUR;

    const stats = {
        outgoingMessages: 0,
        callRequests: 0,
        spamMessages: 0,
        readReceipts: 0,
        inactiveDevices: 0,
    };

    // Clean stale outgoing messages (older than 24 hours)
    const outgoingRef = admin.database().ref(`/users/${userId}/outgoing_messages`);
    const outgoingSnap = await outgoingRef.once("value");
    if (outgoingSnap.exists()) {
        const deletePromises = [];
        outgoingSnap.forEach((child) => {
            const data = child.val();
            const createdAt = data.createdAt || data.timestamp || 0;
            if (now - createdAt > ONE_DAY) {
                deletePromises.push(child.ref.remove());
                stats.outgoingMessages++;
            }
        });
        await Promise.all(deletePromises);
    }

    // Clean old notifications (older than 7 days)
    const notificationsRef = admin.database().ref(`/users/${userId}/notifications`);
    const notificationsSnap = await notificationsRef.once("value");
    if (notificationsSnap.exists()) {
        const deletePromises = [];
        notificationsSnap.forEach((child) => {
            const data = child.val();
            const timestamp = data.timestamp || data.createdAt || 0;
            if (now - timestamp > 7 * ONE_DAY) {
                deletePromises.push(child.ref.remove());
                stats.oldNotifications++;
            }
        });
        await Promise.all(deletePromises);
    }

    // Clean stale typing indicators (older than 30 seconds)
    const typingRef = admin.database().ref(`/users/${userId}/typing`);
    const typingSnap = await typingRef.once("value");
    if (typingSnap.exists()) {
        const deletePromises = [];
        typingSnap.forEach((child) => {
            const data = child.val();
            const timestamp = data.timestamp || 0;
            if (now - timestamp > 30 * 1000) {
                deletePromises.push(child.ref.remove());
                stats.staleTypingIndicators++;
            }
        });
        await Promise.all(deletePromises);
    }

    // Clean expired sessions (older than 24 hours)
    const sessionsRef = admin.database().ref(`/users/${userId}/sessions`);
    const sessionsSnap = await sessionsRef.once("value");
    if (sessionsSnap.exists()) {
        const deletePromises = [];
        sessionsSnap.forEach((child) => {
            const data = child.val();
            const lastActivity = data.lastActivity || data.createdAt || 0;
            if (now - lastActivity > ONE_DAY) {
                deletePromises.push(child.ref.remove());
                stats.expiredSessions++;
            }
        });
        await Promise.all(deletePromises);
    }

    // Clean old file transfers (older than 7 days) - includes R2 files
    const transfersRef = admin.database().ref(`/users/${userId}/file_transfers`);
    const transfersSnap = await transfersRef.once("value");
    if (transfersSnap.exists()) {
        const deletePromises = [];
        const r2Client = getR2Client();
        const r2Bucket = getR2Bucket();

        transfersSnap.forEach((child) => {
            const data = child.val();
            const timestamp = data.timestamp || data.startedAt || 0;
            if (now - timestamp > 7 * ONE_DAY) {
                // Delete R2 file if r2Key exists
                if (data.r2Key && r2Client) {
                    deletePromises.push(
                        r2Client.send(new DeleteObjectCommand({
                            Bucket: r2Bucket,
                            Key: data.r2Key,
                        })).catch(err => console.warn(`[cleanup] Failed to delete R2 file ${data.r2Key}:`, err.message))
                    );
                }
                // Delete database record
                deletePromises.push(child.ref.remove());
                stats.oldFileTransfers++;
            }
        });
        await Promise.all(deletePromises);
    }

    // Clean abandoned pairings (started but not completed within 1 hour)
    const userPairingsRef = admin.database().ref(`/users/${userId}/pairings`);
    const userPairingsSnap = await userPairingsRef.once("value");
    if (userPairingsSnap.exists()) {
        const deletePromises = [];
        userPairingsSnap.forEach((child) => {
            const data = child.val();
            const startedAt = data.startedAt || data.createdAt || 0;
            if (!data.completedAt && now - startedAt > ONE_HOUR) {
                deletePromises.push(child.ref.remove());
                stats.abandonedPairings++;
            }
        });
        await Promise.all(deletePromises);
    }

    // Clean old/unused devices (keep only most recent per platform, remove if not seen in 7 days)
    const devicesRef = admin.database().ref(`/users/${userId}/devices`);
    const devicesSnap = await devicesRef.once("value");
    if (devicesSnap.exists()) {
        const devices = devicesSnap.val();
        const devicesByPlatform = new Map();
        const now = Date.now();
        const sevenDaysAgo = now - (7 * 24 * 60 * 60 * 1000);

        // Group devices by platform
        for (const [deviceId, deviceData] of Object.entries(devices)) {
            const platform = deviceData.platform || 'unknown';
            const lastSeen = deviceData.lastSeen || 0;

            if (!devicesByPlatform.has(platform)) {
                devicesByPlatform.set(platform, []);
            }
            devicesByPlatform.get(platform).push({
                deviceId,
                lastSeen,
                registeredAt: deviceData.registeredAt || 0
            });
        }

        // For each platform, keep only the most recent device, clean others if old
        const deletePromises = [];
        for (const [platform, deviceList] of devicesByPlatform) {
            if (deviceList.length > 1) {
                // Sort by lastSeen descending (most recent first)
                deviceList.sort((a, b) => b.lastSeen - a.lastSeen);

                // Keep the first (most recent), check others for cleanup
                for (let i = 1; i < deviceList.length; i++) {
                    const device = deviceList[i];
                    // Clean if not seen in 7 days
                    if (device.lastSeen < sevenDaysAgo) {
                        deletePromises.push(devicesRef.child(device.deviceId).remove());
                        stats.oldDevices++;
                    }
                }
            }
        }
        await Promise.all(deletePromises);
    }

    return stats;
}

/**
 * Manual cleanup trigger - callable function for admin use
 * Can be called from admin panel for immediate cleanup
 */
exports.triggerCleanup = functions.https.onCall(async (data, context) => {
  // Set CORS headers for web admin access
  if (context.rawResponse) {
    context.rawResponse.set('Access-Control-Allow-Origin', '*');
    context.rawResponse.set('Access-Control-Allow-Methods', 'GET, POST');
    context.rawResponse.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  }

  requireAdmin(context);

  const userId = data && data.userId ? String(data.userId) : context.auth.uid;
    console.log(`Manual cleanup triggered for user ${userId}`);

    try {
        const now = Date.now();
        const stats = await cleanupUserData(userId, now);

        // Clean expired pairings - V1 path (pending_pairings)
        let expiredPairings = 0;
        const v1PairingsRef = admin.database().ref("/pending_pairings");
        const v1PairingsSnap = await v1PairingsRef.once("value");
        if (v1PairingsSnap.exists()) {
            const deletePromises = [];
            v1PairingsSnap.forEach((child) => {
                const pData = child.val();
                if (pData.expiresAt && now > pData.expiresAt) {
                    deletePromises.push(child.ref.remove());
                    expiredPairings++;
                }
            });
            await Promise.all(deletePromises);
        }

        // Clean expired pairings - V2 path (pairing_requests)
        const v2PairingsRef = admin.database().ref("/pairing_requests");
        const v2PairingsSnap = await v2PairingsRef.once("value");
        if (v2PairingsSnap.exists()) {
            const deletePromises = [];
            v2PairingsSnap.forEach((child) => {
                const pData = child.val();
                const isExpired = pData.expiresAt && now > pData.expiresAt;
                const isOldCompleted = pData.status !== "pending" &&
                    pData.createdAt && (now - pData.createdAt > 10 * 60 * 1000);
                if (isExpired || isOldCompleted) {
                    deletePromises.push(child.ref.remove());
                    expiredPairings++;
                }
            });
            await Promise.all(deletePromises);
        }

        const result = {
            ...stats,
            expiredPairings,
            timestamp: now,
        };

        console.log(`Manual cleanup complete for ${userId}:`, JSON.stringify(result));
        return result;
    } catch (error) {
        console.error(`Error in manual cleanup for ${userId}:`, error);
        throw new functions.https.HttpsError("internal", error.message || "Cleanup failed");
    }
});

/**
 * Get cleanup stats - callable function to check orphan counts
 */
exports.getCleanupStats = functions.https.onCall(async (data, context) => {
  // Set CORS headers for web admin access
  if (context.rawResponse) {
    context.rawResponse.set('Access-Control-Allow-Origin', '*');
    context.rawResponse.set('Access-Control-Allow-Methods', 'GET, POST');
    context.rawResponse.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  }

  try {
    if (!context.auth) {
      throw new functions.https.HttpsError("unauthenticated", "Authentication required");
    }

    const userId = data && data.userId ? String(data.userId) : context.auth.uid;
    const now = Date.now();
    const ONE_HOUR = 60 * 60 * 1000;
    const ONE_DAY = 24 * ONE_HOUR;
    const THIRTY_DAYS = 30 * ONE_DAY;

    const stats = {
        staleOutgoingMessages: 0,
        expiredPairings: 0,
        oldCallRequests: 0,
        oldSpamMessages: 0,
        oldReadReceipts: 0,
        inactiveDevices: 0,
        // New cleanup categories
        oldNotifications: 0,
        staleTypingIndicators: 0,
        expiredSessions: 0,
        oldFileTransfers: 0,
        abandonedPairings: 0,
        orphanedMedia: 0,
    };

    try {
        // Count stale outgoing messages
        const outgoingSnap = await admin.database()
            .ref(`/users/${userId}/outgoing_messages`).once("value");
        if (outgoingSnap.exists()) {
            outgoingSnap.forEach((child) => {
                const d = child.val();
                if (now - (d.createdAt || d.timestamp || 0) > ONE_DAY) {
                    stats.staleOutgoingMessages++;
                }
            });
        }

        // Count expired pairings
        const pairingsSnap = await admin.database()
            .ref("/pending_pairings").once("value");
        if (pairingsSnap.exists()) {
            pairingsSnap.forEach((child) => {
                const d = child.val();
                if (d.expiresAt && now > d.expiresAt) {
                    stats.expiredPairings++;
                }
            });
        }

        // Count old call requests
        const callsSnap = await admin.database()
            .ref(`/users/${userId}/call_requests`).once("value");
        if (callsSnap.exists()) {
            callsSnap.forEach((child) => {
                const d = child.val();
                if (now - (d.requestedAt || 0) > ONE_HOUR) {
                    stats.oldCallRequests++;
                }
            });
        }

        // Count old spam messages
        const spamSnap = await admin.database()
            .ref(`/users/${userId}/spam_messages`).once("value");
        if (spamSnap.exists()) {
            spamSnap.forEach((child) => {
                const d = child.val();
                if (now - (d.date || d.detectedAt || 0) > THIRTY_DAYS) {
                    stats.oldSpamMessages++;
                }
            });
        }

        // Count old read receipts
        const receiptsSnap = await admin.database()
            .ref(`/users/${userId}/read_receipts`).once("value");
        if (receiptsSnap.exists()) {
            receiptsSnap.forEach((child) => {
                const d = child.val();
                if (now - (d.readAt || 0) > THIRTY_DAYS) {
                    stats.oldReadReceipts++;
                }
            });
        }

        // Count inactive devices
        const devicesSnap = await admin.database()
            .ref(`/users/${userId}/devices`).once("value");
        if (devicesSnap.exists()) {
            devicesSnap.forEach((child) => {
                const d = child.val();
                if (d.platform !== "android") {
                    const lastSeen = d.lastSeen || 0;
                    if (lastSeen > 0 && now - lastSeen > THIRTY_DAYS) {
                        stats.inactiveDevices++;
                    }
                }
            });
        }

        // Additional cleanup stats for admin interface
        // Count old notifications (older than 7 days)
        const notificationsSnap = await admin.database()
            .ref(`/users/${userId}/notifications`).once("value");
        if (notificationsSnap.exists()) {
            notificationsSnap.forEach((child) => {
                const d = child.val();
                const timestamp = d.timestamp || d.createdAt || 0;
                if (now - timestamp > 7 * ONE_DAY) {
                    stats.oldNotifications++;
                }
            });
        }

        // Count stale typing indicators (older than 30 seconds)
        const typingSnap = await admin.database()
            .ref(`/users/${userId}/typing`).once("value");
        if (typingSnap.exists()) {
            typingSnap.forEach((child) => {
                const d = child.val();
                const timestamp = d.timestamp || 0;
                if (now - timestamp > 30 * 1000) {
                    stats.staleTypingIndicators++;
                }
            });
        }

        // Count expired sessions (older than 24 hours)
        const sessionsSnap = await admin.database()
            .ref(`/users/${userId}/sessions`).once("value");
        if (sessionsSnap.exists()) {
            sessionsSnap.forEach((child) => {
                const d = child.val();
                const lastActivity = d.lastActivity || d.createdAt || 0;
                if (now - lastActivity > ONE_DAY) {
                    stats.expiredSessions++;
                }
            });
        }

        // Count old file transfers (older than 7 days)
        const transfersSnap = await admin.database()
            .ref(`/users/${userId}/file_transfers`).once("value");
        if (transfersSnap.exists()) {
            transfersSnap.forEach((child) => {
                const d = child.val();
                const timestamp = d.timestamp || d.startedAt || 0;
                if (now - timestamp > 7 * ONE_DAY) {
                    stats.oldFileTransfers++;
                }
            });
        }

        // Count abandoned pairings (started but not completed within 1 hour)
        const userPairingsSnap = await admin.database()
            .ref(`/users/${userId}/pairings`).once("value");
        if (userPairingsSnap.exists()) {
            userPairingsSnap.forEach((child) => {
                const d = child.val();
                const startedAt = d.startedAt || d.createdAt || 0;
                if (!d.completedAt && now - startedAt > ONE_HOUR) {
                    stats.abandonedPairings++;
                }
            });
        }

        // Count orphaned media (older than 30 days)
        const mediaSnap = await admin.database()
            .ref(`/users/${userId}/media`).once("value");
        if (mediaSnap.exists()) {
            mediaSnap.forEach((child) => {
                const d = child.val();
                const uploadedAt = d.uploadedAt || d.timestamp || 0;
                if (now - uploadedAt > THIRTY_DAYS) {
                    stats.orphanedMedia++;
                }
            });
        }

        return stats;
    } catch (error) {
        console.error(`Error getting cleanup stats for ${userId}:`, error);
        throw new functions.https.HttpsError("internal", error.message || "Failed to get stats");
    }
  } catch (error) {
    console.error('Error in getCleanupStats:', error);
    throw new functions.https.HttpsError("internal", error.message || "Failed to get cleanup stats");
  }
});

/**
 * Get system-wide cleanup overview - shows totals across all users
 */
exports.getSystemCleanupOverview = functions.https.onCall(async (data, context) => {
  // Set CORS headers for web admin access
  if (context.rawResponse) {
    context.rawResponse.set('Access-Control-Allow-Origin', '*');
    context.rawResponse.set('Access-Control-Allow-Methods', 'GET, POST');
    context.rawResponse.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  }

  requireAdmin(context);

  const now = Date.now();
    const overview = {
        totalUsers: 0,
        totalCleanupItems: 0,
        breakdown: {
            staleOutgoingMessages: 0,
            expiredPairings: 0,
            oldCallRequests: 0,
            oldSpamMessages: 0,
            oldReadReceipts: 0,
            inactiveDevices: 0,
            oldNotifications: 0,
            staleTypingIndicators: 0,
            expiredSessions: 0,
            oldFileTransfers: 0,
            abandonedPairings: 0,
            orphanedMedia: 0,
        },
        topUsersByCleanup: [], // Users with most cleanup items
        systemHealth: {
            databaseSize: "unknown", // Could add database size calculation
            lastCleanup: now,
            cleanupFrequency: "daily",
        }
    };

    try {
        // Get all users
        const usersSnapshot = await admin.database().ref("/users").once("value");
        if (!usersSnapshot.exists()) {
            return overview;
        }

        const userCleanupStats = [];
        const userIds = [];

        usersSnapshot.forEach((child) => {
            if (child.key) userIds.push(child.key);
        });

        overview.totalUsers = userIds.length;

        // Process each user
        for (const userId of userIds) {
            try {
                const userStats = await getUserCleanupCounts(userId, now);
                userCleanupStats.push({
                    userId: userId,
                    totalItems: Object.values(userStats).reduce((sum, count) => sum + count, 0),
                    breakdown: userStats
                });

                // Add to totals
                Object.keys(userStats).forEach(key => {
                    overview.breakdown[key] += userStats[key];
                    overview.totalCleanupItems += userStats[key];
                });

            } catch (error) {
                console.error(`Error getting cleanup counts for user ${userId}:`, error);
            }
        }

        // Get top users by cleanup items
        overview.topUsersByCleanup = userCleanupStats
            .sort((a, b) => b.totalItems - a.totalItems)
            .slice(0, 10)
            .map(user => ({
                userId: user.id.substring(0, 8) + "...", // Partial ID for privacy
                totalItems: user.totalItems,
                mainIssues: Object.entries(user.breakdown)
                    .filter(([, value]) => value > 0)
                    .sort(([,a], [,b]) => b - a)
                    .slice(0, 3)
                    .map(([type, count]) => ({ type, count }))
            }));

        return overview;
    } catch (error) {
        console.error("Error in getSystemCleanupOverview processing:", error);
        // Continue with partial results
    }

    return overview;
});

/**
 * Helper function to get cleanup counts for a user (lighter version)
 */
async function getUserCleanupCounts(userId, now) {
    const ONE_HOUR = 60 * 60 * 1000;
    const ONE_DAY = 24 * ONE_HOUR;
    const SEVEN_DAYS = 7 * ONE_DAY;
    const THIRTY_DAYS = 30 * ONE_DAY;

    const counts = {
        staleOutgoingMessages: 0,
        expiredPairings: 0,
        oldCallRequests: 0,
        oldSpamMessages: 0,
        oldReadReceipts: 0,
        inactiveDevices: 0,
        oldNotifications: 0,
        staleTypingIndicators: 0,
        expiredSessions: 0,
        oldFileTransfers: 0,
        abandonedPairings: 0,
        orphanedMedia: 0,
    };

    const paths = [
        { path: `/users/${userId}/outgoing_messages`, key: "staleOutgoingMessages", threshold: ONE_DAY, timeField: "createdAt" },
        { path: `/users/${userId}/call_requests`, key: "oldCallRequests", threshold: ONE_HOUR, timeField: "requestedAt" },
        { path: `/users/${userId}/spam_messages`, key: "oldSpamMessages", threshold: THIRTY_DAYS, timeField: "date" },
        { path: `/users/${userId}/read_receipts`, key: "oldReadReceipts", threshold: THIRTY_DAYS, timeField: "readAt" },
        { path: `/users/${userId}/notifications`, key: "oldNotifications", threshold: SEVEN_DAYS, timeField: "timestamp" },
        { path: `/users/${userId}/typing`, key: "staleTypingIndicators", threshold: 30 * 1000, timeField: "timestamp" },
        { path: `/users/${userId}/sessions`, key: "expiredSessions", threshold: ONE_DAY, timeField: "lastActivity" },
        { path: `/users/${userId}/file_transfers`, key: "oldFileTransfers", threshold: SEVEN_DAYS, timeField: "timestamp" },
        { path: `/users/${userId}/media`, key: "orphanedMedia", threshold: THIRTY_DAYS, timeField: "uploadedAt" },
    ];

    // Process standard user data paths
    for (const { path, key, threshold, timeField } of paths) {
        try {
            const snap = await admin.database().ref(path).once("value");
            if (snap.exists()) {
                snap.forEach((child) => {
                    const data = child.val();
                    const timestamp = data[timeField] || data.timestamp || data.createdAt || 0;
                    if (now - timestamp > threshold) {
                        counts[key]++;
                    }
                });
            }
        } catch (error) {
            console.error(`Error counting ${key} for user ${userId}:`, error);
        }
    }

    // Special handling for abandoned pairings
    try {
        const pairingsSnap = await admin.database().ref(`/users/${userId}/pairings`).once("value");
        if (pairingsSnap.exists()) {
            pairingsSnap.forEach((child) => {
                const data = child.val();
                const startedAt = data.startedAt || data.createdAt || 0;
                if (!data.completedAt && now - startedAt > ONE_HOUR) {
                    counts.abandonedPairings++;
                }
            });
        }
    } catch (error) {
        console.error(`Error counting abandoned pairings for user ${userId}:`, error);
    }

    // Special handling for inactive devices
    try {
        const devicesSnap = await admin.database().ref(`/users/${userId}/devices`).once("value");
        if (devicesSnap.exists()) {
            devicesSnap.forEach((child) => {
                const data = child.val();
                if (data.platform !== "android") {
                    const lastSeen = data.lastSeen || 0;
                    if (lastSeen > 0 && now - lastSeen > THIRTY_DAYS) {
                        counts.inactiveDevices++;
                    }
                }
            });
        }
    } catch (error) {
        console.error(`Error counting inactive devices for user ${userId}:`, error);
    }

    // Count expired pairings (system-wide)
    try {
        const pairingsSnap = await admin.database().ref("/pending_pairings").once("value");
        if (pairingsSnap.exists()) {
            pairingsSnap.forEach((child) => {
                const data = child.val();
                if (data.expiresAt && now > data.expiresAt) {
                    counts.expiredPairings++;
                }
            });
        }
    } catch (error) {
        console.error("Error counting expired pairings:", error);
    }

    return counts;
}

// ============================================
// SYNC GROUP MANAGEMENT (Device-based pairing)
// ============================================

/**
 * Update sync group plan (admin only)
 * Called when upgrading/downgrading a sync group's plan
 */
exports.updateSyncGroupPlan = functions.https.onCall(async (data, context) => {
    try {
        requireAdmin(context);

        const { syncGroupId, plan } = data;

        if (!syncGroupId) {
            throw new functions.https.HttpsError("invalid-argument", "syncGroupId required");
        }

        const validPlans = ["free", "monthly", "yearly", "3-yearly", "3yearly"];
        if (!validPlans.includes(plan)) {
            throw new functions.https.HttpsError("invalid-argument", "Invalid plan");
        }

        const now = Date.now();
        const groupRef = admin.database().ref(`/syncGroups/${syncGroupId}`);
        const snapshot = await groupRef.once("value");

        if (!snapshot.exists()) {
            throw new functions.https.HttpsError("not-found", "Sync group not found");
        }

        const updates = {
            "plan": plan,
            "updatedAt": now
        };

        // Calculate expiry based on plan
        if (plan === "free") {
            updates["planExpiresAt"] = null;
        } else {
            // Monthly/yearly/3-yearly expire in respective periods
            let expiryMs;
            if (plan === "monthly") {
                expiryMs = 30 * 24 * 60 * 60 * 1000;
            } else if (plan === "yearly") {
                expiryMs = 365 * 24 * 60 * 60 * 1000;
            } else {
                // 3-yearly
                expiryMs = 3 * 365 * 24 * 60 * 60 * 1000;
            }
            updates["planExpiresAt"] = now + expiryMs;
        }

        // Track premium status
        if (["monthly", "yearly", "3-yearly", "3yearly"].includes(plan)) {
            updates["wasPremium"] = true;
            if (!snapshot.child("firstPremiumDate").exists()) {
                updates["firstPremiumDate"] = now;
            }
        }

        // Add history entry
        updates[`history/${now}`] = {
            action: "plan_updated",
            newPlan: plan,
            previousPlan: snapshot.child("plan").val() || "free",
            updatedBy: context.auth.uid
        };

        await groupRef.update(updates);

        return {
            success: true,
            syncGroupId,
            plan,
            updatedAt: now
        };
    } catch (error) {
        console.error("Error updating sync group plan:", error);
        throw error;
    }
});

/**
 * Remove device from sync group (admin or device owner)
 */
exports.removeDeviceFromSyncGroup = functions.https.onCall(async (data, context) => {
    try {
        requireAuth(context);

        const { syncGroupId, deviceId } = data;

        if (!syncGroupId || !deviceId) {
            throw new functions.https.HttpsError("invalid-argument", "syncGroupId and deviceId required");
        }

        const now = Date.now();
        const groupRef = admin.database().ref(`/syncGroups/${syncGroupId}`);
        const snapshot = await groupRef.once("value");

        if (!snapshot.exists()) {
            throw new functions.https.HttpsError("not-found", "Sync group not found");
        }

        // Check authorization: must be admin or device owner
        const isAdmin = context.auth.token?.admin === true;
        if (!isAdmin && context.auth.uid !== deviceId) {
            throw new functions.https.HttpsError("permission-denied", "You can only remove your own device");
        }

        const updates = {
            [`devices/${deviceId}`]: null,
            [`history/${now}`]: {
                action: "device_removed",
                deviceId: deviceId,
                removedBy: context.auth.uid
            }
        };

        await groupRef.update(updates);

        return {
            success: true,
            syncGroupId,
            deviceId,
            removedAt: now
        };
    } catch (error) {
        console.error("Error removing device from sync group:", error);
        throw error;
    }
});

/**
 * Get sync group info (any authenticated user can view)
 */
exports.getSyncGroupInfo = functions.https.onCall(async (data, context) => {
    try {
        requireAuth(context);

        const { syncGroupId } = data;

        if (!syncGroupId) {
            throw new functions.https.HttpsError("invalid-argument", "syncGroupId required");
        }

        const groupRef = admin.database().ref(`/syncGroups/${syncGroupId}`);
        const snapshot = await groupRef.once("value");

        if (!snapshot.exists()) {
            throw new functions.https.HttpsError("not-found", "Sync group not found");
        }

        const groupData = snapshot.val();
        const plan = groupData.plan || "free";
        const deviceLimit = plan === "free" ? 3 : 999;
        const devices = groupData.devices || {};

        return {
            success: true,
            data: {
                syncGroupId,
                plan,
                deviceLimit,
                deviceCount: Object.keys(devices).length,
                createdAt: groupData.createdAt,
                devices: Object.entries(devices).map(([deviceId, info]) => ({
                    deviceId,
                    deviceType: info.deviceType,
                    joinedAt: info.joinedAt,
                    lastSyncedAt: info.lastSyncedAt,
                    status: info.status || "active",
                    deviceName: info.deviceName
                }))
            }
        };
    } catch (error) {
        console.error("Error getting sync group info:", error);
        throw error;
    }
});

/**
 * List all sync groups (admin only)
 */
exports.listSyncGroups = functions.https.onCall(async (data, context) => {
    try {
        requireAdmin(context);

        const groupsRef = admin.database().ref("/syncGroups");
        const snapshot = await groupsRef.once("value");

        if (!snapshot.exists()) {
            return {
                success: true,
                groups: []
            };
        }

        const groups = [];
        snapshot.forEach((child) => {
            const groupData = child.val();
            const plan = groupData.plan || "free";
            const devices = groupData.devices || {};

            groups.push({
                syncGroupId: child.key,
                plan,
                deviceCount: Object.keys(devices).length,
                deviceLimit: plan === "free" ? 3 : 999,
                createdAt: groupData.createdAt,
                masterDevice: groupData.masterDevice
            });
        });

        return {
            success: true,
            groups: groups.sort((a, b) => b.createdAt - a.createdAt)
        };
    } catch (error) {
        console.error("Error listing sync groups:", error);
        throw error;
    }
});

/**
 * Get detailed sync group info (admin only)
 */
exports.getSyncGroupDetails = functions.https.onCall(async (data, context) => {
    try {
        requireAdmin(context);

        const { syncGroupId } = data;

        if (!syncGroupId) {
            throw new functions.https.HttpsError("invalid-argument", "syncGroupId required");
        }

        const groupRef = admin.database().ref(`/syncGroups/${syncGroupId}`);
        const snapshot = await groupRef.once("value");

        if (!snapshot.exists()) {
            throw new functions.https.HttpsError("not-found", "Sync group not found");
        }

        const groupData = snapshot.val();
        const devices = groupData.devices || {};
        const history = groupData.history || {};

        return {
            success: true,
            data: {
                syncGroupId,
                plan: groupData.plan || "free",
                deviceLimit: (groupData.plan || "free") === "free" ? 3 : 999,
                deviceCount: Object.keys(devices).length,
                createdAt: groupData.createdAt,
                masterDevice: groupData.masterDevice,
                wasPremium: groupData.wasPremium || false,
                firstPremiumDate: groupData.firstPremiumDate,
                devices: Object.entries(devices).map(([deviceId, info]) => ({
                    deviceId,
                    deviceType: info.deviceType,
                    joinedAt: info.joinedAt,
                    lastSyncedAt: info.lastSyncedAt,
                    status: info.status || "active",
                    deviceName: info.deviceName
                })),
                history: Object.entries(history)
                    .map(([timestamp, entry]) => ({
                        ...entry,
                        timestamp: parseInt(timestamp)
                    }))
                    .sort((a, b) => b.timestamp - a.timestamp)
            }
        };
    } catch (error) {
        console.error("Error getting sync group details:", error);
        throw error;
    }
});

/**
 * Delete a sync group (admin only - used for cleanup)
 */
exports.deleteSyncGroup = functions.https.onCall(async (data, context) => {
    try {
        requireAdmin(context);

        const { syncGroupId } = data;

        if (!syncGroupId) {
            throw new functions.https.HttpsError("invalid-argument", "syncGroupId required");
        }

        const groupRef = admin.database().ref(`/syncGroups/${syncGroupId}`);
        await groupRef.remove();

        return {
            success: true,
            syncGroupId,
            deletedAt: Date.now()
        };
    } catch (error) {
        console.error("Error deleting sync group:", error);
        throw error;
    }
});

/**
 * List all users with their device counts and subscription status (admin only)
 */
exports.listUsers = functions.https.onCall(async (data, context) => {
    try {
        requireAdmin(context);

        const usersRef = admin.database().ref('users');
        const usersSnapshot = await usersRef.once('value');

        if (!usersSnapshot.exists()) {
            return {
                success: true,
                users: [],
                totalUsers: 0
            };
        }

        const users = usersSnapshot.val();
        const syncGroupsRef = admin.database().ref('syncGroups');
        const syncGroupsSnapshot = await syncGroupsRef.once('value');
        const syncGroups = syncGroupsSnapshot.exists() ? syncGroupsSnapshot.val() : {};

        // Build user list with device counts
        const userList = [];

        for (const [userId, userData] of Object.entries(users)) {
            // Count devices for this user
            const userDevices = userData.devices || {};
            const deviceCount = Object.keys(userDevices).length;

            // Find which sync group(s) this user belongs to
            let syncGroupId = null;
            let subscription = 'free';
            let deviceLimit = 3;

            for (const [groupId, groupData] of Object.entries(syncGroups)) {
                const groupDevices = groupData.devices || {};
                // Check if any of this user's devices are in this sync group
                for (const deviceId of Object.keys(userDevices)) {
                    if (groupDevices[deviceId]) {
                        syncGroupId = groupId;
                        subscription = groupData.plan || 'free';
                        deviceLimit = groupData.deviceLimit || 3;
                        break;
                    }
                }
                if (syncGroupId) break;
            }

            userList.push({
                userId,
                deviceCount,
                syncGroupId,
                subscription,
                deviceLimit,
                createdAt: userData.createdAt || null,
                lastActive: userData.lastActive || null
            });
        }

        // Sort by most recent first
        userList.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));

        return {
            success: true,
            users: userList,
            totalUsers: userList.length,
            totalDevices: userList.reduce((sum, u) => sum + u.deviceCount, 0)
        };
    } catch (error) {
        console.error("Error listing users:", error);
        throw error;
    }
});

// ============================================
// PAIRING V2 - Redesigned Pairing System
// ============================================
// New pairing flow with:
// - Persistent device IDs
// - Device limit enforcement (3 for free, unlimited for pro)
// - 15-minute token expiration
// - Phone-verified user identity

const PAIRING_V2_TTL_MS = 15 * 60 * 1000; // 15 minutes (increased from 5)

/**
 * Helper: Get user's subscription plan
 */
async function getUserPlan(userId) {
    try {
        const subscriptionRef = admin.database().ref(`/users/${userId}/subscription`);
        const snapshot = await subscriptionRef.once("value");

        if (!snapshot.exists()) {
            return "free";
        }

        const subscription = snapshot.val();
        const plan = subscription.plan || "free";

        // Check if plan has expired
        if (subscription.planExpiresAt && Date.now() > subscription.planExpiresAt) {
            return "free";
        }

        return plan;
    } catch (error) {
        console.error("Error getting user plan:", error);
        return "free";
    }
}

/**
 * Helper: Get device count for user
 */
async function getDeviceCount(userId) {
    try {
        const devicesRef = admin.database().ref(`/users/${userId}/devices`);
        const snapshot = await devicesRef.once("value");

        if (!snapshot.exists()) {
            return 0;
        }

        return Object.keys(snapshot.val()).length;
    } catch (error) {
        console.error("Error getting device count:", error);
        return 0;
    }
}

/**
 * Helper: Get device limit for plan
 */
function getDeviceLimit(plan) {
    switch (plan) {
        case "monthly":
        case "yearly":
        case "3-yearly":
        case "3yearly":
            return 999; // Effectively unlimited
        case "free":
        default:
            return 3;
    }
}

/**
 * Initiate Pairing V2 - Called by Mac/Web to start pairing
 *
 * Uses persistent device IDs that survive reinstalls.
 * Returns a QR payload for Android to scan.
 */
exports.initiatePairingV2 = functions.https.onCall(async (data, context) => {
    try {
        // Device can initiate pairing without auth (will get auth after approval)
        const deviceId = data && data.deviceId ? String(data.deviceId) : "";
        const deviceName = data && data.deviceName ? String(data.deviceName) : "Desktop";
        const deviceType = data && data.deviceType ? String(data.deviceType) : "macos";
        const appVersion = data && data.appVersion ? String(data.appVersion) : "2.0.0";

        // Validate device ID format
        if (!deviceId || !deviceId.match(/^(mac|web)_[a-f0-9]{16}$/)) {
            throw new functions.https.HttpsError(
                "invalid-argument",
                "Invalid device ID format. Expected: mac_xxxx or web_xxxx"
            );
        }

        console.log(`[PairingV2] Initiating pairing for device: ${deviceId} (${deviceName})`);

        // Generate secure token
        const token = crypto.randomUUID().replace(/-/g, "");
        const now = Date.now();
        const expiresAt = now + PAIRING_V2_TTL_MS;

        // Store pairing request
        const pairingData = {
            deviceId,
            deviceName,
            deviceType,
            appVersion,
            status: "pending",
            createdAt: now,
            expiresAt,
            version: 2 // Protocol version
        };

        await admin.database().ref(`/pairing_requests/${token}`).set(pairingData);

        // Generate QR payload
        const qrPayload = JSON.stringify({
            v: 2, // Protocol version
            token,
            device: {
                id: deviceId,
                name: deviceName,
                type: deviceType
            },
            expires: expiresAt
        });

        console.log(`[PairingV2] Session created: token=${token.slice(0, 8)}...`);

        return {
            success: true,
            token,
            expiresAt,
            qrPayload
        };
    } catch (error) {
        console.error("[PairingV2] initiatePairingV2 error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", error.message || "Failed to initiate pairing");
    }
});

/**
 * Approve Pairing V2 - Called by Android after scanning QR and user confirms
 *
 * Validates device limits and creates custom auth token for Mac/Web.
 */
exports.approvePairingV2 = functions.https.onCall(async (data, context) => {
    try {
        const androidUid = requireAuth(context);
        const token = data && data.token ? String(data.token) : "";
        const approved = data && data.approved !== false; // Default to true

        if (!token) {
            throw new functions.https.HttpsError("invalid-argument", "Missing token");
        }

        console.log(`[PairingV2] Processing approval for token=${token.slice(0, 8)}..., approved=${approved}`);

        // Get pairing request
        const requestRef = admin.database().ref(`/pairing_requests/${token}`);
        const snapshot = await requestRef.once("value");

        if (!snapshot.exists()) {
            throw new functions.https.HttpsError("not-found", "Pairing request not found or expired");
        }

        const request = snapshot.val();

        // Check status
        if (request.status !== "pending") {
            throw new functions.https.HttpsError(
                "failed-precondition",
                `Pairing already ${request.status}`
            );
        }

        // Check expiration
        if (Date.now() > request.expiresAt) {
            await requestRef.update({ status: "expired" });
            throw new functions.https.HttpsError("deadline-exceeded", "Pairing request expired");
        }

        // Handle rejection
        if (!approved) {
            await requestRef.update({
                status: "rejected",
                rejectedAt: Date.now(),
                rejectedBy: androidUid
            });

            console.log(`[PairingV2] Pairing rejected by ${androidUid}`);
            return { success: true, status: "rejected" };
        }

        // Check device limits for free users
        const userPlan = await getUserPlan(androidUid);
        const deviceCount = await getDeviceCount(androidUid);
        const deviceLimit = getDeviceLimit(userPlan);

        // Check if this device already exists (re-pairing)
        const existingDeviceRef = admin.database().ref(`/users/${androidUid}/devices/${request.deviceId}`);
        const existingDevice = await existingDeviceRef.once("value");
        const isRePairing = existingDevice.exists();

        // Only check limit if this is a new device
        if (!isRePairing && deviceCount >= deviceLimit) {
            console.log(`[PairingV2] Device limit reached: ${deviceCount}/${deviceLimit}`);

            return {
                success: false,
                error: "device_limit",
                message: `Free plan limited to ${deviceLimit} devices. Upgrade to Pro for unlimited.`,
                currentDevices: deviceCount,
                limit: deviceLimit,
                upgradeRequired: true
            };
        }

        // Create custom token for Mac/Web device
        const deviceUid = `device_${request.deviceId}`;
        const customToken = await admin.auth().createCustomToken(deviceUid, {
            pairedUid: androidUid,
            deviceId: request.deviceId,
            deviceType: request.deviceType,
            version: 2
        });

        // Register/update device
        const now = Date.now();
        await admin.database()
            .ref(`/users/${androidUid}/devices/${request.deviceId}`)
            .set({
                name: request.deviceName,
                type: request.deviceType,
                platform: request.deviceType,
                isPaired: true,
                pairedAt: now,
                lastSeen: now,
                appVersion: request.appVersion,
                pairingVersion: 2
            });

        // Update pairing request with approval
        await requestRef.update({
            status: "approved",
            approvedAt: now,
            approvedBy: androidUid,
            pairedUid: androidUid,
            customToken // Mac/Web will read this to complete auth
        });

        // Schedule cleanup of the request after 5 minutes
        // (Let Mac/Web have time to claim the token)
        setTimeout(async () => {
            try {
                await requestRef.remove();
            } catch (e) {
                // Ignore cleanup errors
            }
        }, 5 * 60 * 1000);

        console.log(`[PairingV2] Pairing approved: device=${request.deviceId}, user=${androidUid}`);

        return {
            success: true,
            status: "approved",
            userId: androidUid,
            deviceId: request.deviceId,
            isRePairing
        };
    } catch (error) {
        console.error("[PairingV2] approvePairingV2 error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", error.message || "Failed to approve pairing");
    }
});

/**
 * Check Pairing V2 Status - Called by Mac/Web to poll for approval
 *
 * Returns status and custom token when approved.
 */
exports.checkPairingV2Status = functions.https.onCall(async (data, context) => {
    try {
        const token = data && data.token ? String(data.token) : "";

        if (!token) {
            throw new functions.https.HttpsError("invalid-argument", "Missing token");
        }

        const requestRef = admin.database().ref(`/pairing_requests/${token}`);
        const snapshot = await requestRef.once("value");

        if (!snapshot.exists()) {
            return {
                success: true,
                status: "expired",
                message: "Pairing request not found or expired"
            };
        }

        const request = snapshot.val();

        // Check expiration
        if (Date.now() > request.expiresAt && request.status === "pending") {
            return {
                success: true,
                status: "expired",
                message: "Pairing request expired"
            };
        }

        const result = {
            success: true,
            status: request.status,
            deviceId: request.deviceId
        };

        // Include custom token if approved
        if (request.status === "approved") {
            result.customToken = request.customToken;
            result.pairedUid = request.pairedUid;
        }

        return result;
    } catch (error) {
        console.error("[PairingV2] checkPairingV2Status error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", error.message || "Failed to check status");
    }
});

/**
 * Get Device Info V2 - Returns user's devices with limit info
 */
exports.getDeviceInfoV2 = functions.https.onCall(async (data, context) => {
    try {
        const uid = requireAuth(context);

        const userPlan = await getUserPlan(uid);
        const deviceLimit = getDeviceLimit(userPlan);

        const devicesRef = admin.database().ref(`/users/${uid}/devices`);
        const snapshot = await devicesRef.once("value");

        const devices = [];
        if (snapshot.exists()) {
            const devicesData = snapshot.val();
            for (const [deviceId, deviceInfo] of Object.entries(devicesData)) {
                devices.push({
                    deviceId,
                    name: deviceInfo.name || "Unknown Device",
                    type: deviceInfo.type || deviceInfo.platform || "unknown",
                    platform: deviceInfo.platform || deviceInfo.type || "unknown",
                    isPaired: deviceInfo.isPaired !== false,
                    pairedAt: deviceInfo.pairedAt,
                    lastSeen: deviceInfo.lastSeen
                });
            }
        }

        return {
            success: true,
            plan: userPlan,
            deviceLimit,
            deviceCount: devices.length,
            canAddDevice: devices.length < deviceLimit,
            devices
        };
    } catch (error) {
        console.error("[PairingV2] getDeviceInfoV2 error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", error.message || "Failed to get device info");
    }
});

/**
 * Sync a message from Android to Firebase
 * This allows Android to stay in Firebase offline mode to prevent OOM
 *
 * @param {Object} data - Message data
 * @param {string} data.messageId - Unique message ID
 * @param {Object} data.message - Message content
 */
exports.syncMessage = functions.https.onCall(async (data, context) => {
    try {
        const uid = requireAuth(context);

        const { messageId, message } = data;

        if (!messageId || !message) {
            throw new functions.https.HttpsError("invalid-argument", "Missing messageId or message");
        }

        // Write message to Firebase
        const messageRef = admin.database().ref(`/users/${uid}/messages/${messageId}`);
        await messageRef.set({
            ...message,
            syncedAt: Date.now(),
            syncedVia: "cloud_function"
        });

        return { success: true, messageId };
    } catch (error) {
        console.error("[SyncMessage] Error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", error.message || "Failed to sync message");
    }
});

/**
 * Batch sync multiple messages from Android
 * More efficient than syncing one at a time
 *
 * @param {Object} data - Batch data
 * @param {Array} data.messages - Array of {messageId, message} objects
 */
exports.syncMessageBatch = functions.https.onCall(async (data, context) => {
    try {
        const uid = requireAuth(context);

        const { messages } = data;

        if (!messages || !Array.isArray(messages) || messages.length === 0) {
            throw new functions.https.HttpsError("invalid-argument", "Missing or empty messages array");
        }

        // Limit batch size to prevent timeout
        if (messages.length > 100) {
            throw new functions.https.HttpsError("invalid-argument", "Batch size exceeds limit of 100");
        }

        // Build multi-path update
        const updates = {};
        const now = Date.now();

        for (const { messageId, message } of messages) {
            if (messageId && message) {
                updates[`/users/${uid}/messages/${messageId}`] = {
                    ...message,
                    syncedAt: now,
                    syncedVia: "cloud_function_batch"
                };
            }
        }

        // Perform atomic update
        await admin.database().ref().update(updates);

        return { success: true, count: Object.keys(updates).length };
    } catch (error) {
        console.error("[SyncMessageBatch] Error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", error.message || "Failed to sync messages");
    }
});

/**
 * Sync a spam message from Android to Firebase
 * Uses admin privileges to bypass security rules
 *
 * @param {Object} data - Spam message data
 * @param {string} data.messageId - Unique message ID
 * @param {Object} data.spamMessage - Spam message content
 */
exports.syncSpamMessage = functions.https.onCall(async (data, context) => {
    try {
        const uid = requireAuth(context);

        const { messageId, spamMessage } = data;

        if (!messageId || !spamMessage) {
            throw new functions.https.HttpsError("invalid-argument", "Missing messageId or spamMessage");
        }

        // Validate required fields
        if (!spamMessage.address || !spamMessage.body || spamMessage.date === undefined) {
            throw new functions.https.HttpsError("invalid-argument", "Spam message missing required fields (address, body, date)");
        }

        // Write spam message to Firebase
        const spamRef = admin.database().ref(`/users/${uid}/spam_messages/${messageId}`);
        await spamRef.set({
            messageId: messageId,
            address: spamMessage.address,
            body: spamMessage.body,
            date: spamMessage.date,
            contactName: spamMessage.contactName || spamMessage.address,
            spamConfidence: spamMessage.spamConfidence || 0.5,
            spamReasons: spamMessage.spamReasons || "user_marked",
            detectedAt: spamMessage.detectedAt || Date.now(),
            isUserMarked: spamMessage.isUserMarked !== undefined ? spamMessage.isUserMarked : true,
            isRead: spamMessage.isRead !== undefined ? spamMessage.isRead : false,
            syncedAt: Date.now(),
            syncedVia: "cloud_function"
        });

        console.log(`[SyncSpamMessage] Synced spam message ${messageId} for user ${uid}`);
        return { success: true, messageId };
    } catch (error) {
        console.error("[SyncSpamMessage] Error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", error.message || "Failed to sync spam message");
    }
});

/**
 * Delete a spam message from Firebase
 *
 * @param {Object} data - Request data
 * @param {string} data.messageId - Message ID to delete
 */
exports.deleteSpamMessage = functions.https.onCall(async (data, context) => {
    try {
        const uid = requireAuth(context);

        const { messageId } = data;

        if (!messageId) {
            throw new functions.https.HttpsError("invalid-argument", "Missing messageId");
        }

        // Delete spam message from Firebase
        const spamRef = admin.database().ref(`/users/${uid}/spam_messages/${messageId}`);
        await spamRef.remove();

        console.log(`[DeleteSpamMessage] Deleted spam message ${messageId} for user ${uid}`);
        return { success: true, messageId };
    } catch (error) {
        console.error("[DeleteSpamMessage] Error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", error.message || "Failed to delete spam message");
    }
});

/**
 * Clear all spam messages for a user
 */
exports.clearAllSpamMessages = functions.https.onCall(async (data, context) => {
    try {
        const uid = requireAuth(context);

        // Delete all spam messages
        const spamRef = admin.database().ref(`/users/${uid}/spam_messages`);
        await spamRef.remove();

        console.log(`[ClearAllSpamMessages] Cleared all spam messages for user ${uid}`);
        return { success: true };
    } catch (error) {
        console.error("[ClearAllSpamMessages] Error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", error.message || "Failed to clear spam messages");
    }
});

/**
 * Cleanup expired V2 pairing requests
 * Runs every 5 minutes
 */
exports.cleanupExpiredPairingRequestsV2 = functions.pubsub
    .schedule("every 5 minutes")
    .onRun(async () => {
        const now = Date.now();

        try {
            const requestsRef = admin.database().ref("/pairing_requests");
            const snapshot = await requestsRef.once("value");

            if (!snapshot.exists()) {
                return null;
            }

            const deletePromises = [];

            snapshot.forEach((requestSnapshot) => {
                const data = requestSnapshot.val();

                // Remove if expired or old completed requests
                const isExpired = data.expiresAt && (now > data.expiresAt + PAIRING_V2_TTL_MS);
                const isOldCompleted = data.status !== "pending" &&
                    data.createdAt && (now - data.createdAt > 10 * 60 * 1000);

                if (isExpired || isOldCompleted) {
                    console.log(`[PairingV2] Cleaning up request: ${requestSnapshot.key}`);
                    deletePromises.push(requestSnapshot.ref.remove());
                }
            });

            if (deletePromises.length > 0) {
                await Promise.all(deletePromises);
                console.log(`[PairingV2] Cleaned up ${deletePromises.length} pairing requests`);
            }

            return null;
        } catch (error) {
            console.error("[PairingV2] Error cleaning up requests:", error);
            return null;
        }
    });

// ============================================
// ACCOUNT DELETION (Soft Delete with 30-day grace period)
// ============================================

/**
 * Request account deletion (soft delete)
 * Marks account for deletion in 30 days
 * Stores identifying info for admin recovery assistance
 */
exports.requestAccountDeletion = functions.https.onCall(async (data, context) => {
    try {
        const userId = requireAuth(context);
        const reason = data?.reason || "Not specified";

        const now = Date.now();
        const deletionDate = now + (30 * 24 * 60 * 60 * 1000); // 30 days from now

        // Gather identifying info for recovery assistance
        const identifyingInfo = await getUserIdentifyingInfo(userId);

        // Mark account for deletion
        await admin.database().ref(`users/${userId}/deletion_scheduled`).set({
            requestedAt: now,
            scheduledDeletionAt: deletionDate,
            reason: reason,
            status: "pending"
        });

        // Store in scheduled_deletions with identifying info for admin search
        await admin.database().ref(`scheduled_deletions/${userId}`).set({
            requestedAt: now,
            scheduledDeletionAt: deletionDate,
            reason: reason,
            // Identifying info for recovery
            deviceNames: identifyingInfo?.deviceNames || [],
            phoneNumbers: identifyingInfo?.phoneNumbers || [],
            deviceCount: identifyingInfo?.deviceCount || 0,
            messageCount: identifyingInfo?.messageCount || 0,
            plan: identifyingInfo?.plan || "free"
        });

        // Send notification email to admin with identifying info
        await sendAccountDeletionRequestEmail(userId, reason, deletionDate, identifyingInfo);

        console.log(`[AccountDeletion] User ${userId} scheduled for deletion on ${new Date(deletionDate).toISOString()}`);

        return {
            success: true,
            scheduledDeletionAt: deletionDate,
            message: "Your account has been scheduled for deletion in 30 days."
        };
    } catch (error) {
        console.error("[AccountDeletion] Error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", "Failed to schedule account deletion");
    }
});

/**
 * Cancel account deletion request
 */
exports.cancelAccountDeletion = functions.https.onCall(async (data, context) => {
    try {
        const userId = requireAuth(context);

        // Check if deletion is scheduled
        const deletionSnap = await admin.database().ref(`users/${userId}/deletion_scheduled`).once('value');
        if (!deletionSnap.exists()) {
            throw new functions.https.HttpsError("not-found", "No deletion request found");
        }

        // Remove deletion markers
        await Promise.all([
            admin.database().ref(`users/${userId}/deletion_scheduled`).remove(),
            admin.database().ref(`scheduled_deletions/${userId}`).remove()
        ]);

        console.log(`[AccountDeletion] User ${userId} cancelled deletion request`);

        return {
            success: true,
            message: "Account deletion has been cancelled. Your account is safe."
        };
    } catch (error) {
        console.error("[AccountDeletion] Cancel error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", "Failed to cancel account deletion");
    }
});

/**
 * Check account deletion status
 */
exports.getAccountDeletionStatus = functions.https.onCall(async (data, context) => {
    try {
        const userId = requireAuth(context);

        const deletionSnap = await admin.database().ref(`users/${userId}/deletion_scheduled`).once('value');

        if (!deletionSnap.exists()) {
            return {
                success: true,
                isScheduledForDeletion: false
            };
        }

        const deletion = deletionSnap.val();
        const now = Date.now();
        const daysRemaining = Math.ceil((deletion.scheduledDeletionAt - now) / (24 * 60 * 60 * 1000));

        return {
            success: true,
            isScheduledForDeletion: true,
            requestedAt: deletion.requestedAt,
            scheduledDeletionAt: deletion.scheduledDeletionAt,
            daysRemaining: Math.max(0, daysRemaining),
            reason: deletion.reason
        };
    } catch (error) {
        console.error("[AccountDeletion] Status check error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", "Failed to check deletion status");
    }
});

/**
 * Scheduled job to process account deletions (runs daily at 4 AM UTC)
 */
exports.processScheduledDeletions = functions.pubsub
    .schedule("0 4 * * *") // 4 AM UTC daily
    .timeZone("UTC")
    .onRun(async () => {
        console.log("[AccountDeletion] Starting scheduled deletion processing...");

        const now = Date.now();
        let deletedCount = 0;
        let errorCount = 0;

        try {
            // Get all scheduled deletions
            const deletionsSnap = await admin.database().ref("scheduled_deletions").once("value");

            if (!deletionsSnap.exists()) {
                console.log("[AccountDeletion] No scheduled deletions found");
                return null;
            }

            const deletions = [];
            deletionsSnap.forEach((child) => {
                const data = child.val();
                if (data.scheduledDeletionAt && data.scheduledDeletionAt <= now) {
                    deletions.push({ userId: child.key, ...data });
                }
            });

            console.log(`[AccountDeletion] Found ${deletions.length} accounts to delete`);

            // Process each deletion
            for (const deletion of deletions) {
                try {
                    await performAccountDeletion(deletion.userId, deletion.reason);
                    deletedCount++;
                } catch (error) {
                    console.error(`[AccountDeletion] Failed to delete ${deletion.userId}:`, error);
                    errorCount++;
                }
            }

            // Send summary email to admin
            if (deletedCount > 0 || errorCount > 0) {
                await sendDeletionSummaryEmail(deletedCount, errorCount);
            }

            console.log(`[AccountDeletion] Completed: ${deletedCount} deleted, ${errorCount} errors`);
            return { deletedCount, errorCount };
        } catch (error) {
            console.error("[AccountDeletion] Scheduled job error:", error);
            return null;
        }
    });

/**
 * Perform actual account deletion
 * Preserves identifying info in deleted_accounts for admin recovery
 */
async function performAccountDeletion(userId, reason) {
    console.log(`[AccountDeletion] Deleting account: ${userId}`);

    // Get scheduled deletion info (contains identifying info)
    let scheduledInfo = {};
    try {
        const scheduledSnap = await admin.database().ref(`scheduled_deletions/${userId}`).once('value');
        scheduledInfo = scheduledSnap.val() || {};
    } catch (e) {
        console.log("[AccountDeletion] Could not fetch scheduled deletion info");
    }

    // Get recovery code hash to delete
    let recoveryCodeHash = null;
    try {
        const recoverySnap = await admin.database().ref(`users/${userId}/recovery_info/codeHash`).once('value');
        recoveryCodeHash = recoverySnap.val();
    } catch (e) {
        console.log("[AccountDeletion] No recovery code hash found");
    }

    // Store deletion record BEFORE deleting user data (preserve identifying info)
    await admin.database().ref(`deleted_accounts/${userId}`).set({
        deletedAt: Date.now(),
        requestedAt: scheduledInfo.requestedAt || null,
        reason: reason || scheduledInfo.reason || "User requested",
        // Preserve identifying info for admin recovery
        deviceNames: scheduledInfo.deviceNames || [],
        phoneNumbers: scheduledInfo.phoneNumbers || [],
        deviceCount: scheduledInfo.deviceCount || 0,
        messageCount: scheduledInfo.messageCount || 0,
        plan: scheduledInfo.plan || "free",
        // Mark as permanently deleted (not restorable)
        status: "deleted",
        canRestore: false
    });

    // Delete all user data
    const deletions = [
        admin.database().ref(`users/${userId}`).remove(),
        admin.database().ref(`scheduled_deletions/${userId}`).remove(),
        admin.database().ref(`subscription_records/${userId}`).remove(),
        admin.database().ref(`fcm_tokens/${userId}`).remove(),
        admin.database().ref(`data_export_requests`).orderByChild('userId').equalTo(userId).once('value')
            .then(snap => {
                const updates = {};
                snap.forEach(child => { updates[child.key] = null; });
                if (Object.keys(updates).length > 0) {
                    return admin.database().ref('data_export_requests').update(updates);
                }
            })
    ];

    // Delete recovery code mapping if exists
    if (recoveryCodeHash) {
        deletions.push(admin.database().ref(`recovery_codes/${recoveryCodeHash}`).remove());
    }

    await Promise.all(deletions);

    console.log(`[AccountDeletion] Successfully deleted account: ${userId}`);
}

/**
 * Send email when user requests deletion (includes identifying info for recovery)
 */
const sendAccountDeletionRequestEmail = async (userId, reason, deletionDate, identifyingInfo = null) => {
    const resend = getResend();
    if (!resend) return false;

    // Get user info and tier
    const [userInfo, tierTag] = await Promise.all([
        getUserInfoForEmail(userId),
        getUserTierForSubject(userId)
    ]);
    const timestamp = new Date().toLocaleString();
    const deletionDateStr = new Date(deletionDate).toLocaleString();

    // Format identifying info for admin reference
    let identifyingSection = "";
    if (identifyingInfo) {
        const deviceList = (identifyingInfo.deviceNames || [])
            .map(d => `  - ${d.name} (${d.platform})`)
            .join("\n") || "  (no devices)";

        const phoneList = (identifyingInfo.phoneNumbers || [])
            .slice(0, 10)
            .map(p => `  - ${p}`)
            .join("\n") || "  (no phone numbers)";

        identifyingSection = `
IDENTIFYING INFO (for account recovery):
========================================
Device Names:
${deviceList}

Phone Numbers (contacts):
${phoneList}

Account Stats:
• Messages synced: ${identifyingInfo.messageCount || 0}
• Plan: ${identifyingInfo.plan || "free"}
`;
    }

    const subject = `${tierTag} [Account Deletion] User Requested Account Deletion`;
    const body = `
SYNCFLOW ACCOUNT DELETION REQUEST
==================================

A user has requested account deletion via the app.

Request Time: ${timestamp}
Scheduled Deletion: ${deletionDateStr}
Reason: ${reason}

${userInfo}
${identifyingSection}
The account will be automatically deleted in 30 days unless the user cancels.

User can cancel anytime before the deletion date.

If user contacts support to recover, search by:
- User ID: ${userId}
- Device names listed above
- Phone numbers (contacts) listed above

---
SyncFlow Admin System
Account Deletion Notice
`;

    try {
        await resend.emails.send({
            from: "SyncFlow Admin <noreply@resend.dev>",
            to: [ADMIN_EMAIL],
            subject: subject,
            text: body,
        });
        return true;
    } catch (error) {
        console.error("Failed to send deletion request email:", error);
        return false;
    }
};

/**
 * Send summary email after processing deletions
 */
const sendDeletionSummaryEmail = async (deletedCount, errorCount) => {
    const resend = getResend();
    if (!resend) return false;

    const timestamp = new Date().toLocaleString();

    const subject = `[Account Deletion] Daily Summary: ${deletedCount} accounts deleted`;
    const body = `
SYNCFLOW ACCOUNT DELETION SUMMARY
==================================

Daily Deletion Job Completed: ${timestamp}

Results:
• Accounts Deleted: ${deletedCount}
• Errors: ${errorCount}

${errorCount > 0 ? "⚠️ Some deletions failed. Check logs for details." : "✓ All scheduled deletions processed successfully."}

---
SyncFlow Admin System
Automated Deletion Summary
`;

    try {
        await resend.emails.send({
            from: "SyncFlow Admin <noreply@resend.dev>",
            to: [ADMIN_EMAIL],
            subject: subject,
            text: body,
        });
        return true;
    } catch (error) {
        console.error("Failed to send deletion summary email:", error);
        return false;
    }
};

// ============================================
// AI SUPPORT CHAT
// Handles user-specific queries about their own data
// ============================================

/**
 * AI Support Chat - helps users with their own account queries
 * Users can ask about: recovery code, user ID, data usage, subscription, etc.
 */
exports.supportChat = functions.https.onCall(async (data, context) => {
    try {
        const message = data?.message?.toLowerCase() || "";
        const conversationHistory = data?.conversationHistory || [];

        // Use syncGroupUserId if provided (Mac/Web pass the actual sync group user ID)
        // Fall back to context.auth.uid for Android (which uses the actual user ID for auth)
        const syncGroupUserId = data?.syncGroupUserId;
        const authUserId = context.auth?.uid;
        const userId = syncGroupUserId || authUserId;

        // Detect query type and respond appropriately
        const queryType = detectUserQueryType(message);

        let response;
        switch (queryType) {
            case "recovery_code":
                response = await handleRecoveryCodeQuery(userId);
                break;
            case "user_id":
                response = handleUserIdQuery(userId);
                break;
            case "data_usage":
                response = await handleDataUsageQuery(userId);
                break;
            case "subscription":
                response = await handleSubscriptionQuery(userId);
                break;
            case "account_info":
                response = await handleAccountInfoQuery(userId);
                break;
            case "device_info":
                response = await handleDeviceInfoQuery(userId);
                break;
            case "sync_status":
                response = await handleSyncStatusQuery(userId);
                break;
            case "message_stats":
                response = await handleMessageStatsQuery(userId);
                break;
            case "unpair_device":
                response = await handleUnpairDeviceQuery(userId, data?.message);
                break;
            case "reset_sync":
                response = await handleResetSyncQuery(userId);
                break;
            case "delete_account":
                response = await handleDeleteAccountQuery(userId);
                break;
            case "regenerate_recovery":
                response = handleRegenerateRecoveryQuery();
                break;
            case "billing_history":
                response = await handleBillingHistoryQuery(userId);
                break;
            case "cancel_subscription":
                response = await handleCancelSubscriptionQuery(userId);
                break;
            case "login_history":
                response = await handleLoginHistoryQuery(userId);
                break;
            case "signout_all":
                response = await handleSignOutAllQuery(userId);
                break;
            case "download_data":
                response = await handleDownloadDataQuery(userId);
                break;
            case "spam_settings":
                response = await handleSpamSettingsQuery(userId);
                break;
            case "spam_stats":
                response = await handleSpamStatsQuery(userId);
                break;
            case "help":
                response = getHelpResponse();
                break;
            default:
                response = getGeneralResponse(message);
                break;
        }

        return {
            success: true,
            response: response
        };
    } catch (error) {
        console.error("[SupportChat] Error:", error);
        return {
            success: false,
            response: "I'm sorry, I encountered an error processing your request. Please try again or contact support at syncflow.contact@gmail.com"
        };
    }
});

/**
 * Detect what type of user-specific query this is
 */
function detectUserQueryType(message) {
    const msg = message.toLowerCase();

    // Regenerate recovery code (check before recovery_code)
    if ((msg.includes("regenerate") || msg.includes("new") || msg.includes("reset")) &&
        (msg.includes("recovery") && (msg.includes("code") || msg.includes("key")))) {
        return "regenerate_recovery";
    }

    // Recovery code queries
    if (msg.includes("recovery") && (msg.includes("code") || msg.includes("key"))) {
        return "recovery_code";
    }
    if (msg.includes("backup code") || msg.includes("restore code")) {
        return "recovery_code";
    }

    // User ID queries
    if (msg.includes("user id") || msg.includes("userid") || msg.includes("my id")) {
        return "user_id";
    }
    if (msg.includes("account id")) {
        return "user_id";
    }

    // Sync status queries
    if (msg.includes("sync") && (msg.includes("status") || msg.includes("last") || msg.includes("when"))) {
        return "sync_status";
    }
    if (msg.includes("last sync") || msg.includes("sync error") || msg.includes("sync fail")) {
        return "sync_status";
    }

    // Reset sync queries
    if ((msg.includes("reset") || msg.includes("clear")) && msg.includes("sync")) {
        return "reset_sync";
    }

    // Message stats queries
    if (msg.includes("message") && (msg.includes("count") || msg.includes("total") || msg.includes("how many") || msg.includes("stats"))) {
        return "message_stats";
    }
    if (msg.includes("how many messages")) {
        return "message_stats";
    }

    // Unpair device queries
    if (msg.includes("unpair")) {
        return "unpair_device";
    }
    if ((msg.includes("remove") || msg.includes("disconnect")) && msg.includes("device")) {
        return "unpair_device";
    }

    // Delete account queries
    if (msg.includes("delete") && (msg.includes("account") || msg.includes("my data"))) {
        return "delete_account";
    }

    // Billing history queries
    if (msg.includes("billing") || msg.includes("payment") || msg.includes("invoice") || msg.includes("receipt")) {
        return "billing_history";
    }

    // Cancel subscription queries
    if (msg.includes("cancel") && (msg.includes("subscription") || msg.includes("plan") || msg.includes("pro"))) {
        return "cancel_subscription";
    }

    // Login history queries
    if (msg.includes("login") && (msg.includes("history") || msg.includes("activity") || msg.includes("recent"))) {
        return "login_history";
    }
    if (msg.includes("sign in") && msg.includes("history")) {
        return "login_history";
    }

    // Sign out all devices queries
    if ((msg.includes("sign out") || msg.includes("signout") || msg.includes("logout") || msg.includes("log out")) &&
        (msg.includes("all") || msg.includes("everywhere") || msg.includes("device"))) {
        return "signout_all";
    }

    // Download data / GDPR queries
    if ((msg.includes("download") || msg.includes("export")) && (msg.includes("data") || msg.includes("my"))) {
        return "download_data";
    }
    if (msg.includes("gdpr") || msg.includes("data request")) {
        return "download_data";
    }

    // Spam settings queries
    if (msg.includes("spam") && (msg.includes("setting") || msg.includes("filter") || msg.includes("config"))) {
        return "spam_settings";
    }

    // Spam stats queries
    if (msg.includes("spam") && (msg.includes("blocked") || msg.includes("count") || msg.includes("how many") || msg.includes("stats"))) {
        return "spam_stats";
    }

    // Data usage queries
    if (msg.includes("data") && (msg.includes("usage") || msg.includes("used") || msg.includes("storage"))) {
        return "data_usage";
    }
    if (msg.includes("how much") && (msg.includes("data") || msg.includes("storage") || msg.includes("space"))) {
        return "data_usage";
    }
    if (msg.includes("storage") || msg.includes("quota")) {
        return "data_usage";
    }

    // Subscription queries
    if (msg.includes("subscription") || msg.includes("plan") || msg.includes("premium") || msg.includes("pro")) {
        return "subscription";
    }
    if (msg.includes("trial") || msg.includes("expire") || msg.includes("renew")) {
        return "subscription";
    }

    // Account info queries
    if (msg.includes("account") && (msg.includes("info") || msg.includes("details") || msg.includes("status"))) {
        return "account_info";
    }
    if (msg.includes("my account")) {
        return "account_info";
    }

    // Device info queries
    if (msg.includes("device") && (msg.includes("info") || msg.includes("list") || msg.includes("connected"))) {
        return "device_info";
    }
    if (msg.includes("paired device") || msg.includes("my device")) {
        return "device_info";
    }

    // Help queries
    if (msg.includes("help") || msg.includes("what can you") || msg.includes("how do i")) {
        return "help";
    }

    return "general";
}

/**
 * Handle recovery code query
 */
async function handleRecoveryCodeQuery(userId) {
    if (!userId) {
        return "To view your recovery code, you need to be signed in. Please make sure you're logged into the app and try again.\n\nYour recovery code is shown in the app under Settings > Account > Recovery Code.";
    }

    try {
        const recoveryRef = admin.database().ref(`users/${userId}/recovery_info`);
        const snapshot = await recoveryRef.once('value');

        if (snapshot.exists()) {
            const data = snapshot.val();
            const code = data.code;
            const createdAt = data.createdAt;

            if (code) {
                const createdDate = createdAt ? new Date(createdAt).toLocaleDateString() : "unknown";
                return `Your recovery code is:\n\n**${code}**\n\nThis code was created on ${createdDate}.\n\n**Important:** Keep this code safe! You'll need it to recover your account if you reinstall the app or switch devices. Never share it with anyone.`;
            }
        }

        return "I couldn't find a recovery code for your account. You can generate one in the app under Settings > Account > Recovery Code.\n\nIf you've already set one up, try refreshing the app.";
    } catch (error) {
        console.error("[SupportChat] Error fetching recovery code:", error);
        return "I had trouble retrieving your recovery code. Please try again later or view it directly in the app under Settings > Account > Recovery Code.";
    }
}

/**
 * Handle user ID query
 */
function handleUserIdQuery(userId) {
    if (!userId) {
        return "To view your user ID, you need to be signed in. Please make sure you're logged into the app.\n\nYour user ID is shown in the app under Settings > Account.";
    }

    return `Your user ID is:\n\n**${userId}**\n\nThis is your unique account identifier. You may need this when contacting support.`;
}

/**
 * Handle data usage query
 */
async function handleDataUsageQuery(userId) {
    if (!userId) {
        return "To view your data usage, you need to be signed in. Please make sure you're logged into the app.";
    }

    try {
        const usageRef = admin.database().ref(`users/${userId}/usage`);
        const snapshot = await usageRef.once('value');

        if (snapshot.exists()) {
            const data = snapshot.val();
            const storageBytes = data.storageBytes || 0;
            const monthly = data.monthly || {};

            // Format storage
            const storageMB = (storageBytes / (1024 * 1024)).toFixed(2);
            const storageGB = (storageBytes / (1024 * 1024 * 1024)).toFixed(3);

            // Get current month stats
            const now = new Date();
            const monthKey = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
            const thisMonth = monthly[monthKey] || {};
            const messagesSynced = thisMonth.messagesSynced || 0;
            const mediaSynced = thisMonth.mediaSynced || 0;

            let response = `**Your Data Usage**\n\n`;
            response += `**Storage Used:** ${storageMB} MB (${storageGB} GB)\n`;
            response += `**Messages Synced This Month:** ${messagesSynced.toLocaleString()}\n`;
            response += `**Media Files Synced This Month:** ${mediaSynced.toLocaleString()}\n`;

            if (data.lastUpdatedAt) {
                const lastUpdated = new Date(data.lastUpdatedAt).toLocaleString();
                response += `\n*Last updated: ${lastUpdated}*`;
            }

            return response;
        }

        return "No usage data found for your account yet. Usage statistics will appear here once you start syncing messages.";
    } catch (error) {
        console.error("[SupportChat] Error fetching usage data:", error);
        return "I had trouble retrieving your usage data. Please try again later.";
    }
}

/**
 * Handle subscription query
 */
async function handleSubscriptionQuery(userId) {
    if (!userId) {
        return "To view your subscription details, you need to be signed in. Please make sure you're logged into the app.";
    }

    try {
        // Check both usage and subscription_records
        const [usageSnap, subRecordSnap] = await Promise.all([
            admin.database().ref(`users/${userId}/usage`).once('value'),
            admin.database().ref(`subscription_records/${userId}/active`).once('value')
        ]);

        const usage = usageSnap.val() || {};
        const subRecord = subRecordSnap.val() || {};

        // Merge data, preferring more specific values
        const plan = usage.plan || subRecord.plan || "free";
        const planExpiresAt = usage.planExpiresAt || subRecord.planExpiresAt;
        const trialExpiresAt = usage.freeTrialExpiresAt || subRecord.freeTrialExpiresAt;

        let response = `**Your Subscription**\n\n`;
        response += `**Current Plan:** ${plan.charAt(0).toUpperCase() + plan.slice(1)}\n`;

        if (plan === "free") {
            if (trialExpiresAt) {
                const trialEnd = new Date(trialExpiresAt);
                const now = new Date();
                if (trialEnd > now) {
                    const daysLeft = Math.ceil((trialEnd - now) / (1000 * 60 * 60 * 24));
                    response += `**Free Trial:** ${daysLeft} days remaining\n`;
                    response += `**Trial Ends:** ${trialEnd.toLocaleDateString()}\n`;
                } else {
                    response += `**Free Trial:** Expired\n`;
                }
            }
            response += `\nUpgrade to Pro to unlock unlimited devices, priority sync, and more features!`;
        } else if (planExpiresAt) {
            const expiryDate = new Date(planExpiresAt);
            const now = new Date();
            if (expiryDate > now) {
                const daysLeft = Math.ceil((expiryDate - now) / (1000 * 60 * 60 * 24));
                response += `**Status:** Active\n`;
                response += `**Renews/Expires:** ${expiryDate.toLocaleDateString()} (${daysLeft} days)\n`;
            } else {
                response += `**Status:** Expired\n`;
                response += `**Expired On:** ${expiryDate.toLocaleDateString()}\n`;
            }
        }

        return response;
    } catch (error) {
        console.error("[SupportChat] Error fetching subscription:", error);
        return "I had trouble retrieving your subscription details. Please try again later or check in the app under Settings > Subscription.";
    }
}

/**
 * Handle account info query (combines multiple pieces of info)
 */
async function handleAccountInfoQuery(userId) {
    if (!userId) {
        return "To view your account information, you need to be signed in. Please make sure you're logged into the app.";
    }

    try {
        const [usageSnap, devicesSnap, recoverySnap] = await Promise.all([
            admin.database().ref(`users/${userId}/usage`).once('value'),
            admin.database().ref(`users/${userId}/devices`).once('value'),
            admin.database().ref(`users/${userId}/recovery_info`).once('value')
        ]);

        const usage = usageSnap.val() || {};
        const devices = devicesSnap.val() || {};
        const recovery = recoverySnap.val() || {};

        const deviceCount = Object.keys(devices).length;
        const plan = usage.plan || "free";
        const hasRecoveryCode = !!recovery.code;

        let response = `**Your Account Summary**\n\n`;
        response += `**User ID:** ${userId}\n`;
        response += `**Plan:** ${plan.charAt(0).toUpperCase() + plan.slice(1)}\n`;
        response += `**Connected Devices:** ${deviceCount}\n`;
        response += `**Recovery Code:** ${hasRecoveryCode ? "Set up" : "Not set up"}\n`;

        if (usage.storageBytes) {
            const storageMB = (usage.storageBytes / (1024 * 1024)).toFixed(2);
            response += `**Storage Used:** ${storageMB} MB\n`;
        }

        return response;
    } catch (error) {
        console.error("[SupportChat] Error fetching account info:", error);
        return "I had trouble retrieving your account information. Please try again later.";
    }
}

/**
 * Handle device info query
 */
async function handleDeviceInfoQuery(userId) {
    if (!userId) {
        return "To view your connected devices, you need to be signed in. Please make sure you're logged into the app.";
    }

    try {
        const devicesRef = admin.database().ref(`users/${userId}/devices`);
        const snapshot = await devicesRef.once('value');

        if (snapshot.exists()) {
            const devices = snapshot.val();
            const deviceList = Object.entries(devices);

            if (deviceList.length === 0) {
                return "You don't have any devices connected yet. Open SyncFlow on your devices to connect them.";
            }

            let response = `**Your Connected Devices (${deviceList.length})**\n\n`;

            deviceList.forEach(([deviceId, info], index) => {
                const deviceName = info.deviceName || info.name || "Unknown Device";
                const platform = info.platform || "unknown";
                const lastSeen = info.lastSeen ? new Date(info.lastSeen).toLocaleString() : "unknown";

                response += `${index + 1}. **${deviceName}**\n`;
                response += `   - Platform: ${platform}\n`;
                response += `   - Last active: ${lastSeen}\n\n`;
            });

            return response;
        }

        return "No devices found for your account. Open SyncFlow on your devices to connect them.";
    } catch (error) {
        console.error("[SupportChat] Error fetching devices:", error);
        return "I had trouble retrieving your device information. Please try again later.";
    }
}

/**
 * Get help response listing available queries
 */
function getHelpResponse() {
    return `**Hi! I'm the SyncFlow AI Assistant.**\n\nI can help you with your account. Here's what you can ask:\n\n**Account & Security:**\n- "What's my user ID?"\n- "What's my recovery code?"\n- "Show my login history"\n- "Sign out all devices"\n- "Regenerate recovery code"\n\n**Sync & Messages:**\n- "What's my sync status?"\n- "How many messages synced?"\n- "Reset my sync"\n\n**Devices:**\n- "Show my devices"\n- "Unpair a device"\n\n**Subscription & Billing:**\n- "What's my plan?"\n- "Show billing history"\n- "Cancel subscription"\n\n**Data & Privacy:**\n- "How much data have I used?"\n- "Download my data"\n- "Delete my account"\n\n**Spam:**\n- "Show spam settings"\n- "How many spam blocked?"\n\n**General Help:**\n- "How do I pair devices?"\n- "Messages not syncing"\n\nJust type your question!`;
}

/**
 * Get general response for non-specific queries
 */
function getGeneralResponse(message) {
    const msg = message.toLowerCase();

    // Unpair device questions (must check BEFORE "pair" since "unpair" contains "pair")
    if (msg.includes("unpair") || (msg.includes("remove") && msg.includes("device")) || (msg.includes("disconnect") && msg.includes("device"))) {
        return `**How to Unpair a Device:**\n\nYou can unpair devices in two ways:\n\n**Option 1 - Ask me:**\nSay "unpair device [device name]" and I'll remove it for you.\n\nFirst, ask "show my devices" to see your connected devices.\n\n**Option 2 - Manual:**\n- On Android: Settings → Pair Device → tap the X next to the device\n- On Mac/Web: Settings → Connected Devices → Remove\n\n**Note:** Unpairing removes the device's access to your messages. You can re-pair anytime.`;
    }

    // Device pairing questions
    if (msg.includes("pair") || msg.includes("connect") || msg.includes("link") || msg.includes("qr")) {
        return `**How to Pair Devices:**\n\n1. Open SyncFlow on your Android phone\n2. Go to Settings → Pair Device\n3. Tap "Scan QR Code"\n4. On Mac: Open SyncFlow app - QR code appears automatically\n5. On Web: Go to https://sfweb.app - QR code appears\n6. Scan the QR code with your Android phone\n\nYou can pair multiple Mac computers and web browsers to the same phone.`;
    }

    // Sync issues
    if (msg.includes("not working") || msg.includes("messages not") || (msg.includes("sync") && msg.includes("issue"))) {
        return `**Troubleshooting Sync Issues:**\n\n1. **Check connection:** Make sure both devices have internet\n2. **Check permissions:** On Android, ensure SMS permissions are granted\n3. **Force sync:** Go to Settings → Sync Message History → Sync\n4. **Battery optimization:** Make sure SyncFlow isn't being killed by battery saver\n5. **Reset sync:** Ask me "reset my sync" to clear and restart\n\nIf issues persist, try unpairing and re-pairing your devices.`;
    }

    // Pro/Premium/Subscription features
    if (msg.includes("pro") || msg.includes("premium") || msg.includes("upgrade") || msg.includes("price") || msg.includes("cost")) {
        return `**SyncFlow Plans:**\n\n| Plan | Price | Features |\n|------|-------|----------|\n| Free Trial | 7 days | Full access |\n| Monthly | $4.99/mo | Full access |\n| Yearly | $39.99/yr | Save 33% |\n| 3-Year | $79.99 | Best value |\n\n**Pro features:** Unlimited devices, 100MB file transfers, 500MB/day transfer limit, priority sync, no ads.\n\nUpgrade in Settings → Subscription.`;
    }

    // File transfer questions
    if (msg.includes("file") || msg.includes("transfer") || msg.includes("send photo") || msg.includes("send video")) {
        return `**File Transfer:**\n\n**From Mac to Android:**\n- Use Quick Drop in Mac sidebar, or\n- File → Send File menu\n\n**From Android to Mac:**\n- Go to Settings → Send Files to Mac (only visible when paired)\n\n**Limits:**\n| Plan | Max File | Daily Limit |\n|------|----------|-------------|\n| Free | 25 MB | 100 MB/day |\n| Pro | 100 MB | 500 MB/day |\n\nFiles are saved to Downloads folder on both devices.`;
    }

    // Recovery code questions
    if (msg.includes("recovery") && !msg.includes("delete") && !msg.includes("account")) {
        return `**Recovery Code:**\n\nYour recovery code lets you restore your account on new devices.\n\n**Find it:** Ask me "What's my recovery code?"\n\n**Use it:** When setting up a new device, tap "I have a recovery code" and enter your 12-character code.\n\n**Regenerate:** Ask me "Regenerate my recovery code" (invalidates the old one immediately)\n\n⚠️ If your account is scheduled for deletion, the recovery code is disabled.`;
    }

    // Account deletion questions
    if (msg.includes("delete") && !msg.includes("message")) {
        return `**Account Deletion:**\n\n1. Go to Settings → Account → Delete Account\n2. Choose a reason (optional)\n3. Confirm deletion\n\n**What happens:**\n- Account is scheduled for deletion in **30 days**\n- Recovery code is **immediately disabled**\n- You can continue using the app during this period\n- After 30 days, all data is permanently deleted\n\n**Cancel deletion:** Go to Settings → Account → Cancel Deletion (within 30 days)\n\n**Changed your mind after deleting the app?** Contact syncflow.contact@gmail.com with your device name.`;
    }

    // Privacy/Security
    if (msg.includes("privacy") || msg.includes("secure") || (msg.includes("data") && msg.includes("safe"))) {
        return `**Your Privacy & Security:**\n\n- End-to-end encryption using Signal Protocol\n- HTTPS/TLS for all data transmission\n- Data stored securely in Firebase (Google Cloud)\n- We never sell or share your data\n- Recovery codes are hashed\n- On-device AI processing (no data sent to AI servers)\n\n**Delete your data:** Settings → Account → Delete Account (30-day grace period)`;
    }

    // Call/calling questions
    if (msg.includes("call") || msg.includes("phone") || msg.includes("dial")) {
        return `**Making Calls:**\n\n**From Mac/Web:**\n- Click the phone icon next to any contact or conversation\n- This triggers your Android phone to dial the number\n- The actual call happens on your phone\n\n**SyncFlow-to-SyncFlow calls:**\n- Audio/video calls between SyncFlow users over the internet\n- Works via WebRTC\n\n**Call recording:** Available on macOS for WebRTC calls only.`;
    }

    // MMS/Group messages
    if (msg.includes("mms") || msg.includes("group") || msg.includes("picture") || msg.includes("image")) {
        return `**MMS & Group Messages:**\n\n- ✅ Full MMS support (images, videos, audio)\n- ✅ Group MMS conversations supported\n- ✅ View all participants in group chats\n- ✅ Send media from Mac/Web\n\n**Note:** RCS is not yet supported (requires carrier integration).`;
    }

    // iOS/Windows questions
    if (msg.includes("ios") || msg.includes("iphone") || msg.includes("windows") || msg.includes("pc")) {
        return `**Platform Support:**\n\n- ✅ **Android phone** (required - source of messages)\n- ✅ **macOS app** (Mac App Store)\n- ✅ **Web app** (https://sfweb.app)\n\n❌ **No iOS app** - iPhone doesn't allow third-party SMS access\n❌ **No Windows app** - Use the web app at sfweb.app instead\n\nThe web app works on any device with a browser!`;
    }

    // Default helpful response
    return `I can help with:\n\n**Account:**\n- "What's my user ID?" / "What's my recovery code?"\n- "Show my subscription" / "How much data have I used?"\n- "Show my devices" / "How do I delete my account?"\n\n**Features:**\n- "How do I pair?" / "How do I transfer files?"\n- "What are Pro features?" / "Is there an iOS app?"\n\n**Troubleshooting:**\n- "Messages not syncing" / "Reset my sync"\n\nOr contact syncflow.contact@gmail.com for more help!`;
}

/**
 * Handle sync status query
 */
async function handleSyncStatusQuery(userId) {
    if (!userId) {
        return "To view your sync status, you need to be signed in. Please make sure you're logged into the app.";
    }

    try {
        const [devicesSnap, syncErrorsSnap] = await Promise.all([
            admin.database().ref(`users/${userId}/devices`).once('value'),
            admin.database().ref(`users/${userId}/sync_errors`).limitToLast(5).once('value')
        ]);

        const devices = devicesSnap.val() || {};
        const syncErrors = syncErrorsSnap.val() || {};

        let response = `**Your Sync Status**\n\n`;

        // Show last sync per device
        const deviceList = Object.entries(devices);
        if (deviceList.length > 0) {
            response += `**Last Sync by Device:**\n`;
            deviceList.forEach(([deviceId, info]) => {
                const deviceName = info.deviceName || info.name || "Unknown Device";
                const lastSync = info.lastSyncedAt || info.lastSeen;
                const syncTime = lastSync ? new Date(lastSync).toLocaleString() : "Never";
                const status = info.syncStatus || "unknown";
                response += `- **${deviceName}:** ${syncTime}`;
                if (status === "error") {
                    response += ` (Error)`;
                }
                response += `\n`;
            });
        } else {
            response += `No devices connected.\n`;
        }

        // Show recent errors if any
        const errorList = Object.values(syncErrors);
        if (errorList.length > 0) {
            response += `\n**Recent Sync Issues:**\n`;
            errorList.slice(-3).forEach((error) => {
                const errorTime = error.timestamp ? new Date(error.timestamp).toLocaleString() : "Unknown time";
                const errorMsg = error.message || error.error || "Unknown error";
                response += `- ${errorTime}: ${errorMsg}\n`;
            });
        } else {
            response += `\n*No recent sync errors.*`;
        }

        return response;
    } catch (error) {
        console.error("[SupportChat] Error fetching sync status:", error);
        return "I had trouble retrieving your sync status. Please try again later.";
    }
}

/**
 * Handle message stats query
 */
async function handleMessageStatsQuery(userId) {
    if (!userId) {
        return "To view your message statistics, you need to be signed in. Please make sure you're logged into the app.";
    }

    try {
        const [usageSnap, messagesSnap] = await Promise.all([
            admin.database().ref(`users/${userId}/usage`).once('value'),
            admin.database().ref(`users/${userId}/messages`).once('value')
        ]);

        const usage = usageSnap.val() || {};
        const messages = messagesSnap.val() || {};
        const monthly = usage.monthly || {};

        // Count total messages
        const totalMessages = Object.keys(messages).length;

        // Get monthly stats
        const now = new Date();
        const monthKey = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
        const thisMonth = monthly[monthKey] || {};

        // Calculate all-time total from monthly data
        let allTimeSynced = 0;
        Object.values(monthly).forEach((m) => {
            allTimeSynced += m.messagesSynced || 0;
        });

        let response = `**Your Message Statistics**\n\n`;
        response += `**Total Messages Stored:** ${totalMessages.toLocaleString()}\n`;
        response += `**All-Time Messages Synced:** ${allTimeSynced.toLocaleString()}\n`;
        response += `**This Month Synced:** ${(thisMonth.messagesSynced || 0).toLocaleString()}\n`;
        response += `**This Month Media:** ${(thisMonth.mediaSynced || 0).toLocaleString()} files\n`;

        if (usage.lastMessageAt) {
            const lastMsg = new Date(usage.lastMessageAt).toLocaleString();
            response += `\n**Last Message:** ${lastMsg}`;
        }

        return response;
    } catch (error) {
        console.error("[SupportChat] Error fetching message stats:", error);
        return "I had trouble retrieving your message statistics. Please try again later.";
    }
}

/**
 * Handle unpair device query
 */
async function handleUnpairDeviceQuery(userId, originalMessage) {
    if (!userId) {
        return "To manage your devices, you need to be signed in. Please make sure you're logged into the app.";
    }

    try {
        const devicesSnap = await admin.database().ref(`users/${userId}/devices`).once('value');
        const devices = devicesSnap.val() || {};
        const deviceList = Object.entries(devices);

        if (deviceList.length === 0) {
            return "You don't have any devices connected to unpair.";
        }

        // Check if user specified a device name or number
        const msg = (originalMessage || "").toLowerCase();

        // Try to match device by name or number
        let matchedDevice = null;
        deviceList.forEach(([deviceId, info], index) => {
            const deviceName = (info.deviceName || info.name || "").toLowerCase();
            if (msg.includes(deviceName) || msg.includes(`device ${index + 1}`) || msg.includes(`#${index + 1}`)) {
                matchedDevice = { deviceId, info, index };
            }
        });

        if (matchedDevice) {
            // Remove the device
            await admin.database().ref(`users/${userId}/devices/${matchedDevice.deviceId}`).remove();

            const deviceName = matchedDevice.info.deviceName || matchedDevice.info.name || "Device";
            return `**Device Unpaired Successfully**\n\n"${deviceName}" has been removed from your account.\n\nIf you want to use this device again, you'll need to re-pair it.`;
        }

        // Show device list for user to choose
        let response = `**Which device would you like to unpair?**\n\n`;
        deviceList.forEach(([deviceId, info], index) => {
            const deviceName = info.deviceName || info.name || "Unknown Device";
            const platform = info.platform || "unknown";
            response += `${index + 1}. **${deviceName}** (${platform})\n`;
        });

        response += `\nReply with "unpair device [name]" or "unpair device #[number]" to remove a device.`;

        return response;
    } catch (error) {
        console.error("[SupportChat] Error handling unpair request:", error);
        return "I had trouble processing your request. Please try again later or unpair devices from the app Settings.";
    }
}

/**
 * Handle reset sync query
 */
async function handleResetSyncQuery(userId) {
    if (!userId) {
        return "To reset your sync, you need to be signed in. Please make sure you're logged into the app.";
    }

    try {
        // Clear sync state data
        const updates = {};
        updates[`users/${userId}/sync_state`] = null;
        updates[`users/${userId}/sync_errors`] = null;
        updates[`users/${userId}/last_sync_token`] = null;

        await admin.database().ref().update(updates);

        // Update device sync timestamps
        const devicesSnap = await admin.database().ref(`users/${userId}/devices`).once('value');
        if (devicesSnap.exists()) {
            const deviceUpdates = {};
            devicesSnap.forEach((child) => {
                deviceUpdates[`users/${userId}/devices/${child.key}/needsFullSync`] = true;
                deviceUpdates[`users/${userId}/devices/${child.key}/syncResetAt`] = Date.now();
            });
            await admin.database().ref().update(deviceUpdates);
        }

        // Send service health alert email to admin
        try {
            await sendServiceHealthEmail(userId, "sync_reset");
        } catch (e) {
            console.error("[SupportChat] Failed to send service health email:", e);
        }

        return `**Sync Reset Complete**\n\nYour sync state has been cleared. The next time you open SyncFlow on any device, it will perform a fresh sync.\n\n**What happens now:**\n- Your messages are still stored safely\n- All devices will re-sync from scratch\n- This may take a few minutes depending on your message count\n\nOpen the SyncFlow app to start the fresh sync.`;
    } catch (error) {
        console.error("[SupportChat] Error resetting sync:", error);
        return "I had trouble resetting your sync. Please try again later or contact support.";
    }
}

/**
 * Handle delete account query
 */
async function handleDeleteAccountQuery(userId) {
    if (!userId) {
        return "To delete your account, you need to be signed in. Please make sure you're logged into the app.";
    }

    // Check if already scheduled for deletion
    try {
        const deletionSnap = await admin.database().ref(`users/${userId}/deletion_scheduled`).once('value');
        if (deletionSnap.exists()) {
            const deletion = deletionSnap.val();
            const daysRemaining = Math.ceil((deletion.scheduledDeletionAt - Date.now()) / (24 * 60 * 60 * 1000));
            const deletionDate = new Date(deletion.scheduledDeletionAt).toLocaleDateString();

            return `**Account Already Scheduled for Deletion**\n\nYour account is scheduled to be deleted on **${deletionDate}** (${Math.max(0, daysRemaining)} days remaining).\n\n**Changed your mind?**\nYou can cancel the deletion anytime before that date:\n1. Go to Settings > Account\n2. Tap "Cancel Deletion"\n\nOnce cancelled, your account will be restored to normal.`;
        }
    } catch (e) {
        console.error("[SupportChat] Error checking deletion status:", e);
    }

    // Send notification email to admin
    try {
        await sendDeletionNotificationEmail(userId, "account_deletion_inquiry");
    } catch (e) {
        console.error("[SupportChat] Failed to send deletion notification email:", e);
    }

    return `**Delete Your Account**\n\nTo delete your SyncFlow account and all associated data:\n\n**In the App:**\n1. Open SyncFlow on your Android or Mac\n2. Go to Settings > Account\n3. Tap "Delete Account"\n4. Confirm the deletion\n\n**What happens:**\n- Your account will be marked for deletion\n- You have **30 days** to change your mind\n- After 30 days, all data is permanently deleted\n- Recovery code will stop working immediately\n\n**What gets deleted:**\n- All synced messages\n- All connected devices\n- Your recovery code\n- Subscription data\n- All personal information\n\n**Important:** Cancel any active subscriptions through Google Play/App Store first.`;
}

/**
 * Handle regenerate recovery code query
 */
function handleRegenerateRecoveryQuery() {
    return `**Regenerate Recovery Code**\n\nTo generate a new recovery code:\n\n1. Open SyncFlow on your Android device\n2. Go to Settings > Account > Recovery Code\n3. Tap "Regenerate Code"\n4. Save your new code securely\n\n**Important:**\n- Your old recovery code will stop working immediately\n- Make sure to save the new code before closing the screen\n- Store it somewhere safe (password manager, written down, etc.)\n\n*For security, recovery codes can only be regenerated from the Android app.*`;
}

/**
 * Handle billing history query
 */
async function handleBillingHistoryQuery(userId) {
    if (!userId) {
        return "To view your billing history, you need to be signed in. Please make sure you're logged into the app.";
    }

    try {
        const [subRecordSnap, paymentsSnap] = await Promise.all([
            admin.database().ref(`subscription_records/${userId}`).once('value'),
            admin.database().ref(`users/${userId}/payments`).limitToLast(10).once('value')
        ]);

        const subRecord = subRecordSnap.val() || {};
        const payments = paymentsSnap.val() || {};
        const history = subRecord.history || {};

        let response = `**Your Billing History**\n\n`;

        // Show current plan
        const active = subRecord.active || {};
        response += `**Current Plan:** ${(active.plan || "free").charAt(0).toUpperCase() + (active.plan || "free").slice(1)}\n`;
        if (subRecord.wasPremium) {
            response += `*Premium member since ${subRecord.firstPremiumDate ? new Date(subRecord.firstPremiumDate).toLocaleDateString() : "unknown"}*\n`;
        }

        // Show payment history
        const paymentList = Object.values(payments);
        if (paymentList.length > 0) {
            response += `\n**Recent Payments:**\n`;
            paymentList.slice(-5).reverse().forEach((payment) => {
                const date = payment.timestamp ? new Date(payment.timestamp).toLocaleDateString() : "Unknown";
                const amount = payment.amount ? `$${(payment.amount / 100).toFixed(2)}` : "N/A";
                const status = payment.status || "completed";
                response += `- ${date}: ${amount} (${payment.plan || "Pro"}) - ${status}\n`;
            });
        } else {
            response += `\n*No payment history found.*\n`;
        }

        // Show plan changes
        const historyList = Object.entries(history).sort((a, b) => parseInt(b[0]) - parseInt(a[0]));
        if (historyList.length > 0) {
            response += `\n**Plan History:**\n`;
            historyList.slice(0, 5).forEach(([timestamp, entry]) => {
                const date = new Date(parseInt(timestamp)).toLocaleDateString();
                response += `- ${date}: Changed to ${entry.newPlan || "unknown"}\n`;
            });
        }

        response += `\n*For receipts or billing questions, contact syncflow.contact@gmail.com*`;

        return response;
    } catch (error) {
        console.error("[SupportChat] Error fetching billing history:", error);
        return "I had trouble retrieving your billing history. Please try again later.";
    }
}

/**
 * Handle cancel subscription query
 */
async function handleCancelSubscriptionQuery(userId) {
    // Send churn alert email to admin
    try {
        await sendChurnAlertEmail(userId, "cancel_subscription_inquiry");
    } catch (e) {
        console.error("[SupportChat] Failed to send churn alert email:", e);
    }

    return `**Cancel Your Subscription**\n\nSyncFlow subscriptions are managed through your device's app store:\n\n**For Google Play (Android):**\n1. Open Google Play Store\n2. Tap your profile icon > Payments & subscriptions\n3. Tap Subscriptions\n4. Find SyncFlow and tap Cancel\n\n**For Apple (if applicable):**\n1. Open Settings > tap your name\n2. Tap Subscriptions\n3. Find SyncFlow and tap Cancel\n\n**What happens after cancellation:**\n- You keep Pro features until your current period ends\n- After that, you'll be downgraded to the free plan\n- Your data and messages remain intact\n- You can resubscribe anytime\n\n*Need help? Contact syncflow.contact@gmail.com*`;
}

/**
 * Handle login history query
 */
async function handleLoginHistoryQuery(userId) {
    if (!userId) {
        return "To view your login history, you need to be signed in. Please make sure you're logged into the app.";
    }

    try {
        const [sessionsSnap, loginHistorySnap] = await Promise.all([
            admin.database().ref(`users/${userId}/sessions`).once('value'),
            admin.database().ref(`users/${userId}/login_history`).limitToLast(10).once('value')
        ]);

        const sessions = sessionsSnap.val() || {};
        const loginHistory = loginHistorySnap.val() || {};

        let response = `**Your Login History**\n\n`;

        // Active sessions
        const sessionList = Object.entries(sessions);
        if (sessionList.length > 0) {
            response += `**Active Sessions (${sessionList.length}):**\n`;
            sessionList.forEach(([sessionId, info]) => {
                const device = info.deviceName || info.device || "Unknown device";
                const lastActive = info.lastActivity ? new Date(info.lastActivity).toLocaleString() : "Unknown";
                const location = info.location || info.ip || "";
                response += `- **${device}**\n  Last active: ${lastActive}${location ? `\n  Location: ${location}` : ""}\n`;
            });
        } else {
            response += `*No active sessions found.*\n`;
        }

        // Recent logins
        const historyList = Object.values(loginHistory);
        if (historyList.length > 0) {
            response += `\n**Recent Sign-ins:**\n`;
            historyList.slice(-5).reverse().forEach((login) => {
                const date = login.timestamp ? new Date(login.timestamp).toLocaleString() : "Unknown";
                const device = login.device || "Unknown device";
                const status = login.success === false ? " (Failed)" : "";
                response += `- ${date}: ${device}${status}\n`;
            });
        }

        return response;
    } catch (error) {
        console.error("[SupportChat] Error fetching login history:", error);
        return "I had trouble retrieving your login history. Please try again later.";
    }
}

/**
 * Handle sign out all devices query
 */
async function handleSignOutAllQuery(userId) {
    if (!userId) {
        return "To sign out all devices, you need to be signed in. Please make sure you're logged into the app.";
    }

    try {
        // Clear all sessions
        await admin.database().ref(`users/${userId}/sessions`).remove();

        // Set a flag to force re-authentication on all devices
        await admin.database().ref(`users/${userId}/forceReauth`).set({
            timestamp: Date.now(),
            reason: "user_requested"
        });

        // Revoke all refresh tokens if using Firebase Auth
        try {
            await admin.auth().revokeRefreshTokens(userId);
        } catch (authError) {
            // User might be anonymous, which doesn't have revokable tokens
            console.log("[SupportChat] Could not revoke tokens (might be anonymous user)");
        }

        // Send security alert email to admin
        try {
            await sendSecurityAlertEmail(userId, "signout_all_devices");
        } catch (e) {
            console.error("[SupportChat] Failed to send security alert email:", e);
        }

        return `**Signed Out of All Devices**\n\nYou have been signed out of all devices except this one.\n\n**What happens now:**\n- All other devices will need to sign in again\n- Any active syncs on other devices will stop\n- Your data remains safe and intact\n\nIf you didn't request this, please change your recovery code immediately and contact support.`;
    } catch (error) {
        console.error("[SupportChat] Error signing out all devices:", error);
        return "I had trouble signing out your devices. Please try again later.";
    }
}

/**
 * Handle download data query (GDPR)
 */
async function handleDownloadDataQuery(userId) {
    if (!userId) {
        return "To request your data, you need to be signed in. Please make sure you're logged into the app.";
    }

    try {
        // Create a data export request
        const requestId = `export_${Date.now()}`;
        await admin.database().ref(`data_export_requests/${requestId}`).set({
            userId: userId,
            requestedAt: Date.now(),
            status: "pending",
            type: "full_export"
        });

        // Send notification email to admin
        try {
            await sendDeletionNotificationEmail(userId, "data_export_request", requestId);
        } catch (e) {
            console.error("[SupportChat] Failed to send data export notification email:", e);
        }

        return `**Data Export Request Submitted**\n\nYour request ID: **${requestId}**\n\n**What happens next:**\n1. We'll prepare an export of all your data\n2. This includes: messages, contacts, settings, and account info\n3. You'll receive an email when it's ready (usually within 24-48 hours)\n4. The download link will be valid for 7 days\n\n**Your data includes:**\n- All synced messages\n- Contact information\n- Device settings\n- Account preferences\n- Subscription history\n\n*If you don't receive the email, check your spam folder or contact syncflow.contact@gmail.com*`;
    } catch (error) {
        console.error("[SupportChat] Error creating data export request:", error);
        return "I had trouble submitting your data export request. Please try again later or email syncflow.contact@gmail.com with subject 'Data Export Request'.";
    }
}

/**
 * Handle spam settings query
 */
async function handleSpamSettingsQuery(userId) {
    if (!userId) {
        return "To view your spam settings, you need to be signed in. Please make sure you're logged into the app.";
    }

    try {
        const settingsSnap = await admin.database().ref(`users/${userId}/settings/spam`).once('value');
        const settings = settingsSnap.val() || {};

        const filterLevel = settings.filterLevel || "medium";
        const autoDelete = settings.autoDelete || false;
        const autoDeleteDays = settings.autoDeleteDays || 30;
        const notifyOnSpam = settings.notifyOnSpam !== false;

        let response = `**Your Spam Filter Settings**\n\n`;
        response += `**Filter Level:** ${filterLevel.charAt(0).toUpperCase() + filterLevel.slice(1)}\n`;
        response += `- Low: Only obvious spam\n- Medium: Balanced detection (recommended)\n- High: Aggressive filtering\n\n`;
        response += `**Auto-Delete Spam:** ${autoDelete ? `Yes (after ${autoDeleteDays} days)` : "No"}\n`;
        response += `**Spam Notifications:** ${notifyOnSpam ? "On" : "Off"}\n`;

        response += `\n**To change these settings:**\nOpen SyncFlow > Settings > Spam Filter`;

        return response;
    } catch (error) {
        console.error("[SupportChat] Error fetching spam settings:", error);
        return "I had trouble retrieving your spam settings. Please try again later.";
    }
}

/**
 * Handle spam stats query
 */
async function handleSpamStatsQuery(userId) {
    if (!userId) {
        return "To view your spam statistics, you need to be signed in. Please make sure you're logged into the app.";
    }

    try {
        const spamSnap = await admin.database().ref(`users/${userId}/spam_messages`).once('value');
        const spam = spamSnap.val() || {};

        const spamList = Object.values(spam);
        const totalBlocked = spamList.length;

        // Count by category
        const byCategory = {};
        const byDate = {};
        const now = Date.now();
        const thirtyDaysAgo = now - (30 * 24 * 60 * 60 * 1000);

        spamList.forEach((msg) => {
            // By category
            const category = msg.spamCategory || msg.category || "Unknown";
            byCategory[category] = (byCategory[category] || 0) + 1;

            // By recent (last 30 days)
            const msgDate = msg.date || msg.detectedAt || 0;
            if (msgDate > thirtyDaysAgo) {
                const dateKey = new Date(msgDate).toLocaleDateString();
                byDate[dateKey] = (byDate[dateKey] || 0) + 1;
            }
        });

        let response = `**Your Spam Statistics**\n\n`;
        response += `**Total Spam Blocked:** ${totalBlocked.toLocaleString()} messages\n\n`;

        // Top categories
        const sortedCategories = Object.entries(byCategory)
            .sort((a, b) => b[1] - a[1])
            .slice(0, 5);

        if (sortedCategories.length > 0) {
            response += `**Top Spam Categories:**\n`;
            sortedCategories.forEach(([category, count]) => {
                response += `- ${category}: ${count}\n`;
            });
        }

        // Recent activity
        const recentCount = Object.values(byDate).reduce((sum, c) => sum + c, 0);
        response += `\n**Last 30 Days:** ${recentCount} spam messages blocked`;

        return response;
    } catch (error) {
        console.error("[SupportChat] Error fetching spam stats:", error);
        return "I had trouble retrieving your spam statistics. Please try again later.";
    }
}

// ============================================
// ADMIN - ACCOUNT RECOVERY MANAGEMENT
// ============================================

/**
 * Admin: List all accounts scheduled for deletion
 */
exports.adminListScheduledDeletions = functions.https.onCall(async (data, context) => {
    try {
        requireAdmin(context);

        const deletionsSnap = await admin.database().ref("scheduled_deletions").once("value");

        if (!deletionsSnap.exists()) {
            return { success: true, scheduledDeletions: [] };
        }

        const deletions = [];
        const now = Date.now();

        deletionsSnap.forEach((child) => {
            const deletion = child.val();
            const daysRemaining = Math.ceil((deletion.scheduledDeletionAt - now) / (24 * 60 * 60 * 1000));

            deletions.push({
                userId: child.key,
                requestedAt: deletion.requestedAt,
                scheduledDeletionAt: deletion.scheduledDeletionAt,
                daysRemaining: Math.max(0, daysRemaining),
                reason: deletion.reason,
                deviceNames: deletion.deviceNames || [],
                phoneNumbers: deletion.phoneNumbers || [],
                deviceCount: deletion.deviceCount || 0,
                messageCount: deletion.messageCount || 0,
                plan: deletion.plan || "free"
            });
        });

        // Sort by days remaining (soonest first)
        deletions.sort((a, b) => a.daysRemaining - b.daysRemaining);

        console.log(`[Admin] Listed ${deletions.length} scheduled deletions`);
        return { success: true, scheduledDeletions: deletions };
    } catch (error) {
        console.error("[Admin] List scheduled deletions error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", "Failed to list scheduled deletions");
    }
});

/**
 * Admin: List all permanently deleted accounts
 */
exports.adminListDeletedAccounts = functions.https.onCall(async (data, context) => {
    try {
        requireAdmin(context);

        const limit = data?.limit || 100;
        const deletedSnap = await admin.database().ref("deleted_accounts")
            .orderByChild("deletedAt")
            .limitToLast(limit)
            .once("value");

        if (!deletedSnap.exists()) {
            return { success: true, deletedAccounts: [] };
        }

        const deletedAccounts = [];
        deletedSnap.forEach((child) => {
            const account = child.val();
            deletedAccounts.push({
                userId: child.key,
                deletedAt: account.deletedAt,
                requestedAt: account.requestedAt,
                reason: account.reason,
                deviceNames: account.deviceNames || [],
                phoneNumbers: account.phoneNumbers || [],
                deviceCount: account.deviceCount || 0,
                messageCount: account.messageCount || 0,
                plan: account.plan || "free",
                status: account.status || "deleted",
                canRestore: account.canRestore || false
            });
        });

        // Sort by deletion date (most recent first)
        deletedAccounts.sort((a, b) => (b.deletedAt || 0) - (a.deletedAt || 0));

        console.log(`[Admin] Listed ${deletedAccounts.length} deleted accounts`);
        return { success: true, deletedAccounts };
    } catch (error) {
        console.error("[Admin] List deleted accounts error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", "Failed to list deleted accounts");
    }
});

/**
 * Admin: Search accounts by phone number, device name, or user ID
 */
exports.adminSearchAccounts = functions.https.onCall(async (data, context) => {
    try {
        requireAdmin(context);

        const searchQuery = data?.query?.toLowerCase();
        if (!searchQuery || searchQuery.length < 3) {
            throw new functions.https.HttpsError("invalid-argument", "Search query must be at least 3 characters");
        }

        const results = {
            scheduledDeletions: [],
            deletedAccounts: []
        };

        // Search scheduled deletions
        const scheduledSnap = await admin.database().ref("scheduled_deletions").once("value");
        if (scheduledSnap.exists()) {
            scheduledSnap.forEach((child) => {
                const data = child.val();
                const userId = child.key;

                // Search in userId
                if (userId.toLowerCase().includes(searchQuery)) {
                    results.scheduledDeletions.push({ userId, ...data, matchedOn: "userId" });
                    return;
                }

                // Search in phone numbers
                const phoneNumbers = data.phoneNumbers || [];
                const matchedPhone = phoneNumbers.find(p =>
                    p.replace(/\D/g, '').includes(searchQuery.replace(/\D/g, ''))
                );
                if (matchedPhone) {
                    results.scheduledDeletions.push({ userId, ...data, matchedOn: "phoneNumber", matchedValue: matchedPhone });
                    return;
                }

                // Search in device names
                const deviceNames = data.deviceNames || [];
                const matchedDevice = deviceNames.find(d =>
                    d.name?.toLowerCase().includes(searchQuery)
                );
                if (matchedDevice) {
                    results.scheduledDeletions.push({ userId, ...data, matchedOn: "deviceName", matchedValue: matchedDevice.name });
                }
            });
        }

        // Search deleted accounts
        const deletedSnap = await admin.database().ref("deleted_accounts").once("value");
        if (deletedSnap.exists()) {
            deletedSnap.forEach((child) => {
                const data = child.val();
                const userId = child.key;

                // Search in userId
                if (userId.toLowerCase().includes(searchQuery)) {
                    results.deletedAccounts.push({ userId, ...data, matchedOn: "userId" });
                    return;
                }

                // Search in phone numbers
                const phoneNumbers = data.phoneNumbers || [];
                const matchedPhone = phoneNumbers.find(p =>
                    p.replace(/\D/g, '').includes(searchQuery.replace(/\D/g, ''))
                );
                if (matchedPhone) {
                    results.deletedAccounts.push({ userId, ...data, matchedOn: "phoneNumber", matchedValue: matchedPhone });
                    return;
                }

                // Search in device names
                const deviceNames = data.deviceNames || [];
                const matchedDevice = deviceNames.find(d =>
                    d.name?.toLowerCase().includes(searchQuery)
                );
                if (matchedDevice) {
                    results.deletedAccounts.push({ userId, ...data, matchedOn: "deviceName", matchedValue: matchedDevice.name });
                }
            });
        }

        console.log(`[Admin] Search "${searchQuery}": found ${results.scheduledDeletions.length} scheduled, ${results.deletedAccounts.length} deleted`);
        return { success: true, ...results };
    } catch (error) {
        console.error("[Admin] Search accounts error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", "Failed to search accounts");
    }
});

/**
 * Admin: Cancel a user's scheduled deletion
 */
exports.adminCancelDeletion = functions.https.onCall(async (data, context) => {
    try {
        requireAdmin(context);

        const targetUserId = data?.userId;
        if (!targetUserId) {
            throw new functions.https.HttpsError("invalid-argument", "User ID is required");
        }

        // Check if deletion is scheduled
        const deletionSnap = await admin.database().ref(`scheduled_deletions/${targetUserId}`).once('value');
        if (!deletionSnap.exists()) {
            throw new functions.https.HttpsError("not-found", "No deletion scheduled for this user");
        }

        // Remove deletion markers
        await Promise.all([
            admin.database().ref(`users/${targetUserId}/deletion_scheduled`).remove(),
            admin.database().ref(`scheduled_deletions/${targetUserId}`).remove()
        ]);

        console.log(`[Admin] Cancelled deletion for user ${targetUserId}`);

        return {
            success: true,
            message: `Account deletion cancelled for user ${targetUserId}. User can continue using their account.`
        };
    } catch (error) {
        console.error("[Admin] Cancel deletion error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", "Failed to cancel deletion");
    }
});

/**
 * Admin: Mark deleted account as acknowledged/handled
 * Note: We cannot actually restore data once deleted, but we can update the status
 */
exports.adminUpdateDeletedAccountStatus = functions.https.onCall(async (data, context) => {
    try {
        requireAdmin(context);

        const targetUserId = data?.userId;
        const newStatus = data?.status || "acknowledged";
        const adminNotes = data?.notes || "";

        if (!targetUserId) {
            throw new functions.https.HttpsError("invalid-argument", "User ID is required");
        }

        // Check if account exists in deleted_accounts
        const deletedSnap = await admin.database().ref(`deleted_accounts/${targetUserId}`).once('value');
        if (!deletedSnap.exists()) {
            throw new functions.https.HttpsError("not-found", "Deleted account not found");
        }

        // Update status
        await admin.database().ref(`deleted_accounts/${targetUserId}`).update({
            status: newStatus,
            adminNotes: adminNotes,
            statusUpdatedAt: Date.now(),
            statusUpdatedBy: context.auth.uid
        });

        console.log(`[Admin] Updated deleted account ${targetUserId} status to ${newStatus}`);

        return {
            success: true,
            message: `Account ${targetUserId} status updated to ${newStatus}`,
            note: "Data cannot be restored once deleted. User must create a new account."
        };
    } catch (error) {
        console.error("[Admin] Update deleted account status error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", "Failed to update account status");
    }
});

/**
 * Admin: Get account recovery stats
 */
exports.adminGetRecoveryStats = functions.https.onCall(async (data, context) => {
    try {
        requireAdmin(context);

        const [scheduledSnap, deletedSnap] = await Promise.all([
            admin.database().ref("scheduled_deletions").once("value"),
            admin.database().ref("deleted_accounts").once("value")
        ]);

        const now = Date.now();
        const scheduledDeletions = [];
        const deletedAccounts = [];

        if (scheduledSnap.exists()) {
            scheduledSnap.forEach((child) => {
                scheduledDeletions.push(child.val());
            });
        }

        if (deletedSnap.exists()) {
            deletedSnap.forEach((child) => {
                deletedAccounts.push(child.val());
            });
        }

        // Calculate stats
        const urgentDeletions = scheduledDeletions.filter(d => {
            const daysRemaining = Math.ceil((d.scheduledDeletionAt - now) / (24 * 60 * 60 * 1000));
            return daysRemaining <= 7;
        }).length;

        const thirtyDaysAgo = now - (30 * 24 * 60 * 60 * 1000);
        const recentDeletions = deletedAccounts.filter(d => d.deletedAt > thirtyDaysAgo).length;

        // Group by reason
        const reasonCounts = {};
        [...scheduledDeletions, ...deletedAccounts].forEach(account => {
            const reason = account.reason || "Not specified";
            reasonCounts[reason] = (reasonCounts[reason] || 0) + 1;
        });

        return {
            success: true,
            stats: {
                totalScheduled: scheduledDeletions.length,
                urgentDeletions: urgentDeletions, // within 7 days
                totalDeleted: deletedAccounts.length,
                recentDeletions: recentDeletions, // last 30 days
                reasonBreakdown: reasonCounts
            }
        };
    } catch (error) {
        console.error("[Admin] Get recovery stats error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", "Failed to get recovery stats");
    }
});
