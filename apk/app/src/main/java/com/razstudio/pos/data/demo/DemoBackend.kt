package com.razstudio.pos.data.demo

import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.BrandingResponse
import com.razstudio.pos.data.CreateOrderResponse
import com.razstudio.pos.data.DeviceDto
import com.razstudio.pos.data.InviteResponse
import com.razstudio.pos.data.KitchenResponse
import com.razstudio.pos.data.MenuCategoryDto
import com.razstudio.pos.data.MenuItemDto
import com.razstudio.pos.data.MenuResponse
import com.razstudio.pos.data.NewOrderItem
import com.razstudio.pos.data.OrderDto
import com.razstudio.pos.data.OrderItemDto
import com.razstudio.pos.data.OrdersSyncResponse
import com.razstudio.pos.data.SessionResponse
import com.razstudio.pos.data.SettingsResponse
import com.razstudio.pos.data.local.AppDatabase
import com.razstudio.pos.data.local.MenuCategoryStore
import com.razstudio.pos.data.local.MenuItem
import com.razstudio.pos.data.local.Order
import com.razstudio.pos.data.local.OrderItem
import com.razstudio.pos.data.local.OrderStatus
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The local, in-memory "backend" that powers Demo Mode.
 *
 * When [DemoSession.active] is true, [com.razstudio.pos.data.ApiClient] delegates its network
 * methods here instead of hitting Supabase. Every surface — admin, ordering staff, customer QR —
 * runs the REAL screens but reads/writes this one shared dataset (persisted in the app's Room DB,
 * scoped to the demo-relevant tables only), so an order placed by any role appears live in the
 * admin's Table View. Confirming "back to main page" calls [exit], which wipes that dataset.
 *
 * Design notes:
 * - Reads that the real code paths sync into Room (menu, tables) return the deterministic seed, so
 *   any accidental re-sync is self-healing rather than data-destroying.
 * - Order lifecycle (create / send-to-kitchen / pay / cancel / add-items) mutates the shared Room
 *   tables directly, mirroring what the backend would persist, so all roles observe the same state.
 * - The delta poll [getOrdersSince] returns empty: order state lives in Room and is mutated locally,
 *   so a poll must never overwrite an admin's local change with stale seed data.
 * - Only demo-relevant tables (menu_items, tables, orders, order_items) and the category store are
 *   touched; device-local hardware config (printers, print settings) is deliberately left intact.
 */
