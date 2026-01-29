import { initializeApp, getApps } from 'firebase/app'
import { getAuth, signInAnonymously, signInWithCustomToken } from 'firebase/auth'
import {
  getDatabase,
  ref,
  onValue,
  set,
  push,
  remove,
  serverTimestamp,
  get,
  update,
  increment,
} from 'firebase/database'
import { getFunctions, httpsCallable } from 'firebase/functions'
import { getStorage } from 'firebase/storage'
import { decryptDataKey, decryptMessageBody, getOrCreateDeviceId, getPublicKeyX963Base64 } from './e2ee'
import { PhoneNumberNormalizer } from './phoneNumberNormalizer'

// Normalize phone number for comparison (same logic as ConversationList)
function normalizePhoneNumber(address: string): string {
  // Skip non-phone addresses (email, short codes, etc.)
  if (address.includes('@') || address.length < 6) {
    return address.toLowerCase()
  }

  // Remove all non-digit characters
  const digitsOnly = address.replace(/[^0-9]/g, '')

  // For comparison, use last 10 digits (handles country code differences)
  if (digitsOnly.length >= 10) {
    return digitsOnly.slice(-10)
  }
  return digitsOnly
}

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

const database = getDatabase(app)
const storage = getStorage(app)
const functions = getFunctions(app)

// Authentication
export const signInAnon = async () => {
  try {
    const result = await signInAnonymously(auth)
    return result.user
  } catch (error) {
    console.error('Sign-in failed:', error)
    throw error
  }
}

export const adminLogin = async (username: string, password: string) => {
  const call = httpsCallable(functions, 'adminLogin')
  const result = await call({ username, password })
  const data = result.data as { customToken: string; uid: string }
  await signInWithCustomToken(auth, data.customToken)
  return data
}

export const redeemPairingToken = async (token: string, deviceName: string) => {
  // Get existing device ID and user ID to enable device/user reuse - COST OPTIMIZATION
  const existingDeviceId = typeof window !== 'undefined' ? localStorage.getItem('syncflow_device_id') : null
  const existingUserId = auth.currentUser?.uid || null

  const call = httpsCallable(functions, 'redeemPairingToken')
  const result = await call({
    token,
    deviceName,
    deviceType: 'web',
    existingDeviceId,       // Allow reusing existing device ID
    existingUserId,         // Reconnect to same user if available
    reuseExistingUser: true // Enable user reuse
  })
  const data = result.data as { customToken: string; pairedUid: string; deviceId: string }
  await signInWithCustomToken(auth, data.customToken)
  return data
}

// Database path for pending pairings (needed early for pairing functions)
const PENDING_PAIRINGS_PATH = 'pending_pairings'

// New pairing flow: Web generates QR code, Android scans
export interface PairingSession {
  token: string
  qrPayload: string
  expiresAt: number
  syncGroupId?: string
}

export interface PairingStatus {
  status: 'pending' | 'approved' | 'rejected' | 'expired'
  pairedUid?: string
  deviceId?: string
  customToken?: string
}

// Initiate pairing from Web (generates QR code data)
// Uses V2 pairing with persistent device IDs when available
export const initiatePairing = async (deviceName?: string, syncGroupId?: string): Promise<PairingSession> => {
  // Sign in anonymously first if not already authenticated
  // This is needed to listen to Firebase Database for pairing status updates
  if (!auth.currentUser) {
    await signInAnonymously(auth)
  }

  // Get persistent device ID from IndexedDB/fingerprint
  let deviceId: string | null = null
  try {
    // Dynamic import to avoid SSR issues
    const { getDeviceId } = await import('./deviceId')
    deviceId = await getDeviceId()
    console.log('[Pairing] Using persistent device ID:', deviceId)
  } catch (error) {
    console.warn('[Pairing] Failed to get persistent device ID, using fallback')
    deviceId = typeof window !== 'undefined' ? localStorage.getItem('syncflow_device_id') : null
  }

  // Try V2 pairing first (supports device limits, persistent IDs)
  try {
    const callV2 = httpsCallable(functions, 'initiatePairingV2')
    const result = await callV2({
      deviceId: deviceId || `web_${Date.now().toString(16)}`,
      deviceName: deviceName || getWebDeviceName(),
      deviceType: 'web',
      appVersion: '2.0.0',
    })
    const data = result.data as { success: boolean; token: string; qrPayload: string; expiresAt: number }
    if (data.success) {
      console.log('[Pairing] V2 session created:', data.token?.slice(0, 8) + '...')
      return {
        token: data.token,
        qrPayload: data.qrPayload,
        expiresAt: data.expiresAt,
      }
    }
    throw new Error('V2 pairing returned unsuccessful')
  } catch (v2Error) {
    console.warn('[Pairing] V2 failed, falling back to V1:', v2Error)
  }

  // Fall back to V1 pairing
  const call = httpsCallable(functions, 'initiatePairing')
  const result = await call({
    deviceName: deviceName || getWebDeviceName(),
    platform: 'web',
    appVersion: '1.0.0',
    existingDeviceId: deviceId, // Pass existing device ID to allow reuse
    syncGroupId: syncGroupId || null,
  })
  return result.data as PairingSession
}

