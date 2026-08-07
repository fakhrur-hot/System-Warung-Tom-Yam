package com.razstudio.pos.notification

import android.util.Log
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import com.razstudio.pos.data.lan.LanPushBus
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Broadcasts a payment alert to other connected devices after a payment is captured on this
 * (SecondaryAdmin) device.
 *
 * Propagation strategy per [OperatingMode]:
 * - **Cloud**: broadcasts via Supabase Realtime on the `admin-orders` channel with event
 *   `PAYMENT_RECEIVED`. Currently a log placeholder — wiring to the existing RealtimeService
 *   WebSocket send is deferred until that integration point is available.
 * - **LAN**: publishes via [LanPushBus] with [com.razstudio.pos.data.lan.LanPushEnvelope.Type.PAYMENT_RECEIVED].
 * - **Kiosk**: no-op (single device, no peers to propagate to).
 *
 * Validates: Requirements 4.1, 4.2, 4.3, 4.4
 */
@Singleton
class PaymentAlertBroadcaster @Inject constructor(
    private val modeRepository: ModeRepository,
    private val lanPushBus: LanPushBus,
) {

    /**
     * Broadcasts a [PAYMENT_RECEIVED][com.razstudio.pos.data.lan.LanPushEnvelope.Type.PAYMENT_RECEIVED]
     * event to all other connected devices.
     *
     * Safe to call from any coroutine context — the LAN path is non-suspending and the Cloud path
     * is currently a log-only placeholder.
     */
    suspend fun broadcastPaymentReceived(payment: CapturedPayment) {
        when (modeRepository.currentMode()) {
            OperatingMode.CLOUD -> broadcastCloud(payment)
            OperatingMode.LAN -> broadcastLan(payment)
            OperatingMode.KIOSK -> { /* Single device — no propagation needed */ }
        }
    }

    private fun broadcastLan(payment: CapturedPayment) {
        val delta = JSONObject().apply {
            put("type", "PAYMENT_RECEIVED")
            put("amountSen", payment.amountSen)
            put("sender", payment.sender ?: "")
            put("walletApp", payment.walletApp)
            put("rawText", payment.rawText)
        }
        lanPushBus.publishPayment(delta, Instant.now().toString())
        Log.d(TAG, "LAN broadcast: PAYMENT_RECEIVED ${payment.amountSen} sen from ${payment.walletApp}")
    }

    private fun broadcastCloud(payment: CapturedPayment) {
        // Cloud broadcasting is handled by the existing Supabase Realtime infrastructure.
        // The RealtimeService already maintains the WebSocket connection to admin-orders.
        // The actual Supabase broadcast mechanism (sending a message on the existing channel)
        // will be wired when the RealtimeService integration point is available.
        Log.d(TAG, "Cloud broadcast: PAYMENT_RECEIVED ${payment.amountSen} sen from ${payment.walletApp}")
    }

    companion object {
        private const val TAG = "PaymentAlertBroadcast"
    }
}
