//
//  SyncFlowCallManager.swift
//  SyncFlowMac
//
//  Created by SyncFlow Team
//  Copyright (c) SyncFlow. All rights reserved.
//
//  ============================================================================
//  PURPOSE
//  ============================================================================
//  SyncFlowCallManager handles peer-to-peer audio/video calls between SyncFlow
//  devices using WebRTC. This enables direct calls between the macOS app and
//  Android app (or other SyncFlow clients) without going through traditional
//  phone networks.
//
//  Key Features:
//  - WebRTC-based peer-to-peer calling
//  - Audio and video tracks with effects (blur, face focus)
//  - Screen sharing capability
//  - Call recording
//  - Firebase-based signaling (offer/answer/ICE candidates)
//  - STUN/TURN NAT traversal for connection establishment
//
//  ============================================================================
//  ARCHITECTURE
//  ============================================================================
//  The call flow follows the standard WebRTC signaling pattern:
//
//  ```
//  Caller (Mac)                    Firebase                    Callee (Android)
//      |                              |                              |
//      |---- Create Offer ----------->|                              |
//      |                              |---- Notify Incoming Call --->|
//      |                              |<--- Create Answer -----------|
//      |<--- Receive Answer ----------|                              |
//      |                              |                              |
//      |---- ICE Candidates --------->|<----- ICE Candidates --------|
//      |<--- ICE Candidates ----------|------- ICE Candidates ------>|
//      |                              |                              |
//      |================= WebRTC P2P Connection =====================|
//  ```
//
//  Firebase Database Structure for Calls:
//  ```
//  users/{userId}/syncflow_calls/{callId}/
//    - status: "ringing" | "active" | "ended" | "rejected"
//    - offer: { sdp, type }
//    - answer: { sdp, type }
//    - ice_caller/: { candidate, sdpMid, sdpMLineIndex }
//    - ice_callee/: { candidate, sdpMid, sdpMLineIndex }
//  ```
//
//  ============================================================================
//  NAT TRAVERSAL
//  ============================================================================
//  Uses STUN servers to discover public IP addresses and TURN servers
//  as relay fallback when direct connection fails:
//
//  - STUN: Google's public STUN servers for NAT discovery
//  - TURN: OpenRelay public TURN servers for relay
//  - Note: Production apps should use private TURN servers (coturn, Twilio)
//
//  ============================================================================
//  DEPENDENCIES
//  ============================================================================
//  - WebRTC: Google's WebRTC framework for real-time communication
//  - FirebaseDatabase: Signaling channel for offer/answer exchange
//  - ScreenCaptureKit: macOS screen sharing (macOS 12.3+)
//  - AVFoundation: Camera and microphone access
//  - CoreImage: Video effects processing
//

import Foundation
import Combine
import AVFoundation
import CoreImage
import CoreVideo
import WebRTC
import FirebaseDatabase
import FirebaseAuth
import ScreenCaptureKit

// MARK: - SyncFlowCallManager

/// Manages WebRTC-based audio/video calls between SyncFlow devices.
///
/// SyncFlowCallManager handles the complete lifecycle of peer-to-peer calls:
/// initiating calls, answering incoming calls, managing media tracks,
/// and cleaning up resources when calls end.
///
/// ## Usage - Starting a Call
/// ```swift
/// let callManager = SyncFlowCallManager()
///
/// // Start a video call to a user
/// let callId = try await callManager.startCallToUser(
///     recipientPhoneNumber: "+15551234567",
///     recipientName: "John",
///     isVideo: true
/// )
/// ```
///
/// ## Usage - Answering a Call
/// ```swift
/// // Listen for incoming calls
/// callManager.startListeningForIncomingUserCalls(userId: userId)
///
/// // When incomingUserCall is set, answer it
/// if let incoming = callManager.incomingUserCall {
///     try await callManager.answerUserCall(callId: incoming.callId, withVideo: true)
/// }
/// ```
///
/// ## Thread Safety
/// - All @Published properties are updated on the main thread
/// - WebRTC callbacks may come from background threads
/// - Firebase listeners dispatch to main thread via DispatchQueue.main
class SyncFlowCallManager: NSObject, ObservableObject {

    // MARK: - Published State (UI-Bound)

    /// Current state of the call (idle, ringing, connected, etc.).
    @Published var callState: CallState = .idle

    /// The active call object with participant info.
    @Published var currentCall: SyncFlowCall?

    /// Whether the local microphone is muted.
    @Published var isMuted = false

    /// Whether local video is enabled (camera on).
    @Published var isVideoEnabled = true

    /// Whether speaker output is enabled (vs earpiece on mobile).
    @Published var isSpeakerOn = true

    /// Active video effect (none, face focus, background blur).
    @Published var videoEffect: VideoEffect = .none

    // MARK: - Video Tracks

    /// Local camera video track for preview display.
    @Published var localVideoTrack: RTCVideoTrack?

    /// Remote peer's video track for main display.
    @Published var remoteVideoTrack: RTCVideoTrack?

    // MARK: - Screen Sharing State

    /// Whether screen sharing is currently active.
    @Published var isScreenSharing = false

    /// Screen share video track (if sharing).
    @Published var screenShareTrack: RTCVideoTrack?

    /// Service handling ScreenCaptureKit integration.
    private var screenCaptureService: ScreenCaptureService?

    // MARK: - Call Recording State

    /// Whether call recording is currently active.
    @Published var isRecordingCall = false

    /// Duration of the current recording in seconds.
    @Published var recordingDuration: TimeInterval = 0

    /// Service handling call audio recording.
    private var callRecordingService: CallRecordingService?

    // MARK: - Call State Enum

    /// Represents the current state of a call.
    enum CallState: Equatable {
        /// No active call.
        case idle
        /// Setting up WebRTC and preparing to call.
        case initializing
        /// Outgoing call is ringing, waiting for answer.
        case ringing
        /// Call answered, establishing WebRTC connection.
        case connecting
        /// Call is active with media flowing.
        case connected
        /// Call failed with error message.
        case failed(String)
        /// Call has ended normally.
        case ended
    }

    // MARK: - ICE Server Configuration

    /// STUN servers for NAT traversal - discover public IP address.
    ///
    /// STUN (Session Traversal Utilities for NAT) servers help WebRTC
    /// discover the public IP address when behind a NAT router.
    /// Google provides free public STUN servers.
    private static let stunServers = [
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun2.l.google.com:19302",
        "stun:stun.cloudflare.com:3478"
    ]

    /// TURN servers for relay when direct P2P connection fails.
    ///
    /// TURN (Traversal Using Relays around NAT) servers relay media
    /// when direct peer-to-peer connection isn't possible (e.g., symmetric NAT).
    ///
    /// - Warning: These are free public TURN servers with limited capacity.
    ///   For production, use private TURN servers (coturn, Twilio, etc.).
    private static let turnServers: [(url: String, username: String, credential: String)] = [
        // OpenRelay free TURN servers (metered.ca)
        ("turn:openrelay.metered.ca:80", "openrelayproject", "openrelayproject"),
        ("turn:openrelay.metered.ca:443", "openrelayproject", "openrelayproject"),
        ("turn:openrelay.metered.ca:443?transport=tcp", "openrelayproject", "openrelayproject")
    ]

    /// Combined ICE server configuration for WebRTC.
    ///
    /// ICE (Interactive Connectivity Establishment) uses both STUN and TURN
    /// servers to find the best path for media connection.
    private static var iceServers: [RTCIceServer] {
        var servers: [RTCIceServer] = []

        // Add STUN servers (lightweight, for NAT discovery)
        servers.append(RTCIceServer(urlStrings: stunServers))

        // Add TURN servers with credentials (heavyweight, for relay)
        for turn in turnServers {
            servers.append(RTCIceServer(
                urlStrings: [turn.url],
                username: turn.username,
                credential: turn.credential
            ))
        }

        return servers
    }

    // MARK: - Timeouts and Limits

    /// Seconds to wait for callee to answer before timing out.
    private static let callTimeoutSeconds: TimeInterval = 60

    /// Maximum ICE connection retry attempts before failing the call.
    private static let maxConnectionRetries = 3

    // MARK: - WebRTC Components

    /// Factory for creating WebRTC peer connections and media tracks.
    private var peerConnectionFactory: RTCPeerConnectionFactory?

    /// The WebRTC peer connection for media exchange.
    private var peerConnection: RTCPeerConnection?

    // MARK: - Media Tracks

    /// Local audio track from microphone.
    private var localAudioTrack: RTCAudioTrack?

    /// Source for local audio (microphone).
    private var localAudioSource: RTCAudioSource?

    /// Source for local video (camera).
    private var localVideoSource: RTCVideoSource?

    /// Camera capture device.
    private var videoCapturer: RTCCameraVideoCapturer?

    /// Delegate for applying video effects (blur, face focus).
    private var videoEffectsDelegate: VideoEffectsCapturerDelegate?

    // MARK: - Firebase

    /// Firebase Realtime Database reference.
    private let database = Database.database()

    /// Ensures Firebase connection is active before writes.
    private func ensureOnline() {
        database.goOnline()
    }

    // MARK: - Firebase Listener Handles

    /// Handle for incoming call notifications.
    private var callListenerHandle: DatabaseHandle?

    /// Handle for answer SDP listener.
    private var answerListenerHandle: DatabaseHandle?

    /// Handle for ICE candidate listener.
    private var iceListenerHandle: DatabaseHandle?

