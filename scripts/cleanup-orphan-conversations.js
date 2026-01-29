#!/usr/bin/env node
/**
 * Cleanup script to remove messages with incorrect address (user's own number)
 * Run with: node scripts/cleanup-orphan-conversations.js <userId> <yourPhoneNumber>
 * Example: node scripts/cleanup-orphan-conversations.js abc123 2488542991
 */

const admin = require('firebase-admin');
const path = require('path');

// Initialize Firebase Admin with service account
const serviceAccountPath = path.join(__dirname, '../functions/serviceAccountKey.json');

try {
    const serviceAccount = require(serviceAccountPath);
    admin.initializeApp({
        credential: admin.credential.cert(serviceAccount),
        databaseURL: 'https://phoneintegration-571f7-default-rtdb.firebaseio.com'
    });
} catch (e) {
    console.error('Error loading service account. Make sure serviceAccountKey.json exists in functions/');
    console.error('Download it from Firebase Console > Project Settings > Service Accounts');
    process.exit(1);
}

const db = admin.database();

async function cleanupOrphanMessages(userId, userPhoneNumber) {
    console.log(`\n🔍 Scanning for messages with address: ${userPhoneNumber}`);
    console.log(`   User ID: ${userId}\n`);

    // Normalize phone number (remove non-digits)
    const normalizedNumber = userPhoneNumber.replace(/\D/g, '');

    const messagesRef = db.ref(`users/${userId}/messages`);

    try {
        const snapshot = await messagesRef.once('value');
        const messages = snapshot.val();

        if (!messages) {
            console.log('No messages found for this user.');
            return;
        }

        const toDelete = [];
        const toKeep = [];

        Object.entries(messages).forEach(([msgId, msg]) => {
            const msgAddress = (msg.address || '').replace(/\D/g, '');

            // Check if this message's address matches the user's phone number
            if (msgAddress === normalizedNumber ||
                msgAddress.endsWith(normalizedNumber) ||
                normalizedNumber.endsWith(msgAddress)) {
                toDelete.push({
                    id: msgId,
                    body: (msg.body || '').substring(0, 50),
                    type: msg.type === 1 ? 'received' : 'sent',
                    date: new Date(msg.date).toLocaleString()
                });
            } else {
                toKeep.push(msgId);
            }
        });

        console.log(`📊 Results:`);
        console.log(`   Total messages: ${Object.keys(messages).length}`);
        console.log(`   Messages to delete (wrong address): ${toDelete.length}`);
        console.log(`   Messages to keep: ${toKeep.length}\n`);

        if (toDelete.length === 0) {
            console.log('✅ No orphan messages found. Database is clean!');
            return;
        }

        console.log('📝 Messages to delete:');
        toDelete.slice(0, 10).forEach(msg => {
            console.log(`   [${msg.type}] ${msg.date}: "${msg.body}..."`);
        });
        if (toDelete.length > 10) {
            console.log(`   ... and ${toDelete.length - 10} more`);
        }

        // Ask for confirmation
        console.log('\n⚠️  Do you want to delete these messages? (y/n)');

        const readline = require('readline');
        const rl = readline.createInterface({
            input: process.stdin,
            output: process.stdout
        });

        rl.question('> ', async (answer) => {
            if (answer.toLowerCase() === 'y' || answer.toLowerCase() === 'yes') {
                console.log('\n🗑️  Deleting messages...');

                const updates = {};
                toDelete.forEach(msg => {
                    updates[msg.id] = null;
                });

                await messagesRef.update(updates);
                console.log(`✅ Deleted ${toDelete.length} orphan messages!`);
            } else {
                console.log('❌ Aborted. No changes made.');
            }

            rl.close();
            process.exit(0);
        });

    } catch (error) {
        console.error('Error:', error.message);
        process.exit(1);
    }
}

// Parse command line arguments
const args = process.argv.slice(2);
if (args.length < 2) {
    console.log('Usage: node cleanup-orphan-conversations.js <userId> <yourPhoneNumber>');
    console.log('Example: node cleanup-orphan-conversations.js abc123xyz 2488542991');
    console.log('\nTo find your userId, check the Firebase console or look in the Mac app logs.');
    process.exit(1);
}

const [userId, phoneNumber] = args;
cleanupOrphanMessages(userId, phoneNumber);
