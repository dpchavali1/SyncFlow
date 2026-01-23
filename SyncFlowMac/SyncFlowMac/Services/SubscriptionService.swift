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
        print("SubscriptionService: updateSubscriptionStatus() called")
        // FIRST: Check Firebase for admin-assigned plan (Testing tab)
        // This takes priority over StoreKit for testing
        await syncUsagePlan()

        // If Firebase already set a valid paid status, don't override with StoreKit
        if case .subscribed = subscriptionStatus {
            print("SubscriptionService: Using Firebase-assigned paid plan")
            return
        }
        if subscriptionStatus == .lifetime {
            print("SubscriptionService: Using Firebase-assigned lifetime plan")
            return
        }

        print("SubscriptionService: No Firebase plan found, checking StoreKit...")

        // FALLBACK: Check for StoreKit subscriptions
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

        // Determine status from StoreKit
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
            print("SubscriptionService: No userId found in UserDefaults")
            return
        }

        print("SubscriptionService: Loading plan from Firebase for userId: \(userId)")

        let userRef = Database.database()
            .reference()
            .child("users")
            .child(userId)

        // FIRST: Try to load plan from Firebase (check both root level and usage path)
        do {
            let snapshot = try await userRef.getData()
            if let userData = snapshot.value as? [String: Any] {
                print("SubscriptionService: Firebase user data loaded")

                var plan = ""
                var planExpiresAt: NSNumber?
                var freeTrialExpiresAt: NSNumber?

                // Check for plan at root level (legacy location from before)
                if let rootPlan = userData["plan"] as? String {
                    plan = rootPlan
                    planExpiresAt = userData["planExpiresAt"] as? NSNumber
                    freeTrialExpiresAt = userData["freeTrialExpiresAt"] as? NSNumber
                    print("SubscriptionService: Found plan at root level: \(plan)")
                }

                // Check for plan in usage (new location from Testing tab)
                if let usageData = userData["usage"] as? [String: Any] {
                    if let usagePlan = usageData["plan"] as? String, !usagePlan.isEmpty {
                        plan = usagePlan
                        planExpiresAt = usageData["planExpiresAt"] as? NSNumber
                        freeTrialExpiresAt = usageData["freeTrialExpiresAt"] as? NSNumber
                        print("SubscriptionService: Found plan in usage: \(plan)")
                    }
                }

                let now = Int64(Date().timeIntervalSince1970 * 1000)
                print("SubscriptionService: plan=\(plan), planExpiresAt=\(planExpiresAt?.int64Value ?? 0), now=\(now)")

                // If Firebase has a valid paid plan that hasn't expired, use it
                if ["monthly", "yearly", "lifetime"].contains(plan.lowercased()) {
                    if plan.lowercased() == "lifetime" ||
                       (planExpiresAt != nil && planExpiresAt!.int64Value > now) {
                        // Valid paid plan from Firebase, use it
                        updateLocalPlanData(plan: plan, expiresAt: planExpiresAt?.int64Value ?? 0)

                        // Update subscriptionStatus to reflect the paid plan (immediately, not async)
                        if plan.lowercased() == "lifetime" {
                            self.subscriptionStatus = .lifetime
                        } else {
                            let expiryDate = Date(timeIntervalSince1970: TimeInterval(planExpiresAt?.int64Value ?? 0) / 1000)
                            self.subscriptionStatus = .subscribed(plan: plan, expiresAt: expiryDate)
                        }
                        print("SubscriptionService: Loaded Firebase plan: \(plan)")
                        return
                    }
                }

                // If Firebase has active free trial, use it
                if let trialExpiry = freeTrialExpiresAt?.int64Value, trialExpiry > now {
                    updateLocalPlanData(plan: "free", expiresAt: trialExpiry)

                    // Update subscriptionStatus to reflect active trial (immediately, not async)
                    let trialDaysRemaining = Int((trialExpiry - now) / (24 * 60 * 60 * 1000))
                    self.subscriptionStatus = .trial(daysRemaining: max(0, trialDaysRemaining))
                    print("SubscriptionService: Loaded Firebase free trial with \(trialDaysRemaining) days remaining")
                    return
                }
            } else {
                print("SubscriptionService: No usage data found in Firebase")
            }
        } catch {
            print("SubscriptionService: Error loading plan from Firebase: \(error)")
        }

        // FALLBACK: Sync StoreKit subscription data to Firebase
        let usageRef = userRef.child("usage")
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
