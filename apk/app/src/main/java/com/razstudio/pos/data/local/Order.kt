package com.razstudio.pos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a customer order.
 * Status is the typed [OrderStatus] enum, persisted as its name via a Room TypeConverter.
 */
@Entity(tableName = "orders")
data class Order(
    @PrimaryKey val id: String,

    /**
     * The table this order belongs to, or null in Kiosk Mode.
     *
     * Kiosk is a grocery-style till with no tables at all, so an order there is identified by
     * [orderNumber] instead. The two are mutually exclusive in practice: a device runs exactly one
     * mode, so a café's rows are all one shape or all the other, never mixed.
     */
    val tableId: String?,

    /**
     * Kiosk Mode's running number, unique within a business day and null everywhere else.
     *
     * Minted by `OrderNumberSequenceDao.getNextOrderNumber`, which has existed since schema v11 for
     * this purpose and had no caller until now — the column to put the number in was never added.
     */
    val orderNumber: Int? = null,
    val source: String,            // "QR" or "STAFF"
    val status: OrderStatus,
    val paymentMethod: String? = null,
    val total: Double,
    val sentToKitchenAt: String? = null,
    val cancelReason: String? = null,
    val cancelledBy: String? = null,
    val createdAt: String
)

/**
 * Single source of truth for order lifecycle states.
 * Persisted as [name] via [Converters]; parsed from the wire via [fromWire].
 */
enum class OrderStatus {
    RECEIVED,
    SENT_TO_KITCHEN,
    PREPARING,
    READY,
    COMPLETED,
    CANCELLED,
    UNKNOWN;

    /** A terminal state has no further transitions. */
    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELLED

    companion object {
        /** Map a wire/stored status string to the enum; unrecognized values become [UNKNOWN]. */
        fun fromWire(value: String?): OrderStatus =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}
