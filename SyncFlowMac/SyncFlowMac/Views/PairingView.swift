//
//  PairingView.swift
//  SyncFlowMac
//
//  QR code pairing view for connecting to Android phone
//

import SwiftUI
import Combine
import CoreImage.CIFilterBuiltins
import FirebaseDatabase

struct PairingView: View {
    @EnvironmentObject var appState: AppState

    @State private var pairingSession: PairingSession?
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var pairingStatus: PairingStatusState = .generating
    @State private var timeRemaining: TimeInterval = 0
    @State private var listenerHandle: DatabaseHandle?

    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    enum PairingStatusState {
        case generating
        case waitingForScan
        case success
        case rejected
        case expired
        case error(String)
    }

    var body: some View {
        VStack(spacing: 30) {
            // Header
            VStack(spacing: 10) {
                Image(systemName: "message.and.waveform.fill")
                    .font(.system(size: 80))
                    .foregroundColor(.blue)

                Text("Welcome to SyncFlow")
                    .font(.largeTitle)
                    .fontWeight(.bold)

                Text("Scan this QR code with your Android phone to connect")
                    .font(.body)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
            }

            Divider()
                .padding(.horizontal, 100)

            // Content based on state
            switch pairingStatus {
            case .generating:
                VStack(spacing: 20) {
                    ProgressView()
                        .scaleEffect(1.5)
                    Text("Generating QR Code...")
                        .foregroundColor(.secondary)
                }
                .padding(40)

            case .waitingForScan:
                if let session = pairingSession {
                    VStack(spacing: 20) {
                        // QR Code
                        qrCodeImage(for: session.qrPayload)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 220, height: 220)
                            .background(Color.white)
                            .cornerRadius(10)
                            .shadow(radius: 5)

                        // Timer
                        HStack(spacing: 8) {
                            Image(systemName: "clock")
                                .foregroundColor(timeRemaining <= 60 ? .orange : .secondary)
                            Text(formatTime(timeRemaining))
                                .font(.system(.title3, design: .monospaced))
                                .foregroundColor(timeRemaining <= 60 ? .orange : .primary)
                        }

                        // Refresh button
                        Button {
                            Task { await startPairing() }
                        } label: {
                            Label("Generate New QR Code", systemImage: "arrow.clockwise")
                        }
                        .buttonStyle(.borderless)
                        .foregroundColor(.blue)
                    }
                }

            case .success:
                VStack(spacing: 20) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 80))
                        .foregroundColor(.green)

                    Text("Successfully Paired!")
                        .font(.title)
                        .fontWeight(.semibold)

