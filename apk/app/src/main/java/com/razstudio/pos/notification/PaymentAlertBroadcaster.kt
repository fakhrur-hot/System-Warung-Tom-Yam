package com.razstudio.pos.notification

import android.util.Log
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.BackendGateway
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
    private val backendGateway: BackendGateway,
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

    /**
     * Forward the capture to the backend, where the Main Admin's catch-up poll will collect it.
     *
     * ## Why a POST rather than the Realtime channel this was originally meant to use
     *
     * The placeholder this replaces was waiting on "the RealtimeService integration point". That
     * point never arrived, and it would not have helped: this deployment receives no Realtime
     * broadcast frames at all — every live feature here rides the poll instead. A socket frame also
     * vanishes if the till happens to be asleep or out of signal at the moment of capture, and a
     * payment that silently fails to reach the device holding the printer is a customer recorded as
     * unpaid. A row waits.
     *
     * Failures are logged, not thrown or retried here: the caller is a notification listener
     * reacting to a system callback, and the backend upserts on `clientId`, so the honest recovery
     * is the next capture rather than a retry loop inside a NotificationListenerService.
     */
    private suspend fun broadcastCloud(payment: CapturedPayment) {
        when (val result = backendGateway.postPaymentAlert(
            clientId = payment.id,
            amountSen = payment.amountSen,
            walletApp = payment.walletApp,
            sender = payment.sender,
            rawText = payment.rawText,
            capturedAt = payment.capturedAt,
        )) {
            is ApiResult.Success ->
                Log.i(TAG, "Forwarded payment alert: ${payment.amountSen} sen from ${payment.walletApp}")
            is ApiResult.Error ->
                Log.w(TAG, "Payment alert forward failed: ${result.code} ${result.message}")
            is ApiResult.NetworkError ->
                Log.w(TAG, "Payment alert forward offline: ${result.message}")
        }
    }

    companion object {
        private const val TAG = "PaymentAlertBroadcast"
    }
}
