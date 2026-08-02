package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.NewOrderItem
import com.razstudio.pos.data.VoidLine
import com.razstudio.pos.data.OrderDto
import com.razstudio.pos.data.json.toEntity
import com.razstudio.pos.data.local.MenuDao
import com.razstudio.pos.data.local.MenuItem
import com.razstudio.pos.data.local.Order
import com.razstudio.pos.data.local.OrderDao
import com.razstudio.pos.data.local.OrderItem
import com.razstudio.pos.data.local.OrderStatus
import com.razstudio.pos.data.local.SettingsDao
import com.razstudio.pos.data.local.SystemSettings
import com.razstudio.pos.data.local.TAKEOUT_PREFIX
import com.razstudio.pos.data.local.Table
import com.razstudio.pos.data.local.TableDao
import com.razstudio.pos.data.local.isTakeout
import com.razstudio.pos.printing.PrintService
import com.razstudio.pos.ui.i18n.LanguageManager
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.tableview.OrderDetailState
import com.razstudio.pos.ui.tableview.TableState
import com.razstudio.pos.ui.tableview.TableUiStatus
import com.razstudio.pos.ui.tableview.toTableUiStatus
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
 * [com.razstudio.pos.ui.tableview.TableViewModels] (Requirement 5.4).
 */
@HiltViewModel
class TableViewViewModel @Inject constructor(
    private val apiClient: BackendGateway,
    private val orderDao: OrderDao,
    private val tableDao: TableDao,
    private val settingsDao: SettingsDao,
    private val menuDao: MenuDao,
    private val categoryStore: com.razstudio.pos.data.local.MenuCategoryStore,
    private val printService: PrintService,
    private val languageManager: LanguageManager,
    private val paymentQrResolver: com.razstudio.pos.data.PaymentQrResolver,
    modeRepository: com.razstudio.pos.data.ModeRepository,
) : ViewModel() {

    /**
     * The café's active topology, for the badge in the admin top bar (task 9.4, Requirement 1.4).
     *
     * Surfaced from [com.razstudio.pos.data.ModeRepository] rather than read once at construction, so
     * a mode changed in Setup is reflected the moment the operator returns rather than at next launch
     * — a badge that can be stale about the one thing it exists to report is worse than none.
     */
    val activeMode: StateFlow<com.razstudio.pos.data.OperatingMode> = modeRepository.activeMode

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
        /**
         * Recorded on the original order's void audit when a share is lifted off. Split items are
         * not wastage, and this reason is the only thing that tells them apart there.
         */
        const val SPLIT_REASON = "Split payment"

        const val MAX_TABLES = 30
        const val MAX_TABLE_NUMBER = 9999
        // Take-out ("Tapaw") slots are additional to the dine-in cap (30 + 6 = 36 total).
        const val MAX_TAKEOUT = 6
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

    /** Available menu items, for the add-item picker in [com.razstudio.pos.ui.tableview.OrderDetailSheet]. */
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
                is ApiResult.Success -> {
                    _cafeName.value = result.data.cafeName
                    // Task 16.3 — reconcile this device's cached Payment QR against the café's current
                    // one (Requirements 14.5, 14.6). Hooked here because Table View is the screen every
                    // payment-taking device opens and keeps open, so a replacement propagates without a
                    // dedicated sync path.
                    //
                    // The resolver is a no-op when the hashes already agree, and it treats the admin
                    // device as the origin of its own upload rather than letting the server overwrite
                    // it. Best-effort: a failure here must not disturb the header it was called for.
                    val outcome = paymentQrResolver.reconcile(
                        remoteHash = result.data.paymentQrHash,
                        remoteUrl = result.data.paymentQrUrl,
                    )
                    if (outcome != com.razstudio.pos.data.PaymentQrResolver.Outcome.NO_CHANGE) {
                        android.util.Log.i("TableViewVM", "Payment QR reconcile: $outcome")
                    }
                }
                else -> { /* leave blank */ }
            }
        }
    }

    // --- Order entry (new order on a free table) ---

    data class EntryCartItem(
        val menuItem: MenuItem,
        val quantity: Int,
        val note: String? = null,
        /** Chosen size label (e.g. "S"/"M"/"L") for a variable-price item; null otherwise. */
        val size: String? = null,
        /** Chosen size price; null means use the item's base price. */
        val unitPrice: Double? = null,
    )

    data class OrderEntryState(
        val isVisible: Boolean = false,
        val tableId: String? = null,
        val tableLabel: String = "",
        val menuItems: List<MenuItem> = emptyList(),
        /** Admin-defined category order, so the order-entry tabs match Menu Management. */
        val categoryOrder: List<String> = emptyList(),
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
                categoryOrder = categoryStore.get(),
            )
        }
    }

    fun addToCart(item: MenuItem, note: String? = null, size: String? = null, unitPrice: Double? = null) {
        val n = note?.trim()?.ifBlank { null }
        val cart = _orderEntry.value.cart.toMutableList()
        // Plain repeats of the same dish+size merge (×2, ×3); a different note or size is its
        // own line (so Small and Large of the same dish stay separate).
        val idx = cart.indexOfFirst { it.menuItem.id == item.id && it.note == n && it.size == size }
        if (idx >= 0) {
            cart[idx] = cart[idx].copy(quantity = cart[idx].quantity + 1)
        } else {
            cart.add(EntryCartItem(item, 1, n, size, unitPrice))
        }
        _orderEntry.value = _orderEntry.value.copy(cart = cart)
    }

    /** Remove a single cart line by its position (notes make ids non-unique). */
    fun removeFromCart(index: Int) {
        val cart = _orderEntry.value.cart.toMutableList()
        if (index in cart.indices) cart.removeAt(index)
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

        val items = entry.cart.map {
            NewOrderItem(
                menuItemId = it.menuItem.id,
                quantity = it.quantity,
                note = it.note,
                unitPrice = it.unitPrice,
                size = it.size,
            )
        }

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
                            orderNumber = order.orderNumber,
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
                            orderNumber = order.orderNumber,
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

    /**
     * Void lines the customer never received, so they can settle for what actually arrived.
     *
     * Room is reconciled destructively — every line for the order is deleted and re-inserted from the
     * server's response — because [reconcileOrderFromDto] only inserts. A voided line would otherwise
     * survive locally, and the receipt printed from Room seconds later would still bill for it.
     */
    fun voidItems(orderId: String, lines: List<VoidLine>, reason: String) {
        if (lines.isEmpty()) return
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            when (val result = apiClient.voidOrderItems(orderId, lines, reason)) {
                is ApiResult.Success -> {
                    orderDao.deleteItemsForOrder(orderId)
                    reconcileOrderFromDto(result.data)
                    refreshOrderDetail(orderId)
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        successMessage = str().itemsVoidedMsg
                    )
                }
                is ApiResult.Error -> {
                    // These three are states the cashier can act on, so they get their own wording
                    // instead of a raw server code in front of a waiting customer.
                    val message = when (result.code) {
                        "WOULD_EMPTY_ORDER" -> str().voidWouldEmptyOrderMsg
                        "ALREADY_VOIDED" -> str().voidAlreadyGoneMsg
                        "CANNOT_INCREASE" -> str().voidCannotIncreaseMsg
                        else -> str().failedToVoidItems.format(result.message)
                    }
                    _orderDetail.value = _orderDetail.value.copy(isLoading = false, error = message)
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
    /**
     * Settle one customer's share of a group bill.
     *
     * Three calls, in an order that matters: create the share as its own order, pay it, then shrink
     * the original by exactly what was lifted. Shrinking last means a failure anywhere leaves the
     * table still owing the full amount — the café can retry. Shrinking first and then failing to
     * charge would hand the food away.
     *
     * The final share never reaches here: [SplitPaymentPlanner] returns `SettleWholeOrder` for it,
     * and the caller pays the original through the normal path, which is what ends the table
     * session and offers the receipt.
     */
    fun paySplitShare(
        orderId: String,
        tableId: String?,
        plan: SplitPaymentPlanner.Plan.SliceOff,
        method: String,
    ) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            val created = apiClient.createOrder(tableId, plan.sliceItems, source = "STAFF")
            if (created !is ApiResult.Success) {
                _orderDetail.value = _orderDetail.value.copy(
                    isLoading = false, error = str().splitShareFailed,
                )
                return@launch
            }
            val shareId = created.data.orderId

            when (apiClient.processPayment(shareId, method)) {
                is ApiResult.Success -> Unit
                else -> {
                    // The share exists but is unpaid. Saying so beats a silent retry that would
                    // create a second one and double-charge the table.
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false, error = str().splitShareUnpaid,
                    )
                    return@launch
                }
            }
            orderDao.completePayment(shareId, method)

            when (val shrunk = apiClient.voidOrderItems(orderId, plan.keepLines, SPLIT_REASON)) {
                is ApiResult.Success -> {
                    // Destructive reconcile: reconcileOrderFromDto only inserts, so a line that
                    // shrank would keep its old quantity beside the new one.
                    orderDao.deleteItemsForOrder(orderId)
                    reconcileOrderFromDto(shrunk.data)
                    tableId?.let { loadOrderForTable(it) }
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        successMessage = str().splitSharePaid.format(method),
                    )
                }
                else -> _orderDetail.value = _orderDetail.value.copy(
                    isLoading = false, error = str().splitShareNotRemoved,
                )
            }
        }
    }

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
     * Add a table with an auto-generated ID. Reuses the LOWEST unused number (T0001, T0002,
     * …) so ids stay dense and aligned with the table count even after deletions — deleting
     * T0002 lets the next add reclaim it. Capped at [MAX_TABLES] concurrent tables.
     */
    fun addTable() {
        viewModelScope.launch {
            val all = tableDao.getAll()
            // Only dine-in tables count toward the dine-in cap; take-out (Tapaw) slots are separate.
            val dineInCount = all.count { !it.isTakeout }
            if (dineInCount >= MAX_TABLES) {
                _tableManagement.value = _tableManagement.value.copy(
                    error = str().maxTablesReached.format(MAX_TABLES)
                )
                return@launch
            }

            // Lowest unused number, so a deleted id (gap) is reclaimed before extending.
            // Take-out ids ("TAPAW1") aren't numeric after removePrefix("T"), so they're ignored here.
            val used = all.mapNotNull { it.id.removePrefix("T").toIntOrNull() }.toSet()
            var n = 1
            while (used.contains(n)) n++
            if (n > MAX_TABLE_NUMBER) {
                _tableManagement.value = _tableManagement.value.copy(
                    error = str().tableNumberLimitReached.format(MAX_TABLE_NUMBER)
                )
                return@launch
            }

            val id = "T" + n.toString().padStart(4, '0')
            val label = _tableManagement.value.newTableLabel.trim().ifEmpty { id }

            tableDao.insert(Table(id = id, label = label, sortOrder = dineInCount))
            // nextTableNumber is now only a monotonic hint (generation uses the gap-fill above).
            val settings = settingsDao.get() ?: SystemSettings()
            if (settings.nextTableNumber <= n) {
                settingsDao.upsert(settings.copy(nextTableNumber = n + 1))
            }

            val tables = tableDao.getAll()
            _tableManagement.value = _tableManagement.value.copy(
                tables = tables,
                newTableLabel = "",
                error = null
            )
            pushTablesToBackend(tables)
        }
    }

    /**
     * Add a take-out ("Tapaw") slot: ids TAPAW1..TAPAW6, labels "Tapaw 1".."Tapaw 6". These are
     * additional to the [MAX_TABLES] dine-in cap (up to [MAX_TAKEOUT]), have no printed QR card,
     * and reclaim the lowest free slot number after a deletion. Used for order-taking like tables.
     */
    fun addTakeoutTable() {
        viewModelScope.launch {
            val all = tableDao.getAll()
            val takeout = all.filter { it.isTakeout }
            if (takeout.size >= MAX_TAKEOUT) {
                _tableManagement.value = _tableManagement.value.copy(
                    error = str().maxTablesReached.format(MAX_TAKEOUT)
                )
                return@launch
            }
            // Lowest unused 1..MAX_TAKEOUT (reclaims a deleted slot before extending).
            val used = takeout.mapNotNull { it.id.removePrefix(TAKEOUT_PREFIX).toIntOrNull() }.toSet()
            var n = 1
            while (used.contains(n)) n++
            val id = "$TAKEOUT_PREFIX$n"
            tableDao.insert(Table(id = id, label = "Tapaw $n", sortOrder = 1000 + n))

            val tables = tableDao.getAll()
            _tableManagement.value = _tableManagement.value.copy(tables = tables, error = null)
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
