package com.warungtomyam.pos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a configured Bluetooth thermal printer.
 * Supports multi-printer setups: separate kitchen and receipt printers,
 * or a single printer handling both roles.
 */
@Entity(tableName = "printer_configs")
data class PrinterConfig(
    @PrimaryKey val id: String,           // UUID
    val name: String,                      // User-given name e.g. "Kitchen Printer"
    val macAddress: String,                // BT MAC e.g. "00:11:22:33:44:55"
    val paperWidth: PaperWidth,            // FIFTY_EIGHT_MM or EIGHTY_MM
    val printerRole: PrinterRole,          // RECEIPT_ONLY, KITCHEN_ONLY, BOTH
    val isActive: Boolean = true,
    val categoryFilter: String? = null     // Reserved for future per-category routing
)

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