@Singleton
class DemoBackend @Inject constructor(
    private val db: AppDatabase,
    private val categoryStore: MenuCategoryStore,
) {
    companion object {
        /** Category tab order for the demo café, matching the seeded item categories. */
        private val DEMO_CATEGORIES = listOf("FOOD", "BEVERAGES", "SIDE_DISHES", "OTHERS")
    }

    /** Compose-observable mirror of [DemoSession.active] for the global demo banner. */
    private val _activeFlow = MutableStateFlow(false)
    val activeFlow: StateFlow<Boolean> = _activeFlow.asStateFlow()

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Wipe demo tables, seed the shared dataset, and switch the app into Demo Mode. */
    suspend fun enter() {
        clearDemoTables()
        DemoSeedData.seed(db)
        categoryStore.set(DEMO_CATEGORIES)
        DemoSession.active = true
        _activeFlow.value = true
    }

    /** Leave Demo Mode and destroy the shared demo dataset (called on confirmed exit-to-main). */
    suspend fun exit() {
        DemoSession.active = false
        _activeFlow.value = false
        clearDemoTables()
        categoryStore.set(emptyList())
    }

    /** Clear only the demo-relevant tables; leave printer config / print settings untouched. */
    private suspend fun clearDemoTables() {
        db.orderDao().deleteAllOrderItems()
        db.orderDao().deleteAllOrders()
        db.menuDao().deleteAll()
        db.tableDao().deleteAll()
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /**
     * Reflect the CURRENT in-demo menu (from Room), not the static seed, so edits made in the demo
     * survive the real `openSession → syncMenuFromBackend` round-trip (which deletes + re-upserts
     * from this response on every admin-home entry). Falls back to the seed if Room is somehow empty.
     */
    suspend fun getMenu(): ApiResult<MenuResponse> {
        val entities = db.menuDao().getAll()
        val items = (if (entities.isEmpty()) DemoSeedData.menuItems else entities).map { it.toDto() }
        val names = categoryStore.get().ifEmpty { DEMO_CATEGORIES }
        return ApiResult.Success(
            MenuResponse(
                configured = true,
                items = items,
                categories = names.mapIndexed { i, name -> MenuCategoryDto(name = name, sortOrder = i) }
            )
        )
    }

    fun getTables(): ApiResult<List<Pair<String, String>>> =
        ApiResult.Success(DemoSeedData.tables.map { it.id to it.label })

    fun getTableTokens(): ApiResult<Map<String, String>> =
        ApiResult.Success(DemoSeedData.tables.associate { it.id to "demo-token-${it.id}" })

    fun getBranding(): ApiResult<BrandingResponse> =
        ApiResult.Success(BrandingResponse(cafeName = "Demo Café", logoUrl = ""))

    fun getSettings(): ApiResult<SettingsResponse> = ApiResult.Success(
        SettingsResponse(
            printLanguage = "BM",
            timezone = "Asia/Kuala_Lumpur",
            topN = 5,
            staffCanSendKitchen = true,
            staffCanTakePayment = true,
        )
    )

    /** Attendance geofence is intentionally unconfigured in demo (no clock-in gating). */
    fun getCafeLocation(): ApiResult<Nothing> =
        ApiResult.Error("NOT_CONFIGURED", "Demo café has no location configured")

    fun getDevices(): ApiResult<List<DeviceDto>> = ApiResult.Success(
        listOf(
            DeviceDto(
                id = "demo-admin",
                deviceIdentifier = "DEMO-ADMIN",
                label = "This device (Admin)",
                role = "ADMIN",
                status = "ACTIVE",
                lastSeenAt = null,
                isCheckedIn = true,
            ),
            DeviceDto(
                id = "demo-ordering",
                deviceIdentifier = "DEMO-COUNTER",
                label = "Counter Tablet",
                role = "ORDERING",
                status = "ACTIVE",
                lastSeenAt = null,
                isCheckedIn = true,
            ),
        )
    )

    /** Delta poll is a no-op: demo order state lives in Room and is mutated locally. */
    fun getOrdersSince(): ApiResult<OrdersSyncResponse> =
        ApiResult.Success(OrdersSyncResponse(orders = emptyList(), serverTime = nowIso()))

    fun getInvite(): ApiResult<InviteResponse> =
        ApiResult.Success(InviteResponse(token = "DEMO-INVITE", url = "https://demo.local/invite"))

    // ── Session / writes that are no-ops but must echo a plausible result ──────

    fun postSession(event: String): ApiResult<SessionResponse> =
        ApiResult.Success(SessionResponse(sessionId = "demo-session", event = event, timestamp = nowIso()))

    fun putBranding(cafeName: String): ApiResult<BrandingResponse> =
        ApiResult.Success(BrandingResponse(cafeName = cafeName, logoUrl = ""))

    // ── Order lifecycle (stateful, shared across roles) ───────────────────────

    suspend fun createOrder(
        tableId: String,
        items: List<NewOrderItem>,
        source: String,
    ): ApiResult<CreateOrderResponse> {
        val orderId = "demo-${UUID.randomUUID()}"
        val entityItems = items.map { it.toEntity(orderId, sessionNumber = 1) }
        val total = entityItems.sumOf { it.unitPriceSnapshot * it.quantity }
        db.orderDao().insertOrder(
            Order(
                id = orderId,
                tableId = tableId,
                source = source,
                status = OrderStatus.RECEIVED,
                total = total,
                createdAt = nowIso(),
            )
        )
        db.orderDao().insertOrderItems(entityItems)
        return ApiResult.Success(CreateOrderResponse(orderId = orderId, total = total, status = "RECEIVED"))
    }

    suspend fun sendToKitchen(orderId: String): ApiResult<KitchenResponse> {
        db.orderDao().markSentToKitchen(orderId, nowIso(), OrderStatus.SENT_TO_KITCHEN.name)
        db.orderDao().markAllItemsSentToKitchen(orderId)
        val order = db.orderDao().getOrderById(orderId)
            ?: return ApiResult.Error("NOT_FOUND", "Order not found")
        val items = db.orderDao().getItemsForOrder(orderId)
        return ApiResult.Success(KitchenResponse(order = order.toDto(items), linesToPrint = items.map { it.toDto() }))
    }

    suspend fun updateOrderStatus(orderId: String, status: String): ApiResult<OrderDto> {
        db.orderDao().updateOrderStatus(orderId, status)
        return orderDtoResult(orderId)
    }

    suspend fun processPayment(orderId: String, method: String): ApiResult<OrderDto> {
        db.orderDao().completePayment(orderId, method)
        return orderDtoResult(orderId)
    }

    suspend fun cancelOrder(orderId: String, reason: String, cancelledBy: String): ApiResult<Unit> {
        db.orderDao().cancelOrder(orderId, reason, cancelledBy)
        return ApiResult.Success(Unit)
    }

    suspend fun addItemsToOrder(orderId: String, items: List<NewOrderItem>): ApiResult<OrderDto> {
        val existing = db.orderDao().getItemsForOrder(orderId)
        val nextSession = (existing.maxOfOrNull { it.sessionNumber } ?: 1) + 1
        val newItems = items.map { it.toEntity(orderId, sessionNumber = nextSession) }
        db.orderDao().insertOrderItems(newItems)
        val all = existing + newItems
        val total = all.sumOf { it.unitPriceSnapshot * it.quantity }
        val order = db.orderDao().getOrderById(orderId)
            ?: return ApiResult.Error("NOT_FOUND", "Order not found")
        db.orderDao().insertOrder(order.copy(total = total))
        return ApiResult.Success(order.copy(total = total).toDto(all))
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private suspend fun orderDtoResult(orderId: String): ApiResult<OrderDto> {
        val order = db.orderDao().getOrderById(orderId)
            ?: return ApiResult.Error("NOT_FOUND", "Order not found")
        val items = db.orderDao().getItemsForOrder(orderId)
        return ApiResult.Success(order.toDto(items))
    }

    private fun nowIso(): String = Instant.now().toString()

    /** Resolve a new order line from menu seed data (name / price / category snapshots). */
    private fun NewOrderItem.toEntity(orderId: String, sessionNumber: Int): OrderItem {
        val seed = DemoSeedData.menuItems.firstOrNull { it.id == menuItemId }
        return OrderItem(
            id = "demo-oi-${UUID.randomUUID()}",
            orderId = orderId,
            menuItemId = menuItemId,
            // Backend bakes the English base name as the snapshot; clients re-resolve per surface.
            nameSnapshot = seed?.nameEn ?: menuItemId,
            unitPriceSnapshot = unitPrice ?: seed?.price ?: 0.0,
            categorySnapshot = seed?.category ?: "",
            quantity = quantity,
            note = note,
            sentToKitchen = false,
            sessionNumber = sessionNumber,
        )
    }

    private fun Order.toDto(items: List<OrderItem>): OrderDto = OrderDto(
        id = id,
        tableId = tableId,
        source = source,
        status = status.name,
        paymentMethod = paymentMethod,
        total = total,
        sentToKitchenAt = sentToKitchenAt,
        cancelReason = cancelReason,
        cancelledBy = cancelledBy,
        createdAt = createdAt,
        items = items.map { it.toDto() },
    )

    private fun OrderItem.toDto(): OrderItemDto = OrderItemDto(
        id = id,
        menuItemId = menuItemId,
        nameSnapshot = nameSnapshot,
        unitPriceSnapshot = unitPriceSnapshot,
        categorySnapshot = categorySnapshot,
        quantity = quantity,
        note = note,
        sentToKitchen = sentToKitchen,
        sessionNumber = sessionNumber,
    )

    private fun MenuItem.toDto(): MenuItemDto = MenuItemDto(
        id = id,
        category = category,
        extraCategories = extraCategories,
        code = code,
        price = price,
        marketPrice = marketPrice,
        available = available,
        askMeDaily = askMeDaily,
        imageUrl = imageUrl,
        hasVariablePrice = hasVariablePrice,
        variablePriceDailyPrompt = variablePriceDailyPrompt,
        priceOption1 = priceOption1,
        priceOption2 = priceOption2,
        priceOption3 = priceOption3,
        nameEn = nameEn,
        nameBm = nameBm,
        nameZh = nameZh,
        nameTa = nameTa,
        nameTh = nameTh,
        doNotTranslate = doNotTranslate,
    )
}
