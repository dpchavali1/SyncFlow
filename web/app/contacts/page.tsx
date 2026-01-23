'use client'

import { useState, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import ContactsList from '@/components/ContactsList'
import { waitForAuth } from '@/lib/firebase'

export default function ContactsPage() {
  const router = useRouter()
  const [userId, setUserId] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    const checkAuth = async () => {
      const uid = await waitForAuth()
      if (uid) {
        setUserId(uid)
      } else {
        // Redirect to home if not paired
        router.push('/')
      }
      setIsLoading(false)
    }
    checkAuth()
  }, [router])

  const handleSelectContact = (phoneNumber: string, contactName: string) => {
    // Navigate to messages with this contact
    router.push(`/messages?address=${encodeURIComponent(phoneNumber)}&name=${encodeURIComponent(contactName)}`)
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-50 dark:bg-gray-900">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600"></div>
      </div>
    )
  }

  if (!userId) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-50 dark:bg-gray-900">
        <p className="text-gray-600 dark:text-gray-400">Please pair your device first.</p>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      {/* Navigation Header */}
      <nav className="bg-white dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700">
        <div className="max-w-7xl mx-auto px-4">
          <div className="flex items-center justify-between h-16">
            <div className="flex items-center gap-6">
              <h1 className="text-xl font-bold text-blue-600">SyncFlow</h1>
              <div className="flex items-center gap-4">
                <a
                  href="/messages"
                  className="text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white transition-colors"
                >
                  Messages
                </a>
                <a
                  href="/contacts"
                  className="text-blue-600 font-medium"
                >
                  Contacts
                </a>
              </div>
            </div>
          </div>
        </div>
      </nav>

      {/* Contacts List */}
      <main className="max-w-4xl mx-auto" style={{ height: 'calc(100vh - 4rem)' }}>
        <ContactsList userId={userId} onSelectContact={handleSelectContact} />
      </main>
    </div>
  )
}
