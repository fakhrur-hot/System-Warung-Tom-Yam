package com.warungtomyam.pos.printing

import android.content.Context
import com.warungtomyam.pos.data.SecureStorage
import com.warungtomyam.pos.data.local.Order
import com.warungtomyam.pos.data.local.OrderItem
import com.warungtomyam.pos.data.local.PaperWidth
import com.warungtomyam.pos.data.local.PrinterConfigDao
import com.warungtomyam.pos.data.local.PrinterRole
import com.warungtomyam.pos.data.local.SettingsDao
import com.warungtomyam.pos.data.local.TableDao
import com.warungtomyam.pos.printing.documents.KitchenSlipDocument
import com.warungtomyam.pos.printing.documents.ReceiptDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level print service that generates formatted document payloads
 * and dispatches them to the correct printer via [PrinterDispatcher].
 *
 * Resolves printer dimensions (charWidth/pixelWidth) from the target printer config
 * and the print language from Room settings.
 */
@Singleton
class PrintService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val printerDispatcher: PrinterDispatcher,
    private val printerConfigDao: PrinterConfigDao,
    private val settingsDao: SettingsDao,
    private val tableDao: TableDao,
    private val secureStorage: SecureStorage,
    private val printSettingsStore: com.warungtomyam.pos.data.local.PrintSettingsStore,
    private val menuCategoryStore: com.warungtomyam.pos.data.local.MenuCategoryStore
) {

    // A secondary-admin device has no local printer — all its slips/receipts are printed by
    // the Main Admin (its orders reach it via the same broadcast/poll as staff orders). Guard
    // every print entry point so a secondary admin never prints locally (and never spams
    // "no printer configured" alerts).
    private fun isPrinterHost(): Boolean =
        secureStorage.getRole() != SecureStorage.Role.ADMIN_SECONDARY

    /**
     * Generate and dispatch kitchen slips to the kitchen printer.
     * Produces one slip per category (food, beverages, etc.) so each station
     * receives only its relevant items.
     *
     * @param tableId The table identifier (e.g. "T3")
     * @param items All order items (only unsent ones will be printed)
     * @param isAmendment True if items were previously sent (delta "ADDED" slip)
     * @param sessionNumber Optional order-round number printed on the slip so the kitchen
     *   can tell each session apart (a fresh order is session 1, each added round increments)
     */
    suspend fun printKitchenSlip(
        tableId: String,
        items: List<OrderItem>,
        isAmendment: Boolean,
        sessionNumber: Int? = null
    ) {
        if (!isPrinterHost()) return

        // Batch all DB reads into a single pass — settings once, table name once,
        // printer config once — instead of 4-5 separate round-trips.
        val settings = settingsDao.get()
        val printLanguage = settings?.printLanguage ?: "EN"
        val timezone = settings?.timezone ?: "Asia/Kuala_Lumpur"
        val charWidth = resolveCharWidth(PrinterRole.KITCHEN_ONLY)
        val tableName = resolveTableName(tableId)

        // Group into two buckets (FOOD / BEVERAGE) → at most two slips per order, each
        // routed to the printer assigned to that bucket (categoryFilter holds the bucket).
        val slipsByBucket = KitchenSlipDocument.generateByRoute(
            tableId = tableName,
            items = items,
            isAmendment = isAmendment,
            charWidth = charWidth,
            printLanguage = printLanguage,
            timezone = timezone,
            sessionNumber = sessionNumber,
            menuFontSize = printSettingsStore.getKitchenFontSize(),
            routeOf = { category -> menuCategoryStore.getCategoryRoute(category) }
        )

        for ((bucket, payload) in slipsByBucket) {
            if (payload.isNotBlank()) {
                printerDispatcher.dispatch(
                    PrinterDispatcher.DOCUMENT_TYPE_KITCHEN_SLIP,
                    payload,
                    bucket
                )
            }
        }
    }

    /**
     * Generate and dispatch a receipt to the receipt printer.
     *
     * @param order The completed order
     * @param items All order items (consolidated at snapshotted prices)
     * @param paymentMethod "CASH" or "QR"
     * @param cafeName The café branding name
     */
    suspend fun printReceipt(
        order: Order,
        items: List<OrderItem>,
        paymentMethod: String,
        cafeName: String
    ) {
        if (!isPrinterHost()) return

        // Batch all DB reads — settings once, receipt dimensions once, table name once.
        val settings = settingsDao.get()
        val printLanguage = settings?.printLanguage ?: "EN"
        val timezone = settings?.timezone ?: "Asia/Kuala_Lumpur"
        val (charWidth, pixelWidth) = resolveReceiptDimensions()
        val tableName = resolveTableName(order.tableId)

        val payload = ReceiptDocument.generate(
            context = context,
            order = order,
            items = items,
            paymentMethod = paymentMethod,
            cafeName = cafeName,
            charWidth = charWidth,
            pixelWidth = pixelWidth,
            printLanguage = printLanguage,
            timezone = timezone,
            tableName = tableName
        )

        if (payload.isNotBlank()) {
            printerDispatcher.dispatch(
                PrinterDispatcher.DOCUMENT_TYPE_RECEIPT,
                payload
            )
        }
    }

    /**
     * Resolve a table's admin-entered display name (Table Management "label") from its
     * internal id. Falls back to the id itself if the table row is missing or unnamed, so
     * a slip/receipt never prints blank.
     */
    private suspend fun resolveTableName(tableId: String): String {
        val label = tableDao.getById(tableId)?.label?.trim()
        return if (label.isNullOrBlank()) tableId else label
    }

    /**
     * Resolve the character width for a given printer role.
     * Falls back to BOTH role, then defaults to 80mm (48 chars).
     */
    private suspend fun resolveCharWidth(role: PrinterRole): Int {        val printers = printerConfigDao.getByRole(role).ifEmpty {
            printerConfigDao.getByRole(PrinterRole.BOTH)
        }
        return printers.firstOrNull()?.paperWidth?.charWidth
            ?: PaperWidth.EIGHTY_MM.charWidth
    }

    /**
     * Resolve both char width and pixel width for receipt printing.
     */
    private suspend fun resolveReceiptDimensions(): Pair<Int, Int> {
        val printers = printerConfigDao.getByRole(PrinterRole.RECEIPT_ONLY).ifEmpty {
            printerConfigDao.getByRole(PrinterRole.BOTH)
        }
        val paperWidth = printers.firstOrNull()?.paperWidth ?: PaperWidth.EIGHTY_MM
        return paperWidth.charWidth to paperWidth.pixelWidth
    }
}
