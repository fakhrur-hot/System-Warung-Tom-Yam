package com.razstudio.pos.printing

import android.content.Context
import com.razstudio.pos.data.local.PrinterConfig
import com.razstudio.pos.data.local.PrinterTransport

/**
 * One compiled-in peripheral driver. Selected at runtime from [PrinterTransport] stored on
 * [PrinterConfig]. No reflection, no dynamic loading, no build variants.
 *
 * Drivers receive the markup string the document renderers already produce (DantSu format:
 * "[C]<b>…"), never transport-specific bytes — so BitmapTicketRenderer stays the single
 * multilingual rendering path and Chinese/Tamil/Thai receipts work on any new transport for free.
 * (HW-REQ-1, designs D5)
 */
interface PrinterDriver {
    /** The transport type this driver handles. */
    val transport: PrinterTransport

    /** True when this driver's hardware is reachable on this device right now. */
    suspend fun availability(context: Context): DriverAvailability

    /** Print [markup] to the printer described by [config]. Throws on failure. */
    suspend fun print(config: PrinterConfig, markup: String)

    /** Open the cash drawer associated with [config], if applicable. Default: no-op. */
    suspend fun openDrawer(config: PrinterConfig): Unit = Unit

    /** Disconnect any persistent connections for [config]. Default: no-op. */
    suspend fun disconnect(config: PrinterConfig): Unit = Unit

    /** Disconnect ALL connections managed by this driver. Default: no-op. */
    suspend fun disconnectAll(): Unit = Unit

    /** Current driver/hardware status. Default: UNKNOWN. */
    suspend fun status(config: PrinterConfig): DriverStatus = DriverStatus.UNKNOWN
}

data class DriverAvailability(
    val available: Boolean,
    val reason: String? = null   // Human-readable reason when unavailable; null when available
)

enum class DriverStatus { ONLINE, OFFLINE, OUT_OF_PAPER, COVER_OPEN, OVERHEATING, UNKNOWN }
