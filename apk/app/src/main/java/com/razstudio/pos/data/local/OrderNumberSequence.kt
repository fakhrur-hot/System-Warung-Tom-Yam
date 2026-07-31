package com.razstudio.pos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity that tracks the next available running order number for a given business day.
 *
 * The primary key is the [businessDay] string (e.g. "2025-01-15"), derived from the existing
 * [businessDayStartHour] setting. Using the date as the key means a new day automatically starts
 * a fresh counter — no explicit reset is required. A new row is inserted with [nextNumber] = 1
 * the first time any order is placed on a given day.
 *
 * Used exclusively in Kiosk Mode where there are no tables: each order receives a unique,
 * monotonic running number within the business day (Requirement 3.5).
 *
 * Atomicity of the read-and-increment is enforced by [OrderNumberSequenceDao.getNextOrderNumber],
 * which runs inside a Room [androidx.room.Transaction] and uses a mutex, so concurrent coroutine
 * access on a single device cannot duplicate a number.
 */
@Entity(tableName = "order_number_sequences")
data class OrderNumberSequence(
    /** Business day string, e.g. "2025-01-15". Primary key — one row per day. */
    @PrimaryKey val businessDay: String,
    /** The next number to be issued (1-based). Incremented atomically on each call. */
    val nextNumber: Int
)
