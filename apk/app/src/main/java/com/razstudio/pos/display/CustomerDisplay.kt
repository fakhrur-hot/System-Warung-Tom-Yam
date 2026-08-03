package com.razstudio.pos.display

import android.content.Context
import android.graphics.Bitmap
import com.razstudio.pos.printing.DriverAvailability

/**
 * Which customer-facing display this device drives. Persisted by name in `LocalPrefs`, so these
 * entry names are storage keys — renaming one silently resets every café's choice.
 */
enum class DisplayDriverKind {
    /** No customer display. The null-object default — see [NoDisplayDriver]. */
    NONE,

    /** An Android second screen reached through the `Presentation` API. (H6, H9) */
    PRESENTATION,

    /** A two-line VFD pole over USB serial. Task 10.2; cannot render a payment QR. */
    VFD_SERIAL,
}

/**
 * What the customer is being shown right now.
 *
 * The driver receives this whole state and decides its own rendering — an 800 × 480 screen and a
 * two-line text strip need completely different choices from the same data, so there is
 * deliberately **no `text(line, lineNo)`-style method** on the interface. MultiPOS shapes its
 * customer-display interface around the weakest device and every richer driver then implements a
 * method that cannot mean anything for it; this avoids that. (designs.md D8)
 */
sealed interface CustomerDisplayState {

    /** Nothing in progress. The screen a customer sees for most of the day. */
    data class Idle(
        val cafeName: String,
        val logo: Bitmap? = null,
    ) : CustomerDisplayState

    /** An order being built or reviewed at the counter. */
    data class Order(
        val lines: List<Line>,
        val total: Double,
        val tableLabel: String? = null,
    ) : CustomerDisplayState

    /**
     * A payment QR the customer scans. **This state is why the `Presentation` route was chosen
     * over Sunmi's `lcd*` family** — a two-line text strip cannot render it at all. (H9)
     */
    data class PaymentQr(
        val qr: Bitmap,
        val amount: Double,
        /** e.g. "DuitNow" — the rail being paid over, when known. */
        val caption: String? = null,
    ) : CustomerDisplayState

    /** Settled. Shows change due when there is any, so the customer can check it. */
    data class ThankYou(
        val changeDue: Double? = null,
    ) : CustomerDisplayState

    /** One order line, already resolved for display. */
    data class Line(
        val name: String,
        val quantity: Int,
        val lineTotal: Double,
    )
}

/**
 * One compiled-in customer-display driver, selected at runtime from [DisplayDriverKind] stored
 * device-locally. Same shape and the same rules as `PrinterDriver`: no reflection, no dynamic
 * loading, no build variants. (HW-REQ-1, HW-REQ-4, designs D5)
 *
 * Implementations must be safe to call when no display is attached — [show] on a driver whose
 * hardware vanished is a no-op, not a crash. A customer screen is an accessory; losing it must
 * never take the till down mid-service.
 */
interface CustomerDisplayDriver {

    val kind: DisplayDriverKind

    /** True when this driver's hardware is reachable on this device right now. */
    suspend fun availability(context: Context): DriverAvailability

    /**
     * True when this driver can render a scannable payment QR. False for text-strip hardware,
     * which the picker states up front rather than letting a café discover it at the counter.
     * (HW-REQ-4)
     */
    val canRenderQr: Boolean get() = false

    /** Render [state]. Must not throw; a display failure is never worth failing a sale over. */
    suspend fun show(state: CustomerDisplayState)

    /** Blank the display and release anything held. */
    suspend fun clear()
}
