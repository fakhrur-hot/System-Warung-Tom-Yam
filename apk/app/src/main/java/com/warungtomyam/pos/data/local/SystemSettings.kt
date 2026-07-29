package com.warungtomyam.pos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for system settings.
 * Uses a singleton pattern (id = 1) — only one row exists.
 */
@Entity(tableName = "system_settings")
data class SystemSettings(
    @PrimaryKey val id: Int = 1,
    val printLanguage: String = "EN",
    val timezone: String = "Asia/Kuala_Lumpur",
    val topN: Int = 5,
    val staffCanSendKitchen: Boolean = false,
    val staffCanTakePayment: Boolean = false,
    // Auto-generated table IDs are T0001..T9999, always incrementing — this is the next
    // number to assign. Never decremented on delete, so numbering never reuses a gap.
    val nextTableNumber: Int = 1
)
