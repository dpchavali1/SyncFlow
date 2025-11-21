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
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.phoneintegration.app.desktop.DesktopSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    private val database = FirebaseDatabase.getInstance()

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

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        registerCallStateListener()
        listenForCallCommands()
        syncCallHistory()
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

                // Sync to Firebase
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
                    Log.d(TAG, "Call ended state synced to Firebase")
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing call ended state", e)
                }
            }
        }
        currentCallId = null
    }

    private fun listenForCallCommands() {
        serviceScope.launch {
            try {
                val userId = syncService.getCurrentUserId()
                val commandsRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("call_commands")

                commandsRef.addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        snapshot.children.forEach { commandSnapshot ->
                            val command = commandSnapshot.child("command").value as? String
                            val callId = commandSnapshot.child("callId").value as? String
                            val phoneNumber = commandSnapshot.child("phoneNumber").value as? String
                            val processed = commandSnapshot.child("processed").value as? Boolean ?: false

                            if (!processed && command != null) {
                                Log.d(TAG, "Received call command: $command for call: $callId, number: $phoneNumber")
                                handleCallCommand(command, callId, phoneNumber)

                                // Mark as processed
                                commandSnapshot.ref.child("processed").setValue(true)
                            }
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Error listening for call commands", error.toException())
                    }
                })

                Log.d(TAG, "Listening for call commands from Firebase")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up call commands listener", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCallCommand(command: String, callId: String?, phoneNumber: String?) {
        Log.d(TAG, "Handling call command: $command")

        if (!checkCallPermissions()) {
            Log.e(TAG, "Missing permissions to handle call command")
            return
        }

        try {
            when (command) {
                "answer" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        telecomManager.acceptRingingCall()
                        Log.d(TAG, "Call answered via TelecomManager")
                    } else {
                        Log.w(TAG, "Answer call not supported on API < 26")
                    }
                }
                "reject", "end" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        telecomManager.endCall()
                        Log.d(TAG, "Call ended via TelecomManager")
                    } else {
                        Log.w(TAG, "End call not supported on API < 28")
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
        }
    }

    @SuppressLint("MissingPermission")
    private fun makeOutgoingCall(phoneNumber: String) {
        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(callIntent)
            Log.d(TAG, "Initiated outgoing call to $phoneNumber")

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
                val cursor = contentResolver.query(
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
                    "${CallLog.Calls.DATE} DESC LIMIT 100"
                )

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
