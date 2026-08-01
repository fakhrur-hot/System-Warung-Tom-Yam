package com.razstudio.pos.data.lan

import android.util.Log
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.NewOrderItem
import com.razstudio.pos.data.VoidLine
import com.razstudio.pos.data.local.LocalBackend
import com.razstudio.pos.data.local.PairedDeviceDao
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) {

    private var nsdRegistration: android.net.nsd.NsdManager.RegistrationListener? = null

    @Volatile
    private var engine: io.ktor.server.engine.ApplicationEngine? = null

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
    fun start(): Boolean {
        if (engine != null) return true

        val address = lanAddress.resolve()
        if (address !is LanAddress.Result.Found) {
            Log.w(TAG, "Not starting: ${(address as LanAddress.Result.Unavailable).reason}")
            return false
        }

        return try {
            engine = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
                // No ContentNegotiation plugin: every response here is built with org.json and
                // written via respondText, because the shapes must match the Edge Functions
                // byte-for-byte and a serializer would impose its own. See LanJson.
                routes()
            }.also { it.start(wait = false) }
            boundHost = address.ip
            advertiseOverMdns()
            Log.i(TAG, "Listening on ${address.ip}:$PORT (${address.interfaceName})")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start", t)
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
        val nsd = context.getSystemService(android.content.Context.NSD_SERVICE)
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
        val nsd = context.getSystemService(android.content.Context.NSD_SERVICE)
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
    private suspend fun io.ktor.server.application.ApplicationCall.authorizedDeviceOrNull(): Boolean {
        val bearer = request.headers["Authorization"]
            ?.removePrefix("Bearer ")
            ?.trim()
            .orEmpty()
        if (bearer.isEmpty()) return false

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
            )
        }
    }

    private companion object {
        const val TAG = "LanServer"

        /** Must match `LocalBackend`'s LAN_PORT — the pairing QR carries it. */
        const val PORT = 8765

        /** `ApiClient.baseUrl()` appends this; the routes have to live under it. */
        const val PREFIX = "/functions/v1"

        const val STATUS_APPROVED = "APPROVED"

        /** Must match LanServerLocator's SERVICE_TYPE — the two halves of discovery. */
        const val SERVICE_TYPE = "_warungpos._tcp."
        const val SERVICE_NAME = "WarungPOS"
        val JSON_CT = io.ktor.http.ContentType.Application.Json
    }
}
