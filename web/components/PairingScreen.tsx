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
   const [isInitializing, setIsInitializing] = useState(false)
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
     if (isInitializing) {
       console.log('Initialize already in progress, skipping')
       return
     }

     setIsInitializing(true)
     setStep('loading')
     setErrorMessage('')

     try {
       console.log('Starting sync initialization...')

       // Authenticate first (anonymous)
       const user = await signInAnon()

       // Store user ID so messages page recognizes the session
       if (user?.uid) {
         localStorage.setItem('syncflow_user_id', user.uid)
         console.log('Stored user ID:', user.uid)
       }

       // Try to recover existing sync group first
       const recovered = await recoverSyncGroup('web')
       if (recovered.success && recovered.syncGroupId) {
         console.log('Recovered existing sync group:', recovered.syncGroupId)
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

         // Scroll to QR code when ready
         setTimeout(() => {
           document.getElementById('qr-code-section')?.scrollIntoView({
             behavior: 'smooth',
             block: 'center'
           })
         }, 500)

         // Auto-redirect after 5 seconds (gives time to see QR code and scroll)
         if (redirectTimeoutRef.current) clearTimeout(redirectTimeoutRef.current)
         redirectTimeoutRef.current = setTimeout(() => {
           router.push('/messages')
         }, 5000)
         return
       }

       console.log('No existing sync group, creating new one...')

       // No existing group, create new one
       const newGroupId = getSyncGroupId()
       const created = await createSyncGroup(newGroupId, 'web')

       if (created) {
         console.log('Created new sync group:', newGroupId)
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

         // Scroll to QR code when ready
         setTimeout(() => {
           document.getElementById('qr-code-section')?.scrollIntoView({
             behavior: 'smooth',
             block: 'center'
           })
         }, 500)

         // Auto-redirect after 5 seconds (gives time to see QR code and scroll)
         if (redirectTimeoutRef.current) clearTimeout(redirectTimeoutRef.current)
         redirectTimeoutRef.current = setTimeout(() => {
           router.push('/messages')
         }, 5000)
       } else {
         console.error('Failed to create sync group')
         setStep('error')
         setErrorMessage('Failed to initialize pairing')
       }
     } catch (error: any) {
       console.error('Failed to initialize sync:', error)
       setStep('error')
       setErrorMessage(error.message || 'Failed to initialize pairing')
     } finally {
       setIsInitializing(false)
     }
   }, [isInitializing, setSyncGroupId, setDeviceInfo])

   // Call once on mount
   const initialized = useRef(false)
   useEffect(() => {
     if (!initialized.current && !isInitializing) {
       initialized.current = true
       console.log('PairingScreen mounted, initializing sync...')
       initializeSync()
     }
     return () => {
       if (redirectTimeoutRef.current) {
         clearTimeout(redirectTimeoutRef.current)
       }
     }
   }, [initializeSync, isInitializing])

  return (
    <div className="h-screen bg-gradient-to-br from-blue-50 to-indigo-100 dark:from-gray-900 dark:to-gray-800 overflow-y-auto">
      {/* Loading overlay during initialization */}
      {isInitializing && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white dark:bg-gray-800 rounded-lg p-6 shadow-xl">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600 mx-auto mb-4"></div>
            <p className="text-gray-600 dark:text-gray-400">Initializing pairing...</p>
          </div>
        </div>
      )}

      <div className="max-w-2xl mx-auto w-full py-8 px-4">
        {/* Logo and Title */}
        <div className="text-center mb-6">
          <div className="flex items-center justify-center mb-3">
            <Smartphone className="w-10 h-10 text-blue-600 mr-2" />
            <h1 className="text-3xl font-bold text-gray-900 dark:text-white">SyncFlow</h1>
          </div>
          <p className="text-gray-600 dark:text-gray-400 text-sm">
            Access your phone messages from your desktop
          </p>
        </div>

        {/* Main Card */}
        <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-xl p-6">
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
              <h2 className="text-xl font-semibold text-gray-900 dark:text-white mb-1">
                 Sync Group Ready
               </h2>
               <p className="text-gray-600 dark:text-gray-400 text-sm mb-4">
                 Share this QR code to add more devices
               </p>

               {/* QR Code */}
               {syncGroupId && (
                 <div id="qr-code-section" className="bg-white p-4 rounded-xl inline-block mb-4 shadow-inner border border-gray-200">
                   <QRCodeSVG
                     value={syncGroupId}
                     size={180}
                     level="M"
                     includeMargin={false}
                   />
                 </div>
               )}

               {/* Device Info & Instructions in compact layout */}
               <div className="space-y-3">
                 {/* Device Info */}
                 <div className="bg-blue-50 dark:bg-blue-900/20 rounded-lg p-3">
                   <p className="text-sm text-gray-700 dark:text-gray-300">
                     <span className="font-semibold">{deviceCount}/{deviceLimit}</span> devices connected
                     {deviceCount >= deviceLimit && (
                       <span className="text-yellow-600 dark:text-yellow-400 block text-xs mt-1">
                         Upgrade to Pro for unlimited devices
                       </span>
                     )}
                   </p>
                 </div>

                 {/* Instructions */}
                 <div className="bg-blue-50 dark:bg-blue-900/20 rounded-lg p-3 text-left">
                   <h3 className="font-semibold text-gray-900 dark:text-white mb-2 text-sm">
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

                 {/* Sync Group ID */}
                 <div className="bg-gray-100 dark:bg-gray-700 p-3 rounded-lg text-left">
                   <p className="text-xs text-gray-600 dark:text-gray-400 mb-1">Sync Group ID:</p>
                   <code className="text-xs font-mono text-gray-900 dark:text-white break-all">{syncGroupId}</code>
                 </div>

                 {/* Manual refresh button */}
                 <div className="flex justify-center pt-2">
                   <button
                     onClick={() => {
                       console.log('Manual refresh requested')
                       initialized.current = false
                       setIsInitializing(false)
                       initializeSync()
                     }}
                     disabled={isInitializing}
                     className="flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:bg-blue-400 text-white text-sm rounded-lg transition-colors"
                   >
                     <RefreshCw className={`w-4 h-4 ${isInitializing ? 'animate-spin' : ''}`} />
                     {isInitializing ? 'Refreshing...' : 'Refresh Pairing'}
                   </button>
                 </div>
               </div>


            </div>
          )}

           {step === 'error' && (
             <div className="text-center py-12">
               <AlertCircle className="w-20 h-20 text-red-500 mx-auto mb-4" />
               <h2 className="text-2xl font-semibold text-gray-900 dark:text-white mb-2">
                 Initialization Failed
               </h2>
               <p className="text-gray-600 dark:text-gray-400 mb-6">{errorMessage}</p>
               <div className="space-y-3">
                 <button
                   onClick={() => {
                     console.log('Manual retry requested')
                     initialized.current = false
                     setIsInitializing(false)
                     initializeSync()
                   }}
                   disabled={isInitializing}
                   className="bg-blue-600 hover:bg-blue-700 disabled:bg-blue-400 text-white font-semibold py-3 px-6 rounded-lg transition-colors flex items-center justify-center mx-auto"
                 >
                   <RefreshCw className={`w-5 h-5 mr-2 ${isInitializing ? 'animate-spin' : ''}`} />
                   {isInitializing ? 'Retrying...' : 'Try Again'}
                 </button>
                 <p className="text-xs text-gray-500 dark:text-gray-400">
                   If this persists, try refreshing the page
                 </p>
               </div>
             </div>
           )}
        </div>

        {/* Scroll hint for mobile devices */}
        <div className="flex justify-center mt-4 md:hidden">
          <div className="flex flex-col items-center text-gray-400 dark:text-gray-500">
            <div className="w-6 h-1 bg-gray-300 dark:bg-gray-600 rounded-full mb-1"></div>
            <div className="w-4 h-1 bg-gray-300 dark:bg-gray-600 rounded-full mb-1 opacity-60"></div>
            <div className="w-2 h-1 bg-gray-300 dark:bg-gray-600 rounded-full opacity-40"></div>
          </div>
        </div>

        {/* Footer */}
        <div className="text-center mt-4 space-y-1">
          <p className="text-gray-500 dark:text-gray-400 text-xs">
            Your messages are end-to-end encrypted and never leave your control
          </p>
          <p className="text-xs text-gray-500 dark:text-gray-400">
            Auto-redirecting to messages in 5 seconds...
          </p>
        </div>
      </div>
    </div>
  )
}
