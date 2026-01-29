package com.phoneintegration.app.ui.ai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.ai.AIService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.*
import kotlin.math.min
import com.phoneintegration.app.MessageCategory

data class ChatMessage(val role: String, val content: String)
data class QuickAction(
    val icon: ImageVector,
    val title: String,
    val query: String,
    val color: Long
)

val QUICK_ACTIONS = listOf(
    QuickAction(Icons.Outlined.TrendingUp, "Spending", "How much did I spend this month?", 0xFF2196F3),
    QuickAction(Icons.Outlined.Receipt, "Bills", "Show my upcoming bills", 0xFFFF9800),
    QuickAction(Icons.Outlined.LocalShipping, "Packages", "Track my packages", 0xFF4CAF50),
    QuickAction(Icons.Outlined.AccountBalance, "Balance", "What is my account balance?", 0xFF9C27B0),
    QuickAction(Icons.Outlined.VpnKey, "OTPs", "Show my OTP codes", 0xFFF44336),
    QuickAction(Icons.Outlined.ShoppingCart, "Transactions", "List my transactions", 0xFF009688),
)

data class Transaction(
    val amount: Double,
    val date: Long,
    val message: String,
    val merchant: String?,
    val currency: String = "USD",
    val category: String = "OTHER"
)

object MerchantModel {

    private val known = mapOf(
        "AMZN" to "AMAZON",
        "AMAZON" to "AMAZON",
        "AMAZN" to "AMAZON",
        "APPLE.COM" to "APPLE",
        "APPLE" to "APPLE",
        "GOOGLE" to "GOOGLE",
        "WMT" to "WALMART",
        "WALMART" to "WALMART",
        "TARGET" to "TARGET",
        "COSTCO" to "COSTCO",
        "UBER" to "UBER",
        "LYFT" to "LYFT",
        "KROGER" to "KROGER",
        "BEST BUY" to "BEST BUY",
        "MCDONALD" to "MCDONALDS",
        "STARBUCKS" to "STARBUCKS",
        "WALGREENS" to "WALGREENS",
        "CVS" to "CVS",
        "HOMEDEPOT" to "HOME DEPOT",
        "HOME DEPOT" to "HOME DEPOT",
        "LOWES" to "LOWE'S",
        "7-ELEVEN" to "7-ELEVEN",
        "NETFLIX" to "NETFLIX",
        "SPOTIFY" to "SPOTIFY",
        "DOORDASH" to "DOORDASH",
        "GRUBHUB" to "GRUBHUB",
        "ZOMATO" to "ZOMATO",
        "SWIGGY" to "SWIGGY",
    )

    private val merchantRegex = Regex(
        """
        (?ix)
        (?:
            txn|transaction|purchase|spent|debit|debited|charged|pos|sale|auth|card\s+purchase
        )
        [\s:]* (?:at|on|to)? [\s:]*
        (
            [a-z0-9][a-z0-9\s.&'/-]{2,40}
        )
        (?=
            \s*(?:for|on|ref|at|with|card)
        )
        """.trimIndent(),
        setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
    )

    fun clean(name: String): String {
        var n = name.uppercase()
        n = n.replace(Regex("""WWW\.?|HTTPS?://"""), "")
        n = n.replace(Regex("""\b(COM|NET|ORG|ONLINE)\b"""), "")
        n = n.replace(Regex("""[*/]"""), " ")
        n = n.replace(Regex("""\s+"""), " ").trim()
        return n.takeIf { it.length in 2..40 } ?: ""
    }

    fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(
                    dp[i - 1][j] + 1,
                    min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                )
            }
        }
        return dp[a.length][b.length]
    }

    fun fuzzyMatch(input: String): String {
        val cleaned = clean(input)
        if (cleaned.isBlank()) return ""

        known.keys.forEach { key ->
            if (cleaned.contains(key)) return known[key]!!
        }

        val best = known.keys.minByOrNull { levenshtein(cleaned, it) }
        val dist = best?.let { levenshtein(cleaned, it) } ?: 99
        if (dist <= 2) return known[best]!!

        return cleaned
    }

    fun guessCategory(msg: String, merchant: String?): String {
        val m = merchant?.lowercase() ?: ""
        val lower = msg.lowercase()
        val combined = "$m $lower"

        val categoryKeywords = mapOf(
            "TRANSPORT" to listOf("uber", "lyft", "ola", "taxi", "bus", "metro", "cab"),
            "FUEL" to listOf("fuel", "gas", "shell", "bp", "chevron", "exxon"),
            "GROCERIES" to listOf("kroger", "walmart", "costco", "aldi", "safeway", "trader joe's", "grocery"),
            "SHOPPING" to listOf("amazon", "best buy", "target", "macys", "nordstrom", "shop"),
            "FOOD" to listOf("mcdonalds", "starbucks", "restaurant", "dining", "doordash", "grubhub", "zomato", "swiggy", "coffee", "pizza"),
            "SUBSCRIPTION" to listOf("spotify", "netflix", "prime", "hulu", "disney+", "subscription"),
            "TRAVEL" to listOf("flight", "hotel", "airbnb", "expedia", "booking", "airline", "travel"),
            "ENTERTAINMENT" to listOf("movie", "cinema", "theater", "concert", "tickets"),
            "BILLS" to listOf("bill", "utility", "electricity", "water", "internet", "phone"),
            "HEALTH" to listOf("pharmacy", "walgreens", "cvs", "doctor", "hospital", "clinic"),
        )

        for ((category, keywords) in categoryKeywords) {
            if (keywords.any { combined.contains(it) }) {
                return category
            }
        }

        return "OTHER"
    }

    fun extract(body: String): String? {
        val raw = merchantRegex.find(body)?.groupValues?.getOrNull(1) ?: return null
        val cleaned = clean(raw)
        return fuzzyMatch(cleaned)
    }
}

object SpendingParser {

