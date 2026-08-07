package com.razstudio.pos.notification

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Reads and writes for [CapturedPayment] — the notification-based payment capture ledger. */
@Dao
interface CapturedPaymentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(payment: CapturedPayment)

    /** Live feed of recent captures, newest first. */
    @Query("SELECT * FROM captured_payments ORDER BY capturedAt DESC LIMIT :limit")
    fun getRecentFlow(limit: Int = 50): Flow<List<CapturedPayment>>

    /** All captures with a given [MatchStatus.name], newest first. */
    @Query("SELECT * FROM captured_payments WHERE matchStatus = :status ORDER BY capturedAt DESC")
    suspend fun getByStatus(status: String): List<CapturedPayment>

    /** Update match outcome after auto-match or manual resolution. */
    @Query("UPDATE captured_payments SET matchStatus = :status, matchedOrderId = :orderId, matchedAt = :matchedAt WHERE id = :id")
    suspend fun updateMatch(id: String, status: String, orderId: String?, matchedAt: String?)

    /** Admin dismisses a capture as non-order (personal transfer, etc.). */
    @Query("UPDATE captured_payments SET matchStatus = 'DISMISSED' WHERE id = :id")
    suspend fun dismiss(id: String)

    /** Prune old captures beyond a retention window (ISO-8601 text compare). */
    @Query("DELETE FROM captured_payments WHERE capturedAt < :before")
    suspend fun deleteOlderThan(before: String)

    /** How many captures are already matched to a given order (prevents double-matching). */
    @Query("SELECT COUNT(*) FROM captured_payments WHERE matchedOrderId = :orderId AND matchStatus = 'MATCHED'")
    suspend fun countMatchesForOrder(orderId: String): Int
}
