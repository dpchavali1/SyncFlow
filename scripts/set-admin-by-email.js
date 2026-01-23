const admin = require('firebase-admin');

// Download from: Firebase Console → Project Settings → Service Accounts → Generate New Private Key
const serviceAccount = require('./serviceAccountKey.json');

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount)
});

// YOUR EMAIL
const email = process.argv[2];

if (!email) {
  console.log('❌ Usage: node set-admin-by-email.js <email>');
  console.log('   Example: node set-admin-by-email.js admin@gmail.com');
  process.exit(1);
}

async function setAdminByEmail() {
  try {
    console.log(`🔧 Setting admin claims for email: ${email}`);

    // Find user by email
    const userRecord = await admin.auth().getUserByEmail(email);
    const uid = userRecord.uid;

    console.log(`✅ Found user with UID: ${uid}`);

    // Set admin claims
    await admin.auth().setCustomUserClaims(uid, { admin: true });
    console.log(`✅ Admin claims set successfully!`);

    // Update user profile in database (if doesn't exist, create it)
    const db = admin.database();
    const userRef = db.ref(`users/${uid}`);

    // Check if user exists
    const snapshot = await userRef.once('value');
    if (!snapshot.exists()) {
      // Create user profile if it doesn't exist
      await userRef.set({
        email: email,
        isAdmin: true,
        adminUser: true,
        createdAt: Date.now()
      });
      console.log(`✅ Created admin user profile in database`);
    } else {
      // Just update admin status
      await userRef.update({
        isAdmin: true,
        adminUser: true
      });
      console.log(`✅ Updated user profile with admin status`);
    }

    console.log(`\n👤 User Details:`);
    console.log(`   Email: ${email}`);
    console.log(`   UID: ${uid}`);
    console.log(`\n💡 Next steps:`);
    console.log(`   1. Go to https://sfweb.app`);
    console.log(`   2. Sign in with: ${email}`);
    console.log(`   3. Go to /admin/cleanup`);
    console.log(`   4. Click the "Testing" tab`);
    console.log(`   5. Set user plans for testing`);

    console.log(`\n⚠️  Important: Always sign up with this email (${email}) to keep admin access`);

    process.exit(0);
  } catch (error) {
    if (error.code === 'auth/user-not-found') {
      console.error(`❌ Error: User with email "${email}" not found in Firebase Authentication`);
      console.error(`\n💡 Solutions:`);
      console.error(`   1. Go to Firebase Console → Authentication → Users`);
      console.error(`   2. Check if ${email} exists`);
      console.error(`   3. If not, sign up at https://sfweb.app with this email first`);
      console.error(`   4. Then run this script again`);
    } else {
      console.error('❌ Error:', error.message);
    }
    process.exit(1);
  }
}

setAdminByEmail();
