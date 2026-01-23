//
//  SyncFlowCallView.swift
//  SyncFlowMac
//
//  Full-screen video call view for SyncFlow-to-SyncFlow calls.
//

import SwiftUI
import WebRTC
import ScreenCaptureKit

struct SyncFlowCallView: View {
    @EnvironmentObject var appState: AppState
    @ObservedObject var callManager: SyncFlowCallManager

    @State private var showControls = true
    @State private var controlsTimer: Timer?
    @State private var elapsedTime: TimeInterval = 0
    @State private var durationTimer: Timer?
    @State private var showScreenSharePicker = false

    var body: some View {
        ZStack {
            // Background
            Color.black.ignoresSafeArea()

            // Remote video (full screen)
            if let remoteTrack = callManager.remoteVideoTrack {
                RTCVideoView(videoTrack: remoteTrack)
                    .ignoresSafeArea()
            } else {
                // Placeholder when no remote video
                VStack(spacing: 20) {
                    Image(systemName: callManager.currentCall?.isVideoCall == true ? "video.fill" : "phone.fill")
                        .font(.system(size: 80))
                        .foregroundColor(.white.opacity(0.5))

                    Text(callManager.currentCall?.displayName ?? "Calling...")
                        .font(.largeTitle)
                        .foregroundColor(.white)

                    Text(statusText)
                        .font(.title3)
                        .foregroundColor(.white.opacity(0.7))
                }
            }

            // Local video preview (PiP)
            if let localTrack = callManager.localVideoTrack, callManager.isVideoEnabled {
                VStack {
                    HStack {
                        Spacer()
                        RTCVideoView(videoTrack: localTrack, mirror: true)
                            .frame(width: 200, height: 150)
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.white.opacity(0.3), lineWidth: 2)
                            )
                            .shadow(radius: 10)
                            .padding()
                    }
                    Spacer()
                }
            }

