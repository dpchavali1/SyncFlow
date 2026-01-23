package com.phoneintegration.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.phoneintegration.app.desktop.DesktopSyncService
import com.phoneintegration.app.webrtc.CallAudioManager
import com.phoneintegration.app.webrtc.WebRTCSignalingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CallMonitorService : Service() {

    companion object {
        private const val TAG = "CallMonitorService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "call_monitor_channel"

        fun start(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var telecomManager: TelecomManager
    private lateinit var syncService: DesktopSyncService
    private lateinit var simManager: SimManager
    private val database = FirebaseDatabase.getInstance()

    // WebRTC for audio routing
    private var audioManager: CallAudioManager? = null
    private var signalingService: WebRTCSignalingService? = null
    private var audioRoutingEnabled = false

    private var callCommandsQuery: Query? = null
    private var callCommandsListener: com.google.firebase.database.ChildEventListener? = null
    private var callRequestsRef: com.google.firebase.database.DatabaseReference? = null
    private var callRequestsListener: com.google.firebase.database.ChildEventListener? = null
    private var audioRoutingRef: com.google.firebase.database.DatabaseReference? = null
    private var audioRoutingListener: ValueEventListener? = null

    // For API < 31
    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleCallStateChange(state, phoneNumber)
        }
    }

    // For API >= 31
    @SuppressLint("MissingPermission")
    private val telephonyCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallStateChange(state, null)
            }
        }
    } else null

    private var currentCallId: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CallMonitorService created")

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        syncService = DesktopSyncService(this)
        simManager = SimManager(this)

        // Initialize WebRTC components
        audioManager = CallAudioManager(this)
        signalingService = WebRTCSignalingService(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        registerCallStateListener()
        listenForCallCommands()
        listenForCallRequests()
        listenForAudioRoutingRequests()
        syncCallHistory()
        syncSimInformation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CallMonitorService started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CallMonitorService destroyed")
        unregisterCallStateListener()
        stopAudioRouting()
        stopFirebaseListeners()
        audioManager?.dispose()
        serviceScope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun registerCallStateListener() {
        if (!checkCallPermissions()) {
            Log.e(TAG, "Missing call permissions")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    telephonyManager.registerTelephonyCallback(mainExecutor, it)
                    Log.d(TAG, "Registered TelephonyCallback (API 31+)")
                }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
                Log.d(TAG, "Registered PhoneStateListener (API < 31)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering call state listener", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun unregisterCallStateListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
            Log.d(TAG, "Unregistered call state listener")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering call state listener", e)
        }
    }

    private fun handleCallStateChange(state: Int, phoneNumber: String?) {
        Log.d(TAG, "Call state changed: $state, number: $phoneNumber")

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // Incoming call
                val number = phoneNumber ?: "Unknown"
                Log.d(TAG, "Incoming call from: $number")
                onIncomingCall(number)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Call answered or outgoing call
                Log.d(TAG, "Call active")
                onCallActive()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // Call ended
                Log.d(TAG, "Call ended")
                onCallEnded()
            }
        }
    }

    private fun onIncomingCall(phoneNumber: String) {
        currentCallId = System.currentTimeMillis().toString()

        serviceScope.launch {
            try {
                // Get contact name
                val contactHelper = ContactHelper(this@CallMonitorService)
                val contactName = contactHelper.getContactName(phoneNumber)

                Log.d(TAG, "Incoming call from: $phoneNumber, contactName: $contactName")

                // Sync to active_calls for real-time notification
                syncActiveCall(
                    callId = currentCallId!!,
                    phoneNumber = phoneNumber,
                    contactName = contactName,
                    callState = "ringing"
                )

                // Also sync to call history
                syncService.syncCallEvent(
                    callId = currentCallId!!,
                    phoneNumber = phoneNumber,
                    contactName = contactName,
                    callType = "incoming",
                    callState = "ringing"
                )

                Log.d(TAG, "Incoming call synced to Firebase")
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing incoming call", e)
            }
        }
    }

    private fun onCallActive() {
        currentCallId?.let { callId ->
            serviceScope.launch {
                try {
                    syncService.updateCallState(callId, "active")
                    updateActiveCallState(callId, "active")
                    Log.d(TAG, "Call active state synced to Firebase")
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing call active state", e)
                }
            }
        }
    }

    private fun onCallEnded() {
        currentCallId?.let { callId ->
            serviceScope.launch {
                try {
                    syncService.updateCallState(callId, "ended")
                    clearActiveCall(callId)
                    Log.d(TAG, "Call ended state synced to Firebase")
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing call ended state", e)
                }
            }
        }
        stopAudioRouting()
        currentCallId = null
    }

    /**
     * Sync active call to Firebase for real-time notifications on desktop
     */
    private suspend fun syncActiveCall(
        callId: String,
        phoneNumber: String,
        contactName: String?,
        callState: String
    ) {
        try {
            val userId = syncService.getCurrentUserId()
            val activeCallRef = database.reference
                .child("users")
                .child(userId)
                .child("active_calls")
                .child(callId)

            val callData = mapOf(
                "id" to callId,
                "phoneNumber" to phoneNumber,
                "contactName" to (contactName ?: ""),
                "callState" to callState,
                "timestamp" to ServerValue.TIMESTAMP
            )

            Log.d(TAG, "Syncing active call - callId: $callId, phoneNumber: $phoneNumber, contactName: ${contactName ?: "null"}, callState: $callState")
            activeCallRef.setValue(callData).await()
            Log.d(TAG, "Active call synced: $callId - $callState")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing active call", e)
        }
    }

    /**
     * Update active call state
     */
    private suspend fun updateActiveCallState(callId: String, callState: String) {
        try {
            val userId = syncService.getCurrentUserId()
            val activeCallRef = database.reference
                .child("users")
                .child(userId)
                .child("active_calls")
                .child(callId)

            activeCallRef.child("callState").setValue(callState).await()
            Log.d(TAG, "Active call state updated: $callId - $callState")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating active call state", e)
        }
    }

    /**
     * Clear active call from Firebase when call ends
     */
    private suspend fun clearActiveCall(callId: String) {
        try {
            val userId = syncService.getCurrentUserId()
            val activeCallRef = database.reference
                .child("users")
                .child(userId)
                .child("active_calls")
                .child(callId)

            activeCallRef.removeValue().await()
            Log.d(TAG, "Active call cleared: $callId")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing active call", e)
        }
    }

    private fun listenForCallCommands() {
        serviceScope.launch {
            try {
                val userId = syncService.getCurrentUserId()
                val commandsRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("call_commands")

                // Get current timestamp - only listen for commands created AFTER this point
                val currentTime = System.currentTimeMillis()
                Log.d(TAG, "Setting up call commands listener at: users/$userId/call_commands (ignoring commands older than $currentTime)")

                // Listen for NEW commands by ordering by timestamp and starting from now
                val listener = object : com.google.firebase.database.ChildEventListener {
                    override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                        Log.d(TAG, "onChildAdded triggered! Key: ${snapshot.key}, exists: ${snapshot.exists()}")

                        val command = snapshot.child("command").value as? String
                        val callId = snapshot.child("callId").value as? String
                        val phoneNumber = snapshot.child("phoneNumber").value as? String
                        val processed = snapshot.child("processed").value as? Boolean ?: false
                        val timestamp = snapshot.child("timestamp").value as? Long ?: 0L

                        Log.d(TAG, "Command data: command=$command, callId=$callId, phoneNumber=$phoneNumber, processed=$processed, timestamp=$timestamp")

                        // Process unprocessed commands
                        if (!processed && command != null) {
                            val commandAge = System.currentTimeMillis() - timestamp
                            Log.d(TAG, "Received NEW call command: $command for call: $callId (age: ${commandAge}ms)")
                            handleCallCommand(command, callId, phoneNumber)

                            // Mark as processed
                            snapshot.ref.child("processed").setValue(true)
                        } else {
                            Log.d(TAG, "Ignoring command: processed=$processed, command=$command")
                        }
                    }

                    override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                        Log.d(TAG, "onChildChanged triggered! Key: ${snapshot.key}")
                    }

                    override fun onChildRemoved(snapshot: DataSnapshot) {
                        Log.d(TAG, "onChildRemoved triggered! Key: ${snapshot.key}")
                    }

                    override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                        Log.d(TAG, "onChildMoved triggered! Key: ${snapshot.key}")
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Error listening for call commands", error.toException())
                    }
                }

                callCommandsListener?.let { existing ->
                    callCommandsQuery?.removeEventListener(existing)
                }
                callCommandsQuery = commandsRef
                    .orderByChild("timestamp")
                    .startAt(currentTime.toDouble())
                callCommandsListener = listener
                callCommandsQuery?.addChildEventListener(listener)

                Log.d(TAG, "Call commands listener attached successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up call commands listener", e)
            }
        }
    }

    private fun listenForCallRequests() {
        serviceScope.launch {
            try {
                Log.d(TAG, "Setting up call requests listener...")
                val userId = syncService.getCurrentUserId()
                Log.d(TAG, "Got userId: $userId")
                val requestsRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("call_requests")

                Log.d(TAG, "Attaching Firebase listener to: users/$userId/call_requests")
                val listener = object : com.google.firebase.database.ChildEventListener {
                    override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                        val requestId = snapshot.key ?: return
                        val phoneNumber = snapshot.child("phoneNumber").value as? String
                        val status = snapshot.child("status").value as? String ?: "pending"

                        if (status == "pending" && phoneNumber != null) {
                            Log.d(TAG, "Received call request for: $phoneNumber")
                            processCallRequest(requestId, phoneNumber, snapshot.ref)
                        }
                    }

                    override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                    override fun onChildRemoved(snapshot: DataSnapshot) {}
                    override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Error listening for call requests", error.toException())
                    }
                }

                callRequestsListener?.let { existing ->
                    callRequestsRef?.removeEventListener(existing)
                }
                callRequestsRef = requestsRef
                callRequestsListener = listener
                requestsRef.addChildEventListener(listener)

                Log.d(TAG, "Listening for call requests from Firebase")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up call requests listener", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun processCallRequest(requestId: String, phoneNumber: String, requestRef: com.google.firebase.database.DatabaseReference) {
        if (!checkCallPermissions()) {
            Log.e(TAG, "Missing permissions to process call request")
            // Update status to failed
            requestRef.updateChildren(mapOf(
                "status" to "failed",
                "error" to "Missing permissions",
                "completedAt" to ServerValue.TIMESTAMP
            ))
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                // Get the request data to check for SIM selection
                val snapshot = requestRef.get().await()
                val requestData = snapshot.value as? Map<*, *>
                val requestedSimSubId = requestData?.get("simSubscriptionId") as? Number

                // Update status to calling
                requestRef.updateChildren(mapOf(
                    "status" to "calling"
                )).await()

                // Make the call with specific SIM if requested
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && requestedSimSubId != null) {
                    // Use specific SIM card
                    makeCallWithSim(phoneNumber, requestedSimSubId.toInt())
                } else {
                    // Use default SIM
                    makeCallDefault(phoneNumber)
                }

                Log.d(TAG, "Initiated call from web/desktop request to $phoneNumber" +
                        if (requestedSimSubId != null) " using SIM subscription $requestedSimSubId" else "")

                // Update status to completed
                requestRef.updateChildren(mapOf(
                    "status" to "completed",
                    "completedAt" to ServerValue.TIMESTAMP
                )).await()

                // Sync call event to Firebase
                val newCallId = System.currentTimeMillis().toString()
                try {
                    val contactHelper = ContactHelper(this@CallMonitorService)
                    val contactName = contactHelper.getContactName(phoneNumber)

                    syncService.syncCallEvent(
                        callId = newCallId,
                        phoneNumber = phoneNumber,
                        contactName = contactName,
                        callType = "outgoing",
                        callState = "dialing"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing call request event", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing call request to $phoneNumber", e)
                // Update status to failed
                requestRef.updateChildren(mapOf(
                    "status" to "failed",
                    "error" to e.message,
                    "completedAt" to ServerValue.TIMESTAMP
                ))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun makeCallWithSim(phoneNumber: String, subscriptionId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // Find the PhoneAccountHandle for this subscription ID
                // The phone account ID is the subscription ID as a string
                val callCapableAccounts = telecomManager.callCapablePhoneAccounts
                Log.d(TAG, "Looking for PhoneAccount with subscription ID: $subscriptionId")
                Log.d(TAG, "Available call-capable accounts: ${callCapableAccounts.size}")
                callCapableAccounts.forEach { handle ->
                    Log.d(TAG, "  - Account ID: ${handle.id}, Component: ${handle.componentName}")
                }

                val phoneAccountHandle = callCapableAccounts.find { handle ->
                    handle.id == subscriptionId.toString()
                }

                if (phoneAccountHandle != null) {
                    // Use TelecomManager.placeCall() instead of startActivity()
                    // This is the proper API for making calls from a service
                    val uri = Uri.parse("tel:$phoneNumber")
                    val extras = android.os.Bundle().apply {
                        putParcelable("android.telecom.extra.PHONE_ACCOUNT_HANDLE", phoneAccountHandle)
                    }

                    Log.d(TAG, "Placing call with PhoneAccount: ${phoneAccountHandle.id} (subscription $subscriptionId) to $phoneNumber")
                    telecomManager.placeCall(uri, extras)
                    Log.d(TAG, "Call placed successfully via TelecomManager")
                } else {
                    Log.w(TAG, "Could not find PhoneAccount for subscription $subscriptionId, using default")
                    makeCallDefault(phoneNumber)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error making call with specific SIM, falling back to default", e)
                makeCallDefault(phoneNumber)
            }
        } else {
            makeCallDefault(phoneNumber)
        }
    }

    @SuppressLint("MissingPermission")
    private fun makeCallDefault(phoneNumber: String) {
        try {
            // Use TelecomManager.placeCall() for Android M+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val uri = Uri.parse("tel:$phoneNumber")
                telecomManager.placeCall(uri, android.os.Bundle())
                Log.d(TAG, "Call placed successfully via TelecomManager (default SIM)")
            } else {
                // Fallback to Intent for older versions
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(callIntent)
                Log.d(TAG, "Call started via Intent (API < 23)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error placing call to $phoneNumber", e)
        }
    }

    private fun syncSimInformation() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val sims = simManager.getActiveSims()
                Log.d(TAG, "Detected ${sims.size} active SIM(s)")

                sims.forEachIndexed { index, sim ->
                    Log.d(TAG, "SIM $index: ${simManager.getSimDisplayName(sim)}")
                }

                // Sync to Firebase
                simManager.syncSimsToFirebase(syncService)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing SIM information", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCallCommand(command: String, callId: String?, phoneNumber: String?) {
        Log.d(TAG, "Handling call command: $command (callId: $callId, phoneNumber: $phoneNumber)")

        if (!checkCallPermissions()) {
            Log.e(TAG, "Missing permissions to handle call command")
            return
        }

        try {
            when (command) {
                "answer" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Log.d(TAG, "Attempting to answer call via TelecomManager.acceptRingingCall()...")
                        telecomManager.acceptRingingCall()
                        Log.d(TAG, "Accept ringing call executed")

                        // Update active call state
                        callId?.let { id ->
                            serviceScope.launch {
                                delay(500) // Small delay to let call connect
                                updateActiveCallState(id, "active")
                            }
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Log.d(TAG, "Attempting to answer call via InCallService (Android 6-7)...")
                        DesktopInCallService.answerCall()
                        Log.d(TAG, "Answer command sent to InCallService")

                        callId?.let { id ->
                            serviceScope.launch {
                                updateActiveCallState(id, "active")
                            }
                        }
                    } else {
                        Log.w(TAG, "Answer call not supported on API < 23 (current: ${Build.VERSION.SDK_INT})")
                    }
                }
                "reject", "end" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        Log.d(TAG, "Attempting to end call via TelecomManager.endCall()...")
                        val result = telecomManager.endCall()
                        Log.d(TAG, "End call result: $result")

                        // Clear active call
                        callId?.let { id ->
                            serviceScope.launch {
                                delay(300)
                                clearActiveCall(id)
                            }
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Log.d(TAG, "Attempting to end call via InCallService (Android 6-8)...")
                        DesktopInCallService.endCall()
                        Log.d(TAG, "End command sent to InCallService")

                        callId?.let { id ->
                            serviceScope.launch {
                                clearActiveCall(id)
                            }
                        }
                    } else {
                        Log.w(TAG, "End call not supported on API < 23 (current: ${Build.VERSION.SDK_INT})")
                    }
                }
                "make_call" -> {
                    if (phoneNumber != null) {
                        makeOutgoingCall(phoneNumber)
                    } else {
                        Log.e(TAG, "Cannot make call: phone number is null")
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown call command: $command")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling call command: $command", e)
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun makeOutgoingCall(phoneNumber: String) {
        try {
            // Use TelecomManager.placeCall() for Android M+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val uri = Uri.parse("tel:$phoneNumber")
                telecomManager.placeCall(uri, android.os.Bundle())
                Log.d(TAG, "Initiated outgoing call to $phoneNumber via TelecomManager")
            } else {
                // Fallback to Intent for older versions
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(callIntent)
                Log.d(TAG, "Initiated outgoing call to $phoneNumber via Intent")
            }

            // Sync outgoing call to Firebase
            val newCallId = System.currentTimeMillis().toString()
            serviceScope.launch {
                try {
                    val contactHelper = ContactHelper(this@CallMonitorService)
                    val contactName = contactHelper.getContactName(phoneNumber)

                    syncService.syncCallEvent(
                        callId = newCallId,
                        phoneNumber = phoneNumber,
                        contactName = contactName,
                        callType = "outgoing",
                        callState = "dialing"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing outgoing call", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error making outgoing call to $phoneNumber", e)
        }
    }

    @SuppressLint("MissingPermission", "Range")
    private fun syncCallHistory() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Check if we have READ_CALL_LOG permission
                val hasCallLogPermission = ActivityCompat.checkSelfPermission(
                    this@CallMonitorService,
                    Manifest.permission.READ_CALL_LOG
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasCallLogPermission) {
                    Log.w(TAG, "READ_CALL_LOG permission not granted")
                    return@launch
                }

                val userId = syncService.getCurrentUserId()
                val callLogRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("call_history")

                // Query recent calls (last 100)
                val cursor = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    // Use Bundle for API 26+
                    val queryArgs = android.os.Bundle().apply {
                        putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 100)
                        putStringArray(
                            android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                            arrayOf(CallLog.Calls.DATE)
                        )
                        putInt(
                            android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                            android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                        )
                    }
                    contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        arrayOf(
                            CallLog.Calls._ID,
                            CallLog.Calls.NUMBER,
                            CallLog.Calls.TYPE,
                            CallLog.Calls.DATE,
                            CallLog.Calls.DURATION,
                            CallLog.Calls.CACHED_NAME
                        ),
                        queryArgs,
                        null
                    )
                } else {
                    // Fallback for older APIs - just use DESC without LIMIT
                    contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        arrayOf(
                            CallLog.Calls._ID,
                            CallLog.Calls.NUMBER,
                            CallLog.Calls.TYPE,
                            CallLog.Calls.DATE,
                            CallLog.Calls.DURATION,
                            CallLog.Calls.CACHED_NAME
                        ),
                        null,
                        null,
                        "${CallLog.Calls.DATE} DESC"
                    )
                }

                cursor?.use {
                    val contactHelper = ContactHelper(this@CallMonitorService)

                    while (it.moveToNext()) {
                        val callId = it.getLong(it.getColumnIndex(CallLog.Calls._ID))
                        val number = it.getString(it.getColumnIndex(CallLog.Calls.NUMBER)) ?: "Unknown"
                        val callType = it.getInt(it.getColumnIndex(CallLog.Calls.TYPE))
                        val callDate = it.getLong(it.getColumnIndex(CallLog.Calls.DATE))
                        val duration = it.getLong(it.getColumnIndex(CallLog.Calls.DURATION))
                        val cachedName = it.getString(it.getColumnIndex(CallLog.Calls.CACHED_NAME))

                        // Get contact name (try cached first, then lookup)
                        val contactName = cachedName ?: contactHelper.getContactName(number)

                        // Determine call type string
                        val typeString = when (callType) {
                            CallLog.Calls.INCOMING_TYPE -> "incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            CallLog.Calls.REJECTED_TYPE -> "rejected"
                            else -> "unknown"
                        }

                        // Sync to Firebase
                        val callData = mapOf(
                            "id" to callId,
                            "phoneNumber" to number,
                            "contactName" to (contactName ?: number),
                            "callType" to typeString,
                            "date" to callDate,
                            "duration" to duration,
                            "timestamp" to ServerValue.TIMESTAMP
                        )

                        callLogRef.child(callId.toString()).setValue(callData)
                    }

                    Log.d(TAG, "Call history synced: ${it.count} calls")
                } ?: Log.w(TAG, "Call log cursor is null")

            } catch (e: Exception) {
                Log.e(TAG, "Error syncing call history", e)
            }
        }
    }

    private fun checkCallPermissions(): Boolean {
        val hasReadPhoneState = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val hasAnswerCalls = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        return hasReadPhoneState && hasAnswerCalls
    }

    /**
     * Listen for audio routing requests from desktop
     */
    private fun listenForAudioRoutingRequests() {
        serviceScope.launch {
            try {
                val userId = syncService.getCurrentUserId()
                val audioRoutingRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("audio_routing_requests")

                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        snapshot.children.forEach { requestSnapshot ->
                            val callId = requestSnapshot.child("callId").value as? String
                            val enable = requestSnapshot.child("enable").value as? Boolean ?: false
                            val processed = requestSnapshot.child("processed").value as? Boolean ?: false

                            if (!processed && callId != null) {
                                Log.d(TAG, "Audio routing request: enable=$enable for call $callId")

                                if (enable) {
                                    startAudioRouting(callId)
                                } else {
                                    stopAudioRouting()
                                }

                                // Mark as processed
                                requestSnapshot.ref.child("processed").setValue(true)
                            }
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Error listening for audio routing requests", error.toException())
                    }
                }

                audioRoutingListener?.let { existing ->
                    this@CallMonitorService.audioRoutingRef?.removeEventListener(existing)
                }
                this@CallMonitorService.audioRoutingRef = audioRoutingRef
                audioRoutingListener = listener
                audioRoutingRef.addValueEventListener(listener)

                Log.d(TAG, "Listening for audio routing requests from Firebase")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up audio routing listener", e)
            }
        }
    }

    private fun stopFirebaseListeners() {
        callCommandsListener?.let { listener ->
            callCommandsQuery?.removeEventListener(listener)
        }
        callCommandsListener = null
        callCommandsQuery = null

        callRequestsListener?.let { listener ->
            callRequestsRef?.removeEventListener(listener)
        }
        callRequestsListener = null
        callRequestsRef = null

        audioRoutingListener?.let { listener ->
            audioRoutingRef?.removeEventListener(listener)
        }
        audioRoutingListener = null
        audioRoutingRef = null
    }

    /**
     * Start WebRTC audio routing to desktop
     */
    private fun startAudioRouting(callId: String) {
        if (audioRoutingEnabled) {
            Log.w(TAG, "Audio routing already active")
            return
        }

        Log.d(TAG, "Starting audio routing for call: $callId")

        serviceScope.launch {
            try {
                val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioManager?.startAudioRouting(callId) ?: false
                } else {
                    false
                }

                if (success) {
                    // Setup signaling callbacks
                    audioManager?.onLocalDescriptionCreated = { sdp ->
                        serviceScope.launch {
                            signalingService?.sendOffer(callId, sdp.description)
                        }
                    }

                    audioManager?.onIceCandidateListener = { candidate ->
                        serviceScope.launch {
                            signalingService?.sendIceCandidate(callId, candidate)
                        }
                    }

                    // Listen for answer and ICE candidates from desktop
                    signalingService?.onAnswerReceived = { sdp ->
                        audioManager?.setRemoteAnswer(sdp)
                    }

                    signalingService?.onIceCandidateReceived = { candidate ->
                        audioManager?.addIceCandidate(candidate)
                    }

                    signalingService?.listenForAnswer(callId)
                    signalingService?.listenForIceCandidates(callId)

                    audioRoutingEnabled = true
                    Log.d(TAG, "Audio routing started successfully")
                } else {
                    Log.e(TAG, "Failed to start audio routing")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting audio routing", e)
            }
        }
    }

    /**
     * Stop WebRTC audio routing
     */
    private fun stopAudioRouting() {
        if (!audioRoutingEnabled) {
            return
        }

        Log.d(TAG, "Stopping audio routing")

        audioManager?.stopAudioRouting()
        currentCallId?.let { callId ->
            signalingService?.stopListening(callId)
            serviceScope.launch {
                signalingService?.cleanupSignaling(callId)
            }
        }

        audioRoutingEnabled = false
        Log.d(TAG, "Audio routing stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors phone calls for web integration"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SyncFlow Call Monitor")
            .setContentText("Monitoring calls for web integration")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        return builder.build()
    }
}
