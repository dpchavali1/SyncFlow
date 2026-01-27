/**
 * SyncFlow Firebase Cloud Functions
 *
 * Handles sending FCM push notifications for incoming calls
 * when the app is in the background or closed.
 */

const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();
const crypto = require("crypto");

const PAIRING_TTL_MS = 5 * 60 * 1000;

const requireAuth = (context) => {
    if (!context.auth) {
        throw new functions.https.HttpsError("unauthenticated", "Authentication required");
    }
    return context.auth.uid;
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
    } else if (plan === "lifetime") {
        updates["active/planExpiresAt"] = null;
        updates["active/freeTrialExpiresAt"] = null;
    } else {
        updates["active/planExpiresAt"] = expiresAt;
        updates["active/freeTrialExpiresAt"] = null;
    }

    // Track premium status
    const isPremium = ["monthly", "yearly", "lifetime"].includes(plan);
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

    // Clean old file transfers (older than 7 days)
    const transfersRef = admin.database().ref(`/users/${userId}/file_transfers`);
    const transfersSnap = await transfersRef.once("value");
    if (transfersSnap.exists()) {
        const deletePromises = [];
        transfersSnap.forEach((child) => {
            const data = child.val();
            const timestamp = data.timestamp || data.startedAt || 0;
            if (now - timestamp > 7 * ONE_DAY) {
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

        const validPlans = ["free", "monthly", "yearly", "lifetime"];
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
        } else if (plan === "lifetime") {
            updates["planExpiresAt"] = null;
        } else {
            // Monthly/yearly expire in respective periods
            const expiryMs = plan === "monthly" ? 30 * 24 * 60 * 60 * 1000 : 365 * 24 * 60 * 60 * 1000;
            updates["planExpiresAt"] = now + expiryMs;
        }

        // Track premium status
        if (["monthly", "yearly", "lifetime"].includes(plan)) {
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
        case "lifetime":
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
