package com.phoneintegration.app.ui.navigation

import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.phoneintegration.app.SmsViewModel
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.ui.conversations.ConversationListScreen
import com.phoneintegration.app.ui.chat.ConversationDetailScreen
import com.phoneintegration.app.ui.stats.MessageStatsScreen
import com.phoneintegration.app.ui.settings.*
import com.phoneintegration.app.ui.splash.SplashScreen

@Composable
fun MainNavigation(
    viewModel: SmsViewModel,
    preferencesManager: PreferencesManager
) {
    val navController = rememberNavController()
    var showSplash by remember { mutableStateOf(true) }

    if (showSplash) {
        SplashScreen(onSplashComplete = { showSplash = false })
    } else {
        NavHost(navController = navController, startDestination = "list") {

            composable("list") {
                ConversationListScreen(
                    viewModel = viewModel,
                    onOpen = { address: String, name: String ->
                        navController.navigate("chat/$address/$name")
                    },
                    onOpenStats = {
                        navController.navigate("stats")
                    },
                    onOpenSettings = {
                        navController.navigate("settings")
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
                    onNavigateToBackup = { navController.navigate("settings/backup") }
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
        }
    }
}
