'use client'

import { useMemo } from 'react'
import { Search, User } from 'lucide-react'
import { useAppStore } from '@/lib/store'
import { format } from 'date-fns'

interface Conversation {
  address: string
  contactName?: string
  lastMessage: string
  timestamp: number
  unreadCount: number
}

export default function ConversationList() {
  const { messages, selectedConversation, setSelectedConversation, isSidebarOpen } = useAppStore()

  // Group messages by address to create conversations
  const conversations = useMemo(() => {
    const convMap = new Map<string, Conversation>()

    messages.forEach((msg) => {
      const existing = convMap.get(msg.address)

      if (!existing || msg.date > existing.timestamp) {
        convMap.set(msg.address, {
          address: msg.address,
          contactName: msg.contactName,
          lastMessage: msg.body,
          timestamp: msg.date,
          unreadCount: 0,
        })
      }
    })

    return Array.from(convMap.values()).sort((a, b) => b.timestamp - a.timestamp)
  }, [messages])

  return (
    <div
      className={`${
        isSidebarOpen ? 'w-full md:w-80' : 'hidden'
      } bg-white dark:bg-gray-800 border-r border-gray-200 dark:border-gray-700 flex flex-col`}
    >
      {/* Search */}
      <div className="p-4 border-b border-gray-200 dark:border-gray-700">
        <div className="relative">
          <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-5 h-5 text-gray-400" />
          <input
            type="text"
            placeholder="Search conversations..."
            className="w-full pl-10 pr-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-gray-50 dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          />
        </div>
      </div>

      {/* Conversations */}
      <div className="flex-1 overflow-y-auto">
        {conversations.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full p-8 text-center">
            <User className="w-16 h-16 text-gray-300 dark:text-gray-600 mb-4" />
            <p className="text-gray-500 dark:text-gray-400 mb-2">No messages yet</p>
            <p className="text-sm text-gray-400 dark:text-gray-500">
              Messages from your phone will appear here
            </p>
          </div>
        ) : (
          conversations.map((conv) => (
            <div
              key={conv.address}
              onClick={() => setSelectedConversation(conv.address)}
              className={`p-4 border-b border-gray-100 dark:border-gray-700 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors ${
                selectedConversation === conv.address
                  ? 'bg-blue-50 dark:bg-blue-900/20 border-l-4 border-l-blue-600'
                  : ''
              }`}
            >
              <div className="flex items-start gap-3">
                {/* Avatar */}
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-blue-400 to-blue-600 flex items-center justify-center text-white font-semibold flex-shrink-0">
                  {(conv.contactName || conv.address).charAt(0).toUpperCase()}
                </div>

                {/* Content */}
                <div className="flex-1 min-w-0">
                  <div className="flex items-baseline justify-between mb-1">
                    <h3 className="font-semibold text-gray-900 dark:text-white truncate">
                      {conv.contactName || conv.address}
                    </h3>
                    <span className="text-xs text-gray-500 dark:text-gray-400 ml-2 flex-shrink-0">
                      {format(new Date(conv.timestamp), 'MMM d')}
                    </span>
                  </div>

                  <p className="text-sm text-gray-600 dark:text-gray-400 truncate">
                    {conv.lastMessage}
                  </p>
                </div>

                {/* Unread badge */}
                {conv.unreadCount > 0 && (
                  <div className="w-6 h-6 rounded-full bg-blue-600 text-white text-xs flex items-center justify-center flex-shrink-0">
                    {conv.unreadCount}
                  </div>
                )}
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  )
}
