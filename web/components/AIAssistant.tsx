'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
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

interface ParsedTransaction {
  amount: number
  currency: string
  merchant: string | null
  date: number
  message: string
}

// Known merchants with aliases
const MERCHANT_ALIASES: Record<string, string[]> = {
  'amazon': ['amazon', 'amzn', 'amazn', 'amz'],
  'flipkart': ['flipkart', 'fkrt', 'flip'],
  'walmart': ['walmart', 'wmt'],
  'uber': ['uber'],
  'swiggy': ['swiggy'],
  'zomato': ['zomato'],
  'google': ['google', 'goog'],
  'apple': ['apple', 'itunes'],
  'netflix': ['netflix'],
  'spotify': ['spotify'],
  'doordash': ['doordash'],
  'starbucks': ['starbucks', 'sbux'],
  'myntra': ['myntra'],
  'bigbasket': ['bigbasket', 'bbsk'],
  'paytm': ['paytm'],
  'phonepe': ['phonepe'],
  'gpay': ['gpay', 'googlepay', 'google pay'],
  'target': ['target'],
  'costco': ['costco'],
  'bestbuy': ['best buy', 'bestbuy'],
}

// Transaction keywords that MUST be present for valid debit
const DEBIT_KEYWORDS = [
  'debited', 'spent', 'paid', 'charged', 'purchase', 'payment',
  'debit', 'deducted', 'txn', 'transaction', 'pos', 'withdrawn'
]

// Keywords that indicate NOT a spending
const CREDIT_KEYWORDS = [
  'credited', 'received', 'refund', 'reversal', 'cashback',
  'credit', 'deposit', 'deposited', 'added', 'bonus', 'reward'
]