    /// Handle for call status changes listener.
    private var statusListenerHandle: DatabaseHandle?

    /// Reference to current call in Firebase.
    private var currentCallRef: DatabaseReference?

    // MARK: - User Identity

    /// Gets the current user ID, preferring stored paired ID over auth user.
    ///
    /// This ensures call data is written to the correct Firebase path
    /// even if the auth session has expired.
    private var currentUserId: String? {
        UserDefaults.standard.string(forKey: "syncflow_user_id") ?? Auth.auth().currentUser?.uid
    }

    // MARK: - User-to-User Call Tracking

    /// Whether current call is a user-to-user call (vs device-to-device).
    private var isUserCall: Bool = false

    /// For outgoing calls: the recipient's Firebase UID.
    private var userCallRecipientUid: String?

    /// For incoming calls: the caller's Firebase UID.
    private var userCallCallerUid: String?

    /// The UID used for signaling (ICE candidates, answers).
    private var userCallSignalingUid: String?

    // MARK: - Call Management

    /// Timer for call timeout (no answer).
    private var callTimeoutTimer: Timer?

    /// Number of ICE connection retry attempts.
    private var connectionRetryCount = 0

    /// ICE candidates received before remote description was set.
    /// These are buffered and processed once remote description is available.
    private var pendingIceCandidates: [RTCIceCandidate] = []

    /// Whether the remote SDP description has been set.
    /// ICE candidates cannot be added until this is true.
    private var hasRemoteDescription = false

    /// Combine cancellables for reactive subscriptions.
    private var cancellables = Set<AnyCancellable>()

    // MARK: - Initialization

    /// Creates a new SyncFlowCallManager and initializes WebRTC.
    override init() {
        super.init()
        initializeWebRTC()
    }

    /// Initializes the WebRTC framework and peer connection factory.
    ///
    /// This must be called before any WebRTC operations. It sets up:
    /// - SSL for secure communication
    /// - Video encoder/decoder factories for hardware acceleration
    /// - Peer connection factory for creating connections and tracks
    private func initializeWebRTC() {
        // Initialize SSL for secure WebRTC communication
        RTCInitializeSSL()

        // Use default factories which leverage hardware encoding/decoding
        let encoderFactory = RTCDefaultVideoEncoderFactory()
        let decoderFactory = RTCDefaultVideoDecoderFactory()

        peerConnectionFactory = RTCPeerConnectionFactory(
            encoderFactory: encoderFactory,
            decoderFactory: decoderFactory
        )

        print("SyncFlowCallManager: WebRTC initialized")
    }

    // MARK: - Public Methods - Device Calls

    /// Starts an outgoing call to a specific device.
    ///
    /// This initiates a device-to-device call (e.g., Mac to Android device).
    /// Use `startCallToUser` for user-to-user calls via phone number lookup.
    ///
    /// - Parameters:
    ///   - userId: Firebase user ID owning both devices
    ///   - calleeDeviceId: Target device's unique ID
    ///   - calleeName: Display name for the callee
    ///   - isVideo: Whether to enable video
    /// - Returns: The unique call ID
    /// - Throws: WebRTC or Firebase errors
    func startCall(
        userId: String,
        calleeDeviceId: String,
        calleeName: String,
        isVideo: Bool
    ) async throws -> String {
        ensureOnline()
        await MainActor.run {
            callState = .initializing
        }

        let callId = UUID().uuidString
        let deviceId = getDeviceId()
        let deviceName = Host.current().localizedName ?? "Mac"

        let call = SyncFlowCall.createOutgoing(
            callId: callId,
            callerId: deviceId,
            callerName: deviceName,
            calleeId: calleeDeviceId,
            calleeName: calleeName,
            isVideo: isVideo
        )

        await MainActor.run {
            currentCall = call
        }

        // Write call to Firebase
        let callRef = database.reference()
            .child("users")
            .child(userId)
            .child("syncflow_calls")
            .child(callId)

        try await callRef.setValue(call.toDict())
        currentCallRef = callRef

        // Setup peer connection
        setupPeerConnection(userId: userId, callId: callId, isOutgoing: true)

        // Create media tracks
        try createMediaTracks(withVideo: isVideo)

        // Create and send offer
        try await createAndSendOffer(userId: userId, callId: callId)

        // Listen for answer
        listenForAnswer(userId: userId, callId: callId)

        // Listen for ICE candidates
        listenForIceCandidates(userId: userId, callId: callId, isOutgoing: true)

        await MainActor.run {
            callState = .ringing
        }

        // Start timeout timer
        startCallTimeout(userId: userId, callId: callId)

        print("SyncFlowCallManager: Call started to \(calleeName)")
        return callId
    }

    /// Answers an incoming device-to-device call.
    ///
    /// - Parameters:
    ///   - userId: Firebase user ID
    ///   - callId: The call ID to answer
    ///   - withVideo: Whether to enable video
    /// - Throws: SyncFlowCallError if call not found or WebRTC fails
    func answerCall(userId: String, callId: String, withVideo: Bool) async throws {
        ensureOnline()

        await MainActor.run {
            callState = .connecting
        }

        let callRef = database.reference()
            .child("users")
            .child(userId)
            .child("syncflow_calls")
            .child(callId)

        currentCallRef = callRef

        // Get call data
        let snapshot = try await callRef.getData()
        guard let callData = snapshot.value as? [String: Any],
              var call = SyncFlowCall.from(id: callId, dict: callData) else {
            throw SyncFlowCallError.callNotFound
        }

        await MainActor.run {
            currentCall = call
        }

        // Update call status
        try await callRef.child("status").setValue("active")
        try await callRef.child("answeredAt").setValue(ServerValue.timestamp())

        // Setup peer connection
        setupPeerConnection(userId: userId, callId: callId, isOutgoing: false)

        // Create media tracks
        try createMediaTracks(withVideo: withVideo)

        // Get offer and set as remote description
        guard let offer = call.offer else {
            throw SyncFlowCallError.noOffer
        }

        let offerSdp = RTCSessionDescription(type: .offer, sdp: offer.sdp)
        try await peerConnection?.setRemoteDescription(offerSdp)

        // Create and send answer
        try await createAndSendAnswer(userId: userId, callId: callId)

        // Listen for ICE candidates
        listenForIceCandidates(userId: userId, callId: callId, isOutgoing: false)

        cancelCallTimeout()

        print("SyncFlowCallManager: Answered call from \(call.callerName)")
    }

    /// Rejects an incoming call.
    ///
    /// Updates the call status to "rejected" and cleans up resources.
    ///
    /// - Parameters:
    ///   - userId: Firebase user ID
    ///   - callId: The call ID to reject
    func rejectCall(userId: String, callId: String) async throws {
        ensureOnline()

        let callRef = database.reference()
            .child("users")
            .child(userId)
            .child("syncflow_calls")
            .child(callId)

        try await callRef.child("status").setValue("rejected")
        try await callRef.child("endedAt").setValue(ServerValue.timestamp())

        await MainActor.run {
            callState = .ended
        }

        cleanup()
        print("SyncFlowCallManager: Rejected call")
    }

    /// Ends the current active call.
    ///
    /// Updates Firebase status, closes WebRTC connection, and releases
    /// all media resources (camera, microphone, screen share).
    ///
    /// - Note: Automatically delegates to `endUserCall()` for user-to-user calls.
    func endCall() async throws {
        ensureOnline()

        // For user-to-user calls, delegate to endUserCall
        if isUserCall {
            print("SyncFlowCallManager: endCall() - delegating to endUserCall() for user call")
            try await endUserCall()
            return
        }

        defer { cleanup() }

        guard let call = currentCall,
              let userId = currentUserId else {
            print("SyncFlowCallManager: endCall() - no current call or not authenticated")
            return
        }

        let callRef = database.reference()
            .child("users")
            .child(userId)
            .child("syncflow_calls")
            .child(call.id)

        do {
            try await callRef.child("status").setValue("ended")
            try await callRef.child("endedAt").setValue(ServerValue.timestamp())
        } catch {
            print("SyncFlowCallManager: endCall() failed to update Firebase: \(error)")
        }

        await MainActor.run {
            callState = .ended
        }

        print("SyncFlowCallManager: Ended call")
    }

    // MARK: - Call Controls

    /// Toggles microphone mute state.
    ///
    /// When muted, the local audio track is disabled but the connection
    /// remains active. The remote party sees you as muted.
    func toggleMute() {
        isMuted.toggle()
        localAudioTrack?.isEnabled = !isMuted
        print("SyncFlowCallManager: Mute toggled: \(isMuted)")
    }

    /// Toggles local video on/off.
    ///
    /// When disabled, the camera stops sending frames but the video track
    /// remains in the connection. The remote party sees a black frame.
    func toggleVideo() {
        isVideoEnabled.toggle()
        localVideoTrack?.isEnabled = isVideoEnabled
        print("SyncFlowCallManager: Video toggled: \(isVideoEnabled)")
    }

    /// Toggles face focus video effect.
    ///
    /// Face focus uses a radial blur that keeps the center (face area)
    /// sharp while blurring the periphery.
    func toggleFaceFocus() {
        videoEffect = videoEffect == .faceFocus ? .none : .faceFocus
        print("SyncFlowCallManager: Face focus toggled: \(videoEffect)")
    }

