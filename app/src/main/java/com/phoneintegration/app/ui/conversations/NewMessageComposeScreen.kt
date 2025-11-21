package com.phoneintegration.app.ui.conversations

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.phoneintegration.app.MmsHelper
import com.phoneintegration.app.SmsViewModel
import com.phoneintegration.app.data.GroupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageComposeScreen(
    contacts: List<ContactInfo>,
    viewModel: SmsViewModel,
    onBack: () -> Unit,
    onMessageSent: () -> Unit,
    groupName: String? = null,  // Optional group name
    groupId: Long? = null  // Optional group ID for linking to MMS threads
) {
    val context = LocalContext.current
    val groupRepository = remember { GroupRepository(context) }
    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var isSending by remember { mutableStateOf(false) }

    val isGroupMessage = contacts.size > 1

    // Image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedImageUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = groupName ?: if (contacts.size == 1) {
                                    contacts.first().name
                                } else {
                                    "Group Message"
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (isGroupMessage || groupName != null) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.Groups,
                                    contentDescription = "Group",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Text(
                            text = if (contacts.size == 1) {
                                contacts.first().phoneNumber
                            } else {
                                "${contacts.size} members"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                },
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
        ) {
            // Recipients chips (for group messages)
            if (contacts.size > 1) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Recipients",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(contacts) { contact ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(contact.name) },
                                    leadingIcon = {
                                        if (contact.photoUri != null) {
                                            AsyncImage(
                                                model = contact.photoUri,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = contact.name.firstOrNull()?.uppercase() ?: "?",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Image attachment preview
            if (selectedImageUri != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box {
                        AsyncImage(
                            model = selectedImageUri,
                            contentDescription = "Selected image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        )
                        IconButton(
                            onClick = { selectedImageUri = null },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove image",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Message input area
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Attach button
                    IconButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        enabled = !isSending
                    ) {
                        Icon(Icons.Default.AttachFile, "Attach")
                    }

                    // Message input
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Write message...") },
                        maxLines = 5,
                        enabled = !isSending
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Send button
                    IconButton(
                        onClick = {
                            if (messageText.text.isNotBlank() || selectedImageUri != null) {
                                isSending = true

                                if (isGroupMessage) {
                                    // Send group MMS
                                    Log.d("NewMessageCompose", "=== SENDING GROUP MESSAGE ===")
                                    Log.d("NewMessageCompose", "Group ID: $groupId")
                                    Log.d("NewMessageCompose", "Recipients: ${contacts.map { it.phoneNumber }}")
                                    Log.d("NewMessageCompose", "Message: ${messageText.text}")
                                    Log.d("NewMessageCompose", "Has attachment: ${selectedImageUri != null}")

                                    sendGroupMessage(
                                        context = context,
                                        contacts = contacts,
                                        message = messageText.text,
                                        imageUri = selectedImageUri,
                                        onSuccess = { threadId ->
                                            Log.d("NewMessageCompose", "=== GROUP MESSAGE SEND SUCCESS ===")
                                            Log.d("NewMessageCompose", "Received thread ID: $threadId")

                                            // Update group's thread ID if this is a saved group
                                            if (groupId != null && threadId != null) {
                                                CoroutineScope(Dispatchers.IO).launch {
                                                    try {
                                                        Log.d("NewMessageCompose", "Updating group $groupId with threadId $threadId")
                                                        groupRepository.updateGroupThreadId(groupId, threadId)

                                                        // Verify it was saved
                                                        val updatedGroup = groupRepository.getGroupById(groupId)
                                                        Log.d("NewMessageCompose", "Group after update - threadId: ${updatedGroup?.threadId}")

                                                        // Wait for MMS to be inserted into database
                                                        Log.d("NewMessageCompose", "Waiting 3 seconds for MMS insertion...")
                                                        kotlinx.coroutines.delay(3000)

                                                        // Navigate back on main thread
                                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                            Log.d("NewMessageCompose", "Navigating back to conversation list")
                                                            Toast.makeText(
                                                                context,
                                                                "Group message sent to ${contacts.size} recipients",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                            onMessageSent()
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e("NewMessageCompose", "Failed to update group thread ID", e)
                                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                            Toast.makeText(
                                                                context,
                                                                "Group message sent to ${contacts.size} recipients",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                            onMessageSent()
                                                        }
                                                    }
                                                }
                                            } else {
                                                Log.d("NewMessageCompose", "No group ID or thread ID - groupId=$groupId, threadId=$threadId")
                                                Toast.makeText(
                                                    context,
                                                    "Group message sent to ${contacts.size} recipients",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                onMessageSent()
                                            }
                                        },
                                        onError = { error ->
                                            Log.e("NewMessageCompose", "Group message send error: $error")
                                            Toast.makeText(
                                                context,
                                                "Error: $error",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            isSending = false
                                        }
                                    )
                                } else {
                                    // Send single message
                                    val recipient = contacts.first()
                                    if (selectedImageUri != null) {
                                        // Send MMS
                                        val success = MmsHelper.sendMms(
                                            context,
                                            recipient.phoneNumber,
                                            selectedImageUri!!,
                                            messageText.text.ifBlank { null }
                                        )
                                        if (success) {
                                            Toast.makeText(context, "MMS sent", Toast.LENGTH_SHORT).show()
                                            onMessageSent()
                                        } else {
                                            Toast.makeText(context, "MMS failed", Toast.LENGTH_SHORT).show()
                                            isSending = false
                                        }
                                    } else {
                                        // Send SMS
                                        viewModel.sendSms(recipient.phoneNumber, messageText.text) { success ->
                                            if (success) {
                                                Toast.makeText(context, "Message sent", Toast.LENGTH_SHORT).show()
                                                onMessageSent()
                                            } else {
                                                Toast.makeText(context, "Failed to send", Toast.LENGTH_SHORT).show()
                                                isSending = false
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isSending && (messageText.text.isNotBlank() || selectedImageUri != null)
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
                    }
                }
            }
        }
    }
}

fun sendGroupMessage(
    context: android.content.Context,
    contacts: List<ContactInfo>,
    message: String,
    imageUri: Uri?,
    onSuccess: (threadId: Long?) -> Unit,
    onError: (String) -> Unit
) {
    try {
        Log.d("sendGroupMessage", "=== SENDING GROUP MMS ===")
        Log.d("sendGroupMessage", "Recipients count: ${contacts.size}")

        // Extract phone numbers
        val recipients = contacts.map { it.phoneNumber }
        Log.d("sendGroupMessage", "Phone numbers: ${recipients.joinToString(", ")}")

        // Get or create thread ID for this group
        val threadId = try {
            val id = android.provider.Telephony.Threads.getOrCreateThreadId(
                context,
                recipients.toSet()
            )
            Log.d("sendGroupMessage", "Got thread ID: $id")
            id
        } catch (e: Exception) {
            Log.e("sendGroupMessage", "Failed to get thread ID", e)
            null
        }

        Log.d("sendGroupMessage", "Calling MmsHelper.sendGroupMms...")
        // Use the new sendGroupMms function
        val success = MmsHelper.sendGroupMms(
            ctx = context,
            recipients = recipients,
            messageText = message.ifBlank { null },
            imageUri = imageUri
        )

        Log.d("sendGroupMessage", "MmsHelper.sendGroupMms returned: $success")

        if (success) {
            Log.d("sendGroupMessage", "Calling onSuccess with threadId: $threadId")
            onSuccess(threadId)
        } else {
            Log.e("sendGroupMessage", "Group message send failed")
            onError("Group message send failed")
        }
    } catch (e: Exception) {
        Log.e("sendGroupMessage", "Exception during group send", e)
        onError(e.message ?: "Unknown error")
    }
}