            // Controls overlay
            if showControls {
                VStack {
                    // Top bar
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(callManager.currentCall?.displayName ?? "")
                                .font(.headline)
                                .foregroundColor(.white)

                            if callManager.callState == .connected {
                                Text(formattedDuration)
                                    .font(.subheadline)
                                    .foregroundColor(.white.opacity(0.7))
                            }
                        }

                        Spacer()

                        // Connection status indicator
                        HStack(spacing: 6) {
                            Circle()
                                .fill(connectionStatusColor)
                                .frame(width: 8, height: 8)
                            Text(connectionStatusText)
                                .font(.caption)
                                .foregroundColor(.white.opacity(0.7))
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.black.opacity(0.5))
                        .cornerRadius(16)
                    }
                    .padding()
                    .background(
                        LinearGradient(
                            colors: [Color.black.opacity(0.7), Color.clear],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )

                    Spacer()

                    // Bottom control bar
                    HStack(spacing: 30) {
                        // Mute button
                        CallControlButton(
                            icon: callManager.isMuted ? "mic.slash.fill" : "mic.fill",
                            label: callManager.isMuted ? "Unmute" : "Mute",
                            isActive: callManager.isMuted,
                            action: { callManager.toggleMute() }
                        )

                        // Video toggle button
                        if callManager.currentCall?.isVideoCall == true {
                            CallControlButton(
                                icon: callManager.isVideoEnabled ? "video.fill" : "video.slash.fill",
                                label: callManager.isVideoEnabled ? "Stop Video" : "Start Video",
                                isActive: !callManager.isVideoEnabled,
                                action: { callManager.toggleVideo() }
                            )

                            CallControlButton(
                                icon: "viewfinder",
                                label: "Face Focus",
                                isActive: callManager.videoEffect == .faceFocus,
                                action: { callManager.toggleFaceFocus() }
                            )

                            CallControlButton(
                                icon: "drop",
                                label: "Background",
                                isActive: callManager.videoEffect == .backgroundBlur,
                                action: { callManager.toggleBackgroundBlur() }
                            )
                        }

                        // Screen share button (macOS 12.3+)
                        if #available(macOS 12.3, *) {
                            CallControlButton(
                                icon: callManager.isScreenSharing ? "rectangle.inset.filled.on.rectangle" : "rectangle.on.rectangle",
                                label: callManager.isScreenSharing ? "Stop Share" : "Share Screen",
                                isActive: callManager.isScreenSharing,
                                action: {
                                    if callManager.isScreenSharing {
                                        Task {
                                            await callManager.stopScreenShare()
                                        }
                                    } else {
                                        showScreenSharePicker = true
                                    }
                                }
                            )
                        }

                        // Record button
                        CallControlButton(
                            icon: callManager.isRecordingCall ? "record.circle.fill" : "record.circle",
                            label: callManager.isRecordingCall ? "Stop Rec" : "Record",
                            isActive: callManager.isRecordingCall,
                            activeColor: .red,
                            action: {
                                if callManager.isRecordingCall {
                                    _ = callManager.stopCallRecording()
                                } else {
                                    try? callManager.startCallRecording()
                                }
                            }
                        )

                        // End call button
                        Button(action: endCall) {
                            Image(systemName: "phone.down.fill")
                                .font(.system(size: 28))
                                .foregroundColor(.white)
                                .frame(width: 70, height: 70)
                                .background(Color.red)
                                .clipShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .help("End Call")
                    }
                    .padding(.vertical, 30)
                    .padding(.horizontal)
                    .background(
                        LinearGradient(
                            colors: [Color.clear, Color.black.opacity(0.7)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                }

                // Status indicators
                VStack {
                    Spacer()

                    VStack(spacing: 8) {
                        // Recording indicator
                        if callManager.isRecordingCall {
                            HStack(spacing: 6) {
                                Circle()
                                    .fill(Color.red)
                                    .frame(width: 8, height: 8)

                                Text("REC")
                                    .font(.caption.bold())
                                    .foregroundColor(.red)

                                Text(formatRecordingDuration(callManager.recordingDuration))
                                    .font(.caption.monospacedDigit())
                                    .foregroundColor(.white)
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Color.black.opacity(0.7))
                            .cornerRadius(16)
                        }

                        // Screen sharing indicator
                        if callManager.isScreenSharing {
                            HStack {
                                Image(systemName: "rectangle.on.rectangle.angled")
                                    .foregroundColor(.green)
                                Text("Sharing your screen")
                                    .font(.caption)
                                    .foregroundColor(.white)
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Color.black.opacity(0.7))
                            .cornerRadius(16)
                        }
                    }
                    .padding(.bottom, 120)
                }
            }
        }
        .sheet(isPresented: $showScreenSharePicker) {
            if #available(macOS 12.3, *) {
                ScreenSharePicker(
                    screenCaptureService: callManager.getScreenCaptureService(),
                    onSelect: {
                        showScreenSharePicker = false
                    },
                    onCancel: {
                        showScreenSharePicker = false
                    }
                )
            }
        }
        .onAppear {
            startControlsTimer()
            if callManager.callState == .connected {
                startDurationTimer()
            }
        }
        .onDisappear {
            stopControlsTimer()
            stopDurationTimer()
        }
        .onChange(of: callManager.callState) { newState in
            if newState == .connected {
                startDurationTimer()
            }
        }
        .onTapGesture {
            withAnimation {
                showControls.toggle()
            }
            if showControls {
                startControlsTimer()
            }
        }
    }

    private var statusText: String {
        switch callManager.callState {
        case .ringing:
            return "Ringing..."
        case .connecting:
            return "Connecting..."
        case .connected:
            return "Connected"
        case .failed(let error):
            return "Failed: \(error)"
        default:
            return ""
        }
    }

    private var connectionStatusColor: Color {
        switch callManager.callState {
        case .connected:
            return .green
        case .connecting, .ringing:
            return .yellow
        case .failed:
            return .red
        default:
            return .gray
        }
    }

    private var connectionStatusText: String {
        switch callManager.callState {
        case .connected:
            return "Connected"
        case .connecting:
            return "Connecting"
        case .ringing:
            return "Ringing"
        case .failed:
            return "Failed"
        default:
            return ""
        }
    }

    private var formattedDuration: String {
        let minutes = Int(elapsedTime) / 60
        let seconds = Int(elapsedTime) % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }

    private func formatRecordingDuration(_ duration: TimeInterval) -> String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }

    private func startControlsTimer() {
        stopControlsTimer()
        controlsTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: false) { _ in
            withAnimation {
                showControls = false
            }
        }
    }

    private func stopControlsTimer() {
        controlsTimer?.invalidate()
        controlsTimer = nil
    }

    private func startDurationTimer() {
        stopDurationTimer()
        elapsedTime = 0
        durationTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            elapsedTime += 1
        }
    }

    private func stopDurationTimer() {
        durationTimer?.invalidate()
        durationTimer = nil
    }

    private func endCall() {
        Task {
            // Try user call first (most common case), then fall back to regular call
            try? await callManager.endUserCall()
        }
        // Dismiss the call view
        appState.showSyncFlowCallView = false
    }
}

// MARK: - Call Control Button

struct CallControlButton: View {
    let icon: String
    let label: String
    let isActive: Bool
    var activeColor: Color = .white
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                ZStack {
                    Circle()
                        .fill(isActive ? activeColor.opacity(0.3) : Color.black.opacity(0.5))
                        .frame(width: 60, height: 60)

                    Image(systemName: icon)
                        .font(.system(size: 24))
                        .foregroundColor(isActive && activeColor == .red ? activeColor : .white)

                    Circle()
                        .stroke(isActive && activeColor == .red ? activeColor : Color.white.opacity(0.3), lineWidth: isActive && activeColor == .red ? 2 : 1)
                        .frame(width: 60, height: 60)
                }

