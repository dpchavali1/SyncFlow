package com.phoneintegration.app

data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int, // 1 = received, 2 = sent
    var contactName: String? = null,
    var category: MessageCategory? = null,
    var otpInfo: OtpInfo? = null
) {
    fun getFormattedTime(): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, hh:mm a", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(date))
    }

    fun getDisplayName(): String {
        return contactName ?: address
    }
}