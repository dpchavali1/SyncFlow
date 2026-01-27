const admin = require('firebase-admin');
const path = require('path');
const serviceAccount = require(path.join(process.cwd(), 'functions/service-account-key.json'));

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: 'https://phone-integration-e29ee-default-rtdb.firebaseio.com'
});

const db = admin.database();
const userId = 'WV2rzfwI5pR0kWQRvSsJ7m2BRPI2';

async function cleanup() {
  const activeCallsRef = db.ref(`users/${userId}/active_calls`);
  const snapshot = await activeCallsRef.once('value');
  const calls = snapshot.val();

  if (!calls) {
    console.log('No active calls to clean up');
    process.exit(0);
    return;
  }

  console.log('Current active_calls:', Object.keys(calls));

  const now = Date.now();
  for (const [callId, data] of Object.entries(calls)) {
    const age = now - (data.timestamp || 0);
    const ageSeconds = Math.round(age / 1000);
    console.log(`Call ${callId}: age=${ageSeconds}s, state=${data.state}`);

    // Remove calls older than 60 seconds
    if (age > 60000) {
      console.log(`  -> Removing stale call ${callId}`);
      await activeCallsRef.child(callId).remove();
    }
  }

  console.log('Cleanup complete');
  process.exit(0);
}

cleanup().catch(e => { console.error(e); process.exit(1); });
