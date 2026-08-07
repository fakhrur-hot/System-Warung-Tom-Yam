package com.razstudio.pos.ui.viewmodels

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.local.CancelledSummary
import com.razstudio.pos.data.local.DrawerKick
import com.razstudio.pos.data.local.OrderDao
import com.razstudio.pos.data.local.PaymentMethodTotal
import com.razstudio.pos.data.local.PaymentSplit
import com.razstudio.pos.data.local.PrinterConfigDao
import com.razstudio.pos.data.local.PrinterTransport
import com.razstudio.pos.data.local.ReportData
import com.razstudio.pos.data.local.SettingsDao
import com.razstudio.pos.data.local.TableBreakdown
import com.razstudio.pos.data.local.TableDao
import com.razstudio.pos.data.local.TopItem
import com.razstudio.pos.printing.sunmi.SunmiPrinterDriver
import com.razstudio.pos.ui.i18n.LanguageManager
import com.razstudio.pos.ui.i18n.uiStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * ViewModel for on-device reports (Task 25).
 * Aggregates order data from Room into ReportData, and generates PDF/CSV exports.
 */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val orderDao: OrderDao,
    private val tableDao: TableDao,
    private val settingsDao: SettingsDao,
    private val printerConfigDao: PrinterConfigDao,
    private val sunmiDriver: SunmiPrinterDriver,
    private val apiClient: BackendGateway,
    private val languageManager: LanguageManager,
    private val menuDao: com.razstudio.pos.data.local.MenuDao,
) : ViewModel() {

    private fun str() = uiStrings(languageManager.language.value)

    data class UiState(
        val isLoading: Boolean = false,
        val reportData: ReportData? = null,
        val selectedPeriod: ReportPeriod = ReportPeriod.TODAY,
        val customStartDate: String? = null,
        val customEndDate: String? = null,
        val exportUri: Uri? = null,
        val cafeName: String = "",
        val error: String? = null,
        val successMessage: String? = null
    )

    enum class ReportPeriod { TODAY, THIS_WEEK, THIS_MONTH, CUSTOM }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    companion object {
        private const val DEFAULT_TZ = "Asia/Kuala_Lumpur"
    }

    init {
        loadReport(ReportPeriod.TODAY)
        loadCafeName()
    }

    /** Fetch café name from backend branding for the PDF header. Best-effort. */
    private fun loadCafeName() {
        viewModelScope.launch {
            when (val result = apiClient.getBranding()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(cafeName = result.data.cafeName)
                else -> { /* leave blank */ }
            }
        }
    }

    fun selectPeriod(period: ReportPeriod) {
        _uiState.value = _uiState.value.copy(selectedPeriod = period)
        loadReport(period)
    }

    fun setCustomRange(startDate: String, endDate: String) {
        _uiState.value = _uiState.value.copy(
            selectedPeriod = ReportPeriod.CUSTOM,
            customStartDate = startDate,
            customEndDate = endDate
        )
        loadReport(ReportPeriod.CUSTOM, startDate, endDate)
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(error = null, successMessage = null, exportUri = null)
    }

    private fun loadReport(
        period: ReportPeriod,
        customStart: String? = null,
        customEnd: String? = null
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // Resolve Cafe_Timezone once per report load (T2.3)
                val settings = settingsDao.get()
                val cafeTz = settings?.timezone?.takeIf { it.isNotBlank() } ?: DEFAULT_TZ

                // Business-day start hour (default 15 = 3 PM). Reports anchor to the opening
                // day, and each day's window runs from startHour to the next day's startHour,
                // so sales after midnight count toward the opening day.
                val startHour = when (val r = apiClient.getSettings()) {
                    is ApiResult.Success -> r.data.businessDayStartHour
                    else -> 15
                }.coerceIn(0, 23)

                val (startDate, endDate) = getDateRange(period, customStart, customEnd, cafeTz, startHour)
                val hh = startHour.toString().padStart(2, '0')
                val startIso = "${startDate}T$hh:00:00"
                // Exclusive-ish end at the next day's start hour, so the window is a full
                // business day even when it spans past midnight.
                val endIso = "${java.time.LocalDate.parse(endDate).plusDays(1)}T$hh:00:00"

                // Compute all aggregates
                val totalOrders = orderDao.getCompletedOrderCountBetween(startIso, endIso)
                val totalRevenue = orderDao.getTotalRevenueBetween(startIso, endIso)
                val avgOrderValue = if (totalOrders > 0) totalRevenue / totalOrders else 0.0

                // Per-table breakdown
                val tableRevenues = orderDao.getRevenueByTable(startIso, endIso)
                val tables = tableDao.getAll()
                val tableMap = tables.associateBy { it.id }
                val perTable = tableRevenues.map { tr ->
                    TableBreakdown(
                        tableId = tr.tableId,
                        tableLabel = tableMap[tr.tableId]?.label ?: tr.tableId,
                        orderCount = tr.orderCount,
                        revenue = tr.revenue
                    )
                }

                // Top-N per category
                val topN = settings?.topN ?: 5
                val popularItems = orderDao.getPopularItems(startIso, endIso)

                // Menu names are stored on the order line as an English SNAPSHOT, captured when the
                // order was taken so a later rename cannot rewrite history. That is right for the
                // record and wrong for the reader: a Malay café got a Malay report with English
                // dish names down the middle of it.
                //
                // AppLanguage.localizedSnapshotName swaps the English base for the current
                // language's name while keeping any size suffix, and falls back to the raw snapshot
                // when the item has since been deleted or renamed past recognition — so a discontinued
                // dish still appears in last month's report rather than vanishing from it.
                val lang = languageManager.language.value
                val menuById = menuDao.getAll().associateBy { it.id }
                fun localizedName(menuItemId: String?, snapshot: String): String =
                    lang.localizedSnapshotName(snapshot, menuById[menuItemId])
                val topNPerCategory = popularItems
                    .groupBy { it.categorySnapshot }
                    .mapValues { (_, items) ->
                        items.take(topN).map {
                            TopItem(
                                localizedName(it.menuItemId, it.nameSnapshot),
                                it.totalQuantity,
                                it.totalRevenue,
                            )
                        }
                    }

                // Best sellers across the whole menu. getPopularItems() is already ordered by
                // quantity descending across every category, so the head of that list is the
                // overall ranking — no second query. Per-category can only say which drink beat
                // the other drinks; this says what the café actually sells.
                val topOverall = popularItems
                    .take(topN)
                    .map {
                        TopItem(
                            localizedName(it.menuItemId, it.nameSnapshot),
                            it.totalQuantity,
                            it.totalRevenue,
                        )
                    }

                // Payment split. The query returns EVERY method present in the period, so gateway
                // codes (DUITNOW_QR, GRABPAY, …) arrive here too. Previously only the CASH and QR
                // rows were picked out and the rest discarded, which meant gateway takings counted
                // toward total revenue but appeared in no breakdown line. (task 9.2)
                val paymentMethods = orderDao.getOrdersByPaymentMethod(startIso, endIso)
                val cashRow = paymentMethods.find { it.paymentMethod == "CASH" }
                val qrRow = paymentMethods.find { it.paymentMethod == "QR" }
                val paymentSplit = PaymentSplit(
                    cashCount = cashRow?.orderCount ?: 0,
                    cashTotal = cashRow?.revenue ?: 0.0,
                    qrCount = qrRow?.orderCount ?: 0,
                    qrTotal = qrRow?.revenue ?: 0.0,
                    byMethod = paymentMethods
                        // A null method means an order that completed without one ever being
                        // recorded. It is real revenue but not attributable, so it is left out of
                        // the per-method rows rather than shown as a blank label.
                        .filter { !it.paymentMethod.isNullOrBlank() }
                        .map {
                            PaymentMethodTotal(
                                method = it.paymentMethod!!,
                                orderCount = it.orderCount,
                                revenue = it.revenue,
                            )
                        }
                        .sortedByDescending { it.revenue },
                )

                // Cancelled summary
                val cancelledOrders = orderDao.getCancelledOrders(startIso, endIso)
                val cancelledSummary = CancelledSummary(
                    totalCount = cancelledOrders.size,
                    totalValue = cancelledOrders.sumOf { it.total },
                    byAdmin = cancelledOrders.count { it.cancelledBy?.uppercase() == "ADMIN" },
                    byCustomer = cancelledOrders.count { it.cancelledBy?.uppercase() == "CUSTOMER" },
                    byStaff = cancelledOrders.count {
                        val who = it.cancelledBy?.uppercase() ?: ""
                        who != "ADMIN" && who != "CUSTOMER" && who.isNotEmpty()
                    }
                )

                // Drawer-opening count (Task 2.4, HW-REQ-3 SHOULD).
                // Only query when there is at least one active Sunmi AIDL printer with a
                // SUNMI_AIDL drawer kick — if no such printer is configured, skip the call
                // entirely so non-Sunmi devices never see a drawer-counter row.
                val drawerOpeningCount: Int? = run {
                    val hasSunmiPrinterWithDrawer = printerConfigDao.getActive().any {
                        it.transport == PrinterTransport.SUNMI_AIDL &&
                            it.drawerKick == DrawerKick.SUNMI_AIDL
                    }
                    if (hasSunmiPrinterWithDrawer) {
                        val count = sunmiDriver.getOpenDrawerTimes()
                        if (count >= 0) count else null
                    } else {
                        null
                    }
                }

                val reportData = ReportData(
                    startDate = startDate,
                    endDate = endDate,
                    totalOrders = totalOrders,
                    totalRevenue = totalRevenue,
                    avgOrderValue = avgOrderValue,
                    perTableBreakdown = perTable,
                    topNPerCategory = topNPerCategory,
                    topOverall = topOverall,
                    paymentSplit = paymentSplit,
                    cancelledSummary = cancelledSummary,
                    drawerOpeningCount = drawerOpeningCount
                )

                _uiState.value = _uiState.value.copy(isLoading = false, reportData = reportData)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    // ── PDF Export ──────────────────────────────────────────────────────────

    fun exportPdf(context: Context) {
        viewModelScope.launch {
            val report = _uiState.value.reportData ?: return@launch
            try {
                val document = PdfDocument()
                // The renderer owns pagination now — it starts and finishes pages itself, because
                // the per-table breakdown can run past one page and used to be silently truncated.
                com.razstudio.pos.ui.util.ReportPdfRenderer.render(
                    document = document,
                    report = report,
                    cafeName = _uiState.value.cafeName,
                    logo = com.razstudio.pos.ui.util.LogoPipeline.loadJpegFromInternal(context),
                    // The report speaks the Admin App's language, which itself defaults from the
                    // café-wide `defaultLangAdmin` — Bahasa Malaysia unless an owner changed it.
                    // It used to be hardcoded English regardless.
                    strings = str(),
                )

                val fileName = "report-${report.startDate}-to-${report.endDate}.pdf"
                val (_, uri) = savePdf(context, document, fileName)
                document.close()

                if (uri != null) {
                    _uiState.value = _uiState.value.copy(
                        exportUri = uri,
                        successMessage = str().pdfSaved
                    )
                } else {
                    _uiState.value = _uiState.value.copy(error = str().failedToSavePdf)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = str().pdfExportFailed.format(e.message))
            }
        }
    }

    fun createShareIntent(uri: Uri, mimeType: String): Intent {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(shareIntent, "Share Report")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun getDateRange(
        period: ReportPeriod,
        customStart: String?,
        customEnd: String?,
        cafeTz: String = DEFAULT_TZ,
        startHour: Int = 15
    ): Pair<String, String> {
        val tz = TimeZone.getTimeZone(cafeTz)
        val cal = Calendar.getInstance(tz)
        // Anchor "today" to the business day: before the start hour we're still on the
        // previous opening day, so step back a day for all range calculations below.
        if (cal.get(Calendar.HOUR_OF_DAY) < startHour) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).also { it.timeZone = tz }
        return when (period) {
            ReportPeriod.TODAY -> {
                val today = dateOnlyFormat.format(cal.time)
                today to today
            }
            ReportPeriod.THIS_WEEK -> {
                cal.firstDayOfWeek = Calendar.MONDAY
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                val start = dateOnlyFormat.format(cal.time)
                cal.add(Calendar.DAY_OF_WEEK, 6)
                val end = dateOnlyFormat.format(cal.time)
                start to end
            }
            ReportPeriod.THIS_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val start = dateOnlyFormat.format(cal.time)
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                val end = dateOnlyFormat.format(cal.time)
                start to end
            }
            ReportPeriod.CUSTOM -> {
                (customStart ?: dateOnlyFormat.format(cal.time)) to
                    (customEnd ?: dateOnlyFormat.format(cal.time))
            }
        }
    }

    private fun savePdf(context: Context, document: PdfDocument, fileName: String): Pair<File?, Uri?> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null to null
            context.contentResolver.openOutputStream(uri)?.use { document.writeTo(it) }
            null to uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "reports")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { document.writeTo(it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            file to uri
        }
    }

}
