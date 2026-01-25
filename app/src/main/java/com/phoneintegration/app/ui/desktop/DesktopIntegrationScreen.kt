package com.phoneintegration.app.ui.desktop

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.phoneintegration.app.auth.UnifiedIdentityManager
import com.phoneintegration.app.desktop.DesktopSyncService
import com.phoneintegration.app.desktop.CompletePairingResult
import com.phoneintegration.app.desktop.PairedDevice
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.desktop.NotificationMirrorService
import com.phoneintegration.app.desktop.PhotoSyncService
import com.phoneintegration.app.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import androidx.compose.material3.Switch

/**
 * Global cache for paired devices - survives screen navigation
 */
private object PairedDevicesCache {
    private val _devices = mutableStateListOf<PairedDevice>()
    val devices: List<PairedDevice> get() = _devices.toList()

    var isLoading by mutableStateOf(false)
    var lastError by mutableStateOf<String?>(null)
    var deviceCount by mutableStateOf(0)
    var deviceLimit by mutableStateOf(3)
    var userPlan by mutableStateOf("free")
    var canAddDevice by mutableStateOf(true)

    private var lastLoadTime = 0L
    private const val CACHE_VALID_MS = 30_000L // 30 seconds

    fun isCacheValid(): Boolean {
        return _devices.isNotEmpty() && (System.currentTimeMillis() - lastLoadTime) < CACHE_VALID_MS
    }

    fun updateDevices(newDevices: List<PairedDevice>) {
        android.util.Log.d("PairedDevicesCache", "Updating cache with ${newDevices.size} devices")
        _devices.clear()
        _devices.addAll(newDevices)
        deviceCount = newDevices.size
        lastLoadTime = System.currentTimeMillis()
        lastError = null
    }

    fun setError(error: String) {
        lastError = error
    }

    fun clear() {
        _devices.clear()
        lastLoadTime = 0L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopIntegrationScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    // Services
    val unifiedIdentityManager = remember { UnifiedIdentityManager.getInstance(appContext) }
    val desktopSyncService = remember { DesktopSyncService(appContext) }
    val preferencesManager = remember { PreferencesManager(appContext) }

    // Use the global cache for paired devices (survives navigation)
    val pairedDevices = PairedDevicesCache.devices
    val isLoadingDevices = PairedDevicesCache.isLoading
    val deviceCount = PairedDevicesCache.deviceCount
    val deviceLimit = PairedDevicesCache.deviceLimit
    val userPlan = PairedDevicesCache.userPlan
    val canAddDevice = PairedDevicesCache.canAddDevice

    // Local UI state
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var showManualInput by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }
    var isScanButtonBusy by remember { mutableStateOf(false) }

    // Recovery code state - check before first pairing
    val recoveryManager = remember { com.phoneintegration.app.auth.RecoveryCodeManager.getInstance(appContext) }
    var showRecoverySetupDialog by remember { mutableStateOf(false) }
    var generatedRecoveryCode by remember { mutableStateOf<String?>(null) }
    var isGeneratingCode by remember { mutableStateOf(false) }
    var pendingPairingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    // Function to check if recovery is needed before pairing
    fun checkRecoveryBeforePairing(onProceed: () -> Unit) {
        // If user already has a recovery code or has paired devices, proceed directly
        if (recoveryManager.isSetupComplete() || pairedDevices.isNotEmpty()) {
            onProceed()
        } else {
            // First time pairing - show recovery code setup
            pendingPairingAction = onProceed
            showRecoverySetupDialog = true
        }
    }

    // Sync settings state
    var isBackgroundSyncEnabled by remember { mutableStateOf(preferencesManager.backgroundSyncEnabled.value) }
    var hasNotificationPermission by remember { mutableStateOf(NotificationMirrorService.isEnabled(appContext)) }
    var isNotificationMirrorEnabled by remember { mutableStateOf(preferencesManager.notificationMirrorEnabled.value) }

    fun refreshSettings() {
        isBackgroundSyncEnabled = preferencesManager.backgroundSyncEnabled.value
        hasNotificationPermission = NotificationMirrorService.isEnabled(appContext)
        isNotificationMirrorEnabled = preferencesManager.notificationMirrorEnabled.value
    }

    // Simple, reliable device loading function
    fun loadDevices(forceRefresh: Boolean = false) {
        // Use cached data if valid and not forcing refresh
        if (!forceRefresh && PairedDevicesCache.isCacheValid()) {
            android.util.Log.d("DesktopIntegrationScreen", "Using cached devices: ${PairedDevicesCache.devices.size}")
            return
        }

        // If forceRefresh, wait for current load to finish then load again
        if (PairedDevicesCache.isLoading) {
            if (forceRefresh) {
                android.util.Log.d("DesktopIntegrationScreen", "Force refresh requested, will retry after delay")
                // Schedule a retry after a short delay
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                    kotlinx.coroutines.delay(2000) // Wait 2 seconds for current load to finish
                    if (!PairedDevicesCache.isLoading) {
                        loadDevices(forceRefresh = true)
                    }
                }
            } else {
                android.util.Log.d("DesktopIntegrationScreen", "Already loading, skipping")
            }
            return
        }

