//
//  ScreenCaptureService.swift
//  SyncFlowMac
//
//  Handles screen capture for screen sharing during calls
//

import Foundation
import ScreenCaptureKit
import Combine
import CoreMedia
import WebRTC
import AppKit

@available(macOS 12.3, *)
class ScreenCaptureService: NSObject, ObservableObject {

    // MARK: - Published State

    @Published var isScreenSharing = false
    @Published var hasPermission = false
    @Published var availableDisplays: [SCDisplay] = []
    @Published var availableWindows: [SCWindow] = []
    @Published var selectedDisplay: SCDisplay?
    @Published var selectedWindow: SCWindow?

    // MARK: - Screen Capture

    private var stream: SCStream?
    private var streamOutput: ScreenCaptureStreamOutput?
    private var contentFilter: SCContentFilter?

    // WebRTC video source for screen sharing
    private(set) var videoSource: RTCVideoSource?
    private(set) var videoTrack: RTCVideoTrack?

    private let peerConnectionFactory: RTCPeerConnectionFactory

    // MARK: - Initialization

    init(peerConnectionFactory: RTCPeerConnectionFactory) {
        self.peerConnectionFactory = peerConnectionFactory
        super.init()
        checkPermission()
    }

    // MARK: - Permission

    func checkPermission() {
        if #available(macOS 15.0, *) {
            // Use new API for macOS 15+
            Task {
                do {
                    let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
                    await MainActor.run {
                        self.hasPermission = true
                        self.availableDisplays = content.displays
                        self.availableWindows = content.windows.filter { $0.isOnScreen }
                    }
                } catch {
                    print("[ScreenCapture] Permission check failed: \(error)")
                    await MainActor.run {
                        self.hasPermission = false
                    }
                }
            }
        } else {
            // For older macOS versions
            Task {
                do {
                    let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
                    await MainActor.run {
                        self.hasPermission = true
                        self.availableDisplays = content.displays
                        self.availableWindows = content.windows.filter { $0.isOnScreen }
                    }
                } catch {
                    print("[ScreenCapture] Permission check failed: \(error)")
                    await MainActor.run {
                        self.hasPermission = false
                    }
                }
            }
        }
    }

    func requestPermission() {
        // Open System Settings to Screen Recording permissions
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") {
            NSWorkspace.shared.open(url)
        }
    }

    // MARK: - Content Refresh

    func refreshAvailableContent() async {
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            await MainActor.run {
                self.availableDisplays = content.displays
                self.availableWindows = content.windows.filter {
                    $0.isOnScreen && $0.owningApplication?.bundleIdentifier != Bundle.main.bundleIdentifier
                }
            }
        } catch {
            print("[ScreenCapture] Failed to refresh content: \(error)")
        }
    }

    // MARK: - Start Screen Sharing

    func startSharingDisplay(_ display: SCDisplay) async throws {
        await MainActor.run {
            selectedDisplay = display
            selectedWindow = nil
        }

        // Create video source
        videoSource = peerConnectionFactory.videoSource()

        // Create content filter for display
        let filter = SCContentFilter(display: display, excludingWindows: [])
        contentFilter = filter

        // Configure stream
        let config = SCStreamConfiguration()
        config.width = Int(display.width)
        config.height = Int(display.height)
        config.minimumFrameInterval = CMTime(value: 1, timescale: 30) // 30 fps
        config.queueDepth = 5
        config.showsCursor = true
        config.pixelFormat = kCVPixelFormatType_32BGRA

        // Create stream output handler
        streamOutput = ScreenCaptureStreamOutput(videoSource: videoSource!)

        // Create and start stream
        stream = SCStream(filter: filter, configuration: config, delegate: self)
        try stream?.addStreamOutput(streamOutput!, type: .screen, sampleHandlerQueue: DispatchQueue(label: "screen.capture.queue"))
        try await stream?.startCapture()

        // Create video track
        videoTrack = peerConnectionFactory.videoTrack(with: videoSource!, trackId: "screen0")
        videoTrack?.isEnabled = true

        await MainActor.run {
            isScreenSharing = true
        }

        print("[ScreenCapture] Started sharing display: \(display.displayID)")
    }

    func startSharingWindow(_ window: SCWindow) async throws {
        await MainActor.run {
            selectedWindow = window
            selectedDisplay = nil
        }

        // Create video source
        videoSource = peerConnectionFactory.videoSource()

        // Create content filter for window
        let filter = SCContentFilter(desktopIndependentWindow: window)
        contentFilter = filter

        // Configure stream
        let config = SCStreamConfiguration()
        config.width = Int(window.frame.width)
        config.height = Int(window.frame.height)
        config.minimumFrameInterval = CMTime(value: 1, timescale: 30) // 30 fps
        config.queueDepth = 5
        config.showsCursor = true
        config.pixelFormat = kCVPixelFormatType_32BGRA

        // Create stream output handler
        streamOutput = ScreenCaptureStreamOutput(videoSource: videoSource!)

        // Create and start stream
        stream = SCStream(filter: filter, configuration: config, delegate: self)
        try stream?.addStreamOutput(streamOutput!, type: .screen, sampleHandlerQueue: DispatchQueue(label: "screen.capture.queue"))
        try await stream?.startCapture()

        // Create video track
        videoTrack = peerConnectionFactory.videoTrack(with: videoSource!, trackId: "screen0")
        videoTrack?.isEnabled = true

        await MainActor.run {
            isScreenSharing = true
        }

        print("[ScreenCapture] Started sharing window: \(window.title ?? "Unknown")")
    }

    // MARK: - Stop Screen Sharing

    func stopSharing() async {
        do {
            try await stream?.stopCapture()
        } catch {
            print("[ScreenCapture] Error stopping capture: \(error)")
        }

        stream = nil
        streamOutput = nil
        contentFilter = nil
        videoSource = nil
        videoTrack = nil

        await MainActor.run {
            isScreenSharing = false
            selectedDisplay = nil
            selectedWindow = nil
        }

        print("[ScreenCapture] Stopped screen sharing")
    }

    // MARK: - Get Video Track

    func getVideoTrack() -> RTCVideoTrack? {
        return videoTrack
    }
}

