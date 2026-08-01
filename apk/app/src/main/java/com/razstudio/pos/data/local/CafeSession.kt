package com.razstudio.pos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One café open/close event (task 4.5, Requirement 3.2).
 *
 * In Cloud Mode these rows live in Supabase and the app only posts to them. Off-cloud there is no
 * server, so the Server Device's own database is where the trading day is recorded — and that makes
 * it the only evidence of when the café opened and closed, which the daily aggregate and the closing
 * report are both derived from.
 *
 * Rows are append-only. A CLOSE does not update the matching OPEN: keeping both events means the
 * sequence can be replayed, and a day that was closed twice (or never closed because the tablet died)
 * stays visible instead of being smoothed away by an overwrite.
 */
@Entity(tableName = "cafe_sessions")
data class CafeSession(
    @PrimaryKey val id: String,
    /** `OPEN` or `CLOSE` — the wire vocabulary the Cloud `sessions` endpoint uses. */
    val event: String,
    /** Free text supplied when closing; null for an open. */
    val reason: String? = null,
    /** True when this CLOSE was an end-of-day close-out rather than an incidental one. */
    val closing: Boolean = false,
    /** Fixed-width ISO-8601 UTC, written by `LocalBackend.nowTimestamp()`. */
    val timestamp: String,
)
