package com.razstudio.pos.data.local

import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.BrandingResponse
import com.razstudio.pos.data.CafeLocationResponse
import com.razstudio.pos.data.CreateOrderResponse
import com.razstudio.pos.data.DeviceDto
import com.razstudio.pos.data.DeviceStatusResponse
import com.razstudio.pos.data.InviteResponse
import com.razstudio.pos.data.KitchenResponse
import com.razstudio.pos.data.MenuImageUploadResponse
import com.razstudio.pos.data.MenuResponse
import com.razstudio.pos.data.NewOrderItem
import com.razstudio.pos.data.VoidLine
import com.razstudio.pos.data.OrderDto
import com.razstudio.pos.data.OrderItemDto
import com.razstudio.pos.data.OrdersSyncResponse
import com.razstudio.pos.data.RegisterResponse
import com.razstudio.pos.data.SessionResponse
import com.razstudio.pos.data.SettingsResponse
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The in-process, Room-backed [BackendGateway] used by a LAN **Server** device and by Kiosk Mode
 * (task 3.3 wires the binding; the implementations land in task 4 onwards).
 *
 * Methods that are implemented:
 * - [createOrder] / [createOrderAsStaff] — task 4.1: insert an [Order] via [OrderDao]; the Server
 *   Device assigns the id as a UUID so no Client can mint one. (Requirement 8.4)
 * - [getOrdersSince] / [getOrdersSinceAsStaff] — task 4.1: delegate to
 *   [OrderDao.getOrdersSince], converting Room entities to [OrderDto].
 *
 * All other methods still throw. That is deliberate and preferable to returning plausible empty
 * values: a stub that answers `ApiResult.Success(emptyList())` would let a half-built LAN Mode look
 * like it was working while silently losing orders. Throwing means the first unimplemented call site
 * is impossible to miss, and the exception names the method so it is obvious which task owns it.
 *
 * Cloud Mode never constructs this class — `BackendModule` provides it lazily, so the stub cannot
 * affect an existing install.
 */
