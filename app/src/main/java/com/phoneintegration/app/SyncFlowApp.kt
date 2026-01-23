package com.phoneintegration.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.phoneintegration.app.auth.AuthManager
import com.phoneintegration.app.deals.notify.DealNotificationScheduler
import com.phoneintegration.app.network.FirebaseSecurityConfig
import com.phoneintegration.app.security.SecurityMonitor
import com.phoneintegration.app.utils.ErrorHandler
import com.phoneintegration.app.utils.InputValidation
import net.sqlcipher.database.SQLiteDatabase
import java.io.File

class SyncFlowApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        try {
            // Initialize global error handler first
            ErrorHandler.init(this)

            // Initialize Firebase with certificate pinning for security
            try {
                FirebaseSecurityConfig.initializeFirebaseWithCertificatePinning(this)
                android.util.Log.i("SyncFlowApp", "Firebase security config initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize Firebase security config", e)
                // Continue without security features if Firebase fails
            }

            // Initialize authentication manager for secure session management
            try {
                AuthManager.getInstance(this)
                android.util.Log.i("SyncFlowApp", "AuthManager initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize AuthManager", e)
                // Continue without auth manager if it fails
            }

            // Initialize security monitoring
            try {
                val securityMonitor = SecurityMonitor.getInstance(this)
                setupSecurityAlertHandlers(securityMonitor)
                android.util.Log.i("SyncFlowApp", "SecurityMonitor initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize SecurityMonitor", e)
                // Continue without security monitoring if it fails
            }

            // Initialize input validation security monitoring
            try {
                InputValidation.initializeSecurityMonitoring(this)
                android.util.Log.i("SyncFlowApp", "InputValidation security monitoring initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize InputValidation security monitoring", e)
                // Continue without security monitoring if it fails
            }

            // Load SQLCipher native library
            try {
                SQLiteDatabase.loadLibs(this)
                android.util.Log.i("SyncFlowApp", "SQLCipher loaded successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to load SQLCipher", e)
                // This might be critical, but let's continue
            }

            // Schedule daily notification windows
            try {
                DealNotificationScheduler.scheduleDailyWork(this)
                android.util.Log.i("SyncFlowApp", "DealNotificationScheduler initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize DealNotificationScheduler", e)
                // Continue without notification scheduling if it fails
            }

            // Initialize intelligent sync manager for seamless cross-platform messaging
            try {
                com.phoneintegration.app.services.IntelligentSyncManager.getInstance(this)
                android.util.Log.i("SyncFlowApp", "IntelligentSyncManager initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize IntelligentSyncManager", e)
                // Continue without intelligent sync if it fails
            }

            // Initialize unified identity manager for single user across all devices
            try {
                com.phoneintegration.app.auth.UnifiedIdentityManager.getInstance(this)
                android.util.Log.i("SyncFlowApp", "UnifiedIdentityManager initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize UnifiedIdentityManager", e)
                // Continue without unified identity if it fails
            }

            // Initialize data cleanup service to manage Firebase storage costs
            try {
                com.phoneintegration.app.services.DataCleanupService.getInstance(this)
                android.util.Log.i("SyncFlowApp", "DataCleanupService initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("SyncFlowApp", "Failed to initialize DataCleanupService", e)
                // Continue without cleanup service if it fails
            }

        } catch (e: Exception) {
            android.util.Log.e("SyncFlowApp", "Critical error during app initialization", e)
            // If we get here, something went very wrong
            throw e
        }

        // Note: CallMonitorService is now started from MainActivity
        // to avoid ForegroundServiceStartNotAllowedException on Android 14+
    }

    /**
     * Setup security alert handlers for critical security events
     */
    private fun setupSecurityAlertHandlers(securityMonitor: SecurityMonitor) {
        securityMonitor.addAlertHandler { alert ->
            // Log all alerts
            android.util.Log.w("SecurityAlert", "${alert.severity}: ${alert.message}")

            // Handle critical alerts (could send notifications, etc.)
            when (alert.severity) {
                com.phoneintegration.app.security.AlertSeverity.CRITICAL -> {
                    // Critical alerts could trigger immediate actions
                    android.util.Log.e("SecurityAlert", "CRITICAL SECURITY ALERT: ${alert.message}")
                    // TODO: Could send notification to user or security team
                }
                com.phoneintegration.app.security.AlertSeverity.HIGH -> {
                    android.util.Log.w("SecurityAlert", "HIGH SECURITY ALERT: ${alert.message}")
                }
                else -> {
                    android.util.Log.i("SecurityAlert", "Security alert: ${alert.message}")
                }
            }
        }
    }

    /**
     * Configure Coil ImageLoader with optimized caching for contact photos
     * - Memory cache: 25% of available memory
     * - Disk cache: 100MB for contact photos and images
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // Memory cache configuration
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // Use 25% of app's available memory
                    .strongReferencesEnabled(true)
                    .build()
            }
            // Disk cache configuration
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100MB disk cache
                    .build()
            }
            // Cache policies
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            // Crossfade for smoother loading
            .crossfade(true)
            .crossfade(200)
            // Respect cache headers from network
            .respectCacheHeaders(true)
            .build()
    }
}
