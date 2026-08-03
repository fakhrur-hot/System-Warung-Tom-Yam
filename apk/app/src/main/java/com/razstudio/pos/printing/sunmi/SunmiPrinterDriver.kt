package com.razstudio.pos.printing.sunmi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.razstudio.pos.data.local.DrawerKick
import com.razstudio.pos.data.local.PaperWidth
import com.razstudio.pos.data.local.PrinterConfig
import com.razstudio.pos.data.local.PrinterTransport
import com.razstudio.pos.printing.BitmapTicketRenderer
import com.razstudio.pos.printing.DriverAvailability
import com.razstudio.pos.printing.DriverStatus
import com.razstudio.pos.printing.PrinterDriver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sunmi internal-printer driver. Binds to the Sunmi printer AIDL service and delegates all
 * print / drawer / status operations to it. (HW-REQ-2, HW-REQ-3, HW-REQ-5 — Tasks 2.1–2.3)
 *
 * Binding notes:
 * - The Intent MUST be explicit — `Intent(action).setPackage(pkg)`. An implicit Intent is
 *   refused on Android 5+ inside Sunmi's service host. The exact binding is verified via
 *   `dumpsys` against a real Loyverse install on this device. (Task 2.1)
 * - [availability] checks whether the package resolves on this device, so the driver
 *   auto-disables on a phone with no Sunmi hardware. (HW-REQ-1)
 *
 * Paper width detection (Task 2.3):
 * - After binding succeeds, [getPrinterPaper] is called once and the result updates
 *   [detectedPaperWidth]. PrinterDispatcher and any future UI can read this to avoid asking
 *   the café owner whether they loaded 58 mm or 80 mm paper.
 *
 * Status broadcasts (Task 2.3):
 * - [SunmiStatusReceiver] is registered on first bind and unregistered on [disconnectAll].
 *   The [status] method returns the most-recently received status.
 *
 * Cash drawer (Task 2.2):
 * - [openDrawer] delegates to the AIDL [openDrawer()] call — there is no ESC/POS `ESC p`
 *   in Sunmi's documented command set; the AIDL is the only path. (HW-REQ-3)
 * - [getDrawerStatus] gates the UI so a till without a drawer shows the feature disabled.
 */
