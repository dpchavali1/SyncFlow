package com.phoneintegration.app.ui.desktop

import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
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
    var showPairingDialog by remember { mutableStateOf(false) }
    var pairingToken by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }

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
                            text = "Send and receive SMS, make calls, and transfer files from your MacBook or PC",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    val token = syncService.generatePairingToken()
                                    pairingToken = token
                                    qrBitmap = generateQRCode(token)
                                    showPairingDialog = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCode, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Pair New Device")
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
                    title = "Open on your computer",
                    description = "Visit syncflow.app on your MacBook or PC browser"
                )
            }

            item {
                InstructionCard(
                    number = "2",
                    title = "Tap 'Pair New Device'",
                    description = "Click the button above to generate a QR code"
                )
            }

            item {
                InstructionCard(
                    number = "3",
                    title = "Scan the QR code",
                    description = "Use your computer's webcam or enter the code manually"
                )
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

            if (isLoading) {
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

    // Pairing Dialog
    if (showPairingDialog) {
        AlertDialog(
            onDismissRequest = { showPairingDialog = false },
            title = { Text("Scan QR Code") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    qrBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .size(250.dp)
                                .padding(16.dp)
                        )
                    }
                    Text(
                        text = "Scan this code on your computer",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Or enter code manually:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = pairingToken,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPairingDialog = false }) {
                    Text("Done")
                }
            }
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

/**
 * Generate QR code bitmap from text
 */
fun generateQRCode(text: String, size: Int = 512): Bitmap {
    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)

    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }

    return bitmap
}
