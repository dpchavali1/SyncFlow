import Foundation

// MARK: - Time Filters

enum TimeFilter: String, CaseIterable {
    case today = "today"
    case thisWeek = "this week"
    case thisMonth = "this month"
    case thisYear = "this year"
    case last7Days = "last 7 days"
    case last30Days = "last 30 days"

    var dateRange: (start: Date, end: Date) {
        let now = Date()
        let calendar = Calendar.current

        switch self {
        case .today:
            let start = calendar.startOfDay(for: now)
            return (start, now)
        case .thisWeek:
            let start = calendar.date(from: calendar.dateComponents([.yearForWeekOfYear, .weekOfYear], from: now))!
            return (start, now)
        case .thisMonth:
            let start = calendar.date(from: calendar.dateComponents([.year, .month], from: now))!
            return (start, now)
        case .thisYear:
            let start = calendar.date(from: calendar.dateComponents([.year], from: now))!
            return (start, now)
        case .last7Days:
            let start = calendar.date(byAdding: .day, value: -7, to: now)!
            return (start, now)
        case .last30Days:
            let start = calendar.date(byAdding: .day, value: -30, to: now)!
            return (start, now)
        }
    }

    var displayName: String {
        switch self {
        case .today: return "Today"
        case .thisWeek: return "This Week"
        case .thisMonth: return "This Month"
        case .thisYear: return "This Year"
        case .last7Days: return "Last 7 Days"
        case .last30Days: return "Last 30 Days"
        }
    }
}

// MARK: - Transaction Categories

enum TransactionCategory: String, CaseIterable {
    case shopping = "Shopping"
    case food = "Food & Dining"
    case transport = "Transport"
    case entertainment = "Entertainment"
    case utilities = "Utilities"
    case subscriptions = "Subscriptions"
    case travel = "Travel"
    case health = "Health"
    case groceries = "Groceries"
    case fuel = "Fuel"
    case other = "Other"

    var icon: String {
        switch self {
        case .shopping: return "bag.fill"
        case .food: return "fork.knife"
        case .transport: return "car.fill"
        case .entertainment: return "tv.fill"
        case .utilities: return "bolt.fill"
        case .subscriptions: return "repeat"
        case .travel: return "airplane"
        case .health: return "heart.fill"
        case .groceries: return "cart.fill"
        case .fuel: return "fuelpump.fill"
        case .other: return "creditcard.fill"
        }
    }
}

// MARK: - Parsed Transaction

struct ParsedTransaction: Identifiable {
    let id: String
    let amount: Double
    let currency: String
    let merchant: String?
    let category: TransactionCategory
    let date: Date
    let originalMessageBody: String
    let isDebit: Bool

    var formattedAmount: String {
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", amount))"
    }

    var formattedDate: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        return formatter.string(from: date)
    }
}

// MARK: - Spending Analysis

struct SpendingAnalysis {
    let total: Double
    let currency: String
    let transactionCount: Int
    let averageTransaction: Double
    let byMerchant: [(merchant: String, amount: Double)]
    let byCategory: [(category: TransactionCategory, amount: Double)]
    let transactions: [ParsedTransaction]
    let timeRange: String

    var formattedTotal: String {
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", total))"
    }

    var formattedAverage: String {
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", averageTransaction))"
    }
}

// MARK: - Bill Types

enum BillType: String, CaseIterable {
    case creditCard = "Credit Card"
    case utility = "Utility"
    case subscription = "Subscription"
    case loan = "Loan"
    case rent = "Rent"
    case insurance = "Insurance"
    case other = "Other"

    var icon: String {
        switch self {
        case .creditCard: return "creditcard.fill"
        case .utility: return "bolt.fill"
        case .subscription: return "repeat"
        case .loan: return "banknote.fill"
        case .rent: return "house.fill"
        case .insurance: return "shield.fill"
        case .other: return "doc.text.fill"
        }
    }
}

// MARK: - Bill Reminder

struct BillReminder: Identifiable {
    let id: String
    let billType: BillType
    let dueDate: Date?
    let amount: Double?
    let currency: String
    let merchant: String
    let originalMessageBody: String
    let messageDate: Date

    var isOverdue: Bool {
        guard let dueDate = dueDate else { return false }
        return dueDate < Date()
    }

    var isDueSoon: Bool {
        guard let dueDate = dueDate else { return false }
        let daysUntilDue = Calendar.current.dateComponents([.day], from: Date(), to: dueDate).day ?? 0
        return daysUntilDue >= 0 && daysUntilDue <= 7
    }

    var formattedAmount: String? {
        guard let amount = amount else { return nil }
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", amount))"
    }

    var formattedDueDate: String? {
        guard let dueDate = dueDate else { return nil }
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        return formatter.string(from: dueDate)
    }
}

// MARK: - Delivery Status

enum DeliveryStatus: String, CaseIterable {
    case ordered = "Ordered"
    case shipped = "Shipped"
    case inTransit = "In Transit"
    case outForDelivery = "Out for Delivery"
    case delivered = "Delivered"
    case exception = "Exception"

