'use client'

import { useState } from 'react'
import { Send, Brain, X } from 'lucide-react'

interface Message {
  id: string | number
  address: string
  body: string
  date: number
  type: number
  contactName?: string
}

interface AIAssistantProps {
  messages: Message[]
  onClose?: () => void
}

export default function AIAssistant({ messages, onClose }: AIAssistantProps) {
  const [query, setQuery] = useState('')
  const [conversation, setConversation] = useState<Array<{ role: 'user' | 'assistant', content: string }>>([])
  const [isLoading, setIsLoading] = useState(false)

  // Extract spending information from messages
  const analyzeSpending = () => {
    const spendingPatterns = [
      // US Dollar patterns
      /(?:paid|spent|debited|transaction|purchase)\s*(?:of|for)?\s*(?:\$|usd)\s*(\d+(?:,\d+)*(?:\.\d{2})?)/i,
      /(?:\$|usd)\s*(\d+(?:,\d+)*(?:\.\d{2})?)\s*(?:paid|spent|debited|deducted|charged)/i,
      /(?:amount|total|bill)\s*(?:of|:)?\s*(?:\$|usd)\s*(\d+(?:,\d+)*(?:\.\d{2})?)/i,
      // Indian Rupee patterns
      /(?:paid|spent|debited|transaction|purchase)\s*(?:of|for)?\s*(?:rs\.?|inr|₹)\s*(\d+(?:,\d+)*(?:\.\d{2})?)/i,
      /(?:rs\.?|inr|₹)\s*(\d+(?:,\d+)*(?:\.\d{2})?)\s*(?:paid|spent|debited|deducted|charged)/i,
      /(?:amount|total|bill)\s*(?:of|:)?\s*(?:rs\.?|inr|₹)\s*(\d+(?:,\d+)*(?:\.\d{2})?)/i,
    ]

    const transactions: Array<{ amount: number, date: number, message: string }> = []

    messages.forEach((msg) => {
      for (const pattern of spendingPatterns) {
        const match = msg.body.match(pattern)
        if (match) {
          const amountStr = match[1].replace(/,/g, '')
          const amount = parseFloat(amountStr)
          if (!isNaN(amount)) {
            transactions.push({
              amount,
              date: msg.date,
              message: msg.body,
            })
            break
          }
        }
      }
    })

    return transactions
  }

  // Helper function to format date
  const formatDate = (timestamp: number): string => {
    const date = new Date(timestamp)
    return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
  }

  // Generate response based on query
  const generateResponse = (userQuery: string): string => {
    const lowerQuery = userQuery.toLowerCase()

    // List transactions
    if (lowerQuery.includes('list') && (lowerQuery.includes('transaction') || lowerQuery.includes('spending') || lowerQuery.includes('purchase'))) {
      const transactions = analyzeSpending()
      const limit = lowerQuery.includes('all') ? transactions.length : Math.min(10, transactions.length)
      const recentTransactions = transactions.slice(0, limit)

      if (recentTransactions.length === 0) {
        return "No spending transactions found in your messages."
      }

      let response = `Found ${transactions.length} transactions. Here are the ${limit === transactions.length ? 'all' : 'most recent'}:\n\n`
      recentTransactions.forEach((t, i) => {
        const preview = t.message.substring(0, 60).replace(/\n/g, ' ')
        response += `${i + 1}. $${t.amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} - ${formatDate(t.date)}\n   ${preview}...\n\n`
      })
      return response.trim()
    }

    // Find OTPs
    if (lowerQuery.includes('otp') || lowerQuery.includes('verification code') || lowerQuery.includes('code')) {
      const otpPattern = /\b\d{4,6}\b/g
      const recentMessages = messages.slice(0, 50) // Check last 50 messages
      const otps: Array<{code: string, date: number, message: string}> = []

      recentMessages.forEach(msg => {
        if (msg.body.toLowerCase().includes('otp') ||
            msg.body.toLowerCase().includes('verification') ||
            msg.body.toLowerCase().includes('code')) {
          const matches = msg.body.match(otpPattern)
          if (matches) {
            matches.forEach(code => {
              otps.push({ code, date: msg.date, message: msg.body })
            })
          }
        }
      })

      if (otps.length === 0) {
        return "No OTP codes found in recent messages."
      }

      let response = `Found ${otps.length} OTP code(s):\n\n`
      otps.slice(0, 5).forEach((otp, i) => {
        const preview = otp.message.substring(0, 50).replace(/\n/g, ' ')
        response += `${i + 1}. Code: ${otp.code} - ${formatDate(otp.date)}\n   ${preview}...\n\n`
      })
      return response.trim()
    }

    // Top merchants/businesses
    if (lowerQuery.includes('top merchant') || lowerQuery.includes('where do i spend') || lowerQuery.includes('most spent at')) {
      const transactions = analyzeSpending()
      const merchantPattern = /(?:at|from|to)\s+([A-Z][A-Za-z\s&]+?)(?:\s+(?:on|dated|for)|$)/
      const merchants: Record<string, number> = {}

      transactions.forEach(t => {
        const match = t.message.match(merchantPattern)
        if (match && match[1]) {
          const merchant = match[1].trim()
          if (merchant.length > 2 && merchant.length < 30) {
            merchants[merchant] = (merchants[merchant] || 0) + t.amount
          }
        }
      })

      const sorted = Object.entries(merchants).sort((a, b) => b[1] - a[1]).slice(0, 5)

      if (sorted.length === 0) {
        return "Could not identify specific merchants from your transaction messages."
      }

      let response = `Your top merchants by spending:\n\n`
      sorted.forEach(([merchant, amount], i) => {
        response += `${i + 1}. ${merchant}: $${amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}\n`
      })
      return response
    }

    // Spending by category
    if (lowerQuery.includes('category') || lowerQuery.includes('categories') || lowerQuery.includes('breakdown')) {
      const transactions = analyzeSpending()
      const categories = {
        'Shopping': 0,
        'Food & Dining': 0,
        'Bills & Utilities': 0,
        'Travel': 0,
        'Entertainment': 0,
        'Other': 0
      }

      transactions.forEach(t => {
        const msg = t.message.toLowerCase()
        if (msg.includes('amazon') || msg.includes('shop') || msg.includes('store') || msg.includes('retail')) {
          categories['Shopping'] += t.amount
        } else if (msg.includes('food') || msg.includes('restaurant') || msg.includes('dining') || msg.includes('uber eats') || msg.includes('doordash')) {
          categories['Food & Dining'] += t.amount
        } else if (msg.includes('bill') || msg.includes('electric') || msg.includes('water') || msg.includes('internet') || msg.includes('utility')) {
          categories['Bills & Utilities'] += t.amount
        } else if (msg.includes('uber') || msg.includes('lyft') || msg.includes('flight') || msg.includes('hotel') || msg.includes('travel')) {
          categories['Travel'] += t.amount
        } else if (msg.includes('movie') || msg.includes('game') || msg.includes('spotify') || msg.includes('netflix') || msg.includes('entertainment')) {
          categories['Entertainment'] += t.amount
        } else {
          categories['Other'] += t.amount
        }
      })

      const total = Object.values(categories).reduce((sum, val) => sum + val, 0)
      let response = `Spending by category:\n\n`

      Object.entries(categories)
        .filter(([_, amount]) => amount > 0)
        .sort((a, b) => b[1] - a[1])
        .forEach(([cat, amount]) => {
          const percentage = ((amount / total) * 100).toFixed(1)
          response += `${cat}: $${amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} (${percentage}%)\n`
        })

      return response
    }

    // Spending trends
    if (lowerQuery.includes('trend') || lowerQuery.includes('compare') || lowerQuery.includes('last month')) {
      const transactions = analyzeSpending()
      const now = new Date()
      const thisMonthStart = new Date(now.getFullYear(), now.getMonth(), 1).getTime()
      const lastMonthStart = new Date(now.getFullYear(), now.getMonth() - 1, 1).getTime()

      const thisMonth = transactions.filter(t => t.date >= thisMonthStart)
      const lastMonth = transactions.filter(t => t.date >= lastMonthStart && t.date < thisMonthStart)

      const thisMonthTotal = thisMonth.reduce((sum, t) => sum + t.amount, 0)
      const lastMonthTotal = lastMonth.reduce((sum, t) => sum + t.amount, 0)

      const diff = thisMonthTotal - lastMonthTotal
      const percentChange = lastMonthTotal > 0 ? ((diff / lastMonthTotal) * 100).toFixed(1) : 0
      const trend = diff > 0 ? '📈 increased' : '📉 decreased'

      return `Spending Trends:\n\nThis Month: $${thisMonthTotal.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} (${thisMonth.length} transactions)\nLast Month: $${lastMonthTotal.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} (${lastMonth.length} transactions)\n\nYour spending has ${trend} by $${Math.abs(diff).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} (${percentChange}%)`
    }

    // Search messages
    if (lowerQuery.startsWith('search') || lowerQuery.startsWith('find messages')) {
      const searchTerm = lowerQuery.replace(/^(search|find messages)\s+(for\s+)?/i, '').trim()
      if (!searchTerm) {
        return "Please specify what to search for. Example: 'search amazon'"
      }

      const results = messages.filter(msg =>
        msg.body.toLowerCase().includes(searchTerm)
      ).slice(0, 5)

      if (results.length === 0) {
        return `No messages found containing "${searchTerm}"`
      }

      let response = `Found ${results.length} message(s) containing "${searchTerm}":\n\n`
      results.forEach((msg, i) => {
        const preview = msg.body.substring(0, 80).replace(/\n/g, ' ')
        response += `${i + 1}. From ${msg.address} - ${formatDate(msg.date)}\n   ${preview}...\n\n`
      })
      return response.trim()
    }

    // Delivery tracking
    if (lowerQuery.includes('delivery') || lowerQuery.includes('tracking') || lowerQuery.includes('package')) {
      const trackingPattern = /\b([A-Z0-9]{10,})\b/g
      const deliveryMessages = messages.filter(msg =>
        msg.body.toLowerCase().includes('delivery') ||
        msg.body.toLowerCase().includes('shipped') ||
        msg.body.toLowerCase().includes('tracking') ||
        msg.body.toLowerCase().includes('package')
      ).slice(0, 5)

      if (deliveryMessages.length === 0) {
        return "No delivery or tracking information found in recent messages."
      }

      let response = `Found ${deliveryMessages.length} delivery-related message(s):\n\n`
      deliveryMessages.forEach((msg, i) => {
        const preview = msg.body.substring(0, 100).replace(/\n/g, ' ')
        response += `${i + 1}. ${formatDate(msg.date)}\n   ${preview}...\n\n`
      })
      return response.trim()
    }

    // Standard spending queries
    if (lowerQuery.includes('spent') || lowerQuery.includes('spending')) {
      const transactions = analyzeSpending()
      const now = Date.now()
      const monthStart = new Date(new Date().getFullYear(), new Date().getMonth(), 1).getTime()

      if (lowerQuery.includes('month') || lowerQuery.includes('this month')) {
        const monthTransactions = transactions.filter(t => t.date >= monthStart)
        const total = monthTransactions.reduce((sum, t) => sum + t.amount, 0)
        return `This month, you've spent approximately $${total.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} based on ${monthTransactions.length} transactions found in your messages.`
      }

      if (lowerQuery.includes('today')) {
        const dayStart = new Date().setHours(0, 0, 0, 0)
        const todayTransactions = transactions.filter(t => t.date >= dayStart)
        const total = todayTransactions.reduce((sum, t) => sum + t.amount, 0)
        return `Today, you've spent approximately $${total.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} based on ${todayTransactions.length} transactions found in your messages.`
      }

      if (lowerQuery.includes('week')) {
        const weekStart = Date.now() - (7 * 24 * 60 * 60 * 1000)
        const weekTransactions = transactions.filter(t => t.date >= weekStart)
        const total = weekTransactions.reduce((sum, t) => sum + t.amount, 0)
        return `This week, you've spent approximately $${total.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} based on ${weekTransactions.length} transactions found in your messages.`
      }

      // Default: all time
      const total = transactions.reduce((sum, t) => sum + t.amount, 0)
      return `You've spent approximately $${total.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} based on ${transactions.length} transactions found in your messages.`
    }

    // Message count
    if (lowerQuery.includes('how many messages') || lowerQuery.includes('message count')) {
      return `You have ${messages.length} messages in total.`
    }

    // Most contacted
    if (lowerQuery.includes('most contact') || lowerQuery.includes('who do i text most')) {
      const contactCounts: Record<string, number> = {}
      messages.forEach(msg => {
        contactCounts[msg.address] = (contactCounts[msg.address] || 0) + 1
      })
      const sorted = Object.entries(contactCounts).sort((a, b) => b[1] - a[1])
      const top3 = sorted.slice(0, 3)
      return `Your most contacted numbers are:\n${top3.map(([number, count]) => `${number}: ${count} messages`).join('\n')}`
    }

    // Default response with more options
    return "I can help you analyze your messages! Try asking:\n\n💰 Spending:\n• \"How much did I spend this month?\"\n• \"List my transactions\"\n• \"Show spending by category\"\n• \"Compare spending trends\"\n• \"Top merchants\"\n\n🔍 Search:\n• \"Find my OTP codes\"\n• \"Search for Amazon\"\n• \"Show delivery updates\"\n\n📊 Statistics:\n• \"How many messages do I have?\"\n• \"Who do I text most?\""
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!query.trim() || isLoading) return

    const userMessage = query.trim()
    setQuery('')
    setIsLoading(true)

    // Add user message to conversation
    setConversation(prev => [...prev, { role: 'user', content: userMessage }])

    // Simulate thinking delay
    setTimeout(() => {
      const response = generateResponse(userMessage)
      setConversation(prev => [...prev, { role: 'assistant', content: response }])
      setIsLoading(false)
    }, 500)
  }

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
      <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-2xl w-full max-w-2xl h-[80vh] flex flex-col relative">
        {/* Header */}
        <div className="flex items-center justify-between p-6 border-b border-gray-200 dark:border-gray-700">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 bg-gradient-to-br from-purple-500 to-blue-600 rounded-xl flex items-center justify-center">
              <Brain className="w-6 h-6 text-white" />
            </div>
            <div>
              <h2 className="text-xl font-bold text-gray-900 dark:text-white">AI Assistant</h2>
              <p className="text-sm text-gray-500 dark:text-gray-400">Analyze your messages and spending</p>
            </div>
          </div>
          {onClose && (
            <button
              onClick={onClose}
              className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
            >
              <X className="w-5 h-5 text-gray-600 dark:text-gray-400" />
            </button>
          )}
        </div>

        {/* Conversation */}
        <div className="flex-1 overflow-y-auto p-6 space-y-4">
          {conversation.length === 0 && (
            <div className="text-center py-12">
              <Brain className="w-16 h-16 mx-auto text-gray-300 dark:text-gray-600 mb-4" />
              <p className="text-gray-500 dark:text-gray-400 mb-2">Ask me anything about your messages!</p>
              <p className="text-sm text-gray-400 dark:text-gray-500">
                Try: "How much did I spend this month?"
              </p>
            </div>
          )}

          {conversation.map((msg, idx) => (
            <div
              key={idx}
              className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
            >
              <div
                className={`max-w-[80%] rounded-2xl px-4 py-3 ${
                  msg.role === 'user'
                    ? 'bg-blue-500 text-white'
                    : 'bg-gray-100 dark:bg-gray-700 text-gray-900 dark:text-white'
                }`}
              >
                <p className="whitespace-pre-wrap">{msg.content}</p>
              </div>
            </div>
          ))}

          {isLoading && (
            <div className="flex justify-start">
              <div className="bg-gray-100 dark:bg-gray-700 rounded-2xl px-4 py-3">
                <div className="flex gap-2">
                  <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '0ms' }}></div>
                  <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '150ms' }}></div>
                  <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '300ms' }}></div>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Input */}
        <form onSubmit={handleSubmit} className="p-6 border-t border-gray-200 dark:border-gray-700">
          <div className="flex gap-3">
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Ask about your messages or spending..."
              className="flex-1 px-4 py-3 rounded-xl bg-gray-100 dark:bg-gray-700 text-gray-900 dark:text-white placeholder-gray-500 dark:placeholder-gray-400 outline-none focus:ring-2 focus:ring-blue-500"
              disabled={isLoading}
            />
            <button
              type="submit"
              disabled={!query.trim() || isLoading}
              className="px-6 py-3 bg-blue-500 hover:bg-blue-600 disabled:opacity-50 disabled:cursor-not-allowed text-white rounded-xl flex items-center gap-2 transition-colors"
            >
              <Send className="w-5 h-5" />
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
