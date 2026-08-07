package com.razstudio.pos.data.local

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one writer to the cash-drawer ledger.
 *
 * Every mutation is read-balance → compute → append, so two concurrent writers could both read
 * the same balance and one movement would vanish from [CashDrawerEvent.balanceAfterSen]. A café
 * till makes that collision real — a cash sale confirming while the manager keys in the float —
 * hence a process-wide mutex around the read-modify-write, and every caller funnelled through
 * this class rather than the DAO.
 */
@Singleton
class CashDrawerLedger @Inject constructor(
    private val dao: CashDrawerEventDao,
) {
    private val mutex = Mutex()

    /** What the drawer should currently hold, in sen. Zero before the first event ever. */
    suspend fun balanceSen(): Long = dao.getLatest()?.balanceAfterSen ?: 0L

    /**
     * The manager's daily "I put RM X in the drawer". Sets the expected content to exactly
     * [floatSen]; the delta from the previous balance is recorded so the column still sums.
     */
    suspend fun setOpeningFloat(floatSen: Long): CashDrawerEvent = mutex.withLock {
        val before = balanceSen()
        append(
            CashDrawerEvent(
                type = CashDrawerEvent.TYPE_FLOAT_SET,
                amountSen = floatSen - before,
                balanceAfterSen = floatSen,
                timestamp = PaymentTransaction.nowIso(),
            )
        )
    }

    /**
     * A cash payment settled at the till: [tenderedSen] came in, change went out, so the drawer
     * nets the order total. tendered − change is recorded as the movement, which by construction
     * equals the total — RM 300 float + RM 123.45 tendered − RM 34.57 change = RM 388.88 for an
     * RM 88.88 bill.
     */
    suspend fun recordCashSale(orderId: String, totalSen: Long, tenderedSen: Long): CashDrawerEvent =
        mutex.withLock {
            val changeSen = (tenderedSen - totalSen).coerceAtLeast(0L)
            val before = balanceSen()
            append(
                CashDrawerEvent(
                    type = CashDrawerEvent.TYPE_CASH_SALE,
                    amountSen = tenderedSen - changeSen,
                    balanceAfterSen = before + (tenderedSen - changeSen),
                    orderId = orderId,
                    tenderedSen = tenderedSen,
                    changeSen = changeSen,
                    timestamp = PaymentTransaction.nowIso(),
                )
            )
        }

    /**
     * Money deliberately removed. The caller has already verified the drawer PIN;
     * [usedDefaultPin] preserves *which* PIN authorised it for the audit trail.
     */
    suspend fun recordCashOut(amountSen: Long, usedDefaultPin: Boolean): CashDrawerEvent =
        mutex.withLock {
            val before = balanceSen()
            append(
                CashDrawerEvent(
                    type = CashDrawerEvent.TYPE_CASH_OUT,
                    amountSen = -amountSen,
                    balanceAfterSen = before - amountSen,
                    usedDefaultPin = usedDefaultPin,
                    timestamp = PaymentTransaction.nowIso(),
                )
            )
        }

    private suspend fun append(event: CashDrawerEvent): CashDrawerEvent {
        val id = dao.insert(event)
        return event.copy(id = id)
    }
}
