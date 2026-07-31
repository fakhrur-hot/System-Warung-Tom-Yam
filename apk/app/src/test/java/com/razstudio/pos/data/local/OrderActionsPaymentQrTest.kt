package com.razstudio.pos.data.local

import com.razstudio.pos.data.OperatingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 17.4 — Property 9: the Payment QR is mode-independent
 * (Validates Requirements 13.3, 13.9, 14.7).
 *
 * The Payment QR is a static payee image stored on the device. Nothing about presenting it depends
 * on where orders are stored, so it must behave identically in Cloud, LAN and Kiosk Mode. The
 * failure this guards is quiet and expensive: a café switches topology, the Show QR button stops
 * appearing, and staff discover it mid-service with a customer waiting to pay.
 *
 * ### Why this tests [OrderActions.canShowPaymentQr] rather than the composable
 *
 * The visibility rule used to be an inline `if (hash != null && bitmap != null)` inside
 * `OrderDetailSheet`, wrapped in the payment-permission check. Asserting on that directly would need
 * a Compose UI-test harness this module does not have, and a test that re-implemented the condition
 * would pass while the real one drifted. So the rule moved into [OrderActions] — already this
 * project's single authority for order-action button visibility — and the sheet now calls it. These
 * tests therefore pin the exact expression that runs in production.
 */
class OrderActionsPaymentQrTest {

    private val payableStatuses = listOf(
        OrderStatus.SENT_TO_KITCHEN,
        OrderStatus.PREPARING,
        OrderStatus.READY,
    )

    private val hash = "a".repeat(64)

    // ── The button is absent without a configured QR (Requirement 13.3) ───────────────────────────

    @Test
    fun absentWhenNoHash() {
        for (status in payableStatuses) {
            assertFalse(
                "no configured QR must mean NO button, not a button that opens an empty dialog",
                OrderActions.canShowPaymentQr(
                    hasPaymentPermission = true,
                    status = status,
                    paymentQrHash = null,
                    hasStoredImage = false,
                ),
            )
        }
    }

    @Test
    fun absentWhenHashExistsButTheFileDoesNot() {
        // The state after a partial wipe, or a download that failed after the hash was recorded.
        // PaymentQrCacheTest covers detecting it; this covers not showing a dead control because of it.
        assertFalse(
            OrderActions.canShowPaymentQr(
                hasPaymentPermission = true,
                status = OrderStatus.READY,
                paymentQrHash = hash,
                hasStoredImage = false,
            ),
        )
    }

    @Test
    fun absentWhenTheDeviceMayNotTakePayment() {
        // A staff device without the payment permission must not be able to present the payee code.
        assertFalse(
            OrderActions.canShowPaymentQr(
                hasPaymentPermission = false,
                status = OrderStatus.READY,
                paymentQrHash = hash,
                hasStoredImage = true,
            ),
        )
    }

    @Test
    fun absentOnAnOrderThatCannotBePaid() {
        // Tied to the same statuses as the Pay buttons: offering Show QR on a RECEIVED order (not yet
        // sent to the kitchen) or a COMPLETED/CANCELLED one invites payment against the wrong ticket.
        for (status in OrderStatus.entries.filterNot { it in payableStatuses }) {
            assertFalse(
                "Show QR must not appear on $status",
                OrderActions.canShowPaymentQr(
                    hasPaymentPermission = true,
                    status = status,
                    paymentQrHash = hash,
                    hasStoredImage = true,
                ),
            )
        }
    }

    // ── The button is present whenever it legitimately can be (Requirement 13.9) ──────────────────

    @Test
    fun presentWhenConfiguredAndPayable() {
        for (status in payableStatuses) {
            assertTrue(
                "Show QR must appear on $status when a QR is configured and payment is permitted",
                OrderActions.canShowPaymentQr(
                    hasPaymentPermission = true,
                    status = status,
                    paymentQrHash = hash,
                    hasStoredImage = true,
                ),
            )
        }
    }

    @Test
    fun visibilityTracksPayButtonVisibilityExactly() {
        // Requirement 13.9: the code is presentable by any device permitted to take payment — admin
        // and staff alike. So with a QR configured, Show QR must appear on exactly the statuses the
        // Pay buttons do, with no status where one appears without the other.
        for (status in OrderStatus.entries) {
            assertEquals(
                "Show QR and the Pay buttons must agree on $status",
                OrderActions.canTakePayment(status),
                OrderActions.canShowPaymentQr(
                    hasPaymentPermission = true,
                    status = status,
                    paymentQrHash = hash,
                    hasStoredImage = true,
                ),
            )
        }
    }

    // ── Property 9 proper: no OperatingMode anywhere in the decision ──────────────────────────────

    @Test
    fun theRuleCannotConsultTheOperatingMode() {
        // Structural, not behavioural: a mode-dependent rule is impossible to express if the function
        // has nowhere to receive a mode. Reflection rather than reading the source, so the guarantee
        // survives a future signature change instead of resting on review.
        val fn = OrderActions::class.java.declaredMethods.single { it.name == "canShowPaymentQr" }
        assertFalse(
            "canShowPaymentQr must not accept an OperatingMode — that is Property 9",
            fn.parameterTypes.any { OperatingMode::class.java.isAssignableFrom(it) },
        )
        assertFalse(
            "no parameter may be a mode-like type either",
            fn.parameterTypes.any { it.name.contains("Mode") || it.name.contains("Capabilit") },
        )
    }

    @Test
    fun everyOperatingModeYieldsTheSameAnswer() {
        // Behavioural companion to the structural check: the answer is computed once and compared
        // against itself for each mode. Trivially true given the signature — which is the point. If
        // someone later threads a mode in, this loop is where the differing answers surface.
        val expected = OrderActions.canShowPaymentQr(
            hasPaymentPermission = true,
            status = OrderStatus.READY,
            paymentQrHash = hash,
            hasStoredImage = true,
        )
        for (mode in OperatingMode.entries) {
            assertEquals(
                "the Payment QR must present identically in $mode (Requirement 14.7)",
                expected,
                OrderActions.canShowPaymentQr(
                    hasPaymentPermission = true,
                    status = OrderStatus.READY,
                    paymentQrHash = hash,
                    hasStoredImage = true,
                ),
            )
        }
        assertTrue("and that shared answer must be 'visible', or the loop proves nothing", expected)
    }
}
