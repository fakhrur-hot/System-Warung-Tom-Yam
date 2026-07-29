package com.warungtomyam.pos.ui.tableview

import com.warungtomyam.pos.data.local.Order
import com.warungtomyam.pos.data.local.OrderItem
import com.warungtomyam.pos.data.local.OrderStatus
import com.warungtomyam.pos.data.local.Table

/**
 * UI-layer representation of a table's current status.
 *
 * Named [TableUiStatus] (rather than `TableStatus`) to avoid collision with the
 * [OrderStatus] domain enum. Both view-models use this type instead of their
 * own private copies.
 */
enum class TableUiStatus {
    FREE,
    RECEIVED,
    SENT_TO_KITCHEN,
    PREPARING,
    READY,
}

/**
 * Maps a domain [OrderStatus] to the UI [TableUiStatus].
 * Terminal states (COMPLETED, CANCELLED) and UNKNOWN all resolve to [TableUiStatus.FREE]
 * because those tables are available for a new order.
 */
fun OrderStatus.toTableUiStatus(): TableUiStatus = when (this) {
    OrderStatus.RECEIVED        -> TableUiStatus.RECEIVED
    OrderStatus.SENT_TO_KITCHEN -> TableUiStatus.SENT_TO_KITCHEN
    OrderStatus.PREPARING       -> TableUiStatus.PREPARING
    OrderStatus.READY           -> TableUiStatus.READY
    // COMPLETED, CANCELLED, UNKNOWN → table is free
    else                        -> TableUiStatus.FREE
}

/**
 * Shared state for a single table cell in the grid.
 * Used by both [TableViewViewModel] and [StaffOrderViewModel].
 */
data class TableState(
    val table: Table,
    val status: TableUiStatus = TableUiStatus.FREE,
    val order: Order? = null,
)

/**
 * Shared UI state for the order detail bottom sheet.
 * Used by both [TableViewViewModel] and [StaffOrderViewModel].
 */
data class OrderDetailState(
    val order: Order? = null,
    val items: List<OrderItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
)
