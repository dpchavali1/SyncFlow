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

// Enable auth state persistence (default is 'local' which persists across browser sessions)
// This ensures users stay authenticated even after closing the browser
if (typeof window !== 'undefined') {
  // Auth persistence is automatic in Firebase v9+
  console.log('🔥 Firebase initialized with persistent auth')
}

const database = getDatabase(app)
const storage = getStorage(app)

// Authentication
export const signInAnon = async () => {
  const startTime = performance.now()
  try {
    console.log('🔐 Starting anonymous sign-in...')
    const result = await signInAnonymously(auth)
    const endTime = performance.now()
    console.log(`✅ Sign-in completed in ${(endTime - startTime).toFixed(0)}ms`)
    console.log('🔐 User ID:', result.user?.uid)
    return result.user
  } catch (error) {
    const endTime = performance.now()
    console.error(`❌ Sign-in failed after ${(endTime - startTime).toFixed(0)}ms:`, error)
    throw error
  }
}

// Get current user ID
export const getCurrentUserId = () => {
  return auth.currentUser?.uid || null
}

// Wait for Firebase auth to be ready and return current user
export const waitForAuth = (): Promise<string | null> => {
  return new Promise((resolve) => {
    const unsubscribe = auth.onAuthStateChanged((user) => {
      unsubscribe()
      resolve(user?.uid || null)
    })
  })
}

// Database paths
const USERS_PATH = 'users'
const MESSAGES_PATH = 'messages'
const DEVICES_PATH = 'devices'
const PENDING_PAIRINGS_PATH = 'pending_pairings'
const OUTGOING_MESSAGES_PATH = 'outgoing_messages'

// Listen for messages
export const listenToMessages = (userId: string, callback: (messages: any[]) => void) => {
  const startTime = performance.now()
  const messagesRef = ref(database, `${USERS_PATH}/${userId}/${MESSAGES_PATH}`)
  console.log('🔥 Firebase: Listening to path:', `${USERS_PATH}/${userId}/${MESSAGES_PATH}`)
  console.log('🔥 Firebase: Current auth user:', auth.currentUser?.uid || 'NOT AUTHENTICATED')

  return onValue(messagesRef, (snapshot) => {
    const snapshotTime = performance.now()
    console.log(`⏱️ Firebase: Snapshot received in ${(snapshotTime - startTime).toFixed(0)}ms`)

    const data = snapshot.val()
    if (data) {
      const parseStart = performance.now()
      const messages = Object.entries(data).map(([key, value]: [string, any]) => ({
        id: key,
        ...value,
      }))
      const parseEnd = performance.now()
      console.log(`✅ Firebase: Parsed ${messages.length} messages in ${(parseEnd - parseStart).toFixed(0)}ms`)
      console.log(`⏱️ Total time: ${(parseEnd - startTime).toFixed(0)}ms`)
      callback(messages)
    } else {
      console.log('⚠️ Firebase: No data found at this path')
      callback([])
    }
  }, (error: any) => {
    console.error('❌ Firebase: Error listening to messages:', error)
    console.error('❌ Firebase: Error code:', error?.code)
    console.error('❌ Firebase: Error message:', error?.message)
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

// Generate pairing token (WEB SIDE - shows QR code)
export const generatePairingToken = async () => {
  const startTime = performance.now()

  const timestamp = Date.now()
  const randomToken = Math.random().toString(36).substring(2, 15)
  const token = `web_${timestamp}_${randomToken}`
  console.log('🔑 Generated token:', token)

  // Store token in Firebase pending_pairings
  const pairingRef = ref(database, `${PENDING_PAIRINGS_PATH}/${token}`)
  const writeStart = performance.now()
  await set(pairingRef, {
    createdAt: serverTimestamp(),
    expiresAt: timestamp + 5 * 60 * 1000, // 5 minutes
    type: 'web',
  })
  const writeEnd = performance.now()
  console.log(`⏱️ Firebase write took ${(writeEnd - writeStart).toFixed(0)}ms`)
  console.log(`⏱️ Total token generation: ${(writeEnd - startTime).toFixed(0)}ms`)

  return token
}

// Listen for pairing completion (WEB SIDE - waits for phone to scan and pair)
export const listenForPairingCompletion = (
  token: string,
  callback: (userId: string | null) => void
) => {
  const pairingRef = ref(database, `${PENDING_PAIRINGS_PATH}/${token}`)

  const unsubscribe = onValue(pairingRef, (snapshot) => {
    const data = snapshot.val()
    if (data && data.userId) {
      // Phone has completed the pairing
      callback(data.userId)
      // Clean up the pending pairing
      remove(pairingRef)
    }
  })

  return unsubscribe
}

// Pair device with token (PHONE SIDE - scans QR code)
export const pairDeviceWithToken = async (token: string, userId: string, deviceName: string) => {
  try {
    // Update the pending pairing with userId
    const pairingRef = ref(database, `${PENDING_PAIRINGS_PATH}/${token}`)

    // Check if token exists
    const snapshot = await new Promise<any>((resolve) => {
      onValue(pairingRef, resolve, { onlyOnce: true })
    })

    if (!snapshot.exists()) {
      throw new Error('Invalid or expired pairing token')
    }

    const tokenData = snapshot.val()
    const tokenAge = Date.now() - (tokenData.expiresAt - 5 * 60 * 1000)
    if (tokenAge > 5 * 60 * 1000) {
      throw new Error('Pairing token has expired')
    }

    // Update the pending pairing with userId so web app knows pairing is complete
    await set(pairingRef, {
      ...tokenData,
      userId,
      completedAt: serverTimestamp(),
    })

    // Add device to user's devices
    const deviceId = Date.now().toString()
    const deviceRef = ref(database, `${USERS_PATH}/${userId}/${DEVICES_PATH}/${deviceId}`)

    await set(deviceRef, {
      name: deviceName || 'Desktop',
      type: 'web',
      pairedAt: serverTimestamp(),
    })

    return { userId, deviceId }
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