    private val amountRegex = listOf(
        Regex("""(?:rs\.?|₹|inr)\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""(?:usd|\$)\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""amount[: ]*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    )

    fun analyze(messages: List<SmsMessage>): List<Transaction> {
        val out = mutableListOf<Transaction>()

        messages.forEach { msg ->
            val body = msg.body
            val lower = body.lowercase()

            if (lower.contains("credited") ||
                lower.contains("refund") ||
                lower.contains("reversal") ||
                lower.contains("deposit")
            ) return@forEach

            var amount: Double? = null
            for (r in amountRegex) {
                val m = r.find(body)
                if (m != null) {
                    amount = m.groupValues[1].replace(",", "").toDoubleOrNull()
                    break
                }
            }
            if (amount == null) return@forEach

            val currency = if (lower.contains("₹") || lower.contains("rs")) "INR" else "USD"
            val merchant = MerchantModel.extract(body)
            val category = MerchantModel.guessCategory(body, merchant)

            out.add(
                Transaction(
                    amount = amount!!,
                    date = msg.date,
                    message = body,
                    merchant = merchant,
                    currency = currency,
                    category = category
                )
            )
        }

        return out.sortedByDescending { it.date }
    }
}

// -------------------------------------------------------
// UI + SMART QUERY ENGINE
// -------------------------------------------------------

private data class QueryHandler(
    val name: String,
    val regex: Regex,
    val handler: (MatchResult) -> String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIAssistantScreen(messages: List<SmsMessage>, onDismiss: () -> Unit) {

    val context = LocalContext.current
    val aiService = remember { AIService(context) }

    val transactions by remember(messages) {
        mutableStateOf(SpendingParser.analyze(messages))
    }

    val otps by remember(messages) {
        mutableStateOf(
            messages
                .filter { it.category == MessageCategory.OTP && it.otpInfo != null }
                .map { Triple(it.otpInfo!!.code, it.date, it.body) }
                .sortedByDescending { it.second }
        )
    }

    var query by remember { mutableStateOf("") }
    var conversation by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // -------------------------------------------------------
    // AUTOSCROLL
    // -------------------------------------------------------
    LaunchedEffect(conversation.size) {
        if (conversation.isNotEmpty()) {
            listState.animateScrollToItem(conversation.size - 1)
        }
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    fun formatDate(ts: Long): String =
        java.text.SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date(ts))

    fun formatCurrency(a: Double, c: String = "USD"): String {
        val f = if (c == "INR")
            NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        else NumberFormat.getCurrencyInstance(Locale.US)
        return f.format(a)
    }

    // Unified transaction search
    fun searchByMerchant(term: String): List<Transaction> {
        val q = term.lowercase()
        return transactions.filter {
            it.merchant?.lowercase()?.contains(q) == true
        }
    }

    val queryHandlers = remember(transactions, otps) {
        listOf(
            QueryHandler(
                name = "LIST_TRANSACTIONS_FOR_MERCHANT",
                regex = Regex("""([a-z0-9\s.'-]+)\s+transactions""", RegexOption.IGNORE_CASE),
                handler = { matchResult ->
                    val merchant = matchResult.groupValues[1].trim()
                    val list = searchByMerchant(merchant)
                    if (list.isEmpty()) "No transactions found for \"$merchant\"."
                    else {
                        val total = list.sumOf { it.amount }
                        val sb = StringBuilder()
                        sb.append("💳 **Transactions for $merchant**\n")
                        sb.append("Total spent: **${formatCurrency(total, list.first().currency)}**\n\n")
                        list.take(10).forEachIndexed { i, t ->
                            sb.append("${i + 1}. ${formatCurrency(t.amount, t.currency)} — ${formatDate(t.date)}\n")
                            sb.append("   ${t.merchant ?: "UNKNOWN"} • ${t.category}\n")
                            sb.append("   ${t.message.take(90)}...\n\n")
                        }
                        sb.toString()
                    }
                }
            ),
            QueryHandler(
                name = "LIST_TRANSACTIONS",
                regex = Regex("""list\s+transactions(?:\s+for\s+([a-z\s.'-]+))?""", RegexOption.IGNORE_CASE),
                handler = { matchResult ->
                    val merchant = matchResult.groupValues.getOrNull(1)?.trim()
                    val list = if (merchant != null) searchByMerchant(merchant) else transactions
                    if (list.isEmpty()) "No transactions found."
                    else {
                        val total = list.sumOf { it.amount }
                        val sb = StringBuilder()
                        sb.append("💳 **Listing transactions ${merchant?.let { "for $it " } ?: ""}**\n")
                        sb.append("Total spent: **${formatCurrency(total, list.first().currency)}**\n\n")
                        list.take(12).forEachIndexed { i, t ->
                            sb.append("${i + 1}. ${formatCurrency(t.amount, t.currency)} — ${formatDate(t.date)}\n")
                            sb.append("   ${t.merchant ?: "UNKNOWN"} • ${t.category}\n")
                            sb.append("   ${t.message.take(90)}...\n\n")
                        }
                        sb.toString()
                    }
                }
            ),
            QueryHandler(
                name = "SPENT_AT_MERCHANT",
                regex = Regex("""(?:spent|spend) at\s+([a-z0-9\s.'-]+)""", RegexOption.IGNORE_CASE),
                handler = { matchResult ->
                    val q = query.lowercase()
                    val merchant = matchResult.groupValues[1].trim()

                    fun applyTimeFilter(list: List<Transaction>): List<Transaction> {
                        val now = System.currentTimeMillis()
                        val cal = Calendar.getInstance()
                        return when {
                            q.contains("today") -> {
                                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
                                list.filter { it.date >= cal.timeInMillis }
                            }
                            q.contains("week") -> list.filter { it.date >= now - 7L * 24 * 3600 * 1000 }
                            q.contains("month") -> {
                                cal.set(Calendar.DAY_OF_MONTH, 1); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
                                list.filter { it.date >= cal.timeInMillis }
                            }
                            else -> list
                        }
                    }

                    val merchantList = searchByMerchant(merchant)
                    if (merchantList.isEmpty()) "No spending detected at \"$merchant\"."
                    else {
                        val filtered = applyTimeFilter(merchantList)
                        if (filtered.isEmpty()) "No spending detected for \"$merchant\" in the selected period."
                        else {
                            val total = filtered.sumOf { it.amount }
                            val sb = StringBuilder()
                            sb.append("**Spending breakdown for $merchant:**\n")
                            when {
                                q.contains("today") -> sb.append("**(Today)**\n\n")
                                q.contains("week") -> sb.append("**(This Week)**\n\n")
                                q.contains("month") -> sb.append("**(This Month)**\n\n")
                                else -> sb.append("\n")
                            }
                            filtered.forEachIndexed { i, t ->
                                sb.append("${i + 1}. ${formatCurrency(t.amount, t.currency)} — ${formatDate(t.date)}\n")
                                sb.append("   ${t.message.take(80)}...\n\n")
                            }
                            sb.append("**-----------------------------**\n")
                            sb.append("**Total = ${formatCurrency(total, filtered.first().currency)}**\n")
                            sb.toString()
                        }
                    }
                }
            ),
            QueryHandler(
                name = "TOTAL_SPENT",
                regex = Regex("""spent|spend""", RegexOption.IGNORE_CASE),
                handler = {
                    val q = query.lowercase()
                    fun applyTimeFilter(list: List<Transaction>): List<Transaction> {
                        val now = System.currentTimeMillis()
                        val cal = Calendar.getInstance()
                        return when {
                            q.contains("today") -> {
                                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
                                list.filter { it.date >= cal.timeInMillis }
                            }
                            q.contains("week") -> list.filter { it.date >= now - 7L * 24 * 3600 * 1000 }
                            q.contains("month") -> {
                                cal.set(Calendar.DAY_OF_MONTH, 1); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
                                list.filter { it.date >= cal.timeInMillis }
                            }
                            else -> list
                        }
                    }
                    val period = applyTimeFilter(transactions)
                    val label = when {
                        q.contains("today") -> "Today"
                        q.contains("week") -> "This week"
                        q.contains("month") -> "This month"
                        else -> "Total"
                    }
                    val total = period.sumOf { it.amount }
                    "$label spending = **${formatCurrency(total)}** across ${period.size} transactions."
                }
            ),
            QueryHandler(
                name = "TOP_MERCHANTS",
                regex = Regex("""top\s+merchants?""", RegexOption.IGNORE_CASE),
                handler = {
                    val grouped = transactions
                        .groupBy { it.merchant ?: "UNKNOWN" }
                        .mapValues { it.value.sumOf { t -> t.amount } }
                        .entries.sortedByDescending { it.value }
                        .take(8)
                    val sb = StringBuilder()
                    sb.append("🏪 **Top Merchants**\n\n")
                    grouped.forEachIndexed { i, (m, amt) ->
                        sb.append("${i + 1}. $m — ${formatCurrency(amt)}\n")
                    }
                    sb.toString()
                }
            ),
            QueryHandler(
                name = "CATEGORY_BREAKDOWN",
                regex = Regex("""category|breakdown""", RegexOption.IGNORE_CASE),
                handler = {
                    val grouped = transactions.groupBy { it.category }
                        .mapValues { it.value.sumOf { t -> t.amount } }
                        .entries.sortedByDescending { it.value }
                    val total = grouped.sumOf { it.value }
                    val sb = StringBuilder()
                    sb.append("📊 **Spending by category**\n\n")
                    grouped.forEach { (cat, amt) ->
                        val pct = (amt / total * 100).toInt()
                        sb.append("$cat — ${formatCurrency(amt)} ($pct%)\n")
                    }
                    sb.toString()
                }
            ),
            QueryHandler(
                name = "LIST_OTPS",
                regex = Regex("""otp|code""", RegexOption.IGNORE_CASE),
                handler = {
                    if (otps.isEmpty()) "No OTP messages found."
                    else {
                        val sb = StringBuilder()
                        sb.append("🔐 **OTP codes found:**\n\n")
                        otps.take(10).forEachIndexed { i, o ->
                            sb.append("${i + 1}. ${o.first} — ${formatDate(o.second)}\n")
                            sb.append("   ${o.third.take(90)}...\n\n")
                        }
                        sb.toString()
                    }
                }
            ),
            QueryHandler(
                name = "SEARCH",
                regex = Regex("""search|find""", RegexOption.IGNORE_CASE),
                handler = {
                    val term = query.lowercase().removePrefix("search").removePrefix("find").trim()
                    if (term.isBlank()) "Please type something to search."
                    else {
                        val results = messages.filter { it.body.contains(term, true) }
                            .sortedByDescending { it.date }
                            .take(12)
                        if (results.isEmpty()) "No messages found for \"$term\"."
                        else {
                            val sb = StringBuilder()
                            sb.append("🔍 **Search results:**\n\n")
                            results.forEachIndexed { i, msg ->
                                sb.append("${i + 1}. ${formatDate(msg.date)} — ${msg.body.take(90)}...\n\n")
                            }
                            sb.toString()
                        }
                    }
                }
            )
        )
    }

    // -------------------------------------------------------
    // MAIN RESPONSE ENGINE
    // -------------------------------------------------------

    fun generateResponse(userQuestionRaw: String): String {
        val userQuestion = userQuestionRaw.trim().lowercase()
        val header = "🗣️ **You asked:** \"$userQuestionRaw\"\n\n"

        for (handler in queryHandlers) {
            val match = handler.regex.find(userQuestion)
            if (match != null) {
                return header + handler.handler(match)
            }
        }

        return header + """
I can analyze your entire SMS inbox.

Try asking:
• "Amazon transactions"
• "Uber transactions"
• "List transactions"
• "Spent at Walmart"
• "How much did I spend this month?"
• "Category breakdown"
• "Top merchants"
• "Search Amazon"
• "Show OTP codes"
""".trimIndent()
    }
// -------------------------------------------------------
// SEND LOGIC - Enhanced with AI
// -------------------------------------------------------

    fun handleSubmit() {
        if (query.isBlank() || isLoading) return

        val userMessage = query.trim()
        query = ""
        isLoading = true

        conversation += ChatMessage("user", userMessage)

        scope.launch {
            // Use AIService for intelligent conversation
            val response = try {
                val history = conversation.dropLast(1).map { it.role to it.content }
                aiService.chatWithAI(userMessage, messages, history)
            } catch (e: Exception) {
                "⚠️ Error: ${e.message ?: "Unknown error occurred"}"
            }

            conversation += ChatMessage("assistant", response)
            isLoading = false
        }
    }

    // -------------------------------------------------------
    // UI ROOT (FULL RESTORED WITH FIXED INSETS)
    // -------------------------------------------------------

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()            // keyboard safe
            .navigationBarsPadding(), // bottom bar safe
        color = MaterialTheme.colorScheme.background
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {

            // ---------------------------------------------------
            // HEADER
            // ---------------------------------------------------

            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary
                                    )
                                ),
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "🧠",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "AI Assistant",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Understands your messages & spending",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // New Chat button - show when conversation exists
                    if (conversation.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                conversation = emptyList()
                                query = ""
                            }
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "New Chat",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close"
                        )
                    }
                }
            }

            // ---------------------------------------------------
            // CHAT WINDOW
            // ---------------------------------------------------

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {

                if (conversation.isEmpty()) {

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(8.dp)) }

                        // Smart Digest Card - show if transactions available
                        if (transactions.isNotEmpty()) {
                            item {
                                SmartDigestCard(
                                    transactions = transactions,
                                    formatCurrency = ::formatCurrency
                                )
                            }
                        }

                        // Welcome header
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "How can I help you?",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Ask about spending, bills, packages, and more",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Quick Action Cards Grid
                        item {
                            QuickActionGrid(
                                actions = QUICK_ACTIONS,
                                onActionClick = { action ->
                                    query = action.query
                                    conversation += ChatMessage("user", action.query)
                                    isLoading = true
                                    scope.launch {
                                        val response = try {
                                            aiService.chatWithAI(action.query, messages, emptyList())
                                        } catch (e: Exception) {
                                            "⚠️ Error: ${e.message ?: "Unknown error occurred"}"
                                        }
                                        conversation += ChatMessage("assistant", response)
                                        isLoading = false
                                        query = ""
                                    }
                                }
                            )
                        }

                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }

                } else {

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        items(conversation) { ChatBubble(it) }

                        if (isLoading) {
                            item { TypingIndicator() }
                        }
                    }
                }
            }

            // ---------------------------------------------------
            // INPUT BAR
            // ---------------------------------------------------

            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 10.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 16.dp,
                            vertical = 12.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Ask about spending, merchants, OTP...") },
                        maxLines = 4,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    FilledIconButton(
                        onClick = { handleSubmit() },
                        enabled = query.isNotBlank() && !isLoading,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}