    /// Toggles background blur video effect.
    ///
    /// Background blur uses a stronger blur with wider focus area
    /// to simulate a blurred background effect.
    func toggleBackgroundBlur() {
        videoEffect = videoEffect == .backgroundBlur ? .none : .backgroundBlur
        print("SyncFlowCallManager: Background blur toggled: \(videoEffect)")
    }

    // MARK: - Screen Sharing

    /// Gets the screen capture service, creating it if necessary.
    ///
    /// - Returns: ScreenCaptureService instance for managing screen sharing
    /// - Requires: macOS 12.3 or later for ScreenCaptureKit support
    @available(macOS 12.3, *)
    func getScreenCaptureService() -> ScreenCaptureService {
        if screenCaptureService == nil, let factory = peerConnectionFactory {
            screenCaptureService = ScreenCaptureService(peerConnectionFactory: factory)
        }
        return screenCaptureService!
    }

    /// Start sharing a display
    @available(macOS 12.3, *)
    func startScreenShare(display: SCDisplay) async throws {
        guard let factory = peerConnectionFactory else { return }

        let service = getScreenCaptureService()
        try await service.startSharingDisplay(display)

        if let track = service.getVideoTrack() {
            // Add screen share track to peer connection
            peerConnection?.add(track, streamIds: ["screen0"])

            await MainActor.run {
                screenShareTrack = track
                isScreenSharing = true
            }

            print("SyncFlowCallManager: Screen sharing started (display)")
        }
    }

    /// Start sharing a window
    @available(macOS 12.3, *)
    func startScreenShare(window: SCWindow) async throws {
        guard let factory = peerConnectionFactory else { return }

        let service = getScreenCaptureService()
        try await service.startSharingWindow(window)

        if let track = service.getVideoTrack() {
            // Add screen share track to peer connection
            peerConnection?.add(track, streamIds: ["screen0"])

            await MainActor.run {
                screenShareTrack = track
                isScreenSharing = true
            }

            print("SyncFlowCallManager: Screen sharing started (window)")
        }
    }

    /// Stop screen sharing
    @available(macOS 12.3, *)
    func stopScreenShare() async {
        // Remove screen share track from peer connection
        if let track = screenShareTrack {
            if let senders = peerConnection?.senders {
                for sender in senders {
                    if sender.track?.trackId == track.trackId {
                        peerConnection?.removeTrack(sender)
                        break
                    }
                }
            }
        }

        await screenCaptureService?.stopSharing()

        await MainActor.run {
            screenShareTrack = nil
            isScreenSharing = false
        }

        print("SyncFlowCallManager: Screen sharing stopped")
    }

    // MARK: - Call Recording

    /// Get the call recording service
    func getCallRecordingService() -> CallRecordingService {
        if callRecordingService == nil {
            callRecordingService = CallRecordingService()
        }
        return callRecordingService!
    }

    /// Start recording the call
    func startCallRecording() throws {
        guard let call = currentCall else {
            throw SyncFlowCallError.callNotFound
        }

        let service = getCallRecordingService()
        try service.startRecording(callId: call.id, contactName: call.displayName)

        isRecordingCall = true

        // Observe recording duration
        service.$recordingDuration
            .receive(on: DispatchQueue.main)
            .sink { [weak self] duration in
                self?.recordingDuration = duration
            }
            .store(in: &cancellables)

        print("SyncFlowCallManager: Call recording started")
    }

    /// Stop recording and save
    func stopCallRecording() -> CallRecording? {
        guard let service = callRecordingService else { return nil }

        let recording = service.stopRecording()
        isRecordingCall = false
        recordingDuration = 0

        print("SyncFlowCallManager: Call recording stopped")
        return recording
    }

    /// Cancel recording without saving
    func cancelCallRecording() {
        callRecordingService?.cancelRecording()
        isRecordingCall = false
        recordingDuration = 0

        print("SyncFlowCallManager: Call recording cancelled")
    }

    /// Get all saved call recordings
    func getAllCallRecordings() -> [CallRecording] {
        return getCallRecordingService().getAllRecordings()
    }

    /// Delete a call recording
    func deleteCallRecording(_ recording: CallRecording) {
        getCallRecordingService().deleteRecording(recording)
    }

    /// Get paired Android devices
    func getPairedDevices(userId: String) async throws -> [SyncFlowDevice] {
        let devicesRef = database.reference()
            .child("users")
            .child(userId)
            .child("devices")

        let snapshot = try await devicesRef.getData()
        guard let devicesData = snapshot.value as? [String: [String: Any]] else {
            return []
        }

        return devicesData.compactMap { (id, dict) in
            SyncFlowDevice.from(id: id, dict: dict)
        }.filter { $0.isAndroid && $0.online }
    }

    // MARK: - User-to-User Calls

    /// Starts a call to a user by their phone number.
    ///
    /// This is the primary method for calling other SyncFlow users. It:
    /// 1. Looks up the recipient's Firebase UID via `phone_to_uid` mapping
    /// 2. Creates call entry in recipient's `incoming_syncflow_calls`
    /// 3. Sets up WebRTC and starts signaling
    ///
    /// - Parameters:
    ///   - recipientPhoneNumber: The recipient's phone number
    ///   - recipientName: Display name for the recipient
    ///   - isVideo: Whether to enable video
    /// - Returns: The unique call ID
    /// - Throws: `SyncFlowCallError.userNotOnSyncFlow` if recipient not found
    ///
    /// ## Fallback Behavior
    /// If `phone_to_uid` lookup fails, falls back to the paired user ID.
    /// This enables calls between paired devices when phone number mapping
    /// isn't available.
    func startCallToUser(
        recipientPhoneNumber: String,
        recipientName: String,
        isVideo: Bool
    ) async throws -> String {
        ensureOnline()

        guard let myUid = currentUserId else {
            throw SyncFlowCallError.notAuthenticated
        }

        await MainActor.run {
            callState = .initializing
        }

        // Normalize phone number
        let normalizedPhone = recipientPhoneNumber.replacingOccurrences(of: "[^0-9+]", with: "", options: .regularExpression)
        let phoneKey = normalizedPhone.replacingOccurrences(of: "+", with: "")

        print("SyncFlowCallManager: Looking up phone_to_uid for phoneKey: \(phoneKey)")

        // Look up recipient's UID from phone number
        let phoneToUidRef = database.reference()
            .child("phone_to_uid")
            .child(phoneKey)

        let snapshot = try await phoneToUidRef.getData()
        print("SyncFlowCallManager: phone_to_uid lookup result: \(String(describing: snapshot.value))")

        var recipientUid: String
        if let uid = snapshot.value as? String {
            recipientUid = uid
            if recipientUid == myUid {
                print("SyncFlowCallManager: phone_to_uid resolved to caller UID; blocking self-call")
                await MainActor.run {
                    callState = .failed("Cannot call your own device")
                }
                throw SyncFlowCallError.userNotOnSyncFlow
            }
        } else {
            // Fallback: If phone_to_uid lookup fails, try using the paired user's UID
            // This enables video calls between paired devices when the Android hasn't
            // registered its phone number in phone_to_uid
            print("SyncFlowCallManager: phone_to_uid lookup failed, checking for paired user fallback...")

            if let pairedUserId = UserDefaults.standard.string(forKey: "syncflow_user_id"),
               !pairedUserId.isEmpty,
               pairedUserId != myUid {
                print("SyncFlowCallManager: Using paired user UID as fallback: \(pairedUserId)")
                recipientUid = pairedUserId
            } else {
                print("SyncFlowCallManager: ERROR - User not found in phone_to_uid for \(phoneKey) and no paired user fallback")
                await MainActor.run {
                    callState = .failed("User not found on SyncFlow")
                }
                throw SyncFlowCallError.userNotOnSyncFlow
            }
        }

        print("SyncFlowCallManager: Found recipient UID: \(recipientUid)")

        let callId = UUID().uuidString
        let deviceName = Host.current().localizedName ?? "Mac"

        // Get my phone number from Firebase
        let myPhoneNumber = try await getMyPhoneNumber(userId: myUid)

        let call = SyncFlowCall(
            id: callId,
            callerId: myUid,
            callerName: deviceName,
            callerPlatform: "macos",
            calleeId: recipientUid,
            calleeName: recipientName,
            calleePlatform: "unknown",
            callType: isVideo ? .video : .audio,
            status: .ringing,
            startedAt: Date(),
            answeredAt: nil,
            endedAt: nil,
            offer: nil,
            answer: nil
        )

        await MainActor.run {
            currentCall = call
            // Mark this as a user-to-user call for ICE candidate routing
            isUserCall = true
            userCallRecipientUid = recipientUid
            userCallSignalingUid = recipientUid
        }

        // Write call to RECIPIENT's Firebase path so they receive notification
        let callRef = database.reference()
            .child("users")
            .child(recipientUid)
            .child("incoming_syncflow_calls")
            .child(callId)

        var callData: [String: Any] = [
            "id": callId,
            "callerUid": myUid,
            "callerPhone": myPhoneNumber,
            "callerName": deviceName,
            "callerPlatform": "macos",
            "recipientPhone": recipientPhoneNumber,
            "callType": isVideo ? "video" : "audio",
            "status": "ringing",
            "startedAt": ServerValue.timestamp()
        ]

        try await callRef.setValue(callData)
        currentCallRef = callRef

        print("SyncFlowCallManager: Call written to Firebase at: users/\(recipientUid)/incoming_syncflow_calls/\(callId)")

        // Also save to my outgoing calls for tracking
        let myCallRef = database.reference()
            .child("users")
            .child(myUid)
            .child("outgoing_syncflow_calls")
            .child(callId)

        let myCallData: [String: Any] = [
            "id": callId,
            "recipientUid": recipientUid,
            "recipientPhone": recipientPhoneNumber,
            "recipientName": recipientName,
            "callType": isVideo ? "video" : "audio",
            "status": "ringing",
            "startedAt": ServerValue.timestamp()
        ]

        try await myCallRef.setValue(myCallData)

        // Setup peer connection
        setupPeerConnection(userId: recipientUid, callId: callId, isOutgoing: true)

        // Create media tracks
        try createMediaTracks(withVideo: isVideo)

        // Create and send offer to recipient's path
        try await createAndSendOfferToUser(recipientUid: recipientUid, callId: callId)

        // Listen for answer from recipient
        listenForAnswerFromUser(recipientUid: recipientUid, callId: callId)

        // Listen for ICE candidates
        listenForIceCandidatesFromUser(recipientUid: recipientUid, callId: callId, isOutgoing: true)

        // Listen for call status changes (to detect when receiver ends call)
        listenForCallStatusChanges(recipientUid: recipientUid, callId: callId)

        await MainActor.run {
            callState = .ringing
        }

        // Start timeout timer
        startCallTimeoutToUser(recipientUid: recipientUid, callId: callId)

        print("SyncFlowCallManager: Call started to user \(recipientName) (\(recipientPhoneNumber))")
        return callId
    }

