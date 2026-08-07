package com.razstudio.pos.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One movement of physical cash through the till drawer — the app-side ledger the hardware
 * cannot keep.
 *
 * The only drawer telemetry the hardware offers is Sunmi's lifetime opening counter, which says
 * nothing about *money*. This table is the money: every row is an event that changed what should
 * be inside the drawer, and the latest row's [balanceAfterSen] is what a manager should count if
 * they open it right now. Reconciling that number against an actual count is the whole point of
 * the feature.
 *
 * **Append-only.** Corrections are new rows (a [TYPE_CASH_OUT] or a fresh [TYPE_FLOAT_SET]), never
 * edits — an audit trail that can be rewritten isn't one. Same idiom as `CafeSession`.
 *
 * Money is integer **sen** end-to-end ([PaymentTransaction] set the precedent and owns the
 * conversion rationale — see its `amountSen` doc).
 */
@Entity(
    tableName = "cash_drawer_events",
    indices = [Index("timestamp")]
)
data class CashDrawerEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** One of [TYPE_FLOAT_SET], [TYPE_CASH_SALE], [TYPE_CASH_OUT]. */
    val type: String,

    /**
     * The signed movement this event applied, in sen. Positive into the drawer, negative out.
     * For [TYPE_FLOAT_SET] this is the difference from the previous balance, so the column sums
     * to the balance across any range.
     */
    val amountSen: Long,

    /** What the drawer should hold after this event. The latest row's value IS the balance. */
    val balanceAfterSen: Long,

    /** The paid order, for [TYPE_CASH_SALE] rows. */
    val orderId: String? = null,

    /** What the customer physically handed over, for [TYPE_CASH_SALE] rows. */
    val tenderedSen: Long? = null,

    /** What was handed back, for [TYPE_CASH_SALE] rows. tendered − change = amountSen. */
    val changeSen: Long? = null,

    /**
     * True when a [TYPE_CASH_OUT] was authorised with the factory-default drawer PIN — kept on
     * the row because "who could have taken this" is exactly the question an auditor asks, and
     * "anyone who knows 666666" is an answer worth having preserved.
     */
    val usedDefaultPin: Boolean = false,

    /** Fixed-width ISO-8601 UTC (`PaymentTransaction.nowIso()`), so text sort = time sort. */
    val timestamp: String,
) {
    val ringgit: Double get() = amountSen / 100.0
    val balanceAfterRinggit: Double get() = balanceAfterSen / 100.0

    companion object {
        /** Manager set the opening float — the daily "I put RM 300 in the drawer". */
        const val TYPE_FLOAT_SET = "FLOAT_SET"

        /** A cash payment: tendered came in, change went out, the order total stayed. */
        const val TYPE_CASH_SALE = "CASH_SALE"

        /** Money deliberately removed (banking, petty cash). PIN-gated. */
        const val TYPE_CASH_OUT = "CASH_OUT"
    }
}
