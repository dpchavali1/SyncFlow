'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { useAppStore } from '@/lib/store'
import { listenToMessages, getCurrentUserId } from '@/lib/firebase'
import ConversationList from '@/components/ConversationList'
import MessageView from '@/components/MessageView'
import Header from '@/components/Header'

export default function MessagesPage() {
  const router = useRouter()
  const { userId, setUserId, messages, setMessages, selectedConversation } = useAppStore()

  useEffect(() => {
    // Check authentication
    const storedUserId = localStorage.getItem('syncflow_user_id')

    if (!storedUserId) {
      router.push('/')
      return
    }

    setUserId(storedUserId)

    // Listen to messages from Firebase
    const unsubscribe = listenToMessages(storedUserId, (newMessages) => {
      setMessages(newMessages)
    })

    return () => {
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
    </div>
  )
}
