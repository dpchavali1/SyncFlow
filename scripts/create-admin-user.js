const admin = require('firebase-admin');

// Download from: Firebase Console → Project Settings → Service Accounts → Generate New Private Key
const serviceAccount = require('./serviceAccountKey.json');

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount)
});

// CREATE ADMIN USER
const adminEmail = 'your-admin-email@gmail.com'; // CHANGE THIS
const adminPassword = 'SecurePassword123!'; // CHANGE THIS

async function createAdminUser() {
  try {
    console.log(`🔧 Creating admin user: ${adminEmail}`);

    // Create user in Firebase Auth
    const userRecord = await admin.auth().createUser({
      email: adminEmail,
      password: adminPassword,
      emailVerified: true,
      disabled: false
    });

    console.log(`✅ User created with UID: ${userRecord.uid}`);

    // Set admin claims
    await admin.auth().setCustomUserClaims(userRecord.uid, { admin: true });
    console.log(`✅ Admin claims set for: ${adminEmail}`);

    // Create user profile in Realtime Database
    const db = admin.database();
    await db.ref(`users/${userRecord.uid}`).set({
      email: adminEmail,
      plan: 'lifetime',
      planExpiresAt: null,
      createdAt: Date.now(),
      isAdmin: true,
      adminUser: true
    });

    console.log(`✅ User profile created in database`);
    console.log(`\n📋 Admin User Details:`);
    console.log(`   Email: ${adminEmail}`);
    console.log(`   Password: ${adminPassword}`);
    console.log(`   UID: ${userRecord.uid}`);
    console.log(`\n💡 Next steps:`);
    console.log(`   1. Go to https://sfweb.app`);
    console.log(`   2. Sign up with: ${adminEmail} / ${adminPassword}`);
    console.log(`   3. You'll have admin access to /admin/cleanup`);

    process.exit(0);
  } catch (error) {
    console.error('❌ Error creating admin user:', error.message);
    process.exit(1);
  }
}

createAdminUser();
