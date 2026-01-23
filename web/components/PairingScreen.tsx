'use client'

import { useEffect, useRef, useState, useCallback } from 'react'
import { useRouter } from 'next/navigation'
import { Smartphone, Monitor, CheckCircle, AlertCircle, RefreshCw, XCircle, Clock } from 'lucide-react'
import { QRCodeSVG } from 'qrcode.react'
import { useAppStore } from '@/lib/store'
import { initiatePairing, listenForPairingApproval, PairingSession, PairingStatus } from '@/lib/firebase'

export default function PairingScreen() {
  const router = useRouter()
  const { setUserId } = useAppStore()
  const [step, setStep] = useState<'loading' | 'qr' | 'success' | 'rejected' | 'error'>('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [pairingSession, setPairingSession] = useState<PairingSession | null>(null)
  const [timeRemaining, setTimeRemaining] = useState(0)
  const redirectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const unsubscribeRef = useRef<(() => void) | null>(null)
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  // Cleanup function
  useEffect(() => {
    return () => {
      if (redirectTimeoutRef.current) {
        clearTimeout(redirectTimeoutRef.current)
      }
      if (unsubscribeRef.current) {
        unsubscribeRef.current()
      }
      if (timerRef.current) {
        clearInterval(timerRef.current)
      }
    }
  }, [])

  // Start pairing session
  const startPairing = useCallback(async () => {
    // Cleanup previous listeners
    if (unsubscribeRef.current) {
      unsubscribeRef.current()
      unsubscribeRef.current = null
    }
    if (timerRef.current) {
      clearInterval(timerRef.current)
      timerRef.current = null
    }

    setStep('loading')
    setErrorMessage('')

    try {
      const session = await initiatePairing()
      setPairingSession(session)
      setStep('qr')

      // Calculate initial time remaining
      const remaining = Math.max(0, Math.floor((session.expiresAt - Date.now()) / 1000))
      setTimeRemaining(remaining)

      // Start countdown timer
      timerRef.current = setInterval(() => {
        setTimeRemaining((prev) => {
          if (prev <= 1) {
            if (timerRef.current) {
              clearInterval(timerRef.current)
            }
            return 0
          }
          return prev - 1
        })
      }, 1000)

      // Listen for approval/rejection
      unsubscribeRef.current = listenForPairingApproval(session.token, (status: PairingStatus) => {
        if (status.status === 'approved' && status.pairedUid) {
          // Success! Store credentials and redirect
          localStorage.setItem('syncflow_user_id', status.pairedUid)
          if (status.deviceId) {
            localStorage.setItem('syncflow_device_id', status.deviceId)
          }
          setUserId(status.pairedUid)
          setStep('success')

          // Cleanup listeners
          if (unsubscribeRef.current) {
            unsubscribeRef.current()
            unsubscribeRef.current = null
          }
          if (timerRef.current) {
            clearInterval(timerRef.current)
            timerRef.current = null
          }

          // Redirect after brief delay
          redirectTimeoutRef.current = setTimeout(() => {
            router.push('/messages')
          }, 2000)
        } else if (status.status === 'rejected') {
          setStep('rejected')
          if (unsubscribeRef.current) {
            unsubscribeRef.current()
            unsubscribeRef.current = null
          }
          if (timerRef.current) {
            clearInterval(timerRef.current)
            timerRef.current = null
          }
        } else if (status.status === 'expired') {
          // Token expired, show error
          setStep('error')
          setErrorMessage('Pairing session expired. Please try again.')
          if (unsubscribeRef.current) {
            unsubscribeRef.current()
            unsubscribeRef.current = null
          }
          if (timerRef.current) {
            clearInterval(timerRef.current)
            timerRef.current = null
          }
        }
      })
    } catch (error: any) {
      console.error('Failed to start pairing:', error)
      setStep('error')
      setErrorMessage(error.message || 'Failed to start pairing session')
    }
  }, [router, setUserId])

  // Start pairing on mount
  useEffect(() => {
    startPairing()
  }, [startPairing])

  // Handle timer expiration
  useEffect(() => {
    if (timeRemaining === 0 && step === 'qr') {
      setStep('error')
      setErrorMessage('Pairing session expired. Please try again.')
      if (unsubscribeRef.current) {
        unsubscribeRef.current()
        unsubscribeRef.current = null
      }
    }
  }, [timeRemaining, step])

  // Format time remaining
  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60)
    const secs = seconds % 60
    return `${mins}:${secs.toString().padStart(2, '0')}`
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100 dark:from-gray-900 dark:to-gray-800 flex items-center justify-center p-4">
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
                Generating QR Code...
              </h2>
              <p className="text-gray-600 dark:text-gray-400">
                Please wait while we prepare the pairing session
              </p>
            </div>
          )}

          {step === 'qr' && pairingSession && (
            <div className="text-center">
              <Monitor className="w-16 h-16 text-blue-600 mx-auto mb-4" />
              <h2 className="text-2xl font-semibold text-gray-900 dark:text-white mb-2">
                Scan to Pair
              </h2>
              <p className="text-gray-600 dark:text-gray-400 mb-6">
                Scan this QR code with your SyncFlow Android app
              </p>

              {/* QR Code */}
              <div className="bg-white p-6 rounded-xl inline-block mb-6 shadow-inner">
                <QRCodeSVG
                  value={pairingSession.qrPayload}
                  size={220}
                  level="M"
                  includeMargin={false}
                />
              </div>

              {/* Timer */}
              <div className="flex items-center justify-center gap-2 mb-6">
                <Clock className={`w-5 h-5 ${timeRemaining <= 60 ? 'text-orange-500' : 'text-gray-400'}`} />
                <span className={`font-mono text-lg ${timeRemaining <= 60 ? 'text-orange-500 font-semibold' : 'text-gray-600 dark:text-gray-400'}`}>
                  {formatTime(timeRemaining)}
                </span>
              </div>

              {/* Instructions */}
              <div className="bg-blue-50 dark:bg-blue-900/20 rounded-lg p-4 text-left">
                <h3 className="font-semibold text-gray-900 dark:text-white mb-2">
                  Instructions:
                </h3>
                <ol className="text-gray-700 dark:text-gray-300 space-y-1 text-sm">
                  <li className="flex items-start">
                    <span className="font-semibold mr-2">1.</span>
                    <span>Open SyncFlow app on your Android phone</span>
                  </li>
                  <li className="flex items-start">
                    <span className="font-semibold mr-2">2.</span>
                    <span>Go to Settings → Desktop Integration</span>
                  </li>
                  <li className="flex items-start">
                    <span className="font-semibold mr-2">3.</span>
                    <span>Tap "Scan Desktop QR Code"</span>
                  </li>
                  <li className="flex items-start">
                    <span className="font-semibold mr-2">4.</span>
                    <span>Point your camera at this QR code</span>
                  </li>
                  <li className="flex items-start">
                    <span className="font-semibold mr-2">5.</span>
                    <span>Approve the pairing request on your phone</span>
                  </li>
                </ol>
              </div>

              {/* Refresh button */}
              <button
                onClick={startPairing}
                className="mt-6 text-blue-600 hover:text-blue-700 dark:text-blue-400 dark:hover:text-blue-300 font-medium flex items-center justify-center mx-auto"
              >
                <RefreshCw className="w-4 h-4 mr-2" />
                Generate New QR Code
              </button>
            </div>
          )}

          {step === 'success' && (
            <div className="text-center py-12">
              <CheckCircle className="w-20 h-20 text-green-500 mx-auto mb-4" />
              <h2 className="text-2xl font-semibold text-gray-900 dark:text-white mb-2">
                Successfully Paired!
              </h2>
              <p className="text-gray-600 dark:text-gray-400">
                Redirecting to your messages...
              </p>
            </div>
          )}

          {step === 'rejected' && (
            <div className="text-center py-12">
              <XCircle className="w-20 h-20 text-orange-500 mx-auto mb-4" />
              <h2 className="text-2xl font-semibold text-gray-900 dark:text-white mb-2">
                Pairing Declined
              </h2>
              <p className="text-gray-600 dark:text-gray-400 mb-6">
                The pairing request was declined on your phone.
              </p>
              <button
                onClick={startPairing}
                className="bg-blue-600 hover:bg-blue-700 text-white font-semibold py-3 px-6 rounded-lg transition-colors flex items-center justify-center mx-auto"
              >
                <RefreshCw className="w-5 h-5 mr-2" />
                Try Again
              </button>
            </div>
          )}

          {step === 'error' && (
            <div className="text-center py-12">
              <AlertCircle className="w-20 h-20 text-red-500 mx-auto mb-4" />
              <h2 className="text-2xl font-semibold text-gray-900 dark:text-white mb-2">
                Pairing Failed
              </h2>
              <p className="text-gray-600 dark:text-gray-400 mb-6">{errorMessage}</p>
              <button
                onClick={startPairing}
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
  )
}
