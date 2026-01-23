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

        console.log(`initiatePairing: Creating pairing session for ${deviceName} (${platform}), existingDeviceId: ${existingDeviceId}`);

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
        });

        console.log(`initiatePairing: Session created with token ${token.slice(0, 8)}...`);

        return { token, qrPayload, expiresAt };
    } catch (error) {
        console.error("initiatePairing error:", error);
        throw new functions.https.HttpsError("internal", error.message || "Failed to initiate pairing");
    }
});

/**
 * Completes a pairing request after Android user approval.
 * Creates the device entry and generates a custom auth token for Web/macOS.
 *
 * Called by: Android app after scanning QR and user confirms
 * Returns: { success: true } or throws error
 */
exports.completePairing = functions.https.onCall(async (data, context) => {
    try {
        requireAuth(context);

        const token = data && data.token ? String(data.token) : "";
        const approved = data && data.approved === true;

        if (!token) {
            throw new functions.https.HttpsError("invalid-argument", "Missing token");
        }

        console.log(`completePairing: Processing token ${token.slice(0, 8)}... approved=${approved}`);

        const pairingRef = admin.database().ref(`/pending_pairings/${token}`);
        const snapshot = await pairingRef.once("value");

        if (!snapshot.exists()) {
            throw new functions.https.HttpsError("not-found", "Pairing request not found or expired");
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
            console.log(`completePairing: Pairing rejected by ${pairedUid}`);
            return { success: true, status: "rejected" };
        }

        // User approved - create device and custom token
        // Use existing device ID if provided, otherwise generate new one
        const deviceId = pairingData.existingDeviceId || `dev_${crypto.randomUUID().replace(/-/g, "").slice(0, 12)}`;
        const deviceUid = `device_${deviceId}`;

        console.log(`completePairing: Using device ${deviceId} for user ${pairedUid} (existing: ${!!pairingData.existingDeviceId})`);

        // Create or update device entry
        await admin.database()
            .ref(`/users/${pairedUid}/devices/${deviceId}`)
            .set({
                name: pairingData.deviceName,
                type: pairingData.platform,
                platform: pairingData.platform,
                isPaired: true,
                pairedAt: admin.database.ServerValue.TIMESTAMP,
                lastSeen: admin.database.ServerValue.TIMESTAMP,
            });

        // Generate custom auth token for Web/macOS device
        const customToken = await admin.auth().createCustomToken(deviceUid, {
            pairedUid,
            deviceId,
            deviceType: pairingData.platform,
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

        console.log(`completePairing: Pairing approved, deviceId=${deviceId}`);

        return { success: true, status: "approved", deviceId };
    } catch (error) {
        console.error("completePairing error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", error.message || "Failed to complete pairing");
    }
});

/**
 * Cleanup old/unused device entries (runs daily)
 */
exports.cleanupOldDevices = functions.pubsub.schedule('every 24 hours').onRun(async (context) => {
    try {
        console.log('cleanupOldDevices: Starting device cleanup');

        const usersRef = admin.database().ref('users');
        const usersSnapshot = await usersRef.once('value');

        if (!usersSnapshot.exists()) {
            console.log('cleanupOldDevices: No users found');
            return;
        }

        const now = Date.now();
        const thirtyDaysAgo = now - (30 * 24 * 60 * 60 * 1000); // 30 days ago
        let totalCleaned = 0;

        const users = usersSnapshot.val();
        for (const [userId, userData] of Object.entries(users)) {
            if (!userData.devices) continue;

            const devices = userData.devices;
            const devicesToClean = [];

            // Group devices by platform
            const devicesByPlatform = new Map();

            for (const [deviceId, deviceData] of Object.entries(devices)) {
                const platform = deviceData.platform || 'unknown';
                const lastSeen = deviceData.lastSeen || 0;
                const registeredAt = deviceData.registeredAt || 0;

                if (!devicesByPlatform.has(platform)) {
                    devicesByPlatform.set(platform, []);
                }
                devicesByPlatform.get(platform).push({
                    deviceId,
                    lastSeen,
                    registeredAt,
                    isOnline: deviceData.isOnline || false
                });
            }

            // For each platform, keep only the most recent device, clean others
            for (const [platform, deviceList] of devicesByPlatform) {
                if (deviceList.length > 1) {
                    // Sort by lastSeen descending (most recent first)
                    deviceList.sort((a, b) => b.lastSeen - a.lastSeen);

                    // Keep the first (most recent), mark others for cleanup
                    for (let i = 1; i < deviceList.length; i++) {
                        const device = deviceList[i];
                        // Only clean if device hasn't been seen in 30 days
                        if (device.lastSeen < thirtyDaysAgo) {
                            devicesToClean.push(device.deviceId);
                        }
                    }
                }
            }

            // Clean up identified devices
            for (const deviceId of devicesToClean) {
                await usersRef.child(userId).child('devices').child(deviceId).remove();
                console.log(`cleanupOldDevices: Cleaned up old device ${deviceId} for user ${userId}`);
                totalCleaned++;
            }
        }

        console.log(`cleanupOldDevices: Completed, cleaned up ${totalCleaned} old devices`);
    } catch (error) {
        console.error('cleanupOldDevices error:', error);
        throw error;
    }
});

/**
 * Unregister a device (called when device unpairs itself)
 */
exports.unregisterDevice = functions.https.onCall(async (data, context) => {
    try {
        console.log('unregisterDevice: Called with data:', data);

        if (!context.auth) {
            console.log('unregisterDevice: No authentication');
            throw new functions.https.HttpsError("unauthenticated", "Authentication required");
        }

        const deviceId = data && data.deviceId ? String(data.deviceId) : "";
        console.log(`unregisterDevice: deviceId from data: "${deviceId}"`);

        if (!deviceId) {
            console.log('unregisterDevice: No deviceId provided');
            throw new functions.https.HttpsError("invalid-argument", "Device ID required");
        }

        const userId = context.auth.token?.pairedUid || context.auth.uid;
        console.log(`unregisterDevice: Processing for user ${userId}, device ${deviceId}`);

        // Check if device exists before removing
        const deviceRef = admin.database().ref(`/users/${userId}/devices/${deviceId}`);
        const deviceSnapshot = await deviceRef.once('value');

        if (!deviceSnapshot.exists()) {
            console.log(`unregisterDevice: Device ${deviceId} does not exist for user ${userId}`);
            return { success: true, deviceId: deviceId, message: "Device not found (already removed)" };
        }

        console.log(`unregisterDevice: Removing device ${deviceId} for user ${userId}`);

        // Remove device from Firebase
        await deviceRef.remove();

        // Also clean up any device-specific data
        const cleanupPaths = [
            'device_cache',
            'device_temp',
            'device_sessions'
        ];

        for (const path of cleanupPaths) {
            try {
                await admin.database().ref(`/users/${userId}/${path}/${deviceId}`).remove();
            } catch (e) {
                // Ignore errors for cleanup paths that may not exist
            }
        }

        console.log(`unregisterDevice: Successfully removed device ${deviceId} for user ${userId}`);
        return { success: true, deviceId: deviceId };

    } catch (error) {
        console.error('unregisterDevice error:', error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", error.message || "Failed to unregister device");
    }
});

/**
 * Manual cleanup of old devices (callable function)
 */
exports.cleanupOldDevicesManual = functions.https.onCall(async (data, context) => {
    requireAdmin(context);
    try {
        console.log('cleanupOldDevicesManual: Starting manual device cleanup');

        const usersRef = admin.database().ref('users');
        const usersSnapshot = await usersRef.once('value');

        if (!usersSnapshot.exists()) {
            return { success: true, cleaned: 0, message: 'No users found' };
        }

        const now = Date.now();
        const oneHourAgo = now - (1 * 60 * 60 * 1000); // 1 hour ago for testing
        let totalCleaned = 0;
        const cleanedDevices = [];
        const deviceInfo = [];

        const users = usersSnapshot.val();
        for (const [userId, userData] of Object.entries(users)) {
            if (!userData.devices) continue;

            console.log(`cleanupOldDevicesManual: Processing user ${userId}`);

            const devices = userData.devices;

            // Collect all devices info for debugging
            for (const [deviceId, deviceData] of Object.entries(devices)) {
                const platform = deviceData.platform || 'unknown';
                const lastSeen = deviceData.lastSeen || 0;
                const registeredAt = deviceData.registeredAt || 0;
                const isOnline = deviceData.isOnline || false;
                const lastSeenDate = new Date(lastSeen).toISOString();
                const registeredAtDate = new Date(registeredAt).toISOString();

                deviceInfo.push({
                    userId,
                    deviceId,
                    platform,
                    lastSeen,
                    lastSeenDate,
                    registeredAt,
                    registeredAtDate,
                    isOnline,
                    ageHours: Math.round((now - registeredAt) / (60 * 60 * 1000)),
                    lastSeenHoursAgo: Math.round((now - lastSeen) / (60 * 60 * 1000))
                });

                console.log(`cleanupOldDevicesManual: Device ${deviceId} (${platform}) - lastSeen: ${lastSeenDate}, registered: ${registeredAtDate}, online: ${isOnline}`);
            }

            // Group devices by platform
            const devicesByPlatform = new Map();

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

            // For each platform, keep only the most recent device, clean others
            for (const [platform, deviceList] of devicesByPlatform) {
                console.log(`cleanupOldDevicesManual: Platform ${platform} has ${deviceList.length} devices`);

                if (deviceList.length > 1) {
                    // Sort by lastSeen descending (most recent first)
                    deviceList.sort((a, b) => b.lastSeen - a.lastSeen);

                    // Keep the first (most recent), mark others for cleanup
                    for (let i = 1; i < deviceList.length; i++) {
                        const device = deviceList[i];
                        // For manual cleanup, use very short threshold (1 hour) to test
                        if (device.lastSeen < oneHourAgo) {
                            cleanedDevices.push({
                                deviceId: device.deviceId,
                                platform: platform,
                                lastSeen: device.lastSeen,
                                userId: userId
                            });
                            console.log(`cleanupOldDevicesManual: Marked for cleanup - device ${device.deviceId} last seen ${new Date(device.lastSeen).toISOString()}`);
                        } else {
                            console.log(`cleanupOldDevicesManual: Keeping device ${device.deviceId} - last seen ${new Date(device.lastSeen).toISOString()} (recent)`);
                        }
                    }
                } else {
                    console.log(`cleanupOldDevicesManual: Platform ${platform} has only 1 device, keeping it`);
                }
            }

            // Clean up identified devices
            for (const device of cleanedDevices) {
                await usersRef.child(device.userId).child('devices').child(device.deviceId).remove();
                console.log(`cleanupOldDevicesManual: Cleaned up old device ${device.deviceId} for user ${device.userId}`);
                totalCleaned++;
            }
        }

        console.log(`cleanupOldDevicesManual: Completed, cleaned up ${totalCleaned} old devices`);

        return {
            success: true,
            cleaned: totalCleaned,
            devices: cleanedDevices,
            deviceInfo: deviceInfo, // Include all device info for debugging
            message: `Cleaned up ${totalCleaned} old device entries. Found ${deviceInfo.length} total devices.`,
            debug: {
                totalDevicesFound: deviceInfo.length,
                thresholdHours: 1,
                cleanedCount: totalCleaned
            }
        };
    } catch (error) {
        console.error('cleanupOldDevicesManual error:', error);
        return {
            success: false,
            cleaned: 0,
            error: error.message,
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
                    console.log(`Cleaning up pairing: ${pairingSnapshot.key}, status=${data.status}`);
                    deletePromises.push(pairingSnapshot.ref.remove());
                }
            });

            if (deletePromises.length > 0) {
                await Promise.all(deletePromises);
                console.log(`Cleaned up ${deletePromises.length} old pairing requests`);
            }

            return null;
        } catch (error) {
            console.error("Error cleaning up pairings:", error);
            return null;
        }
    });

exports.createPairingToken = functions.https.onCall(async (data, context) => {
    try {
        if (!context.auth) {
            console.error("createPairingToken: No authentication context");
            throw new functions.https.HttpsError("unauthenticated", "Authentication required");
        }

        const deviceType = data && data.deviceType ? String(data.deviceType) : "desktop";
        const uid = context.auth.uid;

        console.log(`createPairingToken: Creating token for uid=${uid}, deviceType=${deviceType}`);

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

        console.log(`createPairingToken: Token created successfully: ${token}`);

        return { token, expiresAt: payload.expiresAt };
    } catch (error) {
        console.error("createPairingToken error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", error.message || "Failed to create pairing token");
    }
});

exports.redeemPairingToken = functions.https.onCall(async (data) => {
    try {
        const token = data && data.token ? String(data.token) : "";
        const deviceName = data && data.deviceName ? String(data.deviceName) : "Desktop";
        const deviceType = data && data.deviceType ? String(data.deviceType) : "desktop";

        console.log(`redeemPairingToken: Attempting to redeem token for deviceType=${deviceType}, ` +
            `deviceName=${deviceName}`);

        if (!token) {
            throw new functions.https.HttpsError("invalid-argument", "Missing token");
        }

        const tokenRef = admin.database().ref(`/pairing_tokens/${token}`);
        const snapshot = await tokenRef.once("value");
        if (!snapshot.exists()) {
            console.log(`redeemPairingToken: Token not found: ${token}`);
            throw new functions.https.HttpsError("not-found", "Invalid or expired token");
        }

        const tokenData = snapshot.val();
        console.log(`redeemPairingToken: Token data:`, JSON.stringify(tokenData));

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

        console.log(`redeemPairingToken: Creating device ${deviceId} for user ${pairedUid}`);

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

        console.log(`redeemPairingToken: Successfully redeemed token, deviceId=${deviceId}`);

        return { customToken, pairedUid, deviceId };
    } catch (error) {
        console.error("redeemPairingToken error:", error);
        if (error instanceof functions.https.HttpsError) {
            throw error;
        }
        throw new functions.https.HttpsError("internal", error.message || "Failed to redeem pairing token");
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

        console.log(`New call notification for user ${userId}, call ${callId}:`, data);

        // Validate data
        if (!data || !data.type || data.type !== "incoming_call") {
            console.log("Invalid notification data, skipping");
            return null;
        }

        try {
            // Get the user's FCM token
            const tokenSnapshot = await admin.database()
                .ref(`/fcm_tokens/${userId}`)
                .once("value");
            const fcmToken = tokenSnapshot.val();

            if (!fcmToken) {
                console.log(`No FCM token found for user ${userId}`);
                // Clean up the notification from the queue
                await snapshot.ref.remove();
                return null;
            }

            // Construct the FCM message
            // Using data-only message to ensure delivery even when app is killed
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
                    ttl: 60000, // 60 seconds - calls shouldn't ring forever
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

            console.log("Sending FCM message:", JSON.stringify(message));

            // Send the FCM message
            const response = await admin.messaging().send(message);
            console.log(`Successfully sent FCM message: ${response}`);

            // Clean up the notification from the queue after successful send
            await snapshot.ref.remove();
            console.log("Cleaned up notification from queue");

            return response;
        } catch (error) {
            console.error("Error sending FCM notification:", error);

            // If the token is invalid, remove it
            if (error.code === "messaging/invalid-registration-token" ||
                error.code === "messaging/registration-token-not-registered") {
                console.log(`Removing invalid FCM token for user ${userId}`);
                await admin.database().ref(`/fcm_tokens/${userId}`).remove();
            }

            // Clean up the notification from the queue even on error
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

        // Also clean expired pairings
        let expiredPairings = 0;
        const pairingsRef = admin.database().ref("/pending_pairings");
        const pairingsSnap = await pairingsRef.once("value");
        if (pairingsSnap.exists()) {
            const deletePromises = [];
            pairingsSnap.forEach((child) => {
                const pData = child.val();
                if (pData.expiresAt && now > pData.expiresAt) {
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
