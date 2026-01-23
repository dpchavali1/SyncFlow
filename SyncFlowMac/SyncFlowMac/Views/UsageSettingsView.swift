//
//  UsageSettingsView.swift
//  SyncFlowMac
//
//  Displays current usage and plan limits.
//

import SwiftUI
import FirebaseDatabase

private let trialDays: Int64 = 7 // 7 day trial

// Trial/Free tier: 500MB upload/month, 1GB storage
private let trialMonthlyBytes: Int64 = 500 * 1024 * 1024
private let trialStorageBytes: Int64 = 1 * 1024 * 1024 * 1024

// Paid tier: 3GB upload/month, 15GB storage
private let paidMonthlyBytes: Int64 = 3 * 1024 * 1024 * 1024
private let paidStorageBytes: Int64 = 15 * 1024 * 1024 * 1024

struct UsageSettingsView: View {
    @EnvironmentObject var appState: AppState

    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var summary: UsageSummary?

    var body: some View {
        Form {
            Section {
                if let userId = appState.userId, !userId.isEmpty {
                    Text(userId)
                        .font(.caption)
                        .textSelection(.enabled)
                } else {
                    Text("Not paired")
                        .foregroundColor(.secondary)
                }
            } header: {
                Text("User")
            }

            if let summary = summary {
                let planLabel = planLabel(plan: summary.plan, isPaid: summary.isPaid)
                let monthlyLimit = summary.isPaid ? paidMonthlyBytes : trialMonthlyBytes
                let storageLimit = summary.isPaid ? paidStorageBytes : trialStorageBytes

                Section {
                    LabeledContent("Plan") {
                        Text(planLabel)
                            .foregroundColor(.secondary)
                    }

                    if !summary.isPaid {
                        Text(trialStatusText(startedAt: summary.trialStartedAt))
                            .foregroundColor(.secondary)
                    } else if let expiresAt = summary.planExpiresAt {
                        Text("Renews on \(formatDate(expiresAt))")
                            .foregroundColor(.secondary)
                    }
                } header: {
                    Text("Plan")
                }

                Section {
                    UsageBarRow(
                        title: "Monthly uploads",
                        usedBytes: summary.monthlyUploadBytes,
                        limitBytes: monthlyLimit
                    )
                    Text("MMS: \(formatBytes(summary.monthlyMmsBytes)) • Files: \(formatBytes(summary.monthlyFileBytes))")
                        .font(.caption)
                        .foregroundColor(.secondary)
                } header: {
                    Text("Usage")
                }

                Section {
                    UsageBarRow(
                        title: "Storage",
                        usedBytes: summary.storageBytes,
                        limitBytes: storageLimit
                    )
                } header: {
                    Text("Storage")
                }

                if let updatedAt = summary.lastUpdatedAt {
                    Section {
                        Text("Last updated \(formatDateTime(updatedAt))")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }

            if let errorMessage = errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundColor(.red)
                }
            }

            Section {
                Button(isLoading ? "Refreshing..." : "Refresh") {
                    Task { await loadUsage() }
                }
                .disabled(isLoading || appState.userId == nil)
            }
        }
        .formStyle(.grouped)
        .task {
            await loadUsage()
        }
    }

    private func loadUsage() async {
        guard let userId = appState.userId, !userId.isEmpty else {
            summary = nil
            errorMessage = nil
            return
        }

        isLoading = true
        errorMessage = nil

        do {
            let snapshot = try await Database.database()
                .reference()
                .child("users")
                .child(userId)
                .child("usage")
                .getData()

            summary = parseUsage(snapshot)
        } catch {
            errorMessage = "Failed to load usage: \(error.localizedDescription)"
        }

        isLoading = false
    }
}

private struct UsageSummary {
    let plan: String?
    let planExpiresAt: Int64?
    let trialStartedAt: Int64?
    let storageBytes: Int64
    let monthlyUploadBytes: Int64
    let monthlyMmsBytes: Int64
    let monthlyFileBytes: Int64
    let lastUpdatedAt: Int64?
    let isPaid: Bool
}

