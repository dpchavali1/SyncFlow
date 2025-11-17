package com.phoneintegration.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import com.google.android.gms.ads.MobileAds
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.ui.navigation.MainNavigation
import com.phoneintegration.app.ui.theme.PhoneIntegrationTheme

class MainActivity : ComponentActivity() {

    private val smsPermissions = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS
    )

    private val requestSmsPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val viewModel: SmsViewModel by viewModels()
    private lateinit var preferencesManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferencesManager = PreferencesManager(this)
        askRequiredPermissions()
        MobileAds.initialize(this)

        setContent {
            PhoneIntegrationTheme {
                Surface(color = MaterialTheme.colorScheme.background) {

                    val (sharedAddress, sharedText) = remember {
                        handleShareIntent(intent)
                    }
                    
                    MainNavigation(
                        viewModel = viewModel,
                        preferencesManager = preferencesManager
                    )
                }
            }
        }
    }

    /**
     * Extracts phone number + message text when user shares from another app.
     */
    private fun handleShareIntent(intent: Intent): Pair<String?, String?> {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            return Pair(null, sharedText)
        }

        // sms:12345?body=Hello
        if (intent.action == Intent.ACTION_SENDTO) {
            val uri = intent.data
            val number = uri?.schemeSpecificPart?.substringBefore("?body")
            val body = uri?.getQueryParameter("body")
            return Pair(number, body)
        }

        return Pair(null, null)
    }

    /**
     * Request SMS + Contact permissions + Notifications (Android 13+)
     */
    private fun askRequiredPermissions() {
        requestSmsPermissions.launch(smsPermissions)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
