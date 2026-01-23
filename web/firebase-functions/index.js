// Firebase Cloud Function for pairing token validation
// This runs on Firebase servers to securely validate pairing requests

const functions = require('firebase-functions');
const admin = require('firebase-admin');

admin.initializeApp();

// Cloud Function to complete device pairing
exports.completePairing = functions.https.onCall(async (data, context) => {
  try {
    // Validate input
    const { token, approved } = data;

    if (!token || typeof approved !== 'boolean') {
      throw new functions.https.HttpsError('invalid-argument', 'Missing token or approval status');
    }

    // Get token data
    const tokenRef = admin.database().ref(`pairing_tokens/${token}`);
    const tokenSnapshot = await tokenRef.once('value');

    if (!tokenSnapshot.exists()) {
      throw new functions.https.HttpsError('not-found', 'Invalid or expired pairing token');
    }

    const tokenData = tokenSnapshot.val();
    const currentTime = Date.now();

    // Validate token expiry
    if (currentTime > tokenData.expiresAt) {
      // Clean up expired token
      await tokenRef.remove();
      throw new functions.https.HttpsError('deadline-exceeded', 'Pairing token has expired');
    }

    // Check token status
    if (tokenData.status !== 'pending') {
      throw new functions.https.HttpsError('failed-precondition', 'Token has already been used');
    }

    // Update token status
    await tokenRef.update({
      status: approved ? 'approved' : 'rejected',
      completedAt: currentTime,
      approvedBy: context.auth?.uid || 'anonymous'
    });

    // Return result
    return {
      success: true,
      status: approved ? 'approved' : 'rejected',
      deviceId: tokenData.deviceId,
      userId: tokenData.userId,
      deviceName: tokenData.deviceName
    };

  } catch (error) {
    console.error('Error completing pairing:', error);
    throw new functions.https.HttpsError('internal', 'Failed to complete pairing');
  }
});

// Cloud Function to validate unified user access
exports.validateUnifiedAccess = functions.https.onCall(async (data, context) => {
  try {
    const { deviceId } = data;
    const userId = context.auth?.uid;

    if (!userId) {
      throw new functions.https.HttpsError('unauthenticated', 'User must be authenticated');
    }

    if (!deviceId) {
      throw new functions.https.HttpsError('invalid-argument', 'Missing device ID');
    }

    // Check if device is registered under this user
    const deviceRef = admin.database().ref(`users/${userId}/devices/${deviceId}`);
    const deviceSnapshot = await deviceRef.once('value');

    if (!deviceSnapshot.exists()) {
      throw new functions.https.HttpsError('permission-denied', 'Device not registered for this user');
    }

    const deviceData = deviceSnapshot.val();

    return {
      success: true,
      userId: userId,
      deviceId: deviceId,
      deviceName: deviceData.name,
      isValid: true
    };

  } catch (error) {
    console.error('Error validating unified access:', error);
    throw new functions.https.HttpsError('internal', 'Failed to validate access');
  }
});

// Cloud Function to get user device list (for admin purposes)
exports.getUserDevices = functions.https.onCall(async (data, context) => {
  try {
    const userId = context.auth?.uid;

    if (!userId) {
      throw new functions.https.HttpsError('unauthenticated', 'User must be authenticated');
    }

    // Get user's devices
    const devicesRef = admin.database().ref(`users/${userId}/devices`);
    const devicesSnapshot = await devicesRef.once('value');

    const devices = [];
    devicesSnapshot.forEach((childSnapshot) => {
      devices.push({
        id: childSnapshot.key,
        ...childSnapshot.val()
      });
    });

    return {
      success: true,
      userId: userId,
      devices: devices,
      deviceCount: devices.length
    };

  } catch (error) {
    console.error('Error getting user devices:', error);
    throw new functions.https.HttpsError('internal', 'Failed to get user devices');
  }
});

// Scheduled function to clean up expired pairing tokens
exports.cleanupExpiredTokens = functions.pubsub
  .schedule('every 6 hours')
  .onRun(async (context) => {
    try {
      const currentTime = Date.now();
      const tokensRef = admin.database().ref('pairing_tokens');
      const tokensSnapshot = await tokensRef.once('value');

      let cleanedCount = 0;
      const promises = [];

      tokensSnapshot.forEach((childSnapshot) => {
        const tokenData = childSnapshot.val();
        if (currentTime > tokenData.expiresAt) {
          promises.push(childSnapshot.ref.remove());
          cleanedCount++;
        }
      });

      await Promise.all(promises);

      console.log(`Cleaned up ${cleanedCount} expired pairing tokens`);
      return null;

    } catch (error) {
      console.error('Error cleaning up expired tokens:', error);
      return null;
    }
  });</content>
<parameter name="filePath">firebase-functions/index.js