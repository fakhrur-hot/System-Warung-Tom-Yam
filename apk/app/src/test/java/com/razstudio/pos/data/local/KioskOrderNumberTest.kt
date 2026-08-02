package com.razstudio.pos.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Kiosk Mode stores a sale with no table and a running number (Requirement 3.2, 3.5).
 *
 * `OrderNumberSequence` was added in schema v11 *for this*, and sat unused for four schema versions
 * because `Order.tableId` was NOT NULL and there was no `orderNumber` column — so the number it
 * minted had nowhere to go. This is the first test that puts the two halves together.
 *
 * The number is what the counter and the kitchen use to refer to the same sale, so a duplicate is
 * not a cosmetic bug: two customers are handed the same identifier and the kitchen cannot tell their
 * orders apart.
 */
@RunWith(RobolectricTestRunner::class)
class KioskOrderNumberTest {

    private lateinit var db: AppDatabase
    private lateinit var orders: OrderDao
    private lateinit var sequence: OrderNumberSequenceDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        orders = db.orderDao()
        sequence = db.orderNumberSequenceDao()
    }

    @After
    fun tearDown() = db.close()

    private fun kioskSale(number: Int, total: Double = 10.0) = Order(
        id = UUID.randomUUID().toString(),
        tableId = null,
        orderNumber = number,
        source = "STAFF",
        status = OrderStatus.COMPLETED,
        paymentMethod = "CASH",
        total = total,
        createdAt = "2026-08-02T12:00:00Z",
    )

    // ── The thing that was impossible before v15 ─────────────────────────────────────────────────

    @Test
    fun aSaleCanBeStoredWithNoTable() = runTest {
        val sale = kioskSale(number = 1)
        orders.insertOrder(sale)

        val back = orders.getOrderById(sale.id)
        assertNull("a Kiosk sale has no table", back?.tableId)
        assertEquals(1, back?.orderNumber)
    }

    @Test
    fun aTableServiceOrderStillHasNoRunningNumber() = runTest {
        // The two shapes must stay distinguishable, because printing and reporting branch on it.
        val dineIn = kioskSale(number = 1).copy(tableId = "T5", orderNumber = null)
        orders.insertOrder(dineIn)

        val back = orders.getOrderById(dineIn.id)
        assertEquals("T5", back?.tableId)
        assertNull(back?.orderNumber)
    }

    // ── The number itself ────────────────────────────────────────────────────────────────────────

    @Test
    fun numbersStartAtOneAndIncrementWithinADay() = runTest {
        val day = "2026-08-02"
        assertEquals(1, sequence.getNextOrderNumber(day))
        assertEquals(2, sequence.getNextOrderNumber(day))
        assertEquals(3, sequence.getNextOrderNumber(day))
    }

    @Test
    fun eachBusinessDayStartsAgainAtOne() = runTest {
        // A kiosk owner reads "#3" off a receipt and expects it to mean the third sale of *today*.
        assertEquals(1, sequence.getNextOrderNumber("2026-08-02"))
        assertEquals(2, sequence.getNextOrderNumber("2026-08-02"))
        assertEquals(1, sequence.getNextOrderNumber("2026-08-03"))
    }

    @Test
    fun concurrentSalesNeverShareANumber() = runTest {
        // Two taps in quick succession on a busy counter. A duplicate here hands two customers the
        // same identifier and leaves the kitchen unable to tell their orders apart — which is why
        // the DAO guards its read-and-increment with a mutex rather than relying on call ordering.
        val day = "2026-08-02"
        val issued = (1..25).map { async { sequence.getNextOrderNumber(day) } }.awaitAll()

        assertEquals("every number must be unique", issued.size, issued.toSet().size)
        assertEquals("and the run must be contiguous", (1..25).toList(), issued.sorted())
    }

    // ── Reports must still see Kiosk takings ─────────────────────────────────────────────────────

    @Test
    fun kioskSalesCountTowardsRevenue() = runTest {
        // The reason Kiosk sales are Orders at all rather than a separate entity: ReportsViewModel
        // reads orderDao, so a separate table would have given a Kiosk owner empty reports.
        orders.insertOrder(kioskSale(number = 1, total = 12.50))
        orders.insertOrder(kioskSale(number = 2, total = 7.50))

        val revenue = orders.getTotalRevenueBetween("2026-08-01T00:00:00Z", "2026-08-03T00:00:00Z")
        assertEquals(20.0, revenue ?: 0.0, 0.001)
    }

    @Test
    fun aKioskSaleIsCountedAsACompletedOrder() = runTest {
        orders.insertOrder(kioskSale(number = 1))

        val count = orders.getCompletedOrderCountBetween(
            "2026-08-01T00:00:00Z", "2026-08-03T00:00:00Z",
        )
        assertTrue("a paid sale must show in the day's order count", count >= 1)
    }
}
