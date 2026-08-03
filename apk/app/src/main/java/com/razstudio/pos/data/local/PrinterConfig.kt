package com.razstudio.pos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a configured thermal printer.
 * Supports multi-printer setups: separate kitchen and receipt printers,
 * or a single printer handling both roles.
 *
 * Version 16: `macAddress` renamed to `address` (nullable for non-Bluetooth transports) and
 * two new fields added — `transport` (defaults BLUETOOTH for all existing rows) and `drawerKick`
 * (defaults NONE).  Existing Bluetooth cafés keep their MAC in `address` and behave identically.
 */
@Entity(tableName = "printer_configs")
data class PrinterConfig(
    @PrimaryKey val id: String,               // UUID
    val name: String,                          // User-given name e.g. "Kitchen Printer"
    val address: String?,                      // BT MAC / IP / null for internal drivers
    val transport: PrinterTransport,           // Default BLUETOOTH for all existing rows
    val drawerKick: DrawerKick,                // Default NONE for all existing rows
    val paperWidth: PaperWidth,               // FIFTY_EIGHT_MM or EIGHTY_MM
    val printerRole: PrinterRole,             // RECEIPT_ONLY, KITCHEN_ONLY, BOTH
    val isActive: Boolean = true,
    val categoryFilter: String? = null        // Comma-separated categories for kitchen routing
)

/**
 * Transport discriminator — which hardware path the driver uses to reach the printer.
 */
/**
 * Sunmi terminals register their **built-in thermal printer as a bonded Bluetooth device**, named
 * `InnerPrinter` on a placeholder MAC. Observed on a D3 Mini:
 *
 * ```
 * $ adb shell dumpsys bluetooth_manager
 *   Bonded devices:
 *     00:11:22:33:44:55 [ ???? ] InnerPrinter
 * ```
 *
 * It is not a real Bluetooth peripheral: the RFCOMM path carries print bytes only, so a printer
 * stored as `BLUETOOTH` loses `openDrawer()`, paper detection and the hardware status broadcasts —
 * all of which live on the AIDL — and needs `BLUETOOTH_CONNECT`, which Sunmi terminals commonly
 * have denied.
 *
 * It is nonetheless **left visible in the Bluetooth scan**, because that is where a café looks for
 * it and hiding it made the built-in printer undiscoverable. This matcher is what lets the entry
 * stay where it is useful while `PrintersViewModel.addPrinter` quietly stores it on
 * [PrinterTransport.SUNMI_AIDL] instead. (designs.md H9, H10)
 */
object SunmiInnerPrinter {
    const val BONDED_NAME = "InnerPrinter"
    const val PLACEHOLDER_MAC = "00:11:22:33:44:55"

    /** True when a scanned Bluetooth entry is really the Sunmi internal printer. */
    fun matches(name: String?, macAddress: String?): Boolean =
        name.equals(BONDED_NAME, ignoreCase = true) ||
            macAddress.equals(PLACEHOLDER_MAC, ignoreCase = true)
}

enum class PrinterTransport {
    BLUETOOTH,
    SUNMI_AIDL,
    USB,
    NETWORK
}

/**
 * How (and whether) this printer config opens a cash drawer.
 */
enum class DrawerKick {
    NONE,
    ESC_POS_RJ11,
    SUNMI_AIDL
}

/**
 * Paper width enum with character and pixel widths for ESC/POS formatting.
 * - 58mm: 32 chars per line, 384px image width
 * - 80mm: 48 chars per line, 576px image width
 */
enum class PaperWidth(val charWidth: Int, val pixelWidth: Int) {
    FIFTY_EIGHT_MM(32, 384),
    EIGHTY_MM(48, 576)
}

/**
 * Printer role determines which documents are routed to this printer.
 */
enum class PrinterRole {
    RECEIPT_ONLY,
    KITCHEN_ONLY,
    BOTH
}
