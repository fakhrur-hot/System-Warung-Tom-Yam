package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.NewOrderItem
import com.razstudio.pos.data.VoidLine
import com.razstudio.pos.data.OrderDto
import com.razstudio.pos.data.json.optStringOrNull
import com.razstudio.pos.data.json.toEntity
import com.razstudio.pos.data.local.MenuCategoryStore
import com.razstudio.pos.data.local.MenuItem
import com.razstudio.pos.data.local.MenuDao
import com.razstudio.pos.data.local.Order
import com.razstudio.pos.data.local.OrderDao
import com.razstudio.pos.data.local.OrderItem
import com.razstudio.pos.data.local.OrderStatus
import com.razstudio.pos.data.local.PendingOrder
import com.razstudio.pos.data.local.PendingOrderDao
import com.razstudio.pos.data.local.SettingsDao
import com.razstudio.pos.data.local.SystemSettings
import com.razstudio.pos.data.local.Table
import com.razstudio.pos.data.local.TableDao
import com.razstudio.pos.ui.i18n.LanguageManager
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.tableview.OrderDetailState
import com.razstudio.pos.ui.tableview.StaffPermissions
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
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for the staff ordering screen (Task 20).
 *
 * Provides:
 * - Table grid with status (same realtime data as admin via Room)
 * - RBAC-controlled actions (cancel always, kitchen/payment gated by settings)
 * - Order entry flow: table → menu (available items only) → cart → submit
 * - Offline queue: failed orders queued in PendingOrder table
 * - Catch-up sync via ordering API key
 *
 * State types [TableState], [TableUiStatus], and [OrderDetailState] are the shared types from
 * [com.razstudio.pos.ui.tableview.TableViewModels] (Requirement 5.4).
 * [StaffPermissions] is the shared type from [com.razstudio.pos.ui.tableview.StaffPermissions].
 */
