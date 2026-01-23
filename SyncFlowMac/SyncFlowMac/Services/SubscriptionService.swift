//
//  SubscriptionService.swift
//  SyncFlowMac
//
//  Handles in-app purchases and subscriptions using StoreKit 2
//

import Foundation
import StoreKit
import Combine
import FirebaseDatabase

// MARK: - Product Identifiers

enum SubscriptionProduct: String, CaseIterable {
    case monthly = "com.syncflow.subscription.monthly"
    case yearly = "com.syncflow.subscription.yearly"
    case lifetime = "com.syncflow.lifetime"

    var displayName: String {
        switch self {
        case .monthly: return "Monthly"
        case .yearly: return "Yearly"
        case .lifetime: return "Lifetime"
        }
    }

    var description: String {
        switch self {
        case .monthly: return "$3.99/month"
        case .yearly: return "$29.99/year (Save 37%)"
        case .lifetime: return "$99.99 one-time"
        }
    }
}

// MARK: - Subscription Status

enum SubscriptionStatus: Equatable {
    case notSubscribed
    case trial(daysRemaining: Int)
    case subscribed(plan: String, expiresAt: Date?)
    case lifetime
    case expired

    var isActive: Bool {
        switch self {
        case .notSubscribed, .expired:
            return false
        case .trial, .subscribed, .lifetime:
            return true
        }
    }

    var displayText: String {
        switch self {
        case .notSubscribed:
            return "Not Subscribed"
        case .trial(let days):
            return "Trial: \(days) days remaining"
        case .subscribed(let plan, let expires):
            if let expires = expires {
                let formatter = DateFormatter()
                formatter.dateStyle = .medium
                return "\(plan) (expires \(formatter.string(from: expires)))"
            }
            return plan
        case .lifetime:
            return "Lifetime Access"
        case .expired:
            return "Subscription Expired"
        }
    }
}

// MARK: - Subscription Service

@MainActor
class SubscriptionService: ObservableObject {
    static let shared = SubscriptionService()

    @Published var products: [Product] = []
    @Published var purchasedProductIDs: Set<String> = []
    @Published var subscriptionStatus: SubscriptionStatus = .notSubscribed
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?

    private var updateListenerTask: Task<Void, Error>?
    private let trialStartKey = "syncflow_trial_start_date"
    private let trialDuration: TimeInterval = 7 * 24 * 60 * 60 // 7 days trial (reduced from 30)

    private init() {
        updateListenerTask = listenForTransactions()

        Task {
            await loadProducts()
            await updateSubscriptionStatus()
        }
    }

    deinit {
        updateListenerTask?.cancel()
    }

    // MARK: - Trial Management

    var trialStartDate: Date? {
        get {
            UserDefaults.standard.object(forKey: trialStartKey) as? Date
        }
        set {
            UserDefaults.standard.set(newValue, forKey: trialStartKey)
        }
    }

    func startTrialIfNeeded() {
        if trialStartDate == nil {
            trialStartDate = Date()
            print("SubscriptionService: Trial started")
        }
    }

    var trialDaysRemaining: Int {
        guard let startDate = trialStartDate else {
            return 7 // Full trial if not started (7 days)
        }

        let endDate = startDate.addingTimeInterval(trialDuration)
        let remaining = endDate.timeIntervalSince(Date())
        return max(0, Int(remaining / (24 * 60 * 60)))
    }

    var isTrialActive: Bool {
        return trialDaysRemaining > 0
    }

    var isTrialExpired: Bool {
        guard trialStartDate != nil else { return false }
        return trialDaysRemaining <= 0
    }

    /// Returns true if user has an active paid subscription (not trial)
    var isPremium: Bool {
        // First check StoreKit subscription
        switch subscriptionStatus {
        case .subscribed, .lifetime:
            return true
        case .trial, .expired, .notSubscribed:
            break // Fall through to check Firebase plan
        }

        // Fallback to Firebase plan from PreferencesService (for testing tab)
        return PreferencesService.shared.isPaidUser()
    }

    // MARK: - Load Products

    func loadProducts() async {
        isLoading = true
        errorMessage = nil

        do {
            let productIDs = SubscriptionProduct.allCases.map { $0.rawValue }
            let storeProducts = try await Product.products(for: productIDs)

            // Sort: monthly, yearly, lifetime
            products = storeProducts.sorted { p1, p2 in
                let order: [String: Int] = [
                    SubscriptionProduct.monthly.rawValue: 0,
                    SubscriptionProduct.yearly.rawValue: 1,
                    SubscriptionProduct.lifetime.rawValue: 2
                ]
                return (order[p1.id] ?? 99) < (order[p2.id] ?? 99)
            }

            print("SubscriptionService: Loaded \(products.count) products")
        } catch {
            print("SubscriptionService: Failed to load products: \(error)")
            errorMessage = "Failed to load subscription options"
        }

        isLoading = false
    }

    // MARK: - Purchase

    func purchase(_ product: Product) async throws -> Transaction? {
        isLoading = true
        errorMessage = nil

        do {
            let result = try await product.purchase()

            switch result {
            case .success(let verification):
                let transaction = try checkVerified(verification)
                await transaction.finish()
                await updateSubscriptionStatus()
                isLoading = false
                return transaction

            case .userCancelled:
                isLoading = false
                return nil

            case .pending:
                isLoading = false
                errorMessage = "Purchase is pending approval"
                return nil

            @unknown default:
                isLoading = false
                return nil
            }
        } catch {
            isLoading = false
            errorMessage = "Purchase failed: \(error.localizedDescription)"
            throw error
        }
    }

    // MARK: - Restore Purchases

    func restorePurchases() async {
        isLoading = true
        errorMessage = nil

        do {
            try await AppStore.sync()
            await updateSubscriptionStatus()
            print("SubscriptionService: Purchases restored")
        } catch {
            errorMessage = "Failed to restore purchases"
            print("SubscriptionService: Restore failed: \(error)")
        }

        isLoading = false
    }

    // MARK: - Update Status

    func updateSubscriptionStatus() async {
        var hasActiveSubscription = false
        var activePlan: String?
        var expirationDate: Date?
        var hasLifetime = false

        // Check for active transactions
        for await result in Transaction.currentEntitlements {
            do {
                let transaction = try checkVerified(result)
                purchasedProductIDs.insert(transaction.productID)

                if transaction.productID == SubscriptionProduct.lifetime.rawValue {
                    hasLifetime = true
                } else if transaction.productID == SubscriptionProduct.yearly.rawValue {
                    hasActiveSubscription = true
                    activePlan = "Yearly"
                    expirationDate = transaction.expirationDate
                } else if transaction.productID == SubscriptionProduct.monthly.rawValue {
                    if activePlan != "Yearly" { // Yearly takes precedence
                        hasActiveSubscription = true
                        activePlan = "Monthly"
                        expirationDate = transaction.expirationDate
                    }
                }
            } catch {
                print("SubscriptionService: Transaction verification failed")
            }
        }

        // Determine status
        if hasLifetime {
            subscriptionStatus = .lifetime
        } else if hasActiveSubscription, let plan = activePlan {
            subscriptionStatus = .subscribed(plan: plan, expiresAt: expirationDate)
        } else if isTrialActive {
            subscriptionStatus = .trial(daysRemaining: trialDaysRemaining)
        } else if isTrialExpired {
            subscriptionStatus = .expired
        } else {
            // New user - start trial
            startTrialIfNeeded()
            subscriptionStatus = .trial(daysRemaining: trialDaysRemaining)
        }

        print("SubscriptionService: Status updated to \(subscriptionStatus.displayText)")
        await syncUsagePlan()
    }

    // MARK: - Transaction Listener

    private func listenForTransactions() -> Task<Void, Error> {
        return Task.detached {
            for await result in Transaction.updates {
                do {
                    let transaction = try self.checkVerified(result)
                    await self.updateSubscriptionStatus()
                    await transaction.finish()
                } catch {
                    print("SubscriptionService: Transaction update failed verification")
                }
            }
        }
    }

    // MARK: - Verification

    private nonisolated func checkVerified<T>(_ result: VerificationResult<T>) throws -> T {
        switch result {
        case .unverified:
            throw StoreError.verificationFailed
        case .verified(let safe):
            return safe
        }
    }

    // MARK: - Helpers

    func product(for identifier: SubscriptionProduct) -> Product? {
        return products.first { $0.id == identifier.rawValue }
    }

    var hasActiveSubscription: Bool {
        return subscriptionStatus.isActive
    }

    private func syncUsagePlan() async {
        guard let userId = UserDefaults.standard.string(forKey: "syncflow_user_id"),
              !userId.isEmpty else {
            return
        }

        let usageRef = Database.database()
            .reference()
            .child("users")
            .child(userId)
            .child("usage")

        // FIRST: Try to load plan from Firebase (for testing tab assignments)
        do {
            let snapshot = try await usageRef.getData()
            if let usageData = snapshot.value as? [String: Any] {
                let plan = usageData["plan"] as? String ?? ""
                let planExpiresAt = usageData["planExpiresAt"] as? NSNumber
                let freeTrialExpiresAt = usageData["freeTrialExpiresAt"] as? NSNumber
                let now = Int64(Date().timeIntervalSince1970 * 1000)

                // If Firebase has a valid paid plan that hasn't expired, use it
                if ["monthly", "yearly", "lifetime"].contains(plan.lowercased()) {
                    if plan.lowercased() == "lifetime" ||
                       (planExpiresAt != nil && planExpiresAt!.int64Value > now) {
                        // Valid paid plan from Firebase, use it
                        updateLocalPlanData(plan: plan, expiresAt: planExpiresAt?.int64Value ?? 0)

                        // Update subscriptionStatus to reflect the paid plan
                        DispatchQueue.main.async {
                            if plan.lowercased() == "lifetime" {
                                self.subscriptionStatus = .lifetime
                            } else {
                                let expiryDate = Date(timeIntervalSince1970: TimeInterval(planExpiresAt?.int64Value ?? 0) / 1000)
                                self.subscriptionStatus = .subscribed(plan: plan, expiresAt: expiryDate)
                            }
                        }
                        return
                    }
                }

                // If Firebase has active free trial, use it
                if let trialExpiry = freeTrialExpiresAt?.int64Value, trialExpiry > now {
                    updateLocalPlanData(plan: "free", expiresAt: trialExpiry)

                    // Update subscriptionStatus to reflect active trial
                    let trialDaysRemaining = Int((trialExpiry - now) / (24 * 60 * 60 * 1000))
                    DispatchQueue.main.async {
                        self.subscriptionStatus = .trial(daysRemaining: max(0, trialDaysRemaining))
                    }
                    return
                }
            }
        } catch {
            print("Error loading plan from Firebase: \(error)")
        }

        // FALLBACK: Sync StoreKit subscription data to Firebase
        var updates: [String: Any] = [
            "planUpdatedAt": ServerValue.timestamp()
        ]

        switch subscriptionStatus {
        case .lifetime:
            updates["plan"] = "lifetime"
            updates["planExpiresAt"] = NSNull()
        case .subscribed(let plan, let expiresAt):
            updates["plan"] = plan.lowercased()
            if let expiresAt = expiresAt {
                updates["planExpiresAt"] = Int64(expiresAt.timeIntervalSince1970 * 1000)
            } else {
                updates["planExpiresAt"] = NSNull()
            }
        default:
            updates["plan"] = NSNull()
            updates["planExpiresAt"] = NSNull()
        }

        try? await usageRef.updateChildValues(updates)
    }

    private func updateLocalPlanData(plan: String, expiresAt: Int64) {
        let prefs = PreferencesService.shared
        prefs.setUserPlan(plan, expiresAt: expiresAt)
    }
}

// MARK: - Store Errors

enum StoreError: LocalizedError {
    case verificationFailed
    case purchaseFailed
    case productNotFound

    var errorDescription: String? {
        switch self {
        case .verificationFailed:
            return "Transaction verification failed"
        case .purchaseFailed:
            return "Purchase could not be completed"
        case .productNotFound:
            return "Product not found"
        }
    }
}
