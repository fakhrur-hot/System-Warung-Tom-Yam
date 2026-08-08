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
/**
 * v13 -> v14: LAN Mode device pairing (Requirements 5.1, 5.3, 5.4).
 *
 * Adds `pairing_tokens` — single-use, time-limited codes a Client Device presents to register — and
 * one nullable column on `paired_devices` holding the raw credential until the client collects it.
 *
 * `pendingCredential` is nullable with no default precisely because it should be absent for every
 * existing row: devices paired before this migration already hold their credential, and inventing
 * one for them would be issuing a second, unknown secret against a device that is working.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pairing_tokens (
                token        TEXT NOT NULL PRIMARY KEY,
                expiresAtMs  INTEGER NOT NULL,
                usedAtMs     INTEGER,
                createdAtMs  INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("ALTER TABLE paired_devices ADD COLUMN pendingCredential TEXT")
    }
}

/**
 * v15 — an order can exist without a table, and can carry a running number (Kiosk Mode).
 *
 * Kiosk is a grocery-style till: ring up, take payment, print, next customer. It has no tables, and
 * a sale is identified by a number that resets each business day. `OrderNumberSequence` was added in
 * v11 for exactly this and has sat unused ever since, because `orders` had nowhere to put the number
 * and `tableId` was NOT NULL — so a Kiosk sale could not be stored at all.
 *
 * ### Why the rebuild is safe
 *
 * SQLite cannot drop a NOT NULL constraint in place, so the table is recreated and copied. Two things
 * make that the safe direction rather than a risk:
 *
 *  - It **widens**. Every existing row already has a `tableId`, so all of them satisfy the new,
 *    looser column. Nothing can fail validation and nothing is dropped.
 *  - The two shapes never interleave. A device runs exactly one mode, so `tableId IS NULL` and
 *    `orderNumber IS NOT NULL` only ever appear on a Kiosk device, whose table starts empty. A
 *    table-service café's rows keep their old shape untouched.
 *
 * `orders` is the café's only copy of its takings in LAN and Kiosk Mode (Requirement 8), so the copy
 * is explicit, column by column, rather than a `SELECT *` that would silently reorder if the entity
 * changes again.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS orders_new (
                id               TEXT NOT NULL PRIMARY KEY,
                tableId          TEXT,
                orderNumber      INTEGER,
                source           TEXT NOT NULL,
                status           TEXT NOT NULL,
                paymentMethod    TEXT,
                total            REAL NOT NULL,
                sentToKitchenAt  TEXT,
                cancelReason     TEXT,
                cancelledBy      TEXT,
                createdAt        TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO orders_new (
                id, tableId, orderNumber, source, status, paymentMethod,
                total, sentToKitchenAt, cancelReason, cancelledBy, createdAt
            )
            SELECT
                id, tableId, NULL, source, status, paymentMethod,
                total, sentToKitchenAt, cancelReason, cancelledBy, createdAt
            FROM orders
            """.trimIndent()
        )
        db.execSQL("DROP TABLE orders")
        db.execSQL("ALTER TABLE orders_new RENAME TO orders")
    }
}

/**
 * v15 → v16: add transport discriminator and drawer-kick field to `printer_configs`.
 *
 * Three changes:
 *  - `macAddress` (NOT NULL TEXT) renamed to `address` (TEXT, nullable — future non-BT drivers
 *    have no MAC). Existing BT rows keep their MAC value in `address`.
 *  - `transport TEXT NOT NULL DEFAULT 'BLUETOOTH'` — every existing row defaults to Bluetooth;
 *    no café loses its printer setup.
 *  - `drawerKick TEXT NOT NULL DEFAULT 'NONE'` — existing rows default to no drawer.
 *
 * SQLite supports `RENAME COLUMN` only from 3.25.0 (Android API 28), but this project's
 * minSdk = 26.  We therefore use the safe recreate-and-copy pattern, copying columns
 * explicitly so a future entity change cannot silently reorder them.
 *
 * Non-destructive: no row is deleted or rewritten other than gaining the two new columns.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Create the new table with the target schema.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS printer_configs_new (
                id             TEXT NOT NULL PRIMARY KEY,
                name           TEXT NOT NULL,
                address        TEXT,
                transport      TEXT NOT NULL DEFAULT 'BLUETOOTH',
                drawerKick     TEXT NOT NULL DEFAULT 'NONE',
                paperWidth     TEXT NOT NULL,
                printerRole    TEXT NOT NULL,
                isActive       INTEGER NOT NULL,
                categoryFilter TEXT
            )
            """.trimIndent()
        )
        // 2. Copy all existing rows; macAddress → address, new columns get their defaults.
        db.execSQL(
            """
            INSERT INTO printer_configs_new
                (id, name, address, transport, drawerKick, paperWidth, printerRole, isActive, categoryFilter)
            SELECT
                id, name, macAddress, 'BLUETOOTH', 'NONE', paperWidth, printerRole, isActive, categoryFilter
            FROM printer_configs
            """.trimIndent()
        )
        // 3. Swap.
        db.execSQL("DROP TABLE printer_configs")
        db.execSQL("ALTER TABLE printer_configs_new RENAME TO printer_configs")
    }
}


/**
 * v16 → v17 — the `payment_transactions` table. (PG-REQ-5, task 5.1)
 *
 * Purely additive: one new table, nothing existing is touched. In particular **`orders` is not
 * altered** — `paymentMethod` already exists on it and `OrderStatus` already carries whether a bill
 * is settled, so the original plan's `payment_method` and `payment_status` columns would have been
 * a duplicate and a second source of truth respectively. (A4, A5)
 *
 * Column types mirror how Room stores the entity: enums as TEXT, the boolean as INTEGER, money as
 * INTEGER sen. The unique index on `idempotencyKey` is what makes a double-charge a database error
 * rather than a silent second payment — the constraint belongs here, not only in the client. (A6)
 */
