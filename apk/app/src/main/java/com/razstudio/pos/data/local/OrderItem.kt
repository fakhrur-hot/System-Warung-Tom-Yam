package com.razstudio.pos.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a single line in an order.
 * Uses the snapshot pattern: nameSnapshot, unitPriceSnapshot, categorySnapshot
 * are copied from the menu at order time and never updated by future menu edits.
 */
@Entity(
    tableName = "order_items",
    foreignKeys = [
        ForeignKey(
            entity = Order::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("orderId")]
)
data class OrderItem(
    @PrimaryKey val id: String,
    val orderId: String,
    val menuItemId: String,
    val nameSnapshot: String,
    val unitPriceSnapshot: Double,
    val categorySnapshot: String,
    val quantity: Int,
    val note: String? = null,
    val sentToKitchen: Boolean = false,
    // Which order-placement round this line belongs to (1 = the table's first order,
    // 2 = the next round of items added to the same still-occupied table, etc., capped
    // at 10 per order). Lets the order detail view group line items by session.
    val sessionNumber: Int = 1
)
