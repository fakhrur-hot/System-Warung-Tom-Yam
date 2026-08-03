package com.razstudio.pos.data.local

/**
 * Data classes for on-device report aggregation (Task 25).
 * These hold computed analytics from Room queries, used by ReportsViewModel
 * and rendered by ReportsScreen. Content matches the closing report checklist.
 */

/** Top-level report covering a date range. */
data class ReportData(
    val startDate: String,
    val endDate: String,
    val totalOrders: Int,
    val totalRevenue: Double,
    val avgOrderValue: Double,
    val perTableBreakdown: List<TableBreakdown>,
    val topNPerCategory: Map<String, List<TopItem>>,
    /**
     * Best sellers across the whole menu, most-sold first — the "what actually moves" list.
     * [topNPerCategory] answers a different question: it can only ever say which drink outsold
     * the other drinks, never that the drinks outsell the mains. Defaulted so existing callers
     * and tests keep compiling.
     */
    val topOverall: List<TopItem> = emptyList(),
    val paymentSplit: PaymentSplit,
    val cancelledSummary: CancelledSummary,
    /**
     * Hardware drawer-opening counter from the Sunmi AIDL service, or null when:
     * - the printer is not a Sunmi AIDL printer, or
     * - the counter call failed (service not available, device has no drawer).
     *
     * Openings without a matching cash sale surface till shrinkage. (HW-REQ-3, Task 2.4)
     */
    val drawerOpeningCount: Int? = null
)

/** Revenue and order count per table. */
data class TableBreakdown(
    val tableId: String,
    val tableLabel: String,
    val orderCount: Int,
    val revenue: Double
)

/** A popular item within a category. */
data class TopItem(
    val name: String,
    val quantity: Int,
    val revenue: Double
)

/**
 * Payment split for the closing report.
 *
 * [cashCount]/[qrCount] and their totals are kept as named fields because the printed report and
 * the closing-report HTML both read those two specifically — cash is the one a café counts against
 * the drawer, and it earns its own line.
 *
 * [byMethod] is every method that actually took money in the period, cash and static QR included.
 * It exists because the report previously picked out only the `"CASH"` and `"QR"` rows and silently
 * dropped everything else: once gateway payments land, `orders.paymentMethod` also holds codes like
 * `DUITNOW_QR` or `GRABPAY`, and those takings vanished from the breakdown while still counting
 * toward total revenue — a report that did not add up. (PG-REQ-7, task 9.2)
 */
data class PaymentSplit(
    val cashCount: Int,
    val cashTotal: Double,
    val qrCount: Int,
    val qrTotal: Double,
    /** Defaulted so existing constructors and tests keep compiling. */
    val byMethod: List<PaymentMethodTotal> = emptyList()
)

/** One method's takings in the report period, straight from `orders.paymentMethod`. */
data class PaymentMethodTotal(
    /** The stored code — `CASH`, `QR`, or a gateway method code. */
    val method: String,
    val orderCount: Int,
    val revenue: Double
)

/** Cancelled orders breakdown by who cancelled. */
data class CancelledSummary(
    val totalCount: Int,
    val totalValue: Double,
    val byAdmin: Int,
    val byCustomer: Int,
    val byStaff: Int
)

/** Room query result helper — revenue grouped by table. */
data class TableRevenue(
    val tableId: String,
    val orderCount: Int,
    val revenue: Double
)

/** Room query result helper — payment method count. */
data class PaymentMethodCount(
    val paymentMethod: String?,
    val orderCount: Int,
    val revenue: Double
)

/** Room query result helper — popular item aggregation. */
data class PopularItemRow(
    val menuItemId: String,
    val nameSnapshot: String,
    val categorySnapshot: String,
    val totalQuantity: Int,
    val totalRevenue: Double
)
