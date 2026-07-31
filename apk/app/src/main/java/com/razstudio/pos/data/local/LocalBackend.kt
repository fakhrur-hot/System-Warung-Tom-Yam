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
import com.razstudio.pos.data.OrdersSyncResponse
import com.razstudio.pos.data.RegisterResponse
import com.razstudio.pos.data.SessionResponse
import com.razstudio.pos.data.SettingsResponse
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The in-process, Room-backed [BackendGateway] used by a LAN **Server** device and by Kiosk Mode
 * (task 3.3 wires the binding; the implementations land in task 4 onwards).
 *
 * Every method currently throws. That is deliberate and preferable to returning plausible empty
 * values: a stub that answers `ApiResult.Success(emptyList())` would let a half-built LAN Mode look
 * like it was working while silently losing orders. Throwing means the first unimplemented call site
 * is impossible to miss, and the exception names the method so it is obvious which task owns it.
 *
 * Cloud Mode never constructs this class — `BackendModule` provides it lazily, so the stub cannot
 * affect an existing install.
 */
@Singleton
class LocalBackend @Inject constructor() : BackendGateway {

    private fun notImplemented(method: String): Nothing = throw NotImplementedError(
        "LocalBackend.$method is not implemented yet — LAN/Kiosk backend arrives in task 4 onwards. " +
            "If you reached this from Cloud Mode, the BackendGateway binding picked the wrong " +
            "implementation; check ModeRepository.currentMode() and the device role."
    )

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

    override suspend fun createOrder(tableId: String, items: List<NewOrderItem>, source: String): ApiResult<CreateOrderResponse> =
        notImplemented("createOrder")

    override suspend fun getOrdersSince(since: String): ApiResult<OrdersSyncResponse> =
        notImplemented("getOrdersSince")

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

    override suspend fun createOrderAsStaff(tableId: String, items: List<NewOrderItem>): ApiResult<CreateOrderResponse> =
        notImplemented("createOrderAsStaff")

    override suspend fun getOrdersSinceAsStaff(since: String): ApiResult<OrdersSyncResponse> =
        notImplemented("getOrdersSinceAsStaff")

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
}