    /// Get my phone number from Firebase
    private func getMyPhoneNumber(userId: String) async throws -> String {
        // Try to get from sims data
        let simsRef = database.reference()
            .child("users")
            .child(userId)
            .child("sims")

        let snapshot = try await simsRef.getData()

        if let simsData = snapshot.value as? [String: [String: Any]] {
            for (_, sim) in simsData {
                if let phoneNumber = sim["phoneNumber"] as? String, !phoneNumber.isEmpty {
                    return phoneNumber
                }
            }
        }

        // Fallback - search phone_to_uid for my UID
        // This is a reverse lookup, not ideal but works as fallback
        return "Unknown"
    }

    private func createAndSendOfferToUser(recipientUid: String, callId: String) async throws {
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "OfferToReceiveAudio": "true",
                "OfferToReceiveVideo": "true"
            ],
            optionalConstraints: nil
        )

        guard let offer = try await peerConnection?.offer(for: constraints) else {
            throw SyncFlowCallError.failedToCreateOffer
        }

        try await peerConnection?.setLocalDescription(offer)

        // Send offer to recipient's Firebase path
        let offerData: [String: Any] = [
            "sdp": offer.sdp,
            "type": RTCSessionDescription.string(for: offer.type)
        ]

        try await database.reference()
            .child("users")
            .child(recipientUid)
            .child("incoming_syncflow_calls")
            .child(callId)
            .child("offer")
            .setValue(offerData)

        print("SyncFlowCallManager: Offer sent to user")
    }

    private func listenForAnswerFromUser(recipientUid: String, callId: String) {
        let answerRef = database.reference()
            .child("users")
            .child(recipientUid)
            .child("incoming_syncflow_calls")
            .child(callId)
            .child("answer")

        answerListenerHandle = answerRef.observe(.value) { [weak self] snapshot in
            guard let self = self,
                  let answerData = snapshot.value as? [String: Any],
                  let sdp = answerData["sdp"] as? String,
                  let type = answerData["type"] as? String else {
                return
            }

            print("SyncFlowCallManager: Answer received from user")

            let sdpType = RTCSessionDescription.type(for: type)
            let answerSdp = RTCSessionDescription(type: sdpType, sdp: sdp)

            Task {
                do {
                    try await self.peerConnection?.setRemoteDescription(answerSdp)
                    print("SyncFlowCallManager: ✅ Remote description set successfully")

                    await MainActor.run {
                        self.hasRemoteDescription = true
                        self.callState = .connecting
                    }

                    // Process any buffered ICE candidates
                    self.processPendingIceCandidates()
                } catch {
                    print("SyncFlowCallManager: ❌ Failed to set remote description: \(error)")
                    await MainActor.run {
                        self.callState = .failed("Failed to establish connection: \(error.localizedDescription)")
                    }
                }
            }
        }
    }

    private func listenForIceCandidatesFromUser(recipientUid: String, callId: String, isOutgoing: Bool) {
        let icePath = isOutgoing ? "ice_callee" : "ice_caller"
        let iceRef = database.reference()
            .child("users")
            .child(recipientUid)
            .child("incoming_syncflow_calls")
            .child(callId)
            .child(icePath)

        iceListenerHandle = iceRef.observe(.childAdded) { [weak self] snapshot in
            guard let self = self,
                  let candidateData = snapshot.value as? [String: Any],
                  let candidateStr = candidateData["candidate"] as? String else {
                return
            }

            let sdpMid = candidateData["sdpMid"] as? String
            let sdpMLineIndex = (candidateData["sdpMLineIndex"] as? Int) ?? 0

            print("SyncFlowCallManager: Remote ICE candidate received from user")

            let candidate = RTCIceCandidate(
                sdp: candidateStr,
                sdpMLineIndex: Int32(sdpMLineIndex),
                sdpMid: sdpMid
            )

            // Buffer candidate if remote description isn't set yet
            if !self.hasRemoteDescription {
                print("SyncFlowCallManager: Buffering ICE candidate (waiting for remote description)")
                self.pendingIceCandidates.append(candidate)
                return
            }

            self.addIceCandidate(candidate)
        }
    }

    /// Add ICE candidate to peer connection with error handling
    private func addIceCandidate(_ candidate: RTCIceCandidate) {
        peerConnection?.add(candidate) { [weak self] error in
            if let error = error {
                print("SyncFlowCallManager: ❌ Error adding ICE candidate: \(error)")
            } else {
                print("SyncFlowCallManager: ✅ ICE candidate added successfully")
            }
        }
    }

    /// Process any buffered ICE candidates after remote description is set
    private func processPendingIceCandidates() {
        guard hasRemoteDescription, !pendingIceCandidates.isEmpty else { return }

        print("SyncFlowCallManager: Processing \(pendingIceCandidates.count) buffered ICE candidates")

        for candidate in pendingIceCandidates {
            addIceCandidate(candidate)
        }
        pendingIceCandidates.removeAll()
    }

    private func sendIceCandidateToUser(_ candidate: RTCIceCandidate, recipientUid: String, callId: String, isOutgoing: Bool) {
        let icePath = isOutgoing ? "ice_caller" : "ice_callee"

        let candidateData: [String: Any] = [
            "candidate": candidate.sdp,
            "sdpMid": candidate.sdpMid ?? "",
            "sdpMLineIndex": candidate.sdpMLineIndex
        ]

        database.reference()
            .child("users")
            .child(recipientUid)
            .child("incoming_syncflow_calls")
            .child(callId)
            .child(icePath)
            .childByAutoId()
            .setValue(candidateData)

        print("SyncFlowCallManager: ICE candidate sent to user")
    }

    private func startCallTimeoutToUser(recipientUid: String, callId: String) {
        callTimeoutTimer?.invalidate()
        callTimeoutTimer = Timer.scheduledTimer(withTimeInterval: Self.callTimeoutSeconds, repeats: false) { [weak self] _ in
            guard self?.callState == .ringing else { return }

            print("SyncFlowCallManager: User call timed out")

            Task {
                try? await self?.database.reference()
                    .child("users")
                    .child(recipientUid)
                    .child("incoming_syncflow_calls")
                    .child(callId)
                    .child("status")
                    .setValue("missed")

                await MainActor.run {
                    self?.callState = .failed("Call timed out - no answer")
                }
                self?.cleanup()
            }
        }
    }

    /// Listen for call status changes to detect when remote party ends the call
    private func listenForCallStatusChanges(recipientUid: String, callId: String) {
        let statusRef = database.reference()
            .child("users")
            .child(recipientUid)
            .child("incoming_syncflow_calls")
            .child(callId)
            .child("status")

        statusListenerHandle = statusRef.observe(.value) { [weak self] snapshot in
            guard let status = snapshot.value as? String else { return }

            print("SyncFlowCallManager: Call status changed to: \(status)")

            // If the call was ended, rejected, or missed by the other party
            if status == "ended" || status == "rejected" || status == "missed" || status == "failed" {
                DispatchQueue.main.async {
                    // Only end the call if we're still in an active state
                    if self?.callState == .connected || self?.callState == .connecting || self?.callState == .ringing {
                        print("SyncFlowCallManager: Remote party ended the call")
                        self?.callState = .ended
                        self?.cleanup()
                    }
                }
            }
        }

        print("SyncFlowCallManager: Listening for call status changes")
    }

    /// Update this device's online status
    func updateDeviceStatus(userId: String, online: Bool) async throws {
        let deviceId = getDeviceId()
        let deviceName = Host.current().localizedName ?? "Mac"

        let deviceRef = database.reference()
            .child("users")
            .child(userId)
            .child("devices")
            .child(deviceId)

        // Use ServerValue.timestamp() for proper time sync with Android
        let deviceData: [String: Any] = [
            "id": deviceId,
            "name": deviceName,
            "platform": "macos",
            "online": online,
            "lastSeen": ServerValue.timestamp()
        ]

        ensureOnline()
        print("[SyncFlowCallManager] Updating device status: id=\(deviceId), platform=macos, online=\(online)")
        try await deviceRef.setValue(deviceData)
        print("[SyncFlowCallManager] Device status updated successfully")

        // Set disconnect handler
        if online {
            deviceRef.child("online").onDisconnectSetValue(false) { _, _ in }
            deviceRef.child("lastSeen").onDisconnectSetValue(ServerValue.timestamp()) { _, _ in }
        }
    }

    // MARK: - Incoming User Calls

    /// Data structure for an incoming user-to-user call.
    struct IncomingUserCall {
        /// Unique call identifier.
        let callId: String
        /// Firebase UID of the caller.
        let callerUid: String
        /// Caller's phone number (for display).
        let callerPhone: String
        /// Caller's display name.
        let callerName: String
        /// Platform of caller ("android", "macos", "ios").
        let callerPlatform: String
        /// Whether this is a video call.
        let isVideo: Bool
    }

    /// Currently incoming call awaiting user action (answer/reject).
    /// UI should observe this to show incoming call screen.
    @Published var incomingUserCall: IncomingUserCall?

    /// Handle for incoming call notifications listener.
    private var incomingCallListenerHandle: DatabaseHandle?

    /// Handle for incoming call status changes listener.
    private var incomingCallStatusListenerHandle: DatabaseHandle?

    /// Starts listening for incoming user-to-user calls.
    ///
    /// When a call is received, `incomingUserCall` is set, which the UI
    /// should observe to present the incoming call screen.
    ///
    /// - Parameter userId: The current user's Firebase UID
    ///
    /// ## Cleanup
    /// Call `stopListeningForIncomingUserCalls(userId:)` when no longer needed.
    func startListeningForIncomingUserCalls(userId: String) {
        let callsRef = database.reference()
            .child("users")
            .child(userId)
            .child("incoming_syncflow_calls")

        incomingCallListenerHandle = callsRef.observe(.childAdded) { [weak self] snapshot in
            guard let callData = snapshot.value as? [String: Any],
                  let status = callData["status"] as? String,
                  status == "ringing" else {
                return
            }

            let callId = snapshot.key
            let callerUid = callData["callerUid"] as? String ?? ""
            if callerUid == userId {
                // Ignore loopback calls created by this device/user.
                return
            }
            let callerPhone = callData["callerPhone"] as? String ?? "Unknown"
            let callerName = callData["callerName"] as? String ?? "Unknown"
            let callerPlatform = callData["callerPlatform"] as? String ?? "unknown"
            let callType = callData["callType"] as? String ?? "audio"
            let isVideo = callType == "video"

            print("SyncFlowCallManager: Incoming user call from \(callerName) (\(callerPhone))")

            let incomingCall = IncomingUserCall(
                callId: callId,
                callerUid: callerUid,
                callerPhone: callerPhone,
                callerName: callerName,
                callerPlatform: callerPlatform,
                isVideo: isVideo
            )

            DispatchQueue.main.async {
                self?.incomingUserCall = incomingCall
            }
        }

        // Also listen for status changes (to detect cancelled calls)
        incomingCallStatusListenerHandle = callsRef.observe(.childChanged) { [weak self] snapshot in
            guard let callData = snapshot.value as? [String: Any],
                  let status = callData["status"] as? String else {
                return
            }

            if status != "ringing" {
                let callId = snapshot.key
                DispatchQueue.main.async {
                    if self?.incomingUserCall?.callId == callId {
                        self?.incomingUserCall = nil
                        print("SyncFlowCallManager: Incoming call cancelled or ended")
                    }
                }
            }
        }
    }

    /// Stop listening for incoming user calls
    func stopListeningForIncomingUserCalls(userId: String) {
        if let handle = incomingCallListenerHandle {
            database.reference()
                .child("users")
                .child(userId)
                .child("incoming_syncflow_calls")
                .removeObserver(withHandle: handle)
            incomingCallListenerHandle = nil
        }
        if let handle = incomingCallStatusListenerHandle {
            database.reference()
                .child("users")
                .child(userId)
                .child("incoming_syncflow_calls")
                .removeObserver(withHandle: handle)
            incomingCallStatusListenerHandle = nil
        }
    }

    /// Answer an incoming user-to-user call
    func answerUserCall(callId: String, withVideo: Bool, userId: String? = nil) async throws {
        // Use provided userId or fall back to stored user ID
        let myUid: String
        if let providedUserId = userId {
            myUid = providedUserId
        } else if let storedUid = currentUserId {
            myUid = storedUid
        } else {
            print("SyncFlowCallManager: answerUserCall - not authenticated")
            throw SyncFlowCallError.notAuthenticated
        }

        print("SyncFlowCallManager: answerUserCall - userId: \(myUid), callId: \(callId)")

        await MainActor.run {
            callState = .connecting
            incomingUserCall = nil
        }

        let callRef = database.reference()
            .child("users")
            .child(myUid)
            .child("incoming_syncflow_calls")
            .child(callId)

        currentCallRef = callRef

        // Mark as user call for proper ICE candidate routing
        isUserCall = true

        print("SyncFlowCallManager: Fetching call data from path: users/\(myUid)/incoming_syncflow_calls/\(callId)")

        // Get call data
        let snapshot = try await callRef.getData()
        print("SyncFlowCallManager: Snapshot exists: \(snapshot.exists()), value: \(String(describing: snapshot.value))")

        guard let rawCallData = snapshot.value as? [String: Any] else {
            print("SyncFlowCallManager: Call data not found or invalid format")
            throw SyncFlowCallError.callNotFound
        }

        var callData = rawCallData
        if callData["offer"] == nil, let nested = rawCallData[callId] as? [String: Any] {
            callData = nested
            print("SyncFlowCallManager: Using nested call data for callId \(callId)")
        }

        print("SyncFlowCallManager: Call data retrieved successfully")

        let callerUid = callData["callerUid"] as? String ?? callData["callerId"] as? String ?? "unknown"
        let callerName = callData["callerName"] as? String ?? "Unknown"
        let callerPlatform = callData["callerPlatform"] as? String ?? "android"
        let callTypeRaw = callData["callType"] as? String ?? "audio"
        let callStatusRaw = callData["status"] as? String ?? "ringing"
        let deviceName = Host.current().localizedName ?? "Mac"

        let startedAt = Self.parseTimestamp(callData["startedAt"]) ?? Date()
        let answeredAt = Self.parseTimestamp(callData["answeredAt"])
        let endedAt = Self.parseTimestamp(callData["endedAt"])

        let call = SyncFlowCall(
            id: callId,
            callerId: callerUid,
            callerName: callerName,
            callerPlatform: callerPlatform,
            calleeId: myUid,
            calleeName: deviceName,
            calleePlatform: "macos",
            callType: SyncFlowCall.CallType(rawValue: callTypeRaw) ?? .audio,
            status: SyncFlowCall.CallStatus(rawValue: callStatusRaw) ?? .ringing,
            startedAt: startedAt,
            answeredAt: answeredAt,
            endedAt: endedAt,
            offer: nil,
            answer: nil
        )

        // Store the caller's UID so we can update their outgoing call when we end
        userCallCallerUid = callerUid

        await MainActor.run {
            currentCall = call
            isUserCall = true
            userCallSignalingUid = myUid
        }

        // Update call status
        try await callRef.child("status").setValue("active")
        try await callRef.child("answeredAt").setValue(ServerValue.timestamp())

        // Setup peer connection for incoming call
        setupPeerConnectionForUserCall(userId: myUid, callId: callId, isOutgoing: false)

        // Create media tracks
        try createMediaTracks(withVideo: withVideo)

        // Get the offer and set as remote description
        guard let offerData = callData["offer"] as? [String: Any],
              let sdp = offerData["sdp"] as? String,
              let type = offerData["type"] as? String else {
            throw SyncFlowCallError.noOffer
        }

        let sdpType = RTCSessionDescription.type(for: type)
        let offerSdp = RTCSessionDescription(type: sdpType, sdp: sdp)
        try await peerConnection?.setRemoteDescription(offerSdp)

        // Mark remote description as set
        await MainActor.run {
            hasRemoteDescription = true
        }
        print("SyncFlowCallManager: ✅ Remote description set for incoming call")

        // Create and send answer
        try await createAndSendAnswerForUserCall(userId: myUid, callId: callId)

        // Listen for ICE candidates from caller
        listenForIceCandidatesForUserCall(userId: myUid, callId: callId, isOutgoing: false)

        // Listen for status changes so we stop when the caller ends
        listenForCallStatusChanges(recipientUid: myUid, callId: callId)

        // Process any buffered ICE candidates
        processPendingIceCandidates()

        print("SyncFlowCallManager: Answered user call")
    }

    /// Reject an incoming user-to-user call
    func rejectUserCall(callId: String, userId: String? = nil) async throws {
        // Use provided userId or fall back to stored user ID
        let myUid: String
        if let providedUserId = userId {
            myUid = providedUserId
        } else if let storedUid = currentUserId {
            myUid = storedUid
        } else {
            print("SyncFlowCallManager: rejectUserCall - not authenticated")
            throw SyncFlowCallError.notAuthenticated
        }

        let callRef = database.reference()
            .child("users")
            .child(myUid)
            .child("incoming_syncflow_calls")
            .child(callId)

        try await callRef.child("status").setValue("rejected")
        try await callRef.child("endedAt").setValue(ServerValue.timestamp())

        await MainActor.run {
            callState = .ended
            incomingUserCall = nil
        }

        cleanup()
        print("SyncFlowCallManager: Rejected user call")
    }

    /// End a user-to-user call (works for both outgoing and incoming)
    func endUserCall() async throws {
        defer { cleanup() }

        print("SyncFlowCallManager: endUserCall() called - stack trace:")
        Thread.callStackSymbols.prefix(10).forEach { print("  \($0)") }

        guard let call = currentCall,
              let myUid = currentUserId else {
            print("SyncFlowCallManager: endUserCall() - no current call or not authenticated")
            return
        }

        // Update status in the appropriate paths
        do {
            if isUserCall, currentCall?.isOutgoing == true, let recipientUid = userCallRecipientUid {
                // I initiated this call - update both my outgoing and recipient's incoming
                let recipientCallRef = database.reference()
                    .child("users")
                    .child(recipientUid)
                    .child("incoming_syncflow_calls")
                    .child(call.id)

                try await recipientCallRef.child("status").setValue("ended")
                try await recipientCallRef.child("endedAt").setValue(ServerValue.timestamp())

                let myCallRef = database.reference()
                    .child("users")
                    .child(myUid)
                    .child("outgoing_syncflow_calls")
                    .child(call.id)

                try await myCallRef.child("status").setValue("ended")
                try await myCallRef.child("endedAt").setValue(ServerValue.timestamp())
            } else {
                // I received this call - update my incoming call AND the caller's outgoing call
                let myCallRef = database.reference()
                    .child("users")
                    .child(myUid)
                    .child("incoming_syncflow_calls")
                    .child(call.id)

                try await myCallRef.child("status").setValue("ended")
                try await myCallRef.child("endedAt").setValue(ServerValue.timestamp())

                // Also update the caller's outgoing call so they know the call ended
                if let callerUid = userCallCallerUid, !callerUid.isEmpty, callerUid != "unknown" {
                    print("SyncFlowCallManager: Also updating caller's outgoing call - callerUid: \(callerUid)")
                    let callerCallRef = database.reference()
                        .child("users")
                        .child(callerUid)
                        .child("outgoing_syncflow_calls")
                        .child(call.id)

                    try await callerCallRef.child("status").setValue("ended")
                    try await callerCallRef.child("endedAt").setValue(ServerValue.timestamp())
                }
            }
        } catch {
            print("SyncFlowCallManager: endUserCall() failed to update Firebase: \(error)")
        }

        await MainActor.run {
            callState = .ended
        }

        print("SyncFlowCallManager: Ended user call")
    }

    private func setupPeerConnectionForUserCall(userId: String, callId: String, isOutgoing: Bool) {
        let config = RTCConfiguration()
        config.iceServers = Self.iceServers
        config.bundlePolicy = .maxBundle
        config.rtcpMuxPolicy = .require
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: nil
        )

        peerConnection = peerConnectionFactory?.peerConnection(
            with: config,
            constraints: constraints,
            delegate: self
        )
    }

    private func createAndSendAnswerForUserCall(userId: String, callId: String) async throws {
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "OfferToReceiveAudio": "true",
                "OfferToReceiveVideo": "true"
            ],
            optionalConstraints: nil
        )

        guard let answer = try await peerConnection?.answer(for: constraints) else {
            throw SyncFlowCallError.failedToCreateAnswer
        }

        try await peerConnection?.setLocalDescription(answer)

        // Send answer to my incoming_syncflow_calls path
        let answerData: [String: Any] = [
            "sdp": answer.sdp,
            "type": RTCSessionDescription.string(for: answer.type)
        ]

        try await database.reference()
            .child("users")
            .child(userId)
            .child("incoming_syncflow_calls")
            .child(callId)
            .child("answer")
            .setValue(answerData)

        print("SyncFlowCallManager: Answer sent for user call")
    }

    private func listenForIceCandidatesForUserCall(userId: String, callId: String, isOutgoing: Bool) {
        let icePath = isOutgoing ? "ice_callee" : "ice_caller"
        let iceRef = database.reference()
            .child("users")
            .child(userId)
            .child("incoming_syncflow_calls")
            .child(callId)
            .child(icePath)

        iceListenerHandle = iceRef.observe(.childAdded) { [weak self] snapshot in
            guard let self = self,
                  let candidateData = snapshot.value as? [String: Any],
                  let candidateStr = candidateData["candidate"] as? String else {
                return
            }

            let sdpMid = candidateData["sdpMid"] as? String
            let sdpMLineIndex = (candidateData["sdpMLineIndex"] as? Int) ?? 0

            print("SyncFlowCallManager: Remote ICE candidate received (user call)")

            let candidate = RTCIceCandidate(
                sdp: candidateStr,
                sdpMLineIndex: Int32(sdpMLineIndex),
                sdpMid: sdpMid
            )

            // Buffer candidate if remote description isn't set yet
            if !self.hasRemoteDescription {
                print("SyncFlowCallManager: Buffering ICE candidate (waiting for remote description)")
                self.pendingIceCandidates.append(candidate)
                return
            }

            self.addIceCandidate(candidate)
        }
    }

    // MARK: - Private Methods - Peer Connection Setup

    /// Sets up the WebRTC peer connection with ICE server configuration.
    ///
    /// Configures:
    /// - ICE servers (STUN/TURN)
    /// - Bundle policy (max-bundle for efficiency)
    /// - RTCP mux policy (require for single port)
    /// - Unified Plan SDP semantics (modern WebRTC standard)
    ///
    /// - Parameters:
    ///   - userId: Firebase user ID
    ///   - callId: The call ID
    ///   - isOutgoing: true if we're initiating the call
    private func setupPeerConnection(userId: String, callId: String, isOutgoing: Bool) {
        let config = RTCConfiguration()
        config.iceServers = Self.iceServers
        config.bundlePolicy = .maxBundle
        config.rtcpMuxPolicy = .require
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually

        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: nil
        )

        peerConnection = peerConnectionFactory?.peerConnection(
            with: config,
            constraints: constraints,
            delegate: self
        )
    }

    private func createMediaTracks(withVideo: Bool) throws {
        guard let factory = peerConnectionFactory else {
            throw SyncFlowCallError.notInitialized
        }

        // Note: macOS doesn't use AVAudioSession - WebRTC handles audio internally

        // Create audio track
        let audioConstraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "googEchoCancellation": "true",
                "googAutoGainControl": "true",
                "googNoiseSuppression": "true",
                "googHighpassFilter": "true"
            ],
            optionalConstraints: nil
        )
        localAudioSource = factory.audioSource(with: audioConstraints)
        localAudioTrack = factory.audioTrack(with: localAudioSource!, trackId: "audio0")
        localAudioTrack?.isEnabled = true

        if let audioTrack = localAudioTrack {
            peerConnection?.add(audioTrack, streamIds: ["stream0"])
        }

        // Create video track if needed
        if withVideo {
            try createVideoTrack()
        }
    }

    private func createVideoTrack() throws {
        guard let factory = peerConnectionFactory else {
            throw SyncFlowCallError.notInitialized
        }

        localVideoSource = factory.videoSource()

        // Create video capturer with effects pipeline
        let effectsDelegate = VideoEffectsCapturerDelegate(videoSource: localVideoSource!) { [weak self] in
            self?.videoEffect ?? .none
        }
        videoEffectsDelegate = effectsDelegate
        videoCapturer = RTCCameraVideoCapturer(delegate: effectsDelegate)

        // Find front camera
        let devices = RTCCameraVideoCapturer.captureDevices()
        guard let frontCamera = devices.first(where: { $0.position == .front }) ?? devices.first else {
            print("SyncFlowCallManager: No camera found")
            return
        }

        // Get format
        let formats = RTCCameraVideoCapturer.supportedFormats(for: frontCamera)
        guard let format = formats.first(where: { format in
            let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            return dimensions.width >= 640 && dimensions.height >= 480
        }) ?? formats.first else {
            print("SyncFlowCallManager: No suitable format found")
            return
        }

        // Start capturing
        videoCapturer?.startCapture(with: frontCamera, format: format, fps: 30)

        // Create video track
        localVideoTrack = factory.videoTrack(with: localVideoSource!, trackId: "video0")
        localVideoTrack?.isEnabled = true

        if let videoTrack = localVideoTrack {
            peerConnection?.add(videoTrack, streamIds: ["stream0"])
        }

        print("SyncFlowCallManager: Video track created")
    }

    private func createAndSendOffer(userId: String, callId: String) async throws {
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "OfferToReceiveAudio": "true",
                "OfferToReceiveVideo": "true"
            ],
            optionalConstraints: nil
        )

        guard let offer = try await peerConnection?.offer(for: constraints) else {
            throw SyncFlowCallError.failedToCreateOffer
        }

        try await peerConnection?.setLocalDescription(offer)

        // Send offer to Firebase
        let offerData: [String: Any] = [
            "sdp": offer.sdp,
            "type": RTCSessionDescription.string(for: offer.type)
        ]

        try await database.reference()
            .child("users")
            .child(userId)
            .child("syncflow_calls")
            .child(callId)
            .child("offer")
            .setValue(offerData)

        print("SyncFlowCallManager: Offer sent")
    }

    private func createAndSendAnswer(userId: String, callId: String) async throws {
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "OfferToReceiveAudio": "true",
                "OfferToReceiveVideo": "true"
            ],
            optionalConstraints: nil
        )

        guard let answer = try await peerConnection?.answer(for: constraints) else {
            throw SyncFlowCallError.failedToCreateAnswer
        }

        try await peerConnection?.setLocalDescription(answer)

        // Send answer to Firebase
        let answerData: [String: Any] = [
            "sdp": answer.sdp,
            "type": RTCSessionDescription.string(for: answer.type)
        ]

        try await database.reference()
            .child("users")
            .child(userId)
            .child("syncflow_calls")
            .child(callId)
            .child("answer")
            .setValue(answerData)

        print("SyncFlowCallManager: Answer sent")
    }

    private func listenForAnswer(userId: String, callId: String) {
        let answerRef = database.reference()
            .child("users")
            .child(userId)
            .child("syncflow_calls")
            .child(callId)
            .child("answer")

        answerListenerHandle = answerRef.observe(.value) { [weak self] snapshot in
            guard let answerData = snapshot.value as? [String: Any],
                  let sdp = answerData["sdp"] as? String,
                  let type = answerData["type"] as? String else {
                return
            }

            print("SyncFlowCallManager: Answer received")

            let sdpType = RTCSessionDescription.type(for: type)
            let answerSdp = RTCSessionDescription(type: sdpType, sdp: sdp)

            Task {
                try? await self?.peerConnection?.setRemoteDescription(answerSdp)
                await MainActor.run {
                    self?.callState = .connecting
                }
            }
        }
    }

    private func listenForIceCandidates(userId: String, callId: String, isOutgoing: Bool) {
        let icePath = isOutgoing ? "ice_callee" : "ice_caller"
        let iceRef = database.reference()
            .child("users")
            .child(userId)
            .child("syncflow_calls")
            .child(callId)
            .child(icePath)

        iceListenerHandle = iceRef.observe(.childAdded) { [weak self] snapshot in
            guard let candidateData = snapshot.value as? [String: Any],
                  let candidateStr = candidateData["candidate"] as? String else {
                return
            }

            let sdpMid = candidateData["sdpMid"] as? String
            let sdpMLineIndex = (candidateData["sdpMLineIndex"] as? Int) ?? 0

            print("SyncFlowCallManager: Remote ICE candidate received")

            let candidate = RTCIceCandidate(
                sdp: candidateStr,
                sdpMLineIndex: Int32(sdpMLineIndex),
                sdpMid: sdpMid
            )

            self?.peerConnection?.add(candidate) { error in
                if let error = error {
                    print("SyncFlowCallManager: Error adding ICE candidate: \(error)")
                }
            }
        }
    }

    private func sendIceCandidate(_ candidate: RTCIceCandidate, userId: String, callId: String, isOutgoing: Bool) {
        let icePath = isOutgoing ? "ice_caller" : "ice_callee"

        let candidateData: [String: Any] = [
            "candidate": candidate.sdp,
            "sdpMid": candidate.sdpMid ?? "",
            "sdpMLineIndex": candidate.sdpMLineIndex
        ]

        database.reference()
            .child("users")
            .child(userId)
            .child("syncflow_calls")
            .child(callId)
            .child(icePath)
            .childByAutoId()
            .setValue(candidateData)

        print("SyncFlowCallManager: ICE candidate sent")
    }

    private func startCallTimeout(userId: String, callId: String) {
        callTimeoutTimer?.invalidate()
        callTimeoutTimer = Timer.scheduledTimer(withTimeInterval: Self.callTimeoutSeconds, repeats: false) { [weak self] _ in
            guard self?.callState == .ringing else { return }

            print("SyncFlowCallManager: Call timed out")

            Task {
                try? await self?.database.reference()
                    .child("users")
                    .child(userId)
                    .child("syncflow_calls")
                    .child(callId)
                    .child("status")
                    .setValue("missed")

                await MainActor.run {
                    self?.callState = .failed("Call timed out")
                }
                self?.cleanup()
            }
        }
    }

    private func cancelCallTimeout() {
        callTimeoutTimer?.invalidate()
        callTimeoutTimer = nil
    }

    /// Cleans up all call resources after a call ends.
    ///
    /// This method releases:
    /// - Firebase listeners
    /// - Screen sharing resources
    /// - Call recording
    /// - Video capturer and tracks
    /// - Audio tracks (releases microphone)
    /// - Peer connection
    ///
    /// - Important: Always called when a call ends to prevent resource leaks.
    ///   Microphone access is released here to stop the green indicator dot.
    private func cleanup() {
        print("SyncFlowCallManager: Cleaning up")

        cancelCallTimeout()

        // Stop screen sharing if active
        if #available(macOS 12.3, *) {
            // Capture screenCaptureService weakly to avoid accessing self after potential deallocation
            let captureService = screenCaptureService
            Task { [weak captureService] in
                await captureService?.stopSharing()
            }
        }
        screenShareTrack = nil
        isScreenSharing = false

        // Stop call recording if active
        if isRecordingCall {
            _ = callRecordingService?.stopRecording()
            isRecordingCall = false
            recordingDuration = 0
        }

        // Remove Firebase listeners
        if let handle = answerListenerHandle {
            currentCallRef?.child("answer").removeObserver(withHandle: handle)
            answerListenerHandle = nil
        }
        if let handle = iceListenerHandle {
            currentCallRef?.child("ice_caller").removeObserver(withHandle: handle)
            currentCallRef?.child("ice_callee").removeObserver(withHandle: handle)
            iceListenerHandle = nil
        }
        if let handle = statusListenerHandle {
            currentCallRef?.child("status").removeObserver(withHandle: handle)
            statusListenerHandle = nil
        }

        // Stop video capture
        let capturer = videoCapturer
        videoCapturer = nil
        videoEffectsDelegate = nil
        if let capturer {
            if Thread.isMainThread {
                capturer.stopCapture()
            } else {
                DispatchQueue.main.async {
                    capturer.stopCapture()
                }
            }
        }

        // Dispose tracks - IMPORTANT: Disable audio track first to release microphone
        localAudioTrack?.isEnabled = false
        localVideoTrack?.isEnabled = false

        // Remove tracks from peer connection before closing
        if let pc = peerConnection {
            for sender in pc.senders {
                pc.removeTrack(sender)
            }
        }

        localVideoTrack = nil
        localAudioTrack = nil
        remoteVideoTrack = nil
        localVideoSource = nil
        localAudioSource = nil

        // Close peer connection - this should release all WebRTC audio resources
        peerConnection?.close()
        peerConnection = nil

        print("SyncFlowCallManager: Audio and video tracks disposed, microphone should be released")

        // Reset state
        currentCall = nil
        isMuted = false
        isVideoEnabled = true
        videoEffect = .none
        callState = .idle

        // Reset user call tracking
        isUserCall = false
        userCallRecipientUid = nil
        userCallCallerUid = nil
        userCallSignalingUid = nil

        // Reset retry tracking
        connectionRetryCount = 0
        pendingIceCandidates.removeAll()
        hasRemoteDescription = false
    }

    private func getDeviceId() -> String {
        if let pairedDeviceId = UserDefaults.standard.string(forKey: "syncflow_device_id"),
           !pairedDeviceId.isEmpty {
            return pairedDeviceId
        }

        // Use hardware UUID as fallback device ID
        let platformExpert = IOServiceGetMatchingService(
            kIOMainPortDefault,
            IOServiceMatching("IOPlatformExpertDevice")
        )

        guard platformExpert != 0 else {
            return UUID().uuidString
        }

        defer { IOObjectRelease(platformExpert) }

        if let uuid = IORegistryEntryCreateCFProperty(
            platformExpert,
            kIOPlatformUUIDKey as CFString,
            kCFAllocatorDefault,
            0
        )?.takeRetainedValue() as? String {
            return uuid
        }

        return UUID().uuidString
    }

    private static func parseTimestamp(_ value: Any?) -> Date? {
        if let ms = value as? Double {
            return Date(timeIntervalSince1970: ms / 1000)
        }
        if let ms = value as? Int {
            return Date(timeIntervalSince1970: Double(ms) / 1000)
        }
        if let ms = value as? NSNumber {
            return Date(timeIntervalSince1970: ms.doubleValue / 1000)
        }
        return nil
    }

    deinit {
        cleanup()
        RTCCleanupSSL()
    }
}