// MARK: - SCStreamDelegate

@available(macOS 12.3, *)
extension ScreenCaptureService: SCStreamDelegate {
    func stream(_ stream: SCStream, didStopWithError error: Error) {
        print("[ScreenCapture] Stream stopped with error: \(error)")
        Task {
            await stopSharing()
        }
    }
}

// MARK: - Stream Output Handler

@available(macOS 12.3, *)
class ScreenCaptureStreamOutput: NSObject, SCStreamOutput {
    private let videoSource: RTCVideoSource

    init(videoSource: RTCVideoSource) {
        self.videoSource = videoSource
        super.init()
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen else { return }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let timestampNs = Int64(CMTimeGetSeconds(timestamp) * Double(NSEC_PER_SEC))

        // Create RTCVideoFrame from pixel buffer
        let rtcPixelBuffer = RTCCVPixelBuffer(pixelBuffer: pixelBuffer)
        let frame = RTCVideoFrame(
            buffer: rtcPixelBuffer,
            rotation: ._0,
            timeStampNs: timestampNs
        )

        // Feed frame to WebRTC video source
        videoSource.capturer(RTCVideoCapturer(), didCapture: frame)
    }
}

// MARK: - Screen Share Picker View

import SwiftUI

@available(macOS 12.3, *)
struct ScreenSharePicker: View {
    @ObservedObject var screenCaptureService: ScreenCaptureService
    let onSelect: () -> Void
    let onCancel: () -> Void

    @State private var selectedTab = 0

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("Share Your Screen")
                    .font(.title2)
                    .fontWeight(.bold)
                Spacer()
                Button(action: onCancel) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }
            .padding()

            Divider()

            // Tab selection
            Picker("", selection: $selectedTab) {
                Text("Displays").tag(0)
                Text("Windows").tag(1)
            }
            .pickerStyle(.segmented)
            .padding()

            // Content
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 200))], spacing: 16) {
                    if selectedTab == 0 {
                        ForEach(screenCaptureService.availableDisplays, id: \.displayID) { display in
                            DisplayPreviewCard(
                                display: display,
                                isSelected: screenCaptureService.selectedDisplay?.displayID == display.displayID
                            ) {
                                Task {
                                    try await screenCaptureService.startSharingDisplay(display)
                                    onSelect()
                                }
                            }
                        }
                    } else {
                        ForEach(screenCaptureService.availableWindows, id: \.windowID) { window in
                            WindowPreviewCard(
                                window: window,
                                isSelected: screenCaptureService.selectedWindow?.windowID == window.windowID
                            ) {
                                Task {
                                    try await screenCaptureService.startSharingWindow(window)
                                    onSelect()
                                }
                            }
                        }
                    }
                }
                .padding()
            }

            Divider()

            // Footer
            HStack {
                if !screenCaptureService.hasPermission {
                    Button("Grant Screen Recording Permission") {
                        screenCaptureService.requestPermission()
                    }
                    .buttonStyle(.borderedProminent)
                }

                Spacer()

                Button("Cancel") {
                    onCancel()
                }
            }
            .padding()
        }
        .frame(width: 600, height: 500)
        .onAppear {
            Task {
                await screenCaptureService.refreshAvailableContent()
            }
        }
    }
}

@available(macOS 12.3, *)
struct DisplayPreviewCard: View {
    let display: SCDisplay
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            VStack(spacing: 8) {
                // Display preview placeholder
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.gray.opacity(0.2))
                    .aspectRatio(16/10, contentMode: .fit)
                    .overlay(
                        Image(systemName: "display")
                            .font(.system(size: 40))
                            .foregroundColor(.secondary)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(isSelected ? Color.blue : Color.clear, lineWidth: 3)
                    )

                Text("Display \(display.displayID)")
                    .font(.subheadline)
                    .fontWeight(isSelected ? .semibold : .regular)

                Text("\(Int(display.width)) x \(Int(display.height))")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding(8)
            .background(isSelected ? Color.blue.opacity(0.1) : Color.clear)
            .cornerRadius(12)
        }
        .buttonStyle(.plain)
    }
}

@available(macOS 12.3, *)
struct WindowPreviewCard: View {
    let window: SCWindow
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            VStack(spacing: 8) {
                // Window preview placeholder
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.gray.opacity(0.2))
                    .aspectRatio(16/10, contentMode: .fit)
                    .overlay(
                        Image(systemName: "macwindow")
                            .font(.system(size: 40))
                            .foregroundColor(.secondary)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(isSelected ? Color.blue : Color.clear, lineWidth: 3)
                    )

                Text(window.title ?? "Unknown Window")
                    .font(.subheadline)
                    .fontWeight(isSelected ? .semibold : .regular)
                    .lineLimit(1)

                if let app = window.owningApplication?.applicationName {
                    Text(app)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .padding(8)
            .background(isSelected ? Color.blue.opacity(0.1) : Color.clear)
            .cornerRadius(12)
        }
        .buttonStyle(.plain)
    }
}
