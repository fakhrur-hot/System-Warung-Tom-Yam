package com.warungtomyam.pos.printing.documents

import com.warungtomyam.pos.data.local.OrderItem
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Generates DantSu-formatted text payload for kitchen slip printing.
 *
 * Supports:
 * - First send: table number + unsent items + notes + timestamp
 * - Delta (amendment) send: "TAMBAHAN / ADDED" header + new items only
 * - Bilingual labels: EN (English) / BM (Bahasa Melayu)
 *
 * All timestamps are rendered in the café's configured timezone (passed in) so the
 * kitchen slip, customer receipt, and reports all agree — previously the slip used the
 * device's local time while the receipt showed UTC.
 */
object KitchenSlipDocument {

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("hh:mm:ss a")

    /**
     * Print a prominent "Session #N" line so the kitchen can distinguish each order round
     * for the same table. Emitted only when a positive [sessionNumber] is supplied.
     */
    private fun appendSessionLine(sb: StringBuilder, sessionNumber: Int?, printLanguage: String) {
        if (sessionNumber == null || sessionNumber <= 0) return
        val label = if (printLanguage == "BM") "Sesi #$sessionNumber" else "Session #$sessionNumber"
        sb.appendLine("[C]<b><font size='tall'>$label</font></b>")
    }

    /** Resolve a timezone id to a ZoneId, falling back to the device zone if invalid/blank. */
    private fun zoneOf(timezone: String): ZoneId =
        try {
            if (timezone.isBlank()) ZoneId.systemDefault() else ZoneId.of(timezone)
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }

    /**
     * Strips DantSu ESC/POS markup control sequences from user-supplied text
     * to prevent injection of formatting tags into print output.
     * Removes [C], [L], [R], [J], <b>, <u>, <img>, <qrcode> etc.
     */
    private fun sanitize(input: String): String {
        return input
            .replace(Regex("\\[\\s*[CLRJ]\\s*\\]"), "")          // [C] [L] [R] [J]
            .replace(Regex("</?\\s*(b|u|i|s|font|img|qrcode|barcode|cut)[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace("[", "(")   // catch any remaining [ just in case
            .replace("]", ")")
            .trim()
    }

    /**
     * Generate the kitchen slip formatted text for DantSu's printFormattedText.
     *
     * @param tableId Table identifier (e.g. "T3")
     * @param items The exact items to print — callers pass only the round they want
     * printed (a fresh order's items, one amendment round's delta, or the full order
     * for an explicit reprint); this no longer filters by sentToKitchen itself, since
     * every item is marked sent the instant it's placed/added.
     * @param isAmendment True if this is a re-send (delta slip with "ADDED" header)
     * @param charWidth Character width of the target printer (32 for 58mm, 48 for 80mm)
     * @param printLanguage "EN" or "BM"
     * @return Formatted text string for DantSu library
     */
    fun generate(
        tableId: String,
        items: List<OrderItem>,
        isAmendment: Boolean,
        charWidth: Int,
        printLanguage: String,
        timezone: String,
        sessionNumber: Int? = null
    ): String {
        if (items.isEmpty()) return ""

        val separator = "-".repeat(charWidth)
        val tableLabel = if (printLanguage == "BM") "Meja" else "Table"
        val timestamp = ZonedDateTime.now(zoneOf(timezone)).format(TIME_FORMAT)

        val sb = StringBuilder()

        // Amendment header
        if (isAmendment) {
            sb.appendLine("[C]<b>TAMBAHAN / ADDED</b>")
        }

        // Big table number
        sb.appendLine("[C]<b><font size='big'>$tableLabel ${sanitize(tableId)}</font></b>")
        // Session marker — lets the kitchen tell each order round apart so the newly-placed
        // round isn't confused with earlier rounds already cooked/served for the same table.
        appendSessionLine(sb, sessionNumber, printLanguage)
        sb.appendLine("[L]")
        sb.appendLine("[L]$separator")

        // Items
        for (item in items) {
            sb.appendLine("[L]<b>${item.quantity}x ${sanitize(com.warungtomyam.pos.util.MenuName.display(item.nameSnapshot))}</b>")
            if (!item.note.isNullOrBlank()) {
                sb.appendLine("[L]  Note: ${sanitize(item.note!!)}")
            }
        }

        // Footer
        sb.appendLine("[L]$separator")
        sb.appendLine("[L]$timestamp")
        sb.appendLine("[L]")
        sb.appendLine("[L]")

        return sb.toString()
    }

    /**
     * Generate separate kitchen slips grouped by category.
     * Returns a map of category → formatted text. Each entry becomes a separate print job.
     *
     * @param tableId Table identifier (e.g. "T3")
     * @param items The exact items to print — see [generate] for the filtering contract
     * (callers pass only the round they want printed; no internal sentToKitchen filter).
     * @param isAmendment True if this is a re-send (delta slip with "ADDED" header)
     * @param charWidth Character width of the target printer (32 for 58mm, 48 for 80mm)
     * @param printLanguage "EN" or "BM"
     * @return Map of category to formatted text string for DantSu library
     */
    fun generatePerCategory(
        tableId: String,
        items: List<OrderItem>,
        isAmendment: Boolean,
        charWidth: Int,
        printLanguage: String,
        timezone: String,
        sessionNumber: Int? = null,
        menuFontSize: String = "S"
    ): Map<String, String> {
        if (items.isEmpty()) return emptyMap()

        val menuSize = com.warungtomyam.pos.data.local.KitchenFontSize.menu(menuFontSize)
        val noteSize = com.warungtomyam.pos.data.local.KitchenFontSize.note(menuFontSize)

        val grouped = items.groupBy { it.categorySnapshot }
        val result = mutableMapOf<String, String>()

        for ((category, categoryItems) in grouped) {
            val separator = "-".repeat(charWidth)
            val tableLabel = if (printLanguage == "BM") "Meja" else "Table"
            val timestamp = ZonedDateTime.now(zoneOf(timezone)).format(TIME_FORMAT)
            val categoryLabel = when (category.uppercase()) {
                "FOOD" -> if (printLanguage == "BM") "Makanan" else "Food"
                "BEVERAGES" -> if (printLanguage == "BM") "Minuman" else "Beverages"
                "SIDE_DISHES", "SIDE DISHES" -> if (printLanguage == "BM") "Lauk" else "Side Dishes"
                "OTHERS" -> if (printLanguage == "BM") "Lain-lain" else "Others"
                else -> category
            }

            val sb = StringBuilder()

            // Amendment header
            if (isAmendment) {
                sb.appendLine("[C]<b>TAMBAHAN / ADDED</b>")
            }

            // Big table number
            sb.appendLine("[C]<b><font size='big'>$tableLabel $tableId</font></b>")
            // Session marker (see [generate]).
            appendSessionLine(sb, sessionNumber, printLanguage)
            // Category header
            sb.appendLine("[C]<b>[$categoryLabel]</b>")
            sb.appendLine("[L]")
            sb.appendLine("[L]$separator")

            // Items — menu text at the admin-chosen size; note one level smaller (capped at L).
            for (item in categoryItems) {
                sb.appendLine("[L]<b><font size='$menuSize'>${item.quantity}x ${com.warungtomyam.pos.util.MenuName.display(item.nameSnapshot)}</font></b>")
                if (!item.note.isNullOrBlank()) {
                    sb.appendLine("[L]<font size='$noteSize'>  Note: ${item.note}</font>")
                }
            }

            // Footer
            sb.appendLine("[L]$separator")
            sb.appendLine("[L]$timestamp")
            sb.appendLine("[L]")
            sb.appendLine("[L]")

            result[category] = sb.toString()
        }

        return result
    }

    /**
     * Generate kitchen slips grouped into at most TWO buckets — FOOD and BEVERAGE — so a
     * single order prints one Food slip and one Beverage slip regardless of how many menu
     * categories it spans. [routeOf] maps a category name to its bucket ("FOOD"/"BEVERAGE").
     * Within a bucket the items are sub-grouped by their category for the kitchen's clarity.
     * Returns a map of bucket → formatted slip; the caller routes each bucket to its printer.
     */
    fun generateByRoute(
        tableId: String,
        items: List<OrderItem>,
        isAmendment: Boolean,
        charWidth: Int,
        printLanguage: String,
        timezone: String,
        sessionNumber: Int? = null,
        menuFontSize: String = "M",
        routeOf: (String) -> String
    ): Map<String, String> {
        if (items.isEmpty()) return emptyMap()

        val menuSize = com.warungtomyam.pos.data.local.KitchenFontSize.menu(menuFontSize)
        val noteSize = com.warungtomyam.pos.data.local.KitchenFontSize.note(menuFontSize)
        val tableLabel = if (printLanguage == "BM") "Meja" else "Table"
        val separator = "-".repeat(charWidth)
        val timestamp = ZonedDateTime.now(zoneOf(timezone)).format(TIME_FORMAT)

        val byBucket = items.groupBy { if (routeOf(it.categorySnapshot) == "BEVERAGE") "BEVERAGE" else "FOOD" }
        val result = mutableMapOf<String, String>()

        for ((bucket, bucketItems) in byBucket) {
            val bucketLabel = when (bucket) {
                "BEVERAGE" -> if (printLanguage == "BM") "MINUMAN" else "BEVERAGES"
                else -> if (printLanguage == "BM") "MAKANAN" else "FOOD"
            }

            val sb = StringBuilder()
            if (isAmendment) sb.appendLine("[C]<b>TAMBAHAN / ADDED</b>")
            sb.appendLine("[C]<b><font size='big'>$tableLabel $tableId</font></b>")
            appendSessionLine(sb, sessionNumber, printLanguage)
            sb.appendLine("[C]<b><font size='tall'>[$bucketLabel]</font></b>")
            sb.appendLine("[L]")
            sb.appendLine("[L]$separator")

            // Sub-group by category within the bucket for the kitchen's reference.
            for ((category, catItems) in bucketItems.groupBy { it.categorySnapshot }) {
                if (category.isNotBlank()) sb.appendLine("[L]<b>[$category]</b>")
                for (item in catItems) {
                    sb.appendLine("[L]<b><font size='$menuSize'>${item.quantity}x ${com.warungtomyam.pos.util.MenuName.display(item.nameSnapshot)}</font></b>")
                    if (!item.note.isNullOrBlank()) {
                        sb.appendLine("[L]<font size='$noteSize'>  Note: ${item.note}</font>")
                    }
                }
            }

            sb.appendLine("[L]$separator")
            sb.appendLine("[L]$timestamp")
            sb.appendLine("[L]")
            sb.appendLine("[L]")

            result[bucket] = sb.toString()
        }

        return result
    }
}
