package com.warungtomyam.pos.printing

import com.warungtomyam.pos.data.local.PaperWidth
import com.warungtomyam.pos.data.local.PrintJob
import com.warungtomyam.pos.data.local.PrintJobDao
import com.warungtomyam.pos.data.local.PrintJobStatus
import com.warungtomyam.pos.data.local.PrinterConfig
import com.warungtomyam.pos.data.local.PrinterConfigDao
import com.warungtomyam.pos.data.local.PrinterRole
import com.warungtomyam.pos.ui.i18n.LanguageManager
import com.warungtomyam.pos.ui.i18n.uiStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes print jobs to the appropriate printer(s) based on document type and printer role.
 *
 * Dispatch logic:
 * 1. Find active printer(s) matching the exact role for the document type.
 * 2. If no exact match → fallback to printers with role BOTH.
 * 3. If no active printer at all → queue the job and emit a "No printer configured" alert.
 *
 * Execution:
 * - Connects via Bluetooth MAC using the DantSu ESCPOS library.
 * - On success → marks job COMPLETED.
 * - On failure → retries up to MAX_RETRIES, then marks FAILED.
 */
@Singleton
class PrinterDispatcher @Inject constructor(
    private val printerConfigDao: PrinterConfigDao,
    private val printJobDao: PrintJobDao,
    private val connectionManager: PrinterConnectionManager,
    private val languageManager: LanguageManager
) {

    /**
     * Resolve a [PrintAlert] into a user-facing, localized message. Lives here (not on the
     * sealed class) because it needs the injected [languageManager] to pick the active
     * language — the alert types themselves stay pure data with no DI access.
     */
    fun toMessage(alert: PrintAlert): String {
        val s = uiStrings(languageManager.language.value)
        return when (alert) {
            is PrintAlert.NoPrinterConfigured -> s.noPrinterConfigured.format(alert.documentType)
            is PrintAlert.PrintFailed -> s.printFailedOn.format(alert.printerName, alert.error)
            is PrintAlert.PrintSucceeded -> s.printedOnPrinter.format(alert.documentType, alert.printerName)
        }
    }
    companion object {
        const val MAX_RETRIES = 3
        const val DOCUMENT_TYPE_KITCHEN_SLIP = "KITCHEN_SLIP"
        const val DOCUMENT_TYPE_RECEIPT = "RECEIPT"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _alerts = MutableSharedFlow<PrintAlert>(extraBufferCapacity = 10)
    val alerts: SharedFlow<PrintAlert> = _alerts.asSharedFlow()

    /**
     * Dispatch a document to the appropriate printer(s).
     * @param documentType One of DOCUMENT_TYPE_KITCHEN_SLIP or DOCUMENT_TYPE_RECEIPT
     * @param payload Serialized document content (JSON) — actual formatting is Task 22
     * @param category For kitchen slips, the menu category this slip is for — routes the
     *   slip to the kitchen printer(s) that claim that category (see [printerHandlesCategory]).
     */
    fun dispatch(documentType: String, payload: String, category: String? = null) {
        scope.launch {
            val targetRole = when (documentType) {
                DOCUMENT_TYPE_KITCHEN_SLIP -> PrinterRole.KITCHEN_ONLY
                DOCUMENT_TYPE_RECEIPT -> PrinterRole.RECEIPT_ONLY
                else -> PrinterRole.BOTH
            }

            // Step 1: Find printers with exact role match
            var printers = printerConfigDao.getByRole(targetRole)

            // Step 2: Fallback to BOTH if no exact match
            if (printers.isEmpty() && targetRole != PrinterRole.BOTH) {
                printers = printerConfigDao.getByRole(PrinterRole.BOTH)
            }

            // Step 2b: Per-category kitchen routing. If any candidate printer explicitly
            // claims this category, send ONLY to those. Otherwise fall back to the
            // catch-all kitchen printers (no categoryFilter set), else every kitchen printer
            // — so a newly-added category still prints somewhere until it's assigned.
            if (documentType == DOCUMENT_TYPE_KITCHEN_SLIP && !category.isNullOrBlank() && printers.isNotEmpty()) {
                val claimants = printers.filter { printerHandlesCategory(it, category) }
                printers = if (claimants.isNotEmpty()) {
                    claimants
                } else {
                    val catchAll = printers.filter { it.categoryFilter.isNullOrBlank() }
                    if (catchAll.isNotEmpty()) catchAll else printers
                }
            }

            // Step 3: No printer available — queue + alert
            if (printers.isEmpty()) {
                val job = createPrintJob(
                    printerId = "unassigned",
                    documentType = documentType,
                    payload = payload
                )
                printJobDao.insert(job)
                _alerts.emit(PrintAlert.NoPrinterConfigured(documentType))
                return@launch
            }

            // Create a job per matched printer and trigger execution
            for (printer in printers) {
                val job = createPrintJob(
                    printerId = printer.id,
                    documentType = documentType,
                    payload = payload
                )
                printJobDao.insert(job)
                executePrintJob(job, printer)
            }
        }
    }

    /**
     * True if [printer] is assigned to handle [category]. A printer's [PrinterConfig.categoryFilter]
     * is a comma-separated list of category names it prints (one printer can serve several
     * categories); blank/null means "no specific categories" (a catch-all kitchen printer).
     */
    private fun printerHandlesCategory(printer: PrinterConfig, category: String): Boolean {
        val filter = printer.categoryFilter?.trim().orEmpty()
        if (filter.isBlank()) return false
        return filter.split(",").map { it.trim() }.any { it.equals(category.trim(), ignoreCase = true) }
    }

    /**
     * Execute a single print job: connect to BT device, send data, handle retries.
     * Actual ESC/POS command formatting is handled by Task 22's document renderers.
     * This method handles the connection lifecycle and status tracking.
     */
    suspend fun executePrintJob(job: PrintJob, printer: PrinterConfig) {
        printJobDao.updateStatus(job.id, PrintJobStatus.PRINTING.name)

        try {
            // Connect to the Bluetooth printer using the DantSu library.
            // The actual print commands (formatted ESC/POS text) come from the payload.
            // Task 22 will produce the formatted text; here we just establish connection
            // and send whatever payload is provided.
            connectAndPrint(printer, job.payload)
            printJobDao.updateStatus(job.id, PrintJobStatus.COMPLETED.name)
            _alerts.emit(PrintAlert.PrintSucceeded(printer.name, job.documentType))
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            if (job.retryCount < MAX_RETRIES) {
                printJobDao.markForRetry(job.id, errorMsg)
                // Re-fetch the updated job for retry
                val updatedJobs = printJobDao.getQueued(printer.id)
                val retryJob = updatedJobs.find { it.id == job.id }
                if (retryJob != null) {
                    executePrintJob(retryJob, printer)
                }
            } else {
                printJobDao.updateStatus(job.id, PrintJobStatus.FAILED.name, errorMsg)
                _alerts.emit(PrintAlert.PrintFailed(printer.name, errorMsg))
            }
        }
    }

    /**
     * Retry all queued jobs for a specific printer (e.g., after reconnect).
     */
    fun retryQueuedJobs(printerId: String) {
        scope.launch {
            val printer = printerConfigDao.getById(printerId) ?: return@launch
            val queuedJobs = printJobDao.getQueued(printerId)
            for (job in queuedJobs) {
                executePrintJob(job, printer)
            }
        }
    }

    /**
     * Send a test print to verify printer connectivity.
     * @return char width and pixel width based on the printer's paper configuration
     */
    suspend fun testPrint(printerId: String): PrinterDimensions? {
        val printer = printerConfigDao.getById(printerId) ?: return null
        val testPayload = buildTestPayload(printer)

        try {
            connectAndPrint(printer, testPayload)
            return PrinterDimensions(
                charWidth = printer.paperWidth.charWidth,
                pixelWidth = printer.paperWidth.pixelWidth
            )
        } catch (e: Exception) {
            _alerts.emit(PrintAlert.PrintFailed(printer.name, e.message ?: "Connection failed"))
            return null
        }
    }

    /**
     * Get the dimensions (char/pixel width) for a printer config.
     */
    fun getDimensions(paperWidth: PaperWidth): PrinterDimensions {
        return PrinterDimensions(
            charWidth = paperWidth.charWidth,
            pixelWidth = paperWidth.pixelWidth
        )
    }

    /**
     * Connect to a Bluetooth printer via MAC address and send the formatted payload.
     * Uses the DantSu ESCPOS-ThermalPrinter-Android library.
     *
     * NOTE: This requires Android context and Bluetooth permissions at runtime.
     * The actual BluetoothConnection + EscPosPrinter instantiation happens here.
     * Task 22 will enrich the payload with formatted ESC/POS text.
     */
    /**
     * Connect to the SPECIFIC configured printer by MAC address and print via the
     * DantSu ESCPOS-ThermalPrinter library. We match on `macAddress` rather than
     * `selectFirstPaired()` because a café can pair both a kitchen and a receipt
     * printer at once — picking "first paired" would send kitchen slips to whichever
     * happened to pair first. Throws on failure so [executePrintJob]'s retry/FAILED
     * handling (and the resulting PrintAlert) kicks in.
     *
     * Runtime prerequisites (requested at printer-setup time, not here): BLUETOOTH_CONNECT
     * on Android 12+, and the target printer already paired at the OS level.
     */
    /**
     * Delegates to [PrinterConnectionManager], which keeps a warm persistent link (fast mode)
     * or reconnects on demand (eco mode) per the admin's choice. Throws on failure so the
     * retry/FAILED handling above still applies.
     */
    private suspend fun connectAndPrint(printer: PrinterConfig, payload: String) {
        connectionManager.print(
            macAddress = printer.macAddress,
            printerName = printer.name,
            paperWidth = printer.paperWidth,
            payload = payload
        )
    }

    private fun buildTestPayload(printer: PrinterConfig): String {
        val width = printer.paperWidth.charWidth
        val separator = "-".repeat(width)
        return "[C]<b>TEST PRINT</b>\n" +
            "[L]$separator\n" +
            "[L]Printer: ${printer.name}\n" +
            "[L]MAC: ${printer.macAddress}\n" +
            "[L]Paper: ${if (printer.paperWidth == PaperWidth.FIFTY_EIGHT_MM) "58mm" else "80mm"}\n" +
            "[L]Chars/line: $width\n" +
            "[L]Role: ${printer.printerRole.name}\n" +
            "[L]$separator\n" +
            "[C]OK\n"
    }

    private fun createPrintJob(
        printerId: String,
        documentType: String,
        payload: String
    ): PrintJob {
        return PrintJob(
            id = UUID.randomUUID().toString(),
            printerId = printerId,
            documentType = documentType,
            payload = payload,
            status = PrintJobStatus.QUEUED.name,
            createdAt = Instant.now().toString()
        )
    }
}

/**
 * Alert events emitted by the PrinterDispatcher.
 */
sealed class PrintAlert {
    data class NoPrinterConfigured(val documentType: String) : PrintAlert()
    data class PrintFailed(val printerName: String, val error: String) : PrintAlert()
    data class PrintSucceeded(val printerName: String, val documentType: String) : PrintAlert()
}

/**
 * Resolved dimensions for a printer based on its paper width configuration.
 */
data class PrinterDimensions(
    val charWidth: Int,
    val pixelWidth: Int
)
