package com.phoneintegration.app.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
    onBack: () -> Unit
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
    LaunchedEffect(address) {
        viewModel.loadConversation(address)
    }

    // Scroll to bottom on new messages
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
                                    viewModel.sendSms(address, input) { ok ->
                                        if (ok) input = ""
                                        else Toast.makeText(context, "Failed", Toast.LENGTH_SHORT)
                                            .show()
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
                            viewModel.sendSms(address, input) { ok ->
                                if (ok) input = ""
                                else Toast.makeText(context, "Failed", Toast.LENGTH_SHORT).show()
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