// MARK: - RTCPeerConnectionDelegate

/// Extension implementing WebRTC peer connection delegate callbacks.
///
/// These callbacks handle the WebRTC connection lifecycle events:
/// - Signaling state changes
/// - ICE connection state changes (checking, connected, failed)
/// - ICE candidate generation
/// - Media track reception
extension SyncFlowCallManager: RTCPeerConnectionDelegate {

    /// Called when signaling state changes.
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {
        print("SyncFlowCallManager: Signaling state: \(stateChanged.rawValue)")
    }

    /// Called when a media stream is added (legacy API).
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        print("SyncFlowCallManager: Stream added")
    }

    /// Called when a media stream is removed (legacy API).
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {
        print("SyncFlowCallManager: Stream removed")
    }

    /// Called when renegotiation is needed (e.g., adding screen share track).
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {
        print("SyncFlowCallManager: Should negotiate")
    }

    /// Called when ICE connection state changes.
    ///
    /// This is the main indicator of connection health:
    /// - `checking`: Attempting to connect
    /// - `connected`/`completed`: Call is active
    /// - `failed`: Connection couldn't be established
    /// - `disconnected`: Temporary disconnect (may reconnect)
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        let stateDescription: String
        switch newState {
        case .new: stateDescription = "new"
        case .checking: stateDescription = "checking"
        case .connected: stateDescription = "connected"
        case .completed: stateDescription = "completed"
        case .failed: stateDescription = "failed"
        case .disconnected: stateDescription = "disconnected"
        case .closed: stateDescription = "closed"
        case .count: stateDescription = "count"
        @unknown default: stateDescription = "unknown(\(newState.rawValue))"
        }
        print("SyncFlowCallManager: ICE connection state: \(stateDescription)")

        DispatchQueue.main.async {
            switch newState {
            case .connected, .completed:
                self.connectionRetryCount = 0
                self.callState = .connected
                self.cancelCallTimeout()
                print("SyncFlowCallManager: ✅ Call connected successfully!")
            case .failed:
                self.connectionRetryCount += 1
                print("SyncFlowCallManager: ❌ ICE connection failed (attempt \(self.connectionRetryCount)/\(Self.maxConnectionRetries))")

                if self.connectionRetryCount < Self.maxConnectionRetries {
                    // Try ICE restart
                    print("SyncFlowCallManager: Attempting ICE restart...")
                    self.attemptIceRestart()
                } else {
                    self.callState = .failed("Connection failed after \(Self.maxConnectionRetries) attempts. Check your network connection.")
                    Task { try? await self.endUserCall() }
                }
            case .disconnected:
                print("SyncFlowCallManager: ⚠️ ICE disconnected - may reconnect automatically")
                // Wait a moment before declaring failure
                DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) {
                    if case .connected = self.callState {
                        // Still connected from our perspective, check actual state
                        if peerConnection.iceConnectionState == .disconnected {
                            print("SyncFlowCallManager: Still disconnected after 5s, attempting restart")
                            self.attemptIceRestart()
                        }
                    }
                }
            case .checking:
                print("SyncFlowCallManager: 🔄 Checking ICE candidates...")
            default:
                break
            }
        }
    }

    /// Attempt to restart ICE connection
    private func attemptIceRestart() {
        guard let pc = peerConnection else { return }

        // Create a new offer with ICE restart flag
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "IceRestart": "true",
                "OfferToReceiveAudio": "true",
                "OfferToReceiveVideo": "true"
            ],
            optionalConstraints: nil
        )

        Task {
            do {
                let offer = try await pc.offer(for: constraints)
                try await pc.setLocalDescription(offer)
                print("SyncFlowCallManager: ICE restart initiated")

                // Send the new offer
                if isUserCall, let recipientUid = userCallRecipientUid, let call = currentCall {
                    try await createAndSendOfferToUser(recipientUid: recipientUid, callId: call.id)
                }
            } catch {
                print("SyncFlowCallManager: ICE restart failed: \(error)")
            }
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        print("SyncFlowCallManager: ICE gathering state: \(newState.rawValue)")
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        print("SyncFlowCallManager: ICE candidate generated")

        guard let call = currentCall,
              let userId = currentUserId else {
            return
        }

        // For user-to-user calls, send ICE candidates to recipient's incoming_syncflow_calls path
        if isUserCall, let signalingUid = userCallSignalingUid {
            sendIceCandidateToUser(candidate, recipientUid: signalingUid, callId: call.id, isOutgoing: call.isOutgoing)
        } else {
            // For device-to-device calls, use the regular path
            sendIceCandidate(candidate, userId: userId, callId: call.id, isOutgoing: call.isOutgoing)
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {
        print("SyncFlowCallManager: ICE candidates removed")
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        print("SyncFlowCallManager: Data channel opened")
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams mediaStreams: [RTCMediaStream]) {
        print("SyncFlowCallManager: Track received: \(rtpReceiver.track?.kind ?? "unknown")")

        if let videoTrack = rtpReceiver.track as? RTCVideoTrack {
            DispatchQueue.main.async {
                self.remoteVideoTrack = videoTrack
            }
        }
    }
}

// MARK: - Video Effects

/// Available video effects for call video.
enum VideoEffect: String, CaseIterable {
    /// No effect applied - raw camera feed.
    case none
    /// Face focus - blurs edges to draw attention to center/face.
    case faceFocus
    /// Background blur - simulates depth-of-field blur effect.
    case backgroundBlur
}

/// Delegate for processing video frames and applying effects.
///
/// This class intercepts video frames from the camera capturer,
/// applies visual effects (blur, focus), and forwards the processed
/// frames to the WebRTC video source.
///
/// ## Effect Implementation
/// Effects use Core Image filters applied via a radial gradient mask:
/// - `CIGaussianBlur`: Applies blur to the frame
/// - `CIRadialGradient`: Creates center-to-edge mask
/// - `CIBlendWithMask`: Combines sharp center with blurred edges
final class VideoEffectsCapturerDelegate: NSObject, RTCVideoCapturerDelegate {
    /// Target video source for processed frames.
    private let videoSource: RTCVideoSource

    /// Closure that provides the current effect setting.
    private let effectProvider: () -> VideoEffect

    /// Core Image context for GPU-accelerated processing.
    /// Disables intermediate caching for memory efficiency.
    private let ciContext = CIContext(options: [.cacheIntermediates: false])

    init(videoSource: RTCVideoSource, effectProvider: @escaping () -> VideoEffect) {
        self.videoSource = videoSource
        self.effectProvider = effectProvider
    }

    /// Processes each captured video frame, applying effects if enabled.
    func capturer(_ capturer: RTCVideoCapturer, didCapture frame: RTCVideoFrame) {
        let effect = effectProvider()
        guard effect != .none else {
            videoSource.capturer(capturer, didCapture: frame)
            return
        }

        guard let cvBuffer = (frame.buffer as? RTCCVPixelBuffer)?.pixelBuffer else {
            videoSource.capturer(capturer, didCapture: frame)
            return
        }

        let inputImage = CIImage(cvPixelBuffer: cvBuffer)
        let extent = inputImage.extent
        let minDimension = min(extent.width, extent.height)

        let blurRadius: Double
        let innerRadius: Double
        let outerRadius: Double

        switch effect {
        case .faceFocus:
            blurRadius = 6
            innerRadius = Double(minDimension) * 0.18
            outerRadius = Double(minDimension) * 0.38
        case .backgroundBlur:
            blurRadius = 12
            innerRadius = Double(minDimension) * 0.28
            outerRadius = Double(minDimension) * 0.58
        case .none:
            blurRadius = 0
            innerRadius = 0
            outerRadius = 0
        }

        let blurred = inputImage
            .clampedToExtent()
            .applyingFilter("CIGaussianBlur", parameters: ["inputRadius": blurRadius])
            .cropped(to: extent)

        let center = CIVector(x: extent.midX, y: extent.midY)
        let mask = CIFilter(name: "CIRadialGradient", parameters: [
            "inputCenter": center,
            "inputRadius0": innerRadius,
            "inputRadius1": outerRadius,
            "inputColor0": CIColor.white,
            "inputColor1": CIColor.black
        ])?.outputImage?.cropped(to: extent)

        let outputImage: CIImage
        if let mask = mask {
            outputImage = inputImage.applyingFilter(
                "CIBlendWithMask",
                parameters: [
                    "inputBackgroundImage": blurred,
                    "inputMaskImage": mask
                ]
            )
        } else {
            outputImage = blurred
        }

        guard let outputBuffer = makePixelBuffer(
            width: Int(extent.width),
            height: Int(extent.height)
        ) else {
            videoSource.capturer(capturer, didCapture: frame)
            return
        }

        ciContext.render(outputImage, to: outputBuffer)
        let rtcBuffer = RTCCVPixelBuffer(pixelBuffer: outputBuffer)
        let processedFrame = RTCVideoFrame(
            buffer: rtcBuffer,
            rotation: frame.rotation,
            timeStampNs: frame.timeStampNs
        )

        videoSource.capturer(capturer, didCapture: processedFrame)
    }

    private func makePixelBuffer(width: Int, height: Int) -> CVPixelBuffer? {
        let attrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
            kCVPixelBufferWidthKey: width,
            kCVPixelBufferHeightKey: height,
            kCVPixelBufferIOSurfacePropertiesKey: [:]
        ]

        var buffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
            attrs as CFDictionary,
            &buffer
        )

        guard status == kCVReturnSuccess else {
            return nil
        }

        return buffer
    }
}

