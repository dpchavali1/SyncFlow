const admin = require('firebase-admin');

// Download from: Firebase Console → Project Settings → Service Accounts → Generate New Private Key
const serviceAccount = require('./serviceAccountKey.json');

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount)
});

// YOUR UID (get from Firebase Console → Authentication → Users → click on your user)
const uid = process.argv[2];
const email = process.argv[3];

if (!uid) {
  console.log('❌ Usage: node set-admin-user.js <uid> <email>');
  console.log('   Example: node set-admin-user.js 8iHnek4WaEcE3qp4PhNtpKs1P0l2 user@gmail.com');
  process.exit(1);
}

async function setAdmin() {
  try {
    console.log(`🔧 Setting admin claims for UID: ${uid}`);

    await admin.auth().setCustomUserClaims(uid, { admin: true });

    console.log(`✅ Admin claims set successfully!`);
    console.log(`\n👤 User Details:`);
    console.log(`   UID: ${uid}`);
    if (email) console.log(`   Email: ${email}`);
    console.log(`\n💡 You can now:`);
    console.log(`   1. Go to https://sfweb.app/admin/cleanup`);
    console.log(`   2. Click the "Testing" tab`);
    console.log(`   3. Set user plans for testing`);

    process.exit(0);
  } catch (error) {
    console.error('❌ Error:', error.message);
    process.exit(1);
  }
}

setAdmin();
