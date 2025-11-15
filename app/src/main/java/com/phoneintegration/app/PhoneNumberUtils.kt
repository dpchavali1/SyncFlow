package com.phoneintegration.app

import java.util.Locale

object PhoneNumberUtils {

    /**
     * Normalizes a phone number by removing all non-digit characters
     * and handling country codes
     */
    fun normalizePhoneNumber(phoneNumber: String): String {
        // Remove all non-digit characters
        val digitsOnly = phoneNumber.replace(Regex("[^0-9]"), "")

        // Handle US country code (+1)
        // If number starts with 1 and is 11 digits, remove the 1
        return if (digitsOnly.length == 11 && digitsOnly.startsWith("1")) {
            digitsOnly.substring(1) // Remove leading 1
        } else if (digitsOnly.length == 10) {
            digitsOnly // Already 10 digits
        } else {
            digitsOnly // Keep as is for other formats
        }
    }

    /**
     * Alias for normalizePhoneNumber (for compatibility)
     * This fixes the crash where code was calling normalizeNumber instead of normalizePhoneNumber
     */
    fun normalizeNumber(phoneNumber: String): String {
        return normalizePhoneNumber(phoneNumber)
    }

    /**
     * Checks if two phone numbers are the same
     */
    fun areNumbersEqual(number1: String, number2: String): Boolean {
        return normalizePhoneNumber(number1) == normalizePhoneNumber(number2)
    }

    /**
     * Formats a phone number for display
     * Keeps the original format but ensures consistency
     */
    fun formatForDisplay(phoneNumber: String): String {
        val normalized = normalizePhoneNumber(phoneNumber)

        // Format US numbers as (XXX) XXX-XXXX
        return if (normalized.length == 10) {
            "(${normalized.substring(0, 3)}) ${normalized.substring(3, 6)}-${normalized.substring(6)}"
        } else {
            phoneNumber // Keep original format for non-US numbers
        }
    }

    /**
     * Formats a phone number with country code support
     * This matches the signature of Android's PhoneNumberUtils.formatNumber
     */
    fun formatNumber(phoneNumber: String, defaultCountry: String): String? {
        return try {
            // Use Android's built-in formatter
            android.telephony.PhoneNumberUtils.formatNumber(phoneNumber, defaultCountry)
        } catch (e: Exception) {
            // Fallback to our custom formatter
            formatForDisplay(phoneNumber)
        }
    }

    /**
     * Overload without country parameter
     */
    fun formatNumber(phoneNumber: String): String {
        return formatNumber(phoneNumber, Locale.getDefault().country) ?: phoneNumber
    }
}