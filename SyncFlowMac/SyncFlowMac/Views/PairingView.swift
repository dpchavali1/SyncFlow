//
//  PairingView.swift
//  SyncFlowMac
//
//  QR code pairing view for connecting to Android phone
//

import SwiftUI
import AVFoundation

struct PairingView: View {
    @EnvironmentObject var appState: AppState

    @State private var pairingCode = ""
    @State private var deviceName = "MacBook"
    @State private var isProcessing = false
    @State private var errorMessage: String?
    @State private var showCamera = false

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

                Text("Connect your Android phone to access SMS messages on your Mac")
                    .font(.body)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
            }

            Divider()
                .padding(.horizontal, 100)

            // Pairing options
            VStack(spacing: 20) {
                // Device name
                HStack {
                    Text("Device Name:")
                        .frame(width: 120, alignment: .trailing)
                    TextField("Enter device name", text: $deviceName)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 300)
                }

                // Pairing code
                HStack {
                    Text("Pairing Code:")
                        .frame(width: 120, alignment: .trailing)
                    TextField("Paste code from Android app", text: $pairingCode)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 300)

                    Button("Pair") {
                        Task {
                            await pairWithCode()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(pairingCode.isEmpty || isProcessing)
                }

                // OR separator
                HStack {
                    Rectangle()
                        .fill(Color.secondary.opacity(0.3))
                        .frame(height: 1)
                    Text("OR")
                        .foregroundColor(.secondary)
                        .padding(.horizontal)
                    Rectangle()
                        .fill(Color.secondary.opacity(0.3))
                        .frame(height: 1)
                }
                .frame(width: 400)
                .padding(.vertical, 10)

                // Camera scanning (coming soon)
                Button {
                    showCamera = true
                } label: {
                    Label("Scan QR Code", systemImage: "qrcode.viewfinder")
                }
                .buttonStyle(.bordered)
            }

            // Instructions
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
                    Text("Tap \"Pair New Device\" to generate a QR code")
                }

                HStack(alignment: .top) {
                    Text("4.")
                    Text("Scan the QR code or copy and paste the pairing code above")
                }
            }
            .font(.body)
            .foregroundColor(.secondary)
            .padding()
            .background(Color.secondary.opacity(0.1))
            .cornerRadius(10)
            .frame(width: 500)

            // Error message
            if let error = errorMessage {
                Text(error)
                    .foregroundColor(.red)
                    .font(.caption)
            }

            // Processing indicator
            if isProcessing {
                ProgressView("Pairing...")
            }

            Spacer()
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(nsColor: .windowBackgroundColor))
        .sheet(isPresented: $showCamera) {
            QRScannerView { code in
                pairingCode = code
                showCamera = false
                Task {
                    await pairWithCode()
                }
            }
        }
    }

    // MARK: - Pairing Logic

    private func pairWithCode() async {
        guard !pairingCode.isEmpty else { return }

        isProcessing = true
        errorMessage = nil

        do {
            let userId = try await FirebaseService.shared.pairWithToken(
                pairingCode,
                deviceName: deviceName
            )

            await MainActor.run {
                appState.setPaired(userId: userId)
                isProcessing = false
            }
        } catch {
            await MainActor.run {
                errorMessage = error.localizedDescription
                isProcessing = false
            }
        }
    }
}

// MARK: - QR Scanner View (Coming Soon)

struct QRScannerView: View {
    let onCodeScanned: (String) -> Void
    @Environment(\.dismiss) var dismiss

    var body: some View {
        VStack(spacing: 20) {
            Text("QR Scanner")
                .font(.title)

            Text("Camera-based QR scanning coming soon!")
                .foregroundColor(.secondary)

            Text("For now, please copy and paste the pairing code manually.")
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding()

            Button("Close") {
                dismiss()
            }
        }
        .padding(40)
        .frame(width: 400, height: 300)
    }
}
