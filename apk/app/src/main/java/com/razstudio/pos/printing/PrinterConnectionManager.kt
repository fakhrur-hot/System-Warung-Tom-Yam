package com.razstudio.pos.printing

import android.content.Context
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.textparser.PrinterTextParserImg
import com.razstudio.pos.data.local.PaperWidth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the lifecycle of Bluetooth printer connections so the app can keep a warm, persistent
 * link during service instead of reconnecting for every slip (Bluetooth reconnect can take
 * several seconds and sometimes fails mid-service).
 *
 * Two modes, chosen by the admin (stored device-locally — connection strategy is per-device
 * hardware behavior, not café-wide config):
 *
 * - **Fast** (default): after a print the socket stays open and a keep-alive heartbeat is
 *   written every [KEEP_ALIVE_INTERVAL_MS] so the OS/printer don't tear down the idle link.
 *   The next print is instant. Cached by MAC address, so no rescan of paired devices.
 * - **Eco**: the socket is disconnected after [ECO_IDLE_DISCONNECT_MS] of no prints, saving
 *   battery; the next print reconnects on demand.
 *
 * All socket access is guarded by [mutex] so a print, a keep-alive ping, and a disconnect can
 * never touch the same connection concurrently. It's a coroutine [Mutex] (not `synchronized`) so a
 * slow BT connect/transfer suspends the waiting coroutine instead of blocking an OS thread.
 */