// -------------------------------------------------------
// SMART DIGEST CARD
// -------------------------------------------------------

@Composable
fun SmartDigestCard(
    transactions: List<Transaction>,
    formatCurrency: (Double, String) -> String
) {
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    val monthStart = calendar.timeInMillis

    calendar.add(Calendar.MONTH, -1)
    val lastMonthStart = calendar.timeInMillis
    calendar.add(Calendar.MONTH, 1)
    val lastMonthEnd = monthStart

    val thisMonthTxns = transactions.filter { it.date >= monthStart }
    val lastMonthTxns = transactions.filter { it.date >= lastMonthStart && it.date < lastMonthEnd }

    val totalThisMonth = thisMonthTxns.sumOf { it.amount }
    val totalLastMonth = lastMonthTxns.sumOf { it.amount }
    val currency = thisMonthTxns.firstOrNull()?.currency ?: transactions.firstOrNull()?.currency ?: "USD"

    val spendingChange = if (totalLastMonth > 0) {
        ((totalThisMonth - totalLastMonth) / totalLastMonth * 100).toInt()
    } else 0

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "📊 This Month",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        formatCurrency(totalThisMonth, currency),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (totalLastMonth > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (spendingChange >= 0) "↑ $spendingChange%" else "↓ ${-spendingChange}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (spendingChange > 0)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary
                            )
                            Text(
                                " vs last month",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${thisMonthTxns.size}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "transactions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------
// QUICK ACTION GRID
// -------------------------------------------------------

@Composable
fun QuickActionGrid(
    actions: List<QuickAction>,
    onActionClick: (QuickAction) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Display in 2 columns
        actions.chunked(2).forEach { rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowActions.forEach { action ->
                    QuickActionCard(
                        action = action,
                        onClick = { onActionClick(action) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Fill empty space if odd number
                if (rowActions.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun QuickActionCard(
    action: QuickAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = androidx.compose.ui.graphics.Color(action.color).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = action.title,
                    tint = androidx.compose.ui.graphics.Color(action.color),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = action.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// -------------------------------------------------------
// CHAT BUBBLE
// -------------------------------------------------------

@Composable
fun ChatBubble(msg: ChatMessage) {

    val isUser = msg.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {

        Surface(
            color = if (isUser)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 4.dp else 16.dp,
                bottomEnd = if (isUser) 16.dp else 4.dp
            ),
            modifier = Modifier
                .widthIn(max = 300.dp)
        ) {
            Text(
                text = msg.content,
                modifier = Modifier.padding(14.dp),
                color = if (isUser)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

// -------------------------------------------------------
// TYPING INDICATOR
// -------------------------------------------------------

@Composable
fun TypingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp)
        ) {

            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                repeat(3) { index ->
                    val alpha = remember { Animatable(0.3f) }

                    LaunchedEffect(Unit) {
                        delay(index * 160L)
                        alpha.animateTo(
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600),
                                repeatMode = RepeatMode.Reverse
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .alpha(alpha.value)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant,
                                CircleShape
                            )
                    )
                }
            }
        }
    }
}

// END OF FILE
