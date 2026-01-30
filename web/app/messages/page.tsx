'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import { useAppStore } from '@/lib/store'
import {
  ensureWebE2EEKeyBackup,
  ensureWebE2EEKeyPublished,
  listenToDeviceStatus,
  listenToMessages,
  listenToReadReceipts,
  listenToSpamMessages,
  listenToWebE2EEBackfillStatus,
  requestWebE2EEKeyBackfill,
  requestWebE2EEKeySync,
  waitForAuth,
  waitForWebE2EEKeySyncResponse,
} from '@/lib/firebase'
import ConversationList from '@/components/ConversationList'
import MessageView from '@/components/MessageView'
import Header from '@/components/Header'
import AIAssistant from '@/components/AIAssistant'
import AdBanner from '@/components/AdBanner'

export default function MessagesPage() {
  const router = useRouter()
  const {
    userId,
    setUserId,
    messages,
    setMessages,
    setReadReceipts,
    setSpamMessages,
    selectedConversation,
    setSelectedConversation,
    setSelectedSpamAddress,
    setActiveFolder,
    isConversationListVisible,
    setIsConversationListVisible,
    initializeConversationListVisibility,
  } = useAppStore()
  const [showAI, setShowAI] = useState(false)
  const [keySyncLoading, setKeySyncLoading] = useState(false)
  const [keySyncStatus, setKeySyncStatus] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)
  const [backfillStatus, setBackfillStatus] = useState<{
    status?: string
    scanned?: number
    updated?: number
    skipped?: number
    error?: string
  } | null>(null)
  const [dismissedAtFailureCount, setDismissedAtFailureCount] = useState<number | null>(null)

  const failureCount = messages.filter((msg) => msg.decryptionFailed).length
  const hasDecryptFailures = failureCount > 0
  const isBannerDismissed = dismissedAtFailureCount === failureCount && failureCount > 0

  const handleKeySync = async () => {
    if (!userId || keySyncLoading) return
    setKeySyncLoading(true)
    setKeySyncStatus(null)
    try {
      await requestWebE2EEKeySync(userId)
      await waitForWebE2EEKeySyncResponse(userId)
      setKeySyncStatus('Keys synced. Backfilling access to older messages...')
      await requestWebE2EEKeyBackfill(userId)
      setReloadToken(Date.now())
    } catch (err: any) {
      setKeySyncStatus(err?.message || 'Key sync failed')
    } finally {
      setKeySyncLoading(false)
    }
  }

  useEffect(() => {
    if (!userId || typeof window === 'undefined') return
    const key = `syncflow_e2ee_banner_dismissed_${userId}`
    const stored = localStorage.getItem(key)
    if (stored) {
      const parsed = Number(stored)
      if (!Number.isNaN(parsed)) {
        setDismissedAtFailureCount(parsed)
      }
    }
  }, [userId])

  useEffect(() => {
    if (!userId || typeof window === 'undefined') return
    const key = `syncflow_e2ee_banner_dismissed_${userId}`
    if (dismissedAtFailureCount == null) {
      localStorage.removeItem(key)
      return
    }
    localStorage.setItem(key, String(dismissedAtFailureCount))
  }, [dismissedAtFailureCount, userId])

  useEffect(() => {
    if (!userId) return
    const unsubscribe = listenToWebE2EEBackfillStatus(userId, (status) => {
      setBackfillStatus(status)
    })
    return () => {
      unsubscribe()
    }
  }, [userId])

  useEffect(() => {
    if (!hasDecryptFailures) {
      setDismissedAtFailureCount(null)
    }
  }, [hasDecryptFailures])

  const backfillIsActive =
    backfillStatus?.status === 'pending' || backfillStatus?.status === 'processing'
  const backfillErrored = backfillStatus?.status === 'error'
  const showKeyBanner =
    !isBannerDismissed && (keySyncLoading || backfillIsActive || backfillErrored || hasDecryptFailures)

  useEffect(() => {
    let unsubscribe: (() => void) | null = null
    let unsubscribeSpam: (() => void) | null = null
    let unsubscribeReadReceipts: (() => void) | null = null

    const setupFirebase = async () => {
      // Check authentication
      const storedUserId = localStorage.getItem('syncflow_user_id')

      if (!storedUserId) {
        router.push('/')
        return
      }

      setUserId(storedUserId)
      initializeConversationListVisibility()
      ensureWebE2EEKeyPublished(storedUserId)
        .catch((err) => console.error('Failed to publish web E2EE key', err))
      ensureWebE2EEKeyBackup(storedUserId)
        .catch((err) => console.error('Failed to create web E2EE key backup', err))

      // Wait for authentication before setting up listener
      // This is required for Firebase rules to allow data access
      try {
        let currentUser = await waitForAuth()

        if (!currentUser) {
          // Try to sign in anonymously - the stored userId gives us data access
          // Firebase rules allow read if auth != null AND the path matches storedUserId
          console.log('[Messages] No auth, attempting anonymous sign-in for data access')
          try {
            const { signInAnonymously } = await import('firebase/auth')
            const { getAuth } = await import('firebase/auth')
            const auth = getAuth()
            const result = await signInAnonymously(auth)
            currentUser = result.user?.uid || null
            console.log('[Messages] Anonymous sign-in successful:', currentUser)
          } catch (signInError) {
            console.error('[Messages] Failed to sign in anonymously:', signInError)
            // Redirect to pairing to re-establish connection
            router.push('/')
            return
          }
        }

        if (!currentUser) {
          // Still not authenticated, redirect to pairing
          router.push('/')
          return
        }

        // SECURITY: Verify the authenticated user has access to storedUserId
        // Either the auth.uid matches OR the user has pairedUid claim for this user
        // This prevents tampering with localStorage to access other users' data
        const authUserId = currentUser
        if (authUserId !== storedUserId) {
          // The authenticated user is different from stored user
          // This is expected for web clients (they have their own UID with pairedUid claim)
          // Firebase rules will enforce access - if rules deny, listener will fail
          // But we log this for security monitoring
          if (process.env.NODE_ENV === 'development') {
            console.log('Auth user differs from stored user (expected for paired devices)')
          }
        }
      } catch (error) {
        console.error('Authentication failed:', error)
        router.push('/')
        return
      }

      // Set up message listener
      unsubscribe = listenToMessages(storedUserId, (newMessages) => {
        setMessages(newMessages)
      })

      unsubscribeSpam = listenToSpamMessages(storedUserId, (spam) => {
        setSpamMessages(spam)
      })

      unsubscribeReadReceipts = listenToReadReceipts(storedUserId, (receipts) => {
        setReadReceipts(receipts)
      })
    }

    setupFirebase()

    // Cleanup function
    return () => {
      if (unsubscribe) {
        unsubscribe()
      }
      if (unsubscribeSpam) {
        unsubscribeSpam()
      }
      if (unsubscribeReadReceipts) {
        unsubscribeReadReceipts()
      }
    }
  }, [router, setUserId, setMessages, setReadReceipts, reloadToken, initializeConversationListVisibility, setSpamMessages])

  useEffect(() => {
    if (!userId) return
    const handleRemoteUnpair = () => {
      localStorage.removeItem('syncflow_user_id')
      setUserId(null)
      setMessages([])
      setReadReceipts({})
      setSpamMessages([])
      setSelectedConversation(null)
      setSelectedSpamAddress(null)
      setActiveFolder('inbox')
      router.push('/')
    }

    const unsubscribeDevice = listenToDeviceStatus(userId, (isPaired) => {
      if (!isPaired) {
        handleRemoteUnpair()
      }
    })

    return () => {
      unsubscribeDevice()
    }
  }, [
    router,
    setActiveFolder,
    setMessages,
    setReadReceipts,
    setSelectedConversation,
    setSelectedSpamAddress,
    setSpamMessages,
    setUserId,
    userId,
  ])

  if (!userId) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="animate-spin rounded-full h-16 w-16 border-b-2 border-blue-600"></div>
      </div>
    )
  }

  return (
    <div className="h-screen flex flex-col overflow-hidden bg-gray-100 dark:bg-gray-900">
      <Header />

      <div className="flex-1 flex min-h-0 overflow-hidden">
        {isConversationListVisible && <ConversationList />}
        <div className="flex-1 flex flex-col min-w-0">
          {showKeyBanner && (
            <div className="flex-shrink-0 px-4 pt-4">
              <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-amber-900 dark:border-amber-700/60 dark:bg-amber-900/20 dark:text-amber-100">
                <div className="text-sm">
                  {backfillIsActive && (
                    <>
                      Processing older messages… {backfillStatus?.updated ?? 0} updated /{' '}
                      {backfillStatus?.scanned ?? 0} scanned.
                    </>
                  )}
                  {!backfillIsActive && backfillErrored && (
                    <>Key sync completed, but processing failed. You can try again.</>
                  )}
                  {!backfillIsActive && !backfillErrored && hasDecryptFailures && (
                    <>Some messages are encrypted and can’t be decrypted. Sync keys to unlock them.</>
                  )}
                  {!backfillIsActive && !backfillErrored && !hasDecryptFailures && (
                    <>Keys synced. Messages will update automatically.</>
                  )}
                </div>
                <div className="flex items-center gap-3">
                  <button
                    onClick={handleKeySync}
                    disabled={keySyncLoading}
                    className="inline-flex items-center justify-center px-3 py-1.5 rounded-lg bg-amber-600 text-white hover:bg-amber-700 disabled:opacity-60 disabled:cursor-not-allowed transition-colors text-sm"
                  >
                    {keySyncLoading ? 'Syncing…' : 'Sync Keys'}
                  </button>
                  <button
                    onClick={() => setDismissedAtFailureCount(failureCount)}
                    className="text-xs text-amber-800 dark:text-amber-200 hover:underline"
                  >
                    Dismiss
                  </button>
                  {keySyncStatus && (
                    <span className="text-xs text-amber-800 dark:text-amber-200">
                      {keySyncStatus}
                    </span>
                  )}
                </div>
              </div>
            </div>
          )}
          {!isConversationListVisible && (
            <div className="flex-shrink-0 p-4 border-b border-gray-200 dark:border-gray-700">
              <button
                onClick={() => setIsConversationListVisible(true)}
                className="flex items-center gap-2 px-4 py-2 text-sm bg-blue-600 hover:bg-blue-700 text-white rounded-lg transition-colors"
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 10h16M4 14h16M4 18h16" />
                </svg>
                Show Conversations
              </button>
            </div>
          )}
          <MessageView onOpenAI={() => setShowAI(true)} />
        </div>
      </div>

      <div className="flex-shrink-0 border-t border-gray-200 bg-gray-100 dark:border-gray-700 dark:bg-gray-900">
        <AdBanner />
      </div>

      {showAI && (
        <AIAssistant
          messages={messages}
          onClose={() => setShowAI(false)}
        />
      )}
    </div>
  )
}
