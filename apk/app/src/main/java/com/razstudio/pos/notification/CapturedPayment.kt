package com.razstudio.pos.notification

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A payment notification captured from a monitored eWallet/bank app.
 *
 * Each row records exactly one notification that the [PaymentNotificationListener] identified as
 * a payment-received event. The raw notification content is preserved verbatim for debugging
 * format changes, while parsed fields (amount, sender, reference) support matching and display.
 *
 * The [matchStatus] tracks the correlation lifecycle: initially UNMATCHED, then either MATCHED
 * (auto or manual), AMBIGUOUS (multiple candidate orders), or DISMISSED (admin marked non-order).
 *
 * Money is integer **sen** — same convention as [CashDrawerEvent] and [PaymentTransaction].
 */
@Entity(
    tableName = "captured_payments",
    indices = [
        Index("capturedAt"),
        Index("matchStatus"),
        Index("matchedOrderId"),
    ]
)
data class CapturedPayment(
    /** UUID generated at capture time. */
    @PrimaryKey val id: String,

    /** Amount in sen (integer, no floating-point issues). */
    val amountSen: Long,

    /** The eWallet/bank app that sent the notification ([WalletApp.name]). */
    val walletApp: String,

    /** Package name of the source app. */
    val packageName: String,

    /** Sender name extracted from notification (nullable — not all apps include it). */
    val sender: String?,

    /** Payment reference/transaction ID from notification (nullable). */
    val reference: String?,

    /** Raw notification title — kept for debugging format changes. */
    val rawTitle: String,

    /** Raw notification text — kept for debugging format changes. */
    val rawText: String,

    /** Match outcome ([MatchStatus.name], stored as TEXT). */
    val matchStatus: String,

    /** Order ID if matched (single or manually resolved). */
    val matchedOrderId: String?,

    /** ISO-8601 UTC timestamp when the notification was captured. */
    val capturedAt: String,

    /** ISO-8601 UTC timestamp when matched (null if unmatched/ambiguous). */
    val matchedAt: String?,
)
