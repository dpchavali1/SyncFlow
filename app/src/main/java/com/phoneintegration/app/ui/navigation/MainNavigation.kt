package com.phoneintegration.app.ui.navigation

import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.phoneintegration.app.SmsViewModel
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.ui.conversations.ConversationListScreen
import com.phoneintegration.app.ui.conversations.NewConversationScreen
import com.phoneintegration.app.ui.conversations.NewMessageComposeScreen
import com.phoneintegration.app.ui.conversations.CreateGroupNameScreen
import com.phoneintegration.app.ui.conversations.ContactInfo
import com.phoneintegration.app.ui.chat.ConversationDetailScreen
import com.phoneintegration.app.ui.stats.MessageStatsScreen
import com.phoneintegration.app.ui.settings.*
import com.phoneintegration.app.ui.splash.SplashScreen
import com.phoneintegration.app.ui.conversations.AdConversationScreen
import com.phoneintegration.app.ui.desktop.DesktopIntegrationScreen
import com.phoneintegration.app.ui.ai.AIAssistantScreen
import com.phoneintegration.app.data.GroupRepository
import com.phoneintegration.app.SmsMessage
import com.phoneintegration.app.SmsRepository
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import android.util.Log


@Composable
fun MainNavigation(
    viewModel: SmsViewModel,
    preferencesManager: PreferencesManager
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val groupRepository = remember { GroupRepository(context) }
    val scope = rememberCoroutineScope()

    var showSplash by remember { mutableStateOf(true) }
    var selectedContacts by remember { mutableStateOf<List<ContactInfo>>(emptyList()) }

    if (showSplash) {
        SplashScreen(onSplashComplete = { showSplash = false })
    } else {
        NavHost(navController = navController, startDestination = "list") {

            composable("list") {
                // Collect conversations to check if clicked item is a group
                val conversations by viewModel.conversations.collectAsState()

                ConversationListScreen(
                    viewModel = viewModel,
                    onOpen = { address: String, name: String ->
                        // Find the conversation to check if it's a group
                        val conversation = conversations.find {
                            it.address == address && it.contactName == name
                        }

                        when {
                            address == "syncflow_ads" -> {
                                // Open Ads screen
                                navController.navigate("ads")
                            }
                            conversation?.isGroupConversation == true && conversation.groupId != null -> {
                                // Open saved group chat
                                navController.navigate("groupChat/${conversation.groupId}")
                            }
                            else -> {
                                // Open normal SMS chat
                                navController.navigate("chat/$address/$name")
                            }
                        }
                    },
                    onOpenStats = {
                        navController.navigate("stats")
                    },
                    onOpenSettings = {
                        navController.navigate("settings")
                    },
                    onNewMessage = {
                        navController.navigate("newConversation")
                    },
                    onOpenAI = {
                        navController.navigate("ai")
                    }
                )
            }

            composable("chat/{address}/{name}") { backStackEntry ->
                val address = backStackEntry.arguments?.getString("address") ?: ""
                val name = backStackEntry.arguments?.getString("name") ?: address

                ConversationDetailScreen(
                    address = address,
                    contactName = name,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable("stats") {
                MessageStatsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable("ai") {
                var allMessages by remember { mutableStateOf<List<SmsMessage>>(emptyList()) }

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        val repo = SmsRepository(context)
                        val messages = repo.getAllMessages(limit = 500)
                        withContext(Dispatchers.Main) {
                            allMessages = messages
                        }
                    }
                }

                AIAssistantScreen(
                    messages = allMessages,
                    onDismiss = { navController.popBackStack() }
                )
            }
            
            // Settings Navigation
            composable("settings") {
                SettingsScreen(
                    prefsManager = preferencesManager,
                    onBack = { navController.popBackStack() },
                    onNavigateToTheme = { navController.navigate("settings/theme") },
                    onNavigateToNotifications = { navController.navigate("settings/notifications") },
                    onNavigateToAppearance = { navController.navigate("settings/appearance") },
                    onNavigateToPrivacy = { navController.navigate("settings/privacy") },
                    onNavigateToMessages = { navController.navigate("settings/messages") },
                    onNavigateToTemplates = { navController.navigate("settings/templates") },
                    onNavigateToBackup = { navController.navigate("settings/backup") },
                    onNavigateToDesktop = { navController.navigate("settings/desktop") }
                )
            }
            
            composable("settings/theme") {
                ThemeSettingsScreen(
                    prefsManager = preferencesManager,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable("settings/notifications") {
                NotificationSettingsScreen(
                    prefsManager = preferencesManager,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable("settings/appearance") {
                AppearanceSettingsScreen(
                    prefsManager = preferencesManager,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable("settings/privacy") {
                PrivacySettingsScreen(
                    prefsManager = preferencesManager,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable("settings/messages") {
                MessageSettingsScreen(
                    prefsManager = preferencesManager,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable("settings/templates") {
                QuickReplyTemplatesScreen(
                    prefsManager = preferencesManager,
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable("settings/backup") {
                BackupScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("settings/desktop") {
                DesktopIntegrationScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // Ads Conversation Screen
            composable("ads") {
                AdConversationScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // New Conversation - Contact Picker
            composable("newConversation") {
                NewConversationScreen(
                    onBack = { navController.popBackStack() },
                    onContactsSelected = { contacts ->
                        selectedContacts = contacts
                        navController.navigate("newMessageCompose")
                    },
                    onCreateGroup = { contacts ->
                        selectedContacts = contacts
                        navController.navigate("createGroupName")
                    }
                )
            }

            // Create Group - Name Input
            composable("createGroupName") {
                if (selectedContacts.isNotEmpty()) {
                    CreateGroupNameScreen(
                        selectedContacts = selectedContacts,
                        onBack = { navController.popBackStack() },
                        onCreateGroup = { groupName ->
                            // Create the group in database
                            scope.launch {
                                try {
                                    val groupId = withContext(Dispatchers.IO) {
                                        groupRepository.createGroup(
                                            name = groupName,
                                            members = selectedContacts
                                        )
                                    }

                                    // Navigate to group chat (on Main thread)
                                    navController.navigate("groupChat/$groupId") {
                                        popUpTo("list") { inclusive = false }
                                    }
                                } catch (e: Exception) {
                                    // Show toast on Main thread
                                    Toast.makeText(
                                        context,
                                        "Failed to create group: ${e.message}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }
            }

            // New Message Compose
            composable("newMessageCompose") {
                if (selectedContacts.isNotEmpty()) {
                    NewMessageComposeScreen(
                        contacts = selectedContacts,
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onMessageSent = {
                            // Go back to conversation list
                            navController.popBackStack("list", inclusive = false)
                        }
                    )
                }
            }

            // Group Chat
            composable("groupChat/{groupId}") { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId")?.toLongOrNull() ?: 0L

                var groupWithMembers by remember { mutableStateOf<com.phoneintegration.app.data.database.GroupWithMembers?>(null) }

                LaunchedEffect(groupId) {
                    Log.d("MainNavigation", "=== LOADING GROUP CHAT ===")
                    Log.d("MainNavigation", "Group ID: $groupId")
                    groupWithMembers = groupRepository.getGroupWithMembers(groupId)
                    Log.d("MainNavigation", "Group loaded: ${groupWithMembers?.group?.name}")
                    Log.d("MainNavigation", "Group thread ID: ${groupWithMembers?.group?.threadId}")
                    Log.d("MainNavigation", "Group members: ${groupWithMembers?.members?.size}")
                }

                groupWithMembers?.let { group ->
                    val contacts = group.members.map {
                        ContactInfo(
                            name = it.contactName ?: it.phoneNumber,
                            phoneNumber = it.phoneNumber
                        )
                    }

                    // If group has a thread ID, it means messages have been sent
                    // Use ConversationDetailScreen to show message history
                    if (group.group.threadId != null) {
                        // Load by thread ID directly
                        val memberNames = contacts.map { it.name }
                        ConversationDetailScreen(
                            address = group.group.name,
                            contactName = group.group.name,
                            viewModel = viewModel,
                            onBack = {
                                navController.popBackStack("list", inclusive = false)
                            },
                            threadId = group.group.threadId,
                            groupMembers = memberNames,
                            groupId = groupId,
                            onDeleteGroup = { id ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        groupRepository.deleteGroup(id)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Group deleted", Toast.LENGTH_SHORT).show()
                                            navController.popBackStack("list", inclusive = false)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                "Failed to delete group: ${e.message}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            }
                        )
                    } else {
                        // New group without messages - use compose screen
                        NewMessageComposeScreen(
                            contacts = contacts,
                            viewModel = viewModel,
                            onBack = {
                                navController.popBackStack("list", inclusive = false)
                            },
                            onMessageSent = {
                                // Update group's last message timestamp
                                scope.launch(Dispatchers.IO) {
                                    groupRepository.updateLastMessage(groupId)
                                }
                                navController.popBackStack("list", inclusive = false)
                            },
                            groupName = group.group.name,
                            groupId = groupId
                        )
                    }
                }
            }
        }
    }
}
