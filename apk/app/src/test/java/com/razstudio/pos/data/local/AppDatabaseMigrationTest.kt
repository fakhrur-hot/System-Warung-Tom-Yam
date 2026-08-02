package com.razstudio.pos.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 1.5 — migration test over a **populated** database (Property 4, Requirements 8.1 / 12.6).
 *
 * This exists because of a specific hazard the operating-modes spec introduces. In Cloud Mode Room is
 * a cache and losing it is survivable, so `fallbackToDestructiveMigration` was harmless. In LAN and
 * Kiosk Mode Room becomes the café's *only* copy of its orders and takings, the destructive fallback
 * has been removed (`DatabaseModule`), and a broken migration now means a café loses its records with
 * no cloud copy to restore from.
 *
 * The important detail is that the database is **populated before** migrating. A migration exercised
 * against an empty database proves only that the DDL parses — it cannot demonstrate that existing rows
 * survive, which is the entire property being protected.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {

    private companion object {
        const val DB_NAME = "migration-test.db"

        // Fixed fixtures; asserted verbatim after migrating so a silently-altered value fails too,
        // not merely a dropped row.
        const val ORDER_ID = "ord-1001"
        const val ORDER_TOTAL = 27.50
        const val BUSINESS_DAY = "2026-07-31"
        const val NEXT_NUMBER = 42
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate11To12_preservesEveryExistingRow() {
        // ── v11: a database with real content in it ──────────────────────────────────────────────
        helper.createDatabase(DB_NAME, 11).use { db ->
            db.execSQL(
                """
                INSERT INTO orders
                    (id, tableId, source, status, paymentMethod, total,
                     sentToKitchenAt, cancelReason, cancelledBy, createdAt)
                VALUES
                    ('$ORDER_ID', 'table-7', 'STAFF', 'SENT_TO_KITCHEN', NULL, $ORDER_TOTAL,
                     '2026-07-31T12:00:00Z', NULL, NULL, '2026-07-31T11:58:00Z')
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO order_items
                    (id, orderId, menuItemId, nameSnapshot, unitPriceSnapshot,
                     categorySnapshot, quantity, note, sentToKitchen, sessionNumber)
                VALUES
                    ('itm-1', '$ORDER_ID', 'ttym-a01', 'Spicy Chicken', 8.00, 'FOOD', 2, NULL, 1, 1),
                    ('itm-2', '$ORDER_ID', 'ttym-b02', 'Kerabu Mangga',  11.50, 'FOOD', 1, 'less chilli', 1, 2)
                """.trimIndent()
            )
            // The Kiosk running-order-number counter (added in v10->v11) must also survive.
            db.execSQL(
                "INSERT INTO order_number_sequences (businessDay, nextNumber) " +
                    "VALUES ('$BUSINESS_DAY', $NEXT_NUMBER)"
            )
        }

        // ── migrate to v12; validateDroppedTables = true so a migration that quietly leaves the
        //    schema disagreeing with 12.json fails here rather than in production ────────────────
        val db = helper.runMigrationsAndValidate(DB_NAME, 12, true, MIGRATION_11_12)

        // ── the order survived, with its values intact ───────────────────────────────────────────
        db.query("SELECT tableId, status, total FROM orders WHERE id = '$ORDER_ID'").use { c ->
            assertTrue("order row was lost by the migration", c.moveToFirst())
            assertEquals("table-7", c.getString(0))
            assertEquals("SENT_TO_KITCHEN", c.getString(1))
            assertEquals(ORDER_TOTAL, c.getDouble(2), 0.001)
            assertEquals("migration duplicated the order row", 1, c.count)
        }

        // ── both line items survived, including the one carrying a note and a sessionNumber ──────
        db.query(
            "SELECT nameSnapshot, quantity, note, sessionNumber FROM order_items " +
                "WHERE orderId = '$ORDER_ID' ORDER BY id"
        ).use { c ->
            assertEquals("expected both order items to survive", 2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("Spicy Chicken", c.getString(0))
            assertEquals(2, c.getInt(1))
            assertTrue("note should still be null", c.isNull(2))
            assertEquals(1, c.getInt(3))
            assertTrue(c.moveToNext())
            assertEquals("Kerabu Mangga", c.getString(0))
            assertEquals("less chilli", c.getString(2))
            assertEquals(2, c.getInt(3))
        }

        // ── the order-number counter survived, so Kiosk numbering does not restart after upgrade ─
        db.query(
            "SELECT nextNumber FROM order_number_sequences WHERE businessDay = '$BUSINESS_DAY'"
        ).use { c ->
            assertTrue("order-number sequence row was lost", c.moveToFirst())
            assertEquals(NEXT_NUMBER, c.getInt(0))
        }

        // ── and v12's new table exists, empty ────────────────────────────────────────────────────
        db.query("SELECT count(*) FROM paired_devices").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("paired_devices should be created empty", 0, c.getInt(0))
        }
    }

    @Test
    fun migrate12To13_addsSessionsAggregatesAndSettingsColumnsWithoutTouchingExistingRows() {
        // ── v12: a café mid-life — a settings row it has customised, and takings on the books ────
        helper.createDatabase(DB_NAME, 12).use { db ->
            db.execSQL(
                """
                INSERT INTO system_settings
                    (id, printLanguage, timezone, topN, staffCanSendKitchen,
                     staffCanTakePayment, nextTableNumber)
                VALUES (1, 'ZH', 'Asia/Kuala_Lumpur', 7, 1, 0, 23)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO orders
                    (id, tableId, source, status, paymentMethod, total,
                     sentToKitchenAt, cancelReason, cancelledBy, createdAt)
                VALUES
                    ('$ORDER_ID', 'table-3', 'STAFF', 'COMPLETED', 'CASH', $ORDER_TOTAL,
                     '2026-07-31T12:00:00Z', NULL, NULL, '2026-07-31T11:58:00Z')
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 13, true, MIGRATION_12_13)

        // ── the customised settings row survived UNCHANGED ───────────────────────────────────────
        // The migration adds eight columns to this table; the whole risk is that it recreates the
        // row instead of altering it and quietly resets the café's configuration.
        db.query(
            "SELECT printLanguage, timezone, topN, staffCanSendKitchen, nextTableNumber " +
                "FROM system_settings WHERE id = 1"
        ).use { c ->
            assertTrue("the settings row was lost by the migration", c.moveToFirst())
            assertEquals("ZH", c.getString(0))
            assertEquals("Asia/Kuala_Lumpur", c.getString(1))
            assertEquals(7, c.getInt(2))
            assertEquals(1, c.getInt(3))
            assertEquals(23, c.getInt(4))
            assertEquals("migration duplicated the settings row", 1, c.count)
        }

        // ── and the new columns arrived pre-filled with the documented defaults ───────────────────
        // Not merely present: an existing row must come out matching SettingsResponse's defaults, or
        // an upgraded café silently changes behaviour — businessDayStartHour in particular decides
        // which trading day every report covers.
        db.query(
            "SELECT customerOrderHoldSeconds, customerOrderAutoPrint, todaysSpecial, reportEmail, " +
                "businessDayStartHour, defaultLangAdmin, defaultLangOrdering, defaultLangCustomer " +
                "FROM system_settings WHERE id = 1"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(15, c.getInt(0))
            assertEquals(1, c.getInt(1))
            assertEquals("", c.getString(2))
            assertEquals("", c.getString(3))
            assertEquals(15, c.getInt(4))
            assertEquals("BM", c.getString(5))
            assertEquals("BM", c.getString(6))
            assertEquals("BM", c.getString(7))
        }

        // ── the completed order is untouched, so the day's takings still reconcile ───────────────
        db.query("SELECT total, paymentMethod FROM orders WHERE id = '$ORDER_ID'").use { c ->
            assertTrue("order row was lost by the migration", c.moveToFirst())
            assertEquals(ORDER_TOTAL, c.getDouble(0), 0.001)
            assertEquals("CASH", c.getString(1))
        }

        // ── both new tables exist and are empty ──────────────────────────────────────────────────
        db.query("SELECT count(*) FROM cafe_sessions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("cafe_sessions should be created empty", 0, c.getInt(0))
        }
        db.query("SELECT count(*) FROM daily_aggregates").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("daily_aggregates should be created empty", 0, c.getInt(0))
        }
    }

    @Test
    fun migrate13To14_addsPairingTablesAndLeavesPairedDevicesUsable() {
        // ── v13: a café with a staff phone already paired and working ────────────────────────────
        helper.createDatabase(DB_NAME, 13).use { db ->
            db.execSQL(
                """
                INSERT INTO paired_devices
                    (id, name, model, role, status, credentialHash, lastSeenMs)
                VALUES
                    ('dev-1', 'Counter phone', 'Pixel 6a', 'ORDERING', 'APPROVED', 'abc123', 1754000000000)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 14, true, MIGRATION_13_14)

        // ── the working device survived, still APPROVED and still holding its credential hash ────
        db.query(
            "SELECT name, role, status, credentialHash, pendingCredential FROM paired_devices WHERE id = 'dev-1'"
        ).use { c ->
            assertTrue("the paired device was lost by the migration", c.moveToFirst())
            assertEquals("Counter phone", c.getString(0))
            assertEquals("ORDERING", c.getString(1))
            assertEquals("APPROVED", c.getString(2))
            assertEquals("abc123", c.getString(3))
            // Must be NULL, not an invented value: this device already holds its credential, and
            // materialising a second unknown secret for it would be issuing a credential nobody asked
            // for against a phone that is working.
            assertTrue("an already-paired device must not gain a pending credential", c.isNull(4))
        }

        db.query("SELECT count(*) FROM pairing_tokens").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("pairing_tokens should be created empty", 0, c.getInt(0))
        }
    }

    /**
     * v14 → v15 rebuilds `orders` so a Kiosk sale can exist without a table.
     *
     * This is the highest-stakes migration in the app: in LAN and Kiosk Mode `orders` holds the
     * café's **only** copy of its takings (Requirement 8), and SQLite cannot drop a NOT NULL
     * constraint in place, so the table is recreated and copied rather than altered. A column
     * mis-ordered in that copy would silently shuffle every order's data.
     *
     * The direction is safe — it widens, so every existing row already satisfies the looser
     * constraint — but "safe in principle" is not evidence. This populates a real café's day and
     * checks the money survives it.
     */
    @Test
    fun migrate14To15_makesTableOptionalWithoutLosingADaysTakings() {
        helper.createDatabase(DB_NAME, 14).use { db ->
            db.execSQL(
                """
                INSERT INTO orders (id, tableId, source, status, paymentMethod, total,
                                    sentToKitchenAt, cancelReason, cancelledBy, createdAt)
                VALUES ('o-1', 'T5', 'QR', 'COMPLETED', 'CASH', 42.50,
                        '2026-08-02T10:00:00Z', NULL, NULL, '2026-08-02T09:55:00Z')
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO orders (id, tableId, source, status, paymentMethod, total,
                                    sentToKitchenAt, cancelReason, cancelledBy, createdAt)
                VALUES ('o-2', 'T9', 'STAFF', 'CANCELLED', NULL, 8.00,
                        NULL, 'customer left', 'admin-1', '2026-08-02T11:30:00Z')
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 15, true, MIGRATION_14_15)

        // Every column, in order, on a paid order — this is the shuffle a hand-written copy risks.
        db.query(
            "SELECT tableId, orderNumber, source, status, paymentMethod, total, " +
                "sentToKitchenAt, cancelReason, cancelledBy, createdAt FROM orders WHERE id = 'o-1'"
        ).use { c ->
            assertTrue("the completed order must survive", c.moveToFirst())
            assertEquals("T5", c.getString(0))
            assertTrue("an existing order carries no running number", c.isNull(1))
            assertEquals("QR", c.getString(2))
            assertEquals("COMPLETED", c.getString(3))
            assertEquals("CASH", c.getString(4))
            assertEquals(42.50, c.getDouble(5), 0.001)
            assertEquals("2026-08-02T10:00:00Z", c.getString(6))
            assertTrue(c.isNull(7))
            assertTrue(c.isNull(8))
            assertEquals("2026-08-02T09:55:00Z", c.getString(9))
        }

        // Nullable columns that were already null must not have been back-filled with junk.
        db.query("SELECT cancelReason, cancelledBy, paymentMethod FROM orders WHERE id = 'o-2'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("customer left", c.getString(0))
            assertEquals("admin-1", c.getString(1))
            assertTrue("an unpaid order must stay unpaid", c.isNull(2))
        }

        db.query("SELECT COUNT(*) FROM orders").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("no order may be lost in the rebuild", 2, c.getInt(0))
        }

        // And the point of the whole migration: a tableless sale is now storable.
        db.execSQL(
            """
            INSERT INTO orders (id, tableId, orderNumber, source, status, paymentMethod, total,
                                sentToKitchenAt, cancelReason, cancelledBy, createdAt)
            VALUES ('k-1', NULL, 7, 'STAFF', 'COMPLETED', 'CASH', 12.00,
                    NULL, NULL, NULL, '2026-08-02T12:00:00Z')
            """.trimIndent()
        )
        db.query("SELECT orderNumber FROM orders WHERE id = 'k-1'").use { c ->
            assertTrue("a Kiosk sale must be storable with no table", c.moveToFirst())
            assertEquals(7, c.getInt(0))
        }
    }
}
