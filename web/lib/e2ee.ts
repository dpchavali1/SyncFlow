'use client'

const DEVICE_ID_KEY = 'syncflow_device_id'
const E2EE_KEYPAIR_KEY = 'syncflow_e2ee_jwk'
const E2EE_CONTEXT = 'SyncFlow-E2EE-v2'

type StoredKeyPair = {
  privateKeyJwk: JsonWebKey
  publicKeyJwk: JsonWebKey
}

const textEncoder = new TextEncoder()
const textDecoder = new TextDecoder()

const base64ToBytes = (base64: string) => {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

const bytesToBase64 = (bytes: Uint8Array) => {
  let binary = ''
  bytes.forEach((b) => (binary += String.fromCharCode(b)))
  return btoa(binary)
}

export const getOrCreateDeviceId = () => {
  if (typeof window === 'undefined') return null
  const existing = localStorage.getItem(DEVICE_ID_KEY)
  if (existing) return existing
  const id = `web_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`
  localStorage.setItem(DEVICE_ID_KEY, id)
  return id
}

export const getOrCreateKeyPair = async () => {
  if (typeof window === 'undefined') return null
  const existing = localStorage.getItem(E2EE_KEYPAIR_KEY)
  if (existing) {
    const parsed = JSON.parse(existing) as StoredKeyPair
    const privateKey = await crypto.subtle.importKey(
      'jwk',
      parsed.privateKeyJwk,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      ['deriveBits']
    )
    const publicKey = await crypto.subtle.importKey(
      'jwk',
      parsed.publicKeyJwk,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      []
    )
    return { privateKey, publicKey }
  }

  const keyPair = await crypto.subtle.generateKey(
    { name: 'ECDH', namedCurve: 'P-256' },
    true,
    ['deriveBits']
  )

  const privateKeyJwk = await crypto.subtle.exportKey('jwk', keyPair.privateKey)
  const publicKeyJwk = await crypto.subtle.exportKey('jwk', keyPair.publicKey)

  localStorage.setItem(
    E2EE_KEYPAIR_KEY,
    JSON.stringify({ privateKeyJwk, publicKeyJwk })
  )

  return keyPair
}

export const getStoredKeyPairJwk = (): StoredKeyPair | null => {
  if (typeof window === 'undefined') return null
  const existing = localStorage.getItem(E2EE_KEYPAIR_KEY)
  if (!existing) return null
  try {
    return JSON.parse(existing) as StoredKeyPair
  } catch {
    return null
  }
}

export const importKeyPairFromJwk = async (payload: StoredKeyPair) => {
  if (typeof window === 'undefined') return false
  try {
    await crypto.subtle.importKey(
      'jwk',
      payload.privateKeyJwk,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      ['deriveBits']
    )
    await crypto.subtle.importKey(
      'jwk',
      payload.publicKeyJwk,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      []
    )
    localStorage.setItem(E2EE_KEYPAIR_KEY, JSON.stringify(payload))
    return true
  } catch {
    return false
  }
}

export const getPublicKeyX963Base64 = async () => {
  const keyPair = await getOrCreateKeyPair()
  if (!keyPair) return null
  const raw = await crypto.subtle.exportKey('raw', keyPair.publicKey)
  return bytesToBase64(new Uint8Array(raw))
}

const deriveAesKey = async (sharedSecret: ArrayBuffer) => {
  const hkdfKey = await crypto.subtle.importKey(
    'raw',
    sharedSecret,
    'HKDF',
    false,
    ['deriveKey']
  )

  return crypto.subtle.deriveKey(
    {
      name: 'HKDF',
      hash: 'SHA-256',
      salt: new Uint8Array([]),
      info: textEncoder.encode(E2EE_CONTEXT),
    },
    hkdfKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  )
}

export const encryptDataForDevice = async (
  publicKeyX963Base64: string,
  data: Uint8Array
) => {
  const recipientKeyBytes = base64ToBytes(publicKeyX963Base64)
  const recipientKey = await crypto.subtle.importKey(
    'raw',
    recipientKeyBytes,
    { name: 'ECDH', namedCurve: 'P-256' },
    false,
    []
  )

  const ephemeralKeyPair = await crypto.subtle.generateKey(
    { name: 'ECDH', namedCurve: 'P-256' },
    true,
    ['deriveBits']
  )

  const sharedSecret = await crypto.subtle.deriveBits(
    { name: 'ECDH', public: recipientKey },
    ephemeralKeyPair.privateKey,
    256
  )

  const aesKey = await deriveAesKey(sharedSecret)
  const nonce = crypto.getRandomValues(new Uint8Array(12))
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: nonce },
    aesKey,
    data
  )

  const ephemeralPublic = new Uint8Array(
    await crypto.subtle.exportKey('raw', ephemeralKeyPair.publicKey)
  )
  const ciphertextBytes = new Uint8Array(ciphertext)
  const envelope = new Uint8Array(ephemeralPublic.length + nonce.length + ciphertextBytes.length)
  envelope.set(ephemeralPublic, 0)
  envelope.set(nonce, ephemeralPublic.length)
  envelope.set(ciphertextBytes, ephemeralPublic.length + nonce.length)

  return `v2:${bytesToBase64(envelope)}`
}

export const decodeUtf8 = (bytes: Uint8Array) => textDecoder.decode(bytes)

export const encodeUtf8 = (value: string) => textEncoder.encode(value)

export const decryptDataKey = async (envelope: string) => {
  const keyPair = await getOrCreateKeyPair()
  if (!keyPair) return null

  const payload = envelope.replace(/^v2:/, '')
  const bytes = base64ToBytes(payload)
  if (bytes.length < 65 + 12 + 16) return null

  const ephemeralPublic = bytes.slice(0, 65)
  const nonce = bytes.slice(65, 77)
  const ciphertext = bytes.slice(77)

  const publicKey = await crypto.subtle.importKey(
    'raw',
    ephemeralPublic,
    { name: 'ECDH', namedCurve: 'P-256' },
    false,
    []
  )

  const sharedSecret = await crypto.subtle.deriveBits(
    { name: 'ECDH', public: publicKey },
    keyPair.privateKey,
    256
  )

  const aesKey = await deriveAesKey(sharedSecret)
  try {
    const dataKey = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: nonce },
      aesKey,
      ciphertext
    )
    return new Uint8Array(dataKey)
  } catch {
    return null
  }
}

export const decryptMessageBody = async (
  dataKey: Uint8Array,
  ciphertextBase64: string,
  nonceBase64: string
) => {
  const ciphertext = base64ToBytes(ciphertextBase64)
  const nonce = base64ToBytes(nonceBase64)
  // Copy to a fresh ArrayBuffer to satisfy TypeScript's strict type checking
  const keyArrayBuffer = new ArrayBuffer(dataKey.length)
  new Uint8Array(keyArrayBuffer).set(dataKey)
  const aesKey = await crypto.subtle.importKey(
    'raw',
    keyArrayBuffer,
    { name: 'AES-GCM' },
    false,
    ['decrypt']
  )
  try {
    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: nonce },
      aesKey,
      ciphertext
    )
    return new TextDecoder().decode(plaintext)
  } catch {
    return null
  }
}
