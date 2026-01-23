package com.phoneintegration.app.ai

import android.content.Context
import android.util.Log
import com.phoneintegration.app.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * Advanced AI Service for intelligent SMS analysis using enhanced pattern matching
 * All processing is done locally - completely free and no API keys required
 */
class AIService(private val context: Context) {

    companion object {
        private const val TAG = "AIService"
    }

    // Known merchants with aliases
    private val MERCHANT_ALIASES = mapOf(
        "amazon" to listOf("amazon", "amzn", "amazn", "amz"),
        "flipkart" to listOf("flipkart", "fkrt", "flip"),
        "walmart" to listOf("walmart", "wmt"),
        "uber" to listOf("uber"),
        "swiggy" to listOf("swiggy"),
        "zomato" to listOf("zomato"),
        "google" to listOf("google", "goog"),
        "apple" to listOf("apple", "itunes"),
        "netflix" to listOf("netflix"),
        "spotify" to listOf("spotify"),
        "doordash" to listOf("doordash"),
        "starbucks" to listOf("starbucks", "sbux"),
        "myntra" to listOf("myntra"),
        "bigbasket" to listOf("bigbasket", "bbsk"),
        "paytm" to listOf("paytm"),
        "phonepe" to listOf("phonepe"),
        "gpay" to listOf("gpay", "googlepay", "google pay"),
    )

    // Transaction keywords that MUST be present for a valid debit transaction
    private val DEBIT_KEYWORDS = listOf(
        "debited", "spent", "paid", "charged", "purchase", "payment",
        "debit", "deducted", "txn", "transaction", "pos", "withdrawn"
    )

    // Keywords that indicate this is NOT a spending (credits, refunds, etc.)
    private val CREDIT_KEYWORDS = listOf(
        "credited", "received", "refund", "reversal", "cashback",
        "credit", "deposit", "deposited", "added", "bonus", "reward"
    )

    data class ParsedTransaction(
        val amount: Double,
        val currency: String,
        val merchant: String?,
        val date: Long,
        val messageBody: String,
        val isDebit: Boolean
    )

    data class ConversationContext(
        val messagesSent: Int,
        val messagesReceived: Int,
        val topics: List<String>,
        val sentiment: Sentiment,
        val urgency: Urgency
    )

    enum class Sentiment {
        POSITIVE, NEGATIVE, NEUTRAL
    }

    enum class Urgency {
        HIGH, MEDIUM, LOW
    }

