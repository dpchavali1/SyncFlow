import { initializeApp, getApps } from 'firebase/app'
import { getAuth, signInAnonymously } from 'firebase/auth'
import { getDatabase, ref, onValue, set, push, remove, serverTimestamp } from 'firebase/database'
import { getStorage } from 'firebase/storage'

// Firebase configuration
// TODO: Replace with your Firebase project configuration from Firebase Console
const firebaseConfig = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
  databaseURL: process.env.NEXT_PUBLIC_FIREBASE_DATABASE_URL,
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
  storageBucket: process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: process.env.NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
}

// Initialize Firebase
const app = getApps().length === 0 ? initializeApp(firebaseConfig) : getApps()[0]
const auth = getAuth(app)
const database = getDatabase(app)
const storage = getStorage(app)

// Authentication
export const signInAnon = async () => {
  try {
    const result = await signInAnonymously(auth)
    return result.user
  } catch (error) {
    console.error('Error signing in:', error)
    throw error
  }
}

// Get current user ID
export const getCurrentUserId = () => {
  return auth.currentUser?.uid || null
}

// Database paths
const USERS_PATH = 'users'
const MESSAGES_PATH = 'messages'
const DEVICES_PATH = 'devices'
const PENDING_PAIRINGS_PATH = 'pending_pairings'
const OUTGOING_MESSAGES_PATH = 'outgoing_messages'

// Listen for messages
export const listenToMessages = (userId: string, callback: (messages: any[]) => void) => {
  const messagesRef = ref(database, `${USERS_PATH}/${userId}/${MESSAGES_PATH}`)

  return onValue(messagesRef, (snapshot) => {
    const data = snapshot.val()
    if (data) {
      const messages = Object.entries(data).map(([key, value]: [string, any]) => ({
        id: key,
        ...value,
      }))
      callback(messages)
    } else {
      callback([])
    }
  })
}

// Send SMS from web
export const sendSmsFromWeb = async (userId: string, address: string, body: string) => {
  const outgoingRef = ref(database, `${USERS_PATH}/${userId}/${OUTGOING_MESSAGES_PATH}`)
  const newMessageRef = push(outgoingRef)

  await set(newMessageRef, {
    address,
    body,
    timestamp: serverTimestamp(),
    status: 'pending',
  })

  return newMessageRef.key
}

// Pair device with token
export const pairDeviceWithToken = async (token: string, deviceName: string) => {
  try {
    const [userId, timestamp, randomToken] = token.split(':')

    // Check if token is still valid (5 minutes)
    const tokenAge = Date.now() - parseInt(timestamp)
    if (tokenAge > 5 * 60 * 1000) {
      throw new Error('Pairing token has expired')
    }

    // Check if token exists in pending_pairings
    const pairingRef = ref(database, `${PENDING_PAIRINGS_PATH}/${token}`)

    return new Promise((resolve, reject) => {
      onValue(
        pairingRef,
        (snapshot) => {
          if (snapshot.exists()) {
            // Token is valid, pair device
            const deviceId = Date.now().toString()
            const deviceRef = ref(database, `${USERS_PATH}/${userId}/${DEVICES_PATH}/${deviceId}`)

            set(deviceRef, {
              name: deviceName,
              type: 'web',
              pairedAt: serverTimestamp(),
            })
              .then(() => {
                // Remove token from pending
                remove(pairingRef)
                resolve({ userId, deviceId })
              })
              .catch(reject)
          } else {
            reject(new Error('Invalid or expired pairing token'))
          }
        },
        { onlyOnce: true }
      )
    })
  } catch (error) {
    console.error('Error pairing device:', error)
    throw error
  }
}

// Listen for paired devices
export const listenToPairedDevices = (userId: string, callback: (devices: any[]) => void) => {
  const devicesRef = ref(database, `${USERS_PATH}/${userId}/${DEVICES_PATH}`)

  return onValue(devicesRef, (snapshot) => {
    const data = snapshot.val()
    if (data) {
      const devices = Object.entries(data).map(([key, value]: [string, any]) => ({
        id: key,
        ...value,
      }))
      callback(devices)
    } else {
      callback([])
    }
  })
}

export { auth, database, storage }
