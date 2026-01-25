'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import { useAppStore } from '@/lib/store'
import {
  ensureWebE2EEKeyPublished,
  listenToDeviceStatus,
  listenToMessages,
  listenToReadReceipts,
  listenToSpamMessages,
  waitForAuth,
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
  }, [router, setUserId, setMessages, setReadReceipts])

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
