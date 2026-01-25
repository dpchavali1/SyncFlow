package com.phoneintegration.app.utils

import android.content.Context
import com.phoneintegration.app.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spam filter utility for detecting and managing spam messages.
 * Uses pattern matching and heuristics to identify spam.
 */
object SpamFilter {

    // Common spam keywords and patterns
    private val SPAM_KEYWORDS = listOf(
        // Lottery/Prize scams
        "congratulations you have won",
        "you have been selected",
        "claim your prize",
        "lottery winner",
        "you won",
        "winner selected",

        // Financial scams
        "urgent loan",
        "instant loan",
        "loan approved",
        "credit card offer",
        "debt relief",
        "make money fast",
        "earn from home",
        "investment opportunity",
        "crypto profit",
        "bitcoin profit",
        "forex trading",

        // Phishing
        "verify your account",
        "account suspended",
        "click here to verify",
        "update your details",
        "confirm your identity",
        "unusual activity",
        "security alert",

        // Adult content
        "adult content",
        "dating site",
        "singles in your area",
        "meet singles",
        "hot singles",

        // Fake delivery
        "delivery failed",
        "package waiting",
        "customs fee required",
        "shipping fee",

        // General spam
        "act now",
        "limited time offer",
        "exclusive deal",
        "free gift",
        "risk free",
        "no obligation",
        "call now",
        "text back",
        "reply stop to",
        "unsubscribe"
    )

    // Suspicious URL patterns
    private val SUSPICIOUS_URL_PATTERNS = listOf(
        Regex("""bit\.ly/\w+""", RegexOption.IGNORE_CASE),
        Regex("""tinyurl\.com/\w+""", RegexOption.IGNORE_CASE),
        Regex("""goo\.gl/\w+""", RegexOption.IGNORE_CASE),
        Regex("""t\.co/\w+""", RegexOption.IGNORE_CASE),
        Regex("""rb\.gy/\w+""", RegexOption.IGNORE_CASE),
        Regex("""is\.gd/\w+""", RegexOption.IGNORE_CASE),
        Regex("""cutt\.ly/\w+""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.xyz/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.top/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.click/""", RegexOption.IGNORE_CASE),
        Regex("""\w+\.link/""", RegexOption.IGNORE_CASE)
    )

    // Common spam sender patterns
    private val SPAM_SENDER_PATTERNS = listOf(
        Regex("""^[A-Z]{2}-\w+"""),  // Shortcodes like "AD-SPAM"
        Regex("""^\d{5,6}$"""),       // 5-6 digit shortcodes
        Regex("""^[A-Z]{6,}$""")      // All caps sender names
    )

    data class SpamCheckResult(
        val isSpam: Boolean,
        val confidence: Float,  // 0.0 to 1.0
        val reasons: List<String>
    )

    /**
     * Check if a message is likely spam.
     * Returns a SpamCheckResult with confidence level and reasons.
     * @param threshold The confidence threshold for spam classification (default 0.5)
     */
    fun checkMessage(
        body: String,
        senderAddress: String,
        isFromContact: Boolean = false,
        threshold: Float = 0.5f
    ): SpamCheckResult {
        val reasons = mutableListOf<String>()
        var score = 0f

        // Messages from saved contacts are less likely to be spam
        if (isFromContact) {
            score -= 0.3f
        }

        val lowerBody = body.lowercase()

        // Check for spam keywords
        val matchedKeywords = SPAM_KEYWORDS.filter { keyword ->
            lowerBody.contains(keyword)
        }
        if (matchedKeywords.isNotEmpty()) {
            score += 0.2f * matchedKeywords.size.coerceAtMost(3)
            reasons.add("Contains spam keywords: ${matchedKeywords.take(3).joinToString(", ")}")
        }

        // Check for suspicious URLs
        val hasShortUrl = SUSPICIOUS_URL_PATTERNS.any { it.containsMatchIn(body) }
        if (hasShortUrl) {
            score += 0.3f
            reasons.add("Contains shortened/suspicious URL")
        }

        // Check sender patterns
        val isSuspiciousSender = SPAM_SENDER_PATTERNS.any { it.matches(senderAddress) }
        if (isSuspiciousSender) {
            score += 0.2f
            reasons.add("Suspicious sender format")
        }

        // Check for excessive caps
        val capsRatio = body.count { it.isUpperCase() }.toFloat() / body.length.coerceAtLeast(1)
        if (capsRatio > 0.5f && body.length > 20) {
            score += 0.15f
            reasons.add("Excessive capital letters")
        }

        // Check for excessive special characters
        val specialCharRatio = body.count { !it.isLetterOrDigit() && !it.isWhitespace() }.toFloat() / body.length.coerceAtLeast(1)
        if (specialCharRatio > 0.2f) {
            score += 0.1f
            reasons.add("Excessive special characters")
        }

        // Check for phone numbers in body (common in spam)
        val hasPhoneInBody = Regex("""(\+\d{10,}|\d{10,})""").containsMatchIn(body)
        if (hasPhoneInBody && !isFromContact) {
            score += 0.1f
            reasons.add("Contains phone number in message")
        }

        // Check message length (very short or very long)
        if (body.length < 10 && hasShortUrl) {
            score += 0.2f
            reasons.add("Very short message with link")
        }

        // Normalize score to 0-1 range
        val confidence = score.coerceIn(0f, 1f)
        val isSpam = confidence >= threshold

        return SpamCheckResult(
            isSpam = isSpam,
            confidence = confidence,
            reasons = reasons
        )
    }

    /**
     * Check if a message is spam and should be filtered.
     * Simple boolean check for quick filtering.
     */
    fun isSpam(body: String, senderAddress: String, isFromContact: Boolean = false): Boolean {
        return checkMessage(body, senderAddress, isFromContact).isSpam
    }

    /**
     * Get spam classification label for UI
     */
    fun getSpamLabel(confidence: Float): String {
        return when {
            confidence >= 0.8f -> "High confidence spam"
            confidence >= 0.6f -> "Likely spam"
            confidence >= 0.5f -> "Possible spam"
            else -> "Not spam"
        }
    }
}
