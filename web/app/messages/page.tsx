'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import { useAppStore } from '@/lib/store'
import { listenToMessages, waitForAuth, signInAnon } from '@/lib/firebase'
import ConversationList from '@/components/ConversationList'
import MessageView from '@/components/MessageView'
import Header from '@/components/Header'
import IncomingCallNotification from '@/components/IncomingCallNotification'
import AIAssistant from '@/components/AIAssistant'
import { Brain } from 'lucide-react'

export default function MessagesPage() {
  const router = useRouter()
  const { userId, setUserId, messages, setMessages, selectedConversation } = useAppStore()
  const [showAI, setShowAI] = useState(false)

  useEffect(() => {
    let unsubscribe: (() => void) | null = null

    const setupFirebase = async () => {
      // Check authentication
      const storedUserId = localStorage.getItem('syncflow_user_id')
      console.log('=== Messages Page Debug ===')
      console.log('Stored User ID:', storedUserId)

      if (!storedUserId) {
        console.log('No user ID found, redirecting to home')
        router.push('/')
        return
      }

      setUserId(storedUserId)

      // Wait for authentication before setting up listener
      // This is required for Firebase rules to allow data access
      try {
        console.log('⏱️ Checking authentication...')
        const authStartTime = performance.now()
        const currentUser = await waitForAuth()
        const authEndTime = performance.now()

        if (!currentUser) {
          console.log('⏱️ Not authenticated, signing in...')
          const signInStart = performance.now()
          await signInAnon()
          const signInEnd = performance.now()
          console.log(`✅ Sign-in completed in ${(signInEnd - signInStart).toFixed(0)}ms`)
        } else {
          console.log(`✅ Already authenticated in ${(authEndTime - authStartTime).toFixed(0)}ms:`, currentUser)
        }
      } catch (error) {
        console.error('❌ Authentication failed:', error)
        return
      }

      // Now set up listener with authentication ready
      console.log('📱 Using phone user ID:', storedUserId)
      console.log('⏱️ Setting up Firebase listener...')
      const listenerStartTime = performance.now()
      unsubscribe = listenToMessages(storedUserId, (newMessages) => {
        console.log('🔔 FIREBASE CALLBACK: Received', newMessages.length, 'messages')
        console.log('Messages:', newMessages.slice(0, 3)) // Log first 3 messages
        setMessages(newMessages)
      })
      console.log('✅ Firebase listener set up successfully')
    }

    setupFirebase()

    // Cleanup function
    return () => {
      console.log('🧹 Cleaning up Firebase listener')
      if (unsubscribe) {
        unsubscribe()
      }
    }
  }, [router, setUserId, setMessages])

  if (!userId) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="animate-spin rounded-full h-16 w-16 border-b-2 border-blue-600"></div>
      </div>
    )
  }

  return (
    <div className="flex flex-col h-screen bg-gray-100 dark:bg-gray-900">
      <Header />

      <div className="flex flex-1 overflow-hidden">
        {/* Conversation List */}
        <ConversationList />

        {/* Message View */}
        <MessageView />
      </div>

      {/* Incoming Call Notification */}
      <IncomingCallNotification userId={userId} />

      {/* AI Assistant Button */}
      <button
        onClick={() => setShowAI(true)}
        className="fixed bottom-6 right-6 w-14 h-14 bg-gradient-to-br from-purple-500 to-blue-600 hover:from-purple-600 hover:to-blue-700 text-white rounded-full shadow-lg flex items-center justify-center transition-all hover:scale-110 z-40"
        title="AI Assistant"
      >
        <Brain className="w-6 h-6" />
      </button>

      {/* AI Assistant Modal */}
      {showAI && (
        <AIAssistant
          messages={messages}
          onClose={() => setShowAI(false)}
        />
      )}
    </div>
  )
}
