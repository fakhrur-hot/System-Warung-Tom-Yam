package com.razstudio.pos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a physical table in the café.
 * Tables are managed locally by the admin and used to assign orders.
 */
@Entity(tableName = "tables")
data class Table(
    @PrimaryKey val id: String,   // dine-in: "T0001".."T9999"; take-out: "TAPAW1".."TAPAW6"
    val label: String,            // display name
    val sortOrder: Int = 0
)

/** Reserved id prefix for take-out ("Tapaw") slots — no table number, excluded from QR cards. */
const val TAKEOUT_PREFIX = "TAPAW"

/**
 * A take-out ("Tapaw") slot rather than a physical dine-in table. Take-out slots are used for
 * order-taking like any table but have no printed QR card and don't count toward the dine-in cap.
 */
val Table.isTakeout: Boolean get() = id.startsWith(TAKEOUT_PREFIX)
