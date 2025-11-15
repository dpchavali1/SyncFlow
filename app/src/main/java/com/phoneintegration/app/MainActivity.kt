package com.phoneintegration.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phoneintegration.app.ui.theme.PhoneIntegrationTheme

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            android.util.Log.d("MainActivity", "All permissions granted")
            // Permissions granted - data will load automatically via ViewModel init
        } else {
            android.util.Log.e("MainActivity", "Some permissions denied")
        }
    }

    private fun handleShareIntent(intent: Intent?): Pair<String?, String?> {
        var sharedText: String? = null
        var sharedAddress: String? = null

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    android.util.Log.d("MainActivity", "Shared text: $sharedText")
                }
            }
            Intent.ACTION_SENDTO -> {
                sharedAddress = intent.data?.schemeSpecificPart
                android.util.Log.d("MainActivity", "SMS to: $sharedAddress")
            }
        }

        return Pair(sharedAddress, sharedText)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestSmsPermissions()

        setContent {
            PhoneIntegrationTheme {
                val (sharedAddress, sharedText) = remember {
                    handleShareIntent(intent)
                }
                MainNavigation(
                    sharedPhoneNumber = sharedAddress,
                    sharedMessage = sharedText
                )
            }
        }
    }

    private fun requestSmsPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS
        )

        // Add notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            android.util.Log.d("MainActivity", "Requesting permissions")
            requestPermissions.launch(notGranted.toTypedArray())
        } else {
            android.util.Log.d("MainActivity", "All permissions already granted")
        }
    }
}

@Composable
fun MainNavigation(
    sharedPhoneNumber: String? = null,
    sharedMessage: String? = null
) {
    var currentScreen by remember {
        mutableStateOf(
            if (sharedPhoneNumber != null || sharedMessage != null) "send" else "conversations"
        )
    }
    var selectedAddress by remember { mutableStateOf("") }
    var selectedName by remember { mutableStateOf("") }

    val viewModel: SmsViewModel = viewModel()

    when (currentScreen) {
        "conversations" -> ConversationListScreen(
            viewModel = viewModel,
            onNavigateToConversation = { address, name ->
                selectedAddress = address
                selectedName = name
                currentScreen = "conversation_detail"
            },
            onNavigateToSend = { currentScreen = "send" }
        )
        "conversation_detail" -> ConversationDetailScreen(
            address = selectedAddress,
            contactName = selectedName,
            viewModel = viewModel,
            onBack = {
                viewModel.loadConversations()
                currentScreen = "conversations"
            }
        )
        "send" -> SendSmsScreen(
            initialPhoneNumber = sharedPhoneNumber,
            initialMessage = sharedMessage,
            onBack = {
                viewModel.loadConversations()
                currentScreen = "conversations"
            }
        )
    }
}