package com.phoneintegration.app

import java.util.regex.Pattern

enum class MessageCategory(val displayName: String, val emoji: String, val color: Long) {
    OTP("OTP/Codes", "🔑", 0xFF5DADE2),
    TRANSACTION("Transactions", "💰", 0xFF3498DB),
    PERSONAL("Personal", "👥", 0xFF2874A6),
    PROMOTION("Promotions", "📢", 0xFF5DADE2),
    ALERT("Alerts", "⚠️", 0xFFF44336),
    GENERAL("General", "💬", 0xFF9E9E9E)
}

data class OtpInfo(
    val code: String,
    val fullText: String
)

object MessageCategorizer {

    // OTP Detection Patterns
    private val otpPatterns = listOf(
        // 4-8 digit codes
        Pattern.compile("""\b(\d{4,8})\b"""),
        // OTP: 123456 format
        Pattern.compile("""(?i)otp[:\s]*([0-9]{4,8})"""),
        // Code: 123456 format
        Pattern.compile("""(?i)code[:\s]*([0-9]{4,8})"""),
        // Verification code patterns
        Pattern.compile("""(?i)verification\s+code[:\s]*([0-9]{4,8})"""),
        // PIN patterns
        Pattern.compile("""(?i)pin[:\s]*([0-9]{4,6})"""),
        // Pattern with special format: 123-456
        Pattern.compile("""\b([0-9]{3}[-\s]?[0-9]{3,4})\b""")
    )

    // Transaction Keywords
    private val transactionKeywords = listOf(
        "bank", "account", "balance", "credited", "debited", "payment", "paid",
        "transaction", "amount", "rs", "inr", "usd", "debit", "credit",
        "withdrawn", "deposit", "transfer", "atm", "purchase", "refund",
        "invoice", "receipt", "bill", "charged", "card"
    )

    // Promotion Keywords
    private val promotionKeywords = listOf(
        "discount", "sale", "coupon", "promo", "cashback", "reward", "win", "prize", "limited time",
        "exclusive", "shop now", "buy now", "order now", "% off", "percent off", "click here", "unsubscribe"
    )

    // Alert Keywords
    private val alertKeywords = listOf(
        "alert", "urgent", "important", "warning", "action required",
        "expires", "expiring", "deadline", "due", "overdue", "suspended",
        "blocked", "verify", "confirm", "update required", "security"
    )

    // OTP-related keywords
    private val otpKeywords = listOf(
        "otp", "verification", "authenticate", "verify", "code", "pin",
        "one-time", "password", "security code", "access code", "login code"
    )

    /**
     * Categorize a message
     */
    fun categorizeMessage(message: SmsMessage): MessageCategory {
        val body = message.body.lowercase()

        val category = when {
            containsOtp(body) -> MessageCategory.OTP
            containsKeywords(body, transactionKeywords) -> MessageCategory.TRANSACTION
            containsKeywords(body, alertKeywords) -> MessageCategory.ALERT
            containsKeywords(body, promotionKeywords) && (message.contactName == null || isAutomatedSender(message.address)) -> MessageCategory.PROMOTION
            message.contactName != null && !isAutomatedSender(message.address) -> MessageCategory.PERSONAL
            else -> MessageCategory.GENERAL
        }

        android.util.Log.d(
            "MessageCategorizer",
            "Categorized as: $category for message: ${message.body.take(50)}"
        )
        return category
    }

    /**
     * Detect if message contains OTP
     */
    fun containsOtp(text: String): Boolean {
        val lowerText = text.lowercase()

        // Check for OTP keywords
        if (otpKeywords.any { lowerText.contains(it) }) {
            // And verify it has a numeric code
            return otpPatterns.any { it.matcher(text).find() }
        }

        return false
    }

    /**
     * Extract OTP code from message
     */
    fun extractOtp(message: String): OtpInfo? {
        for (pattern in otpPatterns) {
            val matcher = pattern.matcher(message)
            if (matcher.find()) {
                val code = matcher.group(1) ?: matcher.group(0)
                if (code.replace(Regex("""[^0-9]"""), "").length in 4..8) {
                    return OtpInfo(
                        code = code.replace(Regex("""[^0-9]"""), ""),
                        fullText = message
                    )
                }
            }
        }
        return null
    }

    /**
     * Check if contains any keywords from list
     */
    private fun containsKeywords(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }

    /**
     * Check if sender looks automated (short codes, alphanumeric)
     */
    private fun isAutomatedSender(address: String): Boolean {
        // Short codes (5-6 digits)
        if (address.matches(Regex("""^\d{5,6}$"""))) {
            return true
        }

        // Alphanumeric senders (e.g., "VM-BANK", "ALERTS")
        if (address.matches(Regex("""^[A-Z]{2,6}-[A-Z0-9]+$"""))) {
            return true
        }

        // Contains letters (but not full phone number)
        if (address.any { it.isLetter() }) {
            return true
        }

        return false
    }

    /**
     * Get category distribution for stats
     */
    fun getCategoryStats(messages: List<SmsMessage>): Map<MessageCategory, Int> {
        return messages
            .groupBy { categorizeMessage(it) }
            .mapValues { it.value.size }
    }
}