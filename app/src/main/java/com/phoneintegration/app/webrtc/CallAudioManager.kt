package com.phoneintegration.app.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.webrtc.*

/**
 * Manages WebRTC peer connection and audio streaming for call audio routing to desktop
 */
class CallAudioManager(private val context: Context) {

    companion object {
        private const val TAG = "CallAudioManager"

        // ICE servers for WebRTC connection
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // WebRTC components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var localAudioStream: MediaStream? = null

    // Audio focus
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hadAudioFocus = false

    // State
    var isAudioRoutingActive = false
        private set

    // Callback for signaling events
    var onIceCandidateListener: ((IceCandidate) -> Unit)? = null
    var onLocalDescriptionCreated: ((SessionDescription) -> Unit)? = null
    var onConnectionStateChanged: ((PeerConnection.PeerConnectionState) -> Unit)? = null

    init {
        initializeWebRTC()
    }

    /**
     * Initialize WebRTC components
     */
    private fun initializeWebRTC() {
        Log.d(TAG, "Initializing WebRTC...")

        // Initialize PeerConnectionFactory
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()

        PeerConnectionFactory.initialize(options)

        // Create PeerConnectionFactory
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()

        Log.d(TAG, "WebRTC initialized successfully")
    }

    /**
     * Start audio routing to desktop (create offer)
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun startAudioRouting(callId: String): Boolean {
        if (isAudioRoutingActive) {
            Log.w(TAG, "Audio routing already active")
            return false
        }

        Log.d(TAG, "Starting audio routing for call: $callId")

        try {
            // Request audio focus
            requestAudioFocus()

            // Enable speakerphone so call audio can be captured
            enableSpeakerphone(true)

            // Create peer connection
            createPeerConnection()

            // Create audio track from microphone
            createAudioTrack()

            // Add audio track to peer connection
            localAudioStream = peerConnectionFactory?.createLocalMediaStream("LOCAL_AUDIO_STREAM")
            localAudioStream?.addTrack(audioTrack)
            peerConnection?.addStream(localAudioStream)

            // Create offer
            scope.launch {
                createOffer()
            }

            isAudioRoutingActive = true
            Log.d(TAG, "Audio routing started successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio routing", e)
            stopAudioRouting()
            return false
        }
    }

    /**
     * Enable/disable speakerphone for call audio routing
     */
    private fun enableSpeakerphone(enable: Boolean) {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = enable
            Log.d(TAG, "Speakerphone ${if (enable) "enabled" else "disabled"}")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting speakerphone", e)
        }
    }

    /**
     * Stop audio routing to desktop
     */
    fun stopAudioRouting() {
        Log.d(TAG, "Stopping audio routing...")

        // Remove tracks
        localAudioStream?.let { stream ->
            stream.audioTracks.forEach { it.dispose() }
            peerConnection?.removeStream(stream)
        }
        localAudioStream?.dispose()
        localAudioStream = null

        // Dispose audio components
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null

        // Close peer connection
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        // Disable speakerphone and release audio focus
        enableSpeakerphone(false)
        releaseAudioFocus()

        isAudioRoutingActive = false
        Log.d(TAG, "Audio routing stopped")
    }

    /**
     * Create peer connection
     */
    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d(TAG, "ICE candidate generated: ${it.sdp}")
                    onIceCandidateListener?.invoke(it)
                }
            }

            override fun onDataChannel(dataChannel: DataChannel?) {}

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "ICE connection receiving change: $receiving")
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $newState")
            }

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state: $newState")
            }

            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "Stream added: ${stream?.id}")
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $newState")
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onRemoveStream(stream: MediaStream?) {
                Log.d(TAG, "Stream removed: ${stream?.id}")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "Track added")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "Connection state: $newState")
                newState?.let { onConnectionStateChanged?.invoke(it) }
            }
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
        Log.d(TAG, "Peer connection created")
    }

    /**
     * Create audio track from microphone
     */
    private fun createAudioTrack() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        audioSource = peerConnectionFactory?.createAudioSource(constraints)
        audioTrack = peerConnectionFactory?.createAudioTrack("AUDIO_TRACK", audioSource)
        audioTrack?.setEnabled(true)

        Log.d(TAG, "Audio track created")
    }

    /**
     * Create SDP offer
     */
    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                sessionDescription?.let { sdp ->
                    Log.d(TAG, "Offer created successfully")
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set")
                            onLocalDescriptionCreated?.invoke(sdp)
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Failed to set local description: $error")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, sdp)
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create offer: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * Set remote SDP answer from desktop
     */
    fun setRemoteAnswer(sdp: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set successfully")
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sessionDescription)
    }

    /**
     * Add remote ICE candidate from desktop
     */
    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
        Log.d(TAG, "Added remote ICE candidate")
    }

    /**
     * Request audio focus for call
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .build()

            val result = audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
            hadAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

            if (hadAudioFocus) {
                Log.d(TAG, "Audio focus granted")
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
        }
    }

    /**
     * Release audio focus
     */
    private fun releaseAudioFocus() {
        if (hadAudioFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            hadAudioFocus = false
            Log.d(TAG, "Audio focus released")
        }
    }

    /**
     * Clean up resources
     */
    fun dispose() {
        stopAudioRouting()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        PeerConnectionFactory.shutdownInternalTracer()
        Log.d(TAG, "CallAudioManager disposed")
    }
}