    /**
     * General AI conversation - provides intelligent responses to any query
     */
    suspend fun chatWithAI(
        userMessage: String,
        messages: List<SmsMessage>,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing general AI query: $userMessage")

            val lowerMessage = userMessage.lowercase().trim()

            // First check if it's an SMS analysis query
            val smsAnalysisResponse = trySmsAnalysisQuery(userMessage, messages)
            if (smsAnalysisResponse != null) {
                return@withContext smsAnalysisResponse
            }

            // Handle general conversation
            return@withContext generateGeneralResponse(userMessage, messages, conversationHistory)
        } catch (e: Exception) {
            Log.e(TAG, "Error in AI chat", e)
            "⚠️ I'm having trouble processing that. Try asking about your SMS messages or spending analysis!"
        }
    }

    /**
     * Check if this is an SMS analysis query and handle it
     */
    private fun trySmsAnalysisQuery(question: String, messages: List<SmsMessage>): String? {
        val lowerQuestion = question.lowercase()

        // Extract merchant if mentioned in the query
        val queryMerchant = extractMerchantFromQuery(lowerQuestion)

        return when {
            // Merchant-specific spending query (e.g., "Amazon spending", "spent at Amazon")
            queryMerchant != null && (lowerQuestion.contains("spend") || lowerQuestion.contains("spent") ||
                    lowerQuestion.contains("transaction") || lowerQuestion.contains("purchase")) -> {
                analyzeMerchantSpending(messages, queryMerchant, lowerQuestion)
            }
            // General spending query
            lowerQuestion.contains("spend") || lowerQuestion.contains("spent") -> {
                analyzeSpending(messages, lowerQuestion)
            }
            // Transaction listing
            lowerQuestion.contains("transaction") -> {
                analyzeTransactions(messages, lowerQuestion)
            }
            // OTP queries
            lowerQuestion.contains("otp") || lowerQuestion.contains("code") -> {
                findOTPs(messages, lowerQuestion)
            }
            // Balance queries
            lowerQuestion.contains("balance") || lowerQuestion.contains("account") -> {
                findBalanceInfo(messages)
            }
            // Shopping queries (without spending context)
            queryMerchant != null -> {
                findMerchantMessages(messages, queryMerchant)
            }
            lowerQuestion.contains("shop") || lowerQuestion.contains("order") ||
            lowerQuestion.contains("deliver") -> {
                findShoppingMessages(messages, lowerQuestion)
            }
            // Banking queries
            lowerQuestion.contains("bank") || lowerQuestion.contains("payment") -> {
                findBankingMessages(messages, lowerQuestion)
            }
            // Summary requests
            lowerQuestion.contains("summary") || lowerQuestion.contains("summarize") -> {
                runBlocking { summarizeConversation(messages) }
            }
            else -> null // Not an SMS analysis query
        }
    }

    /**
     * Generate intelligent responses for general conversation
     */
    private fun generateGeneralResponse(
        userMessage: String,
        messages: List<SmsMessage>,
        conversationHistory: List<Pair<String, String>>
    ): String {
        val lowerMessage = userMessage.lowercase().trim()

        // Greetings
        if (lowerMessage.matches(Regex("(hi|hello|hey|good morning|good afternoon|good evening).*"))) {
            return when {
                lowerMessage.contains("morning") -> "Good morning! ☀️ How can I help you today?"
                lowerMessage.contains("afternoon") -> "Good afternoon! 🌤️ What can I assist you with?"
                lowerMessage.contains("evening") -> "Good evening! 🌙 How may I help you?"
                else -> "Hello! 👋 I'm your AI assistant. I can help you analyze your SMS messages, provide spending insights, or just chat. What would you like to know?"
            }
        }

        // How are you / status queries
        if (lowerMessage.contains("how are you") || lowerMessage.contains("how do you do")) {
            return "I'm doing great, thank you for asking! 🤖 I'm here to help you make sense of your SMS messages and provide useful insights. What would you like to explore?"
        }

        // Thanks
        if (lowerMessage.matches(Regex(".*(thank|thanks|thx|ty).*"))) {
            return "You're very welcome! 😊 Is there anything else I can help you with?"
        }

        // About queries
        if (lowerMessage.contains("what can you do") || lowerMessage.contains("help") || lowerMessage.contains("what do you do")) {
            return "I'm your smart SMS assistant! I can help you:\n\n" +
                    "💰 **Spending Analysis**\n" +
                    "• Track your expenses by merchant\n" +
                    "• Show spending patterns and trends\n" +
                    "• Analyze transactions and payments\n\n" +
                    "📱 **SMS Insights**\n" +
                    "• Find OTP codes and verification messages\n" +
                    "• Summarize conversations\n" +
                    "• Extract banking and payment information\n\n" +
                    "💬 **Smart Chat**\n" +
                    "• Answer questions about your messages\n" +
                    "• Provide contextual suggestions\n" +
                    "• Help organize your SMS data\n\n" +
                    "Try asking: \"How much did I spend this month?\" or \"Show my recent transactions\" or \"Summarize my messages\""
        }

        // Questions about capabilities
        if (lowerMessage.contains("can you") || lowerMessage.contains("do you")) {
            return "Yes! I'm designed to help you understand and analyze your SMS messages. I can:\n\n" +
                    "✅ Analyze spending patterns and transactions\n" +
                    "✅ Find OTP codes and security messages\n" +
                    "✅ Summarize conversations and extract key information\n" +
                    "✅ Identify merchants and categorize payments\n" +
                    "✅ Provide insights about your messaging habits\n\n" +
                    "What specific question do you have about your messages?"
        }

        // Default helpful response
        return "I'm here to help you analyze your SMS messages! 📱\n\n" +
                "Try asking me questions like:\n" +
                "• \"How much did I spend this month?\"\n" +
                "• \"Show my recent transactions\"\n" +
                "• \"What OTPs did I receive?\"\n" +
                "• \"Summarize my Amazon orders\"\n" +
                "• \"What's my account balance?\"\n\n" +
                "Or just tell me what you're looking for in your messages! 💬"
    }

    /**
     * Legacy method for SMS analysis - kept for compatibility
     */
    suspend fun askWithContext(
        question: String,
        messages: List<SmsMessage>,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): String = chatWithAI(question, messages, conversationHistory)

    /**
     * Extract merchant name from query
     */
    private fun extractMerchantFromQuery(query: String): String? {
        for ((merchant, aliases) in MERCHANT_ALIASES) {
            if (aliases.any { query.contains(it) }) {
                return merchant
            }
        }
        return null
    }

    /**
     * Parse transactions from messages with strict filtering
     */
    private fun parseTransactions(messages: List<SmsMessage>): List<ParsedTransaction> {
        val transactions = mutableListOf<ParsedTransaction>()

        // Amount patterns - must be preceded or followed by transaction context
        val amountPatterns = listOf(
            // INR patterns
            Regex("""(?:rs\.?|₹|inr)\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
            // USD patterns
            Regex("""(?:\$|usd)\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
            // Amount followed by INR
            Regex("""([0-9,]+(?:\.\d{1,2})?)\s*(?:rs\.?|₹|inr)""", RegexOption.IGNORE_CASE),
        )

        for (msg in messages) {
            val bodyLower = msg.body.lowercase()

            // Skip if it's a credit/refund message
            if (CREDIT_KEYWORDS.any { bodyLower.contains(it) }) {
                continue
            }

            // Must have at least one debit keyword
            val hasDebitKeyword = DEBIT_KEYWORDS.any { bodyLower.contains(it) }
            if (!hasDebitKeyword) {
                continue
            }

            // Extract amount
            var amount: Double? = null
            var currency = "INR"

            for (pattern in amountPatterns) {
                val match = pattern.find(msg.body)
                if (match != null) {
                    val amountStr = match.groupValues[1].replace(",", "")
                    amount = amountStr.toDoubleOrNull()
                    if (amount != null) {
                        // Determine currency
                        currency = if (msg.body.contains("$") || bodyLower.contains("usd")) "USD" else "INR"
                        break
                    }
                }
            }

            // Skip if no valid amount found or amount is unreasonably large (likely a reference number)
            if (amount == null || amount <= 0 || amount > 10000000) {
                continue
            }

            // Extract merchant from the message
            val merchant = extractMerchantFromMessage(msg.body)

            transactions.add(
                ParsedTransaction(
                    amount = amount,
                    currency = currency,
                    merchant = merchant,
                    date = msg.date,
                    messageBody = msg.body,
                    isDebit = true
                )
            )
        }

        return transactions.sortedByDescending { it.date }
    }

    /**
     * Extract merchant name from message body
     */
    private fun extractMerchantFromMessage(body: String): String? {
        val bodyLower = body.lowercase()

        // Check for known merchants
        for ((merchant, aliases) in MERCHANT_ALIASES) {
            if (aliases.any { bodyLower.contains(it) }) {
                return merchant.replaceFirstChar { it.uppercase() }
            }
        }

        // Try to extract from common patterns
        val merchantPatterns = listOf(
            Regex("""(?:at|to|from)\s+([A-Za-z][A-Za-z0-9\s&'./-]{2,25})(?:\s+(?:on|for|ref|card)|$)""", RegexOption.IGNORE_CASE),
            Regex("""(?:txn|transaction|purchase)\s+(?:at|on|to)\s+([A-Za-z][A-Za-z0-9\s&'./-]{2,25})""", RegexOption.IGNORE_CASE),
        )

        for (pattern in merchantPatterns) {
            val match = pattern.find(body)
            if (match != null) {
                val extracted = match.groupValues[1].trim()
                // Filter out common false positives
                val skipWords = listOf("your", "the", "a", "an", "card", "account", "bank", "ending")
                if (extracted.length in 2..25 && !skipWords.any { extracted.lowercase().startsWith(it) }) {
                    return extracted.replaceFirstChar { it.uppercase() }
                }
            }
        }

        return null
    }

    /**
     * Analyze spending for a specific merchant
     */
    private fun analyzeMerchantSpending(messages: List<SmsMessage>, merchant: String, query: String): String {
        val allTransactions = parseTransactions(messages)

        // Filter by merchant
        val merchantTransactions = allTransactions.filter { txn ->
            txn.merchant?.lowercase()?.contains(merchant.lowercase()) == true ||
            txn.messageBody.lowercase().contains(merchant.lowercase())
        }

        if (merchantTransactions.isEmpty()) {
            return "📊 No spending transactions found for ${merchant.replaceFirstChar { it.uppercase() }}.\n\n" +
                    "This could mean:\n" +
                    "• No ${merchant} purchases in your SMS history\n" +
                    "• Purchases were made via a different payment method\n" +
                    "• SMS notifications were not enabled"
        }

        // Apply time filter if specified
        val filteredTransactions = applyTimeFilter(merchantTransactions, query)

        val total = filteredTransactions.sumOf { it.amount }
        val currency = filteredTransactions.firstOrNull()?.currency ?: "INR"
        val currencySymbol = if (currency == "USD") "$" else "₹"

        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        val periodLabel = getTimePeriodLabel(query)

        return buildString {
            append("💳 ${merchant.replaceFirstChar { it.uppercase() }} Spending")
            if (periodLabel.isNotEmpty()) {
                append(" ($periodLabel)")
            }
            append("\n\n")
            append("Total: $currencySymbol${String.format("%,.2f", total)}\n")
            append("Transactions: ${filteredTransactions.size}\n\n")

            if (filteredTransactions.isNotEmpty()) {
                append("📝 Details:\n")
                filteredTransactions.take(10).forEachIndexed { index, txn ->
                    append("${index + 1}. $currencySymbol${String.format("%,.2f", txn.amount)} — ${dateFormat.format(Date(txn.date))}\n")
                    append("   ${txn.messageBody.take(70).replace("\n", " ")}...\n\n")
                }
            }
        }
    }

    /**
     * Analyze general spending patterns
     */
    private fun analyzeSpending(messages: List<SmsMessage>, query: String): String {
        val allTransactions = parseTransactions(messages)

        if (allTransactions.isEmpty()) {
            return "📊 No spending transactions found in your messages.\n\n" +
                    "Make sure you have SMS notifications enabled for your bank/payment apps."
        }

        // Apply time filter if specified
        val filteredTransactions = applyTimeFilter(allTransactions, query)

        val total = filteredTransactions.sumOf { it.amount }
        val currency = filteredTransactions.firstOrNull()?.currency ?: "INR"
        val currencySymbol = if (currency == "USD") "$" else "₹"
        val average = if (filteredTransactions.isNotEmpty()) total / filteredTransactions.size else 0.0

        val dateFormat = SimpleDateFormat("MMM dd", Locale.US)
        val periodLabel = getTimePeriodLabel(query)

        // Group by merchant for top spending
        val merchantTotals = filteredTransactions
            .groupBy { it.merchant ?: "Unknown" }
            .mapValues { it.value.sumOf { txn -> txn.amount } }
            .entries
            .sortedByDescending { it.value }
            .take(5)

        return buildString {
            append("💰 Spending Analysis")
            if (periodLabel.isNotEmpty()) {
                append(" ($periodLabel)")
            }
            append("\n\n")
            append("Total Spent: $currencySymbol${String.format("%,.2f", total)}\n")
            append("Transactions: ${filteredTransactions.size}\n")
            append("Average: $currencySymbol${String.format("%,.2f", average)}\n\n")

            if (merchantTotals.isNotEmpty()) {
                append("🏪 Top Merchants:\n")
                merchantTotals.forEachIndexed { index, (merchant, amount) ->
                    append("${index + 1}. $merchant: $currencySymbol${String.format("%,.2f", amount)}\n")
                }
                append("\n")
            }

            append("📝 Recent Transactions:\n")
            filteredTransactions.take(5).forEach { txn ->
                append("• ${dateFormat.format(Date(txn.date))}: $currencySymbol${String.format("%,.2f", txn.amount)}")
                txn.merchant?.let { append(" at $it") }
                append("\n")
            }
        }
    }

    /**
     * Apply time filter based on query
     */
    private fun applyTimeFilter(transactions: List<ParsedTransaction>, query: String): List<ParsedTransaction> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        return when {
            query.contains("today") -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                transactions.filter { it.date >= cal.timeInMillis }
            }
            query.contains("week") || query.contains("7 day") -> {
                transactions.filter { it.date >= now - 7L * 24 * 3600 * 1000 }
            }
            query.contains("month") || query.contains("30 day") -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                transactions.filter { it.date >= cal.timeInMillis }
            }
            query.contains("year") -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                transactions.filter { it.date >= cal.timeInMillis }
            }
            else -> transactions
        }
    }

    /**
     * Get time period label for display
     */
    private fun getTimePeriodLabel(query: String): String {
        return when {
            query.contains("today") -> "Today"
            query.contains("week") || query.contains("7 day") -> "This Week"
            query.contains("month") || query.contains("30 day") -> "This Month"
            query.contains("year") -> "This Year"
            else -> ""
        }
    }

    /**
     * Find all messages from a specific merchant
     */
    private fun findMerchantMessages(messages: List<SmsMessage>, merchant: String): String {
        val merchantMessages = messages.filter { msg ->
            msg.body.lowercase().contains(merchant.lowercase())
        }

        if (merchantMessages.isEmpty()) {
            return "📱 No messages found from ${merchant.replaceFirstChar { it.uppercase() }}."
        }

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        val recent = merchantMessages.take(10).joinToString("\n\n") {
            "${dateFormat.format(Date(it.date))}\n${it.body.take(120)}..."
        }

        return "📱 ${merchant.replaceFirstChar { it.uppercase() }} Messages (${merchantMessages.size} total):\n\n$recent"
    }

    /**
     * Analyze transactions
     */
    private fun analyzeTransactions(messages: List<SmsMessage>, query: String): String {
        val transactionMessages = messages.filter {
            it.body.contains(Regex("(debited|credited|transaction|payment)", RegexOption.IGNORE_CASE))
        }

        if (transactionMessages.isEmpty()) {
            return "📝 No transactions found."
        }

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        val transactions = transactionMessages.take(10).joinToString("\n\n") {
            "${dateFormat.format(Date(it.date))}\nFrom: ${it.address}\n${it.body.take(100)}"
        }

        return "📝 Recent Transactions (${transactionMessages.size} total):\n\n$transactions"
    }

    /**
     * Find OTP messages
     */
    private fun findOTPs(messages: List<SmsMessage>, query: String): String {
        val otpMessages = messages.filter {
            it.body.matches(Regex(".*\\b\\d{4,6}\\b.*")) &&
            it.body.contains(Regex("(otp|code|verification|verify|pin)", RegexOption.IGNORE_CASE))
        }

        if (otpMessages.isEmpty()) {
            return "🔐 No OTP messages found."
        }

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        val otps = otpMessages.take(10).joinToString("\n\n") {
            val otpMatch = Regex("\\b(\\d{4,6})\\b").find(it.body)
            val otp = otpMatch?.value ?: "N/A"
            "${dateFormat.format(Date(it.date))}\nOTP: $otp\nFrom: ${it.address}"
        }

        return "🔐 Recent OTPs (${otpMessages.size} total):\n\n$otps"
    }

    /**
     * Find balance information
     */
    private fun findBalanceInfo(messages: List<SmsMessage>): String {
        val balanceMessages = messages.filter {
            it.body.contains(Regex("(balance|available balance|a/c bal)", RegexOption.IGNORE_CASE))
        }

        if (balanceMessages.isEmpty()) {
            return "💳 No balance information found."
        }

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        val balances = balanceMessages.take(5).joinToString("\n\n") {
            "${dateFormat.format(Date(it.date))}\n${it.body.take(150)}"
        }

        return "💳 Account Balance Information:\n\n$balances"
    }

    /**
     * Find shopping-related messages
     */
    private fun findShoppingMessages(messages: List<SmsMessage>, query: String): String {
        val keywords = listOf("amazon", "flipkart", "myntra", "order", "delivered", "shipped")
        val shoppingMessages = messages.filter { msg ->
            keywords.any { keyword ->
                msg.body.contains(keyword, ignoreCase = true)
            }
        }

        if (shoppingMessages.isEmpty()) {
            return "🛍️ No shopping-related messages found."
        }

        val dateFormat = SimpleDateFormat("MMM dd", Locale.US)
        val orders = shoppingMessages.take(10).joinToString("\n\n") {
            "${dateFormat.format(Date(it.date))}\n${it.body.take(100)}..."
        }

        return "🛍️ Shopping Updates (${shoppingMessages.size} total):\n\n$orders"
    }

    /**
     * Find banking messages
     */
    private fun findBankingMessages(messages: List<SmsMessage>, query: String): String {
        val bankingMessages = messages.filter {
            it.body.contains(Regex("(bank|hdfc|icici|sbi|axis|kotak|payment|transfer|upi)", RegexOption.IGNORE_CASE))
        }

        if (bankingMessages.isEmpty()) {
            return "🏦 No banking messages found."
        }

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
        val banking = bankingMessages.take(10).joinToString("\n\n") {
            "${dateFormat.format(Date(it.date))}\n${it.body.take(120)}"
        }

        return "🏦 Banking Messages (${bankingMessages.size} total):\n\n$banking"
    }

    /**
     * Generate intelligent conversation summary using enhanced pattern analysis
     */
    suspend fun summarizeConversation(messages: List<SmsMessage>): String = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext "No messages to summarize."

        val messageCount = messages.size
        val sentCount = messages.count { it.type == 2 }
        val receivedCount = messages.count { it.type == 1 }

        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        val firstDate = dateFormat.format(Date(messages.minOf { it.date }))
        val lastDate = dateFormat.format(Date(messages.maxOf { it.date }))

        val context = analyzeConversationContext(messages.takeLast(10))
        val keyTopics = extractKeyTopics(messages)
        val activityPattern = analyzeActivityPattern(messages)
        val sentimentTrend = analyzeSentimentTrend(messages)

        return@withContext buildString {
            append("🧠 Smart Summary\n\n")

            // Key insights
            when {
                context.sentiment == Sentiment.POSITIVE && context.urgency == Urgency.LOW -> {
                    append("✨ This appears to be a friendly, casual conversation\n")
                }
                context.sentiment == Sentiment.NEGATIVE -> {
                    append("🤝 This conversation involves some concerns or issues\n")
                }
                context.urgency == Urgency.HIGH -> {
                    append("⚡ This conversation has time-sensitive topics\n")
                }
            }

            // Topics
            if (keyTopics.isNotEmpty()) {
                append("📋 Main Topics: ${keyTopics.joinToString(", ")}\n")
            }

            // Activity pattern
            append("📊 Activity: $messageCount messages ")
            append("($sentCount sent, $receivedCount received)\n")

            when (activityPattern) {
                "very_active" -> append("🔥 Very active conversation\n")
                "active" -> append("💬 Active conversation\n")
                "moderate" -> append("📝 Moderate activity\n")
                "slow" -> append("🐌 Slow conversation\n")
            }

            // Time period
            append("📅 Period: $firstDate to $lastDate\n\n")

            // Recent highlights
            append("💭 Recent Highlights:\n")
            messages.takeLast(3).forEachIndexed { index, msg ->
                val sender = if (msg.type == 2) "You" else "Contact"
                append("${index + 1}. $sender: ${msg.body.take(60)}${if (msg.body.length > 60) "..." else ""}\n")
            }

            // Sentiment insight
            when (sentimentTrend) {
                "improving" -> append("\n📈 Conversation tone is improving")
                "worsening" -> append("\n📉 Conversation tone has become more serious")
                "positive" -> append("\n😊 Generally positive conversation")
                "mixed" -> append("\n⚖️ Mixed sentiments throughout")
            }
        }
    }

    /**
     * Extract key topics from the entire conversation
     */
    private fun extractKeyTopics(messages: List<SmsMessage>): List<String> {
        val topics = mutableMapOf<String, Int>()
        messages.forEach { msg ->
            val foundTopics = extractTopics(listOf(msg))
            foundTopics.forEach { topic ->
                topics[topic] = topics.getOrDefault(topic, 0) + 1
            }
        }
        return topics.entries.sortedByDescending { it.value }.take(3).map { it.key }
    }

    /**
     * Analyze activity pattern
     */
    private fun analyzeActivityPattern(messages: List<SmsMessage>): String {
        if (messages.size < 5) return "minimal"

        val timeSpan = messages.maxOf { it.date } - messages.minOf { it.date }
        val days = timeSpan / (24 * 60 * 60 * 1000.0)
        val messagesPerDay = if (days > 0) messages.size / days else messages.size.toDouble()

        return when {
            messagesPerDay >= 10 -> "very_active"
            messagesPerDay >= 5 -> "active"
            messagesPerDay >= 2 -> "moderate"
            else -> "slow"
        }
    }

    /**
     * Analyze sentiment trend over time
     */
    private fun analyzeSentimentTrend(messages: List<SmsMessage>): String {
        if (messages.size < 3) return "neutral"

        val recent = messages.takeLast(messages.size / 2)
        val earlier = messages.take(messages.size / 2)

        val recentSentiment = analyzeSentiment(recent)
        val earlierSentiment = analyzeSentiment(earlier)

        return when {
            recentSentiment == Sentiment.POSITIVE && earlierSentiment != Sentiment.POSITIVE -> "improving"
            recentSentiment == Sentiment.NEGATIVE && earlierSentiment != Sentiment.NEGATIVE -> "worsening"
            recentSentiment == Sentiment.POSITIVE -> "positive"
            recentSentiment == Sentiment.NEGATIVE -> "negative"
            else -> "mixed"
        }
    }

    /**
     * Generate intelligent message suggestions using advanced pattern matching
     */
    suspend fun generateMessageSuggestions(
        messages: List<SmsMessage>,
        context: String = "",
        count: Int = 3
    ): List<String> = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext emptyList()

        val lastMessage = messages.last()
        val conversationContext = analyzeConversationContext(messages.takeLast(5))

        return@withContext generateContextualReplies(lastMessage.body, conversationContext, count)
    }

    /**
     * Analyze conversation context for better suggestions
     */
    private fun analyzeConversationContext(recentMessages: List<SmsMessage>): ConversationContext {
        val sent = recentMessages.count { it.type == 2 }
        val received = recentMessages.count { it.type == 1 }

        val topics = extractTopics(recentMessages)
        val sentiment = analyzeSentiment(recentMessages)
        val urgency = detectUrgency(recentMessages.lastOrNull()?.body ?: "")

        return ConversationContext(sent, received, topics, sentiment, urgency)
    }

    /**
     * Generate contextual replies based on conversation analysis
     */
    private fun generateContextualReplies(message: String, context: ConversationContext, count: Int): List<String> {
        val lowerMessage = message.lowercase()

        // Enhanced reply patterns based on context
        val suggestions = mutableListOf<String>()

        when {
            // Questions
            lowerMessage.contains("?") -> {
                suggestions.addAll(listOf(
                    "Yes, definitely!",
                    "Let me check that for you",
                    "I'm not sure, can you clarify?",
                    "That sounds good to me",
                    "I'll get back to you on that"
                ))
            }

            // Gratitude
            lowerMessage.contains("thank") -> {
                suggestions.addAll(listOf(
                    "You're welcome! 😊",
                    "Happy to help!",
                    "No problem at all",
                    "Anytime!",
                    "Glad I could assist"
                ))
            }

            // Apologies
            lowerMessage.contains("sorry") -> {
                suggestions.addAll(listOf(
                    "No worries at all",
                    "It's completely fine",
                    "Don't worry about it",
                    "All good!",
                    "No problem 😊"
                ))
            }

            // Time-sensitive (morning/afternoon/evening)
            lowerMessage.contains("morning") || lowerMessage.contains("good morning") -> {
                suggestions.addAll(listOf(
                    "Good morning! Hope you have a great day",
                    "Morning! How are you today?",
                    "Good morning! Ready for the day?",
                    "Hey there! Good morning to you too"
                ))
            }

            // Plans and scheduling
            lowerMessage.contains("meet") || lowerMessage.contains("see") || lowerMessage.contains("call") -> {
                suggestions.addAll(listOf(
                    "Sounds good! What time works for you?",
                    "I'm available. Let me know when",
                    "Perfect! Looking forward to it",
                    "Sure thing! Just let me know the details"
                ))
            }

            // Work-related
            lowerMessage.contains("work") || lowerMessage.contains("meeting") || lowerMessage.contains("project") -> {
                suggestions.addAll(listOf(
                    "Got it, I'll take care of that",
                    "Understood. I'll follow up",
                    "Thanks for the update",
                    "I'll get right on it"
                ))
            }

            // Default conversational responses
            else -> {
                val defaultReplies = listOf(
                    "Thanks for letting me know",
                    "Got it, thanks!",
                    "Okay, noted",
                    "Sounds good",
                    "Thanks for the info",
                    "I'll keep that in mind",
                    "Okay, perfect",
                    "Thanks! 🙏",
                    "Alright, got it",
                    "Thank you!"
                )
                suggestions.addAll(defaultReplies)
            }
        }

        // Filter based on conversation context
        val filtered = suggestions.filter { reply ->
            when (context.sentiment) {
                Sentiment.POSITIVE -> reply.contains("great") || reply.contains("good") || reply.contains("😊")
                Sentiment.NEGATIVE -> reply.contains("sorry") || reply.contains("worry")
                else -> true
            }
        }

        return filtered.distinct().take(count)
    }

    /**
     * Extract topics from recent messages
     */
    private fun extractTopics(messages: List<SmsMessage>): List<String> {
        val topics = mutableSetOf<String>()
        val text = messages.joinToString(" ") { it.body.lowercase() }

        val topicKeywords = mapOf(
            "work" to listOf("meeting", "project", "work", "office", "deadline", "task"),
            "personal" to listOf("family", "friend", "home", "weekend", "party", "dinner"),
            "shopping" to listOf("buy", "purchase", "shopping", "store", "order", "delivery"),
            "travel" to listOf("flight", "travel", "trip", "vacation", "hotel", "booking"),
            "health" to listOf("doctor", "appointment", "medicine", "health", "sick"),
            "finance" to listOf("money", "payment", "bank", "account", "bill", "budget")
        )

        topicKeywords.forEach { (topic, keywords) ->
            if (keywords.any { text.contains(it) }) {
                topics.add(topic)
            }
        }

        return topics.toList()
    }

    /**
     * Analyze sentiment of recent messages
     */
    private fun analyzeSentiment(messages: List<SmsMessage>): Sentiment {
        val text = messages.joinToString(" ") { it.body.lowercase() }

        val positiveWords = listOf("good", "great", "excellent", "awesome", "amazing", "perfect",
                                 "happy", "love", "wonderful", "fantastic", "nice", "thanks", "thank")
        val negativeWords = listOf("bad", "terrible", "awful", "horrible", "sad", "angry",
                                 "hate", "worst", "disappointed", "sorry", "problem", "issue")

        val positiveCount = positiveWords.count { text.contains(it) }
        val negativeCount = negativeWords.count { text.contains(it) }

        return when {
            positiveCount > negativeCount -> Sentiment.POSITIVE
            negativeCount > positiveCount -> Sentiment.NEGATIVE
            else -> Sentiment.NEUTRAL
        }
    }

    /**
     * Detect urgency level from message
     */
    private fun detectUrgency(message: String): Urgency {
        val lowerMessage = message.lowercase()

        val urgentKeywords = listOf("urgent", "asap", "emergency", "important", "critical",
                                  "immediately", "right now", "soon", "quickly", "deadline")

        val mediumKeywords = listOf("today", "tomorrow", "this week", "soon", "when you can")

        return when {
            urgentKeywords.any { lowerMessage.contains(it) } -> Urgency.HIGH
            mediumKeywords.any { lowerMessage.contains(it) } -> Urgency.MEDIUM
            else -> Urgency.LOW
        }
    }


}
