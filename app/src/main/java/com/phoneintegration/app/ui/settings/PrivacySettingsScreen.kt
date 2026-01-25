package com.phoneintegration.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.ContactHelper
import com.phoneintegration.app.SmsRepository
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.data.database.SpamMessage
import com.phoneintegration.app.data.database.SyncFlowDatabase
import com.phoneintegration.app.utils.SpamFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    prefsManager: PreferencesManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Bulk spam scan state
    var showScanDialog by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf(0f) }
    var scanStatus by remember { mutableStateOf("") }
    var scanResults by remember { mutableStateOf<ScanResults?>(null) }

    // AI Features are now handled server-side with Vertex AI

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Security") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Security",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            SwitchCard(
                title = "Require Fingerprint (Coming Soon)",
                subtitle = "Lock app with fingerprint authentication",
                checked = prefsManager.requireFingerprint.value,
                onCheckedChange = { 
                    Toast.makeText(context, 
                        "Fingerprint authentication coming in next update!", 
                        Toast.LENGTH_LONG).show()
                    prefsManager.setRequireFingerprint(it)
                }
            )
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            Text(
                "Privacy",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            SwitchCard(
                title = "Hide Message Preview",
                subtitle = "Don't show message content in recents",
                checked = prefsManager.hideMessagePreview.value,
                onCheckedChange = { 
                    prefsManager.setHideMessagePreview(it)
                    Toast.makeText(context, 
                        if (it) "Message previews hidden" else "Message previews shown", 
                        Toast.LENGTH_SHORT).show()
                }
            )
            
            SwitchCard(
                title = "Incognito Mode",
                subtitle = "Don't save conversation history",
                checked = prefsManager.incognitoMode.value,
                onCheckedChange = {
                    prefsManager.setIncognitoMode(it)
                    Toast.makeText(context,
                        if (it) "Incognito mode enabled" else "Incognito mode disabled",
                        Toast.LENGTH_SHORT).show()
                }
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "Spam Protection",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            SwitchCard(
                title = "Auto Spam Filter",
                subtitle = "Automatically detect and filter spam messages",
                checked = prefsManager.spamFilterEnabled.value,
                onCheckedChange = {
                    prefsManager.setSpamFilterEnabled(it)
                    Toast.makeText(context,
                        if (it) "Spam filter enabled" else "Spam filter disabled",
                        Toast.LENGTH_SHORT).show()
                }
            )

            // Sensitivity selector (only show if spam filter is enabled)
            if (prefsManager.spamFilterEnabled.value) {
                val sensitivityLabels = listOf("Low", "Medium", "High")
                val currentSensitivity = prefsManager.spamFilterSensitivity.value

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Filter Sensitivity",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            when (currentSensitivity) {
                                0 -> "Low: Only catches obvious spam"
                                1 -> "Medium: Balanced detection (recommended)"
                                2 -> "High: Catches more spam but may have false positives"
                                else -> "Medium: Balanced detection"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            sensitivityLabels.forEachIndexed { index, label ->
                                FilterChip(
                                    selected = currentSensitivity == index,
                                    onClick = {
                                        prefsManager.setSpamFilterSensitivity(index)
                                        Toast.makeText(context, "Sensitivity set to $label", Toast.LENGTH_SHORT).show()
                                    },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bulk scan existing messages button
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Scan Existing Messages",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Scan your inbox for spam messages that arrived before enabling the filter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showScanDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan Inbox for Spam")
                        }
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "AI Features",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Smart AI Assistant",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Intelligent message suggestions, conversation summaries, and pattern analysis - all processed locally on your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "✓ 100% Free - No API keys or subscriptions\n✓ Completely private - works offline\n✓ Advanced pattern recognition\n✓ Smart reply suggestions\n✓ Intelligent summaries",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "⚠️ Incognito Mode",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "When enabled, messages won't be saved to your device. SMS messages will still be in your phone's native SMS database.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }

    // Scan confirmation dialog
    if (showScanDialog && !isScanning && scanResults == null) {
        AlertDialog(
            onDismissRequest = { showScanDialog = false },
            title = { Text("Scan Inbox for Spam") },
            text = {
                Text("This will scan all messages in your inbox and move detected spam to the spam folder. Messages from your contacts are less likely to be marked as spam.\n\nThis may take a moment depending on how many messages you have.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        isScanning = true
                        scanProgress = 0f
                        scanStatus = "Preparing..."

                        scope.launch {
                            val results = scanInboxForSpam(
                                context = context,
                                threshold = prefsManager.getSpamThreshold(),
                                onProgress = { progress, status ->
                                    scanProgress = progress
                                    scanStatus = status
                                }
                            )
                            scanResults = results
                            isScanning = false
                        }
                    }
                ) {
                    Text("Start Scan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showScanDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Scanning progress dialog
    if (isScanning) {
        AlertDialog(
            onDismissRequest = { /* Can't dismiss while scanning */ },
            title = { Text("Scanning Messages...") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress = { scanProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        scanStatus,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${(scanProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            },
            confirmButton = { }
        )
    }

    // Scan results dialog
    if (scanResults != null) {
        AlertDialog(
            onDismissRequest = {
                scanResults = null
                showScanDialog = false
            },
            title = { Text("Scan Complete") },
            text = {
                Column {
                    Text(
                        "Scanned ${scanResults!!.totalScanned} messages",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (scanResults!!.spamFound > 0) {
                        Text(
                            "Found ${scanResults!!.spamFound} spam message${if (scanResults!!.spamFound > 1) "s" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Moved to spam folder",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "No spam detected in your inbox!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (scanResults!!.alreadySpam > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${scanResults!!.alreadySpam} message${if (scanResults!!.alreadySpam > 1) "s were" else " was"} already in spam",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scanResults = null
                        showScanDialog = false
                    }
                ) {
                    Text("Done")
                }
            }
        )
    }
}

@Composable
private fun SwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

// Data class to hold scan results
private data class ScanResults(
    val totalScanned: Int,
    val spamFound: Int,
    val alreadySpam: Int
)

// Function to scan inbox for spam
private suspend fun scanInboxForSpam(
    context: android.content.Context,
    threshold: Float,
    onProgress: (Float, String) -> Unit
): ScanResults = withContext(Dispatchers.IO) {
    try {
        val smsRepository = SmsRepository(context)
        val database = SyncFlowDatabase.getInstance(context)
        val spamDao = database.spamMessageDao()
        val contactHelper = try {
            ContactHelper(context)
        } catch (e: Exception) {
            null
        }

        // Get existing spam message IDs to avoid duplicates
        val existingSpamIds = try {
            spamDao.getAllSpamIds().toSet()
        } catch (e: Exception) {
            emptySet()
        }

        var totalScanned = 0
        var spamFound = 0
        var alreadySpam = 0

        // Get all messages (up to 1000 for reasonable scan time)
        withContext(Dispatchers.Main) {
            onProgress(0.05f, "Loading messages...")
        }

        val allMessages = try {
            smsRepository.getAllMessages(limit = 1000)
        } catch (e: Exception) {
            android.util.Log.e("SpamScan", "Failed to load messages", e)
            withContext(Dispatchers.Main) {
                onProgress(1f, "Error loading messages")
            }
            return@withContext ScanResults(0, 0, 0)
        }

        val totalMessages = allMessages.size

        if (totalMessages == 0) {
            withContext(Dispatchers.Main) {
                onProgress(1f, "No messages to scan")
            }
            return@withContext ScanResults(0, 0, 0)
        }

        withContext(Dispatchers.Main) {
            onProgress(0.1f, "Scanning $totalMessages messages...")
        }

        // Process messages - wrap each in try-catch to continue on errors
        val batchSize = 50
        for ((index, message) in allMessages.withIndex()) {
            try {
                // Skip if already in spam
                if (existingSpamIds.contains(message.id)) {
                    alreadySpam++
                    totalScanned++
                } else {
                    // Check if sender is a contact (safely)
                    val contactName = try {
                        contactHelper?.getContactName(message.address)
                    } catch (e: Exception) {
                        null
                    }
                    val isFromContact = contactName != null && contactName != message.address

                    // Run spam check
                    val spamResult = SpamFilter.checkMessage(
                        body = message.body,
                        senderAddress = message.address,
                        isFromContact = isFromContact,
                        threshold = threshold
                    )

                    if (spamResult.isSpam) {
                        // Mark as spam
                        val spamMessage = SpamMessage(
                            messageId = message.id,
                            address = message.address,
                            body = message.body,
                            date = message.date,
                            contactName = contactName,
                            spamConfidence = spamResult.confidence,
                            spamReasons = spamResult.reasons.joinToString(", "),
                            isUserMarked = false
                        )

                        try {
                            spamDao.insert(spamMessage)
                            spamFound++
                        } catch (e: Exception) {
                            // Ignore insert errors (might be duplicate)
                        }
                        // Note: Desktop sync skipped during bulk scan for performance
                        // Spam will sync on next regular sync cycle
                    }
                    totalScanned++
                }
            } catch (e: Exception) {
                // Log but continue scanning
                android.util.Log.e("SpamScan", "Error processing message ${message.id}", e)
                totalScanned++
            }

            // Update progress every batch
            if (index % batchSize == 0 || index == totalMessages - 1) {
                val progress = 0.1f + (0.9f * (index + 1).toFloat() / totalMessages)
                try {
                    withContext(Dispatchers.Main) {
                        onProgress(progress, "Scanned ${index + 1} of $totalMessages messages...")
                    }
                } catch (e: Exception) {
                    // Ignore UI update errors
                }
            }
        }

        withContext(Dispatchers.Main) {
            onProgress(1f, "Scan complete!")
        }

        ScanResults(
            totalScanned = totalScanned,
            spamFound = spamFound,
            alreadySpam = alreadySpam
        )
    } catch (e: Exception) {
        android.util.Log.e("SpamScan", "Scan failed", e)
        withContext(Dispatchers.Main) {
            onProgress(1f, "Scan failed: ${e.message}")
        }
        ScanResults(0, 0, 0)
    }
}
