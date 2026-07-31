package com.razstudio.pos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * DAO for [OrderNumberSequence].
 *
 * The primary entry point is [getNextOrderNumber], which atomically reads the current counter for
 * the given [businessDay], increments it, writes it back, and returns the newly assigned number.
 *
 * The Room [Transaction] annotation prevents interleaved reads from another concurrent caller, and
 * the companion [mutex] prevents multiple coroutines from entering the critical section simultaneously
 * on the same device — together they guarantee no two orders within a business day share the same
 * running number.
 */
@Dao
abstract class OrderNumberSequenceDao {

    // ---------------------------------------------------------------------------
    // Internal primitives — called only from within getNextOrderNumber
    // ---------------------------------------------------------------------------

    @Query("SELECT * FROM order_number_sequences WHERE businessDay = :businessDay LIMIT 1")
    protected abstract suspend fun getByDay(businessDay: String): OrderNumberSequence?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(sequence: OrderNumberSequence)

    @Update
    protected abstract suspend fun update(sequence: OrderNumberSequence)

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Atomically reads the current order number for [businessDay], increments it, persists the
     * updated value, and returns the number that was just assigned to the caller's order.
     *
     * - If no row exists for [businessDay] yet, the sequence starts at 1 and this call returns 1.
     * - If a row already exists, its [OrderNumberSequence.nextNumber] is returned and then
     *   incremented so the next caller receives a higher number.
     *
     * Thread/coroutine safety:
     * - [Transaction] ensures the underlying SQLite read-write pair is atomic from Room's
     *   perspective.
     * - [mutex] prevents multiple coroutines from entering this function simultaneously, so
     *   concurrent callers are serialized and never read the same [nextNumber] before it is
     *   incremented.
     *
     * @param businessDay The business-day string (e.g. "2025-01-15") for which to issue the next
     *   order number.
     * @return The unique, monotonically increasing order number assigned for this call.
     */
    @Transaction
    open suspend fun getNextOrderNumber(businessDay: String): Int {
        return mutex.withLock {
            val existing = getByDay(businessDay)
            if (existing == null) {
                // First order of the day — insert a fresh sequence row starting at 2 (we return 1).
                insert(OrderNumberSequence(businessDay = businessDay, nextNumber = 2))
                1
            } else {
                val assigned = existing.nextNumber
                update(existing.copy(nextNumber = assigned + 1))
                assigned
            }
        }
    }

    /**
     * Returns the most recently issued order number for [businessDay] without modifying the
     * sequence. Returns 0 if no orders have been placed on that day yet.
     */
    @Query("SELECT nextNumber - 1 FROM order_number_sequences WHERE businessDay = :businessDay LIMIT 1")
    abstract suspend fun getLastIssuedNumber(businessDay: String): Int?

    companion object {
        /**
         * Process-level mutex serializing all concurrent calls to [getNextOrderNumber].
         * Room's [Transaction] alone is not sufficient for coroutine concurrency — without a
         * mutex two coroutines can both read the same [nextNumber] before either has written back.
         */
        val mutex = Mutex()
    }
}
