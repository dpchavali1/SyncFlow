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
    onNavigateToBackup: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    var isDefaultSmsApp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isDefaultSmsApp = DefaultSmsHelper.isDefaultSmsApp(context)
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
            // Rest of your settings list
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

            Divider(Modifier.padding(vertical = 8.dp))

            SettingsSection("Desktop Integration")

            SettingsItem(
                icon = Icons.Filled.Computer,
                title = "Messages for Web",
                subtitle = "Use messages on your computer",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://messages.google.com/web")
                    }
                    context.startActivity(intent)
                }
            )

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
