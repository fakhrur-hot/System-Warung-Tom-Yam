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
    suspend fun regenerateRecoveryToken(): ApiResult<InviteResponse>
    suspend fun getInvite(role: String? = null): ApiResult<InviteResponse>
    suspend fun regenerateInvite(role: String? = null): ApiResult<InviteResponse>
    suspend fun getDevices(): ApiResult<List<DeviceDto>>
    suspend fun patchDevice(deviceId: String, action: String, label: String? = null): ApiResult<DeviceDto>

    // ── Session lifecycle and reporting ───────────────────────────────────────────
    suspend fun postSession(event: String, reason: String? = null, closing: Boolean = false): ApiResult<SessionResponse>
    suspend fun postAggregates(date: String, body: JSONObject): ApiResult<Unit>

    /**
     * Build the closing report for the current business day and return a signed URL to it.
     *
     * The URL is short-lived (the backend mints it for an hour), so it is meant to be fetched
     * immediately rather than stored. Returns the URL and the business-day date the report
     * covers — the caller needs the date to name the file it saves.
     */
    suspend fun getClosingReport(): ApiResult<ClosingReportRef> = ApiResult.Error(
        "UNSUPPORTED", "Closing report is only available on a cloud backend",
    )

    // ── Menu ──────────────────────────────────────────────────────────────────────
    suspend fun getMenu(): ApiResult<MenuResponse>
    suspend fun putMenu(menuItems: JSONArray, categories: JSONArray = JSONArray()): ApiResult<Unit>
    suspend fun uploadMenuImage(menuItemId: String, imageBase64: String): ApiResult<MenuImageUploadResponse>
    suspend fun deleteMenuImage(path: String): ApiResult<Unit>

    // ── Orders - admin ────────────────────────────────────────────────────────────
    /**
     * @param tableId null in Kiosk Mode, which has no tables — the order is identified by
     *   [orderNumber] instead, minted per business day.
     */
    suspend fun createOrder(
        tableId: String?,
        items: List<NewOrderItem>,
        source: String = "STAFF",
        orderNumber: Int? = null,
        /** A split share settles one customer's slice while the table's main order stays open —
         *  the two orders coexist by design. Staff/admin only; see migration 0016. */
        splitShare: Boolean = false,
    ): ApiResult<CreateOrderResponse>
    suspend fun getOrdersSince(since: String): ApiResult<OrdersSyncResponse>
    suspend fun sendToKitchen(orderId: String, sessionNumber: Int? = null): ApiResult<KitchenResponse>
    suspend fun addItemsToOrder(orderId: String, items: List<NewOrderItem>): ApiResult<OrderDto>
    /**
     * Reduce or remove lines on an active order before payment — the customer never received part of
     * the order and is settling only for what arrived. Each [VoidLine] carries the quantity to KEEP,
     * so a "2× Teh Tarik" line can come down to 1 rather than only all-or-nothing. Voided quantities
     * are kept server-side for audit and excluded from the recomputed total. Refused if it would
     * empty the order (that is a cancellation) or if a quantity would increase (use [addItemsToOrder]).
     */
    suspend fun voidOrderItems(orderId: String, lines: List<VoidLine>, reason: String): ApiResult<OrderDto>
    suspend fun updateOrderStatus(orderId: String, status: String): ApiResult<OrderDto>
    suspend fun processPayment(orderId: String, method: String): ApiResult<OrderDto>
    suspend fun cancelOrder(orderId: String, reason: String, cancelledBy: String): ApiResult<Unit>

    // ── Orders - ordering staff (separate credential, server-enforced RBAC) ───────
    suspend fun createOrderAsStaff(
        tableId: String,
        items: List<NewOrderItem>,
        splitShare: Boolean = false,
    ): ApiResult<CreateOrderResponse>
    suspend fun getOrdersSinceAsStaff(since: String): ApiResult<OrdersSyncResponse>
    suspend fun sendToKitchenAsStaff(orderId: String, sessionNumber: Int? = null): ApiResult<KitchenResponse>
    suspend fun addItemsToOrderAsStaff(orderId: String, items: List<NewOrderItem>): ApiResult<OrderDto>
    suspend fun voidOrderItemsAsStaff(orderId: String, lines: List<VoidLine>, reason: String): ApiResult<OrderDto>
    suspend fun processPaymentAsStaff(orderId: String, method: String): ApiResult<OrderDto>
    suspend fun cancelOrderAsStaff(orderId: String, reason: String, cancelledBy: String): ApiResult<Unit>

    // ── Payment gateway ────────────────────────────────────────────────────────
    /**
     * Initiate a gateway payment attempt. Creates/uses a [PaymentTransaction] row and forwards
     * to the `payment-initiate` Edge Function, which holds credentials and computes signatures
     * server-side — the POS never sees the aggregator secret. (PG-REQ-4, A2, A3)
     *
     * The [payload]'s [PosCheckoutPayload.idempotencyKey] MUST equal
     * [PaymentTransaction.idempotencyKeyFor]`(orderId, amountSen)`: stable for this (order, amount)
     * pair and reused verbatim on every retry, never derived from a timestamp or a per-attempt id.
     * The backend upserts on this key, so a retry updates the same row rather than creating a new
     * one. (A6, 6.3)
     *
     * This method is unavailable in LAN and KIOSK modes — [ModeCapabilities.gatewayPaymentsEnabled]
     * will be false there, and callers must check before showing gateway tiles. (A1, 6.4)
     */
    suspend fun initiatePayment(payload: PosCheckoutPayload): ApiResult<GatewayPaymentResult>

    /**
     * Query the gateway for a transaction's current status (PENDING / SUCCESS / FAILED).
     * Used by the polling loop in the QR flow. (PG-REQ-4a, 8.2)
     *
     * Note: the aggregator requery expires after 24 hours. The persisted [PaymentTransaction]
     * row is the authoritative source of truth after that window closes. (F5, 6.2c)
     */
    suspend fun queryPayment(transactionId: String): ApiResult<GatewayPaymentResult>

    /**
     * Fetch every payment attempt for an order, newest-first.
     * Drives the retry history panel and task 8.5's crash-recovery display. (PG-REQ-5, 8.5)
     */
    suspend fun listPaymentTransactions(orderId: String): ApiResult<List<PaymentTransactionDto>>

    // Staff variants — ordering-key auth, same contract as the admin counterparts. Staff devices
    // take payment too (A2, A14).
    suspend fun initiatePaymentAsStaff(payload: PosCheckoutPayload): ApiResult<GatewayPaymentResult>
    suspend fun queryPaymentAsStaff(transactionId: String): ApiResult<GatewayPaymentResult>
    suspend fun listPaymentTransactionsAsStaff(orderId: String): ApiResult<List<PaymentTransactionDto>>

    /**
     * Read-only view of the café's gateway configuration — **never** includes the verify/secret
     * key, only whether each is set. Drives which gateway tiles task 7.2 shows at checkout (a
     * staff device has no [com.razstudio.pos.data.GatewayCredentialStore] of its own — this is how
     * it learns which channels are enabled) and lets the settings screen (7.1) render "already
     * configured" without ever re-displaying a secret. (PG-REQ-2, PG-REQ-8)
     */
    suspend fun getGatewayConfig(): ApiResult<GatewayConfigDto>
    suspend fun getGatewayConfigAsStaff(): ApiResult<GatewayConfigDto>

    /**
     * Write the café's gateway configuration. **Admin-only — there is no staff variant**, matching
     * [com.razstudio.pos.data.GatewayCredentialStore]'s own restriction that only the admin
     * settings screen ever calls `readSecretsForUpload()`. (task 7.1)
     *
     * [verifyKey] and [secretKey] are `null` to mean "leave unchanged" — the Edge Function never
     * returns a secret's value, so this is the only way a screen can distinguish "nothing set yet"
     * from "keep the existing one"; a blank string must never be sent to mean "clear it silently".
     */
    suspend fun putGatewayConfig(
        merchantId: String,
        verifyKey: String?,
        secretKey: String?,
        isSandbox: Boolean,
        enabledMethods: List<String>,
    ): ApiResult<GatewayConfigDto>

    /**
     * Every payment provider the café can configure, with each one's credential **field spec** so
     * the settings screen renders the right form without hardcoding one per provider. (PG-REQ-2)
     *
     * Supersedes [getGatewayConfig], which could only describe a single aggregator holding one
     * merchant id + verify key + secret key. Touch 'n Go direct and DuitNow via an acquiring bank
     * are separate merchant relationships with different credential shapes and a callback each.
     *
     * Readable by staff as well as admin: a staff device has no local credential store, so this is
     * how it learns which channels to offer. No credential **value** is ever returned — only
     * [GatewayProviderDto.fieldsSet], which reports whether each field has something stored.
     */
    suspend fun getGatewayProviders(): ApiResult<List<GatewayProviderDto>>
    suspend fun getGatewayProvidersAsStaff(): ApiResult<List<GatewayProviderDto>>

    /**
     * Save one provider's configuration. Admin-only, matching [putGatewayConfig].
     *
     * [credentials] is merged server-side, not replaced: a field left out keeps its stored value,
     * which is what lets the screen show a masked "already set" placeholder without round-tripping
     * a secret. Sending a field as `""` clears it deliberately.
     */
    suspend fun putGatewayProvider(
        provider: String,
        credentials: Map<String, String>,
        enabledMethods: List<String>,
        isSandbox: Boolean,
        isEnabled: Boolean,
    ): ApiResult<Unit>

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
     *
     * [qrCardLogoBase64]/[removeQrCardLogo] follow the same omit/set/null-to-remove convention as the
     * payment QR, but for the logo picked specifically on the Generate Table QR screen — uploading it
     * here (rather than leaving it device-local) is what lets it survive an app reinstall or show up
     * on a second admin device.
     */
    suspend fun putBranding(
        cafeName: String,
        logoBase64: String? = null,
        paymentQrBase64: String? = null,
        paymentQrHash: String? = null,
        removePaymentQr: Boolean = false,
        qrCardLogoBase64: String? = null,
        removeQrCardLogo: Boolean = false,
    ): ApiResult<BrandingResponse>
    suspend fun getTables(): ApiResult<List<Pair<String, String>>>
    suspend fun getTableTokens(): ApiResult<Map<String, String>>
    suspend fun putTables(tables: List<Pair<String, String>>): ApiResult<List<String>>
    suspend fun getCafeLocation(): ApiResult<CafeLocationResponse>
    suspend fun putCafeLocation(lat: Double, lng: Double, radius: Int): ApiResult<Unit>
    suspend fun postAttendance(event: String, lat: Double, lng: Double, forced: Boolean = false): ApiResult<Unit>

    // ── Payment alerts (admin → admin) ────────────────────────────────────────────
    //
    // The phone holding the café's banking app is often the owner's own handset running as a
    // Secondary Admin, not the till. The till is what holds the printer and matches payments to
    // orders, so a capture has to travel between them. These two calls are that hop.
    //
    // Both default to an error rather than being abstract: off-cloud there is no second device to
    // forward to (Kiosk) or the LAN push bus already carries it (LAN), and Demo Mode has no backend
    // at all. Declaring them abstract would force three implementations that all mean "not here".

    /**
     * Forward a notification this device captured, so the main admin can match it to an order.
     *
     * [clientId] is this device's own id for the capture and is what makes the call idempotent —
     * the caller is a notification listener that does not await the result, so a retry after a
     * dropped response is ordinary, and a second row would let the till match one payment twice.
     */
    suspend fun postPaymentAlert(
        clientId: String,
        amountSen: Long,
        walletApp: String,
        sender: String?,
        rawText: String,
        capturedAt: String,
    ): ApiResult<Unit> = ApiResult.Error(
        "UNSUPPORTED", "Payment alert forwarding needs a cloud backend",
    )

    /** Drain alerts forwarded since [since]. Main-admin only — the backend enforces that. */
    suspend fun getPaymentAlerts(since: String): ApiResult<PaymentAlertsResponse> = ApiResult.Error(
        "UNSUPPORTED", "Payment alert forwarding needs a cloud backend",
    )
}
