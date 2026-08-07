package com.razstudio.pos.data.lan

import android.util.Log
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.NewOrderItem
import com.razstudio.pos.data.VoidLine
import com.razstudio.pos.data.local.LocalBackend
import com.razstudio.pos.data.local.PairedDeviceDao
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The HTTP face of [LocalBackend], so Client Devices can reach it over the café's Wi-Fi
 * (task 6.2 — Requirements 4.2, 5.3).
 *
 * ### Why the routes look like Supabase
 *
 * A LAN Client runs the **unmodified** `ApiClient`, with its base URL pointed at
 * `http://<server>:8765` instead of a Supabase project. That is the whole design: one HTTP client,
 * one set of call sites, and the topology decided by a URL. It only works if the paths match
 * exactly, so every route here mirrors an Edge Function name under the same `/functions/v1` prefix
 * that `ApiClient.baseUrl()` appends. A route renamed for tidiness here silently breaks that device.
 *
 * ### The `apikey` header is accepted and ignored
 *
 * `ApiClient` sends Supabase's anon key on every request. It means nothing off-cloud — there is no
 * project to identify — but rejecting requests that carry it would mean forking the client. It is
 * read and discarded.
 *
 * ### What actually authenticates a request
 *
 * The `Authorization: Bearer <credential>` header, matched against [PairedDeviceDao] by hash. A
 * device that is missing, PENDING or REVOKED gets **401**, which is the contract that matters: the
 * client's OkHttp interceptor turns a 401 into `AuthEventBus.SessionExpired` and routes the user
 * back to re-auth. Revoking a device from the admin's Devices screen therefore ejects it on its very
 * next request, exactly as it does in Cloud Mode, with no extra client code.
 *
 * ### Scope
 *
 * Only the endpoints a Client Device actually calls are served. Admin-only surfaces (branding,
 * aggregates, invites, the devices list) are deliberately absent: the admin IS this device and talks
 * to [LocalBackend] in-process, so exposing them on the wire would add attack surface for callers
 * that do not exist.
 */
