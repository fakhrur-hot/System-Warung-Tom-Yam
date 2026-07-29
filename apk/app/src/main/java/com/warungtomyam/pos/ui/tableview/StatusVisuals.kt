package com.warungtomyam.pos.ui.tableview

import androidx.compose.ui.graphics.Color
import com.warungtomyam.pos.data.local.OrderStatus

/**
 * Maps an [OrderStatus] to the table-cell background color.
 *
 * Color semantics follow operational urgency (Requirement 9):
 * - [OrderStatus.READY] is red (highest attention — food is ready, serve now)
 * - [OrderStatus.SENT_TO_KITCHEN] / [OrderStatus.PREPARING] are purple (cooking)
 * - [OrderStatus.RECEIVED] is orange (new order, needs sending to kitchen)
 * - Free / terminal / null are green (table is available or order is done)
 * - [OrderStatus.UNKNOWN] is grey (unrecognized state)
 */
fun OrderStatus?.tableColor(): Color = when (this) {
    OrderStatus.READY           -> Color(0xFFF44336) // Red — serve now (highest urgency)
    OrderStatus.SENT_TO_KITCHEN -> Color(0xFF9C27B0) // Purple — cooking
    OrderStatus.PREPARING       -> Color(0xFF9C27B0) // Purple — cooking
    OrderStatus.RECEIVED        -> Color(0xFFFF9800) // Orange — awaiting kitchen
    OrderStatus.UNKNOWN         -> Color(0xFF9E9E9E) // Grey — unrecognized
    null,
    OrderStatus.COMPLETED,
    OrderStatus.CANCELLED       -> Color(0xFF4CAF50) // Green — free / done
}
