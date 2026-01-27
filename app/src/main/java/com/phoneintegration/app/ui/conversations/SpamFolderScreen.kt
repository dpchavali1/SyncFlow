package com.phoneintegration.app.ui.conversations

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.data.database.AppDatabase
import com.phoneintegration.app.data.database.SpamMessage
import com.phoneintegration.app.ui.components.SyncFlowAvatar
import com.phoneintegration.app.ui.components.AvatarSize
import com.phoneintegration.app.ui.components.SyncFlowEmptyState
import com.phoneintegration.app.ui.components.EmptyStateType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpamFolderScreen(
    onBack: () -> Unit,
    onOpenConversation: (address: String, name: String) -> Unit = { _, _ -> },
    viewModel: com.phoneintegration.app.SmsViewModel? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { AppDatabase.getInstance(context) }
    val syncService = remember { com.phoneintegration.app.desktop.DesktopSyncService(context.applicationContext) }

    // Refresh spam from cloud when screen opens
    LaunchedEffect(Unit) {
        viewModel?.refreshSpamFromCloud()
    }

    val spamMessages by database.spamMessageDao().getAllSpam().collectAsState(initial = emptyList())
    var showClearAllDialog by remember { mutableStateOf(false) }

    // Group spam by address
    val groupedSpam = remember(spamMessages) {
        spamMessages.groupBy { it.address }
            .map { (address, messages) ->
                SpamConversation(
                    address = address,
                    contactName = messages.firstOrNull()?.contactName ?: address,
                    latestMessage = messages.maxByOrNull { it.date },
                    messageCount = messages.size
                )
            }
            .sortedByDescending { it.latestMessage?.date ?: 0 }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Spam")
                        if (spamMessages.isNotEmpty()) {
                            Text(
                                "${spamMessages.size} messages",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (spamMessages.isNotEmpty()) {
                        IconButton(onClick = { showClearAllDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, "Clear all spam")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (groupedSpam.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "🎉",
                        style = MaterialTheme.typography.displayLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No spam messages",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Messages identified as spam will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(groupedSpam, key = { it.address }) { conversation ->
                    SpamConversationItem(
                        conversation = conversation,
                        onRestore = {
                            scope.launch {
                                val ids = spamMessages
                                    .filter { it.address == conversation.address }
                                    .map { it.messageId }
                                database.spamMessageDao().deleteByAddress(conversation.address)
                                // Only sync to cloud if devices are paired
                                if (com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(context)) {
                                    ids.forEach { syncService.deleteSpamMessage(it) }
                                }
                                Toast.makeText(context, "Restored from spam", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = {
                            scope.launch {
                                val ids = spamMessages
                                    .filter { it.address == conversation.address }
                                    .map { it.messageId }
                                database.spamMessageDao().deleteByAddress(conversation.address)
                                // Only sync to cloud if devices are paired
                                if (com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(context)) {
                                    ids.forEach { syncService.deleteSpamMessage(it) }
                                }
                                Toast.makeText(context, "Deleted permanently", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }

    // Clear All Dialog
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear All Spam") },
            text = { Text("This will permanently delete all ${spamMessages.size} spam messages. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            database.spamMessageDao().clearAll()
                            // Only sync to cloud if devices are paired
                            if (com.phoneintegration.app.desktop.DesktopSyncService.hasPairedDevices(context)) {
                                syncService.clearAllSpamMessages()
                            }
                            Toast.makeText(context, "All spam cleared", Toast.LENGTH_SHORT).show()
                        }
                        showClearAllDialog = false
                    }
                ) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private data class SpamConversation(
    val address: String,
    val contactName: String,
    val latestMessage: SpamMessage?,
    val messageCount: Int
)

@Composable
private fun SpamConversationItem(
    conversation: SpamConversation,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(
                conversation.contactName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                Text(
                    conversation.latestMessage?.body ?: "",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "${conversation.messageCount} message${if (conversation.messageCount > 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        leadingContent = {
            SyncFlowAvatar(
                name = conversation.contactName,
                size = AvatarSize.Medium
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRestore) {
                    Icon(
                        Icons.Default.Restore,
                        "Not spam",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        modifier = Modifier.clickable { showActions = !showActions }
    )
    HorizontalDivider()
}