                Text(label)
                    .font(.caption)
                    .foregroundColor(.white.opacity(0.8))
            }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Incoming SyncFlow Call View

struct IncomingSyncFlowCallView: View {
    let call: SyncFlowCall
    let onAcceptVideo: () -> Void
    let onAcceptAudio: () -> Void
    let onDecline: () -> Void

    @State private var ringAnimation = false

    var body: some View {
        ZStack {
            // Blurred background
            Color.black.opacity(0.85)
                .ignoresSafeArea()

            VStack(spacing: 40) {
                Spacer()

                // Animated ring effect
                ZStack {
                    ForEach(0..<3) { i in
                        Circle()
                            .stroke(Color.green.opacity(0.3 - Double(i) * 0.1), lineWidth: 2)
                            .frame(width: CGFloat(120 + i * 40), height: CGFloat(120 + i * 40))
                            .scaleEffect(ringAnimation ? 1.2 : 1.0)
                            .opacity(ringAnimation ? 0 : 1)
                            .animation(
                                Animation.easeOut(duration: 1.5)
                                    .repeatForever(autoreverses: false)
                                    .delay(Double(i) * 0.3),
                                value: ringAnimation
                            )
                    }

                    // Caller avatar
                    Circle()
                        .fill(LinearGradient(
                            colors: [Color.green, Color.green.opacity(0.7)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ))
                        .frame(width: 100, height: 100)
                        .overlay(
                            Image(systemName: call.isVideoCall ? "video.fill" : "phone.fill")
                                .font(.system(size: 40))
                                .foregroundColor(.white)
                        )
                }

                // Caller info
                VStack(spacing: 8) {
                    Text(call.callerName)
                        .font(.largeTitle)
                        .fontWeight(.semibold)
                        .foregroundColor(.white)

                    Text("SyncFlow \(call.isVideoCall ? "Video" : "Audio") Call")
                        .font(.title3)
                        .foregroundColor(.white.opacity(0.7))

                    Text("from \(call.callerPlatform.capitalized)")
                        .font(.subheadline)
                        .foregroundColor(.white.opacity(0.5))
                }

                Spacer()

                // Action buttons
                HStack(spacing: 60) {
                    // Decline button
                    VStack(spacing: 12) {
                        Button(action: onDecline) {
                            Image(systemName: "phone.down.fill")
                                .font(.system(size: 28))
                                .foregroundColor(.white)
                                .frame(width: 70, height: 70)
                                .background(Color.red)
                                .clipShape(Circle())
                        }
                        .buttonStyle(.plain)

                        Text("Decline")
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.7))
                    }

                    if call.isVideoCall {
                        // Accept with audio only
                        VStack(spacing: 12) {
                            Button(action: onAcceptAudio) {
                                Image(systemName: "phone.fill")
                                    .font(.system(size: 28))
                                    .foregroundColor(.white)
                                    .frame(width: 70, height: 70)
                                    .background(Color.blue)
                                    .clipShape(Circle())
                            }
                            .buttonStyle(.plain)

                            Text("Audio")
                                .font(.subheadline)
                                .foregroundColor(.white.opacity(0.7))
                        }
                    }

                    // Accept button
                    VStack(spacing: 12) {
                        Button(action: call.isVideoCall ? onAcceptVideo : onAcceptAudio) {
                            Image(systemName: call.isVideoCall ? "video.fill" : "phone.fill")
                                .font(.system(size: 28))
                                .foregroundColor(.white)
                                .frame(width: 70, height: 70)
                                .background(Color.green)
                                .clipShape(Circle())
                        }
                        .buttonStyle(.plain)

                        Text(call.isVideoCall ? "Video" : "Accept")
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.7))
                    }
                }

                Spacer()
                    .frame(height: 60)
            }
        }
        .onAppear {
            ringAnimation = true
        }
    }
}

// MARK: - WebRTC Video View

struct RTCVideoView: NSViewRepresentable {
    let videoTrack: RTCVideoTrack
    var mirror: Bool = false

    func makeNSView(context: Context) -> RTCMTLVideoView {
        let videoView = RTCMTLVideoView()

        // Note: macOS RTCMTLVideoView uses different properties than iOS
        // Content mode and mirroring are handled differently on macOS

        videoTrack.add(videoView)
        return videoView
    }

    func updateNSView(_ nsView: RTCMTLVideoView, context: Context) {
        // Update if needed
    }

    static func dismantleNSView(_ nsView: RTCMTLVideoView, coordinator: ()) {
        // Clean up
    }
}
