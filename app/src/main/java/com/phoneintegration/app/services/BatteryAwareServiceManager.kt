package com.phoneintegration.app.services

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.phoneintegration.app.auth.AuthManager
import com.phoneintegration.app.desktop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Battery-aware service manager that intelligently controls background services
 * based on battery level, network conditions, and app lifecycle.
 *
 * Goals:
 * - Reduce battery drain by 50-70%
 * - Only run services when needed
 * - Respect user preferences
 * - Handle app lifecycle properly
 * - Adapt to device conditions (charging, network, etc.)
 */
class BatteryAwareServiceManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BatteryAwareServiceManager"
        private const val PREF_LOW_BATTERY_THRESHOLD = 20 // %
        private const val PREF_CRITICAL_BATTERY_THRESHOLD = 10 // %
        private const val NETWORK_CHECK_INTERVAL = 5 * 60 * 1000L // 5 minutes
        private const val BATTERY_CHECK_INTERVAL = 2 * 60 * 1000L // 2 minutes

        @Volatile
        private var instance: BatteryAwareServiceManager? = null

        fun getInstance(context: Context): BatteryAwareServiceManager {
            return instance ?: synchronized(this) {
                instance ?: BatteryAwareServiceManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // Service states
    private val _serviceStates = MutableStateFlow<Map<String, ServiceState>>(emptyMap())
    val serviceStates: StateFlow<Map<String, ServiceState>> = _serviceStates.asStateFlow()

    // Device conditions
    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _isOnWifi = MutableStateFlow(false)
    val isOnWifi: StateFlow<Boolean> = _isOnWifi.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(false)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    // Service instances (lazy loaded)
    private var contactsReceiveService: ContactsReceiveService? = null
    private var phoneStatusService: PhoneStatusService? = null
    private var clipboardSyncService: ClipboardSyncService? = null
    private var findMyPhoneService: FindMyPhoneService? = null
    private var linkSharingService: LinkSharingService? = null
    private var fileTransferService: FileTransferService? = null
    private var photoSyncService: PhotoSyncService? = null
    private var hotspotControlService: HotspotControlService? = null
    private var dndSyncService: DNDSyncService? = null
    private var mediaControlService: MediaControlService? = null
    private var scheduledMessageService: ScheduledMessageService? = null
    private var voicemailSyncService: VoicemailSyncService? = null

    // New intelligent sync manager
    private var intelligentSyncManager: IntelligentSyncManager? = null

    // Background monitoring
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var batteryMonitorJob: Job? = null
    private var networkMonitorJob: Job? = null

    // User preferences
    private var userEnabledServices = setOf(
        "intelligent_sync", "contacts", "phone_status", "clipboard", "find_phone",
        "links", "files", "photos", "hotspot", "dnd", "media"
    )

    init {
        setupLifecycleObserver()
        startMonitoring()
        Log.i(TAG, "BatteryAwareServiceManager initialized")
    }

    /**
     * Setup lifecycle observer to manage services based on app state
     */
    private fun setupLifecycleObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _isAppInForeground.value = true
                Log.d(TAG, "App moved to foreground - optimizing services")
                optimizeServicesForForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                _isAppInForeground.value = false
                Log.d(TAG, "App moved to background - reducing services")
                optimizeServicesForBackground()
            }
        })
    }

    /**
     * Start monitoring battery and network conditions
     */
    private fun startMonitoring() {
        startBatteryMonitoring()
        startNetworkMonitoring()
        updateDeviceConditions()
    }

    /**
     * Monitor battery level and charging status
     */
    private fun startBatteryMonitoring() {
        batteryMonitorJob?.cancel()
        batteryMonitorJob = scope.launch {
            while (isActive) {
                updateBatteryStatus()
                delay(BATTERY_CHECK_INTERVAL)
            }
        }
    }

    /**
     * Monitor network connectivity
     */
    private fun startNetworkMonitoring() {
        networkMonitorJob?.cancel()
        networkMonitorJob = scope.launch {
            while (isActive) {
                updateNetworkStatus()
                delay(NETWORK_CHECK_INTERVAL)
            }
        }
    }

    /**
     * Update current battery status
     */
    private fun updateBatteryStatus() {
        try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryIntent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

                if (level >= 0 && scale > 0) {
                    val batteryPct = (level * 100) / scale
                    _batteryLevel.value = batteryPct
                    _isCharging.value = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                       status == BatteryManager.BATTERY_STATUS_FULL
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating battery status", e)
        }
    }

    /**
     * Update network connectivity status
     */
    private fun updateNetworkStatus() {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            _isOnWifi.value = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating network status", e)
        }
    }

    /**
     * Update all device conditions at once
     */
    private fun updateDeviceConditions() {
        updateBatteryStatus()
        updateNetworkStatus()
    }

    /**
     * Optimize services for foreground usage
     */
    private fun optimizeServicesForForeground() {
        val authManager = AuthManager.getInstance(context)

        // Start intelligent sync manager when app is in foreground
        if (authManager.isAuthenticated()) {
            startIntelligentSync()
            startEssentialServices()
        }
    }

    /**
     * Optimize services for background usage (battery saving)
     */
    private fun optimizeServicesForBackground() {
        // Stop non-essential services when in background
        val batteryLevel = _batteryLevel.value
        val isCharging = _isCharging.value

        if (batteryLevel < PREF_LOW_BATTERY_THRESHOLD && !isCharging) {
            Log.i(TAG, "Low battery (${batteryLevel}%) - stopping non-essential services")
            stopNonEssentialServices()
        } else if (batteryLevel < PREF_CRITICAL_BATTERY_THRESHOLD) {
            Log.w(TAG, "Critical battery (${batteryLevel}%) - stopping all services")
            stopAllServices()
        } else {
            // Normal background operation - keep essential services only
            keepEssentialServicesOnly()
        }
    }

    /**
     * Start only essential services
     */
    private fun startEssentialServices() {
        try {
            // Always start intelligent sync (core functionality for seamless experience)
            if (userEnabledServices.contains("intelligent_sync")) {
                startIntelligentSync()
            }

            // Always start contacts receive (core functionality)
            if (userEnabledServices.contains("contacts")) {
                startContactsReceiveService()
            }

            // Start phone status if battery allows
            if (userEnabledServices.contains("phone_status") && shouldStartService("phone_status")) {
                startPhoneStatusService()
            }

            // Start clipboard sync if enabled and conditions good
            if (userEnabledServices.contains("clipboard") && shouldStartService("clipboard")) {
                startClipboardSyncService()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting essential services", e)
        }
    }

    /**
     * Keep only essential services running in background
     */
    private fun keepEssentialServicesOnly() {
        // Stop services that are not essential for background operation
        stopService("find_phone")
        stopService("links")
        stopService("files")
        stopService("photos")
        stopService("hotspot")
        stopService("media")

        // Keep essential ones: intelligent_sync, contacts, phone_status, clipboard
        // The intelligent sync manager will handle adaptive frequency internally
    }

    /**
     * Stop non-essential services
     */
    private fun stopNonEssentialServices() {
        val nonEssential = listOf("photos", "hotspot", "media", "find_phone", "files")
        nonEssential.forEach { stopService(it) }
    }

    /**
     * Stop all background services
     */
    private fun stopAllServices() {
        val allServices = listOf(
            "intelligent_sync", "contacts", "phone_status", "clipboard", "find_phone", "links",
            "files", "photos", "hotspot", "dnd", "media", "scheduled", "voicemail"
        )
        allServices.forEach { stopService(it) }
    }

    /**
     * Determine if a service should start based on conditions
     */
    private fun shouldStartService(serviceName: String): Boolean {
        val batteryLevel = _batteryLevel.value
        val isCharging = _isCharging.value
        val isOnWifi = _isOnWifi.value

        return when {
            // Critical battery - only intelligent sync and contacts
            batteryLevel < PREF_CRITICAL_BATTERY_THRESHOLD -> serviceName in listOf("intelligent_sync", "contacts")

            // Low battery - prefer WiFi and charging for non-essential services
            batteryLevel < PREF_LOW_BATTERY_THRESHOLD -> {
                when (serviceName) {
                    "intelligent_sync", "contacts", "phone_status" -> true
                    else -> isCharging || isOnWifi
                }
            }

            // Normal battery - allow more services
            else -> true
        }
    }

    // Individual service management methods

    private fun startIntelligentSync() {
        if (intelligentSyncManager == null) {
            intelligentSyncManager = IntelligentSyncManager.getInstance(context)
        }
        updateServiceState("intelligent_sync", ServiceState.RUNNING)
    }

    private fun startContactsReceiveService() {
        if (contactsReceiveService == null) {
            contactsReceiveService = ContactsReceiveService(context)
        }
        contactsReceiveService?.startListening()
        updateServiceState("contacts", ServiceState.RUNNING)
    }

    private fun startPhoneStatusService() {
        if (phoneStatusService == null) {
            phoneStatusService = PhoneStatusService(context)
        }
        phoneStatusService?.startMonitoring()
        updateServiceState("phone_status", ServiceState.RUNNING)
    }

    private fun startClipboardSyncService() {
        if (clipboardSyncService == null) {
            clipboardSyncService = ClipboardSyncService(context)
        }
        clipboardSyncService?.startSync()
        updateServiceState("clipboard", ServiceState.RUNNING)
    }

    private fun stopService(serviceName: String) {
        when (serviceName) {
            "intelligent_sync" -> {
                intelligentSyncManager?.cleanup()
                intelligentSyncManager = null
            }
            "contacts" -> contactsReceiveService?.stopListening()
            "phone_status" -> phoneStatusService?.stopMonitoring()
            "clipboard" -> clipboardSyncService?.stopSync()
            "find_phone" -> findMyPhoneService?.stopListening()
            "links" -> linkSharingService?.stopListening()
            "files" -> fileTransferService?.stopListening()
            "photos" -> photoSyncService?.stopSync()
            "hotspot" -> hotspotControlService?.stopListening()
            "dnd" -> dndSyncService?.stopSync()
            "media" -> mediaControlService?.stopListening()
            "scheduled" -> scheduledMessageService?.stopListening()
            "voicemail" -> voicemailSyncService?.stopSync()
        }
        updateServiceState(serviceName, ServiceState.STOPPED)
    }

    private fun updateServiceState(serviceName: String, state: ServiceState) {
        val currentStates = _serviceStates.value.toMutableMap()
        currentStates[serviceName] = state
        _serviceStates.value = currentStates
    }

    /**
     * Get battery optimization recommendations
     */
    fun getOptimizationRecommendations(): List<String> {
        val recommendations = mutableListOf<String>()
        val batteryLevel = _batteryLevel.value
        val isCharging = _isCharging.value
        val isOnWifi = _isOnWifi.value

        if (batteryLevel < PREF_LOW_BATTERY_THRESHOLD && !isCharging) {
            recommendations.add("Low battery detected - consider disabling photo sync and file transfer")
        }

        if (!isOnWifi) {
            recommendations.add("Not on WiFi - data usage may be high")
        }

        if (_serviceStates.value.count { it.value == ServiceState.RUNNING } > 5) {
            recommendations.add("Many services running - consider disabling unused features")
        }

        return recommendations
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        batteryMonitorJob?.cancel()
        networkMonitorJob?.cancel()
        intelligentSyncManager?.cleanup()
        stopAllServices()
        scope.cancel()
        Log.i(TAG, "BatteryAwareServiceManager cleaned up")
    }
}

/**
 * Service state enumeration
 */
enum class ServiceState {
    RUNNING,
    STOPPED,
    ERROR
}