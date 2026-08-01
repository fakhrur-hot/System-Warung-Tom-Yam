package com.razstudio.pos.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database migrations, extracted from DatabaseModule for test access.
 *
 * These migrations are used in both DatabaseModule (for the production database)
 * and in migration tests (to verify data durability across app upgrades, Requirement 8.1, 12.6).
 */

/**
 * v8 -> v9: add MenuItem.code and MenuItem.marketPrice columns for the dynamic-menu revamp.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE menu_items ADD COLUMN code TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE menu_items ADD COLUMN marketPrice INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v9 -> v10: add MenuItem.extraCategories so an item can appear under multiple category
 * pages (primary [category] + comma-separated extras).
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE menu_items ADD COLUMN extraCategories TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v10 -> v11: add the order_number_sequences table for Kiosk Mode running order numbers.
 * One row per business day; new days start automatically with a fresh counter.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS order_number_sequences (
                businessDay TEXT NOT NULL PRIMARY KEY,
                nextNumber  INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

/**
 * v11 -> v12: add the paired_devices table for LAN Mode Client Device registry.
 * Each row records a Client Device that has completed pairing with this Server Device,
 * together with its role, status, credential hash, and last-seen timestamp (Requirement 5.3, 5.6).
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS paired_devices (
                id             TEXT NOT NULL PRIMARY KEY,
                name           TEXT NOT NULL,
                model          TEXT NOT NULL,
                role           TEXT NOT NULL,
                status         TEXT NOT NULL,
                credentialHash TEXT NOT NULL,
                lastSeenMs     INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

/**
 * v12 -> v13: give LAN and Kiosk Mode somewhere to keep what previously only existed in Supabase
 * (tasks 4.4, 4.5).
 *
 * Three additions, all purely additive — no table is rewritten and no existing column changes type,
 * so an upgrading café keeps every order, table and takings row it had:
 *
 *  - `cafe_sessions`   — the append-only open/close log. Off-cloud this is the only record of when
 *                        the café traded, which the closing report is derived from.
 *  - `daily_aggregates`— one row per business day holding the closing aggregate JSON verbatim.
 *  - eight `system_settings` columns that the app used to round-trip through the `settings` Edge
 *    Function. `LocalBackend.getSettings` cannot answer for a value it has nowhere to store, and
 *    `ReportsViewModel` asks for `businessDayStartHour` on every report load.
 *
 * Every added column is NOT NULL with a DEFAULT matching the Kotlin default and `SettingsResponse`'s
 * default, so the existing single settings row (id = 1) is filled in place rather than needing a
 * backfill pass — and a café that has never opened the settings screen reads identically on either
 * backend.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cafe_sessions (
                id        TEXT NOT NULL PRIMARY KEY,
                event     TEXT NOT NULL,
                reason    TEXT,
                closing   INTEGER NOT NULL DEFAULT 0,
                timestamp TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_aggregates (
                date        TEXT NOT NULL PRIMARY KEY,
                payloadJson TEXT NOT NULL,
                updatedAt   TEXT NOT NULL
            )
            """.trimIndent()
        )

        // ALTER TABLE ADD COLUMN is metadata-only in SQLite when a non-null DEFAULT is supplied, so
        // this stays fast on a café database with a full trading history behind it.
        db.execSQL("ALTER TABLE system_settings ADD COLUMN customerOrderHoldSeconds INTEGER NOT NULL DEFAULT 15")
        db.execSQL("ALTER TABLE system_settings ADD COLUMN customerOrderAutoPrint INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE system_settings ADD COLUMN todaysSpecial TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE system_settings ADD COLUMN reportEmail TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE system_settings ADD COLUMN businessDayStartHour INTEGER NOT NULL DEFAULT 15")
        db.execSQL("ALTER TABLE system_settings ADD COLUMN defaultLangAdmin TEXT NOT NULL DEFAULT 'BM'")
        db.execSQL("ALTER TABLE system_settings ADD COLUMN defaultLangOrdering TEXT NOT NULL DEFAULT 'BM'")
        db.execSQL("ALTER TABLE system_settings ADD COLUMN defaultLangCustomer TEXT NOT NULL DEFAULT 'BM'")
    }
}
