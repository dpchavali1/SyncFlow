'use client'

import { useMemo, useState, useRef, useEffect } from 'react'
import { Search, User, GripVertical, X } from 'lucide-react'
import { useAppStore } from '@/lib/store'
import { format } from 'date-fns'

interface Conversation {
  address: string
  normalizedAddress: string  // For deduplication
  allAddresses: string[]     // All addresses that map to this conversation
  contactName?: string
  lastMessage: string
  timestamp: number
  unreadCount: number
}

// Normalize phone number for comparison (same logic as Android app)
function normalizePhoneNumber(address: string): string {
  // Skip non-phone addresses (email, short codes, etc.)
  if (address.includes('@') || address.length < 6) {
    return address.toLowerCase()
  }

  // Remove all non-digit characters
  const digitsOnly = address.replace(/[^0-9]/g, '')

  // For comparison, use last 10 digits (handles country code differences)
  if (digitsOnly.length >= 10) {
    return digitsOnly.slice(-10)
  }
  return digitsOnly
}

export default function ConversationList() {
  const {
    messages,
    readReceipts,
    selectedConversation,
    setSelectedConversation,
    spamMessages,
    selectedSpamAddress,
    setSelectedSpamAddress,
    activeFolder,
    setActiveFolder,
    isSidebarOpen,
    setIsConversationListVisible,
  } = useAppStore()
  const [searchQuery, setSearchQuery] = useState('')
  const [width, setWidth] = useState(320) // Default width in pixels
  const [isResizing, setIsResizing] = useState(false)
  const resizeRef = useRef<HTMLDivElement>(null)

  // Min and max width constraints
  const MIN_WIDTH = 250
  const MAX_WIDTH = 500

  const readReceiptsBaseline = useMemo(() => {
    if (typeof window === 'undefined') return 0
    const key = 'syncflow_read_receipts_baseline'
    const existing = localStorage.getItem(key)
    if (existing) {
      const parsed = Number(existing)
      return Number.isFinite(parsed) ? parsed : 0
    }
    const now = Date.now()
    localStorage.setItem(key, String(now))
    return now
  }, [])

  // Handle resize
  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (!isResizing) return
      const newWidth = Math.min(Math.max(e.clientX, MIN_WIDTH), MAX_WIDTH)
      setWidth(newWidth)
    }

    const handleMouseUp = () => {
      setIsResizing(false)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }

    if (isResizing) {
      document.body.style.cursor = 'col-resize'
      document.body.style.userSelect = 'none'
      document.addEventListener('mousemove', handleMouseMove)
      document.addEventListener('mouseup', handleMouseUp)
    }

    return () => {
      document.removeEventListener('mousemove', handleMouseMove)
      document.removeEventListener('mouseup', handleMouseUp)
    }
  }, [isResizing])

  // Group messages by normalized address to create deduplicated conversations
  const conversations = useMemo(() => {
    const convMap = new Map<string, Conversation>()

    messages.forEach((msg) => {
      const normalized = normalizePhoneNumber(msg.address)
      const existing = convMap.get(normalized)
      const isUnread =
        msg.type === 1 &&
        msg.date >= readReceiptsBaseline &&
        !readReceipts[msg.id]

      if (!existing) {
        // New conversation
        convMap.set(normalized, {
          address: msg.address,
          normalizedAddress: normalized,
          allAddresses: [msg.address],
          contactName: msg.contactName,
          lastMessage: msg.body,
          timestamp: msg.date,
          unreadCount: isUnread ? 1 : 0,
        })
      } else {
        // Update existing conversation if this message is newer
        if (msg.date > existing.timestamp) {
          existing.lastMessage = msg.body
          existing.timestamp = msg.date
          // Prefer the contact name if available
          if (msg.contactName && !existing.contactName) {
            existing.contactName = msg.contactName
          }
        }
        // Track all addresses that map to this conversation
        if (!existing.allAddresses.includes(msg.address)) {
          existing.allAddresses.push(msg.address)
        }
        if (isUnread) {
          existing.unreadCount += 1
        }
      }
    })

    return Array.from(convMap.values()).sort((a, b) => b.timestamp - a.timestamp)
  }, [messages, readReceipts, readReceiptsBaseline])

  const spamConversations = useMemo(() => {
    const grouped = new Map<string, { address: string; contactName?: string; lastMessage: string; timestamp: number; count: number }>()
    spamMessages.forEach((msg) => {
      const existing = grouped.get(msg.address)
      if (!existing) {
        grouped.set(msg.address, {
          address: msg.address,
          contactName: msg.contactName,
          lastMessage: msg.body,
          timestamp: msg.date,
          count: 1,
        })
      } else {
        existing.count += 1
        if (msg.date > existing.timestamp) {
          existing.lastMessage = msg.body
          existing.timestamp = msg.date
          if (!existing.contactName && msg.contactName) {
            existing.contactName = msg.contactName
          }
        }
      }
    })
    return Array.from(grouped.values()).sort((a, b) => b.timestamp - a.timestamp)
  }, [spamMessages])

  // Filter conversations by search query
  const filteredConversations = useMemo(() => {
    if (!searchQuery.trim()) return conversations

    const query = searchQuery.toLowerCase()
    const queryDigits = query.replace(/[^0-9]/g, '')
    return conversations.filter(conv =>
      (conv.contactName?.toLowerCase().includes(query)) ||
      conv.address.toLowerCase().includes(query) ||
      conv.lastMessage.toLowerCase().includes(query) ||
      (queryDigits.length > 0 && (() => {
        const addressDigits = conv.address.replace(/[^0-9]/g, '')
        const normalized = normalizePhoneNumber(conv.address)
        return addressDigits.includes(queryDigits) ||
          queryDigits.includes(addressDigits) ||
          normalized.includes(queryDigits) ||
          queryDigits.includes(normalized)
      })())
    )
  }, [conversations, searchQuery])

  const filteredSpamConversations = useMemo(() => {
    if (!searchQuery.trim()) return spamConversations
    const query = searchQuery.toLowerCase()
    const queryDigits = query.replace(/[^0-9]/g, '')
    return spamConversations.filter(conv =>
      (conv.contactName?.toLowerCase().includes(query)) ||
      conv.address.toLowerCase().includes(query) ||
      conv.lastMessage.toLowerCase().includes(query) ||
      (queryDigits.length > 0 && conv.address.replace(/[^0-9]/g, '').includes(queryDigits))
    )
  }, [spamConversations, searchQuery])

  if (!isSidebarOpen) {
    return null
  }

  return (
    <div
      className="relative bg-white dark:bg-gray-800 border-r border-gray-200 dark:border-gray-700 flex flex-col min-h-0 overflow-hidden"
      style={{ width: `${width}px`, minWidth: `${MIN_WIDTH}px`, maxWidth: `${MAX_WIDTH}px` }}
    >
       {/* Search */}
       <div className="flex-shrink-0 p-4 border-b border-gray-200 dark:border-gray-700">
         {/* Close Button */}
         <div className="flex justify-end mb-3">
           <button
             onClick={() => setIsConversationListVisible(false)}
             className="p-1 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 rounded hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
             title="Hide conversation list"
           >
             <X className="w-4 h-4" />
           </button>
         </div>
         <div className="flex items-center gap-2 mb-3">
          <button
            onClick={() => {
              setActiveFolder('inbox')
              setSelectedSpamAddress(null)
            }}
            className={`px-3 py-1 rounded-full text-xs font-medium ${
              activeFolder === 'inbox'
                ? 'bg-blue-600 text-white'
                : 'bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300'
            }`}
          >
            Inbox
          </button>
          <button
            onClick={() => {
              setActiveFolder('spam')
              setSelectedConversation(null)
            }}
            className={`px-3 py-1 rounded-full text-xs font-medium ${
              activeFolder === 'spam'
                ? 'bg-red-600 text-white'
                : 'bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300'
            }`}
          >
            Spam {spamMessages.length > 0 ? `(${spamMessages.length})` : ''}
          </button>
        </div>
        <div className="relative">
          <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-5 h-5 text-gray-400" />
          <input
            type="text"
            placeholder="Search conversations..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-10 pr-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-gray-50 dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          />
        </div>
      </div>

      {/* Conversations */}
      <div className="flex-1 min-h-0 h-0 overflow-y-auto">
        {activeFolder === 'spam' ? (
          filteredSpamConversations.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-full p-8 text-center">
              <User className="w-16 h-16 text-gray-300 dark:text-gray-600 mb-4" />
              <p className="text-gray-500 dark:text-gray-400 mb-2">
                {searchQuery ? 'No spam matches found' : 'No spam messages'}
              </p>
              <p className="text-sm text-gray-400 dark:text-gray-500">
                Spam messages from your phone will appear here
              </p>
            </div>
          ) : (
            filteredSpamConversations.map((conv) => (
              <div
                key={conv.address}
                onClick={() => {
                  setSelectedSpamAddress(conv.address)
                }}
                className={`p-4 border-b border-gray-100 dark:border-gray-700 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors ${
                  selectedSpamAddress === conv.address
                    ? 'bg-red-50 dark:bg-red-900/20 border-l-4 border-l-red-600'
                    : ''
                }`}
              >
                <div className="flex items-start gap-3">
                  <div className="w-12 h-12 rounded-full bg-red-100 dark:bg-red-900/40 flex items-center justify-center text-red-600 font-semibold flex-shrink-0">
                    {(conv.contactName || conv.address).charAt(0).toUpperCase()}
                  </div>

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

                  <div className="text-xs text-red-600 font-semibold px-2 py-1 bg-red-50 dark:bg-red-900/30 rounded-full">
                    {conv.count}
                  </div>
                </div>
              </div>
            ))
          )
        ) : filteredConversations.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full p-8 text-center">
            <User className="w-16 h-16 text-gray-300 dark:text-gray-600 mb-4" />
            <p className="text-gray-500 dark:text-gray-400 mb-2">
              {searchQuery ? 'No matches found' : 'No messages yet'}
            </p>
            <p className="text-sm text-gray-400 dark:text-gray-500">
              {searchQuery ? 'Try a different search term' : 'Messages from your phone will appear here'}
            </p>
          </div>
        ) : (
          filteredConversations.map((conv) => (
            <div
              key={conv.normalizedAddress}
              onClick={() => setSelectedConversation(conv.normalizedAddress)}
              className={`p-4 border-b border-gray-100 dark:border-gray-700 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors ${
                selectedConversation === conv.normalizedAddress
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

      {/* Resize handle */}
      <div
        ref={resizeRef}
        onMouseDown={() => setIsResizing(true)}
        className="absolute top-0 right-0 w-1 h-full cursor-col-resize hover:bg-blue-500 transition-colors group flex items-center justify-center"
        style={{ backgroundColor: isResizing ? 'rgb(59, 130, 246)' : 'transparent' }}
      >
        <div className="absolute right-0 top-1/2 -translate-y-1/2 w-4 h-8 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity">
          <GripVertical className="w-3 h-3 text-gray-400" />
        </div>
      </div>
    </div>
  )
}
