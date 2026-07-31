package com.razstudio.pos.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [OrderNumberSequenceDao.getNextOrderNumber].
 *
 * Validates: Requirements 3.5
 * Property 7 (order-number half): order identity is unique — monotonic within a business day.
 *
 * Tests are run via Robolectric so that [Room.inMemoryDatabaseBuilder] (which requires an Android
 * [android.content.Context]) can execute as a plain JVM test without a physical device.
 *
 * All four properties are verified:
 *  1. **Monotonicity** — repeated calls for the same day return strictly increasing numbers.
 *  2. **Uniqueness** — every returned value within a day is distinct.
 *  3. **Day boundary reset** — numbers for one business day are independent from the next.
 *  4. **Concurrent safety** — 20 concurrent coroutines each receive a distinct number; together
 *     they form exactly the set {1..20} with no gaps or duplicates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OrderNumberSequenceDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: OrderNumberSequenceDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        dao = db.orderNumberSequenceDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // 1. Monotonicity
    // -------------------------------------------------------------------------

    /**
     * Five sequential calls for the same business day must return 1, 2, 3, 4, 5 — in order and
     * with no gaps — satisfying the "monotonic within a business day" clause of Requirement 3.5.
     */
    @Test
    fun getNextOrderNumber_returnsMonotonicallyIncreasingSequence() = runTest {
        val day = "2025-01-15"

        val numbers = (1..5).map { dao.getNextOrderNumber(day) }

        assertEquals(listOf(1, 2, 3, 4, 5), numbers)
    }

    // -------------------------------------------------------------------------
    // 2. Uniqueness
    // -------------------------------------------------------------------------

    /**
     * Ten sequential calls for the same business day must yield ten distinct values — no number
     * is issued twice, satisfying the "unique" clause of Requirement 3.5.
     */
    @Test
    fun getNextOrderNumber_allReturnedValuesWithinOneDayAreUnique() = runTest {
        val day = "2025-01-15"

        val numbers = (1..10).map { dao.getNextOrderNumber(day) }

        assertEquals(
            "Expected 10 distinct values but found duplicates",
            numbers.size, numbers.toSet().size
        )
    }

    // -------------------------------------------------------------------------
    // 3. Day boundary reset
    // -------------------------------------------------------------------------

    /**
     * Numbers for "2025-01-15" and "2025-01-16" are independent: each day starts at 1 and neither
     * day's counter is affected by calls made against the other.  Satisfies "within a business day"
     * scope of Requirement 3.5 — the sequence resets at each new business-day boundary.
     */
    @Test
    fun getNextOrderNumber_newBusinessDayResetsCounterToOne() = runTest {
        val day1 = "2025-01-15"
        val day2 = "2025-01-16"

        // Advance day1 to 3 so it is clearly non-trivially ahead.
        repeat(3) { dao.getNextOrderNumber(day1) }

        // day2 must start at 1 regardless of what day1 has reached.
        val firstOfDay2 = dao.getNextOrderNumber(day2)
        assertEquals("First order number of day2 must be 1", 1, firstOfDay2)

        // Continuing day2 must be 2 — it did not inherit day1's counter.
        val secondOfDay2 = dao.getNextOrderNumber(day2)
        assertEquals("Second order number of day2 must be 2", 2, secondOfDay2)

        // day1 must continue unaffected from where it left off (4).
        val nextOfDay1 = dao.getNextOrderNumber(day1)
        assertEquals("day1 counter must continue at 4, unaffected by day2", 4, nextOfDay1)
    }

    // -------------------------------------------------------------------------
    // 4. Concurrent safety
    // -------------------------------------------------------------------------

    /**
     * Twenty coroutines launched simultaneously for the same business day must each receive a
     * distinct number, and together they must form exactly {1..20} — the [Mutex] inside
     * [OrderNumberSequenceDao] ensures no two callers read the same counter before it is
     * incremented.
     *
     * Note: [kotlinx.coroutines.test.TestCoroutineScheduler] is single-threaded, so to exercise
     * real concurrency we switch to [Dispatchers.IO] for the actual DAO calls.
     */
    @Test
    fun getNextOrderNumber_concurrentCallsProduceDistinctNumbersForming1To20() = runTest {
        val day = "2025-01-15"
        val concurrency = 20

        val numbers = (1..concurrency)
            .map { async(Dispatchers.IO) { dao.getNextOrderNumber(day) } }
            .awaitAll()

        val sorted = numbers.sorted()
        assertEquals(
            "Expected exactly $concurrency distinct values but got: $sorted",
            (1..concurrency).toList(), sorted
        )
    }
}
