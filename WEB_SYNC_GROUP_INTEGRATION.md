# Web SyncGroupManager Integration Guide

## Overview

Integrate SyncGroupManager into the web pairing flow to enable QR code generation and scanning.

## Integration Steps

### Step 1: Update PairingScreen Component

**File:** `web/components/PairingScreen.tsx`

Replace existing pairing logic with sync group integration:

```typescript
'use client'

import React, { useState, useEffect } from 'react'
import QRCode from 'qrcode.react'
import {
  getSyncGroupId,
  getWebDeviceId,
  createSyncGroup,
  joinSyncGroup,
  recoverSyncGroup,
  getSyncGroupInfo,
  listenToSyncGroup
} from '@/lib/firebase'

interface PairingScreenProps {
  onPaired: (syncGroupId: string) => void
}

export default function PairingScreen({ onPaired }: PairingScreenProps) {
  const [syncGroupId, setSyncGroupId] = useState<string>('')
  const [isPaired, setIsPaired] = useState(false)
  const [isLoading, setIsLoading] = useState(true)
  const [errorMessage, setErrorMessage] = useState('')
  const [deviceCount, setDeviceCount] = useState(0)
  const [deviceLimit, setDeviceLimit] = useState(3)
  const [showQRScanner, setShowQRScanner] = useState(false)
  const [scanResult, setScanResult] = useState<string>('')

  useEffect(() => {
    initializeSync()
  }, [])

  const initializeSync = async () => {
    try {
      setIsLoading(true)

      // Try to recover existing sync group
      const recovered = await recoverSyncGroup('web')
      if (recovered.success && recovered.syncGroupId) {
        setSyncGroupId(recovered.syncGroupId)
        setIsPaired(true)
        onPaired(recovered.syncGroupId)
        setIsLoading(false)
        return
      }

      // No existing group, create new one
      const newGroupId = getSyncGroupId()
      const created = await createSyncGroup(newGroupId, 'web')
      if (created) {
        setSyncGroupId(newGroupId)
        setIsPaired(true)
        onPaired(newGroupId)

        // Get group info
        const info = await getSyncGroupInfo(newGroupId)
        if (info.success && info.data) {
          setDeviceCount(info.data.deviceCount)
          setDeviceLimit(info.data.deviceLimit)
        }
      } else {
        setErrorMessage('Failed to initialize pairing')
      }
    } catch (error) {
      console.error('Pairing error:', error)
      setErrorMessage('An error occurred during pairing')
    } finally {
      setIsLoading(false)
    }
  }

  const handleJoinScannedGroup = async (scannedGroupId: string) => {
    try {
      setIsLoading(true)
      setErrorMessage('')

      const result = await joinSyncGroup(scannedGroupId, 'web')
      if (result.success) {
        setSyncGroupId(scannedGroupId)
        setIsPaired(true)
        setDeviceCount(result.deviceCount!)
        setDeviceLimit(result.limit!)
        onPaired(scannedGroupId)
        setShowQRScanner(false)
      } else {
        const isLimit = result.error?.includes('Device limit') ?? false
        if (isLimit) {
          setErrorMessage(
            `Device limit reached (${result.deviceCount}/${result.limit}). ` +
            'Upgrade to Pro for unlimited devices.'
          )
        } else {
          setErrorMessage(result.error || 'Failed to join sync group')
        }
      }
    } catch (error) {
      console.error('Join error:', error)
      setErrorMessage('An error occurred while joining')
    } finally {
      setIsLoading(false)
    }
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-center">
          <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-500 mx-auto mb-4"></div>
          <p>Initializing pairing...</p>
        </div>
      </div>
    )
  }

  if (isPaired) {
    return (
      <div className="max-w-md mx-auto p-6 border rounded-lg bg-white">
        <div className="text-center mb-6">
          <div className="text-4xl mb-2">✅</div>
          <h2 className="text-xl font-bold">Paired</h2>
          <p className="text-sm text-gray-600 mt-2">
            {deviceCount}/{deviceLimit} devices
          </p>
        </div>

        <div className="bg-gray-100 p-4 rounded mb-6">
          <p className="text-xs text-gray-600 mb-2">Sync Group ID:</p>
          <code className="text-xs font-mono break-all">{syncGroupId}</code>
        </div>

        <button
          onClick={() => setShowQRScanner(true)}
          className="w-full px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600 mb-2"
        >
          Add Another Device
        </button>

        <button
          onClick={initializeSync}
          className="w-full px-4 py-2 border border-gray-300 rounded hover:bg-gray-50"
        >
          Refresh
        </button>

        {deviceCount >= deviceLimit && (
          <div className="mt-4 p-3 bg-yellow-50 border border-yellow-200 rounded">
            <p className="text-sm text-yellow-800">
              You've reached your device limit. Upgrade to Pro for unlimited devices.
            </p>
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="max-w-md mx-auto p-6 border rounded-lg bg-white">
      <h2 className="text-xl font-bold mb-6">Pair with Your Devices</h2>

      {showQRScanner ? (
        <QRScannerComponent
          onScanned={(result) => {
            setScanResult(result)
            handleJoinScannedGroup(result)
          }}
          onCancel={() => setShowQRScanner(false)}
        />
      ) : (
        <>
          {syncGroupId && (
            <>
              <p className="text-sm text-gray-600 mb-4">
                Scan this QR code on your Android or macOS device to pair:
              </p>

              <div className="flex justify-center mb-6 p-4 bg-gray-50 rounded">
                <QRCode
                  value={syncGroupId}
                  size={256}
                  level="H"
                  includeMargin={true}
                />
              </div>

              <button
                onClick={() => setShowQRScanner(true)}
                className="w-full px-4 py-2 bg-green-500 text-white rounded hover:bg-green-600 mb-2"
              >
                Scan Another Device's QR Code
              </button>

              <div className="text-xs text-gray-600 mt-4">
                <p className="font-semibold mb-2">Pairing Status:</p>
                <ul className="list-disc list-inside">
                  <li>Current devices: {deviceCount}/{deviceLimit}</li>
                  <li>Sync Group: {syncGroupId.slice(0, 8)}...</li>
                </ul>
              </div>
            </>
          )}
        </>
      )}

      {errorMessage && (
        <div className="mt-4 p-3 bg-red-50 border border-red-200 rounded">
          <p className="text-sm text-red-800">{errorMessage}</p>
        </div>
      )}
    </div>
  )
}
```

