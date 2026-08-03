package com.razstudio.pos.ui.viewmodels

import android.graphics.Bitmap
import com.razstudio.pos.data.local.PaymentMethod

/**
 * Drives the full-screen gateway checkout overlay (task 8.1). Cash and static QR never produce
 * this — they complete synchronously through [TableViewViewModel.processPayment] /
 * [StaffOrderViewModel.processPayment] exactly as before. A gateway payment cannot: the acquirer
 * must confirm it first, so this state machine exists to poll until it does.
 */
sealed class GatewayCheckoutState {

    /** Waiting on `payment-initiate`. */
    data class Initiating(val method: PaymentMethod, val amount: Double) : GatewayCheckoutState()

    /**
     * The acquirer has a pending attempt; showing the checkout to the customer. [checkoutUrl] is
     * always present — every channel the evaluated aggregator documents is a hosted page, not a
     * seamless API returning a QR payload to render natively (designs.md F1, F6 #1). [qr] is that
     * URL encoded as a QR bitmap so the customer can scan it with their own phone rather than the
     * till being turned around; it is null only if bitmap encoding itself failed, in which case
     * [checkoutUrl] is still shown as a plain link.
     */
    data class AwaitingPayment(
        val transactionId: String,
        val method: PaymentMethod,
        val amount: Double,
        val checkoutUrl: String,
        val qr: Bitmap?,
        val expiresAtMillis: Long,
    ) : GatewayCheckoutState()

    data class Failed(val method: PaymentMethod, val message: String) : GatewayCheckoutState()

    /** No terminal answer inside [GatewayPolling.TOTAL_TIMEOUT_SECONDS]. Distinct from [Failed] —
     *  the money may still move; see designs.md's state machine and F5. */
    object TimedOut : GatewayCheckoutState()
}

/**
 * Status-polling schedule (task 8.2, designs.md A10).
 *
 * A10 flags the free-tier cost of naive 3-second polling for the full QR lifetime: ~40 Edge
 * Function invocations per payment. Backing off to 5s after the first 30s keeps a single payment
 * well under that while still feeling responsive during the window a customer is most likely to
 * complete it — right after the QR appears.
 */
object GatewayPolling {
    /** Matches the "Expires in 1:45" countdown on designs.md's Screen 3. */
    const val QR_EXPIRY_SECONDS = 120

    /** A little grace past the QR's own expiry, in case a callback lands right at the edge. */
    const val TOTAL_TIMEOUT_SECONDS = QR_EXPIRY_SECONDS + 10

    private const val FAST_WINDOW_SECONDS = 30
    private const val FAST_POLL_MILLIS = 3_000L
    private const val SLOW_POLL_MILLIS = 5_000L

    fun nextDelayMillis(elapsedSeconds: Int): Long =
        if (elapsedSeconds < FAST_WINDOW_SECONDS) FAST_POLL_MILLIS else SLOW_POLL_MILLIS
}