@HiltViewModel
class StaffOrderViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val orderDao: OrderDao,
    private val tableDao: TableDao,
    private val menuDao: MenuDao,
    private val categoryStore: MenuCategoryStore,
    private val settingsDao: SettingsDao,
    private val pendingOrderDao: PendingOrderDao,
    private val languageManager: LanguageManager
) : ViewModel() {

    private fun str() = uiStrings(languageManager.language.value)

    // --- Cart state for new order entry ---

    data class CartItem(
        val menuItem: MenuItem,
        val quantity: Int,
        val note: String? = null,
        val size: String? = null,
        val unitPrice: Double? = null
    )

    data class OrderEntryState(
        val isVisible: Boolean = false,
        val selectedTableId: String? = null,
        val selectedTableLabel: String? = null,
        val menuItems: List<MenuItem> = emptyList(),
        /** Admin-defined category order, so staff order-entry tabs match Menu Management. */
        val categoryOrder: List<String> = emptyList(),
        val cart: List<CartItem> = emptyList(),
        val isSubmitting: Boolean = false,
        val holdRemaining: Int? = null,
        val error: String? = null,
        val successMessage: String? = null
    )

    // Combined flow: tables + active orders → shared TableState
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

    /** Available menu items, for the add-item picker in [com.razstudio.pos.ui.tableview.OrderDetailSheet]. */
    val availableMenu: StateFlow<List<MenuItem>> = menuDao.getAvailableFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _orderDetail = MutableStateFlow(OrderDetailState())
    val orderDetail: StateFlow<OrderDetailState> = _orderDetail.asStateFlow()

    private val _orderEntry = MutableStateFlow(OrderEntryState())
    val orderEntry: StateFlow<OrderEntryState> = _orderEntry.asStateFlow()
    private var orderHoldJob: kotlinx.coroutines.Job? = null

    companion object {
        // Fixed pre-send hold for staff orders (mis-tap guard).
        const val STAFF_ORDER_HOLD_SECONDS = 3
    }

    private val _permissions = MutableStateFlow(StaffPermissions(
        canSendToKitchen = false,
        canTakePayment = false,
        canCancel = true,
    ))
    val permissions: StateFlow<StaffPermissions> = _permissions.asStateFlow()

    // Pending orders count for offline banner
    val pendingOrderCount: StateFlow<Int> = pendingOrderDao.getCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        loadPermissions()
        performCatchUpSync()
    }

    // --- Permissions ---

    private fun loadPermissions() {
        viewModelScope.launch {
            val settings = settingsDao.get() ?: SystemSettings()
            _permissions.value = StaffPermissions(
                canSendToKitchen = settings.staffCanSendKitchen,
                canTakePayment = settings.staffCanTakePayment,
                canCancel = true
            )
        }
    }

    /** Refresh permissions (e.g., after settings broadcast). */
    fun refreshPermissions() {
        loadPermissions()
    }

    // --- Order Detail ---

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

    fun sendToKitchen(orderId: String) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            when (val result = apiClient.sendToKitchenAsStaff(orderId)) {
                is ApiResult.Success -> {
                    val response = result.data
                    orderDao.markAllItemsSentToKitchen(orderId)
                    orderDao.markSentToKitchen(
                        orderId = orderId,
                        timestamp = response.order.sentToKitchenAt ?: "",
                        status = OrderStatus.SENT_TO_KITCHEN.name
                    )
                    refreshOrderDetail(orderId)
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        successMessage = str().sentToKitchenItems.format(response.linesToPrint.size)
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
     * Staff-side per-session reprint. Staff devices have no printer, so this only re-sends
     * the session to the backend for signature parity with the admin sheet's per-session
     * reprint button; the admin device owns physical printing. Kept so the shared
     * OrderDetailSheet works identically for staff.
     */
    fun reprintSession(orderId: String, sessionNumber: Int) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)
            when (val result = apiClient.sendToKitchenAsStaff(orderId, sessionNumber)) {
                is ApiResult.Success -> {
                    _orderDetail.value = _orderDetail.value.copy(
                        isLoading = false,
                        successMessage = str().sessionResentToKitchen.format(sessionNumber)
                    )
                }
                is ApiResult.Error -> _orderDetail.value = _orderDetail.value.copy(
                    isLoading = false, error = str().msgFailed.format(result.message)
                )
                is ApiResult.NetworkError -> _orderDetail.value = _orderDetail.value.copy(
                    isLoading = false, error = str().msgNetworkError.format(result.message)
                )
            }
        }
    }

    /**
     * Confirm (first-time send) a single pending session — the staff-side counterpart to
     * the admin `confirmSession`. Scopes the backend `sentToKitchen` mutation to just
     * [sessionNumber] and reflects the confirmed items into Room. Staff devices have no
     * printer, so this just sends + updates state (the admin device prints on its own
     * ITEMS_ADDED path); the pending block folds into the confirmed display on refresh.
     */
    fun confirmSession(orderId: String, sessionNumber: Int) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            when (val result = apiClient.sendToKitchenAsStaff(orderId, sessionNumber)) {
                is ApiResult.Success -> {
                    val response = result.data
                    if (response.linesToPrint.isNotEmpty()) {
                        orderDao.insertOrderItems(response.linesToPrint.map { it.toEntity(orderId) })
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

    /**
     * Process payment (CASH/QR). [printReceipt] is accepted for signature parity with
     * the admin ViewModel's payment-confirm-dialog flow, but staff devices have no
     * printer attached — only the admin device ever prints receipts.
     */
    fun processPayment(orderId: String, method: String, @Suppress("UNUSED_PARAMETER") printReceipt: Boolean) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            when (val result = apiClient.processPaymentAsStaff(orderId, method)) {
                is ApiResult.Success -> {
                    orderDao.completePayment(orderId, method)
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

    fun cancelOrder(orderId: String, reason: String) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            when (val result = apiClient.cancelOrderAsStaff(orderId, reason, "staff")) {
                is ApiResult.Success -> {
                    orderDao.cancelOrder(orderId, reason, "staff")
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

    fun addItemsToOrder(orderId: String, items: List<NewOrderItem>) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            when (val result = apiClient.addItemsToOrderAsStaff(orderId, items)) {
                is ApiResult.Success -> {
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
     * Void lines the customer never received. Staff credential variant of the admin path; the server
     * enforces the same rules (order must be open, cannot void every line) for both.
     *
     * Room is reconciled destructively because [reconcileOrderFromDto] only inserts — a voided line
     * left behind locally would still show on this device's sheet and in its totals.
     */
    fun voidItems(orderId: String, lines: List<VoidLine>, reason: String) {
        if (lines.isEmpty()) return
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            when (val result = apiClient.voidOrderItemsAsStaff(orderId, lines, reason)) {
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

    fun clearMessages() {
        _orderDetail.value = _orderDetail.value.copy(error = null, successMessage = null)
    }

    fun clearOrderDetail() {
        _orderDetail.value = OrderDetailState()
    }

    // --- Order Entry Flow ---

    /** Start new order entry for a given table. */
    fun startOrderEntry(tableId: String, tableLabel: String) {
        viewModelScope.launch {
            // Load available menu items (filter out unavailable + askMeDaily-off)
            val allItems = menuDao.getAll()
            val availableItems = allItems.filter { it.available }

            _orderEntry.value = OrderEntryState(
                isVisible = true,
                selectedTableId = tableId,
                selectedTableLabel = tableLabel,
                menuItems = availableItems,
                categoryOrder = categoryStore.get(),
                cart = emptyList()
            )
        }
    }

    fun addToCart(
        menuItem: MenuItem,
        quantity: Int = 1,
        note: String? = null,
        size: String? = null,
        unitPrice: Double? = null,
    ) {
        val n = note?.trim()?.ifBlank { null }
        val currentCart = _orderEntry.value.cart.toMutableList()
        // Same dish + same note + same size merges (×2, ×3); a distinct note or size is its own line.
        val existing = currentCart.indexOfFirst {
            it.menuItem.id == menuItem.id && it.note == n && it.size == size
        }
        if (existing >= 0) {
            val item = currentCart[existing]
            currentCart[existing] = item.copy(quantity = item.quantity + quantity)
        } else {
            currentCart.add(CartItem(menuItem = menuItem, quantity = quantity, note = n, size = size, unitPrice = unitPrice))
        }
        _orderEntry.value = _orderEntry.value.copy(cart = currentCart)
    }

    fun removeFromCart(menuItemId: String) {
        val currentCart = _orderEntry.value.cart.toMutableList()
        currentCart.removeAll { it.menuItem.id == menuItemId }
        _orderEntry.value = _orderEntry.value.copy(cart = currentCart)
    }

    /** Remove a single cart line by position (notes make ids non-unique). */
    fun removeFromCartAt(index: Int) {
        val currentCart = _orderEntry.value.cart.toMutableList()
        if (index in currentCart.indices) currentCart.removeAt(index)
        _orderEntry.value = _orderEntry.value.copy(cart = currentCart)
    }

    fun updateCartItemQuantity(menuItemId: String, quantity: Int) {
        if (quantity <= 0) {
            removeFromCart(menuItemId)
            return
        }
        val currentCart = _orderEntry.value.cart.toMutableList()
        val idx = currentCart.indexOfFirst { it.menuItem.id == menuItemId }
        if (idx >= 0) {
            currentCart[idx] = currentCart[idx].copy(quantity = quantity)
            _orderEntry.value = _orderEntry.value.copy(cart = currentCart)
        }
    }

    fun updateCartItemNote(menuItemId: String, note: String?) {
        val currentCart = _orderEntry.value.cart.toMutableList()
        val idx = currentCart.indexOfFirst { it.menuItem.id == menuItemId }
        if (idx >= 0) {
            currentCart[idx] = currentCart[idx].copy(note = note)
            _orderEntry.value = _orderEntry.value.copy(cart = currentCart)
        }
    }

    /** Cancel the pre-send hold — nothing is created; the cart stays for editing. */
    fun cancelSubmitHold() {
        orderHoldJob?.cancel()
        orderHoldJob = null
        _orderEntry.value = _orderEntry.value.copy(holdRemaining = null, isSubmitting = false)
    }

    /**
     * Submit the cart as a new order, after a fixed [STAFF_ORDER_HOLD_SECONDS] mis-tap hold.
     * On network failure, queue to PendingOrder.
     */
    fun submitOrder() {
        val entry = _orderEntry.value
        val tableId = entry.selectedTableId ?: return
        val cart = entry.cart
        if (cart.isEmpty()) return
        if (orderHoldJob?.isActive == true) return

        val items = cart.map { cartItem ->
            NewOrderItem(
                menuItemId = cartItem.menuItem.id,
                quantity = cartItem.quantity,
                note = cartItem.note,
                unitPrice = cartItem.unitPrice,
                size = cartItem.size
            )
        }

        orderHoldJob = viewModelScope.launch {
            for (s in STAFF_ORDER_HOLD_SECONDS downTo 1) {
                _orderEntry.value = _orderEntry.value.copy(holdRemaining = s, error = null)
                kotlinx.coroutines.delay(1000)
            }
            _orderEntry.value = _orderEntry.value.copy(holdRemaining = null, isSubmitting = true)

            when (val result = apiClient.createOrderAsStaff(tableId, items)) {
                is ApiResult.Success -> {
                    _orderEntry.value = OrderEntryState(
                        successMessage = str().orderCreatedTotal.format(result.data.total)
                    )
                    // Retry any pending orders now that we have connectivity
                    retryPendingOrders()
                }
                is ApiResult.Error -> {
                    _orderEntry.value = _orderEntry.value.copy(
                        isSubmitting = false,
                        error = result.message
                    )
                }
                is ApiResult.NetworkError -> {
                    // Queue to offline pending orders
                    queuePendingOrder(tableId, items)
                    _orderEntry.value = OrderEntryState(
                        successMessage = str().orderQueuedOffline
                    )
                }
            }
        }
    }

    fun dismissOrderEntry() {
        _orderEntry.value = OrderEntryState()
    }

    fun clearOrderEntryMessages() {
        _orderEntry.value = _orderEntry.value.copy(error = null, successMessage = null)
    }

    // --- Offline Queue ---

    private suspend fun queuePendingOrder(tableId: String, items: List<NewOrderItem>) {
        val itemsJson = JSONArray().apply {
            items.forEach { item ->
                put(JSONObject().apply {
                    put("menuItemId", item.menuItemId)
                    put("quantity", item.quantity)
                    if (item.note != null) put("note", item.note)
                    if (item.unitPrice != null) put("unitPrice", item.unitPrice)
                    if (item.size != null) put("size", item.size)
                })
            }
        }.toString()

        val pending = PendingOrder(
            id = UUID.randomUUID().toString(),
            tableId = tableId,
            itemsJson = itemsJson,
            createdAt = Instant.now().toString()
        )
        pendingOrderDao.insert(pending)
    }

    /** Retry all pending orders. Called when connectivity is restored. */
    fun retryPendingOrders() {
        viewModelScope.launch {
            val pendingOrders = pendingOrderDao.getAll()
            for (pending in pendingOrders) {
                val items = parsePendingItems(pending.itemsJson)
                when (apiClient.createOrderAsStaff(pending.tableId, items)) {
                    is ApiResult.Success -> {
                        pendingOrderDao.delete(pending.id)
                    }
                    is ApiResult.Error -> {
                        // Non-retryable error (e.g., TABLE_OCCUPIED), remove it
                        pendingOrderDao.delete(pending.id)
                    }
                    is ApiResult.NetworkError -> {
                        // Still offline, increment retry and stop
                        pendingOrderDao.incrementRetryCount(pending.id)
                        break
                    }
                }
            }
        }
    }

    private fun parsePendingItems(json: String): List<NewOrderItem> {
        val items = mutableListOf<NewOrderItem>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                items.add(
                    NewOrderItem(
                        menuItemId = obj.getString("menuItemId"),
                        quantity = obj.getInt("quantity"),
                        note = obj.optStringOrNull("note"),
                        unitPrice = if (obj.has("unitPrice")) obj.getDouble("unitPrice") else null,
                        size = obj.optStringOrNull("size")
                    )
                )
            }
        } catch (_: Exception) { }
        return items
    }

    // --- Catch-up Sync ---

    private fun performCatchUpSync() {
        viewModelScope.launch {
            when (val result = apiClient.getOrdersSinceAsStaff(
                Instant.now().minusSeconds(86400).toString()
            )) {
                is ApiResult.Success -> {
                    for (dto in result.data.orders) {
                        reconcileOrderFromDto(dto)
                    }
                }
                is ApiResult.Error -> { /* silently fail on startup sync */ }
                is ApiResult.NetworkError -> { /* offline — will sync later */ }
            }

            // Also fetch latest settings
            when (val result = apiClient.getSettings()) {
                is ApiResult.Success -> {
                    val s = result.data
                    settingsDao.upsert(
                        SystemSettings(
                            printLanguage = s.printLanguage,
                            timezone = s.timezone,
                            topN = s.topN,
                            staffCanSendKitchen = s.staffCanSendKitchen,
                            staffCanTakePayment = s.staffCanTakePayment
                        )
                    )
                    _permissions.value = StaffPermissions(
                        canSendToKitchen = s.staffCanSendKitchen,
                        canTakePayment = s.staffCanTakePayment,
                        canCancel = true
                    )
                }
                is ApiResult.Error -> { }
                is ApiResult.NetworkError -> { }
            }

            // Also fetch latest menu from backend (public endpoint) and sync to Room.
            when (val menuResult = apiClient.getMenu()) {
                is ApiResult.Success -> {
                    if (menuResult.data.configured) {
                        val entities = menuResult.data.items.map { dto ->
                            MenuItem(
                                id = dto.id,
                                category = dto.category,
                                // Carry the "also show in" extra categories so staff order-taking
                                // shows the item under every tagged category, not just its primary.
                                extraCategories = dto.extraCategories,
                                code = dto.code,
                                price = dto.price,
                                marketPrice = dto.marketPrice,
                                available = dto.available,
                                askMeDaily = dto.askMeDaily,
                                nameEn = dto.nameEn,
                                nameBm = dto.nameBm,
                                nameZh = dto.nameZh,
                                nameTa = dto.nameTa,
                                nameTh = dto.nameTh,
                                doNotTranslate = dto.doNotTranslate
                            )
                        }
                        try {
                            menuDao.deleteAll()
                            menuDao.upsertAll(entities)
                            val categoryNames = menuResult.data.categories.map { it.name }
                                .ifEmpty { entities.map { it.category }.filter { it.isNotBlank() }.distinct() }
                            if (categoryNames.isNotEmpty()) categoryStore.set(categoryNames)
                        } catch (_: Exception) { /* silent best-effort */ }
                    }
                }
                is ApiResult.Error -> { }
                is ApiResult.NetworkError -> { }
            }
        }
    }

    // --- Helpers ---

    private suspend fun refreshOrderDetail(orderId: String) {
        val order = orderDao.getOrderById(orderId)
        val items = if (order != null) orderDao.getItemsForOrder(orderId) else emptyList()
        _orderDetail.value = _orderDetail.value.copy(order = order, items = items)
    }

    private suspend fun reconcileOrderFromDto(dto: OrderDto) {
        orderDao.insertOrder(dto.toEntity())
        if (dto.items.isNotEmpty()) {
            orderDao.insertOrderItems(dto.items.map { it.toEntity(dto.id) })
        }
    }
}
