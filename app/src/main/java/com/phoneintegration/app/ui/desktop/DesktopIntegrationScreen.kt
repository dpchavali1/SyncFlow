package com.phoneintegration.app.ui.desktop

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.google.zxing.BarcodeFormat
import com.phoneintegration.app.desktop.DesktopSyncService
import com.phoneintegration.app.desktop.PairedDevice
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopIntegrationScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncService = remember { DesktopSyncService(context) }

    var pairedDevices by remember { mutableStateOf<List<PairedDevice>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showManualInput by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var isBackgroundSyncEnabled by remember { mutableStateOf(false) }
    var pairingToken by remember { mutableStateOf<String?>(null) }
    var pairingTokenError by remember { mutableStateOf<String?>(null) }
    var showSyncAllDialog by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf<String?>(null) }

    // Check if service is running
    LaunchedEffect(Unit) {
        isBackgroundSyncEnabled = isServiceRunning(context)
    }

    // QR Code Scanner Launcher
    val scanLauncher = rememberLauncherForActivityResult(
        contract = ScanContract()
    ) { result ->
        if (result.contents != null) {
            val token = result.contents
            scope.launch {
                try {
                    isLoading = true
                    syncService.pairWithToken(token)
                    pairedDevices = syncService.getPairedDevices()
                    showSuccessDialog = true
                    isLoading = false
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Failed to pair device"
                    isLoading = false
                }
            }
        }
    }

    // Load paired devices
    LaunchedEffect(Unit) {
        isLoading = true
        pairedDevices = syncService.getPairedDevices()
        isLoading = false
    }

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
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Computer,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Use SyncFlow on your computer",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Send and receive SMS from your MacBook or PC browser",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Pair macOS app (shows a code + QR for the Mac app to use)
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    pairingTokenError = null
                                    try {
                                        pairingToken = syncService.generatePairingToken()
                                        successMessage = "Pairing code generated. Enter it in the macOS app to connect."
                                    } catch (e: Exception) {
                                        pairingTokenError = e.message ?: "Failed to generate pairing code"
                                    }
                                    isLoading = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Default.LaptopMac, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Pair macOS App")
                        }

                        // Scan QR Code Button
                        Button(
                            onClick = {
                                val options = ScanOptions()
                                options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                options.setPrompt("Scan QR code from SyncFlow website")
                                options.setBeepEnabled(true)
                                options.setOrientationLocked(true)
                                scanLauncher.launch(options)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Default.QrCodeScanner, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Scan QR Code")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Manual Code Entry Button
                        OutlinedButton(
                            onClick = { showManualInput = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Default.Input, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Enter Code Manually")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Sync Now Button
                        if (pairedDevices.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isLoading = true
                                        successMessage = null
                                        errorMessage = null
                                        try {
                                            val smsRepository = com.phoneintegration.app.SmsRepository(context)
                                            val messages = smsRepository.getAllRecentMessages(500)
                                            syncService.syncMessages(messages)
                                            successMessage = "Successfully synced ${messages.size} messages to desktop!"
                                        } catch (e: Exception) {
                                            errorMessage = "Sync failed: ${e.message}"
                                        }
                                        isLoading = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading
                            ) {
                                Icon(Icons.Default.Sync, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Sync Recent Messages (500)")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Sync ALL Messages Button
                            OutlinedButton(
                                onClick = { showSyncAllDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading
                            ) {
                                Icon(Icons.Default.CloudSync, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Sync All Messages")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Check for Outgoing Messages Button
                            OutlinedButton(
                                onClick = {
                                    com.phoneintegration.app.desktop.OutgoingMessageWorker.checkNow(context)
                                    successMessage = "Checking for messages from desktop..."
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading
                            ) {
                                Icon(Icons.Default.Download, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Send Messages from Desktop")
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Background Sync Toggle
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Background Sync",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = if (isBackgroundSyncEnabled) "Running" else "Stopped",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = isBackgroundSyncEnabled,
                                        onCheckedChange = { enabled ->
                                            if (enabled) {
                                                com.phoneintegration.app.desktop.OutgoingMessageService.start(context)
                                                successMessage = "Background sync started"
                                            } else {
                                                com.phoneintegration.app.desktop.OutgoingMessageService.stop(context)
                                                successMessage = "Background sync stopped"
                                            }
                                            isBackgroundSyncEnabled = enabled
                                        }
                                    )
                                }
                            }
                        }

                        if (isLoading) {
                            Spacer(modifier = Modifier.height(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }

            // Instructions
            item {
                Text(
                    text = "How to connect",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            item {
                InstructionCard(
                    number = "1",
                    title = "Open website",
                    description = "Visit localhost:3000 or syncflow.app on your computer"
                )
            }

            item {
                InstructionCard(
                    number = "2",
                    title = "Generate QR code",
                    description = "Click 'Generate QR Code' on the website"
                )
            }

            item {
                InstructionCard(
                    number = "3",
                    title = "Scan with phone",
                    description = "Tap 'Scan QR Code' above and scan the code from your screen"
                )
            }

            // Success Message
            successMessage?.let { success ->
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Sync Complete",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    success,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            IconButton(onClick = { successMessage = null }) {
                                Icon(Icons.Default.Close, "Dismiss")
                            }
                        }
                    }
                }
            }

            // Error Message
            errorMessage?.let { error ->
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Error",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            IconButton(onClick = { errorMessage = null }) {
                                Icon(Icons.Default.Close, "Dismiss")
                            }
                        }
                    }
                }
            }

            // Pairing Token (for macOS app)
            pairingToken?.let { token ->
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Pair with macOS",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Enter this code in the macOS SyncFlow app",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = token,
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Spacer(Modifier.height(12.dp))

                            // Optional QR for the Mac app (future QR scanning support)
                            generateQrCode(token)?.let { qr ->
                                Image(
                                    bitmap = qr,
                                    contentDescription = "Pairing QR Code",
                                    modifier = Modifier.size(200.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }

            pairingTokenError?.let { error ->
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Pairing",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            IconButton(onClick = { pairingTokenError = null }) {
                                Icon(Icons.Default.Close, "Dismiss")
                            }
                        }
                    }
                }
            }

            // Paired Devices Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Text(
                    text = "Paired Devices (${pairedDevices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }

            if (isLoading && pairedDevices.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (pairedDevices.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No devices paired yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                items(pairedDevices) { device ->
                    PairedDeviceItem(
                        device = device,
                        onUnpair = {
                            scope.launch {
                                syncService.unpairDevice(device.id)
                                pairedDevices = syncService.getPairedDevices()
                            }
                        }
                    )
                }
            }
        }
    }

    // Manual Input Dialog
    if (showManualInput) {
        AlertDialog(
            onDismissRequest = { showManualInput = false },
            title = { Text("Enter Pairing Code") },
            text = {
                Column {
                    Text(
                        "Enter the pairing code shown on your computer screen:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { manualCode = it },
                        label = { Text("Pairing Code") },
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
                                syncService.pairWithToken(manualCode)
                                pairedDevices = syncService.getPairedDevices()
                                showManualInput = false
                                manualCode = ""
                                showSuccessDialog = true
                                isLoading = false
                            } catch (e: Exception) {
                                errorMessage = e.message ?: "Failed to pair device"
                                showManualInput = false
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
                TextButton(onClick = {
                    showManualInput = false
                    manualCode = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Success Dialog
    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showSuccessDialog = false },
            icon = {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = { Text("Successfully Paired!") },
            text = { Text("Your desktop is now connected. You can send and receive messages from your computer.") },
            confirmButton = {
                TextButton(onClick = { showSuccessDialog = false }) {
                    Text("Got it")
                }
            }
        )
    }

    // Sync All Messages Confirmation Dialog
    if (showSyncAllDialog) {
        AlertDialog(
            onDismissRequest = { showSyncAllDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = { Text("Sync All Messages?") },
            text = {
                Column {
                    Text("This will sync ALL messages from your phone to your desktop.")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠️ This may take a while if you have thousands of messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "💡 Tip: Use \"Sync Recent Messages (500)\" for faster sync.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSyncAllDialog = false
                        scope.launch {
                            isLoading = true
                            successMessage = null
                            errorMessage = null
                            syncProgress = "Starting sync..."
                            try {
                                val smsRepository = com.phoneintegration.app.SmsRepository(context)

                                // Get ALL messages (no limit)
                                syncProgress = "Loading all messages from phone..."
                                val allMessages = smsRepository.getAllRecentMessages(limit = Int.MAX_VALUE)

                                syncProgress = "Syncing ${allMessages.size} messages to desktop..."
                                syncService.syncMessages(allMessages)

                                syncProgress = null
                                successMessage = "Successfully synced ALL ${allMessages.size} messages to desktop! 🎉"
                            } catch (e: Exception) {
                                syncProgress = null
                                errorMessage = "Sync failed: ${e.message}"
                            }
                            isLoading = false
                        }
                    }
                ) {
                    Text("Sync All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSyncAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Sync Progress Dialog
    syncProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = { },
            icon = {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            },
            title = { Text("Syncing Messages") },
            text = { Text(progress) },
            confirmButton = { }
        )
    }
}

@Composable
fun InstructionCard(
    number: String,
    title: String,
    description: String
) {
    ListItem(
        leadingContent = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(description) }
    )
}

@Composable
fun PairedDeviceItem(
    device: PairedDevice,
    onUnpair: () -> Unit
) {
    var showUnpairDialog by remember { mutableStateOf(false) }

    ListItem(
        leadingContent = {
            Icon(
                imageVector = when (device.platform) {
                    "macos" -> Icons.Default.LaptopMac
                    "windows" -> Icons.Default.Computer
                    else -> Icons.Default.Devices
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        headlineContent = { Text(device.name) },
        supportingContent = {
            val lastSeenText = remember(device.lastSeen) {
                val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                "Last seen: ${sdf.format(Date(device.lastSeen))}"
            }
            Text(lastSeenText)
        },
        trailingContent = {
            IconButton(onClick = { showUnpairDialog = true }) {
                Icon(Icons.Default.Delete, "Unpair")
            }
        }
    )

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
                    Text("Unpair")
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

// Helper function to check if OutgoingMessageService is running
private fun isServiceRunning(context: android.content.Context): Boolean {
    val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    @Suppress("DEPRECATION")
    return manager.getRunningServices(Integer.MAX_VALUE).any {
        it.service.className == com.phoneintegration.app.desktop.OutgoingMessageService::class.java.name
    }
}

private fun generateQrCode(token: String): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val bitmap = BarcodeEncoder().encodeBitmap(token, BarcodeFormat.QR_CODE, 600, 600)
        bitmap.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
