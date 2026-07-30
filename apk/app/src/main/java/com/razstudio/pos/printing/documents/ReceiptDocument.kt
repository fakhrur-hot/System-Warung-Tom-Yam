package com.razstudio.pos.printing.documents

import android.content.Context
import com.razstudio.pos.data.local.Order
import com.razstudio.pos.data.local.OrderItem
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Generates DantSu-formatted text payload for customer receipt printing.
 *
 * Includes:
 * - Pre-rendered 1-bit monochrome logo (from LogoPipeline)
 * - Café branding header
 * - All consolidated order items at snapshotted prices
 * - Total, payment method, bilingual thank-you message
 *
 * Labels support EN (English) and BM (Bahasa Melayu).
 */
object ReceiptDocument {

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a")

    /**
     * Generate the receipt formatted text for DantSu's printFormattedText.
     *
     * @param context Android context for loading the monochrome logo from internal storage
     * @param order The completed order
     * @param items All consolidated order items (at snapshotted prices)
     * @param paymentMethod "CASH" or "QR"
     * @param cafeName Branding café name
     * @param charWidth Character width (32 for 58mm, 48 for 80mm)
     * @param pixelWidth Pixel width for images (384 for 58mm, 576 for 80mm)
     * @param printLanguage "EN" or "BM"
     * @return Formatted text string for DantSu library
     */
    fun generate(
        context: Context,
        order: Order,
        items: List<OrderItem>,
        paymentMethod: String,
        cafeName: String,
        charWidth: Int,
        pixelWidth: Int,
        printLanguage: String,
        timezone: String,
        tableName: String,
        printLogo: Boolean = false
    ): String {
        val s = printStrings(printLanguage)
        val separator = "=".repeat(charWidth)
        val sb = StringBuilder()

        // Optional logo header: a sentinel the connection manager swaps for the encoded bundled
        // logo image, placed above the café name. Kept out of the text here because DantSu's <img>
        // needs its own encoder (only available where the printer instance lives).
        if (printLogo) {
            sb.appendLine(com.razstudio.pos.printing.PrinterConnectionManager.RECEIPT_LOGO_MARKER)
        }

        // NOTE: The café logo is intentionally NOT emitted here. LogoPipeline stores a
        // raw 1-bit raster, but DantSu's <img> tag expects its own encoding (a
        // width/height header + raster from PrinterTextParserImg.bitmapToHexadecimalString).
        // Feeding the raw raster hex made DantSu read bogus image dimensions and emit an
        // endless stream of garbage — looked like a printer "factory test". Text-only
        // receipt until the logo is re-encoded via the library's own bitmap converter.

        // Café name header
        sb.appendLine("[C]<b>$cafeName</b>")
        sb.appendLine("[L]")
        sb.appendLine("[L]$separator")

        // Table and date — createdAt is stored as UTC ISO; render it in the café timezone
        // so the receipt matches the kitchen slip and reports (previously it showed UTC).
        val tableLabel = s.table
        val dateLabel = s.date
        val zone = try {
            if (timezone.isBlank()) ZoneId.systemDefault() else ZoneId.of(timezone)
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }
        val dateStr = try {
            ZonedDateTime.parse(order.createdAt).withZoneSameInstant(zone).format(DATE_FORMAT)
        } catch (_: Exception) {
            order.createdAt
        }

        sb.appendLine("[L]$tableLabel: $tableName")
        sb.appendLine("[L]$dateLabel: $dateStr")
        sb.appendLine("[L]$separator")

        // Items with right-aligned prices
        for (item in items) {
            val lineTotal = item.unitPriceSnapshot * item.quantity
            val itemText = "${item.quantity}x ${com.razstudio.pos.util.MenuName.display(item.nameSnapshot)}"
            val priceText = formatPrice(lineTotal)
            val paddedLine = padRight(itemText, priceText, charWidth)
            sb.appendLine("[L]$paddedLine")
        }

        // Total
        sb.appendLine("[L]$separator")
        val totalLabel = s.total
        val totalText = formatPrice(order.total)
        val totalLine = padRight(totalLabel, totalText, charWidth)
        sb.appendLine("[L]$totalLine")

        // Payment method
        val paymentLabel = s.payment
        val paymentDisplay = when {
            paymentMethod.equals("CASH", ignoreCase = true) -> s.cash
            else -> "QR"
        }
        sb.appendLine("[L]$paymentLabel: $paymentDisplay")
        sb.appendLine("[L]$separator")

        // Thank you (localized). Emphasized (taller) so the studio footer below reads smaller.
        sb.appendLine("[C]<font size='tall'>${s.thankYou}</font>")

        // Always-on studio footer at the very bottom, two neat centered lines.
        // NOTE: thermal printers (via the DantSu library) print upright — there's no italic —
        // and the library's font tags don't expose a sub-"normal" size, so this is rendered at
        // the smallest reliable size (normal), one step below the enlarged thank-you line above.
        sb.appendLine("[L]")
        sb.appendLine("[C]Zero-Commitment POS by RAZStudio")
        sb.appendLine("[C]011-32605406")

        // Feed lines for paper cut
        sb.appendLine("[L]")
        sb.appendLine("[L]")
        sb.appendLine("[L]")

        return sb.toString()
    }

    /**
     * Format a Double price as "RMx.xx"
     */
    private fun formatPrice(amount: Double): String {
        return "RM%.2f".format(amount)
    }

    /**
     * Pad a left-aligned text and a right-aligned text to fill the given width.
     * If the combined length exceeds width, truncate the left part.
     */
    private fun padRight(left: String, right: String, width: Int): String {
        val gap = width - left.length - right.length
        return if (gap > 0) {
            left + " ".repeat(gap) + right
        } else {
            // Truncate left text if too long
            val maxLeft = width - right.length - 1
            if (maxLeft > 0) {
                left.take(maxLeft) + " " + right
            } else {
                "$left $right"
            }
        }
    }
}
