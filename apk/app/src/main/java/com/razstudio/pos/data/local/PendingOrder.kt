package com.razstudio.pos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for orders that failed to send due to network issues.
 * The offline queue holds these until connectivity is restored and they can be retried.
 */
@Entity(tableName = "pending_orders")
data class PendingOrder(
    @PrimaryKey val id: String,        // UUID
    val tableId: String,
    val itemsJson: String,             // JSON serialized list of NewOrderItem
    val createdAt: String,
    val retryCount: Int = 0
)
