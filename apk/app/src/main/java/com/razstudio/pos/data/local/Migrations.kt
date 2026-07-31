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
