package com.phoneintegration.app.webrtc

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.Log
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.phoneintegration.app.models.SyncFlowCall
import com.phoneintegration.app.models.SyncFlowDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import org.webrtc.*
import java.util.UUID

/**
 * Manages SyncFlow-to-SyncFlow audio/video calls using WebRTC.
 * Handles peer connection, media tracks, and Firebase signaling.
 * Note: Always uses applicationContext internally to prevent memory leaks.
 */
class SyncFlowCallManager(context: Context) {
    // Always use applicationContext to prevent Activity memory leaks
    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "SyncFlowCallManager"

        // ICE servers for NAT traversal
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )

        // Audio constraints
        private val AUDIO_CONSTRAINTS = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
    }

    // Firebase
    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // WebRTC components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localVideoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // EGL context for video rendering
    private var eglBase: EglBase? = null
    private var isInitialized = false

    // Audio manager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Ringtone for outgoing call feedback
    private var ringbackPlayer: MediaPlayer? = null

    // State
    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _currentCall = MutableStateFlow<SyncFlowCall?>(null)
    val currentCall: StateFlow<SyncFlowCall?> = _currentCall.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isVideoEnabled = MutableStateFlow(true)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled.asStateFlow()

    private val _localVideoTrackFlow = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrackFlow: StateFlow<VideoTrack?> = _localVideoTrackFlow.asStateFlow()

    private val _remoteVideoTrackFlow = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrackFlow: StateFlow<VideoTrack?> = _remoteVideoTrackFlow.asStateFlow()

    private val _videoEffect = MutableStateFlow(VideoEffect.NONE)
    val videoEffect: StateFlow<VideoEffect> = _videoEffect.asStateFlow()

    // Firebase listeners
    private var callListener: ValueEventListener? = null
    private var answerListener: ValueEventListener? = null
    private var iceCandidatesListener: ChildEventListener? = null
    private var callStatusListener: ValueEventListener? = null  // Listen for remote party ending call
    private var callStatusRef: DatabaseReference? = null
    private var currentCallRef: DatabaseReference? = null
    private var disconnectJob: Job? = null

    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    sealed class CallState {
        object Idle : CallState()
        object Initializing : CallState()
        object Ringing : CallState()
        object Connecting : CallState()
        object Connected : CallState()
        data class Failed(val error: String) : CallState()
        object Ended : CallState()
    }

    /**
     * Initialize WebRTC components
     */
    fun initialize() {
        Log.d(TAG, "Initializing WebRTC")

        // Initialize EGL context
        eglBase = EglBase.create()

        // Initialize PeerConnectionFactory
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        Log.d(TAG, "WebRTC initialized successfully")
        isInitialized = true
    }

    private fun ensureInitialized() {
        if (!isInitialized || peerConnectionFactory == null || eglBase == null) {
            Log.d(TAG, "WebRTC not initialized, initializing now")
            initialize()
        }
    }

    /**
     * Get EGL base context for video rendering
     */
    fun getEglBaseContext(): EglBase.Context? = eglBase?.eglBaseContext

    /**
     * Start an outgoing call to a user by their phone number.
     * Looks up the recipient's UID and creates call signaling in their Firebase path.
     */
    suspend fun startCallToUser(
        recipientPhoneNumber: String,
        recipientName: String,
        isVideo: Boolean
    ): Result<String> = withContext(Dispatchers.Main) {
        try {
            ensureInitialized()
            _callState.value = CallState.Initializing

            val myUserId = auth.currentUser?.uid
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            // Normalize phone number (remove spaces, dashes, etc.)
            val normalizedPhone = recipientPhoneNumber.replace(Regex("[^0-9+]"), "")
            val phoneKey = normalizedPhone.replace("+", "")

            // Look up recipient's UID from phone number
            val recipientUidSnapshot = database.reference
                .child("phone_to_uid")
                .child(phoneKey)
                .get()
                .await()

            val recipientUid = recipientUidSnapshot.getValue(String::class.java)
                ?: return@withContext Result.failure(Exception("User not found on SyncFlow. They need to install SyncFlow to receive video calls."))

            val deviceId = getDeviceId()
            val deviceName = getDeviceName()
            val callId = UUID.randomUUID().toString()

            // Get my phone number for caller info (from Firebase or TelephonyManager)
            val myPhoneNumber = getMyPhoneNumber()

            // Create call object - mark as user call since we're calling a user by phone number
            val call = SyncFlowCall.createOutgoing(
                callId = callId,
                callerId = myUserId,  // Use UID instead of device ID
                callerName = deviceName,
                calleeId = recipientUid,
                calleeName = recipientName,
                isVideo = isVideo
            ).copy(isUserCall = true)

            _currentCall.value = call
            Log.d(TAG, "Created outgoing user call: callId=$callId, isUserCall=${call.isUserCall}")

            // Write call to RECIPIENT's Firebase path so they receive notification
            val callRef = database.reference
                .child("users")
                .child(recipientUid)
                .child("incoming_syncflow_calls")
                .child(callId)

            val callData = call.toMap().toMutableMap()
            callData["callerUid"] = myUserId
            callData["callerPhone"] = myPhoneNumber

            callRef.setValue(callData).await()
            currentCallRef = callRef

            // Also write to my path for tracking
            database.reference
                .child("users")
                .child(myUserId)
                .child("outgoing_syncflow_calls")
                .child(callId)
                .setValue(callData)

            // Send FCM push notification to wake up recipient's device
            sendCallNotificationToUser(recipientUid, callId, deviceName, myPhoneNumber, isVideo)

            // Setup peer connection for user call - ICE candidates go to recipient's path
            setupPeerConnectionForUserCall(recipientUid, callId, isOutgoing = true)

            // Create media tracks
            createMediaTracks(isVideo)

            // Create and send offer to recipient's incoming_syncflow_calls path
            createAndSendOfferForUserCall(recipientUid, callId)

            // Listen for answer and ICE candidates from recipient's path
            listenForAnswerForUserCall(recipientUid, callId)
            listenForIceCandidatesForUserCall(recipientUid, callId, isOutgoing = true)

            // Setup audio
            setupAudio()

            _callState.value = CallState.Ringing

            // Start ringback tone so caller hears feedback
            startRingbackTone()

            // Listen for call status changes (if remote party ends the call)
            listenForCallStatusChanges()

            // Start timeout timer for user call
            startCallTimeoutForUserCall(recipientUid, callId)

            Log.d(TAG, "Started call to $recipientName ($recipientPhoneNumber)")
            Result.success(callId)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting call", e)
            _callState.value = CallState.Failed(e.message ?: "Unknown error")
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Start an outgoing call to a device (for device-to-device calls)
     */
    suspend fun startCall(
        calleeDeviceId: String,
        calleeName: String,
        isVideo: Boolean
    ): Result<String> = withContext(Dispatchers.Main) {
        try {
            _callState.value = CallState.Initializing

            val userId = auth.currentUser?.uid
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            val deviceId = getDeviceId()
            val deviceName = getDeviceName()
            val callId = UUID.randomUUID().toString()

            // Create call object
            val call = SyncFlowCall.createOutgoing(
                callId = callId,
                callerId = deviceId,
                callerName = deviceName,
                calleeId = calleeDeviceId,
                calleeName = calleeName,
                isVideo = isVideo
            )

            _currentCall.value = call

            // Write call to Firebase
            val callRef = database.reference
                .child("users")
                .child(userId)
                .child("syncflow_calls")
                .child(callId)

            callRef.setValue(call.toMap()).await()
            currentCallRef = callRef

            // Setup peer connection
            setupPeerConnection(userId, callId, isOutgoing = true)

            // Create media tracks
            createMediaTracks(isVideo)

            // Create and send offer
            createAndSendOffer(userId, callId)

            // Listen for answer and ICE candidates
            listenForAnswer(userId, callId)
            listenForIceCandidates(userId, callId, isOutgoing = true)

            // Setup audio
            setupAudio()

            _callState.value = CallState.Ringing

            // Start timeout timer
            startCallTimeout(userId, callId)

            // Listen for call status changes (if remote party ends the call)
            listenForCallStatusChanges()

            Result.success(callId)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting call", e)
            _callState.value = CallState.Failed(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    private fun getMyPhoneNumberLocal(): String? {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            telephonyManager?.line1Number
        } catch (e: SecurityException) {
            Log.d(TAG, "No permission to read phone number from TelephonyManager")
            null
        }
    }

    /**
     * Get my phone number from Firebase SIMs data (preferred) or local TelephonyManager (fallback)
     */
    private suspend fun getMyPhoneNumber(): String {
        val userId = auth.currentUser?.uid ?: return "Unknown"

        return try {
            // First try to get from Firebase SIMs data
            val simsSnapshot = database.reference
                .child("users")
                .child(userId)
                .child("sims")
                .get()
                .await()

            val simsData = simsSnapshot.value as? Map<String, Map<String, Any?>>
            if (simsData != null) {
                for ((_, sim) in simsData) {
                    val phoneNumber = sim["phoneNumber"] as? String
                    if (!phoneNumber.isNullOrEmpty() && phoneNumber != "Unknown") {
                        Log.d(TAG, "Got phone number from Firebase SIMs")
                        return phoneNumber
                    }
                }
            }

            // Fallback to local TelephonyManager
            val localNumber = getMyPhoneNumberLocal()
            if (!localNumber.isNullOrEmpty()) {
                Log.d(TAG, "Got phone number from TelephonyManager")
                return localNumber
            }

            "Unknown"
        } catch (e: Exception) {
            Log.w(TAG, "Error getting phone number from Firebase: ${e.message}")
            getMyPhoneNumberLocal() ?: "Unknown"
        }
    }

    /**
     * Answer an incoming call
     */
    suspend fun answerCall(userId: String, callId: String, withVideo: Boolean): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                _callState.value = CallState.Connecting

                val callRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("syncflow_calls")
                    .child(callId)

                currentCallRef = callRef

                // Get call data
                val snapshot = callRef.get().await()
                val callData = snapshot.value as? Map<String, Any?>
                    ?: return@withContext Result.failure(Exception("Call not found"))

                val call = SyncFlowCall.fromMap(callId, callData)
                _currentCall.value = call

                // Update call status
                callRef.child("status").setValue("active").await()
                callRef.child("answeredAt").setValue(ServerValue.TIMESTAMP).await()

                // Listen for call status changes (if caller ends the call)
                listenForCallStatusChanges()

                // Setup peer connection
                setupPeerConnection(userId, callId, isOutgoing = false)

                // Create media tracks
                createMediaTracks(withVideo)

                // Get the offer and set as remote description
                val offer = call.offer
                    ?: return@withContext Result.failure(Exception("No offer in call"))

                val offerSdp = SessionDescription(
                    SessionDescription.Type.OFFER,
                    offer.sdp
                )

                // CRITICAL: Must await setRemoteDescription completion before creating answer
                val setRemoteResult = setRemoteDescriptionAsync(offerSdp)
                if (setRemoteResult.isFailure) {
                    val error = setRemoteResult.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "Failed to set remote description: $error")
                    return@withContext Result.failure(Exception("Failed to set remote description: $error"))
                }
                Log.d(TAG, "Remote description set from offer successfully")

                // Create and send answer
                createAndSendAnswer(userId, callId)

                // Listen for ICE candidates
                listenForIceCandidates(userId, callId, isOutgoing = false)

                // Setup audio
                setupAudio()

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error answering call", e)
                _callState.value = CallState.Failed(e.message ?: "Unknown error")
                Result.failure(e)
            }
        }

    /**
     * Reject an incoming call
     */
    suspend fun rejectCall(userId: String, callId: String): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                val callRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("syncflow_calls")
                    .child(callId)

                callRef.child("status").setValue("rejected").await()
                callRef.child("endedAt").setValue(ServerValue.TIMESTAMP).await()

                _callState.value = CallState.Ended
                cleanup()

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error rejecting call", e)
                Result.failure(e)
            }
        }

    /**
     * End the current call (handles both user-to-user and device-to-device calls)
     */
    suspend fun endCall(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "endCall() called")

            // Always stop ringback/ringtone first to prevent audio issues
            stopRingbackTone()

            val currentCallValue = _currentCall.value
            val callId = currentCallValue?.id

            Log.d(TAG, "endCall: callId=$callId, isUserCall=${currentCallValue?.isUserCall}, currentCallRef=$currentCallRef")

            // Use the stored call reference if available (this is set during call setup
            // and points to the correct Firebase path regardless of caller/callee)
            val callRef = currentCallRef
            if (callRef != null) {
                try {
                    callRef.child("status").setValue("ended").await()
                    callRef.child("endedAt").setValue(ServerValue.TIMESTAMP).await()
                    Log.d(TAG, "Call status updated to ended in Firebase via currentCallRef")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating call status in Firebase, continuing with cleanup", e)
                }
            } else if (callId != null) {
                // Fallback: try to determine the path manually
                val userId = auth.currentUser?.uid
                if (userId != null) {
                    val isUserCall = currentCallValue?.isUserCall ?: false
                    val callPath = if (isUserCall) "incoming_syncflow_calls" else "syncflow_calls"

                    Log.d(TAG, "Ending call at fallback path: users/$userId/$callPath/$callId")

                    val fallbackRef = database.reference
                        .child("users")
                        .child(userId)
                        .child(callPath)
                        .child(callId)

                    try {
                        fallbackRef.child("status").setValue("ended").await()
                        fallbackRef.child("endedAt").setValue(ServerValue.TIMESTAMP).await()
                        Log.d(TAG, "Call status updated to ended via fallback path")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating call status via fallback, continuing with cleanup", e)
                    }
                }
            } else {
                Log.w(TAG, "No call reference or call ID, just cleaning up")
            }

            _callState.value = CallState.Ended
            cleanup()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error ending call", e)
            // Always clean up even on error
            _callState.value = CallState.Ended
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Toggle microphone mute
     */
    fun toggleMute() {
        val newMuteState = !_isMuted.value
        _isMuted.value = newMuteState
        localAudioTrack?.setEnabled(!newMuteState)
        Log.d(TAG, "Mute toggled: $newMuteState")
    }

    /**
     * Toggle video
     */
    fun toggleVideo() {
        val newVideoState = !_isVideoEnabled.value
        _isVideoEnabled.value = newVideoState
        localVideoTrack?.setEnabled(newVideoState)
        Log.d(TAG, "Video toggled: $newVideoState")
    }

    fun toggleFaceFocus() {
        if (_videoEffect.value == VideoEffect.FACE_FOCUS) {
            _videoEffect.value = VideoEffect.NONE
            Log.d(TAG, "Face focus toggled off")
            return
        }

        Toast.makeText(
            context,
            "Face focus is temporarily disabled on Android",
            Toast.LENGTH_SHORT
        ).show()
        _videoEffect.value = VideoEffect.NONE
        Log.w(TAG, "Face focus toggle blocked (stability safeguard)")
    }

    fun toggleBackgroundBlur() {
        _videoEffect.value = if (_videoEffect.value == VideoEffect.BACKGROUND_BLUR) {
            VideoEffect.NONE
        } else {
            VideoEffect.BACKGROUND_BLUR
        }
        Log.d(TAG, "Background blur toggled: ${_videoEffect.value}")
    }

    /**
     * Switch camera (front/back)
     */
    fun switchCamera() {
        videoCapturer?.switchCamera(null)
        Log.d(TAG, "Camera switched")
    }

    /**
     * Set video surface views for rendering
     */
    fun setLocalVideoSink(sink: VideoSink) {
        localVideoTrack?.addSink(sink)
    }

    fun setRemoteVideoSink(sink: VideoSink) {
        _remoteVideoTrackFlow.value?.addSink(sink)
    }

    /**
     * Listen for incoming calls (device-to-device)
     */
    fun listenForIncomingCalls(userId: String): Flow<SyncFlowCall> = callbackFlow {
        val callsRef = database.reference
            .child("users")
            .child(userId)
            .child("syncflow_calls")

        val listener = callsRef.orderByChild("status").equalTo("ringing")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val callData = snapshot.value as? Map<String, Any?> ?: return
                    val call = SyncFlowCall.fromMap(snapshot.key ?: "", callData)

                    // Only notify for incoming calls (from macOS)
                    if (call.calleePlatform == "android" && call.isRinging) {
                        trySend(call)
                    }
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Incoming calls listener cancelled: ${error.message}")
                }
            })

        awaitClose {
            callsRef.removeEventListener(listener)
        }
    }

    /**
     * Listen for incoming user-to-user SyncFlow calls
     * These are calls from other SyncFlow users (identified by phone number)
     */
    fun listenForIncomingUserCalls(userId: String): Flow<IncomingUserCall> = callbackFlow {
        val callsRef = database.reference
            .child("users")
            .child(userId)
            .child("incoming_syncflow_calls")

        val listener = callsRef.orderByChild("status").equalTo("ringing")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val callData = snapshot.value as? Map<String, Any?> ?: return
                    val callId = snapshot.key ?: return

                    val callerUid = callData["callerUid"] as? String ?: return
                    val callerPhone = callData["callerPhone"] as? String ?: "Unknown"
                    val callerName = callData["callerName"] as? String ?: "Unknown"
                    val callerPlatform = callData["callerPlatform"] as? String ?: "unknown"
                    val callType = callData["callType"] as? String ?: "audio"
                    val isVideo = callType == "video"

                    Log.d(TAG, "Incoming user call from $callerName ($callerPhone)")

                    val incomingCall = IncomingUserCall(
                        callId = callId,
                        callerUid = callerUid,
                        callerPhone = callerPhone,
                        callerName = callerName,
                        callerPlatform = callerPlatform,
                        isVideo = isVideo
                    )

                    trySend(incomingCall)
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    // Check if call was cancelled or ended
                    val callData = snapshot.value as? Map<String, Any?> ?: return
                    val status = callData["status"] as? String ?: return

                    if (status != "ringing") {
                        Log.d(TAG, "Incoming call status changed to: $status")
                    }
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {
                    Log.d(TAG, "Incoming call removed")
                }

                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Incoming user calls listener cancelled: ${error.message}")
                }
            })

        awaitClose {
            callsRef.removeEventListener(listener)
        }
    }

    /**
     * Answer an incoming user-to-user call
     */
    suspend fun answerUserCall(callId: String, withVideo: Boolean): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                ensureInitialized()
                val myUserId = auth.currentUser?.uid
                    ?: return@withContext Result.failure(Exception("Not authenticated"))

                Log.d(TAG, "Answering user call: $callId, withVideo: $withVideo")
                _callState.value = CallState.Connecting

                val callRef = database.reference
                    .child("users")
                    .child(myUserId)
                    .child("incoming_syncflow_calls")
                    .child(callId)

                currentCallRef = callRef

                // Get call data
                val snapshot = callRef.get().await()
                val callData = snapshot.value as? Map<String, Any?>
                    ?: return@withContext Result.failure(Exception("Call not found"))

                val callerUid = callData["callerUid"] as? String
                    ?: return@withContext Result.failure(Exception("Caller UID not found"))

                // Parse and set the current call - explicitly mark as user call
                val call = SyncFlowCall.fromMap(callId, callData).copy(isUserCall = true)
                _currentCall.value = call
                Log.d(TAG, "Set current call: ${call.callerName}, isVideo: ${call.isVideo}, isUserCall: ${call.isUserCall}")

                // Update call status
                callRef.child("status").setValue("active").await()
                callRef.child("answeredAt").setValue(ServerValue.TIMESTAMP).await()

                // Setup peer connection - use myUserId because signaling is in my path
                setupPeerConnectionForUserCall(myUserId, callId, isOutgoing = false)

                // Get the offer and set as remote description FIRST
                // This tells WebRTC what tracks the caller is sending
                val offerData = callData["offer"] as? Map<String, Any?>
                    ?: return@withContext Result.failure(Exception("No offer in call"))
                val offerSdp = offerData["sdp"] as? String
                    ?: return@withContext Result.failure(Exception("No SDP in offer"))
                val offerType = offerData["type"] as? String ?: "offer"

                val offer = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(offerType),
                    offerSdp
                )

                // CRITICAL: Must await setRemoteDescription completion before creating answer
                // This was causing calls to fail - the answer was created before the offer was processed
                val setRemoteResult = setRemoteDescriptionAsync(offer)
                if (setRemoteResult.isFailure) {
                    val error = setRemoteResult.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "Failed to set remote description: $error")
                    return@withContext Result.failure(Exception("Failed to set remote description: $error"))
                }
                Log.d(TAG, "Remote description set from offer successfully")

                // Create media tracks AFTER setting remote description
                // This ensures our tracks are properly negotiated
                createMediaTracks(withVideo)
                Log.d(TAG, "Media tracks created, withVideo: $withVideo")

                // Create and send answer to my path (where caller is listening)
                createAndSendAnswerForUserCall(myUserId, callId)

                // Listen for ICE candidates from caller
                listenForIceCandidatesForUserCall(myUserId, callId, isOutgoing = false)

                // Listen for call status changes (if caller ends the call)
                listenForCallStatusChanges()

                // Setup audio
                setupAudio()

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error answering user call", e)
                _callState.value = CallState.Failed(e.message ?: "Unknown error")
                Result.failure(e)
            }
        }

    /**
     * Reject an incoming user-to-user call
     */
    suspend fun rejectUserCall(callId: String): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                val myUserId = auth.currentUser?.uid
                    ?: return@withContext Result.failure(Exception("Not authenticated"))

                val callRef = database.reference
                    .child("users")
                    .child(myUserId)
                    .child("incoming_syncflow_calls")
                    .child(callId)

                callRef.child("status").setValue("rejected").await()
                callRef.child("endedAt").setValue(ServerValue.TIMESTAMP).await()

                _callState.value = CallState.Ended
                cleanup()

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error rejecting user call", e)
                Result.failure(e)
            }
        }

    private fun setupPeerConnectionForUserCall(userId: String, callId: String, isOutgoing: Boolean) {
        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(TAG, "ICE candidate generated (user call)")
                        scope.launch {
                            sendIceCandidateForUserCall(userId, callId, it, isOutgoing)
                        }
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE connection state (user call): $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            cancelDisconnectTimeout()
                            stopRingbackTone()
                            _callState.value = CallState.Connected
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            cancelDisconnectTimeout()
                            stopRingbackTone()
                            _callState.value = CallState.Failed("Connection failed")
                            scope.launch { endUserCall() }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            scheduleDisconnectTimeout()
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            cancelDisconnectTimeout()
                            stopRingbackTone()
                            if (_callState.value != CallState.Ended && _callState.value != CallState.Idle) {
                                scope.launch { endUserCall() }
                            }
                        }
                        else -> {}
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    Log.d(TAG, "onTrack called (user call), transceiver: $transceiver")
                    transceiver?.receiver?.track()?.let { track ->
                        Log.d(TAG, "Remote track received (user call): kind=${track.kind()}, id=${track.id()}, enabled=${track.enabled()}")
                        if (track is VideoTrack) {
                            Log.d(TAG, "Setting remote video track (user call)")
                            _remoteVideoTrackFlow.value = track
                            track.setEnabled(true)
                        } else if (track is AudioTrack) {
                            Log.d(TAG, "Remote audio track received (user call)")
                            track.setEnabled(true)
                        }
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "Signaling state (user call): $state")
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    Log.d(TAG, "Peer connection state (user call): $newState")
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            Log.d(TAG, "WebRTC peer connection CONNECTED!")
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            Log.e(TAG, "WebRTC peer connection FAILED!")
                        }
                        else -> {}
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "ICE connection receiving change (user call): $receiving")
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "ICE gathering state (user call): $state")
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )
    }

    private suspend fun createAndSendOfferForUserCall(recipientUid: String, callId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), it)

                        // Send offer to recipient's incoming_syncflow_calls path
                        scope.launch {
                            try {
                                val offerData = mapOf(
                                    "sdp" to it.description,
                                    "type" to it.type.canonicalForm()
                                )
                                database.reference
                                    .child("users")
                                    .child(recipientUid)
                                    .child("incoming_syncflow_calls")
                                    .child(callId)
                                    .child("offer")
                                    .setValue(offerData)
                                    .await()

                                Log.d(TAG, "Offer sent for user call")
                                continuation.resume(Unit) {}
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending offer for user call", e)
                                continuation.cancel(e)
                            }
                        }
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create offer failed (user call): $error")
                    continuation.cancel(Exception(error))
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
    }

    private fun listenForAnswerForUserCall(recipientUid: String, callId: String) {
        val answerRef = database.reference
            .child("users")
            .child(recipientUid)
            .child("incoming_syncflow_calls")
            .child(callId)
            .child("answer")

        answerListener = answerRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val answerData = snapshot.value as? Map<String, Any?> ?: return
                val sdp = answerData["sdp"] as? String ?: return
                val type = answerData["type"] as? String ?: return

                Log.d(TAG, "Answer received for user call")

                val answer = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(type),
                    sdp
                )
                peerConnection?.setRemoteDescription(SdpObserverAdapter(), answer)
                _callState.value = CallState.Connecting
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Answer listener cancelled (user call): ${error.message}")
            }
        })
    }

    private suspend fun createAndSendAnswerForUserCall(userId: String, callId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), it)

                        // Send answer to my incoming_syncflow_calls path
                        scope.launch {
                            try {
                                val answerData = mapOf(
                                    "sdp" to it.description,
                                    "type" to it.type.canonicalForm()
                                )
                                database.reference
                                    .child("users")
                                    .child(userId)
                                    .child("incoming_syncflow_calls")
                                    .child(callId)
                                    .child("answer")
                                    .setValue(answerData)
                                    .await()

                                Log.d(TAG, "Answer sent for user call")
                                continuation.resume(Unit) {}
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending answer for user call", e)
                                continuation.cancel(e)
                            }
                        }
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create answer failed (user call): $error")
                    continuation.cancel(Exception(error))
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
    }

    private fun listenForIceCandidatesForUserCall(userId: String, callId: String, isOutgoing: Boolean) {
        val icePath = if (isOutgoing) "ice_callee" else "ice_caller"
        val iceRef = database.reference
            .child("users")
            .child(userId)
            .child("incoming_syncflow_calls")
            .child(callId)
            .child(icePath)

        iceCandidatesListener = iceRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val candidateData = snapshot.value as? Map<String, Any?> ?: return
                val candidate = candidateData["candidate"] as? String ?: return
                val sdpMid = candidateData["sdpMid"] as? String
                val sdpMLineIndex = (candidateData["sdpMLineIndex"] as? Number)?.toInt() ?: 0

                Log.d(TAG, "Remote ICE candidate received (user call)")

                val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                peerConnection?.addIceCandidate(iceCandidate)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "ICE candidates listener cancelled (user call): ${error.message}")
            }
        })
    }

    private suspend fun sendIceCandidateForUserCall(
        userId: String,
        callId: String,
        candidate: IceCandidate,
        isOutgoing: Boolean
    ) {
        val icePath = if (isOutgoing) "ice_caller" else "ice_callee"

        try {
            val candidateData = mapOf(
                "candidate" to candidate.sdp,
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex
            )

            database.reference
                .child("users")
                .child(userId)
                .child("incoming_syncflow_calls")
                .child(callId)
                .child(icePath)
                .push()
                .setValue(candidateData)
                .await()

            Log.d(TAG, "ICE candidate sent (user call)")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ICE candidate (user call)", e)
        }
    }

    /**
     * End the current user-to-user call
     */
    suspend fun endUserCall(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val myUserId = auth.currentUser?.uid
                ?: return@withContext Result.failure(Exception("Not authenticated"))
            val callId = _currentCall.value?.id
                ?: return@withContext Result.failure(Exception("No active call"))
            val call = _currentCall.value

            if (call?.isOutgoing == true && !call.calleeId.isNullOrBlank()) {
                val recipientRef = database.reference
                    .child("users")
                    .child(call.calleeId)
                    .child("incoming_syncflow_calls")
                    .child(callId)
                recipientRef.child("status").setValue("ended").await()
                recipientRef.child("endedAt").setValue(ServerValue.TIMESTAMP).await()

                val myCallRef = database.reference
                    .child("users")
                    .child(myUserId)
                    .child("outgoing_syncflow_calls")
                    .child(callId)
                myCallRef.child("status").setValue("ended").await()
                myCallRef.child("endedAt").setValue(ServerValue.TIMESTAMP).await()
            } else {
                // Update my incoming call path
                val callRef = database.reference
                    .child("users")
                    .child(myUserId)
                    .child("incoming_syncflow_calls")
                    .child(callId)
                callRef.child("status").setValue("ended").await()
                callRef.child("endedAt").setValue(ServerValue.TIMESTAMP).await()
            }

            _callState.value = CallState.Ended
            cleanup()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error ending user call", e)
            // Fall back to regular cleanup
            cleanup()
            Result.failure(e)
        }
    }

    private fun startCallTimeoutForUserCall(recipientUid: String, callId: String) {
        scope.launch {
            delay(SyncFlowCall.CALL_TIMEOUT_MS)

            // Check if still ringing
            if (_callState.value == CallState.Ringing) {
                Log.d(TAG, "User call timed out")

                // Update status in recipient's incoming_syncflow_calls path
                database.reference
                    .child("users")
                    .child(recipientUid)
                    .child("incoming_syncflow_calls")
                    .child(callId)
                    .child("status")
                    .setValue("missed")

                _callState.value = CallState.Failed("Call timed out - no answer")
                cleanup()
            }
        }
    }

    /**
     * Data class for incoming user-to-user calls
     */
    data class IncomingUserCall(
        val callId: String,
        val callerUid: String,
        val callerPhone: String,
        val callerName: String,
        val callerPlatform: String,
        val isVideo: Boolean
    )

    /**
     * Send FCM push notification to wake up recipient's device for incoming call.
     * This writes to a queue that Cloud Functions picks up to send the actual FCM.
     */
    private fun sendCallNotificationToUser(
        recipientUid: String,
        callId: String,
        callerName: String,
        callerPhone: String,
        isVideo: Boolean
    ) {
        Log.d(TAG, "Sending FCM call notification to user: $recipientUid")

        val notificationData = mapOf(
            "type" to "incoming_call",
            "callId" to callId,
            "callerName" to callerName,
            "callerPhone" to callerPhone,
            "isVideo" to isVideo.toString(),
            "timestamp" to System.currentTimeMillis()
        )

        // Write to fcm_notifications queue - Cloud Functions will pick this up
        database.reference
            .child("fcm_notifications")
            .child(recipientUid)
            .child(callId)
            .setValue(notificationData)
            .addOnSuccessListener {
                Log.d(TAG, "FCM notification queued for $recipientUid")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to queue FCM notification", e)
            }
    }

    /**
     * Listen for call status changes - when remote party ends the call
     */
    private fun listenForCallStatusChanges() {
        val callRef = currentCallRef
        if (callRef == null) {
            Log.w(TAG, "No currentCallRef for call status listener")
            return
        }

        callStatusRef?.let { ref ->
            callStatusListener?.let { listener ->
                ref.removeEventListener(listener)
            }
        }

        callStatusRef = callRef

        Log.d(TAG, "Starting to listen for call status changes at: $callRef")

        callStatusListener = callRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    Log.d(TAG, "Call removed from Firebase, ending locally")
                    if (_callState.value != CallState.Ended && _callState.value != CallState.Idle) {
                        stopRingbackTone()
                        _callState.value = CallState.Ended
                        cleanup()
                    }
                    return
                }

                val status = snapshot.child("status").getValue(String::class.java) ?: return
                Log.d(TAG, "Call status changed to: $status")

                when (status) {
                    "ended", "rejected", "missed", "failed" -> {
                        // Only end if we're not already in ended state
                        if (_callState.value != CallState.Ended && _callState.value != CallState.Idle) {
                            Log.d(TAG, "Remote party ended the call (status: $status)")
                            stopRingbackTone()
                            _callState.value = CallState.Ended
                            cleanup()
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Call status listener cancelled: ${error.message}")
            }
        })
    }

    // Private methods

    private fun setupPeerConnection(userId: String, callId: String, isOutgoing: Boolean) {
        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(TAG, "ICE candidate generated")
                        scope.launch {
                            sendIceCandidate(userId, callId, it, isOutgoing)
                        }
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE connection state: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            cancelDisconnectTimeout()
                            _callState.value = CallState.Connected
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            cancelDisconnectTimeout()
                            _callState.value = CallState.Failed("Connection failed")
                            scope.launch { endCall() }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            scheduleDisconnectTimeout()
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            cancelDisconnectTimeout()
                            if (_callState.value != CallState.Ended && _callState.value != CallState.Idle) {
                                scope.launch { endCall() }
                            }
                        }
                        else -> {}
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    Log.d(TAG, "onTrack called, transceiver: $transceiver")
                    transceiver?.receiver?.track()?.let { track ->
                        Log.d(TAG, "Remote track received: kind=${track.kind()}, id=${track.id()}, enabled=${track.enabled()}")
                        if (track is VideoTrack) {
                            Log.d(TAG, "Setting remote video track")
                            _remoteVideoTrackFlow.value = track
                            track.setEnabled(true)
                        } else if (track is AudioTrack) {
                            Log.d(TAG, "Remote audio track received")
                            track.setEnabled(true)
                        }
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "Signaling state: $state")
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )
    }

    private fun createMediaTracks(withVideo: Boolean) {
        Log.d(TAG, "Creating media tracks, withVideo: $withVideo")

        // Create audio track
        localAudioSource = peerConnectionFactory?.createAudioSource(AUDIO_CONSTRAINTS)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("audio0", localAudioSource)
        localAudioTrack?.setEnabled(true)
        Log.d(TAG, "Audio track created: $localAudioTrack")

        peerConnection?.addTrack(localAudioTrack, listOf("stream0"))

        // Create video track if needed
        if (withVideo) {
            createVideoTrack()
        } else {
            Log.d(TAG, "Video not requested, skipping video track creation")
        }
    }

    private fun createVideoTrack() {
        Log.d(TAG, "Creating video track...")
        val cameraEnumerator = if (Camera2Enumerator.isSupported(context)) {
            Log.d(TAG, "Using Camera2 enumerator")
            Camera2Enumerator(context)
        } else {
            Log.d(TAG, "Camera2 not supported, using Camera1 enumerator")
            Camera1Enumerator(true)
        }
        val deviceNames = cameraEnumerator.deviceNames
        Log.d(TAG, "Available cameras: ${deviceNames.toList()}")

        // Try front camera first, then back
        val frontCamera = deviceNames.find { cameraEnumerator.isFrontFacing(it) }
        val backCamera = deviceNames.find { cameraEnumerator.isBackFacing(it) }
        val cameraName = frontCamera ?: backCamera
        Log.d(TAG, "Selected camera: $cameraName (front: $frontCamera, back: $backCamera)")

        if (cameraName != null) {
            try {
                videoCapturer = cameraEnumerator.createCapturer(cameraName, null)
                if (videoCapturer == null && cameraEnumerator is Camera2Enumerator) {
                    Log.w(TAG, "Camera2 capturer unavailable, retrying with Camera1")
                    val camera1 = Camera1Enumerator(true)
                    val fallbackName = camera1.deviceNames.firstOrNull()
                    if (fallbackName != null) {
                        videoCapturer = camera1.createCapturer(fallbackName, null)
                    }
                }
                Log.d(TAG, "Video capturer created: $videoCapturer")
                if (videoCapturer == null) {
                    Log.e(TAG, "Failed to create video capturer")
                    return
                }

                surfaceTextureHelper = SurfaceTextureHelper.create(
                    "CaptureThread",
                    eglBase?.eglBaseContext
                )
                Log.d(TAG, "Surface texture helper created: $surfaceTextureHelper")

                localVideoSource = peerConnectionFactory?.createVideoSource(false)
                Log.d(TAG, "Video source created: $localVideoSource")

                val downstreamObserver = localVideoSource?.capturerObserver
                val effectObserver = downstreamObserver?.let { observer ->
                    VideoEffectCapturerObserver(observer) { _videoEffect.value }
                }

                videoCapturer?.initialize(
                    surfaceTextureHelper,
                    context,
                    effectObserver ?: localVideoSource?.capturerObserver
                )
                try {
                    videoCapturer?.startCapture(1280, 720, 30)
                    Log.d(TAG, "Video capture started at 1280x720@30fps")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start capture at 1280x720, retrying at 640x480", e)
                    videoCapturer?.startCapture(640, 480, 15)
                    Log.d(TAG, "Video capture started at 640x480@15fps")
                }

                localVideoTrack = peerConnectionFactory?.createVideoTrack("video0", localVideoSource)
                localVideoTrack?.setEnabled(true)
                _localVideoTrackFlow.value = localVideoTrack
                Log.d(TAG, "Local video track created and set: $localVideoTrack")

                peerConnection?.addTrack(localVideoTrack, listOf("stream0"))
                Log.d(TAG, "Video track added to peer connection")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating video track", e)
            }
        } else {
            Log.w(TAG, "No camera found")
        }
    }

    private suspend fun createAndSendOffer(userId: String, callId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), it)

                        // Send offer to Firebase
                        scope.launch {
                            try {
                                val offerData = mapOf(
                                    "sdp" to it.description,
                                    "type" to it.type.canonicalForm()
                                )
                                database.reference
                                    .child("users")
                                    .child(userId)
                                    .child("syncflow_calls")
                                    .child(callId)
                                    .child("offer")
                                    .setValue(offerData)
                                    .await()

                                Log.d(TAG, "Offer sent")
                                continuation.resume(Unit) {}
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending offer", e)
                                continuation.cancel(e)
                            }
                        }
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create offer failed: $error")
                    continuation.cancel(Exception(error))
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
    }

    private suspend fun createAndSendAnswer(userId: String, callId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), it)

                        // Send answer to Firebase
                        scope.launch {
                            try {
                                val answerData = mapOf(
                                    "sdp" to it.description,
                                    "type" to it.type.canonicalForm()
                                )
                                database.reference
                                    .child("users")
                                    .child(userId)
                                    .child("syncflow_calls")
                                    .child(callId)
                                    .child("answer")
                                    .setValue(answerData)
                                    .await()

                                Log.d(TAG, "Answer sent")
                                continuation.resume(Unit) {}
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending answer", e)
                                continuation.cancel(e)
                            }
                        }
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create answer failed: $error")
                    continuation.cancel(Exception(error))
                }
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
    }

    private fun listenForAnswer(userId: String, callId: String) {
        val answerRef = database.reference
            .child("users")
            .child(userId)
            .child("syncflow_calls")
            .child(callId)
            .child("answer")

        callListener = answerRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val answerData = snapshot.value as? Map<String, Any?> ?: return
                val sdp = answerData["sdp"] as? String ?: return
                val type = answerData["type"] as? String ?: return

                Log.d(TAG, "Answer received")

                val answerSdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(type),
                    sdp
                )
                peerConnection?.setRemoteDescription(SdpObserverAdapter(), answerSdp)

                _callState.value = CallState.Connecting
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Answer listener cancelled: ${error.message}")
            }
        })
    }

    private fun listenForIceCandidates(userId: String, callId: String, isOutgoing: Boolean) {
        val icePath = if (isOutgoing) "ice_callee" else "ice_caller"
        val iceRef = database.reference
            .child("users")
            .child(userId)
            .child("syncflow_calls")
            .child(callId)
            .child(icePath)

        iceCandidatesListener = iceRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val candidateData = snapshot.value as? Map<String, Any?> ?: return
                val candidate = candidateData["candidate"] as? String ?: return
                val sdpMid = candidateData["sdpMid"] as? String
                val sdpMLineIndex = (candidateData["sdpMLineIndex"] as? Number)?.toInt() ?: 0

                Log.d(TAG, "Remote ICE candidate received")

                val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
                peerConnection?.addIceCandidate(iceCandidate)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "ICE candidates listener cancelled: ${error.message}")
            }
        })
    }

    private suspend fun sendIceCandidate(
        userId: String,
        callId: String,
        candidate: IceCandidate,
        isOutgoing: Boolean
    ) {
        val icePath = if (isOutgoing) "ice_caller" else "ice_callee"

        try {
            val candidateData = mapOf(
                "candidate" to candidate.sdp,
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex
            )

            database.reference
                .child("users")
                .child(userId)
                .child("syncflow_calls")
                .child(callId)
                .child(icePath)
                .push()
                .setValue(candidateData)
                .await()

            Log.d(TAG, "ICE candidate sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ICE candidate", e)
        }
    }

    private fun setupAudio() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    /**
     * Start playing ringback tone (for caller while waiting for answer)
     */
    private fun startRingbackTone() {
        try {
            stopRingbackTone() // Stop any existing tone first

            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringbackPlayer = MediaPlayer().apply {
                setDataSource(context, ringtoneUri)
                isLooping = true
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                prepare()
                start()
            }
            Log.d(TAG, "Ringback tone started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting ringback tone", e)
        }
    }

    /**
     * Stop playing ringback tone - force stop with multiple fallbacks
     */
    private fun stopRingbackTone() {
        Log.d(TAG, "stopRingbackTone() called, ringbackPlayer=$ringbackPlayer")
        val player = ringbackPlayer
        ringbackPlayer = null  // Clear reference immediately to prevent double-stop

        if (player != null) {
            try {
                if (player.isPlaying) {
                    player.stop()
                    Log.d(TAG, "Ringback player stopped")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping ringback player (may already be stopped)", e)
            }

            try {
                player.reset()
                Log.d(TAG, "Ringback player reset")
            } catch (e: Exception) {
                Log.w(TAG, "Error resetting ringback player", e)
            }

            try {
                player.release()
                Log.d(TAG, "Ringback player released")
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing ringback player", e)
            }
        }
        Log.d(TAG, "Ringback tone cleanup complete")
    }

    private fun startCallTimeout(userId: String, callId: String) {
        scope.launch {
            delay(SyncFlowCall.CALL_TIMEOUT_MS)

            // Check if still ringing
            if (_callState.value == CallState.Ringing) {
                Log.d(TAG, "Call timed out")

                database.reference
                    .child("users")
                    .child(userId)
                    .child("syncflow_calls")
                    .child(callId)
                    .child("status")
                    .setValue("missed")

                _callState.value = CallState.Failed("Call timed out")
                cleanup()
            }
        }
    }

    private fun cleanup() {
        Log.d(TAG, "Cleaning up call resources")

        cancelDisconnectTimeout()

        // Stop ringback tone if playing
        stopRingbackTone()

        // Remove Firebase listeners
        callListener?.let { currentCallRef?.removeEventListener(it) }
        callListener = null

        answerListener?.let { currentCallRef?.child("answer")?.removeEventListener(it) }
        answerListener = null

        iceCandidatesListener?.let {
            currentCallRef?.child("ice_caller")?.removeEventListener(it)
            currentCallRef?.child("ice_callee")?.removeEventListener(it)
        }
        iceCandidatesListener = null

        // Remove call status listener
        callStatusListener?.let { listener ->
            callStatusRef?.removeEventListener(listener)
        }
        callStatusListener = null
        callStatusRef = null

        // Stop video capture
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        // Dispose video track
        localVideoTrack?.dispose()
        localVideoTrack = null
        _localVideoTrackFlow.value = null

        // Dispose audio track
        localAudioTrack?.dispose()
        localAudioTrack = null

        // Dispose sources
        localVideoSource?.dispose()
        localVideoSource = null
        localAudioSource?.dispose()
        localAudioSource = null

        // Dispose surface texture helper
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        // Close peer connection
        peerConnection?.close()
        peerConnection = null

        // Reset remote video
        _remoteVideoTrackFlow.value = null

        // Reset audio
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false

        // Reset state
        _currentCall.value = null
        _isMuted.value = false
        _isVideoEnabled.value = true
        _videoEffect.value = VideoEffect.NONE
        _callState.value = CallState.Idle
    }

    private fun scheduleDisconnectTimeout() {
        if (disconnectJob != null) return
        disconnectJob = scope.launch {
            delay(5000)
            if (_callState.value != CallState.Ended && _callState.value != CallState.Idle) {
                Log.d(TAG, "ICE disconnected too long, ending call")
                if (_currentCall.value?.isUserCall == true) {
                    endUserCall()
                } else {
                    endCall()
                }
            }
        }
    }

    private fun cancelDisconnectTimeout() {
        disconnectJob?.cancel()
        disconnectJob = null
    }

    /**
     * Release all resources
     */
    fun release() {
        cleanup()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
        scope.cancel()
    }

    private fun getDeviceId(): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    }

    private fun getDeviceName(): String {
        return android.os.Build.MODEL
    }

    /**
     * Simple SDP observer adapter with logging
     */
    private class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            Log.d(TAG, "SDP created successfully: type=${sdp?.type}")
        }
        override fun onSetSuccess() {
            Log.d(TAG, "SDP set successfully")
        }
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "SDP create failed: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "SDP set failed: $error")
        }
    }

    /**
     * Suspendable wrapper for setRemoteDescription that properly awaits completion.
     * This is critical - we must wait for the remote description to be set before
     * creating an answer, otherwise WebRTC will fail silently.
     */
    private suspend fun setRemoteDescriptionAsync(sdp: SessionDescription): Result<Unit> =
        suspendCancellableCoroutine { continuation ->
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    // Not used for setRemoteDescription
                }
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote description set successfully: type=${sdp.type}")
                    if (continuation.isActive) {
                        continuation.resume(Result.success(Unit)) {}
                    }
                }
                override fun onCreateFailure(error: String?) {
                    // Not used for setRemoteDescription
                }
                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Failed to set remote description: $error")
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(Exception("Failed to set remote description: $error"))) {}
                    }
                }
            }, sdp) ?: run {
                Log.e(TAG, "PeerConnection is null, cannot set remote description")
                if (continuation.isActive) {
                    continuation.resume(Result.failure(Exception("PeerConnection is null"))) {}
                }
            }
        }
}
