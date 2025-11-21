'use client'

import { useState, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { QrCode, Smartphone, Monitor, CheckCircle, AlertCircle, RefreshCw } from 'lucide-react'
import { useAppStore } from '@/lib/store'
import { QRCodeSVG } from 'qrcode.react'
import { generatePairingToken, listenForPairingCompletion, signInAnon, waitForAuth } from '@/lib/firebase'

export default function PairingScreen() {
  const router = useRouter()
  const { setUserId } = useAppStore()
  const [step, setStep] = useState<'intro' | 'showing-qr' | 'pairing' | 'success' | 'error'>('intro')
  const [errorMessage, setErrorMessage] = useState('')
  const [pairingToken, setPairingToken] = useState('')
  const [deviceName, setDeviceName] = useState('')

  // Initialize Firebase authentication on mount
  useEffect(() => {
    const initAuth = async () => {
      console.log('🔥 Pre-authenticating Firebase in background...')
      const currentUser = await waitForAuth()
      if (!currentUser) {
        try {
          await signInAnon()
          console.log('✅ Background authentication complete')
        } catch (error) {
          console.error('❌ Background authentication failed:', error)
        }
      } else {
        console.log('✅ Already authenticated:', currentUser)
      }
    }
    initAuth()
  }, [])

  const handleGenerateQR = async () => {
    try {
      console.log('⏱️ Starting QR generation...')
      setStep('showing-qr')

      // Wait for auth and sign in if needed
      const currentUser = await waitForAuth()
      if (!currentUser) {
        console.log('⏱️ Not authenticated, signing in to Firebase...')
        await signInAnon()
        console.log('✅ Firebase sign-in complete')
      } else {
        console.log('✅ Already authenticated as:', currentUser)
      }

      console.log('⏱️ Generating pairing token...')
      const token = await generatePairingToken()
      console.log('✅ Token generated:', token)
      setPairingToken(token)

      // Listen for pairing completion
      const cleanup = listenForPairingCompletion(token, (userId) => {
        if (userId) {
          setStep('pairing')
          // Store user ID
          localStorage.setItem('syncflow_user_id', userId)
          setUserId(userId)

          setStep('success')

          // Redirect to messages
          setTimeout(() => {
            router.push('/messages')
          }, 2000)
        }
      })

      // Cleanup listener after 5 minutes
      setTimeout(() => {
        cleanup()
        if (step === 'showing-qr') {
          setErrorMessage('QR code expired. Please generate a new one.')
          setStep('error')
        }
      }, 5 * 60 * 1000)
    } catch (error: any) {
      setStep('error')
      setErrorMessage(error.message || 'Failed to generate QR code')
    }
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
          {step === 'intro' && (
            <div className="text-center">
              <Monitor className="w-20 h-20 text-blue-600 mx-auto mb-6" />
              <h2 className="text-2xl font-semibold text-gray-900 dark:text-white mb-4">
                Pair Your Phone
              </h2>
              <p className="text-gray-600 dark:text-gray-400 mb-6">
                To get started, scan the QR code with your phone's SyncFlow app.
              </p>

              <div className="bg-blue-50 dark:bg-blue-900/20 rounded-lg p-6 mb-6">
                <h3 className="font-semibold text-gray-900 dark:text-white mb-3">
                  Instructions:
                </h3>
                <ol className="text-left text-gray-700 dark:text-gray-300 space-y-2">
                  <li className="flex items-start">
                    <span className="font-semibold mr-2">1.</span>
                    <span>Click "Generate QR Code" below</span>
                  </li>
                  <li className="flex items-start">
                    <span className="font-semibold mr-2">2.</span>
                    <span>Open SyncFlow app on your phone</span>
                  </li>
                  <li className="flex items-start">
                    <span className="font-semibold mr-2">3.</span>
                    <span>Go to Settings → Desktop Integration</span>
                  </li>
                  <li className="flex items-start">
                    <span className="font-semibold mr-2">4.</span>
                    <span>Tap "Scan QR Code" and scan the code shown here</span>
                  </li>
                </ol>
              </div>

              <button
                onClick={handleGenerateQR}
                className="w-full bg-blue-600 hover:bg-blue-700 text-white font-semibold py-3 px-6 rounded-lg transition-colors flex items-center justify-center"
              >
                <QrCode className="w-5 h-5 mr-2" />
                Generate QR Code
              </button>
            </div>
          )}

          {step === 'showing-qr' && (
            <div className="text-center">
              <h2 className="text-2xl font-semibold text-gray-900 dark:text-white mb-4">
                Scan this QR Code
              </h2>
              <p className="text-gray-600 dark:text-gray-400 mb-6">
                Open SyncFlow app on your phone and scan this code
              </p>

              {/* QR Code Display */}
              <div className="bg-white p-6 rounded-xl mb-6 inline-block">
                <QRCodeSVG
                  value={pairingToken}
                  size={256}
                  level="H"
                  includeMargin={true}
                />
              </div>

              {/* Manual Code */}
              <div className="bg-gray-50 dark:bg-gray-700 rounded-lg p-4 mb-6">
                <p className="text-sm text-gray-600 dark:text-gray-400 mb-2">
                  Or enter this code manually:
                </p>
                <code className="text-lg font-mono text-gray-900 dark:text-white bg-white dark:bg-gray-800 px-4 py-2 rounded border border-gray-200 dark:border-gray-600 inline-block">
                  {pairingToken}
                </code>
              </div>

              <p className="text-sm text-gray-500 dark:text-gray-400 mb-4">
                This code will expire in 5 minutes
              </p>

              <button
                onClick={() => {
                  setStep('intro')
                  setPairingToken('')
                }}
                className="text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white flex items-center justify-center mx-auto"
              >
                <RefreshCw className="w-4 h-4 mr-2" />
                Generate New Code
              </button>
            </div>
          )}

          {step === 'pairing' && (
            <div className="text-center py-12">
              <div className="animate-spin rounded-full h-16 w-16 border-b-2 border-blue-600 mx-auto mb-4"></div>
              <h2 className="text-xl font-semibold text-gray-900 dark:text-white mb-2">
                Pairing Device...
              </h2>
              <p className="text-gray-600 dark:text-gray-400">
                Please wait while we connect to your phone
              </p>
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

          {step === 'error' && (
            <div className="text-center py-12">
              <AlertCircle className="w-20 h-20 text-red-500 mx-auto mb-4" />
              <h2 className="text-2xl font-semibold text-gray-900 dark:text-white mb-2">
                Pairing Failed
              </h2>
              <p className="text-gray-600 dark:text-gray-400 mb-6">{errorMessage}</p>
              <button
                onClick={() => setStep('intro')}
                className="bg-blue-600 hover:bg-blue-700 text-white font-semibold py-3 px-6 rounded-lg transition-colors"
              >
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
