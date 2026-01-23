//
//  CallAudioRoutingManager.swift
//  SyncFlowMac
//
//  Handles WebRTC audio routing from Android during an active phone call.
//

import Foundation
import Combine
import WebRTC
import FirebaseDatabase

final class CallAudioRoutingManager: NSObject, ObservableObject, RTCPeerConnectionDelegate {

    enum State: Equatable {
        case idle
        case connecting
        case connected
        case failed(String)
    }

    @Published private(set) var state: State = .idle

    private let database = Database.database()
    private var peerConnectionFactory: RTCPeerConnectionFactory?
    private var peerConnection: RTCPeerConnection?

    private var offerListenerHandle: DatabaseHandle?
    private var iceListenerHandle: DatabaseHandle?
    private var currentCallRef: DatabaseReference?
    private var currentCallId: String?
    private var currentUserId: String?
    private var hasRemoteDescription = false

    override init() {
        super.init()
        RTCInitializeSSL()
        let encoderFactory = RTCDefaultVideoEncoderFactory()
        let decoderFactory = RTCDefaultVideoDecoderFactory()
        peerConnectionFactory = RTCPeerConnectionFactory(
            encoderFactory: encoderFactory,
            decoderFactory: decoderFactory
        )
    }

    func start(userId: String, callId: String) {
        if state == .connecting || state == .connected {
            return
        }
        if case .failed = state {
            cleanup()
        }
        currentUserId = userId
        currentCallId = callId
        hasRemoteDescription = false
        state = .connecting

        setupPeerConnection()
        listenForOffer(userId: userId, callId: callId)
        listenForIceCandidates(userId: userId, callId: callId)
    }

    func stop() {
        cleanup()
    }

    private func setupPeerConnection() {
        let config = RTCConfiguration()
        config.iceServers = [
            RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"]),
            RTCIceServer(urlStrings: ["stun:stun1.l.google.com:19302"])
        ]
        config.sdpSemantics = .unifiedPlan

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        peerConnection = peerConnectionFactory?.peerConnection(
            with: config,
            constraints: constraints,
            delegate: self
        )

        let recvOnly = RTCRtpTransceiverInit()
        recvOnly.direction = .recvOnly
        _ = peerConnection?.addTransceiver(of: .audio, init: recvOnly)
    }

    private func listenForOffer(userId: String, callId: String) {
        let callRef = database.reference()
            .child("users")
            .child(userId)
            .child("webrtc_signaling")
            .child(callId)

        currentCallRef = callRef
        offerListenerHandle = callRef.child("offer").observe(.value) { [weak self] snapshot in
            guard let self = self else { return }
            guard let offerData = snapshot.value as? [String: Any],
                  let sdp = offerData["sdp"] as? String,
                  !self.hasRemoteDescription else {
                return
            }
            self.hasRemoteDescription = true
            self.handleOffer(sdp: sdp, userId: userId, callId: callId)
        }
    }

    private func handleOffer(sdp: String, userId: String, callId: String) {
        let offer = RTCSessionDescription(type: .offer, sdp: sdp)
        peerConnection?.setRemoteDescription(offer) { [weak self] error in
            if let error = error {
                self?.state = .failed("Failed to set remote description: \(error.localizedDescription)")
                return
            }
            self?.createAndSendAnswer(userId: userId, callId: callId)
        }
    }

    private func createAndSendAnswer(userId: String, callId: String) {
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "OfferToReceiveAudio": "true",
                "OfferToReceiveVideo": "false"
            ],
            optionalConstraints: nil
        )

        peerConnection?.answer(for: constraints) { [weak self] answer, error in
            guard let self = self else { return }
            if let error = error {
                self.state = .failed("Failed to create answer: \(error.localizedDescription)")
                return
            }
            guard let answer = answer else {
                self.state = .failed("Failed to create answer")
                return
            }

            self.peerConnection?.setLocalDescription(answer) { [weak self] error in
                if let error = error {
                    self?.state = .failed("Failed to set local description: \(error.localizedDescription)")
                    return
                }
                self?.sendAnswer(userId: userId, callId: callId, sdp: answer.sdp)
            }
        }
    }

    private func sendAnswer(userId: String, callId: String, sdp: String) {
        let answerRef = database.reference()
            .child("users")
            .child(userId)
            .child("webrtc_signaling")
            .child(callId)
            .child("answer")
            .childByAutoId()

        let payload: [String: Any] = [
            "type": "answer",
            "sdp": sdp,
            "timestamp": ServerValue.timestamp()
        ]

        answerRef.setValue(payload)
    }

    private func listenForIceCandidates(userId: String, callId: String) {
        let candidatesRef = database.reference()
            .child("users")
            .child(userId)
            .child("webrtc_signaling")
            .child(callId)
            .child("ice_candidates_android")

        iceListenerHandle = candidatesRef.observe(.childAdded) { [weak self] snapshot in
            guard let self = self else { return }
            guard let data = snapshot.value as? [String: Any],
                  let sdpMid = data["sdpMid"] as? String,
                  let candidate = data["candidate"] as? String else {
                return
            }
            let sdpMLineIndex = (data["sdpMLineIndex"] as? NSNumber)?.intValue ?? 0
            let ice = RTCIceCandidate(sdp: candidate, sdpMLineIndex: Int32(sdpMLineIndex), sdpMid: sdpMid)
            self.peerConnection?.add(ice) { _ in }
        }
    }

    private func sendIceCandidate(_ candidate: RTCIceCandidate) {
        guard let userId = currentUserId,
              let callId = currentCallId else {
            return
        }

        let candidateRef = database.reference()
            .child("users")
            .child(userId)
            .child("webrtc_signaling")
            .child(callId)
            .child("ice_candidates_desktop")
            .childByAutoId()

        let payload: [String: Any] = [
            "sdpMid": candidate.sdpMid ?? "",
            "sdpMLineIndex": Int(candidate.sdpMLineIndex),
            "candidate": candidate.sdp,
            "timestamp": ServerValue.timestamp()
        ]
        candidateRef.setValue(payload)
    }

    private func cleanup() {
        if let handle = offerListenerHandle, let ref = currentCallRef {
            ref.child("offer").removeObserver(withHandle: handle)
        }
        if let handle = iceListenerHandle, let ref = currentCallRef {
            ref.child("ice_candidates_android").removeObserver(withHandle: handle)
        }
        offerListenerHandle = nil
        iceListenerHandle = nil

        peerConnection?.close()
        peerConnection = nil

        currentCallRef = nil
        currentCallId = nil
        currentUserId = nil
        hasRemoteDescription = false
        state = .idle
    }

    // MARK: - RTCPeerConnectionDelegate

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        sendIceCandidate(candidate)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCPeerConnectionState) {
        switch stateChanged {
        case .connected:
            state = .connected
        case .failed, .disconnected:
            state = .failed("Connection lost")
        default:
            break
        }
    }
}
