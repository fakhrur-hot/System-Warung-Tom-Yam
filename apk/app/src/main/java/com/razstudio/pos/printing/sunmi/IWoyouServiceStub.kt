package com.razstudio.pos.printing.sunmi

import android.graphics.Bitmap
import android.os.IBinder
import android.util.Log
import woyou.aidlservice.jiuiv5.IWoyouService

/**
 * Thin wrapper over the Sunmi printer service (`woyou.aidlservice.jiuiv5.IWoyouService`).
 *
 * ## Why this is no longer reflective
 *
 * This class used to call every method by reflection, on the theory that shipping the interface
 * would bloat the APK. It could never have worked. `IWoyouService.Stub` lives in **Sunmi's** app,
 * not ours, so `Class.forName("woyou.aidlservice.jiuiv5.IWoyouService\$Stub")` searched our own
 * classloader for a class that was never there, threw `ClassNotFoundException`, and fell through to
 * a "raw binder fallback" — a `BinderProxy`, which has `transact` and `pingBinder` and none of
 * `printBitmap`, `openDrawer` or `getPrinterPaper`. Every call then failed method lookup and was
 * *logged and swallowed*, so the printer appeared silent rather than broken.
 *
 * The interface is now declared in `src/main/aidl/` and generated at build time, which is what
 * `asInterface(binder)` needs and what every working Sunmi integration does. The generated code is
 * a few KB, and in exchange the compiler checks every signature — which matters, because the
 * hand-written guesses were wrong in three places:
 *
 * | Call | Was | Actually |
 * |---|---|---|
 * | `printBitmap` | `(Bitmap, int, ICallback)` | `(Bitmap, ICallback)` — **nothing printed** |
 * | `enterPrinterBuffer` | `(int)` | `(boolean)` — buffering never engaged |
 * | `openDrawer` | `()` | `(ICallback)` — **drawer never opened** |
 * | `getDrawerStatus` | returns `int` | returns `boolean` |
 *
 * ## The ordering hazard
 *
 * AIDL assigns Binder transaction codes by declaration order, so the `.aidl` must match the
 * revision on the device. An older published copy puts `enterPrinterBuffer` at position 23, where
 * the current service has `cutPaper` — calling one would have run the other. The bundled file is
 * the current revision, cross-checked between two independent sources.
 *
 * Callbacks are passed as `null` throughout: every call here is fire-and-forget, and the service
 * documents null as acceptable. Results that matter are read from the synchronous return values.
 */
class IWoyouServiceStub(binder: IBinder) {

    private val service: IWoyouService? = try {
        IWoyouService.Stub.asInterface(binder)
    } catch (e: Exception) {
        Log.e(TAG, "Could not bind IWoyouService from binder", e)
        null
    }

    /** True when the service interface resolved and calls will actually reach the printer. */
    val isUsable: Boolean get() = service != null

    // ── Buffer control ───────────────────────────────────────────────────────

    /**
     * Queue subsequent commands until [commitPrinterBuffer], so a receipt commits as one unit
     * rather than tearing mid-print.
     *
     * @param clean 0 = keep existing buffer, 1 = clear it first
     */
    fun enterPrinterBuffer(clean: Int = 1) {
        guard("enterPrinterBuffer") { it.enterPrinterBuffer(clean != 0) }
    }

    fun commitPrinterBuffer() {
        guard("commitPrinterBuffer") { it.commitPrinterBuffer() }
    }

    // ── Printing ─────────────────────────────────────────────────────────────

    /**
     * Print a rendered bitmap. The service scales to the head width itself, so [pixelWidth] is
     * informational only — it is the width `BitmapTicketRenderer` already rendered at.
     */
    fun printBitmap(bitmap: Bitmap, pixelWidth: Int = 576, callback: Any? = null) {
        guard("printBitmap") { it.printBitmap(bitmap, null) }
    }

    /** Feed [n] blank lines. Used to clear the print head before a cut. */
    fun lineWrap(n: Int) {
        guardValue("lineWrap", Unit) { it.lineWrap(n, null) }
    }

    /**
     * Feed and cut the paper, through the AIDL only.
     *
     * A raw `GS V` sent via `sendRAWData` was tried here and has been removed. designs.md H8 is
     * explicit that this pipeline never produces raw ESC/POS bytes — documents emit markup and it
     * is rendered, so injecting a byte sequence at the transport is a hole in the one-renderer
     * design, not a fix within it.
     *
     * Never throws: not every model has a cutter, and a receipt that printed but needs tearing by
     * hand is not a failed sale. Throwing would trip `PrintJob`'s retry and reprint the lot.
     */
    fun cutPaper() {
        guardValue("cutPaper", Unit) { it.cutPaper(null) }
    }

    // ── Cash drawer ──────────────────────────────────────────────────────────

    /**
     * Open the cash drawer on the RJ11/RJ12 port.
     *
     * There is no `ESC p` in Sunmi's command set — this AIDL call is the only route, which is why
     * a printer added over Bluetooth can never open the till. (HW-REQ-3, designs.md H9)
     */
    fun openDrawer() {
        guard("openDrawer") { it.openDrawer(null) }
    }

    /**
     * Is a drawer attached and open?
     *
     * The AIDL returns a **boolean**, so it cannot distinguish "no drawer attached" from "drawer
     * closed" — both are false. Mapped to the tri-state this app's callers expect:
     * `2` = open, `1` = closed-or-absent, `-1` = call failed.
     */
    fun getDrawerStatus(): Int =
        guardValue("getDrawerStatus", -1) { if (it.drawerStatus) 2 else 1 }

    /** Hardware counter of drawer openings, for the closing report. -1 if unavailable. */
    fun getOpenDrawerTimes(): Int = guardValue("getOpenDrawerTimes", -1) { it.openDrawerTimes }

    // ── Paper ────────────────────────────────────────────────────────────────

    /** 1 = 58 mm, 2 = 80 mm, -1 = unknown. */
    fun getPrinterPaper(): Int = guardValue("getPrinterPaper", -1) { it.printerPaper }

    // ── Call plumbing ────────────────────────────────────────────────────────

    /**
     * A `RemoteException` here means the service died mid-call — the printer app was updated or
     * killed. Rethrown so `PrintJob`'s retry sees a failure; anything quieter would leave a café
     * believing a slip printed.
     */
    private inline fun guard(name: String, block: (IWoyouService) -> Unit) {
        val svc = service
        if (svc == null) {
            Log.w(TAG, "$name skipped — service not bound")
            return
        }
        try {
            block(svc)
        } catch (e: Exception) {
            Log.e(TAG, "$name failed: ${e.message}")
            throw RuntimeException("Sunmi AIDL call '$name' failed: ${e.message}", e)
        }
    }

    /**
     * Query variant. Returns [fallback] instead of throwing: these are all capability probes, and
     * a terminal whose service predates a getter should read as "unknown", not crash a screen.
     */
    private inline fun <T> guardValue(name: String, fallback: T, block: (IWoyouService) -> T): T {
        val svc = service ?: return fallback
        return try {
            block(svc)
        } catch (e: Exception) {
            Log.w(TAG, "$name unavailable: ${e.message}")
            fallback
        }
    }

    private companion object {
        const val TAG = "IWoyouServiceStub"
    }
}
