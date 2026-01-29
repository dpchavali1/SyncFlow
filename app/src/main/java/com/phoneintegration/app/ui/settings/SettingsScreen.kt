package com.phoneintegration.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.MainActivity
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.desktop.DesktopSyncService
import com.phoneintegration.app.utils.DefaultSmsHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefsManager: PreferencesManager,
    onBack: () -> Unit,
    onNavigateToTheme: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToMessages: () -> Unit,
    onNavigateToTemplates: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToDesktop: () -> Unit = {},
    onNavigateToUsage: () -> Unit = {},
    onNavigateToSync: () -> Unit = {},
    onNavigateToSpamFilter: () -> Unit = {},
    onNavigateToSupport: () -> Unit = {},
    onNavigateToFileTransfer: () -> Unit = {},
    onNavigateToDeleteAccount: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    var isDefaultSmsApp by remember { mutableStateOf(false) }
    var showWebAccessDialog by remember { mutableStateOf(false) }
    var isPaired by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isDefaultSmsApp = DefaultSmsHelper.isDefaultSmsApp(context)
        isPaired = DesktopSyncService.hasPairedDevices(context)
    }

    // Web Access Instructions Dialog
    if (showWebAccessDialog) {
        AlertDialog(
            onDismissRequest = { showWebAccessDialog = false },
            icon = { Icon(Icons.Filled.Language, contentDescription = null) },
            title = { Text("Web Access") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Access your messages from any browser:")

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("1. Open ")
                        Text(
                            "https://sfweb.app",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                        )
                    }
                    Text("2. A QR code will be displayed on the website")
                    Text("3. In this app, go to Settings → Pair Device")
                    Text("4. Tap \"Scan QR Code\" and scan the code from your browser")

                    Text(
                        "Tip: Sync your message history first to see older messages on the web.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showWebAccessDialog = false }) {
                    Text("Got it")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {

            // ----------------------------------------------
            // DEFAULT SMS REQUEST CARD
            // ----------------------------------------------
            if (!isDefaultSmsApp) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Filled.Message, contentDescription = null)
                            Column(Modifier.weight(1f)) {
                                Text("Set as Default SMS App")
                                Text("Required to send/receive SMS & MMS fully")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                android.util.Log.d("SettingsScreen", "Set as Default button clicked")
                                android.util.Log.d("SettingsScreen", "Activity: $activity")
                                activity?.requestDefaultSmsAppViaRole()
                            }
                        ) {
                            Text("Set as Default")
                        }
                    }
                }
            }

            // -------------------------
            // Desktop Integration (at the top for visibility)
            // -------------------------
            SettingsSection("Desktop & Web Access")

            SettingsItem(
                icon = Icons.Filled.Computer,
                title = "Pair Device",
                subtitle = "Connect Mac app or Web browser",
                onClick = onNavigateToDesktop
            )

            SettingsItem(
                icon = Icons.Filled.Language,
                title = "Web Access",
                subtitle = "Access messages from any browser",
                onClick = { showWebAccessDialog = true }
            )

            if (isPaired) {
                SettingsItem(
                    icon = Icons.Filled.Sync,
                    title = "Sync Message History",
                    subtitle = "Load older messages to Mac and Web",
                    onClick = onNavigateToSync
                )

                SettingsItem(
                    icon = Icons.Filled.CloudUpload,
                    title = "Send Files to Mac",
                    subtitle = "Share photos, videos, and files",
                    onClick = onNavigateToFileTransfer
                )
            }

            Divider(Modifier.padding(vertical = 8.dp))

            // -------------------------
            // App Settings
            // -------------------------
            SettingsSection("App Settings")

            SettingsItem(
                icon = Icons.Filled.Palette,
                title = "Theme",
                subtitle = if (prefsManager.isAutoTheme.value) "Auto"
                else if (prefsManager.isDarkMode.value) "Dark" else "Light",
                onClick = onNavigateToTheme
            )

            SettingsItem(
                icon = Icons.Filled.Notifications,
                title = "Notifications",
                subtitle = if (prefsManager.notificationsEnabled.value) "Enabled" else "Disabled",
                onClick = onNavigateToNotifications
            )

            SettingsItem(
                icon = Icons.Filled.Wallpaper,
                title = "Appearance",
                subtitle = "Customize chat appearance",
                onClick = onNavigateToAppearance
            )

            SettingsItem(
                icon = Icons.Filled.Lock,
                title = "Privacy & Security",
                subtitle = if (prefsManager.requireFingerprint.value) "Fingerprint Enabled" else "Not Secured",
                onClick = onNavigateToPrivacy
            )

            SettingsItem(
                icon = Icons.Filled.DataUsage,
                title = "Usage & Limits",
                subtitle = "View plan and current usage",
                onClick = onNavigateToUsage
            )

            Divider(Modifier.padding(vertical = 8.dp))

            SettingsSection("Messages")

            SettingsItem(
                icon = Icons.Filled.Settings,
                title = "Message Settings",
                subtitle = "Delivery, timestamps, and more",
                onClick = onNavigateToMessages
            )

            SettingsItem(
                icon = Icons.Filled.Chat,
                title = "Quick Reply Templates",
                subtitle = "${prefsManager.getQuickReplyTemplates().size} templates",
                onClick = onNavigateToTemplates
            )

            SettingsItem(
                icon = Icons.Filled.Archive,
                title = "Backup & Restore",
                subtitle = "Export and import messages",
                onClick = onNavigateToBackup
            )

            SettingsItem(
                icon = Icons.Filled.Block,
                title = "Blocked Numbers",
                subtitle = "Manage blocked numbers",
                onClick = { /* TODO */ }
            )

            SettingsItem(
                icon = Icons.Filled.Shield,
                title = "Spam Filter",
                subtitle = "AI-powered spam detection",
                onClick = onNavigateToSpamFilter
            )

            Divider(Modifier.padding(vertical = 8.dp))

            SettingsSection("Help & Support")

            SettingsItem(
                icon = Icons.Filled.AutoAwesome,
                title = "AI Support Chat",
                subtitle = "Get help from our AI assistant",
                onClick = onNavigateToSupport
            )

            // Only show Account section if user has paired (has an account)
            if (isPaired) {
                Divider(Modifier.padding(vertical = 8.dp))

                SettingsSection("Account")

                SettingsItem(
                    icon = Icons.Filled.DeleteForever,
                    title = "Delete Account",
                    subtitle = "Schedule account for deletion",
                    onClick = onNavigateToDeleteAccount
                )
            }

            Divider(Modifier.padding(vertical = 8.dp))

            SettingsSection("About")

            SettingsItem(
                icon = Icons.Filled.Info,
                title = "About SyncFlow",
                subtitle = "Version 2.2",
                onClick = {}
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