export default function AIAssistant({ messages, onClose }: AIAssistantProps) {
  const [query, setQuery] = useState('')
  const [conversation, setConversation] = useState<Array<{ role: 'user' | 'assistant', content: string }>>([])
  const [isLoading, setIsLoading] = useState(false)
  const timeoutsRef = useRef<ReturnType<typeof setTimeout>[]>([])

  useEffect(() => {
    return () => {
      timeoutsRef.current.forEach((id) => clearTimeout(id))
      timeoutsRef.current = []
    }
  }, [])

  // Extract merchant from query
  const extractMerchantFromQuery = (query: string): string | null => {
    const lowerQuery = query.toLowerCase()
    for (const [merchant, aliases] of Object.entries(MERCHANT_ALIASES)) {
      if (aliases.some(alias => lowerQuery.includes(alias))) {
        return merchant
      }
    }
    return null
  }

  // Extract merchant from message body
  const extractMerchantFromMessage = (body: string): string | null => {
    const bodyLower = body.toLowerCase()

    // Check known merchants first
    for (const [merchant, aliases] of Object.entries(MERCHANT_ALIASES)) {
      if (aliases.some(alias => bodyLower.includes(alias))) {
        return merchant.charAt(0).toUpperCase() + merchant.slice(1)
      }
    }

    // Try to extract from patterns
    const merchantPatterns = [
      /(?:at|to|from)\s+([A-Za-z][A-Za-z0-9\s&'./-]{2,25})(?:\s+(?:on|for|ref|card)|$)/i,
      /(?:txn|transaction|purchase)\s+(?:at|on|to)\s+([A-Za-z][A-Za-z0-9\s&'./-]{2,25})/i,
    ]

    for (const pattern of merchantPatterns) {
      const match = body.match(pattern)
      if (match && match[1]) {
        const extracted = match[1].trim()
        const skipWords = ['your', 'the', 'a', 'an', 'card', 'account', 'bank', 'ending']
        if (extracted.length >= 2 && extracted.length <= 25 &&
            !skipWords.some(w => extracted.toLowerCase().startsWith(w))) {
          return extracted.charAt(0).toUpperCase() + extracted.slice(1).toLowerCase()
        }
      }
    }

    return null
  }

  // Parse transactions with strict filtering
  const parseTransactions = useMemo((): ParsedTransaction[] => {
    const transactions: ParsedTransaction[] = []

    const amountPatterns = [
      // INR patterns
      /(?:rs\.?|₹|inr)\s*([0-9,]+(?:\.\d{1,2})?)/i,
      // USD patterns
      /(?:\$|usd)\s*([0-9,]+(?:\.\d{1,2})?)/i,
      // Amount followed by currency
      /([0-9,]+(?:\.\d{1,2})?)\s*(?:rs\.?|₹|inr)/i,
    ]

    for (const msg of messages) {
      const bodyLower = msg.body.toLowerCase()

      // Skip credits/refunds
      if (CREDIT_KEYWORDS.some(kw => bodyLower.includes(kw))) {
        continue
      }

      // Must have debit keyword
      if (!DEBIT_KEYWORDS.some(kw => bodyLower.includes(kw))) {
        continue
      }

      // Extract amount
      let amount: number | null = null
      let currency = 'INR'

      for (const pattern of amountPatterns) {
        const match = msg.body.match(pattern)
        if (match) {
          const amountStr = match[1].replace(/,/g, '')
          amount = parseFloat(amountStr)
          if (!isNaN(amount)) {
            currency = (msg.body.includes('$') || bodyLower.includes('usd')) ? 'USD' : 'INR'
            break
          }
        }
      }

      // Skip invalid amounts or likely reference numbers
      if (amount === null || amount <= 0 || amount > 10000000) {
        continue
      }

      const merchant = extractMerchantFromMessage(msg.body)

      transactions.push({
        amount,
        currency,
        merchant,
        date: msg.date,
        message: msg.body,
      })
    }

    return transactions.sort((a, b) => b.date - a.date)
  }, [messages])

  // Filter transactions by merchant
  const filterByMerchant = (transactions: ParsedTransaction[], merchant: string): ParsedTransaction[] => {
    const lowerMerchant = merchant.toLowerCase()
    return transactions.filter(txn =>
      txn.merchant?.toLowerCase().includes(lowerMerchant) ||
      txn.message.toLowerCase().includes(lowerMerchant)
    )
  }

  // Apply time filter
  const applyTimeFilter = (transactions: ParsedTransaction[], query: string): ParsedTransaction[] => {
    const lowerQuery = query.toLowerCase()
    const now = Date.now()

    if (lowerQuery.includes('today')) {
      const dayStart = new Date().setHours(0, 0, 0, 0)
      return transactions.filter(t => t.date >= dayStart)
    }
    if (lowerQuery.includes('week') || lowerQuery.includes('7 day')) {
      return transactions.filter(t => t.date >= now - 7 * 24 * 60 * 60 * 1000)
    }
    if (lowerQuery.includes('month') || lowerQuery.includes('30 day')) {
      const monthStart = new Date(new Date().getFullYear(), new Date().getMonth(), 1).getTime()
      return transactions.filter(t => t.date >= monthStart)
    }
    if (lowerQuery.includes('year')) {
      const yearStart = new Date(new Date().getFullYear(), 0, 1).getTime()
      return transactions.filter(t => t.date >= yearStart)
    }
    return transactions
  }

  // Get time period label
  const getTimePeriodLabel = (query: string): string => {
    const lowerQuery = query.toLowerCase()
    if (lowerQuery.includes('today')) return 'Today'
    if (lowerQuery.includes('week') || lowerQuery.includes('7 day')) return 'This Week'
    if (lowerQuery.includes('month') || lowerQuery.includes('30 day')) return 'This Month'
    if (lowerQuery.includes('year')) return 'This Year'
    return ''
  }

  // Legacy function for backward compatibility
  const analyzeSpending = () => parseTransactions

  // Helper function to format date
  const formatDate = (timestamp: number): string => {
    const date = new Date(timestamp)
    return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
  }

  // Format currency based on type
  const formatCurrency = (amount: number, currency: string): string => {
    const symbol = currency === 'USD' ? '$' : '₹'
    return `${symbol}${amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
  }

  // Generate response based on query
  const generateResponse = (userQuery: string): string => {
    const lowerQuery = userQuery.toLowerCase()

    // Extract merchant from query first
    const queryMerchant = extractMerchantFromQuery(userQuery)

    // Merchant-specific spending query (e.g., "Amazon spending", "spent at Amazon")
    if (queryMerchant && (lowerQuery.includes('spend') || lowerQuery.includes('spent') ||
        lowerQuery.includes('transaction') || lowerQuery.includes('purchase'))) {
      const merchantTransactions = filterByMerchant(parseTransactions, queryMerchant)

      if (merchantTransactions.length === 0) {
        const merchantName = queryMerchant.charAt(0).toUpperCase() + queryMerchant.slice(1)
        return `📊 No spending transactions found for ${merchantName}.\n\nThis could mean:\n• No ${merchantName} purchases in your SMS history\n• Purchases were made via a different payment method\n• SMS notifications were not enabled`
      }

      // Apply time filter
      const filtered = applyTimeFilter(merchantTransactions, userQuery)
      const total = filtered.reduce((sum, t) => sum + t.amount, 0)
      const currency = filtered[0]?.currency || 'INR'
      const periodLabel = getTimePeriodLabel(userQuery)
      const merchantName = queryMerchant.charAt(0).toUpperCase() + queryMerchant.slice(1)

      let response = `💳 ${merchantName} Spending${periodLabel ? ` (${periodLabel})` : ''}\n\n`
      response += `Total: ${formatCurrency(total, currency)}\n`
      response += `Transactions: ${filtered.length}\n\n`

      if (filtered.length > 0) {
        response += `📝 Details:\n`
        filtered.slice(0, 10).forEach((t, i) => {
          const preview = t.message.substring(0, 70).replace(/\n/g, ' ')
          response += `${i + 1}. ${formatCurrency(t.amount, t.currency)} — ${formatDate(t.date)}\n   ${preview}...\n\n`
        })
      }

      return response.trim()
    }

    // List transactions
    if (lowerQuery.includes('list') && (lowerQuery.includes('transaction') || lowerQuery.includes('spending') || lowerQuery.includes('purchase'))) {
      const transactions = parseTransactions
      if (transactions.length === 0) {
        return "📊 No spending transactions found in your messages.\n\nMake sure you have SMS notifications enabled for your bank/payment apps."
      }

      const filtered = applyTimeFilter(transactions, userQuery)
      const limit = lowerQuery.includes('all') ? filtered.length : Math.min(10, filtered.length)
      const displayTransactions = filtered.slice(0, limit)

      let response = `💳 Found ${filtered.length} transactions. Here are the ${limit === filtered.length ? 'all' : 'most recent'}:\n\n`
      displayTransactions.forEach((t, i) => {
        const preview = t.message.substring(0, 60).replace(/\n/g, ' ')
        response += `${i + 1}. ${formatCurrency(t.amount, t.currency)} - ${formatDate(t.date)}${t.merchant ? ` at ${t.merchant}` : ''}\n   ${preview}...\n\n`
      })
      return response.trim()
    }

    // Find OTPs
    if (lowerQuery.includes('otp') || lowerQuery.includes('verification code') || (lowerQuery.includes('code') && !lowerQuery.includes('zip'))) {
      const otpPattern = /\b\d{4,6}\b/g
      const recentMessages = messages.slice(0, 50)
      const otps: Array<{code: string, date: number, message: string}> = []

      recentMessages.forEach(msg => {
        const bodyLower = msg.body.toLowerCase()
        if (bodyLower.includes('otp') ||
            bodyLower.includes('verification') ||
            (bodyLower.includes('code') && !bodyLower.includes('zip code'))) {
          const matches = msg.body.match(otpPattern)
          if (matches) {
            matches.forEach(code => {
              otps.push({ code, date: msg.date, message: msg.body })
            })
          }
        }
      })

      if (otps.length === 0) {
        return "🔐 No OTP codes found in recent messages."
      }

      let response = `🔐 Found ${otps.length} OTP code(s):\n\n`
      otps.slice(0, 5).forEach((otp, i) => {
        const preview = otp.message.substring(0, 50).replace(/\n/g, ' ')
        response += `${i + 1}. Code: ${otp.code} - ${formatDate(otp.date)}\n   ${preview}...\n\n`
      })
      return response.trim()
    }

    // Top merchants/businesses
    if (lowerQuery.includes('top merchant') || lowerQuery.includes('where do i spend') || lowerQuery.includes('most spent at')) {
      const transactions = parseTransactions
      const merchantTotals: Record<string, { amount: number, currency: string }> = {}

      transactions.forEach(t => {
        const merchant = t.merchant || 'Unknown'
        if (!merchantTotals[merchant]) {
          merchantTotals[merchant] = { amount: 0, currency: t.currency }
        }
        merchantTotals[merchant].amount += t.amount
      })

      const sorted = Object.entries(merchantTotals)
        .sort((a, b) => b[1].amount - a[1].amount)
        .slice(0, 8)

      if (sorted.length === 0) {
        return "🏪 Could not identify specific merchants from your transaction messages."
      }

      let response = `🏪 Your top merchants by spending:\n\n`
      sorted.forEach(([merchant, data], i) => {
        response += `${i + 1}. ${merchant}: ${formatCurrency(data.amount, data.currency)}\n`
      })
      return response
    }

    // Spending by category
    if (lowerQuery.includes('category') || lowerQuery.includes('categories') || lowerQuery.includes('breakdown')) {
      const transactions = parseTransactions
      const categories: Record<string, { amount: number, currency: string }> = {
        'Shopping': { amount: 0, currency: 'INR' },
        'Food & Dining': { amount: 0, currency: 'INR' },
        'Bills & Utilities': { amount: 0, currency: 'INR' },
        'Travel': { amount: 0, currency: 'INR' },
        'Entertainment': { amount: 0, currency: 'INR' },
        'Other': { amount: 0, currency: 'INR' }
      }

      transactions.forEach(t => {
        const msg = t.message.toLowerCase()
        let category = 'Other'

        if (msg.includes('amazon') || msg.includes('flipkart') || msg.includes('shop') || msg.includes('store') || msg.includes('retail') || msg.includes('myntra')) {
          category = 'Shopping'
        } else if (msg.includes('food') || msg.includes('restaurant') || msg.includes('dining') || msg.includes('swiggy') || msg.includes('zomato') || msg.includes('doordash')) {
          category = 'Food & Dining'
        } else if (msg.includes('bill') || msg.includes('electric') || msg.includes('water') || msg.includes('internet') || msg.includes('utility') || msg.includes('recharge')) {
          category = 'Bills & Utilities'
        } else if (msg.includes('uber') || msg.includes('lyft') || msg.includes('ola') || msg.includes('flight') || msg.includes('hotel') || msg.includes('travel')) {
          category = 'Travel'
        } else if (msg.includes('movie') || msg.includes('game') || msg.includes('spotify') || msg.includes('netflix') || msg.includes('entertainment')) {
          category = 'Entertainment'
        }

        categories[category].amount += t.amount
        categories[category].currency = t.currency
      })

      const total = Object.values(categories).reduce((sum, val) => sum + val.amount, 0)
      let response = `📊 Spending by category:\n\n`

      Object.entries(categories)
        .filter(([_, data]) => data.amount > 0)
        .sort((a, b) => b[1].amount - a[1].amount)
        .forEach(([cat, data]) => {
          const percentage = total > 0 ? ((data.amount / total) * 100).toFixed(1) : '0'
          response += `${cat}: ${formatCurrency(data.amount, data.currency)} (${percentage}%)\n`
        })

      return response
    }

    // Spending trends
    if (lowerQuery.includes('trend') || lowerQuery.includes('compare') || lowerQuery.includes('last month')) {
      const transactions = parseTransactions
      const now = new Date()
      const thisMonthStart = new Date(now.getFullYear(), now.getMonth(), 1).getTime()
      const lastMonthStart = new Date(now.getFullYear(), now.getMonth() - 1, 1).getTime()

      const thisMonth = transactions.filter(t => t.date >= thisMonthStart)
      const lastMonth = transactions.filter(t => t.date >= lastMonthStart && t.date < thisMonthStart)

      const thisMonthTotal = thisMonth.reduce((sum, t) => sum + t.amount, 0)
      const lastMonthTotal = lastMonth.reduce((sum, t) => sum + t.amount, 0)
      const currency = transactions[0]?.currency || 'INR'

      const diff = thisMonthTotal - lastMonthTotal
      const percentChange = lastMonthTotal > 0 ? ((diff / lastMonthTotal) * 100).toFixed(1) : '0'
      const trend = diff > 0 ? '📈 increased' : '📉 decreased'

      return `📊 Spending Trends:\n\nThis Month: ${formatCurrency(thisMonthTotal, currency)} (${thisMonth.length} transactions)\nLast Month: ${formatCurrency(lastMonthTotal, currency)} (${lastMonth.length} transactions)\n\nYour spending has ${trend} by ${formatCurrency(Math.abs(diff), currency)} (${percentChange}%)`
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
        return `🔍 No messages found containing "${searchTerm}"`
      }

      let response = `🔍 Found ${results.length} message(s) containing "${searchTerm}":\n\n`
      results.forEach((msg, i) => {
        const preview = msg.body.substring(0, 80).replace(/\n/g, ' ')
        response += `${i + 1}. From ${msg.address} - ${formatDate(msg.date)}\n   ${preview}...\n\n`
      })
      return response.trim()
    }

    // Delivery tracking
    if (lowerQuery.includes('delivery') || lowerQuery.includes('tracking') || lowerQuery.includes('package')) {
      const deliveryMessages = messages.filter(msg => {
        const bodyLower = msg.body.toLowerCase()
        return bodyLower.includes('delivery') ||
          bodyLower.includes('shipped') ||
          bodyLower.includes('tracking') ||
          bodyLower.includes('package') ||
          bodyLower.includes('out for delivery') ||
          bodyLower.includes('delivered')
      }).slice(0, 5)

      if (deliveryMessages.length === 0) {
        return "📦 No delivery or tracking information found in recent messages."
      }

      let response = `📦 Found ${deliveryMessages.length} delivery-related message(s):\n\n`
      deliveryMessages.forEach((msg, i) => {
        const preview = msg.body.substring(0, 100).replace(/\n/g, ' ')
        response += `${i + 1}. ${formatDate(msg.date)}\n   ${preview}...\n\n`
      })
      return response.trim()
    }

    // Standard spending queries (general)
    if (lowerQuery.includes('spent') || lowerQuery.includes('spending')) {
      const transactions = parseTransactions

      if (transactions.length === 0) {
        return "📊 No spending transactions found in your messages.\n\nMake sure you have SMS notifications enabled for your bank/payment apps."
      }

      const filtered = applyTimeFilter(transactions, userQuery)
      const total = filtered.reduce((sum, t) => sum + t.amount, 0)
      const currency = filtered[0]?.currency || 'INR'
      const periodLabel = getTimePeriodLabel(userQuery) || 'Total'

      // Group by merchant for top spending
      const merchantTotals = filtered
        .reduce((acc, t) => {
          const merchant = t.merchant || 'Unknown'
          acc[merchant] = (acc[merchant] || 0) + t.amount
          return acc
        }, {} as Record<string, number>)

      const topMerchants = Object.entries(merchantTotals)
        .sort((a, b) => b[1] - a[1])
        .slice(0, 5)

      let response = `💰 Spending Analysis (${periodLabel})\n\n`
      response += `Total Spent: ${formatCurrency(total, currency)}\n`
      response += `Transactions: ${filtered.length}\n`

      if (filtered.length > 0) {
        const average = total / filtered.length
        response += `Average: ${formatCurrency(average, currency)}\n\n`

        if (topMerchants.length > 0) {
          response += `🏪 Top Merchants:\n`
          topMerchants.forEach(([merchant, amount], i) => {
            response += `${i + 1}. ${merchant}: ${formatCurrency(amount, currency)}\n`
          })
        }
      }

      return response
    }

    // Message count
    if (lowerQuery.includes('how many messages') || lowerQuery.includes('message count')) {
      return `📱 You have ${messages.length} messages in total.`
    }

    // Most contacted
    if (lowerQuery.includes('most contact') || lowerQuery.includes('who do i text most')) {
      const contactCounts: Record<string, number> = {}
      messages.forEach(msg => {
        contactCounts[msg.address] = (contactCounts[msg.address] || 0) + 1
      })
      const sorted = Object.entries(contactCounts).sort((a, b) => b[1] - a[1])
      const top3 = sorted.slice(0, 3)
      return `📱 Your most contacted numbers are:\n${top3.map(([number, count]) => `${number}: ${count} messages`).join('\n')}`
    }

    // Default response with more options
    return "I can help you analyze your messages! Try asking:\n\n💰 Spending:\n• \"How much did I spend this month?\"\n• \"Amazon spending\" or \"Amazon transactions\"\n• \"Spent at Swiggy this week\"\n• \"List my transactions\"\n• \"Show spending by category\"\n• \"Compare spending trends\"\n• \"Top merchants\"\n\n🔍 Search:\n• \"Find my OTP codes\"\n• \"Search for Amazon\"\n• \"Show delivery updates\"\n\n📊 Statistics:\n• \"How many messages do I have?\"\n• \"Who do I text most?\""
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
    const timeoutId = setTimeout(() => {
      const response = generateResponse(userMessage)
      setConversation(prev => [...prev, { role: 'assistant', content: response }])
      setIsLoading(false)
      timeoutsRef.current = timeoutsRef.current.filter((id) => id !== timeoutId)
    }, 500)
    timeoutsRef.current.push(timeoutId)
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
