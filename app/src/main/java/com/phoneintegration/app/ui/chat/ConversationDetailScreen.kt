package com.phoneintegration.app.ui.chat

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.phoneintegration.app.SmsReceiver
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.phoneintegration.app.R
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.SmsViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailScreen(
    address: String,
    contactName: String,
    viewModel: SmsViewModel,
    onBack: () -> Unit,
    threadId: Long? = null,  // Optional thread ID for direct loading (used for groups)
    groupMembers: List<String>? = null,  // Optional list of group member names
    groupId: Long? = null,  // Optional group ID for deletion
    onDeleteGroup: ((Long) -> Unit)? = null  // Callback for group deletion
) {
    val context = LocalContext.current
    val messages by viewModel.conversationMessages.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val smart by viewModel.smartReplies.collectAsState()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var selectedMessage by remember { mutableStateOf<SmsMessage?>(null) }
    var showActions by remember { mutableStateOf(false) }

    // FAB only for SyncFlow Deals fake conversation
    val isDealsConversation = address == "syncflow_ads"

    // Attachment state
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var selectedAttachmentUri by remember { mutableStateOf<Uri?>(null) }

    // 🚀 Gallery picker
    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedAttachmentUri = uri
            showAttachmentSheet = false
        }
    }

    // 🚀 Camera capture
    val cameraCapture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            val file = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            }
            selectedAttachmentUri = Uri.fromFile(file)
            showAttachmentSheet = false
        }
    }

    // First time load
    LaunchedEffect(address, threadId) {
        if (threadId != null) {
            // Load by thread ID directly (for groups)
            viewModel.loadConversationByThreadId(threadId, contactName)
        } else {
            // Load by address (for regular conversations)
            viewModel.loadConversation(address)
        }
    }

    // Scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch { listState.scrollToItem(0) }
        }
    }

    // Listen for incoming SMS/MMS broadcasts to refresh this conversation
    DisposableEffect(address, threadId) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val receivedAddress = intent?.getStringExtra(SmsReceiver.EXTRA_ADDRESS)
                Log.d("ConversationDetailScreen", "SMS/MMS received from $receivedAddress - current address: $address")

                // Reload conversation
                // For MMS, we don't have address info, so always reload
                // For SMS, check if it matches current conversation
                if (intent?.action == "com.phoneintegration.app.MMS_RECEIVED" ||
                    receivedAddress == address) {

                    if (threadId != null) {
                        viewModel.loadConversationByThreadId(threadId, contactName)
                    } else {
                        viewModel.loadConversation(address)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(SmsReceiver.SMS_RECEIVED_ACTION)
            addAction("com.phoneintegration.app.MMS_RECEIVED")
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)

        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(contactName)
                        // Show group members if this is a group
                        if (!groupMembers.isNullOrEmpty()) {
                            Text(
                                text = groupMembers.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    // Show delete button for groups
                    if (groupId != null && onDeleteGroup != null) {
                        var showDeleteDialog by remember { mutableStateOf(false) }

                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                painter = painterResource(id = android.R.drawable.ic_menu_delete),
                                contentDescription = "Delete Group",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }

                        if (showDeleteDialog) {
                            AlertDialog(
                                onDismissRequest = { showDeleteDialog = false },
                                title = { Text("Delete Group") },
                                text = { Text("Are you sure you want to delete this group? This will only remove the group from your list, not delete the messages.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showDeleteDialog = false
                                        onDeleteGroup(groupId)
                                    }) {
                                        Text("Delete", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteDialog = false }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }
                    }

                    // Don't show call button for SyncFlow Deals conversation or groups
                    if (!isDealsConversation && groupMembers.isNullOrEmpty()) {
                        IconButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_CALL).apply {
                                    data = Uri.parse("tel:$address")
                                }
                                context.startActivity(intent)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = "Call",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        },

        // ⭐ FAB only for SyncFlow Deals conversation
        floatingActionButton = {
            if (isDealsConversation) {
                DealsRefreshFab(viewModel)
            }
        },

        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                // Smart Replies Row
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
                                    val messageToSend = input
                                    input = "" // Clear immediately to prevent spam
                                    viewModel.sendSms(address, messageToSend) { ok ->
                                        if (!ok) {
                                            Toast.makeText(context, "Failed to send", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        )
                    )

                    // 📎 ATTACH BUTTON
                    IconButton(onClick = { showAttachmentSheet = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_attach),
                            contentDescription = "Attach"
                        )
                    }

                    // Send button
                    IconButton(onClick = {
                        if (input.isNotBlank()) {
                            val messageToSend = input
                            input = "" // Clear immediately to prevent spam
                            viewModel.sendSms(address, messageToSend) { ok ->
                                if (!ok) {
                                    Toast.makeText(context, "Failed to send", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    }
                }
            }
        }
    ) { padding ->

        LazyColumn(
            modifier = Modifier
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
                    },
                    onRetryMms = { failedSms ->
                        viewModel.retryMms(failedSms)
                    }
                )

                if (index == messages.lastIndex && hasMore && !isLoadingMore) {
                    viewModel.loadMore()
                }
            }

            if (isLoadingMore) {
                item {
                    Box(
                        modifier = Modifier
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

    // 📎 Bottom sheet for attachment options
    if (showAttachmentSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachmentSheet = false }) {
            Column(Modifier.padding(16.dp)) {
                Text("Attach Media", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { galleryPicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pick from Gallery")
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { cameraCapture.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Capture Photo")
                }
            }
        }
    }

    // 📸 MMS preview dialog
    if (selectedAttachmentUri != null) {
        AlertDialog(
            onDismissRequest = { selectedAttachmentUri = null },
            title = { Text("Send MMS") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = selectedAttachmentUri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Send this image as MMS?")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.sendMms(address, selectedAttachmentUri!!)
                    selectedAttachmentUri = null
                }) {
                    Text("Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedAttachmentUri = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Long-press actions
    if (showActions) {
        selectedMessage?.let { msg ->
            MessageActionsSheet(
                message = msg,
                onDismiss = { showActions = false },
                onDelete = {
                    viewModel.deleteMessage(msg.id) { ok ->
                        Toast.makeText(
                            context,
                            if (ok) "Deleted" else "Failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }
    }
}


/* -------------------------------------------------------------
   ⭐ Floating Action Button – SyncFlow Deals Pull FAB
------------------------------------------------------------- */
@Composable
fun DealsRefreshFab(viewModel: SmsViewModel) {
    val context = LocalContext.current

    FloatingActionButton(
        onClick = {
            viewModel.refreshDeals { ok ->
                Toast.makeText(
                    context,
                    if (ok) "Deals updated!" else "Failed to refresh",
                    Toast.LENGTH_SHORT
                ).show()
            }
        },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(20.dp),
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 8.dp,
            pressedElevation = 12.dp
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Refresh Deals",
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Pull Deals")
        }
    }
}
