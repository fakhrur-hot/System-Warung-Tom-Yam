package com.razstudio.pos.data.local

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
    val nextTableNumber: Int = 1,

    // ── Added in v13 for LAN/Kiosk (task 4.4) ────────────────────────────────────────────────────
    // These eight settings previously existed ONLY in Supabase: the app wrote them with
    // `putSettings` and re-read them with `getSettings`, and Room cached just the handful above.
    // That is fine while there is a cloud to hold them, but off-cloud `LocalBackend` has to be the
    // authority, and it cannot answer `getSettings` for a value it has nowhere to keep. Reports in
    // particular ask for `businessDayStartHour` on every load.
    //
    // Defaults deliberately match `SettingsResponse`'s, so a café that has never opened the settings
    // screen behaves identically on either backend.
    val customerOrderHoldSeconds: Int = 15,
    val customerOrderAutoPrint: Boolean = true,
    val todaysSpecial: String = "",
    val reportEmail: String = "",
    /** Hour (0–23) a trading day starts; sales after midnight count toward the opening day. */
    val businessDayStartHour: Int = 15,
    /**
     * Hour the trading day ends (0–23). May be EARLIER than [businessDayStartHour] — a stall
     * open 15:00–02:00 is the case this pair exists for, so the window is read as wrapping
     * midnight rather than as invalid.
     */
    val businessDayEndHour: Int = 2,
    /** Café-wide default UI language per surface; a device applies it only if it has no own choice. */
    val defaultLangAdmin: String = "BM",
    val defaultLangOrdering: String = "BM",
    val defaultLangCustomer: String = "BM",
)
