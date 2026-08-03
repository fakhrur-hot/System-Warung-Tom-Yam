package com.razstudio.pos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Reads and writes for [PaymentTransaction]. (PG-REQ-5, task 5.1) */
@Dao
interface PaymentTransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: PaymentTransaction)

    @Query("SELECT * FROM payment_transactions WHERE id = :id")
    suspend fun getById(id: String): PaymentTransaction?

    /** Every attempt against one order, newest first — the retry history for a single bill. */
    @Query("SELECT * FROM payment_transactions WHERE orderId = :orderId ORDER BY createdAt DESC")
    suspend fun getForOrder(orderId: String): List<PaymentTransaction>

    /**
     * The most recent attempt for an order. This is what task 8.5's crash recovery reads when a
     * mid-payment order is reopened — **not** a gateway requery, which expires after 24 hours.
     * (designs.md F5)
     */
    @Query("""
        SELECT * FROM payment_transactions
        WHERE orderId = :orderId
        ORDER BY createdAt DESC LIMIT 1
    """)
    suspend fun getLatestForOrder(orderId: String): PaymentTransaction?

    /**
     * An existing attempt carrying this idempotency key, if any. Checked before initiating so a
     * retry replays the same key at the gateway instead of minting a second payment. (A6)
     */
    @Query("SELECT * FROM payment_transactions WHERE idempotencyKey = :key ORDER BY createdAt DESC LIMIT 1")
    suspend fun getByIdempotencyKey(key: String): PaymentTransaction?

    /**
     * Attempts still awaiting an answer. Drives the polling loop and, on relaunch, tells the app
     * which payments were in flight when it died. (PG-REQ-6)
     */
    @Query("SELECT * FROM payment_transactions WHERE status = 'PENDING' ORDER BY createdAt")
    suspend fun getPending(): List<PaymentTransaction>

    /**
     * Record a terminal outcome. Written the moment the callback lands, because the gateway stops
     * answering for this transaction after a day. (designs.md F5)
     */
    @Query("""
        UPDATE payment_transactions
        SET status = :status,
            gatewayTransactionId = :gatewayTransactionId,
            gatewayResponseJson = :gatewayResponse,
            settledAt = :settledAt
        WHERE id = :id
    """)
    suspend fun settle(
        id: String,
        status: String,
        gatewayTransactionId: String?,
        gatewayResponse: String?,
        settledAt: String,
    )

    /**
     * Transaction history for the admin screen. Live sandbox rows are included — they carry
     * `isSandbox` so the UI can badge them "TEST" rather than hide them. (PG-REQ-10)
     */
    @Query("""
        SELECT * FROM payment_transactions
        WHERE createdAt >= :startDate AND createdAt < :endDate
        ORDER BY createdAt DESC
    """)
    fun getBetweenFlow(startDate: String, endDate: String): Flow<List<PaymentTransaction>>

    /**
     * Per-method takings for the closing report.
     *
     * **Successful, non-sandbox rows only.** A pending or timed-out attempt is not money received,
     * and a sandbox transaction is not money at all — including either would overstate the day.
     * (PG-REQ-7)
     */
    @Query("""
        SELECT paymentMethod, COUNT(*) AS attemptCount, SUM(amountSen) AS totalSen
        FROM payment_transactions
        WHERE createdAt >= :startDate AND createdAt < :endDate
          AND status = 'SUCCESS' AND isSandbox = 0
        GROUP BY paymentMethod
        ORDER BY totalSen DESC
    """)
    suspend fun getGatewayTotals(startDate: String, endDate: String): List<GatewayMethodTotal>

    @Query("DELETE FROM payment_transactions")
    suspend fun deleteAll()
}

/** One row of the gateway breakdown on the closing report. (PG-REQ-7) */
data class GatewayMethodTotal(
    val paymentMethod: String,
    val attemptCount: Int,
    val totalSen: Long,
) {
    val ringgit: Double get() = totalSen / 100.0
}
