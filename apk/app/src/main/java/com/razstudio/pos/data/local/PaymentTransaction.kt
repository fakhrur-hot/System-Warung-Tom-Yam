package com.razstudio.pos.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.math.roundToLong

/**
 * One attempt to settle an order through a payment gateway. (PG-REQ-5, task 5.1)
 *
 * **This row is the source of truth after the day it was created**, not the gateway. The evaluated
 * aggregator's status requery is documented as returning "no result available for transactions more
 * than 1 day or 24 hours", so anything that re-derives payment state by asking the gateway will
 * start returning nothing the next morning. The callback result is written here the moment it
 * arrives and read from here forever after. (designs.md F5)
 *
 * Cash and static-QR settlements do **not** create rows here — they have no gateway leg and
 * `Order.paymentMethod` already records them. This table exists for payments that can be pending,
 * fail, time out, or be reconciled against an acquirer's statement.
 */
@Entity(
    tableName = "payment_transactions",
    indices = [
        // The two lookups that exist: "what happened to this order" and the idempotency replay.
        Index("orderId"),
        Index(value = ["idempotencyKey"], unique = true),
    ]
)
data class PaymentTransaction(
    @PrimaryKey val id: String,

    val orderId: String,

    /** A [PaymentMethod.code]. Stored as text for the same reason `Order.paymentMethod` is. */
    val paymentMethod: String,

    /**
     * Amount in **sen**, not ringgit.
     *
     * The rest of this app holds money as `Double` (`Order.total`, `OrderItem.unitPriceSnapshot`),
     * which is fine for arithmetic it already does but wrong to send to a gateway: `19.99` is not
     * representable in binary floating point, and a gateway that receives `19.989999999999998`
     * either rejects it or settles a different amount than the receipt shows. Integer sen is the
     * only safe wire representation.
     *
     * **Convert at this boundary and nowhere else** — [fromRinggit] and [ringgit] are the whole
     * conversion surface. A second conversion site is how rounding drift gets into reports. (A8)
     */
    val amountSen: Long,

    val status: PaymentTransactionStatus,

    /** The gateway's own transaction id, once it has issued one. */
    val gatewayTransactionId: String? = null,

    /** Raw gateway payload or error text, kept verbatim for disputes. Never parsed for state. */
    @ColumnInfo(name = "gatewayResponseJson")
    val gatewayResponse: String? = null,

    /**
     * Replayed on every retry of the same payment, so a retried attempt cannot double-charge.
     *
     * Derived from `(orderId, amountSen)` — **not** from a per-attempt id. A key derived from a
     * fresh attempt id is a new key each time, which is precisely the opposite of idempotent and
     * was the defect in the original design. Persisting it is what makes the replay possible. (A6)
     */
    val idempotencyKey: String,

    /** Sandbox transactions are badged "TEST" and excluded from takings. (PG-REQ-10) */
    val isSandbox: Boolean = false,

    val createdAt: String,

    /** When the gateway reached a terminal state. Null while pending. */
    val settledAt: String? = null,
) {
    /** The amount as ringgit, for display and for the receipt. */
    val ringgit: Double get() = amountSen / 100.0

    companion object {
        /**
         * The one place ringgit becomes sen.
         *
         * `roundToLong` rather than a cast: `(19.99 * 100).toLong()` is **1998**, because the
         * double nearest 19.99 is slightly below it and truncation takes the sen off. That is a
         * one-sen undercharge on a very ordinary price, and it would only ever be noticed as an
         * unexplained gap at reconciliation.
         */
        fun fromRinggit(amount: Double): Long = (amount * 100.0).roundToLong()

        /**
         * Stable key for `(orderId, amount)`. Deliberately not random and not time-based — the
         * whole point is that a retry of the same payment computes the same value. (A6)
         */
        fun idempotencyKeyFor(orderId: String, amountSen: Long): String = "$orderId:$amountSen"

        /** Fixed-width ISO-8601 UTC, matching [com.razstudio.pos.data.local.LocalBackend]'s own
         *  `nowTimestamp` — same rationale: `Instant.toString()`'s variable fractional-digit width
         *  breaks text comparison/sort ordering. */
        private val ISO_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter
                .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)

        fun nowIso(): String = ISO_FORMAT.format(java.time.Instant.now())
    }
}

/** [PaymentTransaction.createdAt] as epoch millis — used to reconstruct a QR's expiry when
 *  resuming a payment left PENDING across a crash (task 8.5). Falls back to "now" (an already-
 *  expired QR, safely handled) rather than throwing on a row this build cannot parse. */
fun PaymentTransaction.createdAtMillis(): Long =
    runCatching { java.time.Instant.parse(createdAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis())

/**
 * Lifecycle of a gateway payment. (PG-REQ-5)
 *
 * [PENDING] is the only non-terminal state; everything else is final for that attempt. A retry
 * creates a new row carrying the same [PaymentTransaction.idempotencyKey], so the history of
 * attempts survives while the gateway still treats them as one payment.
 */
enum class PaymentTransactionStatus {
    /** Initiated; awaiting the customer, the callback, or both. */
    PENDING,

    SUCCESS,
    FAILED,

    /** The café or the customer abandoned it — a WebView dismissed, a QR screen cancelled. */
    CANCELLED,

    /** No terminal answer within the window. Distinct from [FAILED]: the money may still move. */
    TIMEOUT,

    REFUNDED;

    val isTerminal: Boolean get() = this != PENDING

    /** Counted as money received. Only this state may mark an order paid. */
    val isPaid: Boolean get() = this == SUCCESS
}
