package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.GatewayConfigDto
import com.razstudio.pos.data.GatewayPaymentResult
import com.razstudio.pos.data.NewOrderItem
import com.razstudio.pos.data.toNewOrderItem
import com.razstudio.pos.data.PosCheckoutPayload
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
import com.razstudio.pos.data.local.PaymentCategory
import com.razstudio.pos.data.local.PaymentMethod
import com.razstudio.pos.data.local.PaymentTransaction
import com.razstudio.pos.data.local.PaymentTransactionDao
import com.razstudio.pos.data.local.PaymentTransactionStatus
import com.razstudio.pos.data.local.createdAtMillis
import com.razstudio.pos.data.local.PendingOrder
import com.razstudio.pos.data.local.PendingOrderDao
import com.razstudio.pos.data.local.SettingsDao
import com.razstudio.pos.data.local.SystemSettings
import com.razstudio.pos.data.local.Table
import com.razstudio.pos.data.local.TableDao
import com.razstudio.pos.data.toCapabilities
import com.razstudio.pos.display.CustomerDisplayManager
import com.razstudio.pos.display.CustomerDisplayState
import com.razstudio.pos.ui.i18n.LanguageManager
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.tableview.OrderDetailState
import com.razstudio.pos.ui.tableview.StaffPermissions
import com.razstudio.pos.ui.tableview.TableState
import com.razstudio.pos.ui.tableview.TableUiStatus
import com.razstudio.pos.ui.tableview.paymentMethodLabel
import com.razstudio.pos.ui.tableview.toTableUiStatus
import com.razstudio.pos.ui.util.QrCodeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val apiClient: BackendGateway,
    private val orderDao: OrderDao,
    private val lanServerLocator: com.razstudio.pos.data.lan.LanServerLocator,
    private val tableDao: TableDao,
    private val menuDao: MenuDao,
    private val categoryStore: MenuCategoryStore,
    private val settingsDao: SettingsDao,
    private val pendingOrderDao: PendingOrderDao,
    private val languageManager: LanguageManager,
    private val customerDisplayManager: CustomerDisplayManager,
    private val paymentTransactionDao: PaymentTransactionDao,
    private val modeRepository: com.razstudio.pos.data.ModeRepository,
    private val tableSync: com.razstudio.pos.data.local.TableSync,
    private val staffOrderSync: com.razstudio.pos.data.local.StaffOrderSync,
) : ViewModel() {

    private fun str() = uiStrings(languageManager.language.value)

    /**
     * Read-only gateway configuration, refreshed whenever the order sheet is about to open — see
     * [TableViewViewModel]'s identical rationale. Never holds a secret (task 7.1, 7.2).
     */
    private val _gatewayConfig = MutableStateFlow<GatewayConfigDto?>(null)

    /** Gateway tiles to show at checkout — empty in LAN/Kiosk regardless of what the café has
     *  configured. (A1, PG-REQ-3, 6.4) */
    val gatewayMethods: StateFlow<List<PaymentMethod>> = combine(
        _gatewayConfig, modeRepository.activeMode,
    ) { config, mode ->
        if (config == null || !mode.toCapabilities().gatewayPaymentsEnabled) emptyList()
        else config.enabledMethods.mapNotNull { PaymentMethod.fromCode(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _gatewayCheckout = MutableStateFlow<GatewayCheckoutState?>(null)
    val gatewayCheckout: StateFlow<GatewayCheckoutState?> = _gatewayCheckout.asStateFlow()
    private var gatewayPollJob: Job? = null
    private var currentPollQuery: (suspend (String) -> ApiResult<GatewayPaymentResult>)? = null
    private var currentPollOnSuccess: (suspend () -> Unit)? = null
    private var currentPollLocalRowId: String? = null

    private fun refreshGatewayConfig() {
        viewModelScope.launch {
            if (!modeRepository.activeMode.value.toCapabilities().gatewayPaymentsEnabled) return@launch
            when (val result = apiClient.getGatewayConfigAsStaff()) {
                is ApiResult.Success -> _gatewayConfig.value = result.data
                else -> Unit
            }
        }
    }

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
        /** Matches TableViewViewModel: split-off items are not wastage, and the reason says so. */
        const val SPLIT_REASON = "Split payment"

        // Fixed pre-send hold for staff orders (mis-tap guard).
        const val STAFF_ORDER_HOLD_SECONDS = 3

        /**
         * How often the staff device re-reads the floor when no push arrives.
         *
         * 30s, not 10s, because the LAN push socket now carries the latency: the admin announces a
         * change the moment it makes one and this device pulls immediately, so the poll went from
         * being the mechanism to being the safety net.
         *
         * The number is a quota decision as much as a UX one. At 10s a single device makes roughly
         * 260k Edge Function calls a month and Supabase's free tier is 500k in total — admin plus one
         * staff phone already exceeded it. 30s brings a two-device café to about 175k and leaves room
         * for a third. The cost of the change is the worst case when the push path is down: a table
         * can be stale for 30s instead of 10s, which is the tradeoff worth making because the push
         * path being down is the exception and the quota ceiling is not.
         */
        const val STAFF_ORDER_POLL_INTERVAL_MS = 30_000L
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
        startOrderPolling()
        refreshGatewayConfig()
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
    /**
     * Pull the café's floor plan if this device does not already hold it.
     *
     * Staff had no such path at all: rehydrate lived on the admin ViewModel with a single caller in
     * AdminHomeScreen, so a freshly-joined staff phone showed an empty table grid indefinitely
     * while its menu synced perfectly through an unrelated code path.
     */
    fun syncTablesIfNeeded() {
        viewModelScope.launch { tableSync.syncIfNeeded() }
    }

    fun refreshPermissions() {
        loadPermissions()
    }

    // --- Order Detail ---

    fun loadOrderForTable(tableId: String) {
        refreshGatewayConfig()
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null, successMessage = null)
            val order = orderDao.getActiveOrderForTable(tableId)
            val items = if (order != null) orderDao.getItemsForOrder(order.id) else emptyList()
            // Crash/resume recovery (task 8.5) — see TableViewViewModel.loadOrderForTable.
            // Suppressed while gateway payments are switched off product-wide: the banner's only
            // action is Resume, which would restart a checkout that can no longer be started. A
            // stale PENDING row from an older build must not offer a café a dead button.
            val pendingGateway = order
                ?.takeIf { modeRepository.activeMode.value.toCapabilities().gatewayPaymentsEnabled }
                ?.let { paymentTransactionDao.getLatestForOrder(it.id) }
                ?.takeIf { it.status == PaymentTransactionStatus.PENDING }
            _orderDetail.value = OrderDetailState(
                order = order,
                items = items,
                isLoading = false,
                pendingGatewayTransaction = pendingGateway,
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
    /**
     * Settle one customer's share of a group bill, from a staff device.
     *
     * Mirrors `TableViewViewModel.paySplitShare` on the staff endpoints. It is reachable only where
     * the ordinary pay buttons are, so the café's "Staff can Take Payment" setting governs both —
     * a staff phone that may take a whole payment may take part of one, and one that may not, may
     * not do either.
     *
     * Order matters: charge first, shrink second. A failure after shrinking would have given the
     * food away.
     */
    fun paySplitShare(
        orderId: String,
        tableId: String?,
        plan: SplitPaymentPlanner.Plan.SliceOff,
        method: String,
        @Suppress("UNUSED_PARAMETER") printReceipt: Boolean = false,
    ) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)

            val table = tableId ?: run {
                _orderDetail.value = _orderDetail.value.copy(
                    isLoading = false, error = str().splitShareFailed,
                )
                return@launch
            }

            val created = apiClient.createOrderAsStaff(table, plan.sliceItems)
            if (created !is ApiResult.Success) {
                _orderDetail.value = _orderDetail.value.copy(
                    isLoading = false, error = str().splitShareFailed,
                )
                return@launch
            }
            val shareId = created.data.orderId
            insertShareIntoRoom(shareId, table, plan)
            completeSplitShare(orderId, table, shareId, plan, method)
        }
    }

    /**
     * A gateway checkout for one customer's share of a group bill, from a staff device (task 7.3,
     * 8.1/8.2). Mirrors [TableViewViewModel.startGatewaySplitCheckout] on the staff endpoints.
     */
    fun startGatewaySplitCheckout(
        orderId: String,
        tableId: String?,
        plan: SplitPaymentPlanner.Plan.SliceOff,
        method: PaymentMethod,
        @Suppress("UNUSED_PARAMETER") printReceipt: Boolean = false,
        customerAuthCode: String? = null,
    ) {
        viewModelScope.launch {
            _orderDetail.value = _orderDetail.value.copy(isLoading = true, error = null)
            val table = tableId ?: run {
                _orderDetail.value = _orderDetail.value.copy(
                    isLoading = false, error = str().splitShareFailed,
                )
                return@launch
            }
            val created = apiClient.createOrderAsStaff(table, plan.sliceItems)
            if (created !is ApiResult.Success) {
                _orderDetail.value = _orderDetail.value.copy(
                    isLoading = false, error = str().splitShareFailed,
                )
                return@launch
            }
            val shareId = created.data.orderId
            insertShareIntoRoom(shareId, table, plan)
            _orderDetail.value = _orderDetail.value.copy(isLoading = false)
            startGatewayCheckoutInternal(
                orderIdForKey = shareId,
                method = method,
                amount = plan.amount,
                customerAuthCode = customerAuthCode,
                initiate = { payload -> apiClient.initiatePaymentAsStaff(payload) },
                query = { transactionId -> apiClient.queryPaymentAsStaff(transactionId) },
                onSuccess = { completeSplitShare(orderId, table, shareId, plan, method.code) },
            )
        }
    }

    /** Inserts the newly-created share order into local Room immediately. See
     *  [TableViewViewModel.insertShareIntoRoom] — staff devices never print, but the row still
     *  needs to exist locally for `orderDao.completePayment` below to have anything to update. */
    private suspend fun insertShareIntoRoom(
        shareId: String,
        tableId: String?,
        plan: SplitPaymentPlanner.Plan.SliceOff,
    ) {
        orderDao.insertOrder(
            Order(
                id = shareId,
                tableId = tableId,
                source = "STAFF",
                status = OrderStatus.RECEIVED,
                total = plan.amount,
                createdAt = PaymentTransaction.nowIso(),
            )
        )
        orderDao.insertOrderItems(
            plan.selections.map { sel ->
                OrderItem(
                    id = java.util.UUID.randomUUID().toString(),
                    orderId = shareId,
                    menuItemId = sel.item.menuItemId,
                    nameSnapshot = sel.item.nameSnapshot,
                    unitPriceSnapshot = sel.item.unitPriceSnapshot,
                    categorySnapshot = sel.item.categorySnapshot,
                    quantity = sel.takeQuantity,
                    note = sel.item.note,
                )
            }
        )
    }

    /** Completion tail shared by every split-share payment method. See
     *  [TableViewViewModel.completeSplitShare] for the ordering rationale (charge, then shrink).
     *  No [printReceipt] parameter — staff devices have no printer attached (see [processPayment]). */
    private suspend fun completeSplitShare(
        orderId: String,
        tableId: String?,
        shareId: String,
        plan: SplitPaymentPlanner.Plan.SliceOff,
        method: String,
    ) {
        when (apiClient.processPaymentAsStaff(shareId, method)) {
            is ApiResult.Success -> Unit
            else -> {
                _orderDetail.value = _orderDetail.value.copy(
                    isLoading = false, error = str().splitShareUnpaid,
                )
                return
            }
        }
        orderDao.completePayment(shareId, method)

        when (val shrunk = apiClient.voidOrderItemsAsStaff(orderId, plan.keepLines, SPLIT_REASON)) {
            is ApiResult.Success -> {
                // Destructive: reconcileOrderFromDto only inserts, so a shrunk line would keep
                // its old quantity alongside the new one.
                orderDao.deleteItemsForOrder(orderId)
                reconcileOrderFromDto(shrunk.data)

                // Refresh inline rather than calling loadOrderForTable (which launches a
                // separate coroutine that races with the successMessage assignment below,
                // overwriting it with null before the UI can display it).
                val refreshedOrder = orderDao.getOrderById(orderId)
                val refreshedItems = if (refreshedOrder != null) orderDao.getItemsForOrder(orderId) else emptyList()
                _orderDetail.value = OrderDetailState(
                    order = refreshedOrder,
                    items = refreshedItems,
                    isLoading = false,
                    successMessage = str().splitSharePaid.format(method),
                )
            }
            else -> _orderDetail.value = _orderDetail.value.copy(
                isLoading = false, error = str().splitShareNotRemoved,
            )
        }
    }

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

    /** Whole-bill gateway checkout from a staff device (task 7.2, 8.1, 8.2). Mirrors
     *  [TableViewViewModel.startGatewayCheckout] on the staff endpoints. [customerAuthCode] is the
     *  scanned wallet barcode for merchant-scan channels (task 8.3). */
    fun startGatewayCheckout(
        orderId: String,
        method: PaymentMethod,
        amount: Double,
        printReceipt: Boolean,
        customerAuthCode: String? = null,
    ) {
        startGatewayCheckoutInternal(
            orderIdForKey = orderId,
            method = method,
            amount = amount,
            customerAuthCode = customerAuthCode,
            initiate = { payload -> apiClient.initiatePaymentAsStaff(payload) },
            query = { transactionId -> apiClient.queryPaymentAsStaff(transactionId) },
            onSuccess = { processPayment(orderId, method.code, printReceipt) },
        )
    }

    /** Resume a payment PENDING from before this device last closed (task 8.5). See
     *  [TableViewViewModel.resumeGatewayCheckout] for the rationale. */
    fun resumeGatewayCheckout(row: PaymentTransaction, printReceipt: Boolean = true) {
        val method = PaymentMethod.fromCode(row.paymentMethod) ?: return
        val checkoutUrl = row.gatewayResponse ?: return
        val qr = QrCodeUtil.encode(checkoutUrl)
        val expiresAt = row.createdAtMillis() + GatewayPolling.QR_EXPIRY_SECONDS * 1000L

        gatewayPollJob?.cancel()
        _gatewayCheckout.value = GatewayCheckoutState.AwaitingPayment(
            transactionId = row.id,
            method = method,
            amount = row.ringgit,
            checkoutUrl = checkoutUrl,
            qr = qr,
            expiresAtMillis = expiresAt,
        )
        currentPollQuery = { transactionId -> apiClient.queryPaymentAsStaff(transactionId) }
        currentPollOnSuccess = { processPayment(row.orderId, method.code, printReceipt) }
        currentPollLocalRowId = row.id
        gatewayPollJob = viewModelScope.launch {
            pollGatewayPayment(row.id, method, currentPollQuery!!, currentPollOnSuccess!!)
        }
    }

    /** Shared initiate→poll orchestration. See
     *  [TableViewViewModel.startGatewayCheckoutInternal] for the admin/staff duplication rationale. */
    private fun startGatewayCheckoutInternal(
        orderIdForKey: String,
        method: PaymentMethod,
        amount: Double,
        customerAuthCode: String? = null,
        initiate: suspend (PosCheckoutPayload) -> ApiResult<GatewayPaymentResult>,
        query: suspend (String) -> ApiResult<GatewayPaymentResult>,
        onSuccess: suspend () -> Unit,
    ) {
        gatewayPollJob?.cancel()
        _gatewayCheckout.value = GatewayCheckoutState.Initiating(method, amount)
        gatewayPollJob = viewModelScope.launch {
            val amountSen = PaymentTransaction.fromRinggit(amount)
            val idempotencyKey = PaymentTransaction.idempotencyKeyFor(orderIdForKey, amountSen)
            val isSandbox = _gatewayConfig.value?.isSandbox ?: true
            val payload = PosCheckoutPayload(
                orderId = orderIdForKey,
                amountSen = amountSen,
                paymentMethodCode = method.code,
                customerAuthCode = customerAuthCode,
                idempotencyKey = idempotencyKey,
                isSandbox = isSandbox,
            )
            when (val result = initiate(payload)) {
                is ApiResult.Success -> {
                    val body = result.data
                    val checkoutUrl = body.checkoutUrl
                    if (!body.success || checkoutUrl == null) {
                        _gatewayCheckout.value = GatewayCheckoutState.Failed(
                            method, body.errorMessage ?: str().gatewayPaymentDeclined,
                        )
                        return@launch
                    }
                    val qr = QrCodeUtil.encode(checkoutUrl)
                    val transactionId = body.transactionId ?: orderIdForKey
                    val expiresAt = System.currentTimeMillis() + GatewayPolling.QR_EXPIRY_SECONDS * 1000L
                    _gatewayCheckout.value = GatewayCheckoutState.AwaitingPayment(
                        transactionId = transactionId,
                        method = method,
                        amount = amount,
                        checkoutUrl = checkoutUrl,
                        qr = qr,
                        expiresAtMillis = expiresAt,
                    )
                    if (qr != null && method.category == PaymentCategory.QR_PAYNET) {
                        customerDisplayManager.show(
                            CustomerDisplayState.PaymentQr(
                                qr = qr,
                                caption = paymentMethodLabel(method, str()),
                                amount = amount,
                            )
                        )
                    }
                    paymentTransactionDao.insert(
                        PaymentTransaction(
                            id = transactionId,
                            orderId = orderIdForKey,
                            paymentMethod = method.code,
                            amountSen = amountSen,
                            status = PaymentTransactionStatus.PENDING,
                            gatewayResponse = checkoutUrl,
                            idempotencyKey = idempotencyKey,
                            isSandbox = isSandbox,
                            createdAt = PaymentTransaction.nowIso(),
                        )
                    )
                    currentPollQuery = query
                    currentPollOnSuccess = onSuccess
                    currentPollLocalRowId = transactionId
                    pollGatewayPayment(transactionId, method, query, onSuccess)
                }
                is ApiResult.Error -> _gatewayCheckout.value = GatewayCheckoutState.Failed(method, result.message)
                is ApiResult.NetworkError -> _gatewayCheckout.value =
                    GatewayCheckoutState.Failed(method, str().msgNetworkError.format(result.message))
            }
        }
    }

    private suspend fun pollGatewayPayment(
        transactionId: String,
        method: PaymentMethod,
        query: suspend (String) -> ApiResult<GatewayPaymentResult>,
        onSuccess: suspend () -> Unit,
    ) {
        var elapsed = 0
        while (elapsed < GatewayPolling.TOTAL_TIMEOUT_SECONDS) {
            when (val result = query(transactionId)) {
                is ApiResult.Success -> when (result.data.status) {
                    "SUCCESS" -> {
                        settleLocalTransaction(transactionId, PaymentTransactionStatus.SUCCESS)
                        customerDisplayManager.clear()
                        _gatewayCheckout.value = null
                        onSuccess()
                        return
                    }
                    "FAILED", "CANCELLED" -> {
                        settleLocalTransaction(transactionId, PaymentTransactionStatus.FAILED)
                        customerDisplayManager.clear()
                        _gatewayCheckout.value = GatewayCheckoutState.Failed(method, str().gatewayPaymentDeclined)
                        return
                    }
                    else -> Unit
                }
                else -> Unit
            }
            val delayMs = GatewayPolling.nextDelayMillis(elapsed)
            delay(delayMs)
            elapsed += (delayMs / 1000).toInt()
        }
        settleLocalTransaction(transactionId, PaymentTransactionStatus.TIMEOUT)
        customerDisplayManager.clear()
        _gatewayCheckout.value = GatewayCheckoutState.TimedOut
    }

    private suspend fun settleLocalTransaction(id: String, status: PaymentTransactionStatus) {
        paymentTransactionDao.settle(
            id = id,
            status = status.name,
            gatewayTransactionId = null,
            gatewayResponse = paymentTransactionDao.getById(id)?.gatewayResponse,
            settledAt = PaymentTransaction.nowIso(),
        )
    }

    /** Callback-intercept hook (task 8.4). See [TableViewViewModel.nudgeGatewayPoll]. */
    fun nudgeGatewayPoll() {
        val awaiting = _gatewayCheckout.value as? GatewayCheckoutState.AwaitingPayment ?: return
        val query = currentPollQuery ?: return
        val onSuccess = currentPollOnSuccess ?: return
        gatewayPollJob?.cancel()
        gatewayPollJob = viewModelScope.launch {
            pollGatewayPayment(awaiting.transactionId, awaiting.method, query, onSuccess)
        }
    }

    /** Staff taps Cancel on the checkout overlay. See [TableViewViewModel.cancelGatewayCheckout]. */
    fun cancelGatewayCheckout() {
        gatewayPollJob?.cancel()
        gatewayPollJob = null
        customerDisplayManager.clear()
        val localRowId = currentPollLocalRowId
        currentPollLocalRowId = null
        currentPollQuery = null
        currentPollOnSuccess = null
        _gatewayCheckout.value = null
        if (localRowId != null) {
            viewModelScope.launch { settleLocalTransaction(localRowId, PaymentTransactionStatus.CANCELLED) }
        }
    }

    fun dismissGatewayCheckout() {
        _gatewayCheckout.value = null
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

    /**
     * Recover the LAN Server's address after a connection failure (task 7.3, Requirement 5.5).
     *
     * Called when a request fails to connect — not on every request, so the happy path stays a plain
     * HTTP call. The three branches live in [com.razstudio.pos.data.lan.LanServerLocator]: retry the
     * last known address, then mDNS, then report that a re-scan is needed.
     *
     * **The credential is never touched.** It was issued to this device, not to an address, so a
     * server that moved does not invalidate it and no re-approval is required — that is the whole
     * point of the requirement. Re-pairing would mean the admin approving each phone again mid-service.
     */
    fun recoverServerAddress(onOutcome: (reScanNeeded: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            when (lanServerLocator.locate()) {
                is com.razstudio.pos.data.lan.LanServerLocator.Result.Reachable -> {
                    _orderDetail.value = _orderDetail.value.copy(error = null)
                    onOutcome(false)
                }
                com.razstudio.pos.data.lan.LanServerLocator.Result.NotFound -> {
                    _orderDetail.value = _orderDetail.value.copy(
                        error = str().lanServerNotFoundMsg,
                    )
                    onOutcome(true)
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

        // toNewOrderItem, not a raw NewOrderItem: a hand-typed "+ Customized" line lives in the cart
        // as a synthetic menu item, and only that helper carries its typed name onto the wire.
        val items = cart.map { cartItem ->
            cartItem.menuItem.toNewOrderItem(
                quantity = cartItem.quantity,
                note = cartItem.note,
                size = cartItem.size,
                unitPrice = cartItem.unitPrice,
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
                    // The table tile is driven by Room (`orderDao.getActiveOrdersFlow`), and until
                    // now NOTHING on this device wrote the order there: the slip printed, the admin's
                    // grid went occupied, and the staff phone that took the order kept showing the
                    // table as Free. Write the row immediately so the tile flips under the cashier's
                    // hand, then reconcile from the server for the authoritative items/session.
                    orderDao.insertOrder(
                        Order(
                            id = result.data.orderId,
                            tableId = tableId,
                            source = "STAFF",
                            status = OrderStatus.fromWire(result.data.status),
                            total = result.data.total,
                            createdAt = Instant.now().toString(),
                        )
                    )
                    syncOrders()
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
                    // Without this an offline-queued custom charge would replay as a nameless line.
                    if (item.customName != null) put("customName", item.customName)
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
                        size = obj.optStringOrNull("size"),
                        customName = obj.optStringOrNull("customName")
                    )
                )
            }
        } catch (_: Exception) { }
        return items
    }

    // --- Catch-up Sync ---

    private fun performCatchUpSync() {
        viewModelScope.launch {
            syncOrders()

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

    /**
     * Pull orders and reconcile, via the shared [StaffOrderSync].
     *
     * Shared rather than local because the push socket triggers the SAME function — one watermark,
     * one reconcile, no second de-duplication path that can disagree with this one.
     */
    private suspend fun syncOrders() {
        staffOrderSync.syncNow()
    }

    /**
     * The staff device's catch-up poll.
     *
     * It had none: `performCatchUpSync` ran once in `init` and never again, so this screen only ever
     * showed the floor as it looked the moment the ViewModel was created. Everything after that --
     * its own orders, another phone's orders, a bill the admin settled -- was invisible. The admin
     * side has Realtime plus its own reconcile; staff had neither, which is why the table status
     * never moved here.
     *
     * Polling rather than Realtime deliberately: on this backend the WebSocket delivers no broadcast
     * frames to these devices (the same reason kitchen auto-print rides the poll), so a socket here
     * would be a dependency that looks live and is not.
     */
    private fun startOrderPolling() {
        viewModelScope.launch {
            while (true) {
                delay(STAFF_ORDER_POLL_INTERVAL_MS)
                syncOrders()
            }
        }
    }

    private suspend fun reconcileOrderFromDto(dto: OrderDto) {
        orderDao.insertOrder(dto.toEntity())
        if (dto.items.isNotEmpty()) {
            orderDao.insertOrderItems(dto.items.map { it.toEntity(dto.id) })
        }
    }
}
