'use client'

import { useEffect, useRef, useState, useCallback } from 'react'
import { useRouter } from 'next/navigation'
import { Smartphone, Monitor, CheckCircle, AlertCircle, RefreshCw, XCircle } from 'lucide-react'
import { QRCodeSVG } from 'qrcode.react'
import { useAppStore } from '@/lib/store'
import {
  getSyncGroupId,
  createSyncGroup,
  recoverSyncGroup,
  getSyncGroupInfo,
  getWebDeviceId,
  signInAnon,
} from '@/lib/firebase'

export default function PairingScreen() {
  const router = useRouter()
  const { setSyncGroupId, setDeviceInfo } = useAppStore()
  const [step, setStep] = useState<'loading' | 'paired' | 'error'>('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [syncGroupId, setSyncGroupIdLocal] = useState<string>('')
  const [deviceCount, setDeviceCount] = useState(0)
  const [deviceLimit, setDeviceLimit] = useState(3)
  const redirectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Cleanup function
  useEffect(() => {
    return () => {
      if (redirectTimeoutRef.current) {
        clearTimeout(redirectTimeoutRef.current)
      }
    }
  }, [])

  // Initialize sync group
  const initializeSync = useCallback(async () => {
    setStep('loading')
    setErrorMessage('')

    try {
      // Authenticate first (anonymous)
      await signInAnon()

      // Try to recover existing sync group first
      const recovered = await recoverSyncGroup('web')
      if (recovered.success && recovered.syncGroupId) {
        setSyncGroupIdLocal(recovered.syncGroupId)

        // Get group info
        const info = await getSyncGroupInfo(recovered.syncGroupId)
        if (info.success && info.data) {
          setDeviceCount(info.data.deviceCount)
          setDeviceLimit(info.data.deviceLimit)
        }

        setSyncGroupId(recovered.syncGroupId)
        setDeviceInfo(info.data?.deviceCount || 0, info.data?.deviceLimit || 3)
        setStep('paired')

        // Auto-redirect after 2 seconds
        if (redirectTimeoutRef.current) clearTimeout(redirectTimeoutRef.current)
        redirectTimeoutRef.current = setTimeout(() => {
          router.push('/messages')
        }, 2000)
        return
      }

      // No existing group, create new one
      const newGroupId = getSyncGroupId()
      const created = await createSyncGroup(newGroupId, 'web')

      if (created) {
        setSyncGroupIdLocal(newGroupId)

        // Get group info
        const info = await getSyncGroupInfo(newGroupId)
        if (info.success && info.data) {
          setDeviceCount(info.data.deviceCount)
          setDeviceLimit(info.data.deviceLimit)
        }

        setSyncGroupId(newGroupId)
        setDeviceInfo(info.data?.deviceCount || 0, info.data?.deviceLimit || 3)
        setStep('paired')

        // Auto-redirect after 2 seconds
        if (redirectTimeoutRef.current) clearTimeout(redirectTimeoutRef.current)
        redirectTimeoutRef.current = setTimeout(() => {
          router.push('/messages')
        }, 2000)
      } else {
        setStep('error')
        setErrorMessage('Failed to initialize pairing')
      }
    } catch (error: any) {
      console.error('Failed to initialize sync:', error)
      setStep('error')
      setErrorMessage(error.message || 'Failed to initialize pairing')
    }
  }, [])

  // Call once on mount
  const initialized = useRef(false)
  useEffect(() => {
    if (!initialized.current) {
      initialized.current = true
      initializeSync()
    }
    return () => {
      if (redirectTimeoutRef.current) {
        clearTimeout(redirectTimeoutRef.current)
      }
    }
  }, [])

  return (
    <div className="min-h-screen overflow-y-auto bg-gradient-to-br from-blue-50 to-indigo-100 dark:from-gray-900 dark:to-gray-800">
      <div className="min-h-screen flex items-center justify-center p-4">
        <div className="max-w-2xl w-full">
          {/* Logo and Title */}
          <div className="text-center mb-8">
            <div className="flex items-center justify-center mb-4">
              <Smartphone className="w-12 h-12 text-blue-600 mr-3" />
              <h1 className="text-4xl font-bold text-gray-900 dark:text-white">SyncFlow</h1>
            </div>
            <p className="text-gray-600 dark:text-gray-400">
              Access your phone messages from your desktop
            </p>
          </div>

          {/* Main Card */}
          <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-xl p-8">
            {step === 'loading' && (
              <div className="text-center py-12">
                <div className="animate-spin rounded-full h-16 w-16 border-b-2 border-blue-600 mx-auto mb-4"></div>
                <h2 className="text-xl font-semibold text-gray-900 dark:text-white mb-2">
                  Initializing Pairing...
                </h2>
                <p className="text-gray-600 dark:text-gray-400">
                  Please wait while we set up your sync group
                </p>
              </div>
            )}

            {step === 'paired' && (
              <div className="text-center">
                <CheckCircle className="w-16 h-16 text-green-600 mx-auto mb-4" />
                <h2 className="text-2xl font-semibold text-gray-900 dark:text-white mb-2">
                  Sync Group Ready
                </h2>
                <p className="text-gray-600 dark:text-gray-400 mb-6">
                  Share this QR code to add more devices
                </p>

                {/* QR Code */}
                {syncGroupId && (
                  <div className="bg-white p-6 rounded-xl inline-block mb-6 shadow-inner border border-gray-200">
                    <QRCodeSVG
                      value={syncGroupId}
                      size={220}
                      level="M"
                      includeMargin={false}
                    />
                  </div>
                )}

                {/* Device Info */}
                <div className="bg-blue-50 dark:bg-blue-900/20 rounded-lg p-4 mb-6">
                  <p className="text-sm text-gray-700 dark:text-gray-300 mb-2">
                    <span className="font-semibold">{deviceCount}/{deviceLimit}</span> devices connected
                  </p>
                  {deviceCount >= deviceLimit && (
                    <p className="text-xs text-yellow-600 dark:text-yellow-400">
                      You've reached your device limit. Upgrade to Pro for unlimited devices.
                    </p>
                  )}
                </div>

                {/* Sync Group ID */}
                <div className="bg-gray-100 dark:bg-gray-700 p-3 rounded-lg text-left mb-4">
                  <p className="text-xs text-gray-600 dark:text-gray-400 mb-1">Sync Group ID:</p>
                  <code className="text-xs font-mono text-gray-900 dark:text-white break-all">{syncGroupId}</code>
                </div>

                {/* Instructions */}
                <div className="bg-blue-50 dark:bg-blue-900/20 rounded-lg p-4 text-left">
                  <h3 className="font-semibold text-gray-900 dark:text-white mb-2">
                    To add another device:
                  </h3>
                  <ol className="text-gray-700 dark:text-gray-300 space-y-1 text-sm">
                    <li className="flex items-start">
                      <span className="font-semibold mr-2">1.</span>
                      <span>Open SyncFlow on another device</span>
                    </li>
                    <li className="flex items-start">
                      <span className="font-semibold mr-2">2.</span>
                      <span>Scan this QR code</span>
                    </li>
                    <li className="flex items-start">
                      <span className="font-semibold mr-2">3.</span>
                      <span>Your devices will sync automatically</span>
                    </li>
                  </ol>
                </div>

                <p className="text-xs text-gray-500 dark:text-gray-400 mt-4">
                  Redirecting to messages...
                </p>
              </div>
            )}

            {step === 'error' && (
              <div className="text-center py-12">
                <AlertCircle className="w-20 h-20 text-red-500 mx-auto mb-4" />
                <h2 className="text-2xl font-semibold text-gray-900 dark:text-white mb-2">
                  Initialization Failed
                </h2>
                <p className="text-gray-600 dark:text-gray-400 mb-6">{errorMessage}</p>
                <button
                  onClick={initializeSync}
                  className="bg-blue-600 hover:bg-blue-700 text-white font-semibold py-3 px-6 rounded-lg transition-colors flex items-center justify-center mx-auto"
                >
                  <RefreshCw className="w-5 h-5 mr-2" />
                  Try Again
                </button>
              </div>
            )}
          </div>

          {/* Footer */}
          <p className="text-center text-gray-500 dark:text-gray-400 mt-6 text-sm">
            Your messages are end-to-end encrypted and never leave your control
          </p>
        </div>
      </div>
    </div>
  )
}