### Step 2: Create QR Scanner Component

**File:** `web/components/QRScannerComponent.tsx`

Create a reusable QR scanner:

```typescript
'use client'

import React, { useRef, useEffect, useState } from 'react'
import jsQR from 'jsqr'

interface QRScannerProps {
  onScanned: (result: string) => void
  onCancel: () => void
}

export default function QRScannerComponent({ onScanned, onCancel }: QRScannerProps) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [error, setError] = useState('')
  const [isScanning, setIsScanning] = useState(true)

  useEffect(() => {
    if (!isScanning) return

    const startScanning = async () => {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'environment' }
        })

        if (videoRef.current) {
          videoRef.current.srcObject = stream
          videoRef.current.play()
          scanQRCode()
        }
      } catch (err) {
        setError('Camera access denied. Please allow camera access to scan QR codes.')
        console.error('Camera error:', err)
      }
    }

    startScanning()

    return () => {
      if (videoRef.current?.srcObject) {
        const tracks = (videoRef.current.srcObject as MediaStream).getTracks()
        tracks.forEach(track => track.stop())
      }
    }
  }, [isScanning])

  const scanQRCode = () => {
    const video = videoRef.current
    const canvas = canvasRef.current

    if (!video || !canvas) return

    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const scan = () => {
      if (video.readyState === video.HAVE_ENOUGH_DATA) {
        canvas.width = video.videoWidth
        canvas.height = video.videoHeight
        ctx.drawImage(video, 0, 0)

        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height)
        const code = jsQR(imageData.data, imageData.width, imageData.height)

        if (code) {
          setIsScanning(false)
          onScanned(code.data)
          return
        }
      }

      requestAnimationFrame(scan)
    }

    scan()
  }

  return (
    <div className="space-y-4">
      <div className="relative w-full aspect-square bg-black rounded-lg overflow-hidden">
        <video
          ref={videoRef}
          className="w-full h-full object-cover"
          autoPlay
          playsInline
        />
        <canvas ref={canvasRef} className="hidden" />

        <div className="absolute inset-0 border-2 border-green-400">
          <div className="absolute top-1/4 left-1/4 w-1/2 h-1/2 border border-green-400"></div>
        </div>
      </div>

      {error && (
        <div className="p-3 bg-red-50 border border-red-200 rounded">
          <p className="text-sm text-red-800">{error}</p>
        </div>
      )}

      <div className="space-y-2">
        <button
          onClick={onCancel}
          className="w-full px-4 py-2 border border-gray-300 rounded hover:bg-gray-50"
        >
          Cancel
        </button>

        <p className="text-xs text-gray-600 text-center">
          Point your camera at the QR code
        </p>
      </div>
    </div>
  )
}
```

