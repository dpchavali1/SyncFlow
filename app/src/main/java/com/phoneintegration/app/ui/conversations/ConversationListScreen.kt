package com.phoneintegration.app.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.phoneintegration.app.SmsViewModel
import com.phoneintegration.app.ConversationInfo
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    viewModel: SmsViewModel,
    onOpen: (address: String, name: String) -> Unit,
    onOpenStats: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val conversations by viewModel.conversations.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var query by remember { mutableStateOf(TextFieldValue("")) }

    LaunchedEffect(Unit) { viewModel.loadConversations() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SyncFlow") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onOpenStats,
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.Analytics,
                    contentDescription = "Statistics"
                )
            }
        }
    ) { padding ->

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                )

                val filtered = remember(conversations, query.text) {
                    val q = query.text.lowercase()
                    if (q.isBlank()) conversations
                    else conversations.filter {
                        it.address.lowercase().contains(q) ||
                                (it.contactName?.lowercase()?.contains(q) == true) ||
                                it.lastMessage.lowercase().contains(q)
                    }
                }

                if (isLoading && conversations.isEmpty()) {
                    // Show loading only on first load
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Text("No conversations", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    LazyColumn {
                        items(filtered) { convo ->
                            ConversationListItem(info = convo) {
                                val name = convo.contactName ?: convo.address
                                if (convo.isAdConversation) {
                                    // Navigate to Ads screen instead of opening SMS thread
                                    onOpen("syncflow_ads", "SyncFlow Deals")
                                } else {
                                    val name = convo.contactName ?: convo.address
                                    onOpen(convo.address, name)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConversationListItem(
    info: ConversationInfo,
    onOpen: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {

            // -------------------------------
            // CONTACT PHOTO (OPTION A)
            // -------------------------------
            if (info.photoUri != null) {
                AsyncImage(
                    model = info.photoUri,
                    contentDescription = "Contact photo",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                )
            } else {
                // Fallback circle with first letter
                val label = (info.contactName ?: info.address).firstOrNull()?.uppercase() ?: "?"

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // -------------------------------
            // NAME + LAST MESSAGE
            // -------------------------------
            Column {
                Text(
                    text = info.contactName ?: info.address,
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = info.lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
        }
    }
}