    var icon: String {
        switch self {
        case .ordered: return "bag.fill"
        case .shipped: return "shippingbox.fill"
        case .inTransit: return "box.truck.fill"
        case .outForDelivery: return "figure.walk"
        case .delivered: return "checkmark.circle.fill"
        case .exception: return "exclamationmark.triangle.fill"
        }
    }

    var color: String {
        switch self {
        case .ordered: return "blue"
        case .shipped: return "orange"
        case .inTransit: return "orange"
        case .outForDelivery: return "green"
        case .delivered: return "green"
        case .exception: return "red"
        }
    }
}

// MARK: - Package Status

struct PackageStatus: Identifiable {
    let id: String
    let carrier: String?
    let trackingNumber: String?
    let status: DeliveryStatus
    let estimatedDelivery: Date?
    let merchant: String?
    let originalMessageBody: String
    let messageDate: Date

    var formattedEstimatedDelivery: String? {
        guard let estimatedDelivery = estimatedDelivery else { return nil }
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        return formatter.string(from: estimatedDelivery)
    }
}

// MARK: - Balance Info

struct BalanceInfo: Identifiable {
    let id: String
    let accountType: String
    let balance: Double?
    let currency: String
    let asOfDate: Date
    let institution: String?
    let originalMessageBody: String

    var formattedBalance: String? {
        guard let balance = balance else { return nil }
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", balance))"
    }

    var formattedDate: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: asOfDate)
    }
}

// MARK: - OTP Info

struct OTPInfo: Identifiable {
    let id: String
    let code: String
    let source: String
    let timestamp: Date
    let expiresIn: String?
    let originalMessageBody: String

    var isExpired: Bool {
        let minutesSinceReceived = Calendar.current.dateComponents([.minute], from: timestamp, to: Date()).minute ?? 0
        return minutesSinceReceived > 10 // Consider OTPs expired after 10 minutes
    }

    var formattedTimestamp: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return formatter.string(from: timestamp)
    }

    var timeAgo: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: timestamp, relativeTo: Date())
    }
}

// MARK: - Recurring Expense

struct RecurringExpense: Identifiable {
    let id: String
    let merchant: String
    let amount: Double
    let currency: String
    let frequency: RecurringFrequency
    let lastCharge: Date
    let occurrences: Int

    var formattedAmount: String {
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", amount))"
    }

    var frequencyLabel: String {
        switch frequency {
        case .weekly: return "/wk"
        case .monthly: return "/mo"
        case .yearly: return "/yr"
        }
    }
}

enum RecurringFrequency: String {
    case weekly = "Weekly"
    case monthly = "Monthly"
    case yearly = "Yearly"
}

// MARK: - Smart Digest

struct SmartDigest {
    let totalSpentThisMonth: Double
    let totalSpentLastMonth: Double
    let spendingChange: Double
    let transactionCount: Int
    let upcomingBills: Int
    let recentPackages: Int
    let subscriptionTotal: Double
    let topMerchant: String?
    let currency: String

    var formattedThisMonth: String {
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", totalSpentThisMonth))"
    }

    var formattedLastMonth: String {
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", totalSpentLastMonth))"
    }

    var formattedSubscriptionTotal: String {
        let symbol = currency == "INR" ? "₹" : "$"
        return "\(symbol)\(String(format: "%.2f", subscriptionTotal))"
    }
}

// MARK: - AI Query Type

enum AIQueryType {
    case spending(merchant: String?, timeFilter: TimeFilter?)
    case upcomingBills
    case packageTracking
    case balanceQuery
    case otpFinding
    case transactionList(merchant: String?, timeFilter: TimeFilter?)
    case subscriptions
    case summary
    case spendingTrends
    case generalHelp
    case unknown(query: String)
}

// MARK: - AI Response Details

enum AIResponseDetails {
    case spending(SpendingAnalysis)
    case bills([BillReminder])
    case packages([PackageStatus])
    case balances([BalanceInfo])
    case otps([OTPInfo])
    case transactions([ParsedTransaction])
    case subscriptions([RecurringExpense])
    case digest(SmartDigest)
    case trends(SpendingAnalysis, SpendingAnalysis) // thisMonth, lastMonth
    case help(String)
    case noResults(String)
    case error(String)
}

// MARK: - AI Response

struct AIResponse {
    let queryType: AIQueryType
    let summary: String
    let details: AIResponseDetails
    let suggestedFollowUps: [String]
}

// MARK: - Chat Message

struct AIChatMessage: Identifiable {
    let id: String
    let isUser: Bool
    let content: AIChatContent
    let timestamp: Date

    init(isUser: Bool, content: AIChatContent) {
        self.id = UUID().uuidString
        self.isUser = isUser
        self.content = content
        self.timestamp = Date()
    }
}

enum AIChatContent {
    case text(String)
    case response(AIResponse)
}

// MARK: - Quick Action

struct AIQuickAction: Identifiable {
    let id: String
    let icon: String
    let title: String
    let subtitle: String
    let query: String
    let color: String

    init(icon: String, title: String, subtitle: String, query: String, color: String) {
        self.id = UUID().uuidString
        self.icon = icon
        self.title = title
        self.subtitle = subtitle
        self.query = query
        self.color = color
    }
}