/**
 * Adds the business-day END hour beside the existing start hour.
 *
 * Defaulted to 2 (2 AM) to stay consistent with the start default of 15 (3 PM): a café that has
 * never opened the setting still describes a coherent late-night trading window rather than a
 * zero-length day that would make "is the café open?" answer false forever.
 */
/**
 * Adds the cash-drawer ledger (version 19). Append-only rows; money as INTEGER sen, the same
 * storage the payment_transactions table already established.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cash_drawer_events (
                id              INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                type            TEXT    NOT NULL,
                amountSen       INTEGER NOT NULL,
                balanceAfterSen INTEGER NOT NULL,
                orderId         TEXT,
                tenderedSen     INTEGER,
                changeSen       INTEGER,
                usedDefaultPin  INTEGER NOT NULL DEFAULT 0,
                timestamp       TEXT    NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_cash_drawer_events_timestamp " +
                "ON cash_drawer_events (timestamp)"
        )
    }
}

val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE system_settings ADD COLUMN businessDayEndHour INTEGER NOT NULL DEFAULT 2"
        )
    }
}

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS payment_transactions (
                id                   TEXT    NOT NULL PRIMARY KEY,
                orderId              TEXT    NOT NULL,
                paymentMethod        TEXT    NOT NULL,
                amountSen            INTEGER NOT NULL,
                status               TEXT    NOT NULL,
                gatewayTransactionId TEXT,
                gatewayResponseJson  TEXT,
                idempotencyKey       TEXT    NOT NULL,
                isSandbox            INTEGER NOT NULL DEFAULT 0,
                createdAt            TEXT    NOT NULL,
                settledAt            TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_payment_transactions_orderId " +
                "ON payment_transactions (orderId)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_payment_transactions_idempotencyKey " +
                "ON payment_transactions (idempotencyKey)"
        )
    }
}

/**
 * v19 → v20: add `captured_payments` table for the Payment Notification Listener feature.
 *
 * Purely additive — one new table with three indices. Stores payment notifications captured
 * from eWallet/bank apps so the service can auto-match them to pending orders by amount.
 * Money is INTEGER sen, same convention as payment_transactions and cash_drawer_events.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS captured_payments (
                id TEXT NOT NULL PRIMARY KEY,
                amountSen INTEGER NOT NULL,
                walletApp TEXT NOT NULL,
                packageName TEXT NOT NULL,
                sender TEXT,
                reference TEXT,
                rawTitle TEXT NOT NULL,
                rawText TEXT NOT NULL,
                matchStatus TEXT NOT NULL,
                matchedOrderId TEXT,
                capturedAt TEXT NOT NULL,
                matchedAt TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_captured_payments_capturedAt " +
                "ON captured_payments (capturedAt)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_captured_payments_matchStatus " +
                "ON captured_payments (matchStatus)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_captured_payments_matchedOrderId " +
                "ON captured_payments (matchedOrderId)"
        )
    }
}

/**
 * v20 → v21: add affiliate product cache and campaign tables for the Shopee Affiliate Ads feature.
 *
 * Purely additive — two new tables. The APK fetches product offers from the Shopee Affiliate API,
 * caches them locally in Room, and serves them to the table grid / ambient display UI.
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS affiliate_products (
                id TEXT NOT NULL PRIMARY KEY,
                itemId INTEGER NOT NULL,
                productName TEXT NOT NULL,
                offerLink TEXT NOT NULL,
                imageUrl TEXT NOT NULL,
                price INTEGER NOT NULL,
                originalPrice INTEGER NOT NULL,
                commissionRate REAL NOT NULL,
                commissionXtra REAL,
                shopName TEXT NOT NULL,
                isOfficialShop INTEGER NOT NULL,
                salesCount INTEGER NOT NULL,
                rating REAL NOT NULL,
                subId TEXT NOT NULL,
                validationStatus TEXT NOT NULL,
                lastFetchedAt TEXT NOT NULL,
                impressions INTEGER NOT NULL DEFAULT 0,
                clicks INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS affiliate_campaigns (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                xtraRate REAL NOT NULL,
                startsAt TEXT NOT NULL,
                expiresAt TEXT NOT NULL,
                productCount INTEGER NOT NULL,
                lastSyncedAt TEXT NOT NULL
            )
            """.trimIndent()
        )
    }
}

/**
 * v21 → v22: tag each cached affiliate product row with which source produced it
 * (`SHOPEE_API` or `GITHUB_FALLBACK`).
 *
 * Purely additive — one new column with a default. Without this tag, rows from the Shopee API
 * (keyed by `itemId`) and rows from the GitHub-catalog fallback (keyed by `github_$index`) live in
 * disjoint id spaces, so switching between the two sources never replaced the abandoned source's
 * rows and both accumulated forever. `AffiliateRepository` now deletes the other source's rows on
 * every successful sync (bugfix Requirement 5). Existing v21 rows all came from the fallback path
 * (the Shopee API path could never authenticate before this bugfix — see bugfix/design.md Bug 1),
 * so `'GITHUB_FALLBACK'` is the correct default for anything already on disk.
 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE affiliate_products ADD COLUMN source TEXT NOT NULL DEFAULT 'GITHUB_FALLBACK'"
        )
    }
}
