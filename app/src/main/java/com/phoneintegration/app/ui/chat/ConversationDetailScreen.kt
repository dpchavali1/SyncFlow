package com.phoneintegration.app.ui.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.phoneintegration.app.SmsViewModel
import com.phoneintegration.app.SmsMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailScreen(
    address: String,
    contactName: String,
    viewModel: SmsViewModel,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val messages by viewModel.conversationMessages.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val smart by viewModel.smartReplies.collectAsState()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var selectedMessage by remember { mutableStateOf<SmsMessage?>(null) }
    var showActions by remember { mutableStateOf(false) }

    // Load messages first time
    LaunchedEffect(address) {
        viewModel.loadConversation(address)
    }

    // Scroll to bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch { listState.scrollToItem(0) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contactName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                // Smart Replies
                if (smart.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(6.dp)
                    ) {
                        smart.forEach { suggestion ->
                            AssistChip(
                                onClick = { input = suggestion },
                                label = { Text(suggestion) },
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                    }
                }

                // Input Row
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Write message…") },
                        maxLines = 5,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSend = {
                                if (input.isNotBlank()) {
                                    viewModel.sendSms(address, input) { ok ->
                                        if (ok) input = ""
                                        else Toast.makeText(ctx, "Failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    )

                    IconButton(onClick = {
                        if (input.isNotBlank()) {
                            viewModel.sendSms(address, input) { ok ->
                                if (ok) input = ""
                                else Toast.makeText(ctx, "Failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, null)
                    }
                }
            }
        }
    ) { padding ->

        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            reverseLayout = true,
            state = listState
        ) {
            itemsIndexed(messages) { index, sms ->

                MessageBubble(
                    sms = sms,
                    onLongPress = {
                        selectedMessage = sms
                        showActions = true
                    }
                )

                if (index == messages.lastIndex && hasMore && !isLoadingMore) {
                    viewModel.loadMore()
                }
            }
            if (isLoadingMore) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
    
    // Message Actions Sheet
    if (showActions) {
        val msg = selectedMessage
        if (msg != null) {
            MessageActionsSheet(
                message = msg,
                onDismiss = { showActions = false },
                onDelete = {
                    viewModel.deleteMessage(msg.id) { ok ->
                        Toast.makeText(ctx, if (ok) "Deleted" else "Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}
