package com.phoneintegration.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.app.role.RoleManager
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.google.android.gms.ads.MobileAds
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.ui.navigation.MainNavigation
import com.phoneintegration.app.ui.theme.PhoneIntegrationTheme
import com.phoneintegration.app.utils.DefaultSmsHelper

class MainActivity : ComponentActivity() {

    // -------------------------------------------------------------
    // ViewModel + Prefs
    // -------------------------------------------------------------
    val viewModel: SmsViewModel by viewModels()
    private lateinit var preferencesManager: PreferencesManager

    // -------------------------------------------------------------
    // Default SMS picker — SINGLE launcher
    // -------------------------------------------------------------
    private val defaultSmsRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val isDefault = DefaultSmsHelper.isDefaultSmsApp(this)
            viewModel.onDefaultSmsAppChanged(isDefault)
        }

    fun requestDefaultSmsAppViaRole() {
        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = getSystemService(RoleManager::class.java)
                rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                }
            }

        defaultSmsRoleLauncher.launch(intent)
    }

    // -------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------
    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.RECEIVE_MMS,
        Manifest.permission.RECEIVE_WAP_PUSH,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_PHONE_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
        }
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val allGranted = result.values.all { it }
            if (!allGranted) showPermissionDialog()
        }

    // -------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------
    override fun onResume() {
        super.onResume()
        viewModel.onDefaultSmsAppChanged(DefaultSmsHelper.isDefaultSmsApp(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferencesManager = PreferencesManager(this)

        if (!hasAllPermissions())
            requestAllPermissions()

        MobileAds.initialize(this)

        setContent {
            val systemInDarkTheme = isSystemInDarkTheme()
            val isDarkTheme by remember {
                derivedStateOf {
                    if (preferencesManager.isAutoTheme.value) systemInDarkTheme
                    else preferencesManager.isDarkMode.value
                }
            }

            PhoneIntegrationTheme(darkTheme = isDarkTheme) {
                Surface(color = MaterialTheme.colorScheme.background) {

                    // Initial UI sync
                    viewModel.onDefaultSmsAppChanged(
                        DefaultSmsHelper.isDefaultSmsApp(this)
                    )

                    MainNavigation(
                        viewModel = viewModel,
                        preferencesManager = preferencesManager
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------
    // Permission helpers
    // -------------------------------------------------------------
    private fun hasAllPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) ==
                    PackageManager.PERMISSION_GRANTED
        }

    private fun requestAllPermissions() {
        permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions required")
            .setMessage("SyncFlow requires SMS, MMS, Contacts and Media permissions.")
            .setCancelable(false)
            .setPositiveButton("Grant") { _, _ -> requestAllPermissions() }
            .setNegativeButton("Exit App") { _, _ -> finish() }
            .show()
    }

    // -------------------------------------------------------------
    // Handle share intents
    // -------------------------------------------------------------
    fun handleShareIntent(intent: Intent): Pair<String?, String?> {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            return Pair(null, text)
        }

        if (intent.action == Intent.ACTION_SENDTO) {
            val uri = intent.data
            val number = uri?.schemeSpecificPart?.substringBefore("?body")
            val body = uri?.getQueryParameter("body")
            return Pair(number, body)
        }

        return Pair(null, null)
    }
}
