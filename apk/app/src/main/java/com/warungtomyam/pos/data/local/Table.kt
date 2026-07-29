package com.warungtomyam.pos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a physical table in the café.
 * Tables are managed locally by the admin and used to assign orders.
 */
@Entity(tableName = "tables")
data class Table(
    @PrimaryKey val id: String,   // e.g., "T1", "T2"
    val label: String,            // display name
    val sortOrder: Int = 0
)