// MARK: - Errors

/// Errors that can occur during SyncFlow call operations.
enum SyncFlowCallError: LocalizedError {
    /// User is not authenticated with Firebase.
    case notAuthenticated

    /// WebRTC has not been initialized.
    case notInitialized

    /// The specified call was not found in Firebase.
    case callNotFound

    /// Incoming call data doesn't contain an SDP offer.
    case noOffer

    /// Failed to create WebRTC SDP offer.
    case failedToCreateOffer

    /// Failed to create WebRTC SDP answer.
    case failedToCreateAnswer

    /// Recipient phone number not found in phone_to_uid mapping.
    case userNotOnSyncFlow

    /// User attempted to call their own number.
    case cannotCallSelf

    var errorDescription: String? {
        switch self {
        case .notAuthenticated:
            return "Not authenticated"
        case .notInitialized:
            return "WebRTC not initialized"
        case .callNotFound:
            return "Call not found"
        case .noOffer:
            return "No offer in call data"
        case .failedToCreateOffer:
            return "Failed to create offer"
        case .failedToCreateAnswer:
            return "Failed to create answer"
        case .userNotOnSyncFlow:
            return "User not found on SyncFlow. They need to install SyncFlow to receive video calls."
        case .cannotCallSelf:
            return "Cannot call yourself"
        }
    }
}
