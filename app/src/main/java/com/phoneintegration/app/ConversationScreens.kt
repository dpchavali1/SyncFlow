package com.phoneintegration.app

import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    viewModel: SmsViewModel = viewModel(),
    onNavigateToConversation: (String, String) -> Unit,
    onNavigateToSend: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val categoryStats by viewModel.categoryStats.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadConversations()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SyncFlow", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White) },
                actions = {
                    IconButton(onClick = { viewModel.loadConversations(); viewModel.loadMessages() }) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF075E54))
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToSend, containerColor = Color(0xFF25D366)) {
                Icon(Icons.Default.Add, "New Message", tint = Color.White)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (categoryStats.isNotEmpty()) {
                Surface(Modifier.fillMaxWidth(), color = Color(0xFFF8F9FA), shadowElevation = 2.dp) {
                    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { viewModel.setCategory(null) },
                                label = { Text("All") },
                                leadingIcon = { Text("📱") }
                            )
                        }
                        items(MessageCategory.values().toList()) { category ->
                            val count = categoryStats[category] ?: 0
                            if (count > 0) {
                                FilterChip(
                                    selected = selectedCategory == category,
                                    onClick = { viewModel.setCategory(if (selectedCategory == category) null else category) },
                                    label = { Text("${category.displayName} ($count)") },
                                    leadingIcon = { Text(category.emoji) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(category.color).copy(alpha = 0.2f),
                                        selectedLabelColor = Color(category.color)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Box(Modifier.fillMaxSize().background(Color.White)) {
                when {
                    isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFF25D366))
                    conversations.isEmpty() -> {
                        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Box(Modifier.size(120.dp).clip(CircleShape).background(Color(0xFFDCF8C6)), contentAlignment = Alignment.Center) {
                                Text("💬", style = MaterialTheme.typography.displayLarge, fontSize = 60.sp)
                            }
                            Spacer(Modifier.height(24.dp))
                            Text("No Conversations", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Tap + to start a new conversation!", textAlign = TextAlign.Center)
                        }
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(conversations) { conversation ->
                                var showDialog by remember { mutableStateOf(false) }
                                val context = LocalContext.current

                                SwipeableConversationItem(
                                    conversation = conversation,
                                    onClick = { onNavigateToConversation(conversation.address, conversation.getDisplayName()) },
                                    onDelete = { showDialog = true },
                                    onCall = {
                                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${conversation.address}")))
                                        Toast.makeText(context, "📞 Calling...", Toast.LENGTH_SHORT).show()
                                    }
                                )

                                if (showDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showDialog = false },
                                        title = { Text("Hide Conversation?") },
                                        text = { Text("Hide ${conversation.getDisplayName()}?") },
                                        confirmButton = {
                                            Button(onClick = { viewModel.hideConversation(conversation.address); showDialog = false }) {
                                                Text("Hide")
                                            }
                                        },
                                        dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableConversationItem(conversation: Conversation, onClick: () -> Unit, onDelete: () -> Unit, onCall: () -> Unit) {
    var offsetX by remember { mutableStateOf(0f) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(20.dp)), horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.width(150.dp).fillMaxHeight().background(Color(0xFF25D366)).clickable { onCall(); offsetX = 0f }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Phone, "Call", tint = Color.White, modifier = Modifier.size(32.dp))
                    Text("Call", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(150.dp).fillMaxHeight().background(Color(0xFFDC3545)).clickable { onDelete(); offsetX = 0f }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Delete, "Delete", tint = Color.White, modifier = Modifier.size(32.dp))
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(
            Modifier.fillMaxWidth().offset { IntOffset(offsetX.toInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { offsetX = when { offsetX > 100f -> 300f; offsetX < -100f -> -300f; else -> 0f } },
                        onHorizontalDrag = { _, drag -> offsetX = (offsetX + drag).coerceIn(-300f, 300f) }
                    )
                }
                .clickable { if (offsetX == 0f) onClick() else offsetX = 0f },
            elevation = CardDefaults.cardElevation(4.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(Modifier.fillMaxWidth().height(88.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).border(2.dp, Color(0xFF25D366), CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF075E54), Color(0xFF128C7E)))), contentAlignment = Alignment.Center) {
                    Text(conversation.getDisplayName().first().uppercase(), style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(conversation.getDisplayName(), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(conversation.getFormattedTime(), fontSize = 12.sp, color = Color(0xFF667781))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (conversation.lastMessage.type == 2) {
                            Icon(Icons.Default.Done, "Sent", tint = Color(0xFF53BDEB), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(conversation.getLastMessagePreview(), fontSize = 14.sp, color = Color(0xFF667781), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (conversation.messages.size > 1) {
                    Box(Modifier.clip(CircleShape).background(Color(0xFF25D366)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("${conversation.messages.size}", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailScreen(address: String, contactName: String, viewModel: SmsViewModel = viewModel(), onBack: () -> Unit) {
    val messages by viewModel.conversationMessages.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val smartReplies by viewModel.smartReplies.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var messageText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(address) { viewModel.loadConversation(address) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) scope.launch { listState.animateScrollToItem(0) } }
    LaunchedEffect(messages) { messages.lastOrNull()?.let { if (it.type == 1) viewModel.generateSmartReplies(messages) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF075E54), Color(0xFF128C7E)))), contentAlignment = Alignment.Center) {
                            Text(contactName.first().uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(contactName, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                            Text(address, fontSize = 12.sp, color = Color.White.copy(0.7f))
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF075E54))
            )
        },
        bottomBar = {
            Column {
                if (smartReplies.isNotEmpty() && messageText.isEmpty()) {
                    Surface(Modifier.fillMaxWidth(), color = Color(0xFFF0F2F5)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("✨ Smart Reply", fontWeight = FontWeight.Bold, color = Color(0xFF25D366))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                smartReplies.take(3).forEach { reply ->
                                    OutlinedButton(onClick = { messageText = reply; viewModel.clearSmartReplies() }, Modifier.weight(1f, false),
                                        border = BorderStroke(1.dp, Color(0xFF25D366))) {
                                        Text(reply, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                if (messageText.isEmpty() && smartReplies.isEmpty()) {
                    TextButton(onClick = { showTemplates = true }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.List, null, tint = Color(0xFF25D366))
                        Text("Templates", color = Color(0xFF25D366))
                    }
                }
                Surface(Modifier.fillMaxWidth(), shadowElevation = 8.dp, color = Color(0xFFF0F2F5)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(messageText, { messageText = it }, Modifier.weight(1f).heightIn(min = 48.dp), placeholder = { Text("Message") },
                            shape = RoundedCornerShape(24.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent))
                        Spacer(Modifier.width(8.dp))
                        FloatingActionButton(
                            onClick = {
                                if (messageText.isNotBlank() && !isSending) {
                                    isSending = true
                                    try {
                                        SmsManager.getDefault().sendTextMessage(address, null, messageText, null, null)
                                        Toast.makeText(context, "✅ Sent!", Toast.LENGTH_SHORT).show()
                                        viewModel.addOptimisticMessage(SmsMessage(System.currentTimeMillis(), address, messageText, System.currentTimeMillis(), 2, contactName))
                                        messageText = ""
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "❌ Failed", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isSending = false
                                    }
                                }
                            },
                            containerColor = Color(0xFF25D366), modifier = Modifier.size(48.dp)
                        ) {
                            if (isSending) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
                            else Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White)
                        }
                    }
                }
            }
            if (showTemplates) {
                TemplatePickerDialog(onDismiss = { showTemplates = false }, onTemplateSelected = { messageText = it.message; showTemplates = false })
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(Color(0xFFECE5DD))) {
            when {
                isLoading && messages.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                messages.isEmpty() -> {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("💬", fontSize = 60.sp)
                        Text("Start Chatting", fontWeight = FontWeight.Bold)
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        reverseLayout = true
                    ) {
                        items(messages.reversed()) { MessageBubble(it) }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: SmsMessage) {
    val isSent = message.type == 2
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(8.dp, 2.dp), horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start) {
        Card(Modifier.widthIn(max = 280.dp), shape = RoundedCornerShape(topStart = if (isSent) 16.dp else 2.dp, topEnd = if (isSent) 2.dp else 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            colors = CardDefaults.cardColors(containerColor = if (isSent) Color(0xFFDCF8C6) else Color.White)) {
            Column(Modifier.padding(10.dp)) {
                Text(message.body, fontSize = 15.sp)
                if (message.category == MessageCategory.OTP && message.otpInfo != null) {
                    OutlinedButton(onClick = {
                        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("OTP", message.otpInfo!!.code))
                        Toast.makeText(context, "🔒 Copied", Toast.LENGTH_SHORT).show()
                    }, Modifier.fillMaxWidth()) {
                        Text("Copy OTP: ${message.otpInfo!!.code}")
                    }
                }
                Row(Modifier.align(Alignment.End)) {
                    Text(message.getFormattedTime(), fontSize = 11.sp, color = Color(0xFF667781))
                    if (isSent) Text(" ✓✓", color = Color(0xFF53BDEB))
                }
            }
        }
    }
}

@Composable
fun TemplatePickerDialog(onDismiss: () -> Unit, onTemplateSelected: (MessageTemplate) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth().heightIn(max = 600.dp), shape = RoundedCornerShape(28.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().background(Color(0xFF075E54)).padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("⚡ Templates", fontWeight = FontWeight.Bold, color = Color.White)
                    IconButton(onClick = onDismiss) { Text("✕", color = Color.White) }
                }
                LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    items(TemplateManager.getDefaultTemplates()) { template ->
                        Card(Modifier.fillMaxWidth().clickable { onTemplateSelected(template) }.padding(vertical = 4.dp)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF25D366)), contentAlignment = Alignment.Center) {
                                    Text(template.emoji)
                                }
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(template.title, fontWeight = FontWeight.Bold)
                                    Text(template.message, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}