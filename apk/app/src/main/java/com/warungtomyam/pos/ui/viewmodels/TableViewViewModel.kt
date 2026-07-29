package com.warungtomyam.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warungtomyam.pos.data.ApiClient
import com.warungtomyam.pos.data.ApiResult
import com.warungtomyam.pos.data.NewOrderItem
import com.warungtomyam.pos.data.OrderDto
import com.warungtomyam.pos.data.json.toEntity
import com.warungtomyam.pos.data.local.MenuDao
import com.warungtomyam.pos.data.local.MenuItem
import com.warungtomyam.pos.data.local.Order
import com.warungtomyam.pos.data.local.OrderDao
import com.warungtomyam.pos.data.local.OrderItem
import com.warungtomyam.pos.data.local.OrderStatus
import com.warungtomyam.pos.data.local.SettingsDao
import com.warungtomyam.pos.data.local.SystemSettings
import com.warungtomyam.pos.data.local.Table
import com.warungtomyam.pos.data.local.TableDao
import com.warungtomyam.pos.printing.PrintService
import com.warungtomyam.pos.ui.i18n.LanguageManager
import com.warungtomyam.pos.ui.i18n.uiStrings
import com.warungtomyam.pos.ui.tableview.OrderDetailState
import com.warungtomyam.pos.ui.tableview.TableState
import com.warungtomyam.pos.ui.tableview.TableUiStatus
import com.warungtomyam.pos.ui.tableview.toTableUiStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Table View POS screen.
 * Observes Room orders (Flow-based) to derive table states and provides
 * all order management operations (send to kitchen, add items, payment, cancel).
 *
 * State types [TableState] and [OrderDetailState] are the shared types from
 * [com.warungtomyam.pos.ui.tableview.TableViewModels] (Requirement 5.4).
 */
