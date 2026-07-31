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
}