                    Text("Redirecting to messages...")
                        .foregroundColor(.secondary)
                }

            case .rejected:
                VStack(spacing: 20) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 80))
                        .foregroundColor(.orange)

                    Text("Pairing Declined")
                        .font(.title)
                        .fontWeight(.semibold)

                    Text("The pairing request was declined on your phone.")
                        .foregroundColor(.secondary)

                    Button {
                        Task { await startPairing() }
                    } label: {
                        Label("Try Again", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(.borderedProminent)
                }

            case .expired:
                VStack(spacing: 20) {
                    Image(systemName: "clock.badge.exclamationmark")
                        .font(.system(size: 80))
                        .foregroundColor(.red)

                    Text("Session Expired")
                        .font(.title)
                        .fontWeight(.semibold)

                    Text("The pairing session has expired. Please try again.")
                        .foregroundColor(.secondary)

                    Button {
                        Task { await startPairing() }
                    } label: {
                        Label("Generate New QR Code", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(.borderedProminent)
                }

            case .error(let message):
                VStack(spacing: 20) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 80))
                        .foregroundColor(.red)

                    Text("Pairing Failed")
                        .font(.title)
                        .fontWeight(.semibold)

                    Text(message)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)

                    Button {
                        Task { await startPairing() }
                    } label: {
                        Label("Try Again", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(.borderedProminent)
                }
            }

            // Instructions (only show when waiting for scan)
            if case .waitingForScan = pairingStatus {
                VStack(alignment: .leading, spacing: 10) {
                    Text("How to pair:")
                        .font(.headline)

                    HStack(alignment: .top) {
                        Text("1.")
                        Text("Open SyncFlow app on your Android phone")
                    }

                    HStack(alignment: .top) {
                        Text("2.")
                        Text("Go to Settings → Desktop Integration")
                    }

                    HStack(alignment: .top) {
                        Text("3.")
                        Text("Tap \"Scan Desktop QR Code\"")
                    }

                    HStack(alignment: .top) {
                        Text("4.")
                        Text("Point your camera at this QR code")
                    }

                    HStack(alignment: .top) {
                        Text("5.")
                        Text("Approve the pairing request on your phone")
                    }
                }
                .font(.body)
                .foregroundColor(.secondary)
                .padding()
                .background(Color.secondary.opacity(0.1))
                .cornerRadius(10)
                .frame(width: 500)
            }

            Spacer()
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(nsColor: .windowBackgroundColor))
        .task {
            await startPairing()
        }
        .onDisappear {
            cleanupListener()
        }
        .onReceive(timer) { _ in
            updateTimer()
        }
    }

    // MARK: - QR Code Generation

    private func qrCodeImage(for string: String) -> Image {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"

        if let outputImage = filter.outputImage {
            let scale = 10.0
            let scaledImage = outputImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

            if let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) {
                return Image(nsImage: NSImage(cgImage: cgImage, size: NSSize(width: 220, height: 220)))
            }
        }

        return Image(systemName: "qrcode")
    }

    // MARK: - Timer

    private func formatTime(_ seconds: TimeInterval) -> String {
        let mins = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return String(format: "%d:%02d", mins, secs)
    }

    private func updateTimer() {
        guard let session = pairingSession else { return }

        timeRemaining = session.timeRemaining

        if timeRemaining <= 0 && pairingStatus == .waitingForScan {
            cleanupListener()
            pairingStatus = .expired
        }
    }

    // MARK: - Pairing Logic

    private func startPairing() async {
        cleanupListener()
        pairingStatus = .generating
        pairingSession = nil
        errorMessage = nil

        do {
            let session = try await FirebaseService.shared.initiatePairing()
            await MainActor.run {
                pairingSession = session
                timeRemaining = session.timeRemaining
                pairingStatus = .waitingForScan

                // Start listening for approval
                listenerHandle = FirebaseService.shared.listenForPairingApproval(token: session.token) { status in
                    handlePairingStatus(status)
                }
            }
        } catch {
            await MainActor.run {
                pairingStatus = .error(error.localizedDescription)
            }
        }
    }

    private func handlePairingStatus(_ status: PairingStatus) {
        switch status {
        case .pending:
            // Still waiting
            break

        case .approved(let pairedUid, _):
            cleanupListener()
            pairingStatus = .success

            // Redirect after brief delay
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                appState.setPaired(userId: pairedUid)
            }

        case .rejected:
            cleanupListener()
            pairingStatus = .rejected

        case .expired:
            cleanupListener()
            pairingStatus = .expired
        }
    }

    private func cleanupListener() {
        if let handle = listenerHandle, let session = pairingSession {
            FirebaseService.shared.removePairingApprovalListener(token: session.token, handle: handle)
            listenerHandle = nil
        }
    }
}

extension PairingView.PairingStatusState: Equatable {
    static func == (lhs: PairingView.PairingStatusState, rhs: PairingView.PairingStatusState) -> Bool {
        switch (lhs, rhs) {
        case (.generating, .generating),
             (.waitingForScan, .waitingForScan),
             (.success, .success),
             (.rejected, .rejected),
             (.expired, .expired):
            return true
        case (.error(let a), .error(let b)):
            return a == b
        default:
            return false
        }
    }
}