@Singleton
class LanServer @Inject constructor(
    private val backend: LocalBackend,
    private val pairedDeviceDao: PairedDeviceDao,
    private val lanAddress: LanAddress,
    private val pushBus: LanPushBus,
    private val cloudKeyVerifier: CloudOrderingKeyVerifier,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) {

    private var nsdRegistration: android.net.nsd.NsdManager.RegistrationListener? = null

    @Volatile
    private var engine: io.ktor.server.engine.ApplicationEngine? = null

    /** Cloud mode: only the push socket is registered. See [start]. */
    private var pushOnly: Boolean = false

    /**
     * Why the last [start] failed, for the pairing screen to show (task 14.2). Null when the server
     * is running or has never been started.
     */
    @Volatile
    var lastStartError: String? = null
        private set

    /** Where the server ended up listening, for the pairing QR. Null when stopped. */
    @Volatile
    var boundHost: String? = null
        private set

    val isRunning: Boolean get() = engine != null

    /**
     * Start listening on the café network.
     *
     * Binds `0.0.0.0` rather than the resolved LAN IP: a phone acting as its own access point can
     * change interface address when tethering restarts, and a server pinned to the old one would
     * silently stop accepting connections while still appearing to run. [boundHost] still reports the
     * resolved address, because that is what the pairing QR must carry.
     *
     * Returns false when there is no usable network — see [LanAddress]. Refusing to start is
     * deliberate: a server listening on an unreachable interface is indistinguishable, from the
     * admin's side, from one that is working.
     */
    fun start(pushOnly: Boolean = false): Boolean {
        if (engine != null) return true
        this.pushOnly = pushOnly

        val address = lanAddress.resolve()
        if (address !is LanAddress.Result.Found) {
            lastStartError = (address as LanAddress.Result.Unavailable).reason
            Log.w(TAG, "Not starting: $lastStartError")
            return false
        }
        lastStartError = null

        return try {
            engine = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
                // No ContentNegotiation plugin: every response here is built with org.json and
                // written via respondText, because the shapes must match the Edge Functions
                // byte-for-byte and a serializer would impose its own. See LanJson.
                install(WebSockets) {
                    // Ping the Client periodically. A café AP will silently drop an idle TCP
                    // connection, and without pings the Server keeps a dead socket in its fan-out
                    // list while the Client sits waiting for pushes that can never arrive — the
                    // failure looks exactly like "no orders", which is indistinguishable from a
                    // quiet afternoon.
                    pingPeriodMillis = 20_000
                    timeoutMillis = 30_000
                }
                // Cloud cafés get the push socket and NOTHING else. The REST routes below are the
                // HTTP face of LocalBackend, which in Cloud mode is a mirror rather than the
                // authority — serving them would have staff reading and writing the wrong database
                // while the cloud silently disagreed. Push carries no data (it is a trigger), so it
                // is safe to expose where the REST surface is not.
                if (pushOnly) pushRouteOnly() else routes()
            }.also { it.start(wait = false) }
            boundHost = address.ip
            advertiseOverMdns()
            Log.i(TAG, "Listening on ${address.ip}:$PORT (${address.interfaceName})")
            true
        } catch (t: Throwable) {
            // Task 14.2 / Requirement 4.5 — name the port. "Server failed to start" sends an
            // operator nowhere; "port 8765 is already in use" tells them another copy of the app,
            // or another app, is holding it, which is something they can act on.
            lastStartError = if (t is java.net.BindException) {
                "Port $PORT is already in use on this device. Close any other copy of the app, or " +
                    "restart the device, then try again."
            } else {
                "The café server could not start: ${t.message ?: t.javaClass.simpleName}"
            }
            Log.e(TAG, "Failed to start: $lastStartError", t)
            engine = null
            boundHost = null
            false
        }
    }

    fun stop() {
        val current = engine ?: return
        engine = null
        boundHost = null
        withdrawMdns()
        runCatching { current.stop(gracePeriodMillis = 500, timeoutMillis = 2_000) }
            .onFailure { Log.w(TAG, "Stop failed", it) }
        Log.i(TAG, "Stopped")
    }

    /**
     * Announce this server on the local link so a Client that lost the address can find it again
     * (task 7.3, Requirement 5.5).
     *
     * Advertising is what makes `LanServerLocator`'s middle branch work at all. Without it, a staff
     * phone whose stored address went stale has only "re-scan the QR" — which needs the admin to
     * stop what they are doing and produce a new code, during service.
     *
     * Best-effort by design: mDNS is unavailable or unreliable on some OEM builds and on networks
     * that block multicast. A failure here costs the discovery branch, not the server, so it is
     * logged and swallowed rather than failing the start.
     */
    private fun advertiseOverMdns() {
        if (nsdRegistration != null) return
        val nsd = appContext.getSystemService(android.content.Context.NSD_SERVICE)
            as? android.net.nsd.NsdManager ?: return

        val info = android.net.nsd.NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val listener = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: android.net.nsd.NsdServiceInfo) {
                Log.i(TAG, "Advertising as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode — discovery recovery unavailable")
            }
            override fun onServiceUnregistered(info: android.net.nsd.NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: android.net.nsd.NsdServiceInfo, errorCode: Int) = Unit
        }

        runCatching {
            nsd.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener)
            nsdRegistration = listener
        }.onFailure { Log.w(TAG, "mDNS registration threw", it) }
    }

    private fun withdrawMdns() {
        val listener = nsdRegistration ?: return
        nsdRegistration = null
        val nsd = appContext.getSystemService(android.content.Context.NSD_SERVICE)
            as? android.net.nsd.NsdManager ?: return
        runCatching { nsd.unregisterService(listener) }
            .onFailure { Log.w(TAG, "mDNS unregister failed", it) }
    }

    // ── Routing ───────────────────────────────────────────────────────────────────────────────────

    private fun io.ktor.server.application.Application.routes() = routing {
        // Pairing. Unauthenticated by necessity — a device registering does not have a credential
        // yet; the pairing token it presents is the credential for this one call, and LocalBackend
        // enforces its expiry and single use.
        post("$PREFIX/register") { respond { body ->
            backend.register(
                inviteToken = body.optString("inviteToken", body.optString("token", "")),
                deviceId = body.optString("deviceId", ""),
                deviceModel = body.optString("deviceModel", ""),
                androidId = body.optString("androidId", ""),
                appVersion = body.optString("appVersion", ""),
            ).toJson { JSONObject().put("deviceId", it.deviceId).put("status", it.status) }
        } }

        // The approval poll. Also unauthenticated: the device is asking whether it has been approved,
        // and this is where it collects the credential it does not yet hold.
        get("$PREFIX/devices-status") {
            val deviceId = call.request.queryParameters["deviceId"].orEmpty()
            call.respondResult(
                backend.pollDeviceStatus(deviceId).toJson {
                    JSONObject()
                        .put("status", it.status)
                        .put("role", it.role ?: JSONObject.NULL)
                        .put("apiKey", it.apiKey ?: JSONObject.NULL)
                        .put("sessionToken", it.sessionToken ?: JSONObject.NULL)
                }
            )
        }

        // ── Push channel (task 8.4, Requirement 6.5) ─────────────────────────────────────────────
        // Same Bearer credential as the REST routes. An unapproved device is closed immediately with
        // the WebSocket equivalent of the 401 contract, so revoking a device ejects it from the
        // socket exactly as it ejects it from a request — a revoked phone must not keep receiving
        // the café's order stream just because it connected before the revocation.
        realtimeSocket()

        // ── The Payment QR image (task 15.3, Requirement 14.8) ───────────────────────────────────
        // Authenticated like everything else: the payee code identifies where the café's money goes,
        // so it is not left readable by anything that can reach the port.
        //
        // Served as raw bytes straight from the stored file, never re-encoded. A dense QR pushed
        // through a lossy round-trip can smear until a scanner fails on it, and the failure appears
        // at the counter with a customer waiting.
        get("/media/payment-qr") {
            if (!call.authorizedDeviceOrNull()) return@get call.unauthorized()

            val file = com.razstudio.pos.ui.util.PaymentQrPipeline.storedFileOrNull(appContext)
            if (file == null) {
                call.respondText(
                    JSONObject().put("error", "NOT_FOUND").put("message", "No payment QR configured").toString(),
                    contentType = JSON_CT,
                    status = HttpStatusCode.NotFound,
                )
                return@get
            }
            call.respondBytes(
                bytes = file.readBytes(),
                contentType = io.ktor.http.ContentType.Image.PNG,
                status = HttpStatusCode.OK,
            )
        }

        // ── Everything below requires an approved device ─────────────────────────────────────────

        get("$PREFIX/orders") { authed {
            backend.getOrdersSinceAsStaff(call.request.queryParameters["since"].orEmpty()).toJson { sync ->
                JSONObject()
                    .put("orders", JSONArray(sync.orders.map { it.toJson() }))
                    .put("serverTime", sync.serverTime)
            }
        } }

        post("$PREFIX/orders") { authedWithBody { body ->
            backend.createOrderAsStaff(
                tableId = body.optString("tableId", ""),
                items = body.newOrderItems(),
            ).toJson {
                JSONObject().put("orderId", it.orderId).put("total", it.total).put("status", it.status)
            }
        } }

        post("$PREFIX/orders-kitchen/{id}") { authedWithBody { body ->
            val session = if (body.has("sessionNumber")) body.optInt("sessionNumber") else null
            backend.sendToKitchenAsStaff(call.pathId(), session).toJson { r ->
                JSONObject()
                    .put("order", r.order.toJson())
                    .put("linesToPrint", JSONArray(r.linesToPrint.map { it.toJson() }))
            }
        } }

        post("$PREFIX/orders-items/{id}") { authedWithBody { body ->
            backend.addItemsToOrderAsStaff(call.pathId(), body.newOrderItems()).toJson { it.toJson() }
        } }

        post("$PREFIX/orders-items-void/{id}") { authedWithBody { body ->
            val lines = body.optJSONArray("lines")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { VoidLine(it.optString("id", ""), it.optInt("quantity", 0)) }
                }
            }.orEmpty()
            backend.voidOrderItemsAsStaff(call.pathId(), lines, body.optString("reason", ""))
                .toJson { it.toJson() }
        } }

        put("$PREFIX/orders-status/{id}") { authedWithBody { body ->
            backend.updateOrderStatus(call.pathId(), body.optString("status", "")).toJson { it.toJson() }
        } }

        post("$PREFIX/orders-payment/{id}") { authedWithBody { body ->
            backend.processPaymentAsStaff(call.pathId(), body.optString("method", "")).toJson { it.toJson() }
        } }

        post("$PREFIX/orders-cancel/{id}") { authedWithBody { body ->
            backend.cancelOrderAsStaff(
                orderId = call.pathId(),
                reason = body.optString("reason", ""),
                cancelledBy = body.optString("cancelledBy", "staff"),
            ).toJson { JSONObject().put("ok", true) }
        } }

        get("$PREFIX/menu") { authed {
            backend.getMenu().toJson { menu ->
                JSONObject()
                    .put("configured", menu.configured)
                    .put("items", JSONArray(menu.items.map { it.toMenuJson() }))
                    .put("categories", JSONArray(menu.categories.map { c ->
                        JSONObject().put("name", c.name).put("sortOrder", c.sortOrder)
                    }))
            }
        } }

        get("$PREFIX/settings") { authed {
            backend.getSettings().toJson { s ->
                JSONObject()
                    .put("printLanguage", s.printLanguage)
                    .put("timezone", s.timezone)
                    .put("topN", s.topN)
                    .put("staffCanSendKitchen", s.staffCanSendKitchen)
                    .put("staffCanTakePayment", s.staffCanTakePayment)
                    .put("customerOrderHoldSeconds", s.customerOrderHoldSeconds)
                    .put("customerOrderAutoPrint", s.customerOrderAutoPrint)
                    .put("todaysSpecial", s.todaysSpecial)
                    .put("reportEmail", s.reportEmail)
                    .put("businessDayStartHour", s.businessDayStartHour)
                    .put("businessDayEndHour", s.businessDayEndHour)
                    .put("defaultLangAdmin", s.defaultLangAdmin)
                    .put("defaultLangOrdering", s.defaultLangOrdering)
                    .put("defaultLangCustomer", s.defaultLangCustomer)
            }
        } }

        get("$PREFIX/tables") { authed {
            backend.getTables().toJson { tables ->
                JSONObject().put("tables", JSONArray(tables.map { (id, name) ->
                    JSONObject().put("id", id).put("displayName", name)
                }))
            }
        } }
    }

    // ── Auth + response plumbing ──────────────────────────────────────────────────────────────────

    /**
     * Reject anything not presenting an APPROVED device's credential.
     *
     * 401 specifically, not 403: the client interceptor keys on 401 to clear its stored credential
     * and emit `SessionExpired`. A 403 would leave a revoked device retrying forever with a
     * credential that will never work again.
     */

    /**
     * The push socket, registered by both the full LAN route set and the Cloud push-only server.
     *
     * Extracted rather than duplicated: this is the one route Cloud mode exposes, and a second copy
     * would be a second place for the auth check or the ACK handling to drift.
     */
    private fun io.ktor.server.routing.Route.realtimeSocket() {
        webSocket("$PREFIX/realtime") {
            if (!call.authorizedDeviceOrNull()) {
                close(
                    CloseReason(
                        CloseReason.Codes.VIOLATED_POLICY,
                        "UNAUTHORIZED",
                    )
                )
                return@webSocket
            }

            // Fan out every published change to this Client for as long as the socket lives.
            val job = launch {
                pushBus.events.collect { envelope ->
                    runCatching { send(Frame.Text(envelope.encode())) }
                        .onFailure { Log.d(TAG, "Push send failed; socket closing") }
                }
            }

            try {
                // Inbound: ACKs and catch-up requests. Read rather than ignored, because a socket
                // whose incoming frames are never consumed stops honouring pings and is torn down.
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    val envelope = LanPushEnvelope.decode(text) ?: continue
                    when (envelope.type) {
                        LanPushEnvelope.Type.ACK ->
                            Log.d(TAG, "ACK for ${envelope.ackFor}")
                        LanPushEnvelope.Type.STATUS_REQUEST ->
                            // Nothing replayed here on purpose: the Client's own catch-up poll is
                            // the authoritative reconciliation path (Requirement 6.6), and a second
                            // replay mechanism would be a second thing that can disagree with it.
                            Log.d(TAG, "Catch-up requested from ${envelope.lastSeenId} — poll will serve it")
                        else -> Unit
                    }
                }
            } finally {
                job.cancel()
            }
        }
    }

    /**
     * Cloud mode's entire surface: the push socket and nothing else. See [start].
     */
    private fun io.ktor.server.application.Application.pushRouteOnly() = routing {
        realtimeSocket()
    }

    private suspend fun io.ktor.server.application.ApplicationCall.authorizedDeviceOrNull(): Boolean {
        val bearer = request.headers["Authorization"]
            ?.removePrefix("Bearer ")
            ?.trim()
            .orEmpty()
        if (bearer.isEmpty()) return false

        // Cloud mode: the admin never issued this credential and cannot hash it into a match — the
        // ordering key is the backend's secret. Ask the backend instead. See CloudOrderingKeyVerifier.
        if (pushOnly) return cloudKeyVerifier.isValid(bearer)

        val hash = LocalBackend.hashCredentialForLan(bearer)
        val device = pairedDeviceDao.getAllOnce().firstOrNull { it.credentialHash == hash }
            ?: return false
        return device.status == STATUS_APPROVED
    }

    private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.authed(
        block: suspend () -> Pair<HttpStatusCode, String>,
    ) {
        if (!call.authorizedDeviceOrNull()) return call.unauthorized()
        val (status, body) = block()
        call.respondText(body, contentType = JSON_CT, status = status)
    }

    private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.authedWithBody(
        block: suspend (JSONObject) -> Pair<HttpStatusCode, String>,
    ) {
        if (!call.authorizedDeviceOrNull()) return call.unauthorized()
        val (status, body) = block(call.readJsonBody())
        call.respondText(body, contentType = JSON_CT, status = status)
    }

    private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.respond(
        block: suspend (JSONObject) -> Pair<HttpStatusCode, String>,
    ) {
        val (status, body) = block(call.readJsonBody())
        call.respondText(body, contentType = JSON_CT, status = status)
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondResult(
        result: Pair<HttpStatusCode, String>,
    ) = respondText(result.second, contentType = JSON_CT, status = result.first)

    private suspend fun io.ktor.server.application.ApplicationCall.unauthorized() =
        respondText(
            JSONObject().put("error", "UNAUTHORIZED").put("message", "Device is not approved").toString(),
            contentType = JSON_CT,
            status = HttpStatusCode.Unauthorized,
        )

    private suspend fun io.ktor.server.application.ApplicationCall.readJsonBody(): JSONObject =
        runCatching { JSONObject(receiveText().ifBlank { "{}" }) }.getOrElse { JSONObject() }

    private fun io.ktor.server.application.ApplicationCall.pathId(): String =
        parameters["id"].orEmpty()

    /**
     * Map an [ApiResult] onto the status/body shape the Edge Functions return, so the client's
     * existing per-code error handling works unchanged.
     */
    private fun <T> ApiResult<T>.toJson(render: (T) -> JSONObject): Pair<HttpStatusCode, String> =
        when (this) {
            is ApiResult.Success -> HttpStatusCode.OK to render(data).toString()
            is ApiResult.Error -> statusFor(code) to
                JSONObject().put("error", code).put("message", message).toString()
            is ApiResult.NetworkError -> HttpStatusCode.ServiceUnavailable to
                JSONObject().put("error", "NETWORK").put("message", message).toString()
        }

    /** Error codes carry their HTTP status, matching the Edge Functions the client was written for. */
    private fun statusFor(code: String): HttpStatusCode = when (code) {
        "NOT_FOUND" -> HttpStatusCode.NotFound
        "UNAUTHORIZED", "INVALID_TOKEN" -> HttpStatusCode.Unauthorized
        "VALIDATION", "CANNOT_INCREASE" -> HttpStatusCode.UnprocessableEntity
        "ORDER_CLOSED", "SESSION_LIMIT", "ALREADY_VOIDED",
        "WOULD_EMPTY_ORDER", "ALREADY_PAID", "PAYMENT_CONFLICT", "ALREADY_PAIRED",
        -> HttpStatusCode.Conflict
        else -> HttpStatusCode.InternalServerError
    }

    private fun JSONObject.newOrderItems(): List<NewOrderItem> {
        val arr = optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            NewOrderItem(
                menuItemId = o.optString("menuItemId", ""),
                quantity = o.optInt("quantity", 0),
                note = o.optString("note", "").ifBlank { null },
                unitPrice = if (o.has("unitPrice")) o.optDouble("unitPrice") else null,
                size = o.optString("size", "").ifBlank { null },
                // A staff device may relay a cashier-typed custom charge; the name has to survive
                // the hop or the line arrives priced-but-nameless on the admin device.
                customName = o.optString("customName", "").ifBlank { null },
            )
        }
    }

    private companion object {
        const val TAG = "LanServer"

        /** The pairing QR carries it, so the QR's payload class owns the number. */
        const val PORT = com.razstudio.pos.data.lan.PairingQrPayload.PORT

        /** `ApiClient.baseUrl()` appends this; the routes have to live under it. */
        const val PREFIX = "/functions/v1"

        const val STATUS_APPROVED = "APPROVED"

        /** Must match LanServerLocator's SERVICE_TYPE — the two halves of discovery. */
        const val SERVICE_TYPE = "_warungpos._tcp."
        const val SERVICE_NAME = "WarungPOS"
        val JSON_CT = io.ktor.http.ContentType.Application.Json
    }
}