@Singleton
class PrinterConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val printSettingsStore: com.razstudio.pos.data.local.PrintSettingsStore,
    private val secureStorage: com.razstudio.pos.data.SecureStorage
) {
    companion object {
        const val MODE_FAST = "fast"
        const val MODE_ECO = "eco"

        /** Sentinel a receipt payload carries (from ReceiptDocument) to request the logo header. */
        const val RECEIPT_LOGO_MARKER = "RECEIPT_LOGO"

        private const val PREFS = "printer_conn_prefs"
        private const val KEY_MODE = "keep_alive_mode"

        private const val KEEP_ALIVE_INTERVAL_MS = 15_000L
        private const val ECO_IDLE_DISCONNECT_MS = 60_000L

        // ESC @  (initialize printer) — a harmless no-op that doesn't expect a response,
        // safe on cheap 58mm units. Replaces the old DLE EOT status-request bytes which
        // caused some printers to print blank lines or block waiting for a status reply.
        private val KEEP_ALIVE_BYTES = byteArrayOf(0x1B, 0x40)
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // Coroutine-friendly Mutex: suspends callers instead of blocking an OS thread,
    // so a 3-second BT connect doesn't hold a thread hostage.
    private val mutex = Mutex()

    /** Live connections cached by MAC address. */
    private val connections = mutableMapOf<String, BluetoothConnection>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var keepAliveJob: Job? = null
    private val ecoDisconnectJobs = mutableMapOf<String, Job>()

    /**
     * ONLY the Main Admin device (the printer host) may touch the Bluetooth stack. Ordering-staff
     * and secondary-admin devices must never open a socket, connect, or run the keep-alive — every
     * BT entry point below short-circuits when this is false. This is the hard guarantee that an
     * ordering device runs no Bluetooth activity at all.
     */
    private fun isPrinterHost(): Boolean =
        secureStorage.getRole() == com.razstudio.pos.data.SecureStorage.Role.ADMIN

    fun getMode(): String = prefs.getString(KEY_MODE, MODE_FAST) ?: MODE_FAST

    fun setMode(mode: String) {
        prefs.edit().putString(KEY_MODE, mode).apply()
        if (mode == MODE_FAST) {
            startKeepAlive()
        }
        // Eco: keep-alive loop no-ops itself while the mode is eco; existing links idle out.
    }

    /**
     * Print [payload] to the printer at [macAddress], reusing the cached warm connection when
     * possible (fast) or connecting on demand (eco). Throws on connect/print failure so the
     * caller's retry/FAILED handling still applies.
     *
     * Uses a coroutine [Mutex] instead of `synchronized` so a slow BT connect/transfer
     * suspends the coroutine rather than blocking an IO thread for 1-5 seconds.
     */
    suspend fun print(macAddress: String, printerName: String, paperWidth: PaperWidth, payload: String) {
        // Ordering-staff / secondary-admin devices never print locally — no BT socket is ever opened.
        if (!isPrinterHost()) return
        mutex.withLock {
            val connection = ensureConnected(macAddress, printerName)

            val dpi = 203
            val printingWidthMM = if (paperWidth == PaperWidth.FIFTY_EIGHT_MM) 48f else 72f
            // Reuse the persistent connection; EscPosPrinter's constructor connect() is a
            // no-op when the connection is already open, so this doesn't drop the link.
            val escPosPrinter = EscPosPrinter(connection, dpi, printingWidthMM, paperWidth.charWidth)
            // Force ESC * bit-image mode for bitmap printing on printers that don't implement
            // GS v 0 raster (common on cheap 58mm units — logos/slips come out blank otherwise).
            escPosPrinter.useEscAsteriskCommand(printSettingsStore.getEscAsteriskImageMode())

            // Optional receipt logo header: ReceiptDocument injects RECEIPT_LOGO_MARKER when the
            // "logo on receipt" option is on. Encode the bundled logo via the library's own image
            // converter and place it (centered) above the rest — works for both print paths.
            val hasLogo = payload.contains(RECEIPT_LOGO_MARKER)
            val body = payload.replace(RECEIPT_LOGO_MARKER, "").trimStart('\n')
            val logoMarkup = if (hasLogo) {
                loadReceiptLogo(paperWidth.pixelWidth)?.let { logo ->
                    val hex = PrinterTextParserImg.bitmapToHexadecimalString(escPosPrinter, logo)
                    logo.recycle()
                    "[C]<img>$hex</img>\n"
                } ?: ""
            } else ""

            // Multilingual fallback: if the body contains scripts the printer can't render as
            // text (Chinese/Tamil/Thai), rasterize it to a monochrome bitmap and print it as an
            // image via the library's own encoder. Latin-only bodies take the fast text path.
            val bodyMarkup = if (BitmapTicketRenderer.needsBitmap(body)) {
                val bmp = BitmapTicketRenderer.render(body, paperWidth.pixelWidth, paperWidth.charWidth)
                val hex = PrinterTextParserImg.bitmapToHexadecimalString(escPosPrinter, bmp)
                bmp.recycle()
                "[C]<img>$hex</img>\n"
            } else {
                body
            }
            escPosPrinter.printFormattedTextAndCut(logoMarkup + bodyMarkup)

            when (getMode()) {
                MODE_FAST -> {
                    ecoDisconnectJobs.remove(macAddress)?.cancel()
                    startKeepAlive()
                }
                else -> scheduleEcoDisconnect(macAddress)
            }
        }
    }

    /** Get the cached connection if still live, otherwise (re)connect fresh. Caller holds [mutex]. */
    private fun ensureConnected(mac: String, name: String): BluetoothConnection {
        connections[mac]?.let { existing ->
            if (existing.isConnected) return existing
            try { existing.disconnect() } catch (_: Exception) {}
            connections.remove(mac)
        }
        // Resolve the paired device straight from its MAC — no scan of every bonded peripheral
        // (headphones, watch, car…), which is slow and unpredictable on well-used phones.
        // getRemoteDevice() just builds a handle; it does no I/O and never scans.
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter
            ?: throw RuntimeException("Bluetooth is unavailable on this device")
        if (!adapter.isEnabled) {
            throw RuntimeException("Bluetooth is off — turn it on to print to $name")
        }
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            throw RuntimeException("Invalid printer address for $name ($mac)")
        }
        val fresh = BluetoothConnection(device)
        fresh.connect()
        if (!fresh.isConnected) {
            throw RuntimeException("Printer $name ($mac) not reachable — ensure it is powered on and paired")
        }
        connections[mac] = fresh
        return fresh
    }

    private fun startKeepAlive() {
        // Non-host devices (ordering staff, secondary admin) never run the Bluetooth keep-alive loop.
        if (!isPrinterHost()) return
        if (keepAliveJob?.isActive == true) return
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                if (getMode() != MODE_FAST) continue
                mutex.withLock {
                    val iterator = connections.entries.iterator()
                    while (iterator.hasNext()) {
                        val (_, conn) = iterator.next()
                        try {
                            if (conn.isConnected) {
                                conn.write(KEEP_ALIVE_BYTES)
                            } else {
                                iterator.remove()
                            }
                        } catch (_: Exception) {
                            try { conn.disconnect() } catch (_: Exception) {}
                            iterator.remove()
                        }
                    }
                }
            }
        }
    }

    /**
     * The café's own uploaded logo (Settings → Café Profile → Change Logo) if one exists, else the
     * bundled default (res/raw/qr_default_logo — the same one the QR-card generator uses), scaled
     * to a tasteful header width (~55% of the head), preserving aspect. Returns null on any failure
     * so a receipt still prints without a logo.
     */
    private fun loadReceiptLogo(pixelWidth: Int): android.graphics.Bitmap? = try {
        val raw = com.razstudio.pos.ui.util.LogoPipeline.loadJpegFromInternal(context)
            ?: android.graphics.BitmapFactory.decodeResource(
                context.resources, com.razstudio.pos.R.raw.qr_default_logo
            )
        when {
            raw == null -> null
            raw.width <= (pixelWidth * 0.55f).toInt() -> raw
            else -> {
                val targetW = (pixelWidth * 0.55f).toInt().coerceAtLeast(1)
                val targetH = (raw.height * targetW.toFloat() / raw.width).toInt().coerceAtLeast(1)
                android.graphics.Bitmap.createScaledBitmap(raw, targetW, targetH, true)
                    .also { if (it !== raw) raw.recycle() }
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun scheduleEcoDisconnect(mac: String) {
        ecoDisconnectJobs.remove(mac)?.cancel()
        ecoDisconnectJobs[mac] = scope.launch {
            delay(ECO_IDLE_DISCONNECT_MS)
            disconnect(mac)
        }
    }
    /** Close and evict the connection for [mac] (best-effort). */
    suspend fun disconnect(mac: String) {
        mutex.withLock {
            connections.remove(mac)?.let { try { it.disconnect() } catch (_: Exception) {} }
            ecoDisconnectJobs.remove(mac)?.cancel()
        }
    }

    /**
     * Close ALL printer connections and stop all background Bluetooth activity (keep-alive
     * heartbeat + pending eco-disconnects). Used on sign-out so a signed-out app holds no
     * Bluetooth link and does no printer chatter. Connections re-open on demand on the next print.
     */
    suspend fun disconnectAll() {
        mutex.withLock {
            keepAliveJob?.cancel()
            keepAliveJob = null
            ecoDisconnectJobs.values.forEach { it.cancel() }
            ecoDisconnectJobs.clear()
            connections.values.forEach { try { it.disconnect() } catch (_: Exception) {} }
            connections.clear()
        }
    }
}