        android.util.Log.d("DesktopIntegrationScreen", "Loading devices (forceRefresh=$forceRefresh)")
        PairedDevicesCache.isLoading = true

        // Use GlobalScope to ensure this completes even if screen is disposed
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                // Load device info (includes total device count and desktop devices list)
                val deviceInfo = desktopSyncService.getDeviceInfo()
                if (deviceInfo != null) {
                    android.util.Log.d("DesktopIntegrationScreen", "Device info: count=${deviceInfo.deviceCount}, limit=${deviceInfo.deviceLimit}, canAdd=${deviceInfo.canAddDevice}")

                    // Get the desktop-only devices from deviceInfo (for display)
                    val desktopDevices = deviceInfo.devices
                    android.util.Log.d("DesktopIntegrationScreen", "Desktop devices: ${desktopDevices.size}")
                    desktopDevices.forEach { d ->
                        android.util.Log.d("DesktopIntegrationScreen", "  Device: ${d.name} (${d.platform})")
                    }

                    // Update cache with Cloud Function values
                    // Use deviceCount from Cloud Function (total count including Android)
                    // This ensures the count matches what the limit check uses
                    withContext(Dispatchers.Main) {
                        PairedDevicesCache.updateDevices(desktopDevices)
                        // Override deviceCount with the total from Cloud Function
                        PairedDevicesCache.deviceCount = deviceInfo.deviceCount
                        PairedDevicesCache.deviceLimit = deviceInfo.deviceLimit
                        PairedDevicesCache.userPlan = deviceInfo.plan
                        PairedDevicesCache.canAddDevice = deviceInfo.canAddDevice
                        android.util.Log.d("DesktopIntegrationScreen", "Cache updated: ${desktopDevices.size} desktop devices, total count=${deviceInfo.deviceCount}/${deviceInfo.deviceLimit}")
                    }
                } else {
                    android.util.Log.w("DesktopIntegrationScreen", "getDeviceInfo returned null, falling back to getPairedDevices")
                    // Fallback to direct device fetch
                    val devices = desktopSyncService.getPairedDevices()
                    withContext(Dispatchers.Main) {
                        PairedDevicesCache.updateDevices(devices)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DesktopIntegrationScreen", "Error loading devices", e)
                withContext(Dispatchers.Main) {
                    PairedDevicesCache.setError(e.message ?: "Failed to load devices")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    PairedDevicesCache.isLoading = false
                }
            }
        }
    }

    // Sync initial messages after successful pairing
    suspend fun syncInitialMessages() {
        try {
            android.util.Log.d("DesktopIntegrationScreen", "Starting initial message sync")
            val smsRepository = SmsRepository(appContext)
            val messages = smsRepository.getAllRecentMessages(limit = 2000)
            android.util.Log.d("DesktopIntegrationScreen", "Retrieved ${messages.size} messages for sync")

            if (messages.isNotEmpty()) {
                desktopSyncService.syncMessages(messages)
                android.util.Log.d("DesktopIntegrationScreen", "Initial message sync completed: ${messages.size} messages")
            }
        } catch (e: Exception) {
            android.util.Log.e("DesktopIntegrationScreen", "Error during initial message sync", e)
        }
    }

    // Sync contacts after successful pairing
    suspend fun syncInitialContacts() {
        try {
            android.util.Log.d("DesktopIntegrationScreen", "Starting initial contact sync")

            // Use auth.currentUser?.uid directly (same as working test)
            val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            if (userId == null) {
                android.util.Log.e("DesktopIntegrationScreen", "Cannot sync contacts - no authenticated user")
                return
            }
            android.util.Log.d("DesktopIntegrationScreen", "Syncing contacts for userId: $userId")

            val contactsSyncService = com.phoneintegration.app.desktop.ContactsSyncService(appContext)
            contactsSyncService.syncContactsForUser(userId)
            android.util.Log.d("DesktopIntegrationScreen", "Initial contact sync completed")
        } catch (e: Exception) {
            android.util.Log.e("DesktopIntegrationScreen", "Error during initial contact sync", e)
        }
    }

    // Sync call history after successful pairing
    suspend fun syncInitialCallHistory() {
        try {
            android.util.Log.d("DesktopIntegrationScreen", "Starting initial call history sync")

            // Use auth.currentUser?.uid directly (same as working test)
            val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            if (userId == null) {
                android.util.Log.e("DesktopIntegrationScreen", "Cannot sync - no authenticated user")
                return
            }
            android.util.Log.d("DesktopIntegrationScreen", "Syncing call history for userId: $userId")

            val callHistorySyncService = com.phoneintegration.app.desktop.CallHistorySyncService(appContext)
            callHistorySyncService.syncCallHistoryForUser(userId)
            android.util.Log.d("DesktopIntegrationScreen", "Initial call history sync completed")
        } catch (e: Exception) {
            android.util.Log.e("DesktopIntegrationScreen", "Error during initial call history sync", e)
        }
    }

    // Load devices when screen appears
    LaunchedEffect(Unit) {
        android.util.Log.d("DesktopIntegrationScreen", "Screen appeared, loading devices...")
        refreshSettings()
        loadDevices(forceRefresh = false)
    }

    // Log when device list changes for debugging
    LaunchedEffect(pairedDevices.size) {
        android.util.Log.d("DesktopIntegrationScreen", "Device list updated: ${pairedDevices.size} devices")
    }

    // QR Scanner launcher with optimized settings for faster/better detection
    val scanLauncher = rememberLauncherForActivityResult(
        contract = ScanContract(),
        onResult = { result ->
            // Null result from cancelled scan
            if (result == null) {
                android.util.Log.w("DesktopIntegrationScreen", "QR scan cancelled by user")
                isScanButtonBusy = false
                return@rememberLauncherForActivityResult
            }
            if (result.contents == null) {
                android.util.Log.w("DesktopIntegrationScreen", "QR scan returned null content - likely no QR detected")
                isScanButtonBusy = false
                // Provide better guidance to user
                errorMessage = "No QR code detected. Make sure:\n1. The QR code is visible on your Mac screen\n2. You waited 2+ seconds for focus\n3. The QR code is fully in frame\n\nTry again."
                return@rememberLauncherForActivityResult
            }
            android.util.Log.d("DesktopIntegrationScreen", "QR code scanned, starting processing...")
            scope.launch {
                try {
                    isLoading = true
                    errorMessage = null

                    // Parse QR code and handle pairing
                    val qrContent = result.contents.trim()

                    // Check if this is a sync group QR code (format: sync_xxx, web_xxx, macos_xxx)
                    if (qrContent.startsWith("sync_") || qrContent.startsWith("web_") || qrContent.startsWith("macos_")) {
                        try {
                            val result = unifiedIdentityManager.joinSyncGroupFromQRCode(qrContent, "Android")
                            android.util.Log.d("DesktopIntegrationScreen", "Join sync group result: success=${result.isSuccess}")
                            if (result.isSuccess) {
                                val joinResult = result.getOrNull()
                                android.util.Log.d("DesktopIntegrationScreen", "Join result details: success=${joinResult?.success}, message='${joinResult?.message}'")
                                if (joinResult?.success == true) {
                                    successMessage = "Successfully joined sync group! (${joinResult.deviceCount}/${joinResult.deviceLimit} devices)"
                                    showSuccessDialog = true
                                    // Trigger initial data sync for newly joined group
                                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                        try {
                                            android.util.Log.d("DesktopIntegrationScreen", "Starting initial data sync after sync group join")
                                            syncInitialMessages()
                                            syncInitialContacts()
                                            syncInitialCallHistory()
                                        } catch (e: Exception) {
                                            android.util.Log.e("DesktopIntegrationScreen", "Error syncing data after sync group join", e)
                                        }
                                    }
                                    loadDevices(forceRefresh = true)
                                } else {
                                    errorMessage = joinResult?.message ?: "Failed to join sync group"
                                    android.util.Log.e("DesktopIntegrationScreen", "Join failed: ${errorMessage}")
                                }
                            } else {
                                val errorMsg = result.exceptionOrNull()?.message ?: "Failed to join sync group"
                                errorMessage = errorMsg
                                android.util.Log.e("DesktopIntegrationScreen", "Join sync group failed: $errorMsg")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("DesktopIntegrationScreen", "Sync group join exception", e)
                            errorMessage = e.message ?: "Failed to join sync group"
                        }
                    } else {
                        // Try new unified pairing format first (token-based)
                        val tokenData = parsePairingQrCode(qrContent)
                        android.util.Log.d("DesktopIntegrationScreen", "Parsed token data: $tokenData")

                        if (tokenData != null) {
                            android.util.Log.d("DesktopIntegrationScreen", "Using new pending pairing format")

                            // CRITICAL: Ensure Android is authenticated before pairing
                            // This ensures the Cloud Function receives the correct user ID
                            android.util.Log.d("DesktopIntegrationScreen", "Getting unified user ID...")
                            val userId = try {
                                withTimeout(10000) { // 10 second timeout
                                    unifiedIdentityManager.getUnifiedUserId()
                                }
                            } catch (e: TimeoutCancellationException) {
                                android.util.Log.e("DesktopIntegrationScreen", "Authentication timeout after 10 seconds")
                                errorMessage = "Authentication timeout. Please check your internet connection."
                                isLoading = false
                                isScanButtonBusy = false
                                return@launch
                            } catch (e: Exception) {
                                android.util.Log.e("DesktopIntegrationScreen", "Authentication error: ${e.message}", e)
                                errorMessage = "Authentication failed: ${e.message}"
                                isLoading = false
                                isScanButtonBusy = false
                                return@launch
                            }

                            if (userId == null) {
                                errorMessage = "Authentication failed. Please try again."
                                android.util.Log.e("DesktopIntegrationScreen", "Failed to authenticate before pairing - userId is null")
                                isLoading = false
                                isScanButtonBusy = false
                                return@launch
                            }
                            android.util.Log.d("DesktopIntegrationScreen", "Authenticated as user: $userId")

                            // CRITICAL: If authenticated as a device user, sign out first
                            // This ensures completePairing runs as the real/anonymous user
                            if (userId?.startsWith("device_") == true) {
                                android.util.Log.d("DesktopIntegrationScreen", "WARNING: Android authenticated as device user, signing out to reset")
                                try {
                                    com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                                    // Sign in anonymously to get a fresh anonymous user
                                    com.google.android.gms.tasks.Tasks.await(
                                        com.google.firebase.auth.FirebaseAuth.getInstance().signInAnonymously()
                                    )
                                    android.util.Log.d("DesktopIntegrationScreen", "Reset to anonymous user for pairing")
                                } catch (e: Exception) {
                                    android.util.Log.w("DesktopIntegrationScreen", "Failed to reset auth", e)
                                }
                            }

                            android.util.Log.d("DesktopIntegrationScreen", "Calling completePairing with token: ${tokenData.token.take(8)}...")
                            try {
                                val pairingResult = desktopSyncService.completePairing(tokenData.token, true)
                                android.util.Log.d("DesktopIntegrationScreen", "completePairing result: $pairingResult")
                                when (pairingResult) {
                                    is CompletePairingResult.Approved -> {
                                        android.util.Log.d("DesktopIntegrationScreen", "Pairing approved! Device ID: ${pairingResult.deviceId}")

                                        android.util.Log.d("DesktopIntegrationScreen", "SETTING SUCCESS DIALOG: showSuccessDialog=true, message=Successfully paired")
                                        successMessage = "Successfully paired with desktop device!"
                                        showSuccessDialog = true
                                        android.util.Log.d("DesktopIntegrationScreen", "SUCCESS DIALOG STATE SET: showSuccessDialog=$showSuccessDialog, successMessage=$successMessage")

                                        // Trigger initial data sync immediately
                                        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                            try {
                                                android.util.Log.d("DesktopIntegrationScreen", "Starting initial data sync after pairing")
                                                syncInitialMessages()
                                                syncInitialContacts()
                                                syncInitialCallHistory()
                                            } catch (e: Exception) {
                                                android.util.Log.e("DesktopIntegrationScreen", "Error syncing data", e)
                                            }
                                        }

                                        // Refresh device list
                                        loadDevices(forceRefresh = true)
                                    }
                                    is CompletePairingResult.Rejected -> {
                                        errorMessage = "Pairing rejected by the desktop device"
                                    }
                                    is CompletePairingResult.Error -> {
                                        errorMessage = pairingResult.message
                                            ?: "Failed to pair device"
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("DesktopIntegrationScreen", "Pairing failed", e)
                                errorMessage = e.message ?: "Failed to pair device"
                            }
                        } else if (isLegacyPairingCode(qrContent)) {
                            android.util.Log.d("DesktopIntegrationScreen", "Detected legacy pairing code - using Cloud Function system")
                            // Legacy format - use the working Cloud Function system
                            val tokenData = parsePairingQrCode(qrContent)
                            if (tokenData != null) {
                                val pairingResult = desktopSyncService.completePairing(tokenData.token, true)
                                when (pairingResult) {
                                    is CompletePairingResult.Approved -> {
                                        successMessage = "Successfully paired with desktop device!"
                                        showSuccessDialog = true
                                        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                            try {
                                                syncInitialMessages()
                                                syncInitialContacts()
                                                syncInitialCallHistory()
                                            } catch (e: Exception) {
                                                android.util.Log.e("DesktopIntegrationScreen", "Error syncing messages", e)
                                            }
                                        }
                                        loadDevices(forceRefresh = true)
                                    }
                                    is CompletePairingResult.Rejected -> {
                                        errorMessage = "Pairing was rejected"
                                    }
                                    is CompletePairingResult.Error -> {
                                        errorMessage = pairingResult.message
                                            ?: "Failed to pair device"
                                    }
                                }
                            } else {
                                errorMessage = "Invalid pairing code format"
                            }
                        } else {
                            android.util.Log.d("DesktopIntegrationScreen", "Invalid QR code format")
                            // Invalid format
                            errorMessage = "Invalid QR code format. Please ensure you're scanning a valid pairing code from a compatible app."
                        }
                    }
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Failed to pair device"
                } finally {
                    isLoading = false
                    isScanButtonBusy = false
                }
            }
        }
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Desktop Integration") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                Text(
                    text = "Connect Desktop & Web Apps",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Generate a QR code and scan it with your macOS or Web app to pair devices",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Device limit info card with User ID
            item {
                val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "Not signed in"
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (canAddDevice)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        // User ID row with copy button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "User ID",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = currentUserId,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(currentUserId))
                                }
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "Copy User ID",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Device count and plan info
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Paired Devices: $deviceCount / $deviceLimit",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Plan: ${userPlan.replaceFirstChar { it.uppercase() }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!canAddDevice) {
                                TextButton(onClick = {
                                    errorMessage = "Device limit reached. Upgrade to Pro for unlimited devices."
                                }) {
                                    Text("Upgrade", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }

            // Scan QR Button - ALWAYS enabled unless actively scanning or device limit reached
            item {
                Button(
                    onClick = {
                        android.util.Log.d("DesktopIntegrationScreen", "Scan button clicked! canAddDevice=$canAddDevice, isScanButtonBusy=$isScanButtonBusy")
                        if (!canAddDevice) {
                            errorMessage = "Device limit reached ($deviceCount/$deviceLimit). Upgrade to Pro for unlimited devices."
                            return@Button
                        }
                        // Check if recovery code setup is needed before first pairing
                        checkRecoveryBeforePairing {
                            try {
                                val options = ScanOptions().apply {
                                    setDesiredBarcodeFormats("QR_CODE")
                                    // Improved prompt to guide user better
                                    setPrompt("Point camera at the QR code on your Mac or Web app")
                                    setBeepEnabled(true)
                                    setOrientationLocked(false)
                                    setCaptureActivity(com.journeyapps.barcodescanner.CaptureActivity::class.java)
                                }
                                isScanButtonBusy = true
                                errorMessage = null
                                android.util.Log.d("DesktopIntegrationScreen", "Launching QR scanner...")
                                scanLauncher.launch(options)
                            } catch (e: Exception) {
                                android.util.Log.e("DesktopIntegrationScreen", "Error launching QR scanner", e)
                                errorMessage = "Failed to open camera: ${e.message}"
                                isScanButtonBusy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    // Only disable when: actively scanning OR confirmed device limit reached
                    // Do NOT disable during loading - user should be able to scan immediately
                    enabled = !isScanButtonBusy && canAddDevice
                ) {
                    Icon(Icons.Default.QrCodeScanner, "Scan QR")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        when {
                            !canAddDevice -> "Device Limit Reached"
                            isScanButtonBusy -> "Scanning..."
                            else -> "Scan Desktop QR Code"
                        }
                    )
                }

                // Help text under button
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (canAddDevice)
                        "Open SyncFlow on your Mac or Web browser to see the QR code"
                    else
                        "Upgrade to Pro for unlimited devices",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (canAddDevice) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }

            // Manual Sync Button (for when data isn't showing on Mac/Web)
            if (pairedDevices.isNotEmpty()) {
                item {
                    var isSyncing by remember { mutableStateOf(false) }
                    var syncMessage by remember { mutableStateOf<String?>(null) }

                    OutlinedButton(
                        onClick = {
                            if (isSyncing) return@OutlinedButton
                            isSyncing = true
                            syncMessage = null
                            scope.launch {
                                try {
                                    android.util.Log.d("DesktopIntegrationScreen", "Manual sync triggered")
                                    // Sync call history (contacts are slow due to photos)
                                    syncInitialCallHistory()
                                    syncMessage = "Call history synced!"
                                } catch (e: Exception) {
                                    android.util.Log.e("DesktopIntegrationScreen", "Sync failed", e)
                                    syncMessage = "Failed: ${e.message}"
                                } finally {
                                    isSyncing = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Syncing...")
                        } else {
                            Icon(Icons.Default.Sync, "Sync")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sync Call History")
                        }
                    }

                    syncMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (msg.startsWith("Sync complete")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // Error Message
            errorMessage?.let { error ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                "Error",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { errorMessage = null }) {
                                Icon(Icons.Default.Close, "Dismiss")
                            }
                        }
                    }
                }
            }

            // Paired Devices Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Paired Devices (${pairedDevices.size})",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Refresh button
                        IconButton(
                            onClick = { loadDevices(forceRefresh = true) },
                            enabled = !isLoadingDevices
                        ) {
                            if (isLoadingDevices) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Refresh, "Refresh")
                            }
                        }
                        // Sync button (only if devices exist)
                        if (pairedDevices.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            isLoading = true
                                            unifiedIdentityManager.triggerInitialMessageSync()
                                            successMessage = "Initial message sync triggered for all devices"
                                            showSuccessDialog = true
                                        } catch (e: Exception) {
                                            errorMessage = "Failed to trigger initial sync: ${e.message}"
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                },
                                enabled = !isLoading
                            ) {
                                Icon(Icons.Default.Sync, "Sync Messages")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Sync")
                            }
                        }
                    }
                }
            }

            // Show loading indicator
            if (isLoadingDevices && pairedDevices.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Loading devices...")
                        }
                    }
                }
            }

            // Show paired devices list
            if (pairedDevices.isNotEmpty()) {

                items(pairedDevices) { device ->
                    PairedDeviceItem(
                        device = device,
                        onUnpair = {
                            scope.launch {
                                try {
                                    isLoading = true
                                    val result = unifiedIdentityManager.unregisterDevice(device.id)
                                    result.onSuccess {
                                        successMessage = "Successfully unpaired ${device.name}"
                                        showSuccessDialog = true
                                        loadDevices(forceRefresh = true)
                                    }.onFailure { error ->
                                        errorMessage = error.message ?: "Failed to unpair device"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: "Failed to unpair device"
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    )
                }
            }

            // Show "No devices" only when not loading and list is empty
            if (!isLoadingDevices && pairedDevices.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Devices,
                                "No devices",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No devices paired yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Scan the QR code from your Mac or Web app to pair.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Debug info
                            val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                            val lastError = PairedDevicesCache.lastError
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Debug: userId=${currentUserId?.take(8) ?: "null"}...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            if (lastError != null) {
                                Text(
                                    text = "Error: $lastError",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Sync Settings Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sync Settings",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                    IconButton(
                        onClick = { refreshSettings() },
                        modifier = Modifier.padding(vertical = 16.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            "Refresh Settings",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Background Sync Setting
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Sync,
                            "Background Sync",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Background Sync",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Keep messages and data synced in background",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isBackgroundSyncEnabled,
                            onCheckedChange = { enabled ->
                                isBackgroundSyncEnabled = enabled
                                preferencesManager.setBackgroundSyncEnabled(enabled)
                                android.util.Log.d("DesktopIntegrationScreen", "Background sync ${if (enabled) "enabled" else "disabled"}")
                            }
                        )
                    }
                }
            }

            // Notification Mirroring Setting
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Notifications,
                            "Notification Mirroring",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Notification Mirroring",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (!hasNotificationPermission) {
                                    IconButton(
                                        onClick = {
                                            // Refresh permission status
                                            hasNotificationPermission = NotificationMirrorService.isEnabled(appContext)
                                            android.util.Log.d("DesktopIntegrationScreen", "Permission status refreshed: $hasNotificationPermission")
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            "Refresh permission status",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "Mirror Android notifications to desktop",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!hasNotificationPermission) {
                                Text(
                                    text = "Requires notification access permission - tap switch to enable",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Switch(
                            checked = isNotificationMirrorEnabled && hasNotificationPermission,
                            onCheckedChange = { enabled ->
                                if (enabled && !hasNotificationPermission) {
                                    // User wants to enable but doesn't have permission - open settings
                                    android.util.Log.d("DesktopIntegrationScreen", "Opening notification access settings")
                                    try {
                                        val intent = android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                        context.startActivity(intent)
                                        successMessage = "Opening Notification Access settings...\n\nPlease enable SyncFlow, then return to this screen and toggle the switch again."
                                        showSuccessDialog = true
                                    } catch (e: Exception) {
                                        android.util.Log.e("DesktopIntegrationScreen", "Failed to open notification settings", e)
                                        // Fallback to general settings if specific intent fails
                                        try {
                                            val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                            context.startActivity(intent)
                                            successMessage = "Opening Settings...\n\nPlease enable Notification Access for SyncFlow in Special Access → Notification Access."
                                            showSuccessDialog = true
                                        } catch (e2: Exception) {
                                            android.util.Log.e("DesktopIntegrationScreen", "Failed to open general settings", e2)
                                            // Show message if we can't open settings
                                            successMessage = "Please enable Notification Access for SyncFlow in Android Settings → Special Access → Notification Access."
                                            showSuccessDialog = true
                                        }
                                    }
                                } else if (hasNotificationPermission) {
                                    // Permission granted, update setting
                                    isNotificationMirrorEnabled = enabled
                                    preferencesManager.setNotificationMirrorEnabled(enabled)

                                    val userId = unifiedIdentityManager.getUnifiedUserIdSync()
                                    android.util.Log.d("DesktopIntegrationScreen", "Notification mirroring ${if (enabled) "enabled" else "disabled"} for user: $userId")
                                    android.util.Log.d("DesktopIntegrationScreen", "Notifications will be stored under: /users/$userId/mirrored_notifications/")

                                    if (enabled) {
                                        successMessage = "Notification mirroring enabled!\n\nNotifications will be sent to: /users/$userId/mirrored_notifications/\n\nMake sure your macOS app is authenticated with the same user ID."
                                        showSuccessDialog = true
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Recovery Code Section
            item {
                var showRecoveryCode by remember { mutableStateOf(false) }
                var recoveryCode by remember { mutableStateOf<String?>(null) }
                var isLoadingCode by remember { mutableStateOf(false) }
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Key,
                                "Recovery Code",
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Recovery Code",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Use this code to recover your account after reinstall",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = {
                                    if (showRecoveryCode) {
                                        showRecoveryCode = false
                                    } else {
                                        scope.launch {
                                            isLoadingCode = true
                                            val recoveryManager = com.phoneintegration.app.auth.RecoveryCodeManager.getInstance(appContext)
                                            recoveryCode = recoveryManager.getRecoveryCode()
                                            isLoadingCode = false
                                            showRecoveryCode = true
                                        }
                                    }
                                },
                                enabled = !isLoadingCode
                            ) {
                                Text(if (isLoadingCode) "Loading..." else if (showRecoveryCode) "Hide" else "View")
                            }
                        }

                        // Show recovery code when expanded
                        if (showRecoveryCode && recoveryCode != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = recoveryCode ?: "",
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            letterSpacing = 2.sp
                                        ),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(recoveryCode ?: ""))
                                            successMessage = "Recovery code copied to clipboard!"
                                            showSuccessDialog = true
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            "Copy",
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Copy Code")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Save this code securely. You'll need it to recover your account if you reinstall the app.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (showRecoveryCode && recoveryCode == null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No recovery code found. You may have skipped recovery setup.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Photo Sync Setting
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Photo,
                            "Photo Sync",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Photo Sync",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Sync recent photos to desktop (Premium feature)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        isLoading = true
                                        val photoSyncService = PhotoSyncService(appContext)
                                        val result = photoSyncService.syncRecentPhotos()

                                        result.onSuccess { message ->
                                            successMessage = message
                                            showSuccessDialog = true
                                        }.onFailure { error ->
                                            errorMessage = error.message ?: "Photo sync failed"
                                        }

                                    } catch (e: Exception) {
                                        errorMessage = "Photo sync failed: ${e.message}"
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                            enabled = !isLoading
                        ) {
                            Text(if (isLoading) "Syncing..." else "Sync Now")
                        }
                    }
                }
            }
        }
    }

    // Manual Code Input Dialog
    if (showManualInput) {
        AlertDialog(
            onDismissRequest = { showManualInput = false },
            title = { Text("Enter Pairing Token") },
            text = {
                Column {
                    Text("Enter the pairing token:")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "You can generate a test token using the button above, or get a token from a compatible desktop app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { manualCode = it.trim() },
                        label = { Text("Pairing Token") },
                        placeholder = { Text("Enter token here...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                isLoading = true
                                errorMessage = null

                                val result = unifiedIdentityManager.redeemPairingToken(
                                    manualCode.trim(),
                                    "Manual Entry Device",
                                    "android"
                                )

                                result.onSuccess { deviceInfo ->
                                    successMessage = "Successfully paired device: ${deviceInfo.name}!"
                                    showSuccessDialog = true
                                    showManualInput = false
                                    manualCode = ""
                                }.onFailure { error ->
                                    when {
                                        error.message?.contains("expired") == true ->
                                            errorMessage = "Token has expired. Please generate a new one."
                                        error.message?.contains("Invalid") == true ->
                                            errorMessage = "Invalid token format. Please check and try again."
                                        else ->
                                            errorMessage = "Failed to pair device: ${error.message}"
                                    }
                                }

                            } catch (e: Exception) {
                                errorMessage = e.message ?: "Failed to pair device"
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = manualCode.isNotBlank() && !isLoading
                ) {
                    Text("Pair")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualInput = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Success Dialog
    if (showSuccessDialog && successMessage != null) {
        AlertDialog(
            onDismissRequest = {
                showSuccessDialog = false
                successMessage = null
            },
            icon = {
                Icon(
                    Icons.Default.CheckCircle,
                    "Success",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("Success!") },
            text = { Text(successMessage!!) },
            confirmButton = {
                TextButton(onClick = {
                    showSuccessDialog = false
                    successMessage = null
                }) {
                    Text("OK")
                }
            }
        )
    }

    // Recovery Code Setup Dialog - shows before first pairing
    if (showRecoverySetupDialog) {
        AlertDialog(
            onDismissRequest = {
                // Don't allow dismissing without action
            },
            icon = {
                Icon(
                    Icons.Default.Key,
                    "Recovery Code",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = { Text("Save Your Recovery Code") },
            text = {
                Column {
                    if (generatedRecoveryCode == null && !isGeneratingCode) {
                        Text(
                            text = "Before pairing with your Mac or Web app, we recommend saving a recovery code.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "This code lets you recover your data if you reinstall the app or switch phones.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    "Warning",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Without a recovery code, reinstalling this app will result in losing access to your synced messages.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    } else if (isGeneratingCode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Generating secure code...")
                        }
                    } else if (generatedRecoveryCode != null) {
                        Text(
                            text = "Your recovery code has been generated. Save it somewhere safe!",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = generatedRecoveryCode!!,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        letterSpacing = 2.sp
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = {
                                        clipboardManager.setText(
                                            androidx.compose.ui.text.AnnotatedString(generatedRecoveryCode!!)
                                        )
                                        successMessage = "Recovery code copied!"
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        "Copy",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Copy to Clipboard")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "You can also view this code later in Settings > Recovery Code.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                if (generatedRecoveryCode == null && !isGeneratingCode) {
                    // Show "Generate Code" button
                    Button(
                        onClick = {
                            scope.launch {
                                isGeneratingCode = true
                                val result = recoveryManager.setupRecoveryCode()
                                result.onSuccess { code ->
                                    generatedRecoveryCode = code
                                }.onFailure { error ->
                                    errorMessage = "Failed to generate recovery code: ${error.message}"
                                    showRecoverySetupDialog = false
                                    pendingPairingAction = null
                                }
                                isGeneratingCode = false
                            }
                        }
                    ) {
                        Icon(Icons.Default.Key, "Generate", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generate Recovery Code")
                    }
                } else if (generatedRecoveryCode != null) {
                    // Show "Continue to Pairing" button
                    Button(
                        onClick = {
                            showRecoverySetupDialog = false
                            // Proceed with the pending pairing action
                            pendingPairingAction?.invoke()
                            pendingPairingAction = null
                            generatedRecoveryCode = null
                        }
                    ) {
                        Text("Continue to Pairing")
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.Check, "Continue", modifier = Modifier.size(18.dp))
                    }
                }
            },
            dismissButton = {
                if (!isGeneratingCode) {
                    TextButton(
                        onClick = {
                            if (generatedRecoveryCode == null) {
                                // User is skipping recovery setup
                                scope.launch {
                                    recoveryManager.skipSetup()
                                    showRecoverySetupDialog = false
                                    // Proceed with pairing anyway
                                    pendingPairingAction?.invoke()
                                    pendingPairingAction = null
                                }
                            } else {
                                // User already generated code, just cancel
                                showRecoverySetupDialog = false
                                pendingPairingAction = null
                                generatedRecoveryCode = null
                            }
                        }
                    ) {
                        Text(
                            if (generatedRecoveryCode == null) "Skip (Not Recommended)" else "Cancel",
                            color = if (generatedRecoveryCode == null)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }
}

// Helper function to parse QR codes (supports V1 and V2 formats)
private fun parsePairingQrCode(qrData: String): PairingQrData? {
    return try {
        val json = org.json.JSONObject(qrData)

        // Check for V2 format first (has "v" field and "device" object)
        val version = json.optInt("v", 1)
        if (version >= 2 && json.has("device")) {
            // V2 format: {"v": 2, "token": "...", "device": {"id": "...", "name": "...", "type": "..."}, "expires": ...}
            val token = json.optString("token", "")
            val device = json.optJSONObject("device")
            val deviceId = device?.optString("id", "") ?: ""
            val deviceName = device?.optString("name", "Desktop") ?: "Desktop"
            val deviceType = device?.optString("type", "macos") ?: "macos"
            val expiresAt = json.optLong("expires", 0)

            if (token.isBlank()) {
                android.util.Log.w("QRParser", "V2 QR code has empty token")
                null
            } else {
                android.util.Log.d("QRParser", "Parsed V2 QR: token=${token.take(8)}..., deviceId=$deviceId, type=$deviceType")
                PairingQrData(
                    token = token,
                    name = deviceName,
                    platform = deviceType,
                    syncGroupId = null,
                    version = 2,
                    deviceId = deviceId,
                    expiresAt = expiresAt
                )
            }
        } else {
            // V1 format: {"token": "...", "name": "...", "platform": "...", "syncGroupId": "..."}
            val token = json.optString("token", "")
            val name = json.optString("name", "Desktop")
            val platform = json.optString("platform", "web")
            val rawSyncGroupId = json.optString("syncGroupId", "")
            val syncGroupId = if (rawSyncGroupId == "null") "" else rawSyncGroupId

            if (token.isBlank()) {
                android.util.Log.w("QRParser", "V1 QR code has empty token")
                null
            } else {
                android.util.Log.d("QRParser", "Parsed V1 QR: token=${token.take(8)}..., name=$name")
                PairingQrData(
                    token = token,
                    name = name,
                    platform = platform,
                    syncGroupId = syncGroupId.ifBlank { null },
                    version = 1,
                    deviceId = null,
                    expiresAt = 0
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("QRParser", "Failed to parse QR code", e)
        null
    }
}

// Check if QR code is in legacy format
private fun isLegacyPairingCode(qrData: String): Boolean {
    return try {
        // Legacy codes might be plain strings or different JSON format
        !qrData.trim().startsWith("{") || !qrData.contains("token")
    } catch (e: Exception) {
        true // Assume legacy if parsing fails
    }
}

// Data classes - Updated for V2 support
data class PairingQrData(
    val token: String,
    val name: String,
    val platform: String,
    val syncGroupId: String?,
    val version: Int = 1,        // 1 = legacy, 2 = new V2 with device limits
    val deviceId: String? = null, // Persistent device ID (V2 only)
    val expiresAt: Long = 0       // Token expiration timestamp (V2 only)
) {
    val isV2: Boolean get() = version >= 2
}

@Composable
private fun PairedDeviceItem(
    device: PairedDevice,
    onUnpair: () -> Unit
) {
    var showUnpairDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Device Icon
            val icon = when (device.platform.lowercase()) {
                "macos", "mac" -> Icons.Default.Computer
                "windows" -> Icons.Default.DesktopWindows
                else -> Icons.Default.Web
            }

            Icon(
                icon,
                contentDescription = device.platform,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Device Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = device.platform.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Last seen: ${java.text.SimpleDateFormat("MMM dd, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(device.lastSeen))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Sync Status
                device.syncStatus?.let { syncStatus ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (syncStatus.status) {
                            "starting" -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.dp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Starting sync...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            "syncing" -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.dp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Syncing: ${syncStatus.syncedMessages}/${syncStatus.totalMessages}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            "completed" -> {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    "Sync completed",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                val completedTime = syncStatus.lastSyncCompleted?.let {
                                    java.text.SimpleDateFormat("MMM dd, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(it))
                                } ?: "Recently"
                                Text(
                                    text = "${syncStatus.syncedMessages} messages synced ($completedTime)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            "failed" -> {
                                Icon(
                                    Icons.Default.Error,
                                    "Sync failed",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Sync failed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            else -> {
                                // Idle state - no sync status to show
                            }
                        }
                    }
                }
            }

            // Unpair Button
            IconButton(onClick = { showUnpairDialog = true }) {
                Icon(
                    Icons.Default.Delete,
                    "Unpair",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    // Unpair Confirmation Dialog
    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            title = { Text("Unpair Device?") },
            text = { Text("Are you sure you want to unpair ${device.name}? You'll need to scan the QR code again to reconnect.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUnpair()
                        showUnpairDialog = false
                    }
                ) {
                    Text("Unpair", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
