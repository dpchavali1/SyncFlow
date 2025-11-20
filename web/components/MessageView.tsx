'use client'

import { useState, useEffect, useRef, useMemo } from 'react'
import { Send, Phone, Video, MoreVertical, MessageSquare } from 'lucide-react'
import { useAppStore } from '@/lib/store'
import { sendSmsFromWeb } from '@/lib/firebase'
import { format } from 'date-fns'

export default function MessageView() {
  const { messages, selectedConversation, userId } = useAppStore()
  const [newMessage, setNewMessage] = useState('')
  const [isSending, setIsSending] = useState(false)
  const messagesEndRef = useRef<HTMLDivElement>(null)

  // Filter messages for selected conversation
  const conversationMessages = useMemo(() => {
    if (!selectedConversation) return []

    return messages
      .filter((msg) => msg.address === selectedConversation)
      .sort((a, b) => a.date - b.date)
  }, [messages, selectedConversation])

  // Auto-scroll to bottom when messages change
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [conversationMessages])

  const handleSend = async () => {
    if (!newMessage.trim() || !userId || !selectedConversation || isSending) return

    setIsSending(true)

    try {
      await sendSmsFromWeb(userId, selectedConversation, newMessage)
      setNewMessage('')
    } catch (error) {
      console.error('Error sending message:', error)
      alert('Failed to send message. Please try again.')
    } finally {
      setIsSending(false)
    }
  }

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  if (!selectedConversation) {
    return (
      <div className="flex-1 flex items-center justify-center bg-gray-50 dark:bg-gray-900">
        <div className="text-center">
          <MessageSquare className="w-20 h-20 text-gray-300 dark:text-gray-600 mx-auto mb-4" />
          <h3 className="text-xl font-semibold text-gray-600 dark:text-gray-400 mb-2">
            No conversation selected
          </h3>
          <p className="text-gray-500 dark:text-gray-500">
            Choose a conversation from the left to start messaging
          </p>
        </div>
      </div>
    )
  }

  const contact = conversationMessages[0]?.contactName || selectedConversation

  return (
    <div className="flex-1 flex flex-col bg-gray-50 dark:bg-gray-900">
      {/* Header */}
      <div className="bg-white dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700 px-6 py-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            {/* Avatar */}
            <div className="w-10 h-10 rounded-full bg-gradient-to-br from-blue-400 to-blue-600 flex items-center justify-center text-white font-semibold">
              {contact.charAt(0).toUpperCase()}
            </div>

            {/* Name and Status */}
            <div>
              <h2 className="font-semibold text-gray-900 dark:text-white">{contact}</h2>
              <p className="text-sm text-gray-500 dark:text-gray-400">{selectedConversation}</p>
            </div>
          </div>

          {/* Actions */}
          <div className="flex items-center gap-2">
            <button
              className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700 text-gray-600 dark:text-gray-400"
              title="Call"
            >
              <Phone className="w-5 h-5" />
            </button>
            <button
              className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700 text-gray-600 dark:text-gray-400"
              title="Video Call"
            >
              <Video className="w-5 h-5" />
            </button>
            <button
              className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700 text-gray-600 dark:text-gray-400"
              title="More"
            >
              <MoreVertical className="w-5 h-5" />
            </button>
          </div>
        </div>
      </div>

      {/* Messages */}
      <div className="flex-1 overflow-y-auto p-6 space-y-4">
        {conversationMessages.map((msg) => {
          const isSent = msg.type === 2 // type 2 = sent message
          const timestamp = format(new Date(msg.date), 'MMM d, h:mm a')

          return (
            <div key={msg.id} className={`flex ${isSent ? 'justify-end' : 'justify-start'}`}>
              <div className={`max-w-md ${isSent ? 'order-2' : 'order-1'}`}>
                <div
                  className={`rounded-2xl px-4 py-2 ${
                    isSent
                      ? 'bg-blue-600 text-white'
                      : 'bg-white dark:bg-gray-800 text-gray-900 dark:text-white'
                  }`}
                >
                  <p className="whitespace-pre-wrap break-words">{msg.body}</p>
                </div>
                <p
                  className={`text-xs text-gray-500 dark:text-gray-400 mt-1 ${
                    isSent ? 'text-right' : 'text-left'
                  }`}
                >
                  {timestamp}
                </p>
              </div>
            </div>
          )
        })}
        <div ref={messagesEndRef} />
      </div>

      {/* Input */}
      <div className="bg-white dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700 px-6 py-4">
        <div className="flex items-end gap-3">
          <textarea
            value={newMessage}
            onChange={(e) => setNewMessage(e.target.value)}
            onKeyPress={handleKeyPress}
            placeholder="Type a message..."
            rows={1}
            className="flex-1 resize-none px-4 py-3 border border-gray-300 dark:border-gray-600 rounded-lg bg-gray-50 dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent max-h-32"
          />
          <button
            onClick={handleSend}
            disabled={!newMessage.trim() || isSending}
            className="p-3 rounded-lg bg-blue-600 hover:bg-blue-700 disabled:bg-gray-400 disabled:cursor-not-allowed text-white transition-colors"
          >
            <Send className="w-5 h-5" />
          </button>
        </div>
        <p className="text-xs text-gray-500 dark:text-gray-400 mt-2">
          Press Enter to send, Shift+Enter for new line
        </p>
      </div>
    </div>
  )
}
