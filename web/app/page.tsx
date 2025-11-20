'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import PairingScreen from '@/components/PairingScreen'
import { useAppStore } from '@/lib/store'

export default function Home() {
  const router = useRouter()
  const { userId, isAuthenticated } = useAppStore()
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    // Check if user is already paired (stored in localStorage)
    const storedUserId = localStorage.getItem('syncflow_user_id')

    if (storedUserId) {
      useAppStore.getState().setUserId(storedUserId)
      router.push('/messages')
    } else {
      setIsLoading(false)
    }
  }, [router])

  if (isLoading) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100 dark:from-gray-900 dark:to-gray-800">
        <div className="text-center">
          <div className="animate-spin rounded-full h-16 w-16 border-b-2 border-blue-600 mx-auto mb-4"></div>
          <p className="text-gray-600 dark:text-gray-400">Loading SyncFlow...</p>
        </div>
      </div>
    )
  }

  return <PairingScreen />
}
