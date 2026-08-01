package com.razstudio.pos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One business day's closing aggregate (task 4.5, Requirement 3.3).
 *
 * The payload is stored as the JSON the app already builds for the Cloud `aggregates` endpoint,
 * rather than being exploded into columns. That is deliberate: the aggregate's shape is decided by
 * `AdminSessionViewModel` and has changed as reports gained fields, so pinning it into a schema would
 * mean a Room migration every time a report learns a new number. Here the row is a record of what was
 * sent, and the reports themselves are always recomputed from `orders` — so nothing reads these
 * columns to produce a figure.
 *
 * Keyed by business day (the opening day, so post-midnight trade belongs to the day it started), and
 * upserted: closing the same day twice replaces the aggregate rather than duplicating it.
 */
@Entity(tableName = "daily_aggregates")
data class DailyAggregate(
    /** Business day as `yyyy-MM-dd`. */
    @PrimaryKey val date: String,
    /** Verbatim aggregate JSON, as posted to the Cloud endpoint. */
    val payloadJson: String,
    /** When this row was last written — fixed-width ISO-8601 UTC. */
    val updatedAt: String,
)
