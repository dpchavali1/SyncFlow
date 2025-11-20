'use client'

import { useState, useRef } from 'react'
import { useRouter } from 'next/navigation'
import { QrCode, Smartphone, Monitor, CheckCircle, AlertCircle } from 'lucide-react'
import { pairDeviceWithToken } from '@/lib/firebase'
import { useAppStore } from '@/lib/store'
import QRScanner from './QRScanner'

export default function PairingScreen() {
  const router = useRouter()
  const { setUserId, setIsPairing } = useAppStore()
  const [step, setStep] = useState<'intro' | 'scanning' | 'pairing' | 'success' | 'error'>('intro')
  const [errorMessage, setErrorMessage] = useState('')
  const [deviceName, setDeviceName] = useState('')

  const handleScanComplete = async (token: string) => {
    setStep('pairing')

    try {
      const name = deviceName || `Desktop - ${new Date().toLocaleDateString()}`
      const result = await pairDeviceWithToken(token, name)

      // Store user ID in localStorage
      localStorage.setItem('syncflow_user_id', result.userId)
      setUserId(result.userId)

      setStep('success')

      // Redirect to messages after 2 seconds
      setTimeout(() => {
        router.push('/messages')
      }, 2000)
    } catch (error: any) {
      setStep('error')
      setErrorMessage(error.message || 'Failed to pair device')
    }
  }

  const handleStartScanning = () => {
    if (!deviceName) {
      setDeviceName(`Desktop - ${new Date().toLocaleDateString()}`)
    }
    setStep('scanning')
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
                To get started, you'll need to scan a QR code from your phone's SyncFlow app.
              </p>

              <div className="bg-blue-50 dark:bg-blue-900/20 rounded-lg p-6 mb-6">
                <h3 className="font-semibold text-gray-900 dark:text-white mb-3">
                  Instructions:
                </h3>
                <ol className="text-left text-gray-700 dark:text-gray-300 space-y-2">
                  <li className="flex items-start">
                    <span className="font-semibold mr-2">1.</span>
                    <span>Open SyncFlow app on your phone</span>
                  </li>
                  <li className="flex items-start">
                    <span className="font-semibold mr-2">2.</span>
                    <span>Go to Settings → Desktop Integration</span>
                  </li>
                  <li className="flex items-start">
                    <span className="font-semibold mr-2">3.</span>
                    <span>Tap "Pair New Device"</span>
                  </li>
                  <li className="flex items-start">
                    <span className="font-semibold mr-2">4.</span>
                    <span>Scan the QR code with your computer's camera</span>
                  </li>
                </ol>
              </div>

              <div className="mb-6">
                <label className="block text-left text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                  Device Name (Optional)
                </label>
                <input
                  type="text"
                  value={deviceName}
                  onChange={(e) => setDeviceName(e.target.value)}
                  placeholder={`Desktop - ${new Date().toLocaleDateString()}`}
                  className="w-full px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                />
              </div>

              <button
                onClick={handleStartScanning}
                className="w-full bg-blue-600 hover:bg-blue-700 text-white font-semibold py-3 px-6 rounded-lg transition-colors flex items-center justify-center"
              >
                <QrCode className="w-5 h-5 mr-2" />
                Start Scanning
              </button>
            </div>
          )}

          {step === 'scanning' && (
            <div className="text-center">
              <QRScanner onScanComplete={handleScanComplete} />
              <button
                onClick={() => setStep('intro')}
                className="mt-4 text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white"
              >
                Cancel
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
