// Script to add test SIM data to Firebase for testing macOS SIM selector
// Run this in Firebase Console > Database > Rules tab's test environment

// Or use Firebase CLI:
// firebase database:set /users/YOUR_USER_ID/sims test_sims.json

const testSims = [
  {
    subscriptionId: 1,
    slotIndex: 0,
    displayName: "Personal",
    carrierName: "T-Mobile",
    phoneNumber: "+1234567890",
    iccId: "89011234567890123456",
    isEmbedded: false,
    isActive: true
  },
  {
    subscriptionId: 2,
    slotIndex: 1,
    displayName: "Work",
    carrierName: "Verizon",
    phoneNumber: "+0987654321",
    iccId: "89019876543210987654",
    isEmbedded: true,  // This is an eSIM
    isActive: true
  }
];

console.log("Test SIM data:");
console.log(JSON.stringify(testSims, null, 2));

// Instructions:
// 1. Go to Firebase Console: https://console.firebase.google.com/project/syncflow-6980e/database
// 2. Navigate to: users → <your_user_id> → sims
// 3. Click the "+" button
// 4. Paste this JSON data
// 5. Refresh your macOS app to see the SIM selector
