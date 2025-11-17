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
    val isDefaultSmsApp = remember { DefaultSmsHelper.isDefaultSmsApp(context) }
    
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
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Default SMS App Section
            if (!isDefaultSmsApp) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Filled.Message,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Set as Default SMS App",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    "Get the full SyncFlow experience",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                activity?.let { DefaultSmsHelper.requestDefaultSmsApp(it) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Set as Default")
                        }
                    }
                }
            }
            
            // App Settings
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
                subtitle = if (prefsManager.requireFingerprint.value) "Fingerprint enabled" else "Not secured",
                onClick = onNavigateToPrivacy
            )
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // Messages Settings
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
                subtitle = "Manage blocked contacts",
                onClick = { /* TODO: Navigate to blocked list */ }
            )
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            // About Section
            SettingsSection("About")
            
            SettingsItem(
                icon = Icons.Filled.Info,
                title = "About SyncFlow",
                subtitle = "Version 2.2 - Default SMS Ready",
                onClick = {
                    // Show about dialog
                }
            )
            
            SettingsItem(
                icon = Icons.Filled.Star,
                title = "Rate Us",
                subtitle = "Rate on Google Play Store",
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("market://details?id=${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                }
            )
            
            SettingsItem(
                icon = Icons.Filled.Share,
                title = "Share App",
                subtitle = "Tell your friends",
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Check out SyncFlow SMS - Modern messaging app! https://play.google.com/store/apps/details?id=${context.packageName}")
                    }
                    context.startActivity(Intent.createChooser(intent, "Share SyncFlow"))
                }
            )
            
            SettingsItem(
                icon = Icons.Filled.BugReport,
                title = "Report a Bug",
                subtitle = "Help us improve",
                onClick = {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:support@syncflow.app")
                        putExtra(Intent.EXTRA_SUBJECT, "Bug Report - SyncFlow SMS")
                    }
                    context.startActivity(intent)
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // App Version
            Text(
                text = "SyncFlow SMS v2.2",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .wrapContentWidth(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
