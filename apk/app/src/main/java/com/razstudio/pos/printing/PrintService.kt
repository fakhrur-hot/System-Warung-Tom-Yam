package com.razstudio.pos.printing

import android.content.Context
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.data.local.Order
import com.razstudio.pos.data.local.MenuDao
import com.razstudio.pos.data.local.OrderItem
import com.razstudio.pos.data.local.PaperWidth
import com.razstudio.pos.data.local.PrinterConfigDao
import com.razstudio.pos.data.local.PrinterRole
import com.razstudio.pos.data.local.SettingsDao
import com.razstudio.pos.data.local.TableDao
import com.razstudio.pos.printing.documents.KitchenSlipDocument
import com.razstudio.pos.printing.documents.ReceiptDocument
import com.razstudio.pos.ui.i18n.AppLanguage
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
    private val printSettingsStore: com.razstudio.pos.data.local.PrintSettingsStore,
    private val menuCategoryStore: com.razstudio.pos.data.local.MenuCategoryStore,
    private val menuDao: MenuDao
) {

    /**
     * Re-localize each line's [OrderItem.nameSnapshot] into [printLanguage] using the live menu.
     * The backend freezes the snapshot as the ENGLISH name (+ any " (size)" suffix), so we swap
     * that English base for the localized name from the current menu (keyed by menuItemId),
     * preserving the size suffix. Falls back to the raw snapshot for deleted/renamed items.
     * Fixes kitchen slips + receipts always printing English regardless of the print language.
     */
    private suspend fun localizeItemNames(items: List<OrderItem>, printLanguage: String): List<OrderItem> {
        val lang = AppLanguage.fromServerCode(printLanguage)
        val menuById = menuDao.getAll().associateBy { it.id }
        return items.map { oi ->
            oi.copy(nameSnapshot = lang.localizedSnapshotName(oi.nameSnapshot, menuById[oi.menuItemId]))
        }
    }

    // ONLY the Main Admin device is a printer host. Secondary-admin devices have no local printer
    // (the Main Admin prints their slips/receipts), and ordering-staff devices never print at all —
    // they must never touch the Bluetooth stack. Guard every print entry point so anyone but the
    // Main Admin is a no-op (and never spams "no printer configured" alerts).
    private fun isPrinterHost(): Boolean =
        secureStorage.getRole() == SecureStorage.Role.ADMIN

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
        tableId: String?,
        items: List<OrderItem>,
        isAmendment: Boolean,
        sessionNumber: Int? = null,
        /**
         * Kiosk's running number. Requirement 3.5 is explicit that it appears on **both** the
         * kitchen slip and the customer receipt, so the counter and the kitchen refer to the same
         * sale — without it a Kiosk slip printed a bare "—" and the kitchen had no way to match a
         * dish to a customer.
         */
        orderNumber: Int? = null,
    ) {
        if (!isPrinterHost()) return

        // Batch all DB reads into a single pass — settings once, table name once,
        // printer config once — instead of 4-5 separate round-trips.
        val settings = settingsDao.get()
        val printLanguage = settings?.printLanguage ?: "EN"
        val timezone = settings?.timezone ?: "Asia/Kuala_Lumpur"
        val charWidth = resolveCharWidth(PrinterRole.KITCHEN_ONLY)
        val tableName = resolveTableName(tableId, orderNumber)
        val localizedItems = localizeItemNames(items, printLanguage)

        // Group into two buckets (FOOD / BEVERAGE) → at most two slips per order, each
        // routed to the printer assigned to that bucket (categoryFilter holds the bucket).
        val slipsByBucket = KitchenSlipDocument.generateByRoute(
            tableId = tableName,
            items = localizedItems,
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
        val tableName = resolveTableName(order.tableId, order.orderNumber)
        val localizedItems = localizeItemNames(items, printLanguage)

        val payload = ReceiptDocument.generate(
            context = context,
            order = order,
            items = localizedItems,
            paymentMethod = paymentMethod,
            cafeName = cafeName,
            charWidth = charWidth,
            pixelWidth = pixelWidth,
            printLanguage = printLanguage,
            timezone = timezone,
            tableName = tableName,
            printLogo = printSettingsStore.getReceiptLogo()
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
    /**
     * The heading a slip carries: the table's label, or the Kiosk running number.
     *
     * `tableId` is null in Kiosk Mode, which has no tables at all — a sale is identified by a number
     * that resets each business day. Falling back to the id (the old behaviour) would have printed
     * an empty heading there.
     */
    private suspend fun resolveTableName(tableId: String?, orderNumber: Int? = null): String {
        if (tableId == null) return orderNumber?.let { "#$it" } ?: "—"
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
