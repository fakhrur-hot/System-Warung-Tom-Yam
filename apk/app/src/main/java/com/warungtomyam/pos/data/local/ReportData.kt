package com.warungtomyam.pos.data.local

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
    val paymentSplit: PaymentSplit,
    val cancelledSummary: CancelledSummary
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

/** Cash-vs-QR payment split. */
data class PaymentSplit(
    val cashCount: Int,
    val cashTotal: Double,
    val qrCount: Int,
    val qrTotal: Double
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