@HiltViewModel
class TableViewViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val orderDao: OrderDao,
    private val tableDao: TableDao,
    private val settingsDao: SettingsDao,
    private val menuDao: MenuDao,
    private val printService: PrintService,
    private val languageManager: LanguageManager
) : ViewModel() {

    private fun str() = uiStrings(languageManager.language.value)

    /** UI state for table management dialog */
    data class TableManagementState(
        val isVisible: Boolean = false,
        val tables: List<Table> = emptyList(),
        val newTableLabel: String = "",
        val editingTable: Table? = null,
        val editLabel: String = "",
        val error: String? = null
    )

    companion object {
        const val MAX_TABLES = 30
        const val MAX_TABLE_NUMBER = 9999
        // Fixed hold before an admin/staff order is actually sent — a brief mis-tap guard,
        // distinct from and shorter than the configurable customer hold.
        const val STAFF_ORDER_HOLD_SECONDS = 3
    }

    // Combined flow: tables + active orders → table states (shared TableState type)
    val tableStates: StateFlow<List<TableState>> = combine(
        tableDao.getAllFlow(),
        orderDao.getActiveOrdersFlow()
    ) { tables, orders ->
        tables.map { table ->
            val order = orders.find { it.tableId == table.id }
            val status = order?.status?.toTableUiStatus() ?: TableUiStatus.FREE
            TableState(table = table, status = status, order = order)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** One pending kitchen print job — a table's session whose items haven't been sent yet. */
    data class PendingPrintGroup(
        val orderId: String,
        val tableLabel: String,
        val sessionNumber: Int,
        val items: List<OrderItem>,
        val total: Double,
    )

    /**
     * All pending (not-yet-sent) kitchen prints across every active table, grouped by
     * table+session — feeds the global Pending Kitchen Prints modal used when auto-print is
     * off. Reactive: an item disappears from here the moment it's confirmed/sent.
     */
    val pendingKitchenPrints: StateFlow<List<PendingPrintGroup>> = combine(
        orderDao.getActiveOrdersFlow(),
        orderDao.getPendingKitchenItemsFlow(),
        tableDao.getAllFlow()
    ) { orders, pendingItems, tables ->
        val orderById = orders.associateBy { it.id }
        val tableLabelById = tables.associateBy({ it.id }, { it.label })
        pendingItems
            .filter { orderById.containsKey(it.orderId) } // only items on still-active orders
            .groupBy { it.orderId to it.sessionNumber }
            .map { (key, items) ->
                val order = orderById[key.first]
                PendingPrintGroup(
                    orderId = key.first,
                    tableLabel = tableLabelById[order?.tableId] ?: order?.tableId ?: "",
                    sessionNumber = key.second,
                    items = items.sortedBy { it.categorySnapshot },
                    total = items.sumOf { it.unitPriceSnapshot * it.quantity },
                )
            }
            .sortedWith(compareBy({ it.tableLabel }, { it.sessionNumber }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Available menu items, for the add-item picker in [com.warungtomyam.pos.ui.tableview.OrderDetailSheet]. */
    val availableMenu: StateFlow<List<MenuItem>> = menuDao.getAvailableFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _orderDetail = MutableStateFlow(OrderDetailState())
    val orderDetail: StateFlow<OrderDetailState> = _orderDetail.asStateFlow()

    private val _tableManagement = MutableStateFlow(TableManagementState())
    val tableManagement: StateFlow<TableManagementState> = _tableManagement.asStateFlow()

    /** Current café name (from backend branding), shown above the Table View header. Empty until loaded. */
    private val _cafeName = MutableStateFlow("")
    val cafeName: StateFlow<String> = _cafeName.asStateFlow()

    init {
        loadCafeName()
    }

    /** Fetch the café branding name for the header. Best-effort — stays blank on failure. */
    private fun loadCafeName() {
        viewModelScope.launch {
            when (val result = apiClient.getBranding()) {
                is ApiResult.Success -> _cafeName.value = result.data.cafeName
                else -> { /* leave blank */ }
            }
        }
    }

    // --- Order entry (new order on a free table) ---

    data class EntryCartItem(val menuItem: MenuItem, val quantity: Int)

    data class OrderEntryState(
        val isVisible: Boolean = false,
        val tableId: String? = null,
        val tableLabel: String = "",
        val menuItems: List<MenuItem> = emptyList(),
        val cart: List<EntryCartItem> = emptyList(),
        val isSubmitting: Boolean = false,
        // Non-null while the pre-send hold countdown is running (seconds remaining).
        val holdRemaining: Int? = null,
        val error: String? = null,
        val successMessage: String? = null,
    )

    private val _orderEntry = MutableStateFlow(OrderEntryState())
    val orderEntry: StateFlow<OrderEntryState> = _orderEntry.asStateFlow()
    private var orderHoldJob: kotlinx.coroutines.Job? = null

    /** Open the new-order entry modal for a free table, loading the available menu. */
    fun startOrderEntry(tableId: String, tableLabel: String) {
        viewModelScope.launch {
            val menu = menuDao.getAvailable()
            _orderEntry.value = OrderEntryState(
                isVisible = true,
                tableId = tableId,
                tableLabel = tableLabel,
                menuItems = menu,
            )
        }
    }

    fun addToCart(item: MenuItem) {
        val cart = _orderEntry.value.cart.toMutableList()
        val idx = cart.indexOfFirst { it.menuItem.id == item.id }
        if (idx >= 0) {
            cart[idx] = cart[idx].copy(quantity = cart[idx].quantity + 1)
        } else {
            cart.add(EntryCartItem(item, 1))
        }
        _orderEntry.value = _orderEntry.value.copy(cart = cart)
    }

    fun removeFromCart(menuItemId: String) {
        val cart = _orderEntry.value.cart.filterNot { it.menuItem.id == menuItemId }
        _orderEntry.value = _orderEntry.value.copy(cart = cart)
    }

    /**
     * Submit the cart as a new order via the admin token — after a short
     * [STAFF_ORDER_HOLD_SECONDS] hold during which the cashier can cancel a mis-tap.
     * The actual POST only fires once the countdown elapses.
     */
    fun submitOrder() {
        val entry = _orderEntry.value
        val tableId = entry.tableId ?: return
        if (entry.cart.isEmpty()) return
        if (orderHoldJob?.isActive == true) return

        val items = entry.cart.map { NewOrderItem(menuItemId = it.menuItem.id, quantity = it.quantity) }

        orderHoldJob = viewModelScope.launch {
            for (s in STAFF_ORDER_HOLD_SECONDS downTo 1) {
                _orderEntry.value = _orderEntry.value.copy(holdRemaining = s, error = null)
                kotlinx.coroutines.delay(1000)
            }
            _orderEntry.value = _orderEntry.value.copy(holdRemaining = null, isSubmitting = true)

            when (val result = apiClient.createOrder(tableId, items, "STAFF")) {
                is ApiResult.Success -> {
                    _orderEntry.value = OrderEntryState(successMessage = str().orderCreated)
                }
                is ApiResult.Error -> {
                    _orderEntry.value = _orderEntry.value.copy(
                        isSubmitting = false,
                        error = str().msgFailed.format(result.message),
                    )
                }
                is ApiResult.NetworkError -> {
                    _orderEntry.value = _orderEntry.value.copy(
                        isSubmitting = false,
                        error = str().msgNetworkError.format(result.message),
                    )
                }
            }
        }
    }

    /** Cancel the pre-send hold — nothing is created; the cart stays for editing. */
    fun cancelSubmitHold() {
        orderHoldJob?.cancel()
        orderHoldJob = null
        _orderEntry.value = _orderEntry.value.copy(holdRemaining = null, isSubmitting = false)
    }

    fun dismissOrderEntry() {
        orderHoldJob?.cancel()
        orderHoldJob = null
        _orderEntry.value = OrderEntryState()
    }

    fun clearOrderEntryMessages() {
        _orderEntry.value = _orderEntry.value.copy(error = null, successMessage = null)
    }

    // --- Order Detail Operations ---

    /** Load order detail for a specific table. */
    fun loadOrderForTable(tableId: String) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null, successMessage = null)
            val order = orderDao.getActiveOrderForTable(tableId)
            val items = if (order != null) orderDao.getItemsForOrder(order.id) else emptyList()
            _orderDetail.value = OrderDetailState(
                order = order,
                items = items,
                isLoading = false
            )
        }
    }

    /**
     * Reprint the kitchen slip for ONE session (order round) only — e.g. the printer jammed
     * for that round, or the kitchen needs the ticket for a specific round again. Scoping to
     * a single session (rather than the whole ticket) means the kitchen gets a slip clearly
     * marked "Session #N" and can tell a freshly-placed round apart from earlier rounds that
     * were already cooked/served. Does not change order status.
     */
    fun reprintSession(orderId: String, sessionNumber: Int) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            when (val result = apiClient.sendToKitchen(orderId, sessionNumber)) {
                is ApiResult.Success -> {
                    val response = result.data
                    val order = orderDao.getOrderById(orderId)
                    if (order != null && response.linesToPrint.isNotEmpty()) {
                        printService.printKitchenSlip(
                            tableId = order.tableId,
                            items = response.linesToPrint.map { it.toEntity(orderId) },
                            isAmendment = sessionNumber > 1,
                            sessionNumber = sessionNumber
                        )
                    }

                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        successMessage = str().reprintedSession.format(sessionNumber, response.linesToPrint.size)
                    )
                }
                is ApiResult.Error -> {
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        error = str().msgFailed.format(result.message)
                    )
                }
                is ApiResult.NetworkError -> {
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        error = str().msgNetworkError.format(result.message)
                    )
                }
            }
        }
    }

    /**
     * Confirm (first-time print) a single pending session's items — used by the
     * Pending_Section UI that appears when `customerOrderAutoPrint` is off, so items
     * arrive `sentToKitchen=false` and wait for the cashier. Scopes the backend
     * `sentToKitchen` mutation + printed slip to just [sessionNumber], reflects the
     * now-confirmed items into Room (REPLACE by id), and prints them as an amendment.
     */
    /**
     * Clear a pending session WITHOUT printing it — marks its items sent-to-kitchen (so the
     * group leaves the Pending Kitchen Prints list and folds into the normal order display)
     * but never dispatches a slip. Used to dismiss stale/erroneous pending prints.
     */
    fun dismissPendingSession(orderId: String, sessionNumber: Int) {
        viewModelScope.launch {
            when (val result = apiClient.sendToKitchen(orderId, sessionNumber)) {
                is ApiResult.Success -> {
                    val response = result.data
                    if (response.linesToPrint.isNotEmpty()) {
                        orderDao.insertOrderItems(response.linesToPrint.map { it.toEntity(orderId) })
                    }
                    refreshOrderDetail(orderId)
                }
                is ApiResult.Error ->
                    _orderDetail.value = _orderDetail.value.copy(error = str().msgFailed.format(result.message))
                is ApiResult.NetworkError ->
                    _orderDetail.value = _orderDetail.value.copy(error = str().msgNetworkError.format(result.message))
            }
        }
    }

    fun confirmSession(orderId: String, sessionNumber: Int) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            when (val result = apiClient.sendToKitchen(orderId, sessionNumber)) {
                is ApiResult.Success -> {
                    val response = result.data
                    val order = orderDao.getOrderById(orderId)

                    // Reflect the confirmed items (sentToKitchen=true) back into Room so the
                    // pending block folds into the normal confirmed display on refresh.
                    if (response.linesToPrint.isNotEmpty()) {
                        orderDao.insertOrderItems(response.linesToPrint.map { it.toEntity(orderId) })
                    }

                    if (order != null && response.linesToPrint.isNotEmpty()) {
                        printService.printKitchenSlip(
                            tableId = order.tableId,
                            items = response.linesToPrint.map { it.toEntity(orderId) },
                            isAmendment = sessionNumber > 1,
                            sessionNumber = sessionNumber
                        )
                    }

                    refreshOrderDetail(orderId)
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        successMessage = str().sessionSentToKitchen.format(sessionNumber, response.linesToPrint.size)
                    )
                }
                is ApiResult.Error -> {
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        error = str().msgFailed.format(result.message)
                    )
                }
                is ApiResult.NetworkError -> {
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        error = str().msgNetworkError.format(result.message)
                    )
                }
            }
        }
    }

    /** Add items to an existing order (amendment). */
    fun addItems(orderId: String, items: List<NewOrderItem>) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            when (val result = apiClient.addItemsToOrder(orderId, items)) {
                is ApiResult.Success -> {
                    // Reconcile new items to Room
                    reconcileOrderFromDto(result.data)
                    refreshOrderDetail(orderId)
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        successMessage = str().itemsAdded
                    )
                }
                is ApiResult.Error -> {
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        error = str().failedToAddItems.format(result.message)
                    )
                }
                is ApiResult.NetworkError -> {
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        error = str().msgNetworkError.format(result.message)
                    )
                }
            }
        }
    }

    /** Update order status (PREPARING/READY). */
    fun updateStatus(orderId: String, status: String) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            when (val result = apiClient.updateOrderStatus(orderId, status)) {
                is ApiResult.Success -> {
                    orderDao.updateOrderStatus(orderId, status)
                    refreshOrderDetail(orderId)
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        successMessage = str().statusUpdatedTo.format(status)
                    )
                }
                is ApiResult.Error -> {
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        error = str().msgFailed.format(result.message)
                    )
                }
                is ApiResult.NetworkError -> {
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        error = str().msgNetworkError.format(result.message)
                    )
                }
            }
        }
    }

    /**
     * Process payment (CASH/QR). Only valid after SENT_TO_KITCHEN.
     * [printReceipt] reflects the admin's choice in the print-confirm dialog shown
     * before this is called — payment completes and the table frees either way.
     */
    fun processPayment(orderId: String, method: String, printReceipt: Boolean) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            when (val result = apiClient.processPayment(orderId, method)) {
                is ApiResult.Success -> {
                    // Get order + items before completing (for receipt)
                    val order = orderDao.getOrderById(orderId)
                    val items = orderDao.getItemsForOrder(orderId)

                    orderDao.completePayment(orderId, method)

                    if (printReceipt && order != null) {
                        val cafeName = resolveCafeName()
                        printService.printReceipt(
                            order = order,
                            items = items,
                            paymentMethod = method,
                            cafeName = cafeName
                        )
                    }

                    _orderDetail.value = OrderDetailState(
                        isLoading = false,
                        successMessage = str().paymentCompleted.format(method)
                    )
                }
                is ApiResult.Error -> {
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        error = str().paymentFailedMsg.format(result.message)
                    )
                }
                is ApiResult.NetworkError -> {
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        error = str().msgNetworkError.format(result.message)
                    )
                }
            }
        }
    }

    /** Cancel order with reason. */
    fun cancelOrder(orderId: String, reason: String) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            when (val result = apiClient.cancelOrder(orderId, reason, "admin")) {
                is ApiResult.Success -> {
                    orderDao.cancelOrder(orderId, reason, "admin")
                    _orderDetail.value = OrderDetailState(
                        isLoading = false,
                        successMessage = str().orderCancelled
                    )
                }
                is ApiResult.Error -> {
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        error = str().cancelFailedMsg.format(result.message)
                    )
                }
                is ApiResult.NetworkError -> {
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        error = str().msgNetworkError.format(result.message)
                    )
                }
            }
        }
    }

    /** Clear messages after they've been shown. */
    fun clearMessages() {
        _orderDetail.value = _orderDetail.value.copy(error = null, successMessage = null)
    }

    /** Clear order detail (close bottom sheet). */
    fun clearOrderDetail() {
        _orderDetail.value = OrderDetailState()
    }

    // --- Table Management ---

    /**
     * Rehydrate the local table registry from the backend if — and only if — the
     * local list is empty. This covers a fresh install/relogin where a prior device
     * already pushed tables to the server: without this, the phone starts with zero
     * tables and looks like all previously-created tables were lost, even though
     * they're intact server-side. Only runs when local is empty so it never clobbers
     * real local edits made while offline (the phone is still authoritative once it
     * has *something* to be authoritative over).
     */
    fun rehydrateTablesIfEmpty() {
        viewModelScope.launch {
            if (tableDao.getCount() > 0) return@launch
            when (val result = apiClient.getTables()) {
                is ApiResult.Success -> {
                    result.data.forEachIndexed { index, (id, label) ->
                        tableDao.insert(Table(id = id, label = label, sortOrder = index))
                    }

                    // A fresh install's SystemSettings.nextTableNumber restarts at 1 —
                    // bump it past the highest rehydrated "T####" suffix so addTable()
                    // doesn't immediately collide with a table just pulled from the server.
                    val highestExisting = result.data
                        .mapNotNull { (id, _) -> Regex("""^T(\d+)$""").find(id)?.groupValues?.get(1)?.toIntOrNull() }
                        .maxOrNull() ?: 0
                    if (highestExisting > 0) {
                        val settings = settingsDao.get() ?: SystemSettings()
                        if (settings.nextTableNumber <= highestExisting) {
                            settingsDao.upsert(settings.copy(nextTableNumber = highestExisting + 1))
                        }
                    }
                }
                else -> { /* best-effort — admin can still add tables manually */ }
            }
        }
    }

    fun showTableManagement() {
        viewModelScope.launch {
            val tables = tableDao.getAll()
            _tableManagement.value = TableManagementState(
                isVisible = true,
                tables = tables
            )
            // Catch up any tables added before backend sync existed, or if a prior
            // push failed (e.g. offline) — cheap idempotent resync on every open.
            pushTablesToBackend(tables)
        }
    }

    fun hideTableManagement() {
        _tableManagement.value = TableManagementState()
    }

    fun updateNewTableLabel(label: String) {
        _tableManagement.value = _tableManagement.value.copy(newTableLabel = label, error = null)
    }

    /**
     * Add a table with an auto-generated ID (T0001..T9999, always incrementing — see
     * [SystemSettings.nextTableNumber]; deleting a table never frees its number for reuse).
     * Capped at [MAX_TABLES] concurrently registered tables.
     */
    fun addTable() {
        viewModelScope.launch {
            val count = tableDao.getCount()
            if (count >= MAX_TABLES) {
                _tableManagement.value = _tableManagement.value.copy(
                    error = str().maxTablesReached.format(MAX_TABLES)
                )
                return@launch
            }

            val settings = settingsDao.get() ?: SystemSettings()
            if (settings.nextTableNumber > MAX_TABLE_NUMBER) {
                _tableManagement.value = _tableManagement.value.copy(
                    error = str().tableNumberLimitReached.format(MAX_TABLE_NUMBER)
                )
                return@launch
            }

            val id = "T" + settings.nextTableNumber.toString().padStart(4, '0')
            val label = _tableManagement.value.newTableLabel.trim().ifEmpty { id }

            tableDao.insert(Table(id = id, label = label, sortOrder = count))
            settingsDao.upsert(settings.copy(nextTableNumber = settings.nextTableNumber + 1))

            val tables = tableDao.getAll()
            _tableManagement.value = _tableManagement.value.copy(
                tables = tables,
                newTableLabel = "",
                error = null
            )
            pushTablesToBackend(tables)
        }
    }

    fun startEditTable(table: Table) {
        _tableManagement.value = _tableManagement.value.copy(
            editingTable = table,
            editLabel = table.label
        )
    }

    fun updateEditLabel(label: String) {
        _tableManagement.value = _tableManagement.value.copy(editLabel = label)
    }

    fun saveEditTable() {
        val state = _tableManagement.value
        val table = state.editingTable ?: return
        val newLabel = state.editLabel.trim().ifEmpty { table.id }

        viewModelScope.launch {
            tableDao.update(table.copy(label = newLabel))
            val tables = tableDao.getAll()
            _tableManagement.value = _tableManagement.value.copy(
                tables = tables,
                editingTable = null,
                editLabel = ""
            )
            pushTablesToBackend(tables)
        }
    }

    fun cancelEdit() {
        _tableManagement.value = _tableManagement.value.copy(
            editingTable = null,
            editLabel = ""
        )
    }

    fun deleteTable(tableId: String) {
        viewModelScope.launch {
            tableDao.delete(tableId)
            val tables = tableDao.getAll()
            _tableManagement.value = _tableManagement.value.copy(tables = tables)
            pushTablesToBackend(tables)
        }
    }

    /**
     * Push the full local table list to the backend so order submission (this app)
     * and customer QR ordering both validate tableId against an up-to-date registry.
     * Best-effort: a push failure here doesn't block the local edit — the next
     * successful add/edit/delete (or reopening Manage Tables) retries with the
     * then-current full list. A table still referenced by a live/historical order
     * is kept server-side even if it's gone from the local list (`skippedInUse`) —
     * that's surfaced as a warning, not an error, since it's not a sync failure.
     */
    private suspend fun pushTablesToBackend(tables: List<Table>) {
        when (val result = apiClient.putTables(tables.map { it.id to it.label })) {
            is ApiResult.Success -> {
                val skipped = result.data
                _tableManagement.value = _tableManagement.value.copy(
                    error = if (skipped.isNotEmpty()) {
                        "Note: ${skipped.joinToString(", ")} kept on server (has order history)"
                    } else null
                )
            }
            is ApiResult.Error -> {
                _tableManagement.value = _tableManagement.value.copy(
                    error = "Failed to sync tables to server: ${result.message}"
                )
            }
            is ApiResult.NetworkError -> {
                _tableManagement.value = _tableManagement.value.copy(
                    error = "Network error syncing tables: ${result.message}"
                )
            }
        }
    }

    // --- Private helpers ---

    private suspend fun refreshOrderDetail(orderId: String) {
        val order = orderDao.getOrderById(orderId)
        val items = if (order != null) orderDao.getItemsForOrder(orderId) else emptyList()
        _orderDetail.value = _orderDetail.value.copy(order = order, items = items)
    }

    /**
     * Resolve the café name for the printed receipt from the live backend branding
     * ([_cafeName], loaded on init). Re-fetches if it hasn't loaded yet so a rename in
     * Settings is reflected on the very next receipt. Falls back to a generic label only
     * if branding truly can't be reached.
     */
    private suspend fun resolveCafeName(): String {
        if (_cafeName.value.isBlank()) {
            when (val result = apiClient.getBranding()) {
                is ApiResult.Success -> _cafeName.value = result.data.cafeName
                else -> { /* keep blank; fall through to default */ }
            }
        }
        return _cafeName.value.ifBlank { "Café" }
    }

    private suspend fun reconcileOrderFromDto(dto: OrderDto) {
        orderDao.insertOrder(dto.toEntity())
        if (dto.items.isNotEmpty()) {
            orderDao.insertOrderItems(dto.items.map { it.toEntity(dto.id) })
        }
    }
}