### Step 3: Update Page Layout

**File:** `web/app/messages/page.tsx`

Add pairing check before showing messages:

```typescript
'use client'

import React, { useState } from 'react'
import PairingScreen from '@/components/PairingScreen'
import MessageView from '@/components/MessageView'

export default function MessagesPage() {
  const [syncGroupId, setSyncGroupId] = useState<string | null>(null)

  return (
    <div>
      {syncGroupId ? (
        <MessageView syncGroupId={syncGroupId} />
      ) : (
        <PairingScreen
          onPaired={(groupId) => {
            setSyncGroupId(groupId)
          }}
        />
      )}
    </div>
  )
}
```

### Step 4: Update Store for Sync Group

**File:** `web/lib/store.ts`

Add sync group state to Zustand store:

```typescript
import { create } from 'zustand'

interface AppState {
  syncGroupId: string | null
  setSyncGroupId: (id: string | null) => void
  deviceCount: number
  deviceLimit: number
  setDeviceInfo: (count: number, limit: number) => void
  // ... existing state
}

export const useAppStore = create<AppState>((set) => ({
  syncGroupId: null,
  setSyncGroupId: (id) => set({ syncGroupId: id }),
  deviceCount: 0,
  deviceLimit: 3,
  setDeviceInfo: (count, limit) => set({ deviceCount: count, deviceLimit: limit }),
  // ... existing state
}))
```

### Step 5: Add Device Limit Indicator

**File:** `web/components/Header.tsx`

Add device status to header:

```typescript
export default function Header() {
  const { syncGroupId, deviceCount, deviceLimit } = useAppStore()

  return (
    <header className="bg-white border-b">
      <div className="max-w-7xl mx-auto px-4 py-4 flex justify-between items-center">
        <h1 className="text-2xl font-bold">SyncFlow</h1>

        {syncGroupId && (
          <div className="text-sm text-gray-600">
            <span className="font-semibold">{deviceCount}/{deviceLimit}</span> devices connected
          </div>
        )}
      </div>
    </header>
  )
}
```

## Testing

Test the web integration with:

1. **First Load:** Check if creates sync group and shows QR
2. **QR Display:** Verify QR code contains correct sync group ID
3. **QR Scanner:** Test scanning another device's QR code
4. **Device Limit:** Verify 3 device limit on free tier

```typescript
// Debug: Log sync group ID
console.log('Sync Group ID:', getSyncGroupId())
console.log('Device ID:', getWebDeviceId())
```

## Files to Create/Modify

1. `web/components/PairingScreen.tsx` - Main pairing UI
2. `web/components/QRScannerComponent.tsx` - QR scanner
3. `web/app/messages/page.tsx` - Page layout
4. `web/lib/store.ts` - Add sync group state
5. `web/components/Header.tsx` - Show device count

## Dependency: jsQR

Install QR code scanning library:

```bash
npm install jsqr
npm install --save-dev @types/jsqr
```

## Next Steps

1. Implement the code changes above
2. Test QR code generation
3. Test QR code scanning
4. Verify device limit enforcement
5. Test cross-platform pairing (Web ↔ Android, Web ↔ macOS)