@Singleton
class LocalBackend @Inject constructor(
    private val orderDao: OrderDao,
    private val menuDao: MenuDao,
) : BackendGateway {

    private companion object {
        /** Fixed-width ISO-8601 UTC, always 6 fractional digits — see [nowTimestamp]. */
        private val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)
    }

    override suspend fun adminHandshakeDebug(deviceId: String, cafeName: String): ApiResult<String> =
        notImplemented("adminHandshakeDebug")

    override suspend fun register(inviteToken: String, deviceId: String, deviceModel: String, androidId: String, appVersion: String): ApiResult<RegisterResponse> =
        notImplemented("register")

    override suspend fun pollDeviceStatus(deviceId: String): ApiResult<DeviceStatusResponse> =
        notImplemented("pollDeviceStatus")

    override suspend fun recoverAdmin(recoveryToken: String, deviceId: String, deviceModel: String): ApiResult<String> =
        notImplemented("recoverAdmin")

    override suspend fun getRecoveryToken(): ApiResult<InviteResponse> =
        notImplemented("getRecoveryToken")

    override suspend fun getInvite(role: String?): ApiResult<InviteResponse> =
        notImplemented("getInvite")

    override suspend fun regenerateInvite(role: String?): ApiResult<InviteResponse> =
        notImplemented("regenerateInvite")

    override suspend fun getDevices(): ApiResult<List<DeviceDto>> =
        notImplemented("getDevices")

    override suspend fun patchDevice(deviceId: String, action: String, label: String?): ApiResult<DeviceDto> =
        notImplemented("patchDevice")

    override suspend fun postSession(event: String, reason: String?, closing: Boolean): ApiResult<SessionResponse> =
        notImplemented("postSession")

    override suspend fun postAggregates(date: String, body: JSONObject): ApiResult<Unit> =
        notImplemented("postAggregates")

    override suspend fun getMenu(): ApiResult<MenuResponse> =
        notImplemented("getMenu")

    override suspend fun putMenu(menuItems: JSONArray, categories: JSONArray): ApiResult<Unit> =
        notImplemented("putMenu")

    override suspend fun uploadMenuImage(menuItemId: String, imageBase64: String): ApiResult<MenuImageUploadResponse> =
        notImplemented("uploadMenuImage")

    override suspend fun deleteMenuImage(path: String): ApiResult<Unit> =
        notImplemented("deleteMenuImage")

    /**
     * Create a new order on behalf of the admin device.
     *
     * The Server Device is the sole id assigner (Requirement 8.4): the id is a UUID minted here,
     * never accepted from a caller, so two devices cannot produce the same id — collision is
     * structurally impossible rather than made unlikely by a probability argument.
     *
     * Item snapshots (name, price, category) are resolved from [MenuDao] at insert time so a
     * later menu edit cannot retroactively change what a customer was billed.
     */
    override suspend fun createOrder(
        tableId: String,
        items: List<NewOrderItem>,
        source: String,
    ): ApiResult<CreateOrderResponse> {
        val orderId = UUID.randomUUID().toString()
        val now = nowTimestamp()
        val menuIndex = menuDao.getAll().associateBy { it.id }
        val orderItems = items.map { it.toEntity(orderId, menuIndex, sessionNumber = 1) }
        val total = orderItems.sumOf { it.unitPriceSnapshot * it.quantity }
        orderDao.insertOrder(
            Order(
                id = orderId,
                tableId = tableId,
                source = source,
                status = OrderStatus.RECEIVED,
                total = total,
                createdAt = now,
            )
        )
        orderDao.insertOrderItems(orderItems)
        return ApiResult.Success(
            CreateOrderResponse(orderId = orderId, total = total, status = "RECEIVED")
        )
    }

    /**
     * Fetch all orders whose [Order.createdAt] is strictly after [since].
     *
     * [since] is the timestamp the poller last received as `serverTime`, and the response carries the
     * next one. SQLite compares `createdAt` as text, which only tracks chronological order because
     * every timestamp this class writes is fixed-width — see [nowTimestamp] for why
     * `Instant.toString()` is not safe here.
     *
     * Delivery is at-least-once, deliberately; the comment on `serverTime` below is the argument.
     */
    override suspend fun getOrdersSince(since: String): ApiResult<OrdersSyncResponse> {
        // Captured BEFORE the read, and this ordering is the whole correctness argument.
        //
        // Taking it afterwards loses orders outright: the query returns rows as of T0, the timestamp
        // would be T1 > T0, and an order written in between is in neither this response (it did not
        // exist at T0) nor the next one (which asks for > T1). It is dropped permanently, and because
        // a poll response looks identical either way nothing would ever reveal it.
        //
        // Capturing first inverts the failure: an order written between T0 and the query is returned
        // now AND again next poll. Duplicate delivery is harmless — the poller upserts by primary key
        // — so this trades an invisible loss for a redundant write, which is the right way round.
        val serverTime = nowTimestamp()
        val orders = orderDao.getOrdersSince(since)
        val orderDtos = orders.map { order ->
            val items = orderDao.getItemsForOrder(order.id)
            order.toDto(items)
        }
        return ApiResult.Success(
            OrdersSyncResponse(orders = orderDtos, serverTime = serverTime)
        )
    }

    override suspend fun sendToKitchen(orderId: String, sessionNumber: Int?): ApiResult<KitchenResponse> =
        notImplemented("sendToKitchen")

    override suspend fun addItemsToOrder(orderId: String, items: List<NewOrderItem>): ApiResult<OrderDto> =
        notImplemented("addItemsToOrder")

    override suspend fun voidOrderItems(orderId: String, lines: List<VoidLine>, reason: String): ApiResult<OrderDto> =
        notImplemented("voidOrderItems")

    override suspend fun updateOrderStatus(orderId: String, status: String): ApiResult<OrderDto> =
        notImplemented("updateOrderStatus")

    override suspend fun processPayment(orderId: String, method: String): ApiResult<OrderDto> =
        notImplemented("processPayment")

    override suspend fun cancelOrder(orderId: String, reason: String, cancelledBy: String): ApiResult<Unit> =
        notImplemented("cancelOrder")

    /**
     * Create a new order on behalf of an ordering-staff (Client) device.
     *
     * In LAN Mode, RBAC is enforced at the [LanServer] HTTP layer — only a properly credentialed
     * ORDERING device reaches this path. At the Room level, the order is identical; the id is still
     * Server-assigned (Requirement 8.4) and the source is fixed to "STAFF" per the BackendGateway
     * contract (staff orders cannot set the source header).
     */
    override suspend fun createOrderAsStaff(
        tableId: String,
        items: List<NewOrderItem>,
    ): ApiResult<CreateOrderResponse> = createOrder(tableId, items, source = "STAFF")

    /**
     * Fetch orders since a timestamp for an ordering-staff (Client) device.
     * Same query as the admin variant — staff catch-up sync reads the same Room tables.
     */
    override suspend fun getOrdersSinceAsStaff(since: String): ApiResult<OrdersSyncResponse> =
        getOrdersSince(since)

    override suspend fun sendToKitchenAsStaff(orderId: String, sessionNumber: Int?): ApiResult<KitchenResponse> =
        notImplemented("sendToKitchenAsStaff")

    override suspend fun addItemsToOrderAsStaff(orderId: String, items: List<NewOrderItem>): ApiResult<OrderDto> =
        notImplemented("addItemsToOrderAsStaff")

    override suspend fun voidOrderItemsAsStaff(orderId: String, lines: List<VoidLine>, reason: String): ApiResult<OrderDto> =
        notImplemented("voidOrderItemsAsStaff")

    override suspend fun processPaymentAsStaff(orderId: String, method: String): ApiResult<OrderDto> =
        notImplemented("processPaymentAsStaff")

    override suspend fun cancelOrderAsStaff(orderId: String, reason: String, cancelledBy: String): ApiResult<Unit> =
        notImplemented("cancelOrderAsStaff")

    override suspend fun getSettings(): ApiResult<SettingsResponse> =
        notImplemented("getSettings")

    override suspend fun putSettings(body: JSONObject): ApiResult<Unit> =
        notImplemented("putSettings")

    override suspend fun getBranding(): ApiResult<BrandingResponse> =
        notImplemented("getBranding")

    override suspend fun putBranding(cafeName: String, logoBase64: String?, paymentQrBase64: String?, paymentQrHash: String?, removePaymentQr: Boolean): ApiResult<BrandingResponse> =
        notImplemented("putBranding")

    override suspend fun getTables(): ApiResult<List<Pair<String, String>>> =
        notImplemented("getTables")

    override suspend fun getTableTokens(): ApiResult<Map<String, String>> =
        notImplemented("getTableTokens")

    override suspend fun putTables(tables: List<Pair<String, String>>): ApiResult<List<String>> =
        notImplemented("putTables")

    override suspend fun getCafeLocation(): ApiResult<CafeLocationResponse> =
        notImplemented("getCafeLocation")

    override suspend fun putCafeLocation(lat: Double, lng: Double, radius: Int): ApiResult<Unit> =
        notImplemented("putCafeLocation")

    override suspend fun postAttendance(event: String, lat: Double, lng: Double, forced: Boolean): ApiResult<Unit> =
        notImplemented("postAttendance")

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * The timestamp format for everything [LocalBackend] writes, and the reason `?since=` can use a
     * plain SQL `>` comparison at all.
     *
     * [OrderDao.getOrdersSince] compares `createdAt` as **text**, so the format has to be
     * fixed-width or the comparison stops matching chronological order. `Instant.toString()` is not:
     * it omits the fractional part when it happens to be zero and drops trailing zero groups
     * otherwise, so the same instant can render as `…:05Z`, `…:05.123Z` or `…:05.123456Z`. Since
     * `'.'` (0x2E) sorts below `'Z'` (0x5A), `…:05.5Z` compares as LESS than `…:05Z` — meaning an
     * order at `.5` seconds is treated as older than one at `.0` in the same second, and with
     * `createdAt > since` it is skipped and never delivered.
     *
     * Six fixed fractional digits plus a literal `Z` makes every timestamp the same length, so text
     * order and time order coincide and that whole class of dropped order disappears.
     *
     * Rows written earlier by the Cloud path may carry Supabase's `+00:00` offset form instead. That
     * is harmless here: `'+'` (0x2B) sorts below both `'.'` and `'Z'`, so those rows sort no later
     * than they should, and they are in the past relative to any `since` a LAN poll supplies.
     */
    private fun nowTimestamp(): String = TIMESTAMP_FORMAT.format(Instant.now())

    private fun notImplemented(method: String): Nothing = throw NotImplementedError(
        "LocalBackend.$method is not implemented yet — LAN/Kiosk backend arrives in task 4 onwards. " +
            "If you reached this from Cloud Mode, the BackendGateway binding picked the wrong " +
            "implementation; check ModeRepository.currentMode() and the device role."
    )

    /**
     * Convert a [NewOrderItem] to an [OrderItem] entity, resolving name/price/category snapshots
     * from [menuIndex] (pre-loaded for the whole batch so we do a single DB read per createOrder
     * call rather than N reads). Falls back gracefully when the menu item is not found — this
     * matches the DemoBackend pattern and means an unknown menuItemId produces a zero-price line
     * rather than crashing the whole order.
     */
    private fun NewOrderItem.toEntity(
        orderId: String,
        menuIndex: Map<String, MenuItem>,
        sessionNumber: Int,
    ): OrderItem {
        val menu = menuIndex[menuItemId]
        return OrderItem(
            id = UUID.randomUUID().toString(),
            orderId = orderId,
            menuItemId = menuItemId,
            // Backend bakes the English name as the line-item snapshot; clients re-resolve per locale
            // at display/print time from the live menu (PrintService.localizeItemNames).
            nameSnapshot = menu?.nameEn ?: menuItemId,
            unitPriceSnapshot = unitPrice ?: menu?.price ?: 0.0,
            categorySnapshot = menu?.category ?: "",
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
}
