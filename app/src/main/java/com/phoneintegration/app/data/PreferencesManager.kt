package com.phoneintegration.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("syncflow_prefs", Context.MODE_PRIVATE)

    // Theme Settings
    var isDarkMode = mutableStateOf(prefs.getBoolean("dark_mode", false))
        private set
    
    var isAutoTheme = mutableStateOf(prefs.getBoolean("auto_theme", true))
        private set

    // Notification Settings
    var notificationsEnabled = mutableStateOf(prefs.getBoolean("notifications_enabled", true))
        private set
    
    var notificationSound = mutableStateOf(prefs.getBoolean("notification_sound", true))
        private set
    
    var notificationVibrate = mutableStateOf(prefs.getBoolean("notification_vibrate", true))
        private set
    
    var notificationPreview = mutableStateOf(prefs.getBoolean("notification_preview", true))
        private set

    // Message Settings
    var sendOnEnter = mutableStateOf(prefs.getBoolean("send_on_enter", false))
        private set
    
    var showTimestamps = mutableStateOf(prefs.getBoolean("show_timestamps", true))
        private set
    
    var groupMessagesByDate = mutableStateOf(prefs.getBoolean("group_by_date", true))
        private set
    
    var autoDeleteOld = mutableStateOf(prefs.getBoolean("auto_delete_old", false))
        private set
    
    var deleteAfterDays = mutableStateOf(prefs.getInt("delete_after_days", 90))
        private set

    // Privacy Settings
    var requireFingerprint = mutableStateOf(prefs.getBoolean("require_fingerprint", false))
        private set
    
    var hideMessagePreview = mutableStateOf(prefs.getBoolean("hide_message_preview", false))
        private set
    
    var incognitoMode = mutableStateOf(prefs.getBoolean("incognito_mode", false))
        private set

    // Appearance Settings
    var bubbleStyle = mutableStateOf(prefs.getString("bubble_style", "rounded") ?: "rounded")
        private set
    
    var fontSize = mutableStateOf(prefs.getInt("font_size", 14))
        private set
    
    var chatWallpaper = mutableStateOf(prefs.getString("chat_wallpaper", "default") ?: "default")
        private set

    // Signature
    var messageSignature = mutableStateOf(prefs.getString("message_signature", "") ?: "")
        private set
    
    var addSignature = mutableStateOf(prefs.getBoolean("add_signature", false))
        private set

    // Quick Reply Templates
    fun getQuickReplyTemplates(): List<String> {
        val templatesJson = prefs.getString("quick_reply_templates", "[]") ?: "[]"
        return try {
            templatesJson.split("|").filter { it.isNotEmpty() }
        } catch (e: Exception) {
            listOf()
        }
    }

    fun saveQuickReplyTemplates(templates: List<String>) {
        prefs.edit().putString("quick_reply_templates", templates.joinToString("|")).apply()
    }

    // Update methods
    fun setDarkMode(enabled: Boolean) {
        isDarkMode.value = enabled
        prefs.edit().putBoolean("dark_mode", enabled).apply()
    }

    fun setAutoTheme(enabled: Boolean) {
        isAutoTheme.value = enabled
        prefs.edit().putBoolean("auto_theme", enabled).apply()
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        notificationsEnabled.value = enabled
        prefs.edit().putBoolean("notifications_enabled", enabled).apply()
    }

    fun setNotificationSound(enabled: Boolean) {
        notificationSound.value = enabled
        prefs.edit().putBoolean("notification_sound", enabled).apply()
    }

    fun setNotificationVibrate(enabled: Boolean) {
        notificationVibrate.value = enabled
        prefs.edit().putBoolean("notification_vibrate", enabled).apply()
    }

    fun setNotificationPreview(enabled: Boolean) {
        notificationPreview.value = enabled
        prefs.edit().putBoolean("notification_preview", enabled).apply()
    }

    fun setSendOnEnter(enabled: Boolean) {
        sendOnEnter.value = enabled
        prefs.edit().putBoolean("send_on_enter", enabled).apply()
    }

    fun setShowTimestamps(enabled: Boolean) {
        showTimestamps.value = enabled
        prefs.edit().putBoolean("show_timestamps", enabled).apply()
    }

    fun setGroupByDate(enabled: Boolean) {
        groupMessagesByDate.value = enabled
        prefs.edit().putBoolean("group_by_date", enabled).apply()
    }

    fun setAutoDeleteOld(enabled: Boolean) {
        autoDeleteOld.value = enabled
        prefs.edit().putBoolean("auto_delete_old", enabled).apply()
    }

    fun setDeleteAfterDays(days: Int) {
        deleteAfterDays.value = days
        prefs.edit().putInt("delete_after_days", days).apply()
    }

    fun setRequireFingerprint(enabled: Boolean) {
        requireFingerprint.value = enabled
        prefs.edit().putBoolean("require_fingerprint", enabled).apply()
    }

    fun setHideMessagePreview(enabled: Boolean) {
        hideMessagePreview.value = enabled
        prefs.edit().putBoolean("hide_message_preview", enabled).apply()
    }

    fun setIncognitoMode(enabled: Boolean) {
        incognitoMode.value = enabled
        prefs.edit().putBoolean("incognito_mode", enabled).apply()
    }

    fun setBubbleStyle(style: String) {
        bubbleStyle.value = style
        prefs.edit().putString("bubble_style", style).apply()
    }

    fun setFontSize(size: Int) {
        fontSize.value = size
        prefs.edit().putInt("font_size", size).apply()
    }

    fun setChatWallpaper(wallpaper: String) {
        chatWallpaper.value = wallpaper
        prefs.edit().putString("chat_wallpaper", wallpaper).apply()
    }

    fun setMessageSignature(signature: String) {
        messageSignature.value = signature
        prefs.edit().putString("message_signature", signature).apply()
    }

    fun setAddSignature(enabled: Boolean) {
        addSignature.value = enabled
        prefs.edit().putBoolean("add_signature", enabled).apply()
    }
}