private func parseUsage(_ snapshot: DataSnapshot) -> UsageSummary {
    let usage = snapshot.value as? [String: Any] ?? [:]

    let plan = usage["plan"] as? String
    let planExpiresAt = (usage["planExpiresAt"] as? NSNumber)?.int64Value
    let trialStartedAt = (usage["trialStartedAt"] as? NSNumber)?.int64Value
    let storageBytes = (usage["storageBytes"] as? NSNumber)?.int64Value ?? 0
    let lastUpdatedAt = (usage["lastUpdatedAt"] as? NSNumber)?.int64Value

    let periodKey = currentPeriodKey()
    let monthly = usage["monthly"] as? [String: Any]
    let periodData = monthly?[periodKey] as? [String: Any]
    let monthlyUploadBytes = (periodData?["uploadBytes"] as? NSNumber)?.int64Value ?? 0
    let monthlyMmsBytes = (periodData?["mmsBytes"] as? NSNumber)?.int64Value ?? 0
    let monthlyFileBytes = (periodData?["fileBytes"] as? NSNumber)?.int64Value ?? 0

    let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
    let isPaid = isPaidPlan(plan: plan, planExpiresAt: planExpiresAt, nowMs: nowMs)

    return UsageSummary(
        plan: plan,
        planExpiresAt: planExpiresAt,
        trialStartedAt: trialStartedAt,
        storageBytes: storageBytes,
        monthlyUploadBytes: monthlyUploadBytes,
        monthlyMmsBytes: monthlyMmsBytes,
        monthlyFileBytes: monthlyFileBytes,
        lastUpdatedAt: lastUpdatedAt,
        isPaid: isPaid
    )
}

private struct UsageBarRow: View {
    let title: String
    let usedBytes: Int64
    let limitBytes: Int64

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title)
                Spacer()
                Text("\(formatBytes(usedBytes)) / \(formatBytes(limitBytes))")
                    .foregroundColor(.secondary)
            }
            ProgressView(value: progressValue)
        }
    }

    private var progressValue: Double {
        guard limitBytes > 0 else { return 0 }
        return min(1.0, Double(usedBytes) / Double(limitBytes))
    }
}

private func planLabel(plan: String?, isPaid: Bool) -> String {
    if !isPaid { return "Trial" }
    switch plan?.lowercased() {
    case "lifetime":
        return "Lifetime"
    case "yearly":
        return "Yearly"
    case "monthly":
        return "Monthly"
    case "paid":
        return "Paid"
    default:
        return "Paid"
    }
}

private func trialStatusText(startedAt: Int64?) -> String {
    if startedAt == nil {
        return "Trial not started (starts on first upload)"
    }
    let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
    let endMs = startedAt! + trialDays * 24 * 60 * 60 * 1000
    let remaining = max(0, (endMs - nowMs) / (24 * 60 * 60 * 1000))
    if remaining > 0 {
        return "\(remaining) days remaining in trial"
    }
    return "Trial expired"
}

private func formatBytes(_ bytes: Int64) -> String {
    let formatter = ByteCountFormatter()
    formatter.allowedUnits = [.useKB, .useMB, .useGB]
    formatter.countStyle = .binary
    return formatter.string(fromByteCount: bytes)
}

private func formatDate(_ millis: Int64) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
    let formatter = DateFormatter()
    formatter.dateStyle = .medium
    return formatter.string(from: date)
}

private func formatDateTime(_ millis: Int64) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
    let formatter = DateFormatter()
    formatter.dateStyle = .medium
    formatter.timeStyle = .short
    return formatter.string(from: date)
}

private func currentPeriodKey() -> String {
    let formatter = DateFormatter()
    formatter.dateFormat = "yyyyMM"
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    return formatter.string(from: Date())
}

private func isPaidPlan(plan: String?, planExpiresAt: Int64?, nowMs: Int64) -> Bool {
    guard let normalized = plan?.lowercased() else { return false }
    if normalized == "lifetime" { return true }
    if normalized == "monthly" || normalized == "yearly" || normalized == "paid" {
        return planExpiresAt.map { $0 > nowMs } ?? true
    }
    return false
}