@Singleton
class SunmiPrinterDriver @Inject constructor(
    @ApplicationContext private val context: Context
) : PrinterDriver {

    companion object {
        private const val TAG = "SunmiPrinterDriver"
        private const val SUNMI_SERVICE_ACTION  = "woyou.aidlservice.jiuiv5.IWoyouService"
        private const val SUNMI_SERVICE_PACKAGE = "woyou.aidlservice.jiuiv5"
        private const val BIND_TIMEOUT_MS       = 5_000L
        private const val PRINT_TIMEOUT_MS      = 15_000L
    }

    override val transport: PrinterTransport = PrinterTransport.SUNMI_AIDL

    // ── Service binding state ─────────────────────────────────────────────────

    private val bindMutex = Mutex()
    @Volatile private var woyouService: IWoyouServiceStub? = null

    /**
     * Last paper width detected by the hardware after the first successful bind.
     * Null until [getPrinterPaper] returns a valid value.
     */
    private val _detectedPaperWidth = MutableStateFlow<PaperWidth?>(null)
    val detectedPaperWidth: StateFlow<PaperWidth?> = _detectedPaperWidth.asStateFlow()

    /** Last known driver status, updated via [SunmiStatusReceiver]. */
    private val _lastStatus = MutableStateFlow(DriverStatus.UNKNOWN)

    private val statusReceiver = SunmiStatusReceiver { newStatus ->
        _lastStatus.value = newStatus
        Log.d(TAG, "Status updated: $newStatus")
    }

    @Volatile private var receiverRegistered = false

    // ── PrinterDriver API ─────────────────────────────────────────────────────

    /**
     * True when the Sunmi printer service package is installed on this device.
     * Returns [DriverAvailability.available]=false (with a reason) on any other Android device,
     * so the app behaves identically when running on a phone. (HW-REQ-1)
     */
    override suspend fun availability(context: Context): DriverAvailability {
        return try {
            context.packageManager.getPackageInfo(SUNMI_SERVICE_PACKAGE, 0)
            DriverAvailability(true)
        } catch (e: PackageManager.NameNotFoundException) {
            DriverAvailability(false, "Sunmi internal printer not detected on this device")
        }
    }

    /**
     * Print [markup] (DantSu-format string) to the Sunmi internal printer.
     *
     * Flow:
     * 1. Ensure the AIDL service is bound.
     * 2. Render [markup] → Bitmap via [BitmapTicketRenderer] at 576 px (80 mm head width).
     * 3. Enter printer buffer.
     * 4. Send the bitmap.
     * 5. Commit the buffer — the receipt arrives as one atomic unit. (Task 2.1)
     */
    override suspend fun print(config: PrinterConfig, markup: String) {
        val service = ensureConnected()

        // Use detected paper width when available; fall back to config's setting.
        val width = _detectedPaperWidth.value ?: config.paperWidth
        val dotWidth  = width.pixelWidth
        val charWidth = width.charWidth

        val bitmap = BitmapTicketRenderer.render(markup, dotWidth, charWidth)

        withTimeout(PRINT_TIMEOUT_MS) {
            service.enterPrinterBuffer(clean = 1)
            try {
                service.printBitmap(bitmap, dotWidth)
                // Feed clear of the head before cutting, or the cutter slices through the last
                // couple of lines — the tear bar sits below the print head, not level with it.
                service.lineWrap(3)
                service.commitPrinterBuffer()
                // Cut *after* the commit: inside the buffer it would be queued behind the bitmap
                // and, on models that ignore buffered cuts, dropped entirely.
                service.cutPaper()
                Log.d(TAG, "Print completed (${dotWidth}px, ${charWidth}ch/line)")
            } catch (e: Exception) {
                // Try to exit buffer mode on error so subsequent prints aren't stuck in buffer
                runCatching { service.commitPrinterBuffer() }
                throw e
            }
        }
    }

    /**
     * Open the cash drawer via the AIDL service.
     *
     * There is NO `ESC p` (0x1B 0x70) in Sunmi's documented command set; the drawer is
     * reachable ONLY through this AIDL call. ESC/POS drawer pulse is for Bluetooth printers
     * only. (HW-REQ-3, Task 2.2)
     *
     * Only calls [openDrawer] if [config.drawerKick] is [DrawerKick.SUNMI_AIDL]; otherwise
     * the method is silently skipped so a config without a drawer does nothing.
     */
    override suspend fun openDrawer(config: PrinterConfig) {
        if (config.drawerKick != DrawerKick.SUNMI_AIDL) return
        val service = ensureConnected()
        service.openDrawer()
        Log.d(TAG, "Cash drawer opened via AIDL")
    }

    /**
     * Disconnect the AIDL service binding and unregister the status receiver.
     * Re-binding happens automatically on the next [print] / [openDrawer] call.
     */
    override suspend fun disconnectAll() {
        bindMutex.withLock {
            doUnbind()
        }
    }

    /**
     * Returns the most recent [DriverStatus] received from [SunmiStatusReceiver].
     * UNKNOWN until the first broadcast arrives after binding.
     */
    override suspend fun status(config: PrinterConfig): DriverStatus = _lastStatus.value

    // ── Drawer query (public API for UI gating) ───────────────────────────────

    /**
     * Returns the drawer status from the hardware.
     * 0 = no drawer, 1 = closed, 2 = open, -1 = service not reachable.
     *
     * Gate the cash-drawer button in the UI on this: if 0 is returned, show the button
     * disabled so the café owner is not confused. (HW-REQ-3, Task 2.2)
     */
    suspend fun getDrawerStatus(): Int {
        return try {
            val service = ensureConnected()
            service.getDrawerStatus()
        } catch (e: Exception) {
            Log.w(TAG, "getDrawerStatus failed: ${e.message}")
            -1
        }
    }

    /**
     * Hardware counter of drawer openings since last reset.
     * Included on the closing report beside the cash total. (HW-REQ-3, Task 2.4)
     *
     * Returns -1 if the service is not reachable or the method is not available.
     */
    suspend fun getOpenDrawerTimes(): Int {
        return try {
            val service = ensureConnected()
            service.getOpenDrawerTimes()
        } catch (e: Exception) {
            Log.w(TAG, "getOpenDrawerTimes failed: ${e.message}")
            -1
        }
    }

    // ── Service connection ────────────────────────────────────────────────────

    /**
     * Return the live [IWoyouServiceStub], binding the service first if not already connected.
     * Thread-safe via [bindMutex]. Throws if the bind does not complete within [BIND_TIMEOUT_MS].
     */
    private suspend fun ensureConnected(): IWoyouServiceStub {
        // Fast path — already bound
        woyouService?.let { return it }

        return bindMutex.withLock {
            // Double-check after acquiring lock
            woyouService?.let { return@withLock it }

            withTimeout(BIND_TIMEOUT_MS) {
                doBindAndWait()
            }
        }
    }

    /**
     * Suspend until the AIDL service calls back via [ServiceConnection.onServiceConnected].
     */
    private suspend fun doBindAndWait(): IWoyouServiceStub =
        suspendCancellableCoroutine { cont ->
            val intent = Intent(SUNMI_SERVICE_ACTION).apply {
                setPackage(SUNMI_SERVICE_PACKAGE)
            }

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    Log.d(TAG, "Sunmi printer service connected: $name")
                    val stub = IWoyouServiceStub(service)
                    woyouService = stub

                    // Detect paper width once on connect and cache it
                    runCatching {
                        val paper = stub.getPrinterPaper()
                        _detectedPaperWidth.value = when (paper) {
                            1 -> PaperWidth.FIFTY_EIGHT_MM
                            2 -> PaperWidth.EIGHTY_MM
                            else -> null
                        }
                        if (_detectedPaperWidth.value == null) {
                            // Observed on the D3 Mini: code 0, meaning the service does not
                            // report a width. Falling back to the configured value is correct —
                            // but say so, because "detection works" was an assumption elsewhere.
                            Log.w(TAG, "getPrinterPaper()=$paper is not 1(58mm)/2(80mm) — " +
                                "using the width configured on the printer instead")
                        } else {
                            Log.d(TAG, "Detected paper width code=$paper → ${_detectedPaperWidth.value}")
                        }
                    }

                    // Register status broadcasts on first successful bind
                    if (!receiverRegistered) {
                        statusReceiver.register(context)
                        receiverRegistered = true
                    }

                    if (cont.isActive) cont.resume(stub)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    Log.w(TAG, "Sunmi printer service disconnected: $name")
                    woyouService = null
                    // Leave the status receiver registered — it handles reconnect tracking
                }
            }

            val bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            if (!bound) {
                cont.resumeWithException(
                    IllegalStateException(
                        "bindService returned false — Sunmi printer service not available. " +
                        "Is this a Sunmi device? (package=$SUNMI_SERVICE_PACKAGE)"
                    )
                )
            }

            cont.invokeOnCancellation {
                // If the coroutine is cancelled before onServiceConnected fires, unbind cleanly
                runCatching { context.unbindService(conn) }
            }
        }

    /**
     * Unbind the service and reset state. Must be called inside [bindMutex].
     */
    private fun doUnbind() {
        woyouService = null
        if (receiverRegistered) {
            statusReceiver.unregister(context)
            receiverRegistered = false
        }
        Log.d(TAG, "Sunmi driver unbound")
    }
}