// Listen for pairing approval (after Android user scans and approves)
// Supports both V1 (pending_pairings) and V2 (pairing_requests) paths
export const listenForPairingApproval = (
  token: string,
  callback: (status: PairingStatus) => void
): (() => void) => {
  // V2 path (pairing_requests) - new system
  const v2Ref = ref(database, `pairing_requests/${token}`)
  // V1 path (pending_pairings) - legacy system
  const v1Ref = ref(database, `${PENDING_PAIRINGS_PATH}/${token}`)

  console.log('[Pairing] Starting dual listener for token:', token.slice(0, 8) + '...')

  let hasResolved = false

  const handleSnapshot = async (snapshot: any, version: string) => {
    if (hasResolved) return

    console.log(`[Pairing] ${version} update, exists:`, snapshot.exists())

    if (!snapshot.exists()) {
      // Don't mark as expired yet - might be on the other path
      return
    }

    const data = snapshot.val()
    console.log(`[Pairing] ${version} status:`, data.status)
    const now = Date.now()

    if (data.expiresAt && now > data.expiresAt) {
      console.log(`[Pairing] ${version} token expired`)
      hasResolved = true
      callback({ status: 'expired' })
      return
    }

    if (data.status === 'approved' && data.customToken) {
      console.log(`[Pairing] ${version} Approved! Signing in with custom token...`)
      hasResolved = true
      // Sign in with the custom token provided by Android approval
      try {
        await signInWithCustomToken(auth, data.customToken)
        console.log('[Pairing] Sign-in successful, pairedUid:', data.pairedUid)
        callback({
          status: 'approved',
          pairedUid: data.pairedUid,
          deviceId: data.deviceId,
          customToken: data.customToken,
        })
      } catch (error) {
        console.error('[Pairing] Failed to sign in with custom token:', error)
        callback({ status: 'expired' })
      }
      return
    }

    if (data.status === 'rejected') {
      console.log(`[Pairing] ${version} Pairing was rejected`)
      hasResolved = true
      callback({ status: 'rejected' })
      return
    }

    console.log(`[Pairing] ${version} Status is pending`)
    callback({ status: 'pending' })
  }

  // Listen on both paths
  const unsubscribeV2 = onValue(v2Ref, (snapshot) => handleSnapshot(snapshot, 'V2'), (error) => {
    console.error('[Pairing] V2 Listener error:', error)
  })

  const unsubscribeV1 = onValue(v1Ref, (snapshot) => handleSnapshot(snapshot, 'V1'), (error) => {
    console.error('[Pairing] V1 Listener error:', error)
  })

  // Return combined unsubscribe function
  return () => {
    unsubscribeV2()
    unsubscribeV1()
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
// PENDING_PAIRINGS_PATH is defined earlier (needed for pairing functions)
const OUTGOING_MESSAGES_PATH = 'outgoing_messages'
const CONTACTS_PATH = 'contacts'
const USAGE_PATH = 'usage'
const READ_RECEIPTS_PATH = 'read_receipts'

const TRIAL_DAYS = 7 // 7 day trial

// Trial/Free tier: 500MB upload/month, 1GB storage
const TRIAL_MONTHLY_UPLOAD_BYTES = 500 * 1024 * 1024
const TRIAL_STORAGE_BYTES = 1 * 1024 * 1024 * 1024

// Paid tier: 3GB upload/month, 15GB storage
const PAID_MONTHLY_UPLOAD_BYTES = 3 * 1024 * 1024 * 1024
const PAID_STORAGE_BYTES = 15 * 1024 * 1024 * 1024

// COST OPTIMIZATION: Cleanup grace periods by plan type
// Free/Trial users: aggressive cleanup to reduce storage costs
// Paid users: balanced approach to preserve data
const CLEANUP_CONFIG = {
  free: {
    // Delete inactive users faster on free tier
    inactiveUserDays: 14,        // Down from 90 days
    inactiveDeviceDays: 3,       // Down from 30 days
    emptyNodeDays: 1,            // Down from 7 days (orphaned nodes are waste)
    spamMessageDays: 7,          // Down from 30 days
    readReceiptDays: 7,          // Down from 30 days
    orphanedMediaDays: 7,        // Down from 30 days
    notificationDays: 1,         // Down from 7 days
    sessionHours: 24,            // Unchanged
    fileTransferDays: 1,         // Down from 7 days
    typingIndicatorMinutes: 0.5, // Unchanged
    // Storage optimization for free tier
    messageRetentionDays: 30,    // Keep messages for 30 days max
    maxMessagesPerConversation: 100, // Keep only last 100 messages
    mmsRetentionDays: 7,         // AGGRESSIVE: Delete MMS after 7 days (expensive storage)
    smsOnly: false,              // Free tier: SMS + MMS allowed (but aggressively deleted)
    maxStorageGB: 0.1,           // 100 MB max per user
  },
  paid: {
    // Preserve data longer for paid users
    inactiveUserDays: 60,        // Keep longer
    inactiveDeviceDays: 14,      // Keep longer
    emptyNodeDays: 7,            // Standard grace period
    spamMessageDays: 30,         // Keep spam logs
    readReceiptDays: 30,         // Keep receipts
    orphanedMediaDays: 30,       // Keep media longer
    notificationDays: 7,         // Standard
    sessionHours: 24,            // Standard
    fileTransferDays: 7,         // Standard
    typingIndicatorMinutes: 0.5, // Standard
    // Storage optimization for paid tier
    messageRetentionDays: 90,    // Keep messages for 90 days
    maxMessagesPerConversation: 1000, // Keep last 1000 messages
    mmsRetentionDays: 90,        // Keep MMS for 90 days (more generous than free)
    smsOnly: false,              // Paid tier: All message types allowed
    maxStorageGB: 5,             // 5 GB per user
  }
}

// Helper to get cleanup config for a user plan
const getCleanupConfig = (plan: string | null) => {
  const isPaid = plan && (plan.toLowerCase() === 'monthly' || plan.toLowerCase() === 'yearly' || plan.toLowerCase() === 'lifetime')
  return isPaid ? CLEANUP_CONFIG.paid : CLEANUP_CONFIG.free
}

const currentPeriodKey = () => {
  const now = new Date()
  const year = now.getUTCFullYear()
  const month = String(now.getUTCMonth() + 1).padStart(2, '0')
  return `${year}${month}`
}

const normalizePlan = (plan?: string | null) => (plan ? plan.toLowerCase() : null)

const isPaidPlan = (plan: string | null, planExpiresAt: number | null, nowMs: number) => {
  if (!plan) return false
  if (plan === 'lifetime') return true
  if (plan === 'monthly' || plan === 'yearly' || plan === 'paid') {
    return planExpiresAt ? planExpiresAt > nowMs : true
  }
  return false
}

const ensureTrialStarted = async (userId: string, trialStartedAt?: number | null) => {
  if (trialStartedAt) return trialStartedAt
  await set(ref(database, `${USERS_PATH}/${userId}/${USAGE_PATH}/trialStartedAt`), serverTimestamp())
  return Date.now()
}

const usageLimitMessage = (reason?: string | null) => {
  switch (reason) {
    case 'trial_expired':
      return 'Free trial expired. Upgrade to keep sending MMS.'
    case 'monthly_quota':
      return 'Monthly upload limit reached. Try again next month or upgrade.'
    case 'storage_quota':
      return 'Storage limit reached. Free up space or upgrade your plan.'
    default:
      return 'Upload limit reached. Please try again later.'
  }
}

const checkUploadQuota = async (userId: string, bytes: number, countsTowardStorage: boolean) => {
  if (bytes <= 0) return { allowed: true, reason: null }

  const usageSnap = await get(ref(database, `${USERS_PATH}/${userId}/${USAGE_PATH}`))
  const usage = usageSnap.exists() ? usageSnap.val() : {}

  const plan = normalizePlan(typeof usage.plan === 'string' ? usage.plan : null)
  const planExpiresAt = typeof usage.planExpiresAt === 'number' ? usage.planExpiresAt : null
  const nowMs = Date.now()
  const paid = isPaidPlan(plan, planExpiresAt, nowMs)

  if (!paid) {
    const trialStartedAtRaw = typeof usage.trialStartedAt === 'number' ? usage.trialStartedAt : null
    const trialStartedAt = await ensureTrialStarted(userId, trialStartedAtRaw)
    if (nowMs - trialStartedAt > TRIAL_DAYS * 24 * 60 * 60 * 1000) {
      return { allowed: false, reason: 'trial_expired' }
    }
  }

  const periodKey = currentPeriodKey()
  const periodData = usage.monthly?.[periodKey] ?? {}
  const uploadBytes = Number(periodData.uploadBytes || 0)
  const monthlyLimit = paid ? PAID_MONTHLY_UPLOAD_BYTES : TRIAL_MONTHLY_UPLOAD_BYTES
  if (uploadBytes + bytes > monthlyLimit) {
    return { allowed: false, reason: 'monthly_quota' }
  }

  if (countsTowardStorage) {
    const storageBytes = Number(usage.storageBytes || 0)
    const storageLimit = paid ? PAID_STORAGE_BYTES : TRIAL_STORAGE_BYTES
    if (storageBytes + bytes > storageLimit) {
      return { allowed: false, reason: 'storage_quota' }
    }
  }

  return { allowed: true, reason: null }
}

const recordUpload = async (
  userId: string,
  bytes: number,
  category: 'mms' | 'file',
  countsTowardStorage: boolean
) => {
  if (bytes <= 0) return

  const periodKey = currentPeriodKey()
  const updates: Record<string, any> = {
    [`monthly/${periodKey}/uploadBytes`]: increment(bytes),
    lastUpdatedAt: serverTimestamp(),
  }

  if (category === 'mms') {
    updates[`monthly/${periodKey}/mmsBytes`] = increment(bytes)
  } else {
    updates[`monthly/${periodKey}/fileBytes`] = increment(bytes)
  }

  if (countsTowardStorage) {
    updates.storageBytes = increment(bytes)
  }

  await update(ref(database, `${USERS_PATH}/${userId}/${USAGE_PATH}`), updates)
}

const getWebDeviceName = () => {
  if (typeof window === 'undefined') return 'Web'
  const platform = (navigator as any).userAgentData?.platform || navigator.platform
  return platform ? `Web (${platform})` : 'Web'
}

export const listenToReadReceipts = (
  userId: string,
  callback: (receipts: Record<string, any>) => void
) => {
  const receiptsRef = ref(database, `${USERS_PATH}/${userId}/${READ_RECEIPTS_PATH}`)

  return onValue(receiptsRef, (snapshot) => {
    if (!snapshot.exists()) {
      callback({})
      return
    }

    const data = snapshot.val() || {}
    const receipts: Record<string, any> = {}
    Object.entries(data).forEach(([key, value]) => {
      const receipt = value as any
      const messageId =
        typeof receipt.messageId === 'string'
          ? receipt.messageId
          : typeof receipt.messageId === 'number'
            ? String(receipt.messageId)
            : key
      const readAt = typeof receipt.readAt === 'number' ? receipt.readAt : 0
      receipts[messageId] = {
        messageId,
        readAt,
        readBy: receipt.readBy || 'unknown',
        readDeviceName: receipt.readDeviceName || null,
        conversationAddress: receipt.conversationAddress || '',
        sourceId: receipt.sourceId,
        sourceType: receipt.sourceType,
      }
    })
    callback(receipts)
  })
}

export const markMessagesRead = async (
  userId: string,
  messageIds: string[],
  conversationAddress: string
) => {
  if (!userId || messageIds.length === 0) return
  const deviceName = getWebDeviceName()
  const updates: Record<string, any> = {}

  messageIds.forEach((messageId) => {
    updates[`${USERS_PATH}/${userId}/${READ_RECEIPTS_PATH}/${messageId}`] = {
      messageId,
      readAt: serverTimestamp(),
      readBy: 'web',
      readDeviceName: deviceName,
      conversationAddress,
    }
  })

  await update(ref(database), updates)
}

export interface UsageSummary {
  planLabel: string
  planExpiresAt: number | null
  trialDaysRemaining: number | null
  monthlyUsedBytes: number
  monthlyLimitBytes: number
  storageUsedBytes: number
  storageLimitBytes: number
  mmsBytes: number
  fileBytes: number
  lastUpdatedAt: number | null
  isPaid: boolean
}

export const getUsageSummary = async (userId: string): Promise<UsageSummary> => {
  const snapshot = await get(ref(database, `${USERS_PATH}/${userId}/${USAGE_PATH}`))
  const usage = snapshot.exists() ? snapshot.val() : {}

  const plan = normalizePlan(typeof usage.plan === 'string' ? usage.plan : null)
  const planExpiresAt = typeof usage.planExpiresAt === 'number' ? usage.planExpiresAt : null
  const trialStartedAt = typeof usage.trialStartedAt === 'number' ? usage.trialStartedAt : null
  const lastUpdatedAt = typeof usage.lastUpdatedAt === 'number' ? usage.lastUpdatedAt : null

  const nowMs = Date.now()
  const isPaid = isPaidPlan(plan, planExpiresAt, nowMs)
  const monthlyLimitBytes = isPaid ? PAID_MONTHLY_UPLOAD_BYTES : TRIAL_MONTHLY_UPLOAD_BYTES
  const storageLimitBytes = isPaid ? PAID_STORAGE_BYTES : TRIAL_STORAGE_BYTES

  const periodKey = currentPeriodKey()
  const periodData = usage.monthly?.[periodKey] ?? {}
  const monthlyUsedBytes = Number(periodData.uploadBytes || 0)
  const mmsBytes = Number(periodData.mmsBytes || 0)
  const fileBytes = Number(periodData.fileBytes || 0)
  const storageUsedBytes = Number(usage.storageBytes || 0)

  let trialDaysRemaining: number | null = null
  if (!isPaid) {
    const trialStart = trialStartedAt ?? nowMs
    const remainingMs = trialStart + TRIAL_DAYS * 24 * 60 * 60 * 1000 - nowMs
    trialDaysRemaining = Math.max(0, Math.floor(remainingMs / (24 * 60 * 60 * 1000)))
  }

  const planLabel = (() => {
    if (!isPaid) return 'Trial'
    switch (plan) {
      case 'lifetime':
        return 'Lifetime'
      case 'yearly':
        return 'Yearly'
      case 'monthly':
        return 'Monthly'
      case 'paid':
        return 'Paid'
      default:
        return 'Paid'
    }
  })()

  return {
    planLabel,
    planExpiresAt,
    trialDaysRemaining,
    monthlyUsedBytes,
    monthlyLimitBytes,
    storageUsedBytes,
    storageLimitBytes,
    mmsBytes,
    fileBytes,
    lastUpdatedAt,
    isPaid,
  }
}

// Listen for messages
export const listenToMessages = (userId: string, callback: (messages: any[]) => void) => {
  const messagesRef = ref(database, `${USERS_PATH}/${userId}/${MESSAGES_PATH}`)

  return onValue(messagesRef, async (snapshot) => {
    const data = snapshot.val()
    if (data) {
      const deviceId = getOrCreateDeviceId()
      const messages = await Promise.all(
        Object.entries(data).map(async ([key, value]: [string, any]) => {
          const message = { id: key, ...value, decryptionFailed: false }

          if (message.encrypted && message.keyMap && message.nonce && deviceId) {
            const envelope = message.keyMap[deviceId]
            if (envelope) {
              try {
                const dataKey = await decryptDataKey(envelope)
                if (dataKey) {
                  const decrypted = await decryptMessageBody(
                    dataKey,
                    message.body,
                    message.nonce
                  )
                  if (decrypted) {
                    message.body = decrypted
                  } else {
                    message.decryptionFailed = true
                    message.body = '[🔒 Encrypted message - re-pair device to decrypt]'
                  }
                } else {
                  message.decryptionFailed = true
                  message.body = '[🔒 Encrypted message - re-pair device to decrypt]'
                }
              } catch {
                message.decryptionFailed = true
                message.body = '[🔒 Encrypted message - re-pair device to decrypt]'
              }
            } else if (message.encrypted) {
              // Encrypted but no key for this device
              message.decryptionFailed = true
              message.body = '[🔒 Encrypted message - re-pair device to decrypt]'
            }
          } else if (message.encrypted && !message.keyMap) {
            // Legacy encrypted message without key map
            message.decryptionFailed = true
            message.body = '[🔒 Encrypted message - re-pair device to decrypt]'
          }

          return message
        })
      )
      callback(messages)
    } else {
      callback([])
    }
  }, (error: any) => {
    console.error('Firebase listener error:', error?.code)
  })
}

// Listen for spam messages
export const listenToSpamMessages = (userId: string, callback: (messages: any[]) => void) => {
  const spamRef = ref(database, `${USERS_PATH}/${userId}/spam_messages`)

  return onValue(spamRef, (snapshot) => {
    const data = snapshot.val()
    if (data) {
      const messages = Object.entries(data).map(([key, value]: [string, any]) => ({
        id: key,
        address: value.address || '',
        body: value.body || '',
        date: value.date || 0,
        contactName: value.contactName,
        spamConfidence: value.spamConfidence,
        spamReasons: value.spamReasons,
        detectedAt: value.detectedAt,
        isUserMarked: value.isUserMarked,
        isRead: value.isRead,
      }))
      messages.sort((a, b) => b.date - a.date)
      callback(messages)
    } else {
      callback([])
    }
  }, (error: any) => {
    console.error('Spam listener error:', error?.code)
  })
}

// Mark a message as spam (syncs to all devices)
export const markMessageAsSpam = async (
  userId: string,
  message: {
    id: string,
    address: string,
    body: string,
    date: number,
    contactName?: string
  }
): Promise<void> => {
  const messageId = message.id || String(message.date)
  const spamRef = ref(database, `${USERS_PATH}/${userId}/spam_messages/${messageId}`)

  const payload = {
    messageId: message.id,
    address: message.address,
    body: message.body,
    date: message.date,
    contactName: message.contactName || null,
    spamConfidence: 1.0,
    spamReasons: 'Marked by user',
    detectedAt: Date.now(),
    isUserMarked: true,
    isRead: true,
    originalMessageId: message.id
  }

  await set(spamRef, payload)
  console.log('Message marked as spam:', messageId)
}

// Delete a spam message (syncs to all devices)
export const deleteSpamMessage = async (userId: string, messageId: string): Promise<void> => {
  const spamRef = ref(database, `${USERS_PATH}/${userId}/spam_messages/${messageId}`)
  await remove(spamRef)
  console.log('Spam message deleted:', messageId)
}

// Unmark a message as spam (move back to inbox)
export const unmarkMessageAsSpam = async (userId: string, messageId: string): Promise<void> => {
  await deleteSpamMessage(userId, messageId)
  console.log('Message unmarked as spam:', messageId)
}

// Delivery status tracking types
export type DeliveryStatus = 'pending' | 'sending' | 'sent' | 'failed' | 'delivered'

export interface DeliveryResult {
  messageId: string
  status: DeliveryStatus
  error?: string
}

// Default delivery timeout in milliseconds (60 seconds)
const DELIVERY_TIMEOUT_MS = 60000

// Listen for message delivery status with timeout
export const waitForDelivery = (
  userId: string,
  messageId: string,
  timeoutMs: number = DELIVERY_TIMEOUT_MS
): Promise<DeliveryResult> => {
  return new Promise((resolve) => {
    const messageRef = ref(database, `${USERS_PATH}/${userId}/${OUTGOING_MESSAGES_PATH}/${messageId}`)
    let unsubscribe: (() => void) | null = null
    let timeoutId: NodeJS.Timeout | null = null

    const cleanup = () => {
      if (unsubscribe) unsubscribe()
      if (timeoutId) clearTimeout(timeoutId)
    }

    // Set timeout for delivery
    timeoutId = setTimeout(() => {
      cleanup()
      resolve({
        messageId,
        status: 'failed',
        error: 'Delivery timeout - phone may be offline or not running the app',
      })
    }, timeoutMs)

    // Listen for status changes
    unsubscribe = onValue(messageRef, (snapshot) => {
      if (!snapshot.exists()) {
        // Message was deleted - means it was processed successfully
        cleanup()
        resolve({ messageId, status: 'sent' })
        return
      }

      const data = snapshot.val()
      const status = data?.status as DeliveryStatus

      if (status === 'sent' || status === 'delivered') {
        cleanup()
        resolve({ messageId, status })
      } else if (status === 'failed') {
        cleanup()
        resolve({
          messageId,
          status: 'failed',
          error: data?.error || 'Message delivery failed',
        })
      }
      // For 'pending' or 'sending' status, keep waiting
    })
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
    createdAt: Date.now(),
  })

  return newMessageRef.key
}

// Send SMS with delivery tracking
export const sendSmsWithDeliveryTracking = async (
  userId: string,
  address: string,
  body: string,
  onStatusChange?: (status: DeliveryStatus) => void
): Promise<DeliveryResult> => {
  const messageId = await sendSmsFromWeb(userId, address, body)
  if (!messageId) {
    return { messageId: '', status: 'failed', error: 'Failed to queue message' }
  }

  onStatusChange?.('pending')

  const result = await waitForDelivery(userId, messageId)
  onStatusChange?.(result.status)

  return result
}

// Upload image for MMS
export const uploadMmsImage = async (
  userId: string,
  file: File
): Promise<{ url: string; contentType: string; fileName: string }> => {
  const { ref: storageRef, uploadBytes, getDownloadURL } = await import('firebase/storage')

  const usageDecision = await checkUploadQuota(userId, file.size, true)
  if (!usageDecision.allowed) {
    throw new Error(usageLimitMessage(usageDecision.reason))
  }

  // Generate unique filename
  const timestamp = Date.now()
  const extension = file.name.split('.').pop() || 'jpg'
  const fileName = `mms_${timestamp}.${extension}`
  const storagePath = `users/${userId}/outgoing_attachments/${fileName}`

  const fileRef = storageRef(storage, storagePath)

  // Read file as array buffer
  const arrayBuffer = await file.arrayBuffer()
  const bytes = new Uint8Array(arrayBuffer)

  // Upload to Firebase Storage
  await uploadBytes(fileRef, bytes, {
    contentType: file.type,
  })

  // Get download URL
  const url = await getDownloadURL(fileRef)

  await recordUpload(userId, file.size, 'mms', true)

  return {
    url,
    contentType: file.type,
    fileName: file.name,
  }
}

// Send MMS from web (with image attachments)
export const sendMmsFromWeb = async (
  userId: string,
  address: string,
  body: string,
  attachments: Array<{ url: string; contentType: string; fileName: string }>
) => {
  console.log(`[WebSend] Sending MMS to address: "${address}" (normalized: "${normalizePhoneNumber(address)}"), body: "${body.substring(0, 50)}${body.length > 50 ? '...' : ''}", attachments: ${attachments.length}`)

  const outgoingRef = ref(database, `${USERS_PATH}/${userId}/${OUTGOING_MESSAGES_PATH}`)
  const newMessageRef = push(outgoingRef)

  const attachmentData = attachments.map((att, idx) => ({
    id: idx,
    url: att.url,
    contentType: att.contentType,
    fileName: att.fileName,
    type: att.contentType.startsWith('image/') ? 'image' : 'file',
    encrypted: false,
  }))

  const messageData = {
    address,
    body,
    isMms: true,
    attachments: attachmentData,
    timestamp: serverTimestamp(),
    status: 'pending',
  }

  await set(newMessageRef, messageData)

  return newMessageRef.key
}

// Generate pairing token (WEB SIDE - shows QR code)
export const generatePairingToken = async () => {
  const timestamp = Date.now()
  const randomToken = Math.random().toString(36).substring(2, 15)
  const token = `web_${timestamp}_${randomToken}`

  // Store token in Firebase pending_pairings
  const pairingRef = ref(database, `${PENDING_PAIRINGS_PATH}/${token}`)
  await set(pairingRef, {
    createdAt: serverTimestamp(),
    expiresAt: timestamp + 5 * 60 * 1000, // 5 minutes
    type: 'web',
  })

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

export const ensureWebDeviceRegistered = async (userId: string, deviceName?: string) => {
  const deviceId = getOrCreateDeviceId()
  if (!deviceId) return null

  const deviceRef = ref(database, `${USERS_PATH}/${userId}/${DEVICES_PATH}/${deviceId}`)
  await set(deviceRef, {
    name: deviceName || 'Web',
    type: 'web',
    platform: 'web',
    isPaired: true,
    pairedAt: serverTimestamp(),
    lastSeen: serverTimestamp(),
  })

  return deviceId
}

export const ensureWebE2EEKeyPublished = async (userId: string) => {
  const deviceId = getOrCreateDeviceId()
  if (!deviceId) return
  const publicKeyX963 = await getPublicKeyX963Base64()
  if (!publicKeyX963) return

  const keyRef = ref(database, `e2ee_keys/${userId}/${deviceId}`)
  await set(keyRef, {
    publicKeyX963,
    format: 'x963',
    keyVersion: 2,
    platform: 'web',
    timestamp: serverTimestamp(),
  })
}

export const listenToDeviceStatus = (
  userId: string,
  callback: (isPaired: boolean) => void
) => {
  const deviceId = getOrCreateDeviceId()
  if (!deviceId) {
    callback(false)
    return () => {}
  }

  const deviceRef = ref(database, `${USERS_PATH}/${userId}/${DEVICES_PATH}/${deviceId}`)
  return onValue(deviceRef, (snapshot) => {
    if (!snapshot.exists()) {
      callback(false)
      return
    }

    const data = snapshot.val() as Record<string, any> | null
    const isPaired = data?.isPaired ?? true
    callback(!!isPaired)
  })
}

// ===== CONTACTS FUNCTIONS =====

export interface ContactPhoto {
  thumbnailBase64?: string
  hash?: string
  storagePath?: string
  updatedAt?: number
}

export interface ContactSources {
  android?: boolean
  web?: boolean
  macos?: boolean
}

export interface ContactSyncMetadata {
  lastUpdatedAt: number
  lastSyncedAt?: number
  lastUpdatedBy: string
  version: number
  pendingAndroidSync: boolean
  desktopOnly: boolean
}

export interface Contact {
  id: string
  displayName: string
  phoneNumber?: string
  normalizedNumber?: string
  phoneType: string
  photo?: ContactPhoto
  notes?: string
  email?: string
  sync: ContactSyncMetadata
  sources: ContactSources
  androidContactId?: number
}

const contactsFromSnapshot = (data: Record<string, any>): Contact[] => {
  return Object.entries(data).map(([key, value]) => {
    const contactData = value || {}
    const phoneNumbers = contactData.phoneNumbers as Record<string, any> | undefined
    const firstPhone = phoneNumbers
      ? (Object.values(phoneNumbers)[0] as Record<string, any> | undefined)
      : undefined
    const photoData = contactData.photo as Record<string, any> | undefined
    const syncData = contactData.sync as Record<string, any> | undefined
    const sources = contactData.sources as Record<string, boolean> | undefined
    const emailData = contactData.emails
      ? (Object.values(contactData.emails)[0] as Record<string, any> | undefined)
      : undefined

    return {
      id: key,
      displayName: contactData.displayName || '',
      phoneNumber: firstPhone?.number,
      normalizedNumber: firstPhone?.normalizedNumber,
      phoneType: firstPhone?.type || 'Mobile',
      photo: photoData
        ? {
            thumbnailBase64: photoData.thumbnailBase64,
            hash: photoData.hash,
            storagePath: photoData.storagePath,
            updatedAt: photoData.updatedAt,
          }
        : undefined,
      notes: contactData.notes,
      email: emailData?.address,
      sync: {
        lastUpdatedAt: Number(syncData?.lastUpdatedAt ?? 0),
        lastSyncedAt: typeof syncData?.lastSyncedAt === 'number' ? syncData.lastSyncedAt : undefined,
        lastUpdatedBy: syncData?.lastUpdatedBy ?? '',
        version: Number(syncData?.version ?? 0),
        pendingAndroidSync: !!syncData?.pendingAndroidSync,
        desktopOnly: !!syncData?.desktopOnly,
      },
      sources: {
        android: !!sources?.android,
        web: !!sources?.web,
        macos: !!sources?.macos,
      },
      androidContactId: contactData.androidContactId,
    }
  })
}

export const listenToContacts = (userId: string, callback: (contacts: Contact[]) => void) => {
  const contactsRef = ref(database, `${USERS_PATH}/${userId}/${CONTACTS_PATH}`)

  return onValue(contactsRef, (snapshot) => {
    const data = snapshot.val()
    if (data) {
      const contacts = contactsFromSnapshot(data as Record<string, any>)
      contacts.sort((a, b) => a.displayName.localeCompare(b.displayName))
      callback(contacts)
    } else {
      callback([])
    }
  })
}

const buildContactPayload = async (
  existingData: Record<string, any> | null,
  displayName: string,
  phoneNumber: string,
  phoneType: string,
  email?: string,
  notes?: string,
  photoBase64?: string,
  source: 'web' | 'macos' = 'web'
): Promise<Record<string, any>> => {
  const payload = existingData ? { ...existingData } : {}

  payload.displayName = displayName

  if (notes && notes.trim().length > 0) {
    payload.notes = notes.trim()
  } else {
    delete payload.notes
  }

  if (email && email.trim().length > 0) {
    const normalizedEmail = email.trim().toLowerCase()
    payload.emails = {
      [normalizedEmail]: {
        address: email.trim(),
        type: 'primary',
        isPrimary: true,
      },
    }
  } else {
    delete payload.emails
  }

  const normalizedNumber = PhoneNumberNormalizer.normalize(phoneNumber)
  const phoneKey = normalizedNumber || phoneNumber
  payload.phoneNumbers = {
    [phoneKey]: {
      number: phoneNumber,
      normalizedNumber,
      type: phoneType,
      label: phoneType,
      isPrimary: true,
    },
  }

  const existingPhoto = payload.photo as Record<string, any> | undefined
  const photoMap = existingPhoto ? { ...existingPhoto } : {}
  if (photoBase64 !== undefined) {
    const trimmedPhoto = photoBase64.trim()
    if (trimmedPhoto.length > 0) {
      photoMap.thumbnailBase64 = trimmedPhoto
    } else {
      delete photoMap.thumbnailBase64
      delete photoMap.hash
    }
    photoMap.updatedAt = serverTimestamp()
  }
  if (existingPhoto?.storagePath) {
    photoMap.storagePath = existingPhoto.storagePath
  }
  payload.photo = photoMap

  const existingSources = payload.sources as Record<string, boolean> | undefined
  const sources: ContactSources = {
    android: existingSources?.android ?? false,
    web: existingSources?.web ?? false,
    macos: existingSources?.macos ?? false,
  }
  sources[source] = true
  payload.sources = sources

  const existingSync = payload.sync as Record<string, any> | undefined
  const existingVersion = Number(existingSync?.version ?? 0)
  const version = existingVersion + 1
  const syncMeta: Record<string, any> = {
    lastUpdatedAt: serverTimestamp(),
    lastUpdatedBy: source,
    version,
    pendingAndroidSync: true,
    desktopOnly: true,
  }
  if (existingSync?.lastSyncedAt) {
    syncMeta.lastSyncedAt = existingSync.lastSyncedAt
  }
  payload.sync = syncMeta

  return payload
}

export const createContact = async (
  userId: string,
  displayName: string,
  phoneNumber: string,
  phoneType: string = 'Mobile',
  email?: string,
  notes?: string,
  photoBase64?: string,
  source: 'web' | 'macos' = 'web'
) => {
  const contactId = PhoneNumberNormalizer.getDeduplicationKey(phoneNumber, displayName)
  const contactRef = ref(database, `${USERS_PATH}/${userId}/${CONTACTS_PATH}/${contactId}`)
  const payload = await buildContactPayload(
    null,
    displayName,
    phoneNumber,
    phoneType,
    email,
    notes,
    photoBase64,
    source
  )
  await set(contactRef, payload)
  return contactId
}

export const updateContact = async (
  userId: string,
  contactId: string,
  displayName: string,
  phoneNumber: string,
  phoneType: string = 'Mobile',
  email?: string,
  notes?: string,
  photoBase64?: string,
  source: 'web' | 'macos' = 'web'
) => {
  const contactRef = ref(database, `${USERS_PATH}/${userId}/${CONTACTS_PATH}/${contactId}`)
  const snapshot = await get(contactRef)
  const existingData = snapshot.val() as Record<string, any> | null
  const payload = await buildContactPayload(
    existingData,
    displayName,
    phoneNumber,
    phoneType,
    email,
    notes,
    photoBase64,
    source
  )
  await set(contactRef, payload)
}

export const deleteContact = async (userId: string, contactId: string) => {
  const contactRef = ref(database, `${USERS_PATH}/${userId}/${CONTACTS_PATH}/${contactId}`)
  await remove(contactRef)
}

// ============================================
// ============================================
// ADMIN CLEANUP FUNCTIONS
// ============================================

export interface CleanupStats {
  outgoingMessages: number
  pendingPairings: number
  callRequests: number
  spamMessages: number
  readReceipts: number
  oldDevices: number
  // New cleanup categories
  oldNotifications: number
  staleTypingIndicators: number
  expiredSessions: number
  oldFileTransfers: number
  abandonedPairings: number
  orphanedMedia: number
}

export interface OrphanCounts {
  staleOutgoingMessages: number
  expiredPairings: number
  oldCallRequests: number
  oldSpamMessages: number
  oldReadReceipts: number
  inactiveDevices: number
  // New cleanup categories
  oldNotifications: number
  staleTypingIndicators: number
  expiredSessions: number
  oldFileTransfers: number
  abandonedPairings: number
  orphanedMedia: number
  // Cost optimization - NEW
  emptyUserNodes: number
  orphanedUsers: number
}

// Get counts of orphan/stale data - FIXED: Now actually detects orphaned nodes
export const getOrphanCounts = async (): Promise<OrphanCounts> => {
  const counts: OrphanCounts = {
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
    emptyUserNodes: 0,
    orphanedUsers: 0
  }

  try {
    const now = Date.now()
    const thirtyDaysAgo = now - (30 * 24 * 60 * 60 * 1000)
    const sevenDaysAgo = now - (7 * 24 * 60 * 60 * 1000)

    // Check pending pairings - V1 path
    const v1PairingsRef = ref(database, PENDING_PAIRINGS_PATH)
    const v1PairingsSnapshot = await get(v1PairingsRef)
    if (v1PairingsSnapshot.exists()) {
      v1PairingsSnapshot.forEach((child) => {
        const data = child.val()
        if (data.expiresAt && now > data.expiresAt) {
          counts.expiredPairings++
        }
      })
    }

    // Check pairing requests - V2 path
    const v2PairingsRef = ref(database, 'pairing_requests')
    const v2PairingsSnapshot = await get(v2PairingsRef)
    if (v2PairingsSnapshot.exists()) {
      v2PairingsSnapshot.forEach((child) => {
        const data = child.val()
        const isExpired = data.expiresAt && now > data.expiresAt
        const isOldCompleted = data.status !== 'pending' &&
          data.createdAt && (now - data.createdAt > 10 * 60 * 1000)
        if (isExpired || isOldCompleted) {
          counts.expiredPairings++
        }
      })
    }

    // Check all users for orphaned nodes
    const usersRef = ref(database, USERS_PATH)
    const usersSnapshot = await get(usersRef)

    if (usersSnapshot.exists()) {
      const users = usersSnapshot.val()

      for (const [userId, userData] of Object.entries(users)) {
        const data = userData as any

        // Count user-specific orphaned data
        if (data.outgoing_messages) {
          const cutoff = now - (24 * 60 * 60 * 1000)
          Object.values(data.outgoing_messages).forEach((msg: any) => {
            const createdAt = msg.createdAt || msg.timestamp || 0
            if (createdAt < cutoff) {
              counts.staleOutgoingMessages++
            }
          })
        }

        if (data.call_requests) {
          const cutoff = now - (60 * 60 * 1000)
          Object.values(data.call_requests).forEach((req: any) => {
            const requestedAt = req.requestedAt || 0
            if (requestedAt < cutoff) {
              counts.oldCallRequests++
            }
          })
        }

        if (data.spam_messages) {
          const cutoff = now - (30 * 24 * 60 * 60 * 1000)
          Object.values(data.spam_messages).forEach((spam: any) => {
            const date = spam.date || spam.detectedAt || 0
            if (date < cutoff) {
              counts.oldSpamMessages++
            }
          })
        }

        if (data.read_receipts) {
          const cutoff = now - (30 * 24 * 60 * 60 * 1000)
          Object.values(data.read_receipts).forEach((receipt: any) => {
            const readAt = receipt.readAt || 0
            if (readAt < cutoff) {
              counts.oldReadReceipts++
            }
          })
        }

        if (data.devices) {
          const cutoff = now - (30 * 24 * 60 * 60 * 1000)
          Object.values(data.devices).forEach((device: any) => {
            const lastSeen = device.lastSeen || 0
            if (device.platform !== 'android' && lastSeen > 0 && lastSeen < cutoff) {
              counts.inactiveDevices++
            }
          })
        }

        if (data.notifications) {
          const cutoff = now - (7 * 24 * 60 * 60 * 1000)
          Object.values(data.notifications).forEach((notif: any) => {
            const timestamp = notif.timestamp || notif.createdAt || 0
            if (timestamp < cutoff) {
              counts.oldNotifications++
            }
          })
        }

        if (data.typing) {
          const cutoff = now - (0.5 * 60 * 1000)
          Object.values(data.typing).forEach((typing: any) => {
            const timestamp = typing.timestamp || 0
            if (timestamp < cutoff) {
              counts.staleTypingIndicators++
            }
          })
        }

        if (data.sessions) {
          const cutoff = now - (24 * 60 * 60 * 1000)
          Object.values(data.sessions).forEach((session: any) => {
            const lastActivity = session.lastActivity || session.createdAt || 0
            if (lastActivity < cutoff) {
              counts.expiredSessions++
            }
          })
        }

        if (data.file_transfers) {
          const cutoff = now - (7 * 24 * 60 * 60 * 1000)
          Object.values(data.file_transfers).forEach((transfer: any) => {
            const timestamp = transfer.timestamp || transfer.createdAt || 0
            if (timestamp < cutoff) {
              counts.oldFileTransfers++
            }
          })
        }

        // DETECT EMPTY USER NODES (no data but node exists)
        const hasMessages = data.messages && Object.keys(data.messages).length > 0
        const hasDevices = data.devices && Object.keys(data.devices).length > 0
        const lastActivity = data.lastActivity || 0

        // Empty node: no messages, no devices, inactive 7+ days
        if (!hasMessages && !hasDevices && lastActivity > 0 && lastActivity < sevenDaysAgo) {
          counts.emptyUserNodes++
        }

        // DETECT ORPHANED USERS (no messages, no activity, older than 30 days)
        if (!hasMessages && lastActivity > 0 && lastActivity < thirtyDaysAgo) {
          counts.orphanedUsers++
        }
      }
    }

    return counts
  } catch (error) {
    console.error('Error getting orphan counts:', error)
    return {
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
      emptyUserNodes: 0,
      orphanedUsers: 0
    }
  }
}

// Clean up stale outgoing messages (older than specified hours)
export const cleanupStaleOutgoingMessages = async (userId: string, olderThanHours: number = 24): Promise<number> => {
  const cutoff = Date.now() - (olderThanHours * 60 * 60 * 1000)
  let deletedCount = 0

  try {
    const outgoingRef = ref(database, `${USERS_PATH}/${userId}/${OUTGOING_MESSAGES_PATH}`)
    const snapshot = await get(outgoingRef)

    if (snapshot.exists()) {
      const deletePromises: Promise<void>[] = []
      snapshot.forEach((child) => {
        const data = child.val()
        const createdAt = data.createdAt || data.timestamp || 0
        if (createdAt < cutoff) {
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })
      await Promise.all(deletePromises)
    }
  } catch (error) {
    console.error('Error cleaning up outgoing messages:', error)
  }

  return deletedCount
}

// Clean up expired pending pairings (V1 and V2 paths)
export const cleanupExpiredPairings = async (): Promise<number> => {
  const now = Date.now()
  let deletedCount = 0

  try {
    // Clean up V1 path (pending_pairings)
    const v1PairingsRef = ref(database, PENDING_PAIRINGS_PATH)
    const v1Snapshot = await get(v1PairingsRef)

    if (v1Snapshot.exists()) {
      const deletePromises: Promise<void>[] = []
      v1Snapshot.forEach((child) => {
        const data = child.val()
        if (data.expiresAt && now > data.expiresAt) {
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })
      await Promise.all(deletePromises)
    }

    // Clean up V2 path (pairing_requests)
    const v2PairingsRef = ref(database, 'pairing_requests')
    const v2Snapshot = await get(v2PairingsRef)

    if (v2Snapshot.exists()) {
      const deletePromises: Promise<void>[] = []
      v2Snapshot.forEach((child) => {
        const data = child.val()
        // Delete if expired or if it's an old completed/rejected request
        const isExpired = data.expiresAt && now > data.expiresAt
        const isOldCompleted = data.status !== 'pending' &&
          data.createdAt && (now - data.createdAt > 10 * 60 * 1000) // 10 minutes

        if (isExpired || isOldCompleted) {
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })
      await Promise.all(deletePromises)
    }
  } catch (error) {
    console.error('Error cleaning up expired pairings:', error)
  }

  return deletedCount
}

// Clean up old call requests (older than specified hours)
export const cleanupOldCallRequests = async (userId: string, olderThanHours: number = 1): Promise<number> => {
  const cutoff = Date.now() - (olderThanHours * 60 * 60 * 1000)
  let deletedCount = 0

  try {
    const callsRef = ref(database, `${USERS_PATH}/${userId}/call_requests`)
    const snapshot = await get(callsRef)

    if (snapshot.exists()) {
      const deletePromises: Promise<void>[] = []
      snapshot.forEach((child) => {
        const data = child.val()
        const requestedAt = data.requestedAt || 0
        if (requestedAt < cutoff) {
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })
      await Promise.all(deletePromises)
    }
  } catch (error) {
    console.error('Error cleaning up call requests:', error)
  }

  return deletedCount
}

// Clean up old spam messages (older than specified days)
export const cleanupOldSpamMessages = async (userId: string, olderThanDays: number = 30): Promise<number> => {
  const cutoff = Date.now() - (olderThanDays * 24 * 60 * 60 * 1000)
  let deletedCount = 0

  try {
    const spamRef = ref(database, `${USERS_PATH}/${userId}/spam_messages`)
    const snapshot = await get(spamRef)

    if (snapshot.exists()) {
      const deletePromises: Promise<void>[] = []
      snapshot.forEach((child) => {
        const data = child.val()
        const date = data.date || data.detectedAt || 0
        if (date < cutoff) {
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })
      await Promise.all(deletePromises)
    }
  } catch (error) {
    console.error('Error cleaning up spam messages:', error)
  }

  return deletedCount
}

// Clean up old read receipts (older than specified days)
export const cleanupOldReadReceipts = async (userId: string, olderThanDays: number = 30): Promise<number> => {
  const cutoff = Date.now() - (olderThanDays * 24 * 60 * 60 * 1000)
  let deletedCount = 0

  try {
    const receiptsRef = ref(database, `${USERS_PATH}/${userId}/read_receipts`)
    const snapshot = await get(receiptsRef)

    if (snapshot.exists()) {
      const deletePromises: Promise<void>[] = []
      snapshot.forEach((child) => {
        const data = child.val()
        const readAt = data.readAt || 0
        if (readAt < cutoff) {
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })
      await Promise.all(deletePromises)
    }
  } catch (error) {
    console.error('Error cleaning up read receipts:', error)
  }

  return deletedCount
}

// Clean up inactive devices (not seen in specified days)
export const cleanupInactiveDevices = async (userId: string, olderThanDays: number = 30): Promise<number> => {
  const cutoff = Date.now() - (olderThanDays * 24 * 60 * 60 * 1000)
  let deletedCount = 0

  try {
    const devicesRef = ref(database, `${USERS_PATH}/${userId}/devices`)
    const snapshot = await get(devicesRef)

    if (snapshot.exists()) {
      const deletePromises: Promise<void>[] = []
      snapshot.forEach((child) => {
        const data = child.val()
        const lastSeen = data.lastSeen || 0
        // Skip android devices (those are the phone itself)
        if (data.platform === 'android') return
        if (lastSeen < cutoff && lastSeen > 0) {
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })
      await Promise.all(deletePromises)
    }
  } catch (error) {
    console.error('Error cleaning up inactive devices:', error)
  }

  return deletedCount
}

// Clean up old notifications (older than specified days)
export const cleanupOldNotifications = async (userId: string, olderThanDays: number = 7): Promise<number> => {
  const cutoff = Date.now() - (olderThanDays * 24 * 60 * 60 * 1000)
  let deletedCount = 0

  try {
    const notificationsRef = ref(database, `${USERS_PATH}/${userId}/notifications`)
    const snapshot = await get(notificationsRef)

    if (snapshot.exists()) {
      const deletePromises: Promise<void>[] = []
      snapshot.forEach((child) => {
        const data = child.val()
        const timestamp = data.timestamp || data.createdAt || 0
        if (timestamp < cutoff) {
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })
      await Promise.all(deletePromises)
    }
  } catch (error) {
    console.error('Error cleaning up old notifications:', error)
  }

  return deletedCount
}

// Clean up stale typing indicators (older than specified minutes)
export const cleanupTypingIndicators = async (userId: string, olderThanMinutes: number = 0.5): Promise<number> => {
  const cutoff = Date.now() - (olderThanMinutes * 60 * 1000)
  let deletedCount = 0

  try {
    const typingRef = ref(database, `${USERS_PATH}/${userId}/typing`)
    const snapshot = await get(typingRef)

    if (snapshot.exists()) {
      const deletePromises: Promise<void>[] = []
      snapshot.forEach((child) => {
        const data = child.val()
        const timestamp = data.timestamp || 0
        if (timestamp < cutoff) {
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })
      await Promise.all(deletePromises)
    }
  } catch (error) {
    console.error('Error cleaning up typing indicators:', error)
  }

  return deletedCount
}

// Clean up expired sessions (older than specified hours)
export const cleanupExpiredSessions = async (userId: string, olderThanHours: number = 24): Promise<number> => {
  const cutoff = Date.now() - (olderThanHours * 60 * 60 * 1000)
  let deletedCount = 0

  try {
    const sessionsRef = ref(database, `${USERS_PATH}/${userId}/sessions`)
    const snapshot = await get(sessionsRef)

    if (snapshot.exists()) {
      const deletePromises: Promise<void>[] = []
      snapshot.forEach((child) => {
        const data = child.val()
        const lastActivity = data.lastActivity || data.createdAt || 0
        if (lastActivity < cutoff) {
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })
      await Promise.all(deletePromises)
    }
  } catch (error) {
    console.error('Error cleaning up expired sessions:', error)
  }

  return deletedCount
}

// Clean up old file transfers (older than specified days) - includes R2 file cleanup
export const cleanupOldFileTransfers = async (userId: string, olderThanDays: number = 7): Promise<number> => {
  const cutoff = Date.now() - (olderThanDays * 24 * 60 * 60 * 1000)
  let deletedCount = 0

  try {
    const transfersRef = ref(database, `${USERS_PATH}/${userId}/file_transfers`)
    const snapshot = await get(transfersRef)

    if (snapshot.exists()) {
      const deletePromises: Promise<void>[] = []
      const deleteR2FileFn = httpsCallable(functions, 'deleteR2File')

      snapshot.forEach((child) => {
        const data = child.val()
        const timestamp = data.timestamp || data.startedAt || 0
        if (timestamp < cutoff) {
          // Delete R2 file if r2Key exists
          if (data.r2Key) {
            deletePromises.push(
              deleteR2FileFn({ r2Key: data.r2Key })
                .catch((err: any) => console.warn(`Failed to delete R2 file ${data.r2Key}:`, err.message))
            )
          }
          // Delete database record
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })
      await Promise.all(deletePromises)
    }
  } catch (error) {
    console.error('Error cleaning up old file transfers:', error)
  }

  return deletedCount
}

// Clean up abandoned pairings (started but not completed within specified hours)
export const cleanupAbandonedPairings = async (userId: string, olderThanHours: number = 1): Promise<number> => {
  const cutoff = Date.now() - (olderThanHours * 60 * 60 * 1000)
  let deletedCount = 0

  try {
    const pairingsRef = ref(database, `${USERS_PATH}/${userId}/pairings`)
    const snapshot = await get(pairingsRef)

    if (snapshot.exists()) {
      const deletePromises: Promise<void>[] = []
      snapshot.forEach((child) => {
        const data = child.val()
        const startedAt = data.startedAt || data.createdAt || 0
        // Only delete pairings that were started but never completed
        if (!data.completedAt && startedAt < cutoff) {
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })
      await Promise.all(deletePromises)
    }
  } catch (error) {
    console.error('Error cleaning up abandoned pairings:', error)
  }

  return deletedCount
}

// Clean up orphaned media (older than specified days) - includes R2 file cleanup
export const cleanupOrphanedMedia = async (userId: string, olderThanDays: number = 30): Promise<number> => {
  const cutoff = Date.now() - (olderThanDays * 24 * 60 * 60 * 1000)
  let deletedCount = 0

  try {
    const mediaRef = ref(database, `${USERS_PATH}/${userId}/media`)
    const snapshot = await get(mediaRef)

    if (snapshot.exists()) {
      const deletePromises: Promise<void>[] = []
      const deleteR2FileFn = httpsCallable(functions, 'deleteR2File')

      snapshot.forEach((child) => {
        const data = child.val()
        const uploadedAt = data.uploadedAt || data.timestamp || 0
        if (uploadedAt < cutoff) {
          // Delete R2 file if r2Key exists
          if (data.r2Key) {
            deletePromises.push(
              deleteR2FileFn({ r2Key: data.r2Key })
                .catch((err: any) => console.warn(`Failed to delete R2 media file ${data.r2Key}:`, err.message))
            )
          }
          // Delete database record
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })
      await Promise.all(deletePromises)
    }
  } catch (error) {
    console.error('Error cleaning up orphaned media:', error)
  }

  return deletedCount
}

// NEW: Clean up empty user nodes (no data, inactive) - COST OPTIMIZATION
export const cleanupEmptyUserNodes = async (inactiveDays?: number): Promise<number> => {
  const cutoff = Date.now() - ((inactiveDays || 7) * 24 * 60 * 60 * 1000)
  let deletedCount = 0

  try {
    const usersRef = ref(database, USERS_PATH)
    const snapshot = await get(usersRef)

    if (snapshot.exists()) {
      const deletePromises: Promise<void>[] = []

      snapshot.forEach((child) => {
        const data = child.val()
        const userId = child.key
        const lastActivity = data.lastActivity || 0

        // Delete if: no messages AND no devices AND inactive
        const hasMessages = data.messages && Object.keys(data.messages).length > 0
        const hasDevices = data.devices && Object.keys(data.devices).length > 0

        if (!hasMessages && !hasDevices && lastActivity > 0 && lastActivity < cutoff) {
          console.log(`Deleting empty user node: ${userId} (last activity: ${new Date(lastActivity).toISOString()})`)
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })

      if (deletePromises.length > 0) {
        await Promise.all(deletePromises)
        console.log(`Cleaned up ${deletedCount} empty user nodes`)
      }
    }
  } catch (error) {
    console.error('Error cleaning up empty user nodes:', error)
  }

  return deletedCount
}

// NEW: Clean up orphaned users (no messages, inactive 30+ days) - COST OPTIMIZATION
export const cleanupOrphanedUsers = async (inactiveDays: number = 30): Promise<number> => {
  const cutoff = Date.now() - (inactiveDays * 24 * 60 * 60 * 1000)
  let deletedCount = 0

  try {
    const usersRef = ref(database, USERS_PATH)
    const snapshot = await get(usersRef)

    if (snapshot.exists()) {
      const deletePromises: Promise<void>[] = []

      snapshot.forEach((child) => {
        const data = child.val()
        const userId = child.key
        const lastActivity = data.lastActivity || 0

        // Delete if: no messages AND inactive 30+ days
        const hasMessages = data.messages && Object.keys(data.messages).length > 0

        if (!hasMessages && lastActivity > 0 && lastActivity < cutoff) {
          console.log(`Deleting orphaned user: ${userId} (last activity: ${new Date(lastActivity).toISOString()})`)
          deletePromises.push(remove(child.ref))
          deletedCount++
        }
      })

      if (deletePromises.length > 0) {
        await Promise.all(deletePromises)
        console.log(`Cleaned up ${deletedCount} orphaned users`)
        addAdminAuditLog('cleanup_orphaned_users', 'system', `Deleted ${deletedCount} orphaned user accounts`)
      }
    }
  } catch (error) {
    console.error('Error cleaning up orphaned users:', error)
  }

  return deletedCount
}

// NEW: Detect duplicate users from same device (caused by disconnect/reconnect) - COST OPTIMIZATION
export const detectDuplicateUsersByDevice = async (): Promise<Array<{
  deviceId: string
  userIds: string[]
  count: number
  potentialMergeCandidates: boolean
}>> => {
  const duplicates: Array<{
    deviceId: string
    userIds: string[]
    count: number
    potentialMergeCandidates: boolean
  }> = []

  try {
    const usersRef = ref(database, USERS_PATH)
    const snapshot = await get(usersRef)

    if (snapshot.exists()) {
      const deviceToUsers: Map<string, string[]> = new Map()

      // Map all users to their device IDs
      snapshot.forEach((userChild) => {
        const userId = userChild.key
        const userData = userChild.val()

        if (userData.devices) {
          Object.entries(userData.devices).forEach(([deviceId, deviceData]: any) => {
            if (!deviceToUsers.has(deviceId)) {
              deviceToUsers.set(deviceId, [])
            }
            deviceToUsers.get(deviceId)!.push(userId)
          })
        }
      })

      // Find devices with multiple users (indication of duplicate accounts)
      deviceToUsers.forEach((userIds, deviceId) => {
        if (userIds.length > 1) {
          // Check if these are merge candidates (same device, similar activity times)
          let potentialMergeCandidates = false

          // Get data for each user to compare
          const userActivityTimes: number[] = []
          userIds.forEach(uid => {
            const userData = snapshot.child(`${uid}`).val()
            if (userData?.lastActivity) {
              userActivityTimes.push(userData.lastActivity)
            }
          })

          // If activity times are within 24 hours, likely same person
          if (userActivityTimes.length > 1) {
            const maxActivity = Math.max(...userActivityTimes)
            const minActivity = Math.min(...userActivityTimes)
            potentialMergeCandidates = (maxActivity - minActivity) < (24 * 60 * 60 * 1000)
          }

          duplicates.push({
            deviceId,
            userIds,
            count: userIds.length,
            potentialMergeCandidates
          })

          console.log(`Found ${userIds.length} users on device ${deviceId}:`, userIds,
            potentialMergeCandidates ? '(Likely duplicates)' : '')
        }
      })
    }

    return duplicates
  } catch (error) {
    console.error('Error detecting duplicate users:', error)
    return []
  }
}

// NEW: Delete detected duplicate users (keep newest, delete rest) - COST OPTIMIZATION
export const deleteDetectedDuplicates = async (): Promise<{
  success: boolean
  deletedCount: number
  devicesProcessed: number
  details: string[]
}> => {
  const details: string[] = []
  let deletedCount = 0
  let devicesProcessed = 0

  try {
    console.log('Starting duplicate user deletion...')
    const duplicates = await detectDuplicateUsersByDevice()

    if (duplicates.length === 0) {
      console.log('No duplicates found to delete')
      return { success: true, deletedCount: 0, devicesProcessed: 0, details: ['No duplicates found'] }
    }

    const usersRef = ref(database, USERS_PATH)

    for (const dup of duplicates) {
      const deletePromises: Promise<void>[] = []

      // Get user data to find newest one (by lastActivity)
      const userActivityMap: Map<string, number> = new Map()

      const snapshot = await get(usersRef)
      if (snapshot.exists()) {
        const allUsers = snapshot.val()

        // Get activity time for each duplicate user
        for (const userId of dup.userIds) {
          const userData = allUsers[userId]
          const lastActivity = userData?.lastActivity || 0
          userActivityMap.set(userId, lastActivity)
        }
      }

      // Sort by activity, keep newest, delete rest
      const sorted = Array.from(userActivityMap.entries())
        .sort(([, a], [, b]) => b - a) // Newest first

      if (sorted.length > 1) {
        const newestUserId = sorted[0][0]
        const oldestUsers = sorted.slice(1)

        details.push(`Device ${dup.deviceId.substring(0, 20)}...: Keeping newest user ${newestUserId.substring(0, 20)}...`)

        // Delete all but newest
        for (const [userId] of oldestUsers) {
          const userRef = ref(database, `${USERS_PATH}/${userId}`)
          deletePromises.push(remove(userRef))
          deletedCount++

          console.log(`Deleting duplicate user: ${userId}`)
          details.push(`  🗑️ Deleted: ${userId.substring(0, 20)}...`)
        }

        devicesProcessed++
      }

      await Promise.all(deletePromises)
    }

    console.log(`Duplicate deletion complete: ${deletedCount} users deleted from ${devicesProcessed} devices`)
    addAdminAuditLog('delete_duplicates', 'system', `Deleted ${deletedCount} duplicate users from ${devicesProcessed} devices`)

    // Send email report for manual duplicate deletion
    const cleanupStats: CleanupStats = {
      outgoingMessages: 0,
      pendingPairings: deletedCount,
      callRequests: 0,
      spamMessages: 0,
      readReceipts: 0,
      oldDevices: devicesProcessed,
      oldNotifications: 0,
      staleTypingIndicators: 0,
      expiredSessions: 0,
      oldFileTransfers: 0,
      abandonedPairings: 0,
      orphanedMedia: 0
    }

    try {
      await sendCleanupReport(cleanupStats, 'admin', 'MANUAL')
      console.log('✅ Duplicate deletion report email sent')
    } catch (emailError) {
      console.error('Failed to send deletion report email:', emailError)
    }

    return { success: true, deletedCount, devicesProcessed, details }
  } catch (error) {
    console.error('Error deleting duplicates:', error)
    return {
      success: false,
      deletedCount: 0,
      devicesProcessed: 0,
      details: [`Error: ${error}`]
    }
  }
}

// NEW: Smart per-user cleanup that respects plan type - COST OPTIMIZATION
export const cleanupUserDataByPlan = async (userId: string): Promise<{
  success: boolean
  itemsCleaned: number
  details: string[]
}> => {
  const details: string[] = []
  let itemsCleaned = 0

  try {
    // Get user's plan
    const usageSnap = await get(ref(database, `${USERS_PATH}/${userId}/${USAGE_PATH}`))
    const usage = usageSnap.exists() ? usageSnap.val() : {}
    const userPlan = usage.plan || 'trial'

    const config = getCleanupConfig(userPlan)
    console.log(`Running cleanup for user ${userId} on plan: ${userPlan}`, config)

    // Run cleanup operations with plan-specific settings
    const oldNotifCount = await cleanupOldNotifications(userId, config.notificationDays)
    if (oldNotifCount > 0) {
      itemsCleaned += oldNotifCount
      details.push(`Removed ${oldNotifCount} old notifications`)
    }

    const oldSpamCount = await cleanupOldSpamMessages(userId, config.spamMessageDays)
    if (oldSpamCount > 0) {
      itemsCleaned += oldSpamCount
      details.push(`Removed ${oldSpamCount} old spam messages`)
    }

    const oldReceiptsCount = await cleanupOldReadReceipts(userId, config.readReceiptDays)
    if (oldReceiptsCount > 0) {
      itemsCleaned += oldReceiptsCount
      details.push(`Removed ${oldReceiptsCount} old read receipts`)
    }

    const oldMediaCount = await cleanupOrphanedMedia(userId, config.orphanedMediaDays)
    if (oldMediaCount > 0) {
      itemsCleaned += oldMediaCount
      details.push(`Removed ${oldMediaCount} orphaned media items`)
    }

    const oldTransfersCount = await cleanupOldFileTransfers(userId, config.fileTransferDays)
    if (oldTransfersCount > 0) {
      itemsCleaned += oldTransfersCount
      details.push(`Removed ${oldTransfersCount} old file transfers`)
    }

    const oldSessionsCount = await cleanupExpiredSessions(userId, config.sessionHours)
    if (oldSessionsCount > 0) {
      itemsCleaned += oldSessionsCount
      details.push(`Removed ${oldSessionsCount} expired sessions`)
    }

    const typingCount = await cleanupTypingIndicators(userId, config.typingIndicatorMinutes)
    if (typingCount > 0) {
      itemsCleaned += typingCount
      details.push(`Removed ${typingCount} stale typing indicators`)
    }

    details.push(`Total items cleaned: ${itemsCleaned}`)

    return {
      success: true,
      itemsCleaned,
      details
    }
  } catch (error) {
    console.error(`Error cleaning up user data for ${userId}:`, error)
    return {
      success: false,
      itemsCleaned: 0,
      details: [`Error during cleanup: ${error}`]
    }
  }
}

// Delete users with no devices (orphaned accounts that can't access messages) - uses deleteUserAccount for R2 cleanup
export const deleteUsersWithoutDevices = async (): Promise<{
  success: boolean
  deletedCount: number
  r2FilesDeleted: number
  details: string[]
}> => {
  const details: string[] = []
  let deletedCount = 0
  let r2FilesDeleted = 0

  try {
    console.log('Starting deletion of users without devices...')
    const usersRef = ref(database, USERS_PATH)
    const snapshot = await get(usersRef)

    if (snapshot.exists()) {
      const allUsers = snapshot.val()

      for (const [userId, userData] of Object.entries(allUsers)) {
        const user = userData as any
        // Check if user has no devices
        if (!user.devices || Object.keys(user.devices).length === 0) {
          try {
            // Use deleteUserAccount which handles R2 cleanup
            const result = await deleteUserAccount(userId)
            if (result.success) {
              deletedCount++
              r2FilesDeleted += result.deletedData.r2Files
              details.push(`✓ Deleted user ${userId.substring(0, 20)}... (no devices, ${result.deletedData.r2Files} R2 files)`)
              console.log(`Deleted user without devices: ${userId}`)
            } else {
              details.push(`✗ Failed to delete ${userId.substring(0, 20)}...: ${result.errors.join(', ')}`)
            }
          } catch (deleteError) {
            details.push(`✗ Failed to delete ${userId.substring(0, 20)}...: ${deleteError}`)
            console.error(`Failed to delete user ${userId}:`, deleteError)
          }
        }
      }
    }

    console.log(`Deleted ${deletedCount} users without devices, ${r2FilesDeleted} R2 files`)
    addAdminAuditLog('delete_users_no_devices', 'system', `Deleted ${deletedCount} users without devices, ${r2FilesDeleted} R2 files`)

    return { success: true, deletedCount, r2FilesDeleted, details }
  } catch (error) {
    console.error('Error deleting users without devices:', error)
    return { success: false, deletedCount: 0, r2FilesDeleted: 0, details: [`Error: ${error}`] }
  }
}

// Delete old messages based on retention policy
export const deleteOldMessages = async (plan: string | null = null): Promise<{
  success: boolean
  messagesDeleted: number
  detailsByUser: { [userId: string]: number }
}> => {
  const detailsByUser: { [userId: string]: number } = {}
  let totalDeleted = 0

  try {
    console.log('Starting old message cleanup...')
    const config = getCleanupConfig(plan)
    const retentionMs = config.messageRetentionDays * 24 * 60 * 60 * 1000
    const cutoffTime = Date.now() - retentionMs

    const usersRef = ref(database, USERS_PATH)
    const snapshot = await get(usersRef)

    if (snapshot.exists()) {
      const allUsers = snapshot.val()

      for (const [userId] of Object.entries(allUsers)) {
        try {
          const messagesRef = ref(database, `${USERS_PATH}/${userId}/messages`)
          const messagesSnapshot = await get(messagesRef)

          let userDeleted = 0
          if (messagesSnapshot.exists()) {
            const messages = messagesSnapshot.val()

            for (const [messageId, messageData] of Object.entries(messages)) {
              const msg = messageData as any
              if (msg.timestamp && msg.timestamp < cutoffTime) {
                const msgRef = ref(database, `${USERS_PATH}/${userId}/messages/${messageId}`)
                await remove(msgRef)
                userDeleted++
                totalDeleted++
              }
            }
          }

          if (userDeleted > 0) {
            detailsByUser[userId] = userDeleted
            console.log(`Deleted ${userDeleted} old messages for user ${userId}`)
          }
        } catch (userError) {
          console.error(`Error cleaning messages for user ${userId}:`, userError)
        }
      }
    }

    console.log(`Total old messages deleted: ${totalDeleted}`)
    addAdminAuditLog('delete_old_messages', 'system', `Deleted ${totalDeleted} old messages`)

    return { success: true, messagesDeleted: totalDeleted, detailsByUser }
  } catch (error) {
    console.error('Error deleting old messages:', error)
    return { success: false, messagesDeleted: 0, detailsByUser: {} }
  }
}

// Aggressively delete MMS messages to save storage (MMS is expensive - ~2MB per message) - includes R2 cleanup
export const deleteOldMmsMessages = async (plan: string | null = null): Promise<{
  success: boolean
  mmsDeleted: number
  r2FilesDeleted: number
  storageSavedMB: number
  detailsByUser: { [userId: string]: number }
}> => {
  const detailsByUser: { [userId: string]: number } = {}
  let totalDeleted = 0
  let r2FilesDeleted = 0
  let estimatedSavedMB = 0

  try {
    console.log('Starting aggressive MMS cleanup...')
    const config = getCleanupConfig(plan)
    const mmsRetentionMs = config.mmsRetentionDays * 24 * 60 * 60 * 1000
    const mmssCutoffTime = Date.now() - mmsRetentionMs
    const deleteR2FileFn = httpsCallable(functions, 'deleteR2File')

    const usersRef = ref(database, USERS_PATH)
    const snapshot = await get(usersRef)

    if (snapshot.exists()) {
      const allUsers = snapshot.val()

      for (const [userId] of Object.entries(allUsers)) {
        try {
          const messagesRef = ref(database, `${USERS_PATH}/${userId}/messages`)
          const messagesSnapshot = await get(messagesRef)

          let userDeleted = 0
          if (messagesSnapshot.exists()) {
            const messages = messagesSnapshot.val()

            for (const [messageId, messageData] of Object.entries(messages)) {
              const msg = messageData as any
              // Delete MMS messages (type='mms') older than retention period
              if (msg.type === 'mms' && msg.timestamp && msg.timestamp < mmssCutoffTime) {
                // Delete R2 files for MMS attachments
                if (msg.attachments && Array.isArray(msg.attachments)) {
                  for (const attachment of msg.attachments) {
                    if (attachment.r2Key) {
                      try {
                        await deleteR2FileFn({ r2Key: attachment.r2Key })
                        r2FilesDeleted++
                      } catch (err: any) {
                        console.warn(`Failed to delete R2 file ${attachment.r2Key}:`, err.message)
                      }
                    }
                  }
                }
                // Also check for r2Key directly on the message
                if (msg.r2Key) {
                  try {
                    await deleteR2FileFn({ r2Key: msg.r2Key })
                    r2FilesDeleted++
                  } catch (err: any) {
                    console.warn(`Failed to delete R2 file ${msg.r2Key}:`, err.message)
                  }
                }

                const msgRef = ref(database, `${USERS_PATH}/${userId}/messages/${messageId}`)
                await remove(msgRef)
                userDeleted++
                totalDeleted++
                estimatedSavedMB += 2 // Estimate ~2MB per MMS with attachments
              }
            }
          }

          if (userDeleted > 0) {
            detailsByUser[userId] = userDeleted
            console.log(`Deleted ${userDeleted} old MMS messages for user ${userId}`)
          }
        } catch (userError) {
          console.error(`Error cleaning MMS for user ${userId}:`, userError)
        }
      }
    }

    console.log(`Total MMS deleted: ${totalDeleted}, R2 files: ${r2FilesDeleted}, Estimated storage saved: ${estimatedSavedMB}MB`)
    addAdminAuditLog('delete_old_mms', 'system', `Deleted ${totalDeleted} MMS messages, ${r2FilesDeleted} R2 files, saved ~${estimatedSavedMB}MB`)

    return { success: true, mmsDeleted: totalDeleted, r2FilesDeleted, storageSavedMB: estimatedSavedMB, detailsByUser }
  } catch (error) {
    console.error('Error deleting old MMS:', error)
    return { success: false, mmsDeleted: 0, r2FilesDeleted: 0, storageSavedMB: 0, detailsByUser: {} }
  }
}

// Remove non-SMS messages for free tier users (storage optimization)
export const enforceSmsFreeMessages = async (): Promise<{
  success: boolean
  messagesRemoved: number
  usersProcessed: number
}> => {
  let totalRemoved = 0
  let usersProcessed = 0

  try {
    console.log('Starting SMS-only enforcement for free tier...')
    const usersRef = ref(database, USERS_PATH)
    const snapshot = await get(usersRef)

    if (snapshot.exists()) {
      const allUsers = snapshot.val()

      for (const [userId, userData] of Object.entries(allUsers)) {
        const user = userData as any
        const userPlan = user.plan || null
        const config = getCleanupConfig(userPlan)

        // Only enforce SMS-only for free tier
        if (config.smsOnly) {
          usersProcessed++
          try {
            const messagesRef = ref(database, `${USERS_PATH}/${userId}/messages`)
            const messagesSnapshot = await get(messagesRef)

            if (messagesSnapshot.exists()) {
              const messages = messagesSnapshot.val()

              for (const [messageId, messageData] of Object.entries(messages)) {
                const msg = messageData as any
                // Remove non-SMS messages (calls, media, etc.)
                if (msg.type && msg.type !== 'sms' && msg.type !== 'text') {
                  const msgRef = ref(database, `${USERS_PATH}/${userId}/messages/${messageId}`)
                  await remove(msgRef)
                  totalRemoved++
                  console.log(`Removed non-SMS message ${messageId} for free user ${userId}`)
                }
              }
            }
          } catch (userError) {
            console.error(`Error processing free user ${userId}:`, userError)
          }
        }
      }
    }

    console.log(`SMS enforcement complete: ${totalRemoved} non-SMS messages removed from ${usersProcessed} free users`)
    addAdminAuditLog('enforce_sms_free', 'system', `Removed ${totalRemoved} non-SMS messages from ${usersProcessed} free users`)

    return { success: true, messagesRemoved: totalRemoved, usersProcessed }
  } catch (error) {
    console.error('Error enforcing SMS-only for free tier:', error)
    return { success: false, messagesRemoved: 0, usersProcessed: 0 }
  }
}

// NEW: Global cleanup with plan-aware strategies - COST OPTIMIZATION
export const runSmartGlobalCleanup = async (): Promise<{
  usersProcessed: number
  totalItemsCleaned: number
  emptyNodesDeleted: number
  orphanedUsersDeleted: number
  duplicatesDetected: number
}> => {
  const results = {
    usersProcessed: 0,
    totalItemsCleaned: 0,
    emptyNodesDeleted: 0,
    orphanedUsersDeleted: 0,
    duplicatesDetected: 0
  }

  try {
    console.log('Starting smart global cleanup...')

    // Get all users
    const usersRef = ref(database, USERS_PATH)
    const snapshot = await get(usersRef)

    if (snapshot.exists()) {
      // Cleanup each user based on their plan
      for (const [userId] of Object.entries(snapshot.val())) {
        const cleanup = await cleanupUserDataByPlan(userId)
        if (cleanup.success) {
          results.usersProcessed++
          results.totalItemsCleaned += cleanup.itemsCleaned
        }
      }
    }

    // Clean empty nodes (plan: free, days: 1)
    const emptyDeleted = await cleanupEmptyUserNodes(CLEANUP_CONFIG.free.emptyNodeDays)
    results.emptyNodesDeleted = emptyDeleted

    // Clean orphaned users (plan: free, days: 14)
    const orphanedDeleted = await cleanupOrphanedUsers(CLEANUP_CONFIG.free.inactiveUserDays)
    results.orphanedUsersDeleted = orphanedDeleted

    // Detect duplicates (don't auto-delete, just report)
    const duplicates = await detectDuplicateUsersByDevice()
    results.duplicatesDetected = duplicates.filter(d => d.potentialMergeCandidates).length

    console.log('Smart global cleanup completed:', results)
    addAdminAuditLog('global_cleanup_complete', 'system', JSON.stringify(results))

    // Email report is sent by the caller (route handler) to avoid duplicates
    return results
  } catch (error) {
    console.error('Error in smart global cleanup:', error)
    return results
  }
}

// Get system-wide cleanup overview (requires admin privileges)
export const getSystemCleanupOverview = async (): Promise<any> => {
  try {
    const getSystemCleanupOverviewFn = httpsCallable(functions, 'getSystemCleanupOverview')
    const result = await getSystemCleanupOverviewFn()
    return result.data
  } catch (error) {
    console.error('Error getting system cleanup overview:', error)
    // Return placeholder on error
    return {
      totalUsers: 0,
      totalCleanupItems: 0,
      breakdown: {},
      topUsersByCleanup: [],
      systemHealth: {
        databaseSize: "unknown",
        lastCleanup: Date.now(),
        cleanupFrequency: "daily"
      }
    }
  }
}

// Run all auto-cleanup tasks
export const runAutoCleanup = async (userId: string): Promise<CleanupStats> => {
  try {
    const triggerCleanupFn = httpsCallable(functions, 'triggerCleanup')
    const result = await triggerCleanupFn({ userId })
    return result.data as CleanupStats
  } catch (error) {
    console.error('Error running auto cleanup:', error)
    // Return zeros on error
    return {
      outgoingMessages: 0,
      pendingPairings: 0,
      callRequests: 0,
      spamMessages: 0,
      readReceipts: 0,
      oldDevices: 0,
      oldNotifications: 0,
      staleTypingIndicators: 0,
      expiredSessions: 0,
      oldFileTransfers: 0,
      abandonedPairings: 0,
      orphanedMedia: 0
    }
  }
}


// Get all users for admin cleanup (requires admin privileges)
export const getAllUserIds = async (): Promise<string[]> => {
  const userIds: string[] = []
  try {
    const usersRef = ref(database, USERS_PATH)
    const snapshot = await get(usersRef)
    if (snapshot.exists()) {
      snapshot.forEach((child) => {
        if (child.key) userIds.push(child.key)
      })
    }
  } catch (error) {
    console.error('Error getting user IDs:', error)
  }
  return userIds
}

export const getStorageEstimate = async (userId: string): Promise<{
  messagesCount: number
  attachmentsCount: number
  estimatedSizeMB: number
}> => {
  try {
    const usage = await getUsageSummary(userId)

    // Estimate message count and attachments from usage data
    // This is an approximation since we don't have direct counts
    const messagesCount = Math.floor(usage.monthlyUsedBytes / 1000) // Rough estimate: ~1KB per message
    const attachmentsCount = Math.floor((usage.mmsBytes + usage.fileBytes) / (50 * 1024)) // Rough estimate: ~50KB per attachment
    const estimatedSizeMB = Math.round(usage.storageUsedBytes / (1024 * 1024) * 100) / 100

    return {
      messagesCount: messagesCount || 0,
      attachmentsCount: attachmentsCount || 0,
      estimatedSizeMB: estimatedSizeMB || 0,
    }
  } catch (error) {
    console.error('Error getting storage estimate:', error)
    return {
      messagesCount: 0,
      attachmentsCount: 0,
      estimatedSizeMB: 0,
    }
  }
}

export const getUserDataSummary = async (userId: string): Promise<{
  messagesCount: number
  devicesCount: number
  lastActivity?: number
  hasData: boolean
} | null> => {
  try {
    const userRef = ref(database, `${USERS_PATH}/${userId}`)
    const snapshot = await get(userRef)
    if (!snapshot.exists()) {
      return { messagesCount: 0, devicesCount: 0, hasData: false }
    }

    const data = snapshot.val()
    console.log(`User ${userId} data:`, data) // Debug log

    let messagesCount = 0
    let devicesCount = 0
    let lastActivity: number | undefined

    // Check for messages in different possible locations
    if (data.messages && typeof data.messages === 'object') {
      const messageKeys = Object.keys(data.messages)
      messagesCount = messageKeys.length
      console.log(`User ${userId} has ${messagesCount} messages`)

      // Find last activity - check first few messages for timestamp structure
      if (messagesCount > 0) {
        const sampleMessage = data.messages[messageKeys[0]]
        console.log(`Sample message structure:`, sampleMessage)

        const timestamps = Object.values(data.messages)
          .map((msg: any) => {
            // Try different timestamp field names
            return msg.timestamp || msg.date || msg.time || msg.syncedAt
          })
          .filter((ts: any) => ts && typeof ts === 'number')

        if (timestamps.length > 0) {
          lastActivity = Math.max(...timestamps)
          console.log(`Last activity timestamp: ${lastActivity}`)
        }
      }
    } else {
      console.log(`User ${userId} has no messages object or invalid structure`)
    }

    // Check for devices
    if (data.devices && typeof data.devices === 'object') {
      devicesCount = Object.keys(data.devices).length
      console.log(`User ${userId} has ${devicesCount} devices`)
    } else {
      console.log(`User ${userId} has no devices object or invalid structure`)
    }

    // Also check for other possible data structures
    if (data.contacts) {
      // User has contacts data
    }

    const result = {
      messagesCount,
      devicesCount,
      lastActivity,
      hasData: messagesCount > 0 || devicesCount > 0 || !!data.contacts || !!data.usage
    }

    console.log(`User ${userId} summary:`, result) // Debug log
    return result
  } catch (error) {
    console.error(`Error getting user data for ${userId}:`, error)
    return { messagesCount: 0, devicesCount: 0, hasData: false }
  }
}

// ============================================
// ADMIN FUNCTIONS - Comprehensive System Management
// ============================================

// Simple cache for admin data (5 minute TTL)
const adminCache: {
  systemOverview?: { data: any; timestamp: number }
  detailedUsers?: { data: any; timestamp: number }
} = {}
const ADMIN_CACHE_TTL = 5 * 60 * 1000 // 5 minutes

// Clear admin cache (call when force refreshing)
export const clearAdminCache = () => {
  delete adminCache.systemOverview
  delete adminCache.detailedUsers
  console.log('Admin cache cleared')
}

// Admin System Overview - OPTIMIZED: single pass, no per-user fetches
export const getSystemOverview = async (): Promise<{
  totalUsers: number
  activeUsers: number
  totalMessages: number
  totalStorageMB: number
  databaseSize: number
  firebaseCosts: {
    estimatedMonthly: number
    breakdown: {
      database: number
      storage: number
      functions: number
    }
  }
  systemHealth: {
    status: 'healthy' | 'warning' | 'critical'
    issues: string[]
  }
}> => {
  // Check cache first
  if (adminCache.systemOverview && Date.now() - adminCache.systemOverview.timestamp < ADMIN_CACHE_TTL) {
    console.log('Returning cached system overview')
    return adminCache.systemOverview.data
  }

  try {
    const usersRef = ref(database, USERS_PATH)
    const usersSnapshot = await get(usersRef)

    let totalUsers = 0
    let activeUsers = 0
    let totalMessages = 0
    let totalStorageMB = 0
    const systemIssues: string[] = []

    if (usersSnapshot.exists()) {
      const users = usersSnapshot.val()
      const userIds = Object.keys(users)
      totalUsers = userIds.length
      const thirtyDaysAgo = Date.now() - (30 * 24 * 60 * 60 * 1000)

      // Single pass through all users - no additional fetches
      for (const userData of Object.values(users)) {
        const data = userData as any

        // Count messages
        if (data.messages) {
          const messageCount = Object.keys(data.messages).length
          totalMessages += messageCount

          // Check activity - only look at first few messages for speed
          const messageValues = Object.values(data.messages).slice(0, 50) as any[]
          const hasRecent = messageValues.some(msg =>
            (msg.timestamp || msg.date) > thirtyDaysAgo
          )
          if (hasRecent) activeUsers++
        }

        // Estimate storage from usage node if available (no extra fetch)
        if (data.usage?.storageBytes) {
          totalStorageMB += data.usage.storageBytes / (1024 * 1024)
        } else if (data.messages) {
          // Rough estimate: 500 bytes per message
          totalStorageMB += Object.keys(data.messages).length * 0.0005
        }
      }
    }

    // Estimate Firebase costs
    const databaseCost = Math.max(0, (totalMessages * 0.000001) + (totalUsers * 0.005))
    const storageCost = Math.max(0, totalStorageMB * 0.026)
    const functionsCost = 5
    const totalMonthly = databaseCost + storageCost + functionsCost

    // System health check
    let status: 'healthy' | 'warning' | 'critical' = 'healthy'
    if (totalStorageMB > 1000) {
      status = 'warning'
      systemIssues.push('High storage usage detected')
    }
    if (totalMessages > 100000) {
      status = 'warning'
      systemIssues.push('High message volume detected')
    }
    if (totalMonthly > 50) {
      status = 'critical'
      systemIssues.push('High monthly costs projected')
    }

    const result = {
      totalUsers,
      activeUsers,
      totalMessages,
      totalStorageMB: Math.round(totalStorageMB * 100) / 100,
      databaseSize: totalMessages,
      firebaseCosts: {
        estimatedMonthly: Math.round(totalMonthly * 100) / 100,
        breakdown: {
          database: Math.round(databaseCost * 100) / 100,
          storage: Math.round(storageCost * 100) / 100,
          functions: functionsCost
        }
      },
      systemHealth: {
        status,
        issues: systemIssues
      }
    }

    // Cache the result
    adminCache.systemOverview = { data: result, timestamp: Date.now() }
    return result
  } catch (error) {
    console.error('Error getting system overview:', error)
    return {
      totalUsers: 0,
      activeUsers: 0,
      totalMessages: 0,
      totalStorageMB: 0,
      databaseSize: 0,
      firebaseCosts: {
        estimatedMonthly: 0,
        breakdown: { database: 0, storage: 0, functions: 0 }
      },
      systemHealth: {
        status: 'critical',
        issues: ['Unable to load system data']
      }
    }
  }
}

// Device-Based Subscription Tracking (tracks users across UID changes)
export const trackSubscriptionByDevice = async (
  deviceId: string,
  userId: string,
  plan: string,
  planExpiresAt: number | null
) => {
  try {
    const now = Date.now()
    const accountRef = ref(database, `subscription_accounts/${deviceId}`)
    const accountSnapshot = await get(accountRef)

    // Get previous plan
    let previousPlan = 'free'
    if (accountSnapshot.exists()) {
      previousPlan = accountSnapshot.child('currentPlan').val() || 'free'
    }

    // Build update object
    const updates: any = {
      currentUid: userId,
      currentPlan: plan,
      [`history/${now}`]: {
        uid: userId,
        plan: plan,
        createdAt: now,
        status: 'active'
      }
    }

    // Track if this is a premium plan
    const isPremium = ['monthly', 'yearly', 'lifetime'].includes(plan)
    if (isPremium) {
      updates.wasPremium = true
      if (!accountSnapshot.exists() || !accountSnapshot.child('firstPremiumDate').exists()) {
        updates.firstPremiumDate = now
      }
    }

    await update(accountRef, updates)
    console.log(`[Device ${deviceId}] Subscription tracked for user ${userId}, plan: ${plan}`)
  } catch (error) {
    console.error(`Error tracking subscription by device:`, error)
  }
}

export const getSubscriptionByDevice = async (deviceId: string) => {
  try {
    const accountRef = ref(database, `subscription_accounts/${deviceId}`)
    const snapshot = await get(accountRef)

    if (!snapshot.exists()) {
      return null
    }

    const data = snapshot.val()
    return {
      currentUid: data.currentUid,
      currentPlan: data.currentPlan,
      wasPremium: data.wasPremium || false,
      firstPremiumDate: data.firstPremiumDate || null,
      totalPremiumDays: data.totalPremiumDays || 0,
      history: data.history || {}
    }
  } catch (error) {
    console.error(`Error getting subscription by device:`, error)
    return null
  }
}

export const markAccountDeletedByDevice = async (deviceId: string, userId: string) => {
  try {
    const now = Date.now()
    const accountRef = ref(database, `subscription_accounts/${deviceId}`)
    const accountSnapshot = await get(accountRef)

    if (accountSnapshot.exists()) {
      // Find the history entry for this user and mark it as deleted
      const history = accountSnapshot.child('history').val() || {}

      for (const [timestamp, entry] of Object.entries(history)) {
        const historyEntry = entry as any
        if (historyEntry.uid === userId && historyEntry.status === 'active') {
          await update(ref(database, `subscription_accounts/${deviceId}/history/${timestamp}`), {
            deletedAt: now,
            status: 'deleted'
          })
          console.log(`[Device ${deviceId}] Marked user ${userId} as deleted in subscription history`)
          break
        }
      }
    }
  } catch (error) {
    console.error(`Error marking account deleted by device:`, error)
  }
}

// Admin User Management - OPTIMIZED: batch fetch subscriptions, no per-user DB calls
export const getDetailedUserList = async (): Promise<Array<{
  userId: string
  messagesCount: number
  devicesCount: number
  storageUsedMB: number
  lastActivity: number | null
  plan: string
  planExpiresAt: number | null
  planAssignedAt: number | null
  planAssignedBy: string
  wasPremium: boolean
  isActive: boolean
}>> => {
  // Check cache first
  if (adminCache.detailedUsers && Date.now() - adminCache.detailedUsers.timestamp < ADMIN_CACHE_TTL) {
    console.log('Returning cached user list')
    return adminCache.detailedUsers.data
  }

  try {
    // Fetch users and subscription_records in parallel (2 calls instead of 2*N)
    const [usersSnapshot, subscriptionsSnapshot] = await Promise.all([
      get(ref(database, USERS_PATH)),
      get(ref(database, 'subscription_records'))
    ])

    if (!usersSnapshot.exists()) return []

    const users = []
    const userData = usersSnapshot.val()
    const subscriptions = subscriptionsSnapshot.exists() ? subscriptionsSnapshot.val() : {}
    const thirtyDaysAgo = Date.now() - (30 * 24 * 60 * 60 * 1000)

    for (const [userId, data] of Object.entries(userData)) {
      const userInfo = data as any
      const subscriptionRecord = subscriptions[userId]?.active || null

      // Count devices
      const devicesCount = userInfo.devices ? Object.keys(userInfo.devices).length : 0

      // Count messages and check activity - sample first 100 for speed
      let lastActivity: number | null = null
      let isActive = false
      let actualMessageCount = 0
      let storageUsedMB = 0

      if (userInfo.messages) {
        const messageKeys = Object.keys(userInfo.messages)
        actualMessageCount = messageKeys.length

        // Sample messages for activity check (don't iterate all)
        const sampleSize = Math.min(100, messageKeys.length)
        const sampleKeys = messageKeys.slice(-sampleSize) // Get most recent
        for (const key of sampleKeys) {
          const msg = userInfo.messages[key]
          const timestamp = msg?.timestamp || msg?.date
          if (timestamp) {
            if (timestamp > thirtyDaysAgo) isActive = true
            if (!lastActivity || timestamp > lastActivity) lastActivity = timestamp
          }
        }
      }

      // Get storage from usage node (already in user data, no extra fetch)
      if (userInfo.usage?.storageBytes) {
        storageUsedMB = Math.round(userInfo.usage.storageBytes / (1024 * 1024) * 100) / 100
      } else {
        // Rough estimate: 500 bytes per message
        storageUsedMB = Math.round(actualMessageCount * 0.0005 * 100) / 100
      }

      // Get plan: prioritize subscription_records, fallback to inline usage
      let plan = 'Trial'
      let planExpiresAt: number | null = null
      let planAssignedAt: number | null = null
      let planAssignedBy = 'unknown'
      let wasPremium = false

      if (subscriptionRecord?.plan) {
        plan = subscriptionRecord.plan === 'free' ? 'Trial' :
               subscriptionRecord.plan.charAt(0).toUpperCase() + subscriptionRecord.plan.slice(1)
        planExpiresAt = subscriptionRecord.planExpiresAt || null
        planAssignedAt = subscriptionRecord.planAssignedAt || null
        planAssignedBy = subscriptionRecord.planAssignedBy || 'system'
        wasPremium = subscriptionRecord.wasPremium === true || (subscriptionRecord.plan && subscriptionRecord.plan !== 'free')
      } else if (userInfo.usage?.plan && userInfo.usage.plan !== 'free') {
        // Fallback to inline usage data
        plan = userInfo.usage.plan.charAt(0).toUpperCase() + userInfo.usage.plan.slice(1)
        planExpiresAt = userInfo.usage.planExpiresAt || null
        planAssignedBy = 'storekit'
        wasPremium = true
      }

      users.push({
        userId,
        messagesCount: actualMessageCount,
        devicesCount,
        storageUsedMB,
        lastActivity,
        plan,
        planExpiresAt,
        planAssignedAt,
        planAssignedBy,
        wasPremium,
        isActive
      })
    }

    const result = users.sort((a, b) => (b.lastActivity || 0) - (a.lastActivity || 0))

    // Cache the result
    adminCache.detailedUsers = { data: result, timestamp: Date.now() }
    return result
  } catch (error) {
    console.error('Error getting detailed user list:', error)
    return []
  }
}

// Admin Analytics
export const getSystemAnalytics = async (): Promise<{
  dailyActiveUsers: number[]
  messageVolume: number[]
  storageGrowth: number[]
  costTrends: number[]
  topUsers: Array<{userId: string, messages: number, storage: number}>
}> => {
  try {
    const users = await getDetailedUserList()

    // Calculate metrics for last 30 days
    const dailyActiveUsers = []
    const messageVolume = []
    const storageGrowth = []
    const costTrends = []

    // Mock data for now - in real implementation, you'd track historical data
    for (let i = 29; i >= 0; i--) {
      const date = new Date()
      date.setDate(date.getDate() - i)

      // Simple calculations based on current data
      dailyActiveUsers.push(Math.floor(users.filter(u => u.isActive).length * (0.7 + Math.random() * 0.3)))
      messageVolume.push(Math.floor(users.reduce((sum, u) => sum + u.messagesCount, 0) / 30))
      storageGrowth.push(Math.floor(users.reduce((sum, u) => sum + u.storageUsedMB, 0) * (0.8 + Math.random() * 0.4)))
      costTrends.push(Math.floor((storageGrowth[storageGrowth.length - 1] * 0.026) + (messageVolume[messageVolume.length - 1] * 0.000001)))
    }

    // Top users by activity
    const topUsers = users
      .sort((a, b) => b.messagesCount - a.messagesCount)
      .slice(0, 10)
      .map(u => ({
        userId: u.userId,
        messages: u.messagesCount,
        storage: u.storageUsedMB
      }))

    return {
      dailyActiveUsers,
      messageVolume,
      storageGrowth,
      costTrends,
      topUsers
    }
  } catch (error) {
    console.error('Error getting system analytics:', error)
    return {
      dailyActiveUsers: [],
      messageVolume: [],
      storageGrowth: [],
      costTrends: [],
      topUsers: []
    }
  }
}

// Admin Security & Audit
export const addAdminAuditLog = (action: string, userId: string, details: string, ip?: string) => {
  // In a real implementation, this would store to a database
  // For now, we'll just log to console
  const logEntry = {
    timestamp: Date.now(),
    action,
    userId,
    details,
    ip: ip || 'web-admin'
  }
  console.log('Admin Audit Log:', logEntry)
}

export const getAdminAuditLog = async (): Promise<Array<{
  timestamp: number
  action: string
  userId: string
  details: string
  ip?: string
}>> => {
  try {
    // In a real implementation, you'd store admin actions in a separate collection
    // For now, return mock data
    const mockActions = [
      {
        timestamp: Date.now() - (2 * 60 * 60 * 1000), // 2 hours ago
        action: 'admin_login',
        userId: 'admin',
        details: 'Admin login successful',
        ip: '192.168.1.1'
      },
      {
        timestamp: Date.now() - (5 * 60 * 60 * 1000), // 5 hours ago
        action: 'cleanup_run',
        userId: 'system',
        details: 'Auto cleanup completed - 1,247 records deleted',
        ip: 'system'
      },
      {
        timestamp: Date.now() - (24 * 60 * 60 * 1000), // 1 day ago
        action: 'user_cleanup',
        userId: 'admin',
        details: 'Cleaned inactive user data for user_123',
        ip: '192.168.1.1'
      }
    ]

    return mockActions
  } catch (error) {
    console.error('Error getting admin audit log:', error)
    return []
  }
}

// Individual User Deletion - includes R2 file cleanup
export const deleteUserAccount = async (userId: string): Promise<{
  success: boolean
  deletedData: {
    messages: number
    devices: number
    storageMB: number
    r2Files: number
  }
  errors: string[]
}> => {
  try {
    const errors: string[] = []
    let messagesDeleted = 0
    let devicesDeleted = 0
    let storageFreed = 0
    let r2FilesDeleted = 0

    // Get actual message count before deletion
    console.log(`Starting deletion of user: ${userId}`)
    try {
      const userRef = ref(database, `${USERS_PATH}/${userId}`)
      const userSnapshot = await get(userRef)

      if (userSnapshot.exists()) {
        const userData = userSnapshot.val()
        console.log(`User data found:`, Object.keys(userData))

        // Count actual messages
        if (userData.messages) {
          messagesDeleted = Object.keys(userData.messages).length
          console.log(`Found ${messagesDeleted} messages for user ${userId}`)
        } else {
          console.log(`No messages found for user ${userId}`)
        }

        // Count devices
        if (userData.devices) {
          devicesDeleted = Object.keys(userData.devices).length
          console.log(`Found ${devicesDeleted} devices for user ${userId}`)
        }

        // Clean up R2 files (file_transfers and media) before deleting user
        const deleteR2FileFn = httpsCallable(functions, 'deleteR2File')
        const r2DeletePromises: Promise<any>[] = []

        // Delete R2 files from file_transfers
        if (userData.file_transfers) {
          Object.values(userData.file_transfers).forEach((transfer: any) => {
            if (transfer.r2Key) {
              r2DeletePromises.push(
                deleteR2FileFn({ r2Key: transfer.r2Key })
                  .then(() => { r2FilesDeleted++ })
                  .catch((err: any) => console.warn(`Failed to delete R2 file ${transfer.r2Key}:`, err.message))
              )
            }
          })
        }

        // Delete R2 files from media
        if (userData.media) {
          Object.values(userData.media).forEach((media: any) => {
            if (media.r2Key) {
              r2DeletePromises.push(
                deleteR2FileFn({ r2Key: media.r2Key })
                  .then(() => { r2FilesDeleted++ })
                  .catch((err: any) => console.warn(`Failed to delete R2 media file ${media.r2Key}:`, err.message))
              )
            }
          })
        }

        // Wait for all R2 deletions to complete
        if (r2DeletePromises.length > 0) {
          console.log(`Deleting ${r2DeletePromises.length} R2 files for user ${userId}...`)
          await Promise.all(r2DeletePromises)
          console.log(`Deleted ${r2FilesDeleted} R2 files for user ${userId}`)
        }
      } else {
        console.log(`User ${userId} not found in database`)
      }
    } catch (countError) {
      console.warn('Error counting user data:', countError)
      // Try fallback method
      try {
        const userSummary = await getUserDataSummary(userId)
        if (userSummary) {
          messagesDeleted = userSummary.messagesCount
        }
      } catch (summaryError) {
        console.warn('Fallback counting also failed:', summaryError)
      }
    }

    // Delete all user data
    const userRef = ref(database, `${USERS_PATH}/${userId}`)

    // Get storage usage before deletion
    try {
      const usage = await getUsageSummary(userId)
      storageFreed = usage.storageUsedBytes / (1024 * 1024)
    } catch (error) {
      console.warn('Could not get usage summary for storage calculation:', error)
    }

    // Delete the entire user node
    await remove(userRef)

    // Log the deletion in audit
    addAdminAuditLog('user_delete', 'admin', `Deleted user account: ${userId} (${messagesDeleted} messages, ${r2FilesDeleted} R2 files, ${storageFreed.toFixed(2)}MB storage)`)

    return {
      success: true,
      deletedData: {
        messages: messagesDeleted,
        devices: devicesDeleted,
        storageMB: Math.round(storageFreed * 100) / 100,
        r2Files: r2FilesDeleted
      },
      errors
    }
  } catch (error) {
    console.error('Error deleting user account:', error)
    return {
      success: false,
      deletedData: {
        messages: 0,
        devices: 0,
        storageMB: 0,
        r2Files: 0
      },
      errors: [`Failed to delete user ${userId}: ${error}`]
    }
  }
}

// Bulk User Operations - uses deleteUserAccount which handles R2 cleanup
export const bulkDeleteInactiveUsers = async (inactiveDays: number = 90): Promise<{
  deletedUsers: number
  freedStorageMB: number
  r2FilesDeleted: number
  errors: string[]
}> => {
  try {
    const users = await getDetailedUserList()
    const cutoffTime = Date.now() - (inactiveDays * 24 * 60 * 60 * 1000)

    let deletedUsers = 0
    let freedStorageMB = 0
    let r2FilesDeleted = 0
    const errors: string[] = []

    for (const user of users) {
      if (!user.lastActivity || user.lastActivity < cutoffTime) {
        try {
          // Use deleteUserAccount which handles R2 cleanup
          const result = await deleteUserAccount(user.userId)
          if (result.success) {
            deletedUsers++
            freedStorageMB += result.deletedData.storageMB
            r2FilesDeleted += result.deletedData.r2Files
          } else {
            errors.push(...result.errors)
          }
        } catch (error) {
          errors.push(`Failed to delete user ${user.userId}: ${error}`)
        }
      }
    }

    return { deletedUsers, freedStorageMB, r2FilesDeleted, errors }
  } catch (error) {
    console.error('Error in bulk delete:', error)
    return { deletedUsers: 0, freedStorageMB: 0, r2FilesDeleted: 0, errors: ['Bulk delete failed'] }
  }
}

// Cost Optimization Recommendations
export const getCostOptimizationRecommendations = async (): Promise<Array<{
  type: 'storage' | 'database' | 'cleanup' | 'scaling'
  priority: 'high' | 'medium' | 'low'
  title: string
  description: string
  potentialSavings: number
  action: string
}>> => {
  try {
    console.log('Starting cost optimization analysis...')
    console.log('Current auth state:', await waitForAuth())

    const overview = await getSystemOverview()
    console.log('System overview:', overview)

    const recommendations: Array<{
      type: 'storage' | 'database' | 'cleanup' | 'scaling'
      priority: 'high' | 'medium' | 'low'
      title: string
      description: string
      potentialSavings: number
      action: string
    }> = []

    // Storage optimization
    if (overview.totalStorageMB > 500) {
      recommendations.push({
        type: 'storage',
        priority: 'high',
        title: 'High Storage Usage',
        description: `System is using ${overview.totalStorageMB}MB of storage`,
        potentialSavings: overview.totalStorageMB * 0.026 * 0.3, // 30% potential savings
        action: 'Run storage cleanup and compress old attachments'
      })
    }

    // Database optimization
    if (overview.totalMessages > 50000) {
      recommendations.push({
        type: 'database',
        priority: 'medium',
        title: 'Large Message Database',
        description: `${overview.totalMessages} messages stored`,
        potentialSavings: overview.totalMessages * 0.000001 * 0.2,
        action: 'Archive old messages and implement data retention policies'
      })
    }

    // Inactive user cleanup
    console.log('Checking for inactive users...')
    const users = await getDetailedUserList()
    console.log('Users found:', users.length)
    const inactiveUsers = users.filter(u => !u.isActive).length
    console.log('Inactive users:', inactiveUsers, 'Total users:', users.length)

    if (inactiveUsers > users.length * 0.2 && users.length > 0) { // More than 20% inactive
      console.log('Adding inactive user cleanup recommendation')
      recommendations.push({
        type: 'cleanup',
        priority: 'medium',
        title: 'Inactive User Cleanup',
        description: `${inactiveUsers} inactive users found`,
        potentialSavings: inactiveUsers * 0.005, // Database cost savings
        action: 'Delete users inactive for 90+ days'
      })
    }

    // Cost monitoring
    console.log('Checking cost monitoring, estimated monthly:', overview.firebaseCosts.estimatedMonthly)
    if (overview.firebaseCosts.estimatedMonthly > 25) {
      console.log('Adding high cost recommendation')
      recommendations.push({
        type: 'scaling',
        priority: 'high',
        title: 'High Monthly Costs',
        description: `Estimated $${overview.firebaseCosts.estimatedMonthly}/month`,
        potentialSavings: overview.firebaseCosts.estimatedMonthly * 0.15,
        action: 'Implement usage limits and optimize data structure'
      })
    }

    // Always add at least some basic recommendations
    if (recommendations.length === 0) {
      console.log('No recommendations generated based on thresholds, adding general recommendations')

      // Add general recommendations that are always useful
      recommendations.push({
        type: 'cleanup',
        priority: 'low',
        title: 'Regular Data Maintenance',
        description: 'Keep your database optimized and clean',
        potentialSavings: overview.totalUsers * 0.01, // Small savings per user
        action: 'Run regular cleanup operations and monitor data growth'
      })

      if (overview.totalUsers > 0) {
        recommendations.push({
          type: 'database',
          priority: 'low',
          title: 'Database Health Check',
          description: `Monitor ${overview.totalUsers} users and ${overview.totalMessages} messages`,
          potentialSavings: 2.50,
          action: 'Review user activity and optimize data structure'
        })
      }

      recommendations.push({
        type: 'scaling',
        priority: 'low',
        title: 'Cost Monitoring Setup',
        description: 'Set up regular cost monitoring and alerts',
        potentialSavings: overview.firebaseCosts.estimatedMonthly * 0.05,
        action: 'Implement automated cost tracking and optimization alerts'
      })
    }

    console.log('Generated recommendations:', recommendations.length)
    return recommendations.sort((a, b) => {
      const priorityOrder = { high: 3, medium: 2, low: 1 }
      return priorityOrder[b.priority] - priorityOrder[a.priority]
    })
  } catch (error) {
    console.error('Error getting cost recommendations:', error)
    return []
  }
}

// Orphaned Data Cleanup - Find and remove data not associated with users
export const findOrphanedMessages = async (): Promise<{
  orphanedMessages: number
  orphanedStorageMB: number
  details: string[]
}> => {
  try {
    console.log('Searching for orphaned messages...')
    const result = {
      orphanedMessages: 0,
      orphanedStorageMB: 0,
      details: [] as string[]
    }

    // This is a placeholder - in a real implementation, you'd need to check
    // if there are messages stored outside user nodes or in different structures
    // For now, we'll just return the current message count for analysis

    const overview = await getSystemOverview()
    result.orphanedMessages = overview.totalMessages
    result.details.push(`Total messages in system: ${overview.totalMessages}`)
    result.details.push(`Active users: ${overview.activeUsers}`)
    result.details.push(`If users show 0 messages but total is high, there may be orphaned data`)

    console.log('Orphaned message analysis:', result)
    return result
  } catch (error) {
    console.error('Error finding orphaned messages:', error)
    return {
      orphanedMessages: 0,
      orphanedStorageMB: 0,
      details: ['Error analyzing orphaned data']
    }
  }
}

// ============================================
// EMAIL SERVICE - Cleanup Reports
// ============================================

// Generate detailed cleanup report (text format) - used by both client and server
export function generateCleanupReport(cleanupStats: CleanupStats, userId?: string): string {
  const timestamp = new Date().toLocaleString()
  const totalCleaned = Object.values(cleanupStats).reduce((sum, count) => sum + count, 0)

  return `
SYNCFLOW AUTO CLEANUP REPORT
============================

Report Generated: ${timestamp}
Cleaned By: ${userId || 'System Admin'}

CLEANUP STATISTICS
==================
Total Records Cleaned: ${totalCleaned}

Detailed Breakdown:
• Stale Outgoing Messages: ${cleanupStats.outgoingMessages || 0}
• Expired Pairings: ${cleanupStats.pendingPairings || 0}
• Old Call Requests: ${cleanupStats.callRequests || 0}
• Old Spam Messages: ${cleanupStats.spamMessages || 0}
• Old Read Receipts: ${cleanupStats.readReceipts || 0}
• Inactive Devices: ${cleanupStats.oldDevices || 0}
• Old Notifications: ${cleanupStats.oldNotifications || 0}
• Stale Typing Indicators: ${cleanupStats.staleTypingIndicators || 0}
• Expired Sessions: ${cleanupStats.expiredSessions || 0}
• Old File Transfers: ${cleanupStats.oldFileTransfers || 0}
• Abandoned Pairings: ${cleanupStats.abandonedPairings || 0}
• Orphaned Media: ${cleanupStats.orphanedMedia || 0}

IMPACT ANALYSIS
===============
Storage Freed: Approximately ${Math.round(totalCleaned * 0.001)} MB
Database Optimization: ${totalCleaned} records removed
Performance Improvement: Enhanced system efficiency

COST SAVINGS PROJECTION
=======================
Monthly Database Cost Reduction: $${(totalCleaned * 0.000001).toFixed(4)}
Annual Savings: $${(totalCleaned * 0.000001 * 12).toFixed(2)}

NEXT CLEANUP SCHEDULED
======================
Auto cleanup runs every 24 hours or on-demand via admin panel.

---
SyncFlow Admin System
Automated Maintenance Report
`
}



// Send cleanup report via console/email (simplified version)
export const sendCleanupReport = async (
  cleanupStats: CleanupStats,
  userId?: string,
  type: 'MANUAL' | 'AUTO' = 'MANUAL'
): Promise<{ success: boolean; error?: string }> => {
  try {
    const report = generateCleanupReport(cleanupStats, userId)

    // Always log to console for debugging
    console.log('=== SYNCFLOW CLEANUP REPORT ===')
    console.log(report)
    console.log('================================')

    // Send email via server-side API route
    console.log(`📧 Sending cleanup report via server API [${type}]...`)

    const response = await fetch('/api/send-cleanup-report', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        cleanupStats,
        userId,
        type
      })
    })

    const result = await response.json()

    if (result.success) {
      console.log('✅ Email sent successfully via server:', result.messageId)
      return { success: true }
    } else {
      console.log('❌ Server email sending failed:', result.error)
      return {
        success: false,
        error: result.error || 'Server email sending failed'
      }
    }
  } catch (error) {
    console.error('❌ Failed to send cleanup report:', error)
    return {
      success: false,
      error: `Email sending failed: ${error}`
    }
  }
}

// Enhanced auto cleanup with reporting
export const runAutoCleanupWithReport = async (userId: string): Promise<{
  cleanupStats: CleanupStats
  reportSent: boolean
  reportError?: string
}> => {
  try {
    console.log('Starting auto cleanup with reporting...')

    // Run the cleanup
    const cleanupStats = await runAutoCleanup(userId)
    console.log('Cleanup completed:', cleanupStats)

    // Send the report
    const reportResult = await sendCleanupReport(cleanupStats, userId, 'AUTO')

    return {
      cleanupStats,
      reportSent: reportResult.success,
      reportError: reportResult.error
    }
  } catch (error) {
    console.error('Auto cleanup with report failed:', error)
    return {
      cleanupStats: {} as CleanupStats,
      reportSent: false,
      reportError: `Cleanup process failed: ${error}`
    }
  }
}

export const cleanupOldDevices = async (): Promise<any> => {
  const result = await httpsCallable(functions, 'cleanupOldDevicesManual')()
  return result.data
}

export const unregisterDevice = async (deviceId: string): Promise<any> => {
  const result = await httpsCallable(functions, 'unregisterDevice')({ deviceId })
  return result.data
}

// ============================================
// SYNC GROUP MANAGEMENT (Device-based pairing)
// ============================================

/**
 * Generate a new sync group ID
 * Creates a generic sync group ID that multiple devices can join
 */
export const generateSyncGroupId = (): string => {
  return `sync_${crypto.randomUUID()}`
}

/**
 * Get stored sync group ID for this web browser
 * Returns stable ID across browser sessions (stored in localStorage)
 */
export const getStoredSyncGroupId = (): string | null => {
  if (typeof window === 'undefined') {
    return null
  }

  return localStorage.getItem('sync_group_id')
}

/**
 * Store sync group ID locally
 */
export const setStoredSyncGroupId = (syncGroupId: string): void => {
  if (typeof window !== 'undefined') {
    localStorage.setItem('sync_group_id', syncGroupId)
  }
}

/**
 * Get device ID for this web browser
 * Returns same ID across browser sessions
 */
export const getWebDeviceId = (): string => {
  if (typeof window === 'undefined') {
    return ''
  }

  let id = localStorage.getItem('device_id')
  if (!id) {
    id = `web_${crypto.randomUUID()}`
    localStorage.setItem('device_id', id)
  }
  return id
}

/**
 * Create a new sync group (called when first device initializes)
 */
export const createSyncGroup = async (deviceType: 'web' | 'macos' | 'android'): Promise<{ success: boolean; syncGroupId?: string }> => {
  try {
    const syncGroupId = generateSyncGroupId()
    const deviceId = getWebDeviceId()

    const syncGroupRef = ref(database, `syncGroups/${syncGroupId}`)
    const now = Date.now()

    await set(syncGroupRef, {
      plan: 'free',
      deviceLimit: 3,
      masterDevice: deviceId,
      createdAt: now,
      devices: {
        [deviceId]: {
          deviceType,
          joinedAt: now,
          status: 'active'
        }
      }
    })

    // Store locally
    setStoredSyncGroupId(syncGroupId)

    return { success: true, syncGroupId }
  } catch (error) {
    console.error('Error creating sync group:', error)
    return { success: false }
  }
}

/**
 * Join an existing sync group
 * Checks device limit before allowing join
 */
export const joinSyncGroup = async (
  syncGroupId: string,
  deviceType: 'web' | 'macos' | 'android',
  scannedGroupId?: string
): Promise<{ success: boolean; error?: string; deviceCount?: number; limit?: number }> => {
  try {
    // Use scanned group ID if provided (from QR code), otherwise use local sync group ID
    const targetGroupId = scannedGroupId || syncGroupId
    const deviceId = deviceType === 'web' ? getWebDeviceId() : undefined

    console.log('[WebJoin] joinSyncGroup called with syncGroupId:', syncGroupId, 'scannedGroupId:', scannedGroupId, 'targetGroupId:', targetGroupId)

    // First, check if sync group exists and get current device count
    const syncGroupRef = ref(database, `syncGroups/${targetGroupId}`)
    const groupSnapshot = await get(syncGroupRef)

    console.log('[WebJoin] Group snapshot exists:', groupSnapshot.exists())

    if (!groupSnapshot.exists()) {
      console.error('[WebJoin] Sync group not found:', targetGroupId)
      return {
        success: false,
        error: 'Sync group not found'
      }
    }

    const groupData = groupSnapshot.val()
    const plan = groupData.plan || 'free'
    const deviceLimit = plan === 'free' ? 3 : 999
    const currentDevices = Object.keys(groupData.devices || {}).length

    console.log('[WebJoin] Group data: plan=', plan, 'deviceLimit=', deviceLimit, 'currentDevices=', currentDevices)

    // Check device limit
    if (currentDevices >= deviceLimit) {
      console.warn('[WebJoin] Device limit reached:', currentDevices, '/', deviceLimit)
      return {
        success: false,
        error: `Device limit reached: ${currentDevices}/${deviceLimit}. Upgrade to Pro for unlimited devices.`,
        deviceCount: currentDevices,
        limit: deviceLimit
      }
    }

    // Save locally
    if (typeof window !== 'undefined') {
      localStorage.setItem('sync_group_id', targetGroupId)
    }
    console.log('[WebJoin] Saved sync group ID locally:', targetGroupId)

    // Register device in Firebase
    const now = Date.now()
    const actualDeviceId = deviceId || `${deviceType}_${crypto.randomUUID()}`

    console.log('[WebJoin] Registering device:', actualDeviceId, 'with deviceType:', deviceType)

    await update(syncGroupRef, {
      [`devices/${actualDeviceId}`]: {
        deviceType,
        joinedAt: now,
        status: 'active'
      }
    })

    console.log('[WebJoin] Device registered successfully')

    // Log to history
    await update(syncGroupRef, {
      [`history/${now}`]: {
        action: 'device_joined',
        deviceId: actualDeviceId,
        deviceType
      }
    })

    console.log('[WebJoin] History logged, joinSyncGroup completed successfully')

    return {
      success: true,
      deviceCount: currentDevices + 1,
      limit: deviceLimit
    }
  } catch (error) {
    console.error('[WebJoin] Error joining sync group:', error)
    return {
      success: false,
      error: `Error joining sync group: ${error}`
    }
  }
}

/**
 * Recover sync group on reinstall/browser clear
 * Searches for existing sync group by device ID
 */
export const recoverSyncGroup = async (
  deviceType: 'web' | 'macos' | 'android'
): Promise<{ success: boolean; syncGroupId?: string }> => {
  try {
    const deviceId = getWebDeviceId()

    // First, try to recover from localStorage
    const storedGroupId = getStoredSyncGroupId()
    if (storedGroupId) {
      // Verify the stored group still exists and contains our device
      const syncGroupRef = ref(database, `syncGroups/${storedGroupId}`)
      const groupSnapshot = await get(syncGroupRef)

      if (groupSnapshot.exists()) {
        const groupData = groupSnapshot.val()
        const devices = groupData.devices || {}
        if (devices[deviceId]) {
          return { success: true, syncGroupId: storedGroupId }
        }
      }
    }

    // Fallback: search all sync groups for our device ID
    // This is expensive but handles edge cases
    const allGroupsRef = ref(database, 'syncGroups')
    const allGroupsSnapshot = await get(allGroupsRef)

    if (allGroupsSnapshot.exists()) {
      const allGroups = allGroupsSnapshot.val()
      for (const [groupId, groupData] of Object.entries(allGroups as Record<string, any>)) {
        const devices = groupData.devices || {}
        if (devices[deviceId]) {
          // Found our device in this group
          setStoredSyncGroupId(groupId)
          return { success: true, syncGroupId: groupId }
        }
      }
    }

    return { success: false }
  } catch (error) {
    console.error('Error recovering sync group:', error)
    return { success: false }
  }
}

/**
 * Get sync group info with all devices
 */
export const getSyncGroupInfo = async (syncGroupId: string): Promise<{
  success: boolean
  data?: {
    plan: string
    deviceLimit: number
    deviceCount: number
    devices: Array<{
      deviceId: string
      deviceType: string
      joinedAt: number
      lastSyncedAt?: number
      status: string
    }>
  }
  error?: string
}> => {
  try {
    const syncGroupRef = ref(database, `syncGroups/${syncGroupId}`)
    const snapshot = await get(syncGroupRef)

    if (!snapshot.exists()) {
      return {
        success: false,
        error: 'Sync group not found'
      }
    }

    const groupData = snapshot.val()
    const plan = groupData.plan || 'free'
    const deviceLimit = plan === 'free' ? 3 : 999
    const devices = groupData.devices || {}

    return {
      success: true,
      data: {
        plan,
        deviceLimit,
        deviceCount: Object.keys(devices).length,
        devices: Object.entries(devices).map(([deviceId, info]: [string, any]) => ({
          deviceId,
          deviceType: info.deviceType,
          joinedAt: info.joinedAt,
          lastSyncedAt: info.lastSyncedAt,
          status: info.status || 'active'
        }))
      }
    }
  } catch (error) {
    console.error('Error getting sync group info:', error)
    return {
      success: false,
      error: `Error fetching sync group: ${error}`
    }
  }
}

/**
 * Update plan for a sync group (admin only via Cloud Functions)
 */
export const updateSyncGroupPlan = async (syncGroupId: string, plan: 'free' | 'monthly' | 'yearly' | 'lifetime'): Promise<boolean> => {
  try {
    const call = httpsCallable(functions, 'updateSyncGroupPlan')
    await call({ syncGroupId, plan })
    return true
  } catch (error) {
    console.error('Error updating sync group plan:', error)
    return false
  }
}

/**
 * Remove device from sync group
 */
export const removeDeviceFromGroup = async (syncGroupId: string, deviceId: string): Promise<boolean> => {
  try {
    const deviceRef = ref(database, `syncGroups/${syncGroupId}/devices/${deviceId}`)
    await remove(deviceRef)

    // Log to history
    const syncGroupRef = ref(database, `syncGroups/${syncGroupId}`)
    const now = Date.now()
    await update(syncGroupRef, {
      [`history/${now}`]: {
        action: 'device_removed',
        deviceId
      }
    })

    return true
  } catch (error) {
    console.error('Error removing device from group:', error)
    return false
  }
}

/**
 * Listen to sync group changes in real-time
 */
export const listenToSyncGroup = (syncGroupId: string, callback: (data: any) => void) => {
  const syncGroupRef = ref(database, `syncGroups/${syncGroupId}`)

  return onValue(syncGroupRef, (snapshot) => {
    if (snapshot.exists()) {
      callback(snapshot.val())
    }
  })
}

// ============================================
// R2 STORAGE ADMIN FUNCTIONS
// ============================================

export interface R2Analytics {
  totalFiles: number
  totalSize: number
  fileCounts: { files: number; mms: number; photos: number }
  sizeCounts: { files: number; mms: number; photos: number }
  largestFiles: R2File[]
  oldestFiles: R2File[]
  estimatedCost: number
  userStorage: UserStorageInfo[]
  totalUsersWithStorage: number
}

export interface R2File {
  key: string
  size: number
  uploadedAt: number
  type: 'files' | 'mms' | 'photos'
}

export interface UserStorageInfo {
  userId: string
  storageBytes: number
  lastUpdatedAt: number
}

/**
 * Get R2 storage analytics
 */
export const getR2Analytics = async (): Promise<R2Analytics> => {
  try {
    const call = httpsCallable(functions, 'getR2Analytics')
    const result = await call({})
    return result.data as R2Analytics
  } catch (error) {
    console.error('Error getting R2 analytics:', error)
    return {
      totalFiles: 0,
      totalSize: 0,
      fileCounts: { files: 0, mms: 0, photos: 0 },
      sizeCounts: { files: 0, mms: 0, photos: 0 },
      largestFiles: [],
      oldestFiles: [],
      estimatedCost: 0,
      userStorage: [],
      totalUsersWithStorage: 0
    }
  }
}

/**
 * Get R2 file list with pagination
 */
export const getR2FileList = async (
  fileType?: 'files' | 'mms' | 'photos',
  limit: number = 100
): Promise<{ files: R2File[]; totalCount: number }> => {
  try {
    const call = httpsCallable(functions, 'getR2FileList')
    const result = await call({ fileType, limit })
    return result.data as { files: R2File[]; totalCount: number }
  } catch (error) {
    console.error('Error getting R2 file list:', error)
    return { files: [], totalCount: 0 }
  }
}

/**
 * Clean up old R2 files
 */
export const cleanupOldR2Files = async (
  daysThreshold: number,
  fileType?: 'files' | 'mms' | 'photos'
): Promise<{
  success: boolean
  deletedFiles: number
  freedBytes: number
  errors: string[]
}> => {
  try {
    const call = httpsCallable(functions, 'cleanupOldR2Files')
    const result = await call({ daysThreshold, fileType })
    return result.data as {
      success: boolean
      deletedFiles: number
      freedBytes: number
      errors: string[]
    }
  } catch (error) {
    console.error('Error cleaning up R2 files:', error)
    return {
      success: false,
      deletedFiles: 0,
      freedBytes: 0,
      errors: [`Cleanup failed: ${error}`]
    }
  }
}

/**
 * Delete a specific R2 file (admin only)
 */
export const deleteR2FileAdmin = async (r2Key: string): Promise<{ success: boolean; error?: string }> => {
  try {
    const call = httpsCallable(functions, 'deleteR2FileAdmin')
    const result = await call({ r2Key })
    return result.data as { success: boolean; error?: string }
  } catch (error) {
    console.error('Error deleting R2 file:', error)
    return { success: false, error: `Delete failed: ${error}` }
  }
}

/**
 * Recalculate user's storage based on actual R2 files
 */
export interface RecalculateStorageResult {
  success: boolean
  userId: string
  fileCount: number
  actualBytes: number
  previousBytes: number
  difference: number
  error?: string
}

export const recalculateUserStorage = async (userId: string): Promise<RecalculateStorageResult> => {
  try {
    const call = httpsCallable(functions, 'recalculateUserStorage')
    const result = await call({ userId })
    return result.data as RecalculateStorageResult
  } catch (error) {
    console.error('Error recalculating storage:', error)
    return {
      success: false,
      userId,
      fileCount: 0,
      actualBytes: 0,
      previousBytes: 0,
      difference: 0,
      error: `Recalculation failed: ${error}`
    }
  }
}

export { auth, database, storage, signInAnon as signInAnonymously, getAuth }
