package com.razstudio.pos.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Task 4.1 — [OrderDao.getOrdersSince], the `?since=` catch-up poll that replaces the Supabase
 * Realtime WebSocket in LAN and Kiosk Mode (Validates Requirements 3.1, 4.1).
 *
 * This exists because of a defect found while completing 4.1, and the defect is worth stating
 * plainly: the query compares `createdAt` as **text**. That is only equivalent to comparing instants
 * while every stored timestamp is the same width, and `Instant.toString()` — the obvious thing to
 * write, and what the first draft used — is not. It omits the fractional part when it happens to be
 * zero and drops trailing zero groups otherwise. Because `'.'` (0x2E) sorts below `'Z'` (0x5A),
 * `…:05.5Z` compares as LESS than `…:05Z`, so an order placed half a second later reads as older —
 * and with `createdAt > since` it is skipped and never delivered to the kitchen.
 *
 * There is no alert for that. The order simply never arrives, and the poll response looks identical
 * whether or not it swallowed one. So these tests pin the format's sortability directly, rather than
 * trusting the query in isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OrdersSinceQueryTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: OrderDao

    /** Must stay identical to LocalBackend.TIMESTAMP_FORMAT — that is what these tests are about. */
    private val fixedWidth: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.orderDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(id: String, createdAt: String, table: String = "T1") {
        dao.insertOrder(
            Order(
                id = id,
                tableId = table,
                source = "STAFF",
                status = OrderStatus.RECEIVED,
                total = 10.0,
                createdAt = createdAt,
            )
        )
    }

    // ── The query's contract ──────────────────────────────────────────────────────────────────────

    @Test
    fun returnsOnlyOrdersStrictlyAfterTheGivenTimestamp() = runTest {
        val base = Instant.parse("2026-08-01T10:00:00Z")
        insert("a", fixedWidth.format(base))
        insert("b", fixedWidth.format(base.plusSeconds(1)))
        insert("c", fixedWidth.format(base.plusSeconds(2)))

        val since = fixedWidth.format(base)
        val got = dao.getOrdersSince(since).map { it.id }

        assertEquals(
            "strictly greater — the boundary order was already delivered in the previous poll",
            listOf("b", "c"), got,
        )
    }

    @Test
    fun returnsOrdersOldestFirstSoTheKitchenSeesThemInPlacementOrder() = runTest {
        val base = Instant.parse("2026-08-01T10:00:00Z")
        insert("third", fixedWidth.format(base.plusSeconds(30)))
        insert("first", fixedWidth.format(base.plusSeconds(10)))
        insert("second", fixedWidth.format(base.plusSeconds(20)))

        assertEquals(
            listOf("first", "second", "third"),
            dao.getOrdersSince(fixedWidth.format(base)).map { it.id },
        )
    }

    @Test
    fun anEmptyWindowReturnsNothingRatherThanEverything() = runTest {
        // A poller that received back the whole table every tick would reprint every order in the
        // café, so "no new orders" has to mean an empty list.
        val base = Instant.parse("2026-08-01T10:00:00Z")
        insert("a", fixedWidth.format(base))
        assertTrue(dao.getOrdersSince(fixedWidth.format(base.plusSeconds(5))).isEmpty())
    }

    // ── The format property the query depends on ──────────────────────────────────────────────────

    @Test
    fun subSecondOrdersAreNotSkipped_theRegressionThisFileExistsFor() = runTest {
        // The exact shape of the bug: two orders in the same second, one on the second boundary and
        // one a fraction later. Under Instant.toString() these render as "…:05Z" and "…:05.500Z", and
        // the text comparison puts them in the wrong order — so polling from the earlier one misses
        // the later one entirely.
        val onSecond = Instant.parse("2026-08-01T10:00:05Z")
        val halfLater = onSecond.plusMillis(500)

        insert("on_second", fixedWidth.format(onSecond))
        insert("half_later", fixedWidth.format(halfLater))

        val got = dao.getOrdersSince(fixedWidth.format(onSecond)).map { it.id }
        assertEquals(
            "the order placed 500ms later must still be delivered",
            listOf("half_later"), got,
        )
    }

    @Test
    fun theFixedWidthFormatSortsAsTextExactlyAsItSortsInTime() {
        // Guards the formatter itself, independent of Room: if this ever regresses to a variable-width
        // format the query silently starts dropping orders, and only this test would say so.
        val base = Instant.parse("2026-08-01T10:00:05Z")
        val instants = listOf(
            base,
            base.plusNanos(1_000),
            base.plusMillis(1),
            base.plusMillis(500),
            base.plusSeconds(1),
            base.plusSeconds(60),
        )

        val rendered = instants.map { fixedWidth.format(it) }
        assertEquals(
            "text order must match chronological order",
            rendered, rendered.sorted(),
        )
        assertEquals(
            "every timestamp must be the same length, which is what makes the above hold",
            1, rendered.map { it.length }.distinct().size,
        )
    }

    @Test
    fun instantToStringWouldHaveBrokenIt() {
        // Kept as an executable explanation. This asserts the WRONG behaviour of the rejected
        // approach, so the reason for the custom formatter cannot be lost to a future "simplification".
        val onSecond = Instant.parse("2026-08-01T10:00:05Z")
        val halfLater = onSecond.plusMillis(500)

        assertTrue(
            "Instant.toString() drops the zero fraction: " + onSecond.toString(),
            onSecond.toString() > halfLater.toString(),
        )
        assertTrue(
            "…while the real order is the other way round",
            onSecond.isBefore(halfLater),
        )
        assertTrue(
            "and the fixed-width format gets it right",
            fixedWidth.format(onSecond) < fixedWidth.format(halfLater),
        )
    }
}
