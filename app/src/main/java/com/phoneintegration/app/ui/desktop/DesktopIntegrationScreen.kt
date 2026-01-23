package com.phoneintegration.app.ui.desktop

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.phoneintegration.app.auth.UnifiedIdentityManager
import com.phoneintegration.app.desktop.DesktopSyncService
import com.phoneintegration.app.desktop.CompletePairingResult
import com.phoneintegration.app.desktop.PairedDevice
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.desktop.NotificationMirrorService
import com.phoneintegration.app.desktop.PhotoSyncService
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import androidx.compose.material3.Switch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopIntegrationScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    // Keep both systems for compatibility
    val unifiedIdentityManager = remember { UnifiedIdentityManager.getInstance(appContext) }
    val desktopSyncService = remember { DesktopSyncService(appContext) }

    // Using desktop sync service for device management (working system)
    var pairedDevices by remember { mutableStateOf<List<PairedDevice>>(emptyList()) }

    // Sync settings state
    val preferencesManager = remember { PreferencesManager(appContext) }
    var isBackgroundSyncEnabled by remember { mutableStateOf(preferencesManager.backgroundSyncEnabled.value) }
    var hasNotificationPermission by remember { mutableStateOf(NotificationMirrorService.isEnabled(appContext)) }
    var isNotificationMirrorEnabled by remember { mutableStateOf(preferencesManager.notificationMirrorEnabled.value) }

    // Refresh settings when screen becomes active
    fun refreshSettings() {
        isBackgroundSyncEnabled = preferencesManager.backgroundSyncEnabled.value
        hasNotificationPermission = NotificationMirrorService.isEnabled(appContext)
        isNotificationMirrorEnabled = preferencesManager.notificationMirrorEnabled.value
        android.util.Log.d("DesktopIntegrationScreen", "Settings refreshed - Background: $isBackgroundSyncEnabled, Notification: $isNotificationMirrorEnabled, Permission: $hasNotificationPermission")
    }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var lastScannedContent by remember { mutableStateOf<String?>(null) }
    var showManualInput by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }

    // Load paired devices using the working system
    suspend fun refreshPairedDevices() {
        val devices = desktopSyncService.getPairedDevices()
        pairedDevices = devices
        android.util.Log.d("DesktopIntegrationScreen", "Loaded ${devices.size} paired devices")
    }

    LaunchedEffect(Unit) {
        try {
            isLoading = true
            refreshPairedDevices()
            // Refresh settings when screen loads
            refreshSettings()
        } catch (e: Exception) {
            android.util.Log.e("DesktopIntegrationScreen", "Error loading paired devices", e)
            errorMessage = "Failed to load paired devices"
        } finally {
            isLoading = false
        }
    }

    // Refresh settings when screen regains focus
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshSettings()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // QR Scanner launcher
    val scanLauncher = rememberLauncherForActivityResult(
        contract = ScanContract(),
        onResult = { result ->
            if (result.contents != null) {
                lastScannedContent = result.contents // Store for debugging
                scope.launch {
                    try {
                        isLoading = true
                        errorMessage = null

                        // Parse QR code and handle pairing
                        val qrContent = result.contents.trim()
                        android.util.Log.d("DesktopIntegrationScreen", "Scanned QR content: '$qrContent'")

                        // Check if this is a sync group QR code (new format: web_xxx, macos_xxx, sync_xxx)
                        if (qrContent.startsWith("web_") || qrContent.startsWith("macos_") || qrContent.startsWith("sync_") || qrContent.startsWith("android_")) {
                            android.util.Log.d("DesktopIntegrationScreen", "Detected sync group QR code")
                            try {
                                val result = unifiedIdentityManager.joinSyncGroupFromQRCode(qrContent, "Android")
                                if (result.isSuccess) {
                                    val joinResult = result.getOrNull()
                                    if (joinResult?.success == true) {
                                        successMessage = "Successfully joined sync group! (${joinResult.deviceCount}/${joinResult.deviceLimit} devices)"
                                        showSuccessDialog = true
                                        scope.launch {
                                            try {
                                                refreshPairedDevices()
                                            } catch (e: Exception) {
                                                android.util.Log.e("DesktopIntegrationScreen", "Error refreshing devices", e)
                                            }
                                        }
                                    } else {
                                        errorMessage = joinResult?.message ?: "Failed to join sync group"
                                    }
                                } else {
                                    errorMessage = result.exceptionOrNull()?.message ?: "Failed to join sync group"
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("DesktopIntegrationScreen", "Sync group join failed", e)
                                errorMessage = e.message ?: "Failed to join sync group"
                            }
                        } else {
                            // Try new unified pairing format first (token-based)
                            val tokenData = parsePairingQrCode(qrContent)
                        android.util.Log.d("DesktopIntegrationScreen", "Parsed token data: $tokenData")

                        if (tokenData != null) {
                            android.util.Log.d("DesktopIntegrationScreen", "Using new pending pairing format")
                            try {
                                val pairingResult = desktopSyncService.completePairing(tokenData.token, true)
                                when (pairingResult) {
                                    is CompletePairingResult.Approved -> {
                                        successMessage = "Successfully paired with desktop device!"
                                        showSuccessDialog = true
                                        scope.launch {
                                            try {
                                                refreshPairedDevices()
                                            } catch (e: Exception) {
                                                android.util.Log.e("DesktopIntegrationScreen", "Error refreshing devices", e)
                                            }
                                        }
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
                                    // Refresh devices list
                                        scope.launch {
                                            try {
                                                refreshPairedDevices()
                                            } catch (e: Exception) {
                                                android.util.Log.e("DesktopIntegrationScreen", "Error refreshing devices", e)
                                            }
                                        }
                                }
                                    is CompletePairingResult.Rejected -> {
                                        errorMessage = "Pairing was rejected"
                                    }
                                    is CompletePairingResult.Error -> {
                                        errorMessage = pairingResult.message
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
                        } // Close the else block for sync group vs token-based pairing

                    } catch (e: Exception) {
                        errorMessage = e.message ?: "Failed to pair device"
                    } finally {
                        isLoading = false
                    }
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

            // Scan QR Button (for manual entry or legacy systems)
            item {
                OutlinedButton(
                    onClick = {
                        val options = ScanOptions().apply {
                            setDesiredBarcodeFormats("QR_CODE")
                            setPrompt("Scan QR code from desktop app")
                            setBeepEnabled(true)
                            setOrientationLocked(false)
                        }
                        scanLauncher.launch(options)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    Icon(Icons.Default.QrCodeScanner, "Scan QR")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan Desktop QR Code")
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
            if (pairedDevices.isNotEmpty()) {
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
                            Text("Sync Messages")
                        }
                    }
                }

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
                                        try {
                                            refreshPairedDevices()
                                        } catch (e: Exception) {
                                            android.util.Log.e("DesktopIntegrationScreen", "Error refreshing devices post-unpair", e)
                                        }
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
            } else {
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
                                text = "Generate a test token above and use manual entry to test pairing.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
}

// Helper function to parse QR codes
private fun parsePairingQrCode(qrData: String): PairingQrData? {
    return try {
        val json = org.json.JSONObject(qrData)
        val token = json.optString("token", "")
        val name = json.optString("name", "Desktop")
        val platform = json.optString("platform", "web")

        if (token.isBlank()) {
            null
        } else {
            PairingQrData(token, name, platform)
        }
    } catch (e: Exception) {
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

// Data classes
data class PairingQrData(
    val token: String,
    val name: String,
    val platform: String
)

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
