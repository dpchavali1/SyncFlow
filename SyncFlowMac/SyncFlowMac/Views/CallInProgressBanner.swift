//
//  CallInProgressBanner.swift
//  SyncFlowMac
//
//  Lightweight banner shown when a call was answered from macOS
//

import SwiftUI
import Combine

struct CallInProgressBanner: View {
    let call: ActiveCall
    let onEndCall: () -> Void
    let onDismiss: () -> Void
    let isTransferActive: Bool
    let isTransferConnecting: Bool
    let onStartTransfer: () -> Void
    let onStopTransfer: () -> Void

    @State private var now = Date()

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "phone.fill")
                .foregroundColor(.white)
                .padding(10)
                .background(Color.green)
                .clipShape(Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text("Call in progress")
                    .font(.headline)
                Text(call.contactName ?? call.phoneNumber)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                Text(durationString)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            if isTransferActive {
                Button(action: onStopTransfer) {
                    Label("Stop Transfer", systemImage: "speaker.slash.fill")
                }
                .buttonStyle(.bordered)
            } else {
                Button(action: onStartTransfer) {
                    Label(
                        isTransferConnecting ? "Connecting..." : "Transfer Audio",
                        systemImage: "speaker.wave.2.fill"
                    )
                }
                .buttonStyle(.bordered)
                .disabled(isTransferConnecting)
            }

            Button(role: .destructive, action: onEndCall) {
                Label("End", systemImage: "phone.down.fill")
            }
            .buttonStyle(.borderedProminent)

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(12)
        .background(.thickMaterial)
        .cornerRadius(12)
        .shadow(radius: 6, y: 2)
        .onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) { date in
            now = date
        }
    }

    private var durationString: String {
        let elapsed = max(0, now.timeIntervalSince(call.timestamp))
        let minutes = Int(elapsed) / 60
        let seconds = Int(elapsed) % 60
        return String(format: "%02d:%02d elapsed", minutes, seconds)
    }
}

struct CallInProgressBanner_Previews: PreviewProvider {
    static var previews: some View {
        CallInProgressBanner(
            call: ActiveCall(
                id: "123",
                phoneNumber: "+19032867804",
                contactName: "John Doe",
                callState: .active,
                timestamp: Date()
            ),
            onEndCall: {},
            onDismiss: {},
            isTransferActive: false,
            isTransferConnecting: false,
            onStartTransfer: {},
            onStopTransfer: {}
        )
        .padding()
    }
}
