package com.razstudio.pos.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's backend, as an interface (task 3.1, Requirement 4.2).
 *
 * Every backend call the app makes goes through here. This is the seam the three operating modes swap
 * at, and it exists because [ApiClient] already proved the pattern works: nearly all of its methods
 * open with `if (DemoSession.active) return demoBackend.…`, routing the whole app at an in-process
 * fake. This interface makes that swap explicit and type-checked instead of a per-method convention.
 *
 * Planned implementations:
 *
 * | Mode    | Device        | Implementation                                            |
 * |---------|---------------|-----------------------------------------------------------|
 * | `CLOUD` | any           | [ApiClient] — HTTPS to Supabase Edge Functions, unchanged |
 * | `LAN`   | Server (admin)| `LocalBackend` — in-process, Room-backed (task 4)         |
 * | `LAN`   | Client (staff)| [ApiClient] with base URL `http://<server>:<port>`        |
 * | `KIOSK` | admin         | `LocalBackend`                                            |
 *
 * Because a LAN Client reuses the HTTP implementation verbatim against a different host, the entire
 * ordering-staff feature set works in LAN Mode with no new client code — which is the point of
 * Requirement 4.2 insisting the LAN Server speak the same request/response shapes.
 *
 * ### Notes for implementers
 *
 * - **Default parameter values live here, not in implementations.** Kotlin forbids an override from
 *   re-declaring a default, so the defaults were removed from [ApiClient] when it adopted this
 *   interface. A caller holding a `BackendGateway` gets the same ergonomics as before.
 * - **`adminHandshake` is deliberately absent.** It is the rotating-key handshake, is not wired to any
 *   release UI, and has no meaning off-cloud — including it would force `LocalBackend` to stub a
 *   handshake it can never serve. It remains a public method on [ApiClient] for the cloud path.
 * - Errors are returned as `ApiResult`, never thrown. Implementations must preserve the **401
 *   contract**: an expired/revoked credential has to surface the same way, because that is what makes
 *   a client clear its token and re-authenticate (see `AuthEventBus`).
 */
interface BackendGateway {

    // ── Onboarding, auth, and device registry ─────────────────────────────────────
    suspend fun adminHandshakeDebug(deviceId: String, cafeName: String): ApiResult<String>
    suspend fun register(inviteToken: String, deviceId: String, deviceModel: String, androidId: String, appVersion: String): ApiResult<RegisterResponse>
    suspend fun pollDeviceStatus(deviceId: String): ApiResult<DeviceStatusResponse>
    suspend fun recoverAdmin(recoveryToken: String, deviceId: String, deviceModel: String): ApiResult<String>
    suspend fun getRecoveryToken(): ApiResult<InviteResponse>
    suspend fun getInvite(role: String? = null): ApiResult<InviteResponse>
    suspend fun regenerateInvite(role: String? = null): ApiResult<InviteResponse>
    suspend fun getDevices(): ApiResult<List<DeviceDto>>
    suspend fun patchDevice(deviceId: String, action: String, label: String? = null): ApiResult<DeviceDto>

    // ── Session lifecycle and reporting ───────────────────────────────────────────
    suspend fun postSession(event: String, reason: String? = null, closing: Boolean = false): ApiResult<SessionResponse>
    suspend fun postAggregates(date: String, body: JSONObject): ApiResult<Unit>

    // ── Menu ──────────────────────────────────────────────────────────────────────
    suspend fun getMenu(): ApiResult<MenuResponse>
    suspend fun putMenu(menuItems: JSONArray, categories: JSONArray = JSONArray()): ApiResult<Unit>
    suspend fun uploadMenuImage(menuItemId: String, imageBase64: String): ApiResult<MenuImageUploadResponse>
    suspend fun deleteMenuImage(path: String): ApiResult<Unit>

    // ── Orders - admin ────────────────────────────────────────────────────────────
    suspend fun createOrder(tableId: String, items: List<NewOrderItem>, source: String = "STAFF"): ApiResult<CreateOrderResponse>
    suspend fun getOrdersSince(since: String): ApiResult<OrdersSyncResponse>
    suspend fun sendToKitchen(orderId: String, sessionNumber: Int? = null): ApiResult<KitchenResponse>
    suspend fun addItemsToOrder(orderId: String, items: List<NewOrderItem>): ApiResult<OrderDto>
    suspend fun updateOrderStatus(orderId: String, status: String): ApiResult<OrderDto>
    suspend fun processPayment(orderId: String, method: String): ApiResult<OrderDto>
    suspend fun cancelOrder(orderId: String, reason: String, cancelledBy: String): ApiResult<Unit>

    // ── Orders - ordering staff (separate credential, server-enforced RBAC) ───────
    suspend fun createOrderAsStaff(tableId: String, items: List<NewOrderItem>): ApiResult<CreateOrderResponse>
    suspend fun getOrdersSinceAsStaff(since: String): ApiResult<OrdersSyncResponse>
    suspend fun sendToKitchenAsStaff(orderId: String, sessionNumber: Int? = null): ApiResult<KitchenResponse>
    suspend fun addItemsToOrderAsStaff(orderId: String, items: List<NewOrderItem>): ApiResult<OrderDto>
    suspend fun processPaymentAsStaff(orderId: String, method: String): ApiResult<OrderDto>
    suspend fun cancelOrderAsStaff(orderId: String, reason: String, cancelledBy: String): ApiResult<Unit>

    // ── Settings, branding, tables, location, attendance ──────────────────────────
    suspend fun getSettings(): ApiResult<SettingsResponse>
    suspend fun putSettings(body: JSONObject): ApiResult<Unit>
    suspend fun getBranding(): ApiResult<BrandingResponse>
    /**
     * Updates branding, and optionally the café's Payment QR (task 16.2).
     *
     * The Payment QR has three distinct intents, which is why [removePaymentQr] exists rather than
     * relying on a null [paymentQrBase64]: Kotlin cannot distinguish "argument omitted" from
     * "argument passed as null", but the server must. Omitting leaves an existing QR untouched (the
     * admin is only renaming the café); [removePaymentQr] deletes it, which is what makes the Show QR
     * button disappear on every device (Requirement 14.5).
     *
     * [paymentQrHash] is the SHA-256 of the uploaded bytes. Devices cache on that hash rather than the
     * URL, because the object key is stable across replacements — see `PaymentQrResolver`.
     */
    suspend fun putBranding(
        cafeName: String,
        logoBase64: String? = null,
        paymentQrBase64: String? = null,
        paymentQrHash: String? = null,
        removePaymentQr: Boolean = false,
    ): ApiResult<BrandingResponse>
    suspend fun getTables(): ApiResult<List<Pair<String, String>>>
    suspend fun getTableTokens(): ApiResult<Map<String, String>>
    suspend fun putTables(tables: List<Pair<String, String>>): ApiResult<List<String>>
    suspend fun getCafeLocation(): ApiResult<CafeLocationResponse>
    suspend fun putCafeLocation(lat: Double, lng: Double, radius: Int): ApiResult<Unit>
    suspend fun postAttendance(event: String, lat: Double, lng: Double, forced: Boolean = false): ApiResult<Unit>
}
