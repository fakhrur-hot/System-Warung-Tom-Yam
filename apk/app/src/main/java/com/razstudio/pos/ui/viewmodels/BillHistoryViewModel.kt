package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.local.Order
import com.razstudio.pos.data.local.OrderDao
import com.razstudio.pos.data.local.OrderItem
import com.razstudio.pos.data.local.OrderStatus
import com.razstudio.pos.data.local.PaymentTransactionDao
import com.razstudio.pos.data.local.PaymentTransactionStatus
import com.razstudio.pos.data.local.SettingsDao
import com.razstudio.pos.data.local.TableDao
import com.razstudio.pos.printing.PrintService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Bill History — find a settled or cancelled bill again, look at what was on it, and reprint it.
 *
 * The café-facing need this answers: a customer comes back and asks about a bill, or the owner is
 * reconciling the till and wants to see one order rather than a day's totals. Reports answers
 * "how much did we take"; this answers "what happened on that one bill".
 *
 * Search runs in SQL over four fields at once (order number, table, payment method, and any item
 * name on the bill) rather than filtering an in-memory list, because a café two years in has tens
 * of thousands of bills and loading them all to filter would be slow and eventually fatal.
 */
@HiltViewModel
class BillHistoryViewModel @Inject constructor(
    private val orderDao: OrderDao,
    private val tableDao: TableDao,
    private val settingsDao: SettingsDao,
    private val printService: PrintService,
    private val paymentTransactionDao: PaymentTransactionDao,
    private val gateway: BackendGateway
) : ViewModel() {

    companion object {
        /**
         * A café scanning bills reads maybe a screen or two before refining the search. Loading
         * more than this per query wastes work; "Load more" extends it rather than paging, which
         * keeps scroll position stable and needs no cursor state.
         */
        private const val PAGE_SIZE = 60

        /** Typing pause before searching, so a 12-character query is one query, not twelve. */
        private const val DEBOUNCE_MS = 250L

        /** How far back the history reaches. Bills older than this need the exported reports. */
        private const val LOOKBACK_DAYS = 730L
    }

    /** One row in the list: the bill plus the bits only a join can tell us. */
    data class BillRow(
        val order: Order,
        val tableLabel: String,
        val itemCount: Int,
        /** "3x Tom Yam, 2x Teh Ais…" — enough to recognise a bill without opening it. */
        val itemSummary: String,
        /** Already formatted in the café's timezone — the row must not do date work. */
        val whenText: String
    ) {
        val isCancelled: Boolean get() = order.status == OrderStatus.CANCELLED
    }

    data class UiState(
        val query: String = "",
        val bills: List<BillRow> = emptyList(),
        val isLoading: Boolean = false,
        val hasSearched: Boolean = false,
        /** True when the result set was cut off, so the UI can offer "Load more". */
        val truncated: Boolean = false,
        val limit: Int = PAGE_SIZE,
        val selected: BillDetail? = null,
        val cafeName: String = "",
        val message: String? = null
    )

    /** An opened bill: its lines, resolved for display. */
    data class BillDetail(
        val order: Order,
        val tableLabel: String,
        val items: List<OrderItem>,
        val printedAt: String,
        val isReprinting: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadCafeName()
        // Open on the most recent bills rather than an empty screen — the bill someone is looking
        // for is usually one of the last few.
        searchJob = viewModelScope.launch { search("") }
    }

    private fun loadCafeName() {
        viewModelScope.launch {
            when (val r = gateway.getBranding()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(cafeName = r.data.cafeName)
                else -> { /* a blank café name only affects the reprint header */ }
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query, limit = PAGE_SIZE)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            search(query)
        }
    }

    /** Extend the current result set rather than paging — see [PAGE_SIZE]. */
    fun loadMore() {
        val next = _uiState.value.limit + PAGE_SIZE
        _uiState.value = _uiState.value.copy(limit = next)
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(_uiState.value.query) }
    }

    fun refresh() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(_uiState.value.query) }
    }

    private suspend fun search(rawQuery: String) {
        _uiState.value = _uiState.value.copy(isLoading = true)
        try {
            // Fold in Kotlin, not SQL: SQLite's LOWER() is ASCII-only, so a Chinese, Tamil or
            // Thai item name would never match a differently-cased query if we relied on it.
            val query = rawQuery.trim().lowercase()
            val limit = _uiState.value.limit
            val now = Instant.now()
            val orders = orderDao.searchBills(
                startDate = now.minusSeconds(LOOKBACK_DAYS * 86_400).toString(),
                endDate = now.plusSeconds(86_400).toString(),
                query = query,
                // Ask for one more than we show, so "is there more?" needs no COUNT query.
                limit = limit + 1
            )

            val truncated = orders.size > limit
            val page = if (truncated) orders.take(limit) else orders

            // Batched reads for the whole page rather than one per row.
            val tz = settingsDao.get()?.timezone?.takeIf { it.isNotBlank() } ?: "Asia/Kuala_Lumpur"
            val labels = tableDao.getAll().associate { it.id to it.label }
            val itemsByOrder = if (page.isEmpty()) emptyMap() else
                orderDao.getItemsForOrders(page.map { it.id }).groupBy { it.orderId }

            val rows = page.map { order ->
                val items = itemsByOrder[order.id].orEmpty()
                BillRow(
                    order = order,
                    tableLabel = order.tableId?.let { labels[it] ?: it } ?: "",
                    itemCount = items.sumOf { it.quantity },
                    itemSummary = items.joinToString(", ", limit = 3, truncated = "…") {
                        "${it.quantity}x ${com.razstudio.pos.util.MenuName.display(it.nameSnapshot)}"
                    },
                    whenText = formatWhen(order.createdAt, tz)
                )
            }

            _uiState.value = _uiState.value.copy(
                bills = rows,
                isLoading = false,
                hasSearched = true,
                truncated = truncated
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(isLoading = false, message = e.message)
        }
    }

    fun openBill(row: BillRow) {
        viewModelScope.launch {
            try {
                val items = orderDao.getItemsForOrder(row.order.id)
                _uiState.value = _uiState.value.copy(
                    selected = BillDetail(
                        order = row.order,
                        tableLabel = row.tableLabel,
                        items = items,
                        printedAt = row.whenText
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(message = e.message)
            }
        }
    }

    fun closeBill() {
        _uiState.value = _uiState.value.copy(selected = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /**
     * Reprint a past receipt through the ordinary print path, so it goes to whichever printer is
     * configured for receipts and renders identically to the original — including the bitmap route
     * for non-Latin item names.
     *
     * The bill is reprinted **as it was**: `PrintService` reads the stored order and its line
     * snapshots, so a menu price changed since then does not rewrite history.
     */
    fun reprint() {
        val detail = _uiState.value.selected ?: return
        if (detail.isReprinting) return
        _uiState.value = _uiState.value.copy(selected = detail.copy(isReprinting = true))
        viewModelScope.launch {
            try {
                val method = detail.order.paymentMethod ?: ""
                printService.printReceipt(
                    order = detail.order,
                    items = detail.items,
                    paymentMethod = method,
                    cafeName = _uiState.value.cafeName,
                    // Reprinting "as it was" has to include the gateway reference, or a reprint
                    // silently drops the one number a customer disputing a charge needs. Cash and
                    // static QR have no gateway leg and correctly resolve to null. (9.1)
                    gatewayTransactionId = gatewayTransactionIdFor(detail.order.id, method),
                )
                _uiState.value = _uiState.value.copy(
                    selected = _uiState.value.selected?.copy(isReprinting = false),
                    message = REPRINT_SENT
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    selected = _uiState.value.selected?.copy(isReprinting = false),
                    message = e.message
                )
            }
        }
    }

    /** Same rule as [com.razstudio.pos.ui.viewmodels.TableViewViewModel.gatewayTransactionIdFor]:
     *  null for cash/static-QR, and null for any attempt that did not reach SUCCESS. */
    private suspend fun gatewayTransactionIdFor(orderId: String, method: String): String? {
        if (method.equals("CASH", ignoreCase = true) || method.equals("QR", ignoreCase = true)) {
            return null
        }
        return paymentTransactionDao.getLatestForOrder(orderId)
            ?.takeIf { it.status == PaymentTransactionStatus.SUCCESS }
            ?.gatewayTransactionId
    }

    private fun formatWhen(iso: String, tz: String): String = try {
        DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a")
            .withZone(ZoneId.of(tz))
            .format(Instant.parse(iso))
    } catch (e: Exception) {
        iso
    }
}

/**
 * Sentinel the screen swaps for a localized string. The ViewModel does not hold English text —
 * `UiStringsCompletenessTest` guards that rule, and it was broken once already by a screen that
 * built its messages here.
 */
const val REPRINT_SENT = "__reprint_sent__"
