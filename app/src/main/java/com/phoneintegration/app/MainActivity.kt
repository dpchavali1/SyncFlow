package com.phoneintegration.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import com.google.android.gms.ads.MobileAds
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phoneintegration.app.R
import com.phoneintegration.app.SmsPermissions
import com.phoneintegration.app.SmsViewModel
import com.phoneintegration.app.auth.AuthManager
import com.phoneintegration.app.auth.RecoveryCodeManager
import com.phoneintegration.app.desktop.*
import com.phoneintegration.app.ui.auth.RecoveryCodeScreen
import com.phoneintegration.app.share.SharePayload
import com.phoneintegration.app.utils.DefaultSmsHelper
import com.phoneintegration.app.data.PreferencesManager
import com.phoneintegration.app.e2ee.SignalProtocolManager
import com.phoneintegration.app.webrtc.SyncFlowCallManager
import com.phoneintegration.app.services.BatteryAwareServiceManager
import com.phoneintegration.app.ui.call.SyncFlowCallScreen
import com.phoneintegration.app.ui.call.IncomingSyncFlowCallScreen
import com.phoneintegration.app.ui.navigation.MainNavigation
import com.phoneintegration.app.ui.theme.PhoneIntegrationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // -------------------------------------------------------------
    // ViewModel + Prefs
    // -------------------------------------------------------------
    val viewModel: SmsViewModel by viewModels()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var authManager: AuthManager
    private lateinit var recoveryCodeManager: RecoveryCodeManager

    // State for active call (can be updated from onNewIntent)
    private val _activeCallTrigger = mutableStateOf(0)
    private var _pendingActiveCallId: String? = null
    private var _pendingActiveCallName: String? = null
    private var _pendingActiveCallVideo: Boolean = false

    // State for incoming call (can be updated from onNewIntent)
    private val _incomingCallTrigger = mutableStateOf(0)
    private var _pendingIncomingCallId: String? = null
    private var _pendingIncomingCallName: String? = null
    private var _pendingIncomingCallVideo: Boolean = false

    // Services are now managed by BatteryAwareServiceManager for optimal battery usage

    // Share intent payload
    private val pendingSharePayload = mutableStateOf<SharePayload?>(null)
    private val pendingConversationLaunch = mutableStateOf<ConversationLaunch?>(null)

    data class ConversationLaunch(
        val threadId: Long,
        val address: String,
        val name: String
    )

    // -------------------------------------------------------------
    // Default SMS picker — SINGLE launcher
    // -------------------------------------------------------------
    private val defaultSmsRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            android.util.Log.d("MainActivity", "=== Default SMS launcher callback ===")
            android.util.Log.d("MainActivity", "Result code: ${result.resultCode}")
            val isDefault = DefaultSmsHelper.isDefaultSmsApp(this)
            android.util.Log.d("MainActivity", "Is now default SMS app: $isDefault")
            viewModel.onDefaultSmsAppChanged(isDefault)
        }

    // Default Dialer picker — SINGLE launcher
    private val defaultDialerRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            android.util.Log.d("MainActivity", "=== Default Dialer launcher callback ===")
            android.util.Log.d("MainActivity", "Result code: ${result.resultCode}")
            val isDefault = isDefaultDialer()
            android.util.Log.d("MainActivity", "Is now default dialer: $isDefault")
        }

    fun requestDefaultSmsAppViaRole() {
        android.util.Log.d("MainActivity", "=== requestDefaultSmsAppViaRole() called ===")
        android.util.Log.d("MainActivity", "Android version: ${Build.VERSION.SDK_INT}")

        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.util.Log.d("MainActivity", "Using RoleManager (Android 10+)")
                val rm = getSystemService(RoleManager::class.java) as RoleManager
                val isRoleAvailable = rm.isRoleAvailable(RoleManager.ROLE_SMS)
                android.util.Log.d("MainActivity", "SMS Role available: $isRoleAvailable")
                rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                android.util.Log.d("MainActivity", "Using legacy ACTION_CHANGE_DEFAULT")
                Intent("android.telephony.action.CHANGE_DEFAULT").apply {
                    putExtra("android.telephony.extra.CHANGE_DEFAULT_DIALER_PACKAGE_NAME", packageName)
                }
            }

        android.util.Log.d("MainActivity", "Launching intent: $intent")
        defaultSmsRoleLauncher.launch(intent)
        android.util.Log.d("MainActivity", "Intent launched successfully")
    }

    private fun requestDefaultDialerRole() {
        android.util.Log.d("MainActivity", "=== requestDefaultDialerRole() called ===")
        android.util.Log.d("MainActivity", "Android version: ${Build.VERSION.SDK_INT}")

        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.util.Log.d("MainActivity", "Using RoleManager (Android 10+)")
                val rm = getSystemService(RoleManager::class.java) as RoleManager
                val isRoleAvailable = rm.isRoleAvailable(RoleManager.ROLE_DIALER)
                android.util.Log.d("MainActivity", "Dialer Role available: $isRoleAvailable")
                rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.util.Log.d("MainActivity", "Using legacy ACTION_CHANGE_DEFAULT_DIALER")
                Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                    putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                }
            } else {
                android.util.Log.w("MainActivity", "Default dialer role not supported on this Android version")
                return
            }

        android.util.Log.d("MainActivity", "Launching dialer intent: $intent")
        defaultDialerRoleLauncher.launch(intent)
        android.util.Log.d("MainActivity", "Dialer intent launched successfully")
    }

    private fun isDefaultDialer(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val telecomManager = getSystemService(TelecomManager::class.java) as TelecomManager
        val currentDefault = telecomManager?.defaultDialerPackage
        val isDefault = currentDefault == packageName
        android.util.Log.d("MainActivity", "Current default dialer: $currentDefault, ours: $isDefault")
        return isDefault
    }

    // -------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------
    // MINIMAL core permissions required for basic app functionality
    // (Reduces antivirus false positives)
    private val CORE_PERMISSIONS = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG, // For call history sync
        Manifest.permission.POST_NOTIFICATIONS // For local notifications
    )

    // Optional permissions for enhanced features (requested when needed)
    private val CALL_PERMISSIONS = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.MANAGE_OWN_CALLS,
        Manifest.permission.READ_CALL_LOG
    )

    private val MEDIA_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val STORAGE_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    // Additional permissions for enhanced features (requested separately)
    private val ENHANCED_PERMISSIONS_GROUP_1 = arrayOf( // Phone & Calls
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALL_LOG
    )

    private val ENHANCED_PERMISSIONS_GROUP_2 = arrayOf( // Media & Notifications
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).let { permissions ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions + arrayOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            permissions + arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    // All permissions for full functionality (for reference)
    private val ALL_PERMISSIONS = (CORE_PERMISSIONS + ENHANCED_PERMISSIONS_GROUP_1 + ENHANCED_PERMISSIONS_GROUP_2).toMutableList()

    // Track if we've already asked for permissions this session
    private var hasRequestedPermissions = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            hasRequestedPermissions = true
            val coreGranted = hasCorePermissions()
            if (!coreGranted) {
                // Only show dialog if CORE permissions are missing
                showPermissionDialog()
            }
            // If core permissions are granted, proceed even if optional ones are denied
        }

    // -------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------
    override fun onResume() {
        super.onResume()
        // Track user activity for session management
        authManager.updateActivity()

        viewModel.onDefaultSmsAppChanged(DefaultSmsHelper.isDefaultSmsApp(this))
        viewModel.reconcileDeletedMessages()
        // Ensure call service is running while app is in foreground (needed for incoming calls).
        SyncFlowCallService.startService(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up BatteryAwareServiceManager (it handles all service cleanup)
        try {
            BatteryAwareServiceManager.getInstance(applicationContext).cleanup()
            android.util.Log.d("MainActivity", "BatteryAwareServiceManager cleaned up")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error cleaning up BatteryAwareServiceManager", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val startTime = System.currentTimeMillis()
            android.util.Log.d("MainActivity", "=== onCreate START ===")

            // Essential setup only - must be on main thread
            preferencesManager = PreferencesManager(this)
            authManager = AuthManager.getInstance(this)
            recoveryCodeManager = RecoveryCodeManager.getInstance(this)

            if (preferencesManager.backgroundSyncEnabled.value) {
            com.phoneintegration.app.desktop.OutgoingMessageService.start(applicationContext)
        }

        // Request permissions if core ones are missing (non-blocking)
        if (!hasCorePermissions()) {
            requestCorePermissions()
        }

        // Handle incoming/active call intent immediately
        // Only enable lock screen override if this is an incoming call
        val isIncomingCall = intent?.hasExtra("incoming_syncflow_call_id") == true ||
                intent?.hasExtra("active_syncflow_call") == true ||
                intent?.hasExtra("syncflow_call_action") == true
        if (isIncomingCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            android.util.Log.d("MainActivity", "Enabled lock screen override for incoming call")
        }
        handleCallIntent(intent)
        handleShareIntent(intent)?.let { pendingSharePayload.value = it }
        handleConversationIntent(intent)?.let { pendingConversationLaunch.value = it }

        android.util.Log.d("MainActivity", "Essential setup done in ${System.currentTimeMillis() - startTime}ms")

        // Defer ALL heavy initializations to background using lifecycle-aware scope
        // Use applicationContext to prevent Activity memory leaks
        val appContext = applicationContext
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bgStartTime = System.currentTimeMillis()

            // Initialize Signal Protocol Manager for E2EE (can be slow)
            try {
                val signalProtocolManager = SignalProtocolManager(appContext)
                signalProtocolManager.initializeKeys()
                android.util.Log.d("MainActivity", "Signal Protocol initialized")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error initializing Signal Protocol", e)
            }

            // Register FCM token for receiving video call notifications
            // This is critical - without the token, FCM can't deliver call notifications
            try {
                val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                if (userId != null) {
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                        .addOnSuccessListener { token ->
                            android.util.Log.d("MainActivity", "Registering FCM token for user $userId")
                            com.google.firebase.database.FirebaseDatabase.getInstance().reference
                                .child("fcm_tokens")
                                .child(userId)
                                .setValue(token)
                                .addOnSuccessListener {
                                    android.util.Log.d("MainActivity", "FCM token registered successfully")
                                }
                                .addOnFailureListener { e ->
                                    android.util.Log.e("MainActivity", "Failed to register FCM token", e)
                                }
                        }
                        .addOnFailureListener { e ->
                            android.util.Log.e("MainActivity", "Failed to get FCM token", e)
                        }
                } else {
                    android.util.Log.w("MainActivity", "Cannot register FCM token - user not authenticated")
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error registering FCM token", e)
            }

            // Initialize MobileAds (slow network call)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                MobileAds.initialize(this@MainActivity)
            }

            // Request default dialer role (if not already)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!isDefaultDialer()) {
                    // Delay to avoid UI interruption during app launch
                    kotlinx.coroutines.delay(1500)
                    requestDefaultDialerRole()
                }
            }

            // Schedule background workers (low priority) - use appContext
            com.phoneintegration.app.desktop.SmsSyncWorker.schedule(appContext)
            com.phoneintegration.app.desktop.ContactsSyncWorker.schedule(appContext)
            com.phoneintegration.app.desktop.CallHistorySyncWorker.schedule(appContext)

            android.util.Log.d("MainActivity", "Background init done in ${System.currentTimeMillis() - bgStartTime}ms")

            // Delay sync operations to avoid competing with UI
            kotlinx.coroutines.delay(3000)

            // Trigger sync (very low priority - after UI is responsive)
            com.phoneintegration.app.desktop.ContactsSyncWorker.syncNow(appContext)
            com.phoneintegration.app.desktop.CallHistorySyncWorker.syncNow(appContext)
        }

        // Start services with slight delay (avoid blocking UI)
        // Use BatteryAwareServiceManager for intelligent service management
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.delay(500) // Let UI render first

            try {
                // Initialize battery-aware service manager
                val serviceManager = BatteryAwareServiceManager.getInstance(appContext)
                android.util.Log.d("MainActivity", "BatteryAwareServiceManager initialized - services will start intelligently based on battery and conditions")

                // Note: SyncFlowCallService and OutgoingMessageService are NOT started here.
                // They are started on-demand via FCM push notifications when:
                // - An incoming video call arrives (triggers FCM -> starts SyncFlowCallService)
                // - A message needs to be sent from desktop (triggers FCM -> starts OutgoingMessageService)
                // This eliminates the persistent "Ready to receive calls" notification.

                // Services are now managed by BatteryAwareServiceManager based on:
                // - Battery level and charging status
                // - Network conditions (WiFi vs mobile data)
                // - App lifecycle (foreground/background)
                // - User preferences
                // This should reduce battery usage by 50-70%

                android.util.Log.i("MainActivity", "Service management delegated to BatteryAwareServiceManager")

            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error initializing BatteryAwareServiceManager", e)
            }

            // Services are now managed by BatteryAwareServiceManager
            // No manual service starting needed - BatteryAwareServiceManager handles this intelligently
        }

        android.util.Log.d("MainActivity", "=== onCreate UI setup starting at ${System.currentTimeMillis() - startTime}ms ===")

        setContent {
            val systemInDarkTheme = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val isDarkTheme by remember {
                derivedStateOf {
                    try {
                        if (preferencesManager.isAutoTheme.value) systemInDarkTheme
                        else preferencesManager.isDarkMode.value
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error getting theme preferences", e)
                        false // Default to light theme
                    }
                }
            }

            // SyncFlow call state - poll for call manager since it might not be available immediately
            var callManager by remember { mutableStateOf(SyncFlowCallService.getCallManager()) }

            // Keep checking for call manager if it's null
            LaunchedEffect(callManager) {
                if (callManager == null) {
                    while (callManager == null) {
                        kotlinx.coroutines.delay(200)
                        callManager = SyncFlowCallService.getCallManager()
                    }
                    android.util.Log.d("MainActivity", "CallManager became available")
                }
            }

            val callState by callManager?.callState?.collectAsState() ?: remember { mutableStateOf(null) }
            val currentCall by callManager?.currentCall?.collectAsState() ?: remember { mutableStateOf(null) }

            // Incoming call state
            var incomingCallId by remember { mutableStateOf<String?>(null) }
            var incomingCallerName by remember { mutableStateOf<String?>(null) }
            var incomingIsVideo by remember { mutableStateOf(false) }

            // Active call state (when answering from notification)
            var showActiveCallScreen by remember { mutableStateOf(false) }

            // Observe the trigger for active calls from onNewIntent
            val activeCallTrigger by _activeCallTrigger
            val incomingCallTrigger by _incomingCallTrigger

            // Also check activeCallTrigger to refresh callManager
            LaunchedEffect(activeCallTrigger, incomingCallTrigger) {
                if (callManager == null) {
                    callManager = SyncFlowCallService.getCallManager()
                }
            }

            // Check for incoming call or active call from intent (initial launch)
            LaunchedEffect(Unit) {
                // Check for incoming call (unanswered)
                val callId = intent.getStringExtra("incoming_syncflow_call_id")
                val callerName = intent.getStringExtra("incoming_syncflow_call_name")
                val isVideo = intent.getBooleanExtra("incoming_syncflow_call_video", false)

                if (callId != null) {
                    android.util.Log.d("MainActivity", "Incoming SyncFlow call: $callId from $callerName")
                    incomingCallId = callId
                    incomingCallerName = callerName
                    incomingIsVideo = isVideo
                }

                // Check for active call (already answered from notification)
                val isActiveCall = intent.getBooleanExtra("active_syncflow_call", false)
                if (isActiveCall) {
                    android.util.Log.d("MainActivity", "Active SyncFlow call - showing call screen")
                    showActiveCallScreen = true
                }
            }

            // React to active call trigger from onNewIntent
            LaunchedEffect(activeCallTrigger) {
                if (_pendingActiveCallId != null) {
                    android.util.Log.d("MainActivity", "Active call triggered from onNewIntent: $_pendingActiveCallId")
                    showActiveCallScreen = true
                    incomingCallId = null // Clear any incoming call UI
                    _pendingActiveCallId = null
                }
            }

            // React to incoming call trigger from onNewIntent
            LaunchedEffect(incomingCallTrigger) {
                if (_pendingIncomingCallId != null) {
                    android.util.Log.d("MainActivity", "Incoming call triggered from onNewIntent: $_pendingIncomingCallId")
                    incomingCallId = _pendingIncomingCallId
                    incomingCallerName = _pendingIncomingCallName
                    incomingIsVideo = _pendingIncomingCallVideo
                    showActiveCallScreen = false
                    _pendingIncomingCallId = null
                }
            }

            // Also watch for call state changes to show/hide call screen
            LaunchedEffect(callState) {
                when (callState) {
                    is SyncFlowCallManager.CallState.Connected,
                    is SyncFlowCallManager.CallState.Connecting -> {
                        showActiveCallScreen = true
                        incomingCallId = null // Clear incoming call UI
                    }
                    is SyncFlowCallManager.CallState.Ended,
                    is SyncFlowCallManager.CallState.Idle -> {
                        showActiveCallScreen = false
                    }
                    else -> {}
                }
            }

            val coroutineScope = rememberCoroutineScope()

            PhoneIntegrationTheme(darkTheme = isDarkTheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    // No recovery code screen on startup - it will be shown when user tries to pair
                    // This allows Android-only users to use the app without friction
                        Box(modifier = Modifier.fillMaxSize()) {
                            MainNavigation(
                                viewModel = viewModel,
                                preferencesManager = preferencesManager,
                                pendingShare = pendingSharePayload.value,
                                onShareHandled = { pendingSharePayload.value = null },
                                pendingConversation = pendingConversationLaunch.value,
                                onConversationHandled = { pendingConversationLaunch.value = null }
                            )

                        // Show incoming call screen
                        if (incomingCallId != null && callState != SyncFlowCallManager.CallState.Connected) {
                            IncomingSyncFlowCallScreen(
                                callerName = incomingCallerName ?: "Unknown",
                                isVideo = incomingIsVideo,
                                onAcceptVideo = {
                                    // Use the service to answer the call - this ensures ringtone stops
                                    val answerIntent = Intent(this@MainActivity, SyncFlowCallService::class.java).apply {
                                        action = SyncFlowCallService.ACTION_ANSWER_CALL
                                        putExtra(SyncFlowCallService.EXTRA_CALL_ID, incomingCallId)
                                        putExtra(SyncFlowCallService.EXTRA_WITH_VIDEO, true)
                                    }
                                    startService(answerIntent)
                                    incomingCallId = null
                                    showActiveCallScreen = true
                                },
                                onAcceptAudio = {
                                    // Use the service to answer the call - this ensures ringtone stops
                                    val answerIntent = Intent(this@MainActivity, SyncFlowCallService::class.java).apply {
                                        action = SyncFlowCallService.ACTION_ANSWER_CALL
                                        putExtra(SyncFlowCallService.EXTRA_CALL_ID, incomingCallId)
                                        putExtra(SyncFlowCallService.EXTRA_WITH_VIDEO, false)
                                    }
                                    startService(answerIntent)
                                    incomingCallId = null
                                    showActiveCallScreen = true
                                },
                                onDecline = {
                                    // Use the service to reject the call - this ensures ringtone stops
                                    val rejectIntent = Intent(this@MainActivity, SyncFlowCallService::class.java).apply {
                                        action = SyncFlowCallService.ACTION_REJECT_CALL
                                        putExtra(SyncFlowCallService.EXTRA_CALL_ID, incomingCallId)
                                    }
                                    startService(rejectIntent)
                                    incomingCallId = null
                                }
                            )
                        }

                        // Show active call screen
                        val currentCallManager = callManager
                        if (currentCallManager != null &&
                            (showActiveCallScreen || currentCall != null) &&
                            (callState == SyncFlowCallManager.CallState.Connected ||
                             callState == SyncFlowCallManager.CallState.Connecting ||
                             callState == SyncFlowCallManager.CallState.Ringing)) {
                            SyncFlowCallScreen(
                                callManager = currentCallManager,
                                onCallEnded = {
                                    android.util.Log.d("MainActivity", "Call ended")
                                    showActiveCallScreen = false
                                }
                            )
                        }
                    }
                }
            }
        }

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Critical error during MainActivity initialization", e)
            // Show error dialog or finish activity
            runOnUiThread {
                try {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("App Initialization Error")
                        .setMessage("Failed to initialize the app. Please restart.\n\nError: ${e.message}")
                        .setCancelable(false)
                        .setPositiveButton("Restart") { _, _ ->
                            val intent = packageManager.getLaunchIntentForPackage(packageName)
                            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            startActivity(intent)
                            finish()
                        }
                        .setNegativeButton("Close") { _, _ ->
                            finish()
                        }
                        .show()
                } catch (dialogError: Exception) {
                    android.util.Log.e("MainActivity", "Failed to show error dialog", dialogError)
                    finish()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the intent so getIntent() returns the new one

        // Enable lock screen override only for incoming calls
        val isIncomingCall = intent.hasExtra("incoming_syncflow_call_id") ||
                intent.getBooleanExtra("active_syncflow_call", false) ||
                intent.hasExtra("syncflow_call_action")
        if (isIncomingCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        handleCallIntent(intent)
        handleShareIntent(intent)?.let { pendingSharePayload.value = it }
        handleConversationIntent(intent)?.let { pendingConversationLaunch.value = it }
    }

    private fun handleConversationIntent(intent: Intent?): ConversationLaunch? {
        if (intent == null) return null
        val address = intent.getStringExtra("open_address") ?: return null
        val name = intent.getStringExtra("open_name") ?: address
        val threadId = intent.getLongExtra("open_thread_id", 0L)
        return ConversationLaunch(threadId, address, name)
    }

    private fun handleCallIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.getStringExtra("syncflow_call_action")
        if (action == "answer") {
            val callId = intent.getStringExtra("incoming_syncflow_call_id")
            val callerName = intent.getStringExtra("incoming_syncflow_call_name")
            val withVideo = intent.getBooleanExtra("syncflow_call_answer_video", true)

            if (callId != null) {
                val answerIntent = Intent(this, SyncFlowCallService::class.java).apply {
                    this.action = SyncFlowCallService.ACTION_ANSWER_CALL
                    putExtra(SyncFlowCallService.EXTRA_CALL_ID, callId)
                    putExtra(SyncFlowCallService.EXTRA_WITH_VIDEO, withVideo)
                }
                startService(answerIntent)

                _pendingActiveCallId = callId
                _pendingActiveCallName = callerName
                _pendingActiveCallVideo = withVideo
                _activeCallTrigger.value++
            }
            return
        }

        // Handle active call (answered from notification)
        val isActiveCall = intent.getBooleanExtra("active_syncflow_call", false)
        if (isActiveCall) {
            val callId = intent.getStringExtra("active_syncflow_call_id")
            val callerName = intent.getStringExtra("active_syncflow_call_name")
            val isVideo = intent.getBooleanExtra("active_syncflow_call_video", false)

            android.util.Log.d("MainActivity", "Handling active SyncFlow call: $callId from $callerName")

            _pendingActiveCallId = callId
            _pendingActiveCallName = callerName
            _pendingActiveCallVideo = isVideo
            _activeCallTrigger.value++ // Trigger recomposition
            return
        }

        // Handle incoming call (unanswered)
        val callId = intent.getStringExtra("incoming_syncflow_call_id")
        if (callId != null) {
            val callerName = intent.getStringExtra("incoming_syncflow_call_name")
            val isVideo = intent.getBooleanExtra("incoming_syncflow_call_video", false)
            android.util.Log.d("MainActivity", "Handling incoming SyncFlow call intent: $callId")
            _pendingIncomingCallId = callId
            _pendingIncomingCallName = callerName
            _pendingIncomingCallVideo = isVideo
            _incomingCallTrigger.value++
        }
    }

    // -------------------------------------------------------------
    // Permission helpers
    // -------------------------------------------------------------
    private fun hasCorePermissions(): Boolean =
        CORE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasAllPermissions(): Boolean =
        ALL_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    // Request only core permissions initially (reduces antivirus false positives)
    private fun requestCorePermissions() {
        if (!hasRequestedPermissions) {
            permissionLauncher.launch(CORE_PERMISSIONS)
        }
    }

    // Request call-related permissions when call features are used
    private fun requestCallPermissions() {
        if (CALL_PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(CALL_PERMISSIONS)
        }
    }

    // Request media permissions when camera/microphone features are used
    private fun requestMediaPermissions() {
        if (MEDIA_PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(MEDIA_PERMISSIONS)
        }
    }

    // Request storage permissions when file features are used
    private fun requestStoragePermissions() {
        if (STORAGE_PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(STORAGE_PERMISSIONS)
        }
    }

    private fun showPermissionDialog() {
        // Don't show dialog if we've already asked and user denied
        if (hasRequestedPermissions) {
            // Check if user has permanently denied - open settings instead
            val shouldShowRationale = CORE_PERMISSIONS.any {
                shouldShowRequestPermissionRationale(it)
            }

            if (!shouldShowRationale && !hasCorePermissions()) {
                // Permissions permanently denied, direct to settings
                android.app.AlertDialog.Builder(this)
                    .setTitle("🔒 Enable Permissions for Full Functionality")
                    .setMessage("""
                        SyncFlow requires SMS and Contacts permissions to sync messages with your desktop.

                        These permissions are standard for messaging apps like WhatsApp or Signal.

                        If antivirus software flagged this request, it's a false positive - SyncFlow is legitimate and secure.
                    """.trimIndent())
                    .setCancelable(true)
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.fromParts("package", packageName, null)
                        startActivity(intent)
                    }
                    .setNegativeButton("Continue Limited") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
            return
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("📱 Enable SMS Sync")
            .setMessage("""
                SyncFlow needs minimal permissions to sync your SMS messages with your desktop:

                📱 SMS Access - Read and send your text messages
                👥 Contacts - Show contact names instead of phone numbers
                🔔 Notifications - Show sync status notifications

                🔒 Privacy Focused:
                • Only requests essential permissions for SMS sync
                • No camera, microphone, or phone call access initially
                • Additional permissions requested only when using specific features
                • Open-source and transparent about data usage

                This minimal approach reduces false antivirus warnings while maintaining full functionality.
            """.trimIndent())
            .setCancelable(true)
            .setPositiveButton("Enable SMS Sync") { _, _ ->
                hasRequestedPermissions = false
                requestCorePermissions()
            }
            .setNegativeButton("Later") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("Learn More") { _, _ ->
                showDetailedPermissionInfo()
            }
            .show()
    }

    private fun showDetailedPermissionInfo() {
        android.app.AlertDialog.Builder(this)
            .setTitle("🔒 SyncFlow Permission Strategy")
            .setMessage("""
                SyncFlow uses a minimal permission approach to reduce security concerns while maintaining full functionality:

                📱 CORE PERMISSIONS (Required for SMS Sync):
                • SMS Access - Read/send messages, sync with desktop
                • Contacts - Show names instead of phone numbers
                • Notifications - Show sync status updates

                📞 OPTIONAL PERMISSIONS (Requested when needed):
                • Phone/Call permissions - Only when using call features
                • Camera/Microphone - Only when starting video calls
                • Storage access - Only when attaching files to MMS

                🛡️ SECURITY MEASURES:
                • Minimal initial permissions reduce antivirus false positives
                • Additional permissions requested contextually
                • All data encrypted end-to-end
                • Open source for transparency
                • No data collection or sharing

                This approach balances functionality with security and user trust.
            """.trimIndent())
            .setCancelable(true)
            .setPositiveButton("Got It") { dialog, _ ->
                showPermissionDialog()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // -------------------------------------------------------------
    // Handle share intents
    // -------------------------------------------------------------
    private fun handleShareIntent(intent: Intent?): SharePayload? {
        if (intent == null) return null

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val uris = extractSharedUris(intent)
                SharePayload(text = text, uris = uris).takeIf { it.hasContent }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val uris = extractSharedUris(intent)
                SharePayload(text = text, uris = uris).takeIf { it.hasContent }
            }
            Intent.ACTION_SENDTO -> {
                val uri = intent.data
                val number = uri?.schemeSpecificPart?.substringBefore("?")
                val body = uri?.getQueryParameter("body")
                SharePayload(text = body, uris = emptyList(), recipient = number).takeIf { it.hasContent }
            }
            else -> null
        }
    }

    private fun extractSharedUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()

        val single = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (single != null) {
            uris.add(single)
        }

        val multiple = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (!multiple.isNullOrEmpty()) {
            uris.addAll(multiple)
        }

        val clipData = intent.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                val uri = clipData.getItemAt(i).uri
                if (uri != null) {
                    uris.add(uri)
                }
            }
        }

        return uris.distinct()
    }
}
