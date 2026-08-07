package com.razstudio.pos.notification

import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.local.Order
import com.razstudio.pos.data.local.OrderDao
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Correlates captured payment notifications to pending orders by exact amount match.
 *
 * The algorithm is intentionally simple: match on integer sen amount, exclude orders that already
 * have a matched capture, and auto-complete when exactly one candidate remains. Multiple candidates
 * produce AMBIGUOUS status requiring admin disambiguation; zero candidates leave the capture as
 * UNMATCHED.
 */
@Singleton
class PaymentMatcher @Inject constructor(
    private val orderDao: OrderDao,
    private val capturedPaymentDao: CapturedPaymentDao,
    private val backendGateway: BackendGateway,
) {

    /**
     * Attempt to auto-match a captured payment to an active order.
     *
     * - Single match: calls [BackendGateway.processPayment] and updates capture to MATCHED.
     * - Multiple matches: marks capture as AMBIGUOUS for admin resolution.
     * - No match: leaves capture as UNMATCHED.
     * - Backend failure: reverts to UNMATCHED and returns [MatchResult.Error].
     */
    suspend fun matchPayment(payment: CapturedPayment): MatchResult {
        // Step 1: Query all active (non-terminal) orders
        val activeOrders = orderDao.getActiveOrders()

        // Step 2: Filter to orders matching the captured amount (exact sen match)
        val candidates = activeOrders.filter { order ->
            val orderSen = (order.total * 100.0).roundToLong()
            orderSen == payment.amountSen
        }

        // Step 3: Exclude orders that already have a captured payment matched
        val unmatched = candidates.filter { order ->
            capturedPaymentDao.countMatchesForOrder(order.id) == 0
        }

        return when {
            unmatched.size == 1 -> {
                val order = unmatched.first()
                val result = backendGateway.processPayment(order.id, "NOTIFICATION")
                if (result is ApiResult.Error) {
                    // Revert to UNMATCHED on failure
                    return MatchResult.Error(result.message)
                }
                capturedPaymentDao.updateMatch(
                    id = payment.id,
                    status = MatchStatus.MATCHED.name,
                    orderId = order.id,
                    matchedAt = Instant.now().toString(),
                )
                MatchResult.SingleMatch(orderId = order.id, tableId = order.tableId)
            }

            unmatched.size > 1 -> {
                capturedPaymentDao.updateMatch(
                    id = payment.id,
                    status = MatchStatus.AMBIGUOUS.name,
                    orderId = null,
                    matchedAt = null,
                )
                MatchResult.MultipleMatches(orders = unmatched)
            }

            else -> {
                // Already marked UNMATCHED on insert — no update needed
                MatchResult.NoMatch
            }
        }
    }

    /**
     * Admin manually resolves an AMBIGUOUS payment by selecting the correct order.
     *
     * Calls [BackendGateway.processPayment] and updates the capture to MATCHED on success.
     * Returns [MatchResult.Error] if the backend rejects the payment (e.g., order cancelled).
     */
    suspend fun resolveManually(capturedPaymentId: String, orderId: String): MatchResult {
        val result = backendGateway.processPayment(orderId, "NOTIFICATION")
        if (result is ApiResult.Error) {
            // Revert to UNMATCHED on failure
            capturedPaymentDao.updateMatch(
                id = capturedPaymentId,
                status = MatchStatus.UNMATCHED.name,
                orderId = null,
                matchedAt = null,
            )
            return MatchResult.Error(result.message)
        }

        capturedPaymentDao.updateMatch(
            id = capturedPaymentId,
            status = MatchStatus.MATCHED.name,
            orderId = orderId,
            matchedAt = Instant.now().toString(),
        )

        // Retrieve the order to include tableId in the result
        val order = orderDao.getOrderById(orderId)
        return MatchResult.SingleMatch(orderId = orderId, tableId = order?.tableId)
    }

    /**
     * Admin dismisses a captured payment as non-order (personal transfer, etc.).
     */
    suspend fun dismiss(capturedPaymentId: String) {
        capturedPaymentDao.dismiss(capturedPaymentId)
    }
}

/**
 * Result of an auto-match or manual resolution attempt.
 */
sealed class MatchResult {
    /** Exactly one order matched and payment was processed successfully. */
    data class SingleMatch(val orderId: String, val tableId: String?) : MatchResult()

    /** Multiple orders have the same total — admin must disambiguate. */
    data class MultipleMatches(val orders: List<Order>) : MatchResult()

    /** No active order matches this amount. */
    data object NoMatch : MatchResult()

    /** Backend rejected the payment (order cancelled, network error, etc.). */
    data class Error(val message: String) : MatchResult()
}
