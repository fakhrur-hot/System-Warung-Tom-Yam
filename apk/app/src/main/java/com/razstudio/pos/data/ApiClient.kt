package com.razstudio.pos.data

import com.razstudio.pos.BuildConfig
import com.razstudio.pos.data.demo.DemoSession
import com.razstudio.pos.data.json.OrderMapper
import com.razstudio.pos.data.json.optStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP client for communicating with Supabase Edge Functions.
 * Handles admin handshake, device registration, status polling,
 * session lifecycle, aggregates, and menu operations.
 *
 * A 401 response from any authenticated endpoint clears the stale session token
 * and emits [AuthEventBus.AuthEvent.SessionExpired] so the UI can redirect to
 * re-authentication without needing per-call handling.
 */
@Singleton
class ApiClient @Inject constructor(
    private val secureStorage: SecureStorage,
    private val authEventBus: AuthEventBus,
    private val demoBackend: com.razstudio.pos.data.demo.DemoBackend,
    private val appConfig: AppConfigStore,
    private val noInternetGuard: com.razstudio.pos.data.net.NoInternetGuard,
    private val modeRepository: ModeRepository,
    private val lanPushBus: com.razstudio.pos.data.lan.LanPushBus,
) : BackendGateway {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Query params carrying the café's backend in the Owner Recovery QR. Shared with
         * `AdminConnectScreen`, which reads them back — the two must not drift, so they live here
         * rather than being spelled out at each end.
         */
        const val QR_PARAM_API = "api"
        const val QR_PARAM_KEY = "key"

        /**
         * Turn an unmapped HTTP status into an error a café owner can act on (task 8.2).
         *
         * This replaced `unexpectedStatus(response.code)` at 39 call
         * sites. That fallback was on **every** endpoint, so any status nobody had thought about
         * reached the counter as a bare number — which is precisely how the staff-join defect was
         * reported: "error 404 straight after scanning". The number was true and told nobody
         * anything, and it hid a device-id bug for as long as it took to read the source.
         *
         * The status is still named, because support conversations need it, but it is never the
         * whole message: every branch says what happened and what to do next. Requirement 8.2 forbids
         * a raw status *as the message*, not the mention of one.
         */
        fun unexpectedStatus(code: Int): ApiResult.Error = when (code) {
            404 -> ApiResult.Error(
                "NOT_FOUND",
                "The café's server didn't recognise that request (404). This device may be pointed " +
                    "at the wrong address, or the café's backend needs updating.",
            )
            408, 504 -> ApiResult.Error(
                "TIMEOUT",
                "The café's server took too long to answer ($code). Check the connection and try again.",
            )
            429 -> ApiResult.Error(
                "RATE_LIMITED",
                "Too many requests too quickly (429). Wait a moment and try again.",
            )
            502, 503 -> ApiResult.Error(
                "UNAVAILABLE",
                "The café's server is temporarily unavailable ($code). Try again shortly.",
            )
            in 500..599 -> ApiResult.Error(
                "SERVER_ERROR",
                "Something went wrong on the café's server ($code). Try again; if it keeps " +
                    "happening the café's backend needs attention.",
            )
            in 400..499 -> ApiResult.Error(
                "REJECTED",
                "The café's server refused that request ($code). This device may need to be " +
                    "re-registered, or the app may be out of date.",
            )
            else -> ApiResult.Error(
                "UNEXPECTED",
                "Unexpected reply from the café's server ($code). Try again.",
            )
        }
    }

    /**
     * No backend has been configured on this device, so there is nothing to call.
     *
     * An [IOException] on purpose: every request path in this class already funnels `IOException`
     * into [ApiResult.NetworkError], so this reaches the UI as a handled failure rather than as
     * whatever raw exception text happened to be thrown deeper down.
     */
    class BackendNotConfiguredException : IOException(
        "No backend configured on this device — run Setup, or pair with a LAN Server.",
    )

    // Supabase config is resolved at RUNTIME so one template APK can serve any café: prefer the
    // in-app Setup value, fall back to a compile-time BuildConfig value (set for café-specific
    // builds via local.properties). The Functions base is derived from whichever URL wins.
    private fun supabaseUrl(): String =
        appConfig.supabaseUrl().ifBlank { BuildConfig.SUPABASE_URL }

    /**
     * Where this device's API calls go.
     *
     * A LAN Client stores its Server's address (task 7.2) and it wins, because on such a device the
     * Supabase URL is blank by construction — task 9.3 clears it on the mode switch — and falling
     * through to `BuildConfig.SUPABASE_URL` would send a staff phone's orders to whatever cloud
     * project the APK happened to be built against. Empty on the Server Device and in Cloud Mode,
     * so this changes nothing for either.
     *
     * `/functions/v1` is appended in both cases; `LanServer` serves the same prefix precisely so
     * this one line is the only difference between the two topologies.
     */
    private fun baseUrl(): String {
        // The LAN address only wins in LAN Mode. Keying purely on "is lan_server_url set?" — as an
        // earlier revision did — lets a stale value from a past pairing hijack a Cloud café's API
        // calls: every request, including the owner-key recovery that has to reach Supabase, would
        // be sent to a phone on the counter that no longer serves it. The mode is the fact; the
        // stored URL is only a detail of one mode.
        val lan = appConfig.lanServerUrl()
        val useLan = modeRepository.currentMode() == OperatingMode.LAN && lan.isNotBlank()

        // A LAN *host* has no base URL at all, and must not borrow one.
        //
        // Falling through to supabaseUrl() here meant falling through to BuildConfig.SUPABASE_URL —
        // the address baked into the build — so a host with no cloud café would quietly aim its
        // requests at somebody else's Supabase project. NoInternetGuard then refused to resolve it
        // (correctly: LAN Mode has no internet), the IOException came back as a network failure, and
        // the owner was told their admin device was unreachable. It was reachable. It was the device
        // showing the message.
        //
        // Nothing should reach this in the first place — BackendModule hands a host LocalBackend —
        // so throwing keeps a genuine wiring mistake loud instead of dressing it as a network blip.
        if (modeRepository.isLanHost()) throw BackendNotConfiguredException()

        val base = if (useLan) lan else supabaseUrl()

        // A blank base used to sail straight through to `Request.Builder().url("/functions/v1/…")`,
        // where OkHttp threw IllegalArgumentException("Expected URL scheme 'http' or 'https' but no
        // scheme was found for /funct…"). The generic `catch (e: Exception)` below then handed that
        // string to the café owner verbatim. It is not a wrong URL — there is no URL at all, because
        // this device has never been pointed at a backend: the template APK ships with an empty
        // BuildConfig.SUPABASE_URL, and Setup was either skipped or never completed.
        //
        // Failing here, with a typed exception, keeps that diagnosis intact all the way to the UI.
        // BackendNotConfiguredException is an IOException so every existing call site's
        // `catch (e: IOException)` already maps it to ApiResult.NetworkError — no new catch blocks,
        // and no path can regress to the OkHttp message.
        if (base.isBlank()) throw BackendNotConfiguredException()

        return base.trimEnd('/') + "/functions/v1"
    }

    /**
     * Is this device pointed at a backend at all?
     *
     * For screens that must not start a flow they cannot finish. The admin sign-in screen is the
     * one that matters: a café owner scanning the owner key on an unconfigured build deserves
     * "this app isn't connected to a café yet", not a failed round trip. Mirrors [baseUrl]'s rule
     * exactly, so the two cannot drift.
     */
    fun isBackendConfigured(): Boolean {
        // A host is not "unconfigured" — it is the backend. Reporting false here would send an
        // owner to Setup for a café that is already running on the device in front of them.
        if (modeRepository.isLanHost()) return true
        val lan = appConfig.lanServerUrl()
        if (modeRepository.currentMode() == OperatingMode.LAN && lan.isNotBlank()) return true
        return supabaseUrl().isNotBlank()
    }

    /**
     * Tell staff devices on the café LAN that something changed, so they can pull it now instead of
     * waiting for their next poll tick.
     *
     * ### A trigger, never a payload
     *
     * The frame carries an order id and nothing else that matters. Receivers re-fetch through their
     * own catch-up sync — the same function their poll runs, with the same de-duplication. That rule
     * is what keeps exactly one code path into kitchen printing; applying deltas straight from a
     * frame would create a second one that de-duplicates against different data, and the way that
     * surfaces is a slip printing twice mid-service.
     *
     * ### Why here and not in the ViewModels
     *
     * This is the choke point every cloud mutation already passes through, so a new order path
     * cannot forget to announce itself. It mirrors what LocalBackend does off-cloud.
     *
     * Fire-and-forget by construction: [LanPushBus.publish] never suspends and never throws, so a
     * dead socket cannot fail a payment.
     */
    private fun announceOrderChange(orderId: String?, what: String) {
        if (modeRepository.currentMode() != OperatingMode.CLOUD) return
        lanPushBus.publish(
            JSONObject().put("kind", what).apply { if (orderId != null) put("orderId", orderId) },
            java.time.Instant.now().toString(),
        )
    }

    private fun anonKey(): String =
        appConfig.supabaseAnonKey().ifBlank { BuildConfig.SUPABASE_ANON_KEY }

    /**
     * Append this café's backend address to the Owner Recovery URL, so the QR is self-sufficient.
     *
     * Without this the QR carries a recovery token and nothing else — and a token names no café. A
     * device that has never been set up therefore cannot act on it, which is why an owner holding a
     * perfectly good key was still being sent through the Setup Wizard to type in a project URL and
     * anon key by hand. Carrying both in the QR makes the wizard optional whenever the owner has
     * their QR: scan, adopt, sign in.
     *
     * The anon key is safe to publish here — it is a public client key, already compiled into every
     * café-specific APK and sent as the `apikey` header on unauthenticated calls. What guards the
     * café is the recovery token beside it and the row-level policies behind it.
     *
     * Added as extra query params on the existing link rather than as a new payload format, so the
     * URL still opens the join page in a browser and `extractRecoverToken`'s `recover=` match is
     * untouched. Older QRs simply lack the params and keep working exactly as before.
     *
     * Applied to **invite** links as well as the owner key. A staff phone joining a café is in the
     * same position the owner was: a token names no café, so on a template APK the scan had nowhere
     * to go. Joining is how a device becomes configured, so it cannot itself require configuration.
     */
    private fun withBackendDetails(url: String): String {
        val base = supabaseUrl()
        val key = anonKey()
        if (base.isBlank() || key.isBlank()) return url
        if (url.contains("$QR_PARAM_API=")) return url
        val sep = if (url.contains('?')) '&' else '?'
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        return "$url$sep$QR_PARAM_API=${enc(base)}&$QR_PARAM_KEY=${enc(key)}"
    }

    // Task 18.1: guarded first, so no later builder call can be added "above" it.
    private val client = OkHttpClient.Builder()
        .dns(noInternetGuard)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // 401 on any authenticated admin call = expired/revoked session token.
            // Clear the stale token and broadcast so the UI can route to re-auth.
            if (response.code == 401) {
                val authHeader = chain.request().header("Authorization") ?: ""
                // Only fire for requests carrying the admin Bearer token, not the
                // anon-key-only endpoints (handshake, device status polling, etc.)
                val role = secureStorage.getRole()
                if (authHeader.startsWith("Bearer ") &&
                    (role == SecureStorage.Role.ADMIN || role == SecureStorage.Role.ADMIN_SECONDARY)
                ) {
                    secureStorage.clearSessionToken()
                    authEventBus.emitSessionExpired()
                }
            }
            response
        }
        .build()

    /**
     * The same client **without** [NoInternetGuard], for the calls that *establish* a connection.
     *
     * Found on a device: a café stored as LAN or Kiosk could not sign in with its owner key. The
     * guard reads the persisted mode, so `recoverAdmin` was refused before it left the phone —
     *
     *     BLOCKED: <project>.supabase.co … which is off-LAN, and this device is in KIOSK
     *
     * — and the screen said "Network error. Check your connection and try again", which is wrong on
     * both counts. The same block silently emptied the debug quick-connect list, because
     * `getBranding` never completed.
     *
     * That state is not exotic. It is every device mid-transition: a Kiosk or LAN till that now
     * needs to become a Cloud till, or an admin taking over a device that was set up off-cloud. The
     * owner QR is the café's only key, and Property 9 says it must remain sufficient throughout — a
     * guard that makes it fail in a recoverable state defeats the one credential the owner keeps.
     *
     * ### Why this does not weaken Property 3
     *
     * The property is "no internet traffic **originates** in LAN or Kiosk Mode", and it exists to
     * stop traffic nobody asked for: background sync, leftover `https://` image URLs, telemetry. The
     * calls below are the opposite — each is the direct result of someone tapping Scan, Sign in, or
     * Register on a connect screen, and each exists to put the device into a known topology.
     *
     * Note also that a *genuinely* off-cloud café cannot reach these at all: with no Supabase URL
     * stored, [baseUrl] throws [BackendNotConfiguredException] first. Reaching this client requires a
     * cloud URL to be configured while the mode says otherwise — which is precisely the contradiction
     * an operator is here to resolve.
     *
     * Everything operational — orders, menu, settings, images, realtime — stays on the guarded
     * [client].
     */
    private val connectClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()


    /**
     * Turn a connection failure into something a café can act on (task 14.1, Requirement 4.5).
     *
     * `e.message` from OkHttp is a stack-flavoured string — "Failed to connect to /192.168.43.1:8765"
     * or a bare `SocketTimeoutException` — and it was going straight to the screen at 40 call sites.
     * In Cloud Mode that is merely unhelpful. In LAN Mode it is actively misleading: the operator
     * reads "connection failed" and checks their internet, when the thing that is unreachable is the
     * admin phone sitting on the counter, and the fix is to wake it or re-join its hotspot.
     *
     * The two are told apart by where the request was going, not by the exception: a private-range
     * base URL means the peer is the Server Device.
     */
    private fun networkError(e: IOException): ApiResult.NetworkError {
        val base = runCatching { baseUrl() }.getOrDefault("")
        val host = runCatching { java.net.URI(base).host.orEmpty() }.getOrDefault("")
        val isLocalPeer = host.startsWith("192.168.") || host.startsWith("10.") ||
            host.startsWith("127.") || Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host)

        return if (isLocalPeer || modeRepository.currentMode() == OperatingMode.LAN) {
            ApiResult.NetworkError(
                "Can't reach the admin device${if (host.isNotBlank()) " at $host" else ""}. " +
                    "Check it is switched on, its hotspot is running, and this phone is joined to it."
            )
        } else {
            ApiResult.NetworkError(
                "Couldn't reach the café's server. Check this device's internet connection and try again."
            )
        }
    }

    private fun adminBearerToken(): String? = secureStorage.getSessionToken()

    /**
     * Log enough to diagnose a failed join from a device in the field (task 8.5).
     *
     * The staff-join defect was reported as "error 404 straight after scanning" and took a source
     * read to explain, because nothing recorded *which URL* answered 404 or *which id* was sent. The
     * two facts that would have settled it in seconds are the resolved base URL and the id — a
     * client UUID where a server row key was required.
     *
     * Logged at WARN so `adb logcat` picks it up on a release build without a debug flag. No
     * credential is logged: the base URL is a public address, the device id is not a secret, and the
     * bearer token is never touched.
     */
    private fun logJoinFailure(endpoint: String, code: Int, deviceId: String) {
        android.util.Log.w(
            "ApiClient",
            "join failed: $endpoint -> HTTP $code | base=${baseUrl()} | deviceId=$deviceId | " +
                "mode=${modeRepository.currentMode()}",
        )
    }

    /** Consume and discard a response body so OkHttp can reuse the connection. */
    private fun Response.consumeBody(): String = use { body?.string() ?: "" }


    /**
     * Debug-only admin handshake: claims the admin slot using the café's plaintext
     * name instead of the rotating key. Only ever wired up from a `BuildConfig.DEBUG`
     * gated UI path — the backend independently requires its `ALLOW_DEBUG_ADMIN`
     * deployment secret to be set, so this is a no-op (401) against any deployment
     * that hasn't explicitly opted in, release or otherwise.
     */
    override suspend fun adminHandshakeDebug(deviceId: String, cafeName: String): ApiResult<String> =
        adminHandshakeRequest(deviceId) { put("debugCafeName", cafeName) }

    private suspend fun adminHandshakeRequest(
        deviceId: String,
        extra: JSONObject.() -> Unit
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                extra()
            }.toString()

            val request = Request.Builder()
                .url("${baseUrl()}/admin-handshake")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", anonKey())
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = connectClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    val token = json.getString("sessionToken")
                    ApiResult.Success(token)
                }
                409 -> ApiResult.Error("ADMIN_EXISTS", "An admin device is already registered")
                401 -> ApiResult.Error("INVALID_KEY", "Invalid or expired rotating key")
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Register an ordering device with an invitation token.
     * @return [ApiResult] with device status on success.
     */
    override suspend fun register(
        inviteToken: String,
        deviceId: String,
        deviceModel: String,
        androidId: String,
        appVersion: String
    ): ApiResult<RegisterResponse> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("inviteToken", inviteToken)
                put("deviceId", deviceId)
                put("deviceModel", deviceModel)
                put("androidId", androidId)
                put("appVersion", appVersion)
            }.toString()

            val request = Request.Builder()
                .url("${baseUrl()}/register")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", anonKey())
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = connectClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                201 -> {
                    val json = JSONObject(responseBody)
                    ApiResult.Success(
                        RegisterResponse(
                            deviceId = json.getString("deviceId"),
                            status = json.getString("status")
                        )
                    )
                }
                403 -> {
                    logJoinFailure("register", response.code, deviceId)
                    ApiResult.Error("INVALID_INVITE", "Invalid or expired invitation")
                }
                else -> {
                    logJoinFailure("register", response.code, deviceId)
                    unexpectedStatus(response.code)
                }
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Poll device approval status.
     * @return [ApiResult] with device status (PENDING/APPROVED/REVOKED) and optional apiKey.
     */
    override suspend fun pollDeviceStatus(deviceId: String): ApiResult<DeviceStatusResponse> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${baseUrl()}/devices-status?deviceId=$deviceId")
                    .addHeader("apikey", anonKey())
                    .get()
                    .build()

                val response = connectClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        ApiResult.Success(
                            DeviceStatusResponse(
                                status = json.getString("status"),
                                role = json.optStringOrNull("role"),
                                apiKey = json.optStringOrNull("apiKey"),
                                sessionToken = json.optStringOrNull("sessionToken")
                            )
                        )
                    }
                    else -> {
                        // The exact call that produced the reported "404 straight after scanning".
                        // Logging the id beside the status is what makes the two-device-ids
                        // conflation visible from a field device rather than only from the source.
                        logJoinFailure("devices-status", response.code, deviceId)
                        unexpectedStatus(response.code)
                    }
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    // --- Session lifecycle ---

    /**
     * Post a session event (OPEN/CLOSE) with optional reason and closing flag.
     * Backend broadcasts CAFE_OPEN or CAFE_CLOSED accordingly.
     */
    override suspend fun postSession(
        event: String,
        reason: String?,
        closing: Boolean
    ): ApiResult<SessionResponse> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.postSession(event)
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val body = JSONObject().apply {
                put("event", event)
                if (reason != null) put("reason", reason)
                if (closing) put("closing", true)
            }.toString()

            val request = Request.Builder()
                .url("${baseUrl()}/sessions")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    ApiResult.Success(
                        SessionResponse(
                            sessionId = json.getString("sessionId"),
                            event = json.getString("event"),
                            timestamp = json.getString("timestamp")
                        )
                    )
                }
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Push daily aggregate summary at closing time.
     */
    override suspend fun postAggregates(date: String, body: JSONObject): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext ApiResult.Success(Unit)
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                body.put("date", date)

                val request = Request.Builder()
                    .url("${baseUrl()}/aggregates")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                response.consumeBody()

                when (response.code) {
                    200 -> ApiResult.Success(Unit)
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * GET /reports/closing — build today's closing report and return a signed URL to it.
     *
     * 409 means the day has no aggregate row yet, which is not an error worth surfacing as one:
     * it is what a café that closed without taking an order looks like. Reported as a distinct
     * code so the caller can stay silent rather than telling an owner something failed.
     */
    override suspend fun getClosingReport(): ApiResult<ClosingReportRef> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) {
                return@withContext ApiResult.Error("UNSUPPORTED", "Not available in demo mode")
            }
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val request = Request.Builder()
                    .url("${baseUrl()}/reports-closing")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(body)
                        val url = json.optString("reportUrl", "")
                        if (url.isBlank()) {
                            ApiResult.Error("NO_REPORT", "Report was generated without a URL")
                        } else {
                            ApiResult.Success(
                                ClosingReportRef(url = url, date = json.optString("date", "")),
                            )
                        }
                    }
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                    409 -> ApiResult.Error("NO_AGGREGATE", "No takings recorded for this day")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Push full menu snapshot (availability updates, daily popup changes).
     */
    override suspend fun putMenu(menuItems: JSONArray, categories: JSONArray): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext ApiResult.Success(Unit)
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val body = JSONObject().apply {
                    put("items", menuItems)
                    put("categories", categories)
                }.toString()

                val request = Request.Builder()
                    .url("${baseUrl()}/menu")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .put(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                response.consumeBody() // ensure connection is reused

                when (response.code) {
                    200 -> ApiResult.Success(Unit)
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    // --- Order Management ---

    /**
     * Catch-up sync: fetch all orders since a given timestamp.
     * Called on every WebSocket (re)connect to reconcile with Room.
     */
    override suspend fun getOrdersSince(since: String): ApiResult<OrdersSyncResponse> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.getOrdersSince()
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val request = Request.Builder()
                    .url("${baseUrl()}/orders?since=$since")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        val serverTime = json.getString("serverTime")
                        val ordersArray = json.getJSONArray("orders")
                        val orders = mutableListOf<OrderDto>()
                        for (i in 0 until ordersArray.length()) {
                            orders.add(parseOrderDto(ordersArray.getJSONObject(i)))
                        }
                        ApiResult.Success(OrdersSyncResponse(orders = orders, serverTime = serverTime))
                    }
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Send order to kitchen — marks unsent items as sent, returns delta lines to print.
     * When [sessionNumber] is non-null, scopes the operation to just that session's
     * items (B4.3: confirm a single pending round without reprinting everything).
     */
    override suspend fun sendToKitchen(orderId: String, sessionNumber: Int?): ApiResult<KitchenResponse> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.sendToKitchen(orderId)
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val bodyJson = if (sessionNumber != null) {
                    JSONObject().apply { put("sessionNumber", sessionNumber) }.toString()
                } else {
                    "{}"
                }

                val request = Request.Builder()
                    .url("${baseUrl()}/orders-kitchen/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        val order = parseOrderDto(json.getJSONObject("order"))
                        val linesArray = json.getJSONArray("linesToPrint")
                        val lines = mutableListOf<OrderItemDto>()
                        for (i in 0 until linesArray.length()) {
                            lines.add(parseOrderItemDto(linesArray.getJSONObject(i)))
                        }
                        ApiResult.Success(KitchenResponse(order = order, linesToPrint = lines))
                    }
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }
            // Nudge staff devices to pull now instead of waiting for their poll tick.
            .also { r -> if (r is ApiResult.Success) announceOrderChange(orderId, "kitchen") }

    /**
     * Add items to an existing order (amendment).
     */
    override suspend fun addItemsToOrder(orderId: String, items: List<NewOrderItem>): ApiResult<OrderDto> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.addItemsToOrder(orderId, items)
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val itemsArray = JSONArray()
                items.forEach { item ->
                    itemsArray.put(JSONObject().apply {
                        put("menuItemId", item.menuItemId)
                        put("quantity", item.quantity)
                        if (item.note != null) put("note", item.note)
                        if (item.unitPrice != null) put("unitPrice", item.unitPrice)
                        if (item.size != null) put("size", item.size)
                        if (item.customName != null) put("customName", item.customName)
                    })
                }
                val body = JSONObject().apply {
                    put("items", itemsArray)
                }.toString()

                val request = Request.Builder()
                    .url("${baseUrl()}/orders-items/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        ApiResult.Success(parseOrderDto(json))
                    }
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                    422 -> ApiResult.Error("VALIDATION", "Item unavailable or unknown")
                    409 -> ApiResult.Error("SESSION_LIMIT", "This table has reached the maximum order rounds — pay out and free it first")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }
            // Nudge staff devices to pull now instead of waiting for their poll tick.
            .also { r -> if (r is ApiResult.Success) announceOrderChange(orderId, "items") }


    /**
     * Void lines on an active order (admin bearer). See [BackendGateway.voidOrderItems].
     */
    override suspend fun voidOrderItems(
        orderId: String,
        lines: List<VoidLine>,
        reason: String
    ): ApiResult<OrderDto> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.voidOrderItems(orderId, lines, reason)
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")
                voidRequest(orderId, lines, reason, token)
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }
            // Nudge staff devices to pull now instead of waiting for their poll tick.
            .also { r -> if (r is ApiResult.Success) announceOrderChange(orderId, "void") }

    /**
     * Void lines on an active order using the ordering API key (staff).
     */
    override suspend fun voidOrderItemsAsStaff(
        orderId: String,
        lines: List<VoidLine>,
        reason: String
    ): ApiResult<OrderDto> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.voidOrderItems(orderId, lines, reason)
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")
                voidRequest(orderId, lines, reason, token)
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * The shared body of both void calls — identical apart from which credential is presented, so
     * the error mapping (which is the part worth getting right) exists once.
     */
    private fun voidRequest(
        orderId: String,
        lines: List<VoidLine>,
        reason: String,
        bearer: String
    ): ApiResult<OrderDto> {
        val linesArray = JSONArray()
        lines.forEach { line ->
            linesArray.put(JSONObject().apply {
                put("id", line.itemId)
                put("quantity", line.keepQuantity)
            })
        }
        val body = JSONObject().apply {
            put("lines", linesArray)
            put("reason", reason)
        }.toString()

        val request = Request.Builder()
            .url("${baseUrl()}/orders-items-void/$orderId")
            .addHeader("Content-Type", "application/json")
            .addHeader("apikey", anonKey())
            .addHeader("Authorization", "Bearer $bearer")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        return when (response.code) {
            200 -> ApiResult.Success(parseOrderDto(JSONObject(responseBody)))
            401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired credentials")
            404 -> ApiResult.Error("NOT_FOUND", "Order not found")
            // 409s are all states the cashier can resolve, so each keeps its own code for the UI to
            // turn into a specific message rather than a generic failure.
            409 -> when {
                responseBody.contains("WOULD_EMPTY_ORDER") -> ApiResult.Error("WOULD_EMPTY_ORDER", "")
                responseBody.contains("ALREADY_VOIDED") -> ApiResult.Error("ALREADY_VOIDED", "")
                else -> ApiResult.Error("ORDER_CLOSED", "")
            }
            422 -> if (responseBody.contains("CANNOT_INCREASE")) {
                ApiResult.Error("CANNOT_INCREASE", "")
            } else {
                ApiResult.Error("VALIDATION", "No lines selected")
            }
            else -> unexpectedStatus(response.code)
        }
    }

    /**
     * Update order status (PREPARING/READY).
     */
    override suspend fun updateOrderStatus(orderId: String, status: String): ApiResult<OrderDto> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.updateOrderStatus(orderId, status)
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val body = JSONObject().apply {
                    put("status", status)
                }.toString()

                val request = Request.Builder()
                    .url("${baseUrl()}/orders-status/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .put(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        ApiResult.Success(parseOrderDto(json))
                    }
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }
            // Nudge staff devices to pull now instead of waiting for their poll tick.
            .also { r -> if (r is ApiResult.Success) announceOrderChange(orderId, "status") }

    /**
     * Process payment (Cash/QR). Only valid after SENT_TO_KITCHEN.
     */
    override suspend fun processPayment(orderId: String, method: String): ApiResult<OrderDto> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.processPayment(orderId, method)
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val body = JSONObject().apply {
                    put("method", method)
                }.toString()

                val request = Request.Builder()
                    .url("${baseUrl()}/orders-payment/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        ApiResult.Success(parseOrderDto(json))
                    }
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                    409 -> ApiResult.Error("NOT_SENT_TO_KITCHEN", "Order not yet sent to kitchen")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }
            // Nudge staff devices to pull now instead of waiting for their poll tick.
            .also { r -> if (r is ApiResult.Success) announceOrderChange(orderId, "payment") }

    /**
     * Cancel an order with reason and who cancelled it.
     */
    override suspend fun cancelOrder(orderId: String, reason: String, cancelledBy: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.cancelOrder(orderId, reason, cancelledBy)
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val body = JSONObject().apply {
                    put("reason", reason)
                    put("cancelledBy", cancelledBy)
                }.toString()

                val request = Request.Builder()
                    .url("${baseUrl()}/orders-cancel/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .delete(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                response.consumeBody()

                when (response.code) {
                    200 -> ApiResult.Success(Unit)
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                    403 -> ApiResult.Error("CANCEL_NOT_ALLOWED", "Cannot cancel this order")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    // --- JSON Parsing helpers for Orders ---
    // Delegated to the single canonical OrderMapper so REST sync, Realtime
    // broadcasts, and the ordering service all share one field contract.

    private fun parseOrderDto(json: JSONObject): OrderDto = OrderMapper.orderDto(json)

    private fun parseOrderItemDto(json: JSONObject): OrderItemDto = OrderMapper.orderItemDto(json)

    // --- Device Management ---

    /**
     * Get all registered devices (admin bearer).
     */
    override suspend fun getDevices(): ApiResult<List<DeviceDto>> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.getDevices()
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val request = Request.Builder()
                .url("${baseUrl()}/devices")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val jsonArray = JSONArray(responseBody)
                    val devices = mutableListOf<DeviceDto>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        devices.add(
                            DeviceDto(
                                id = obj.getString("id"),
                                deviceIdentifier = obj.optString("deviceIdentifier", ""),
                                label = obj.optString("label", ""),
                                role = obj.optString("role", ""),
                                status = obj.getString("status"),
                                lastSeenAt = obj.optStringOrNull("lastSeenAt"),
                                isCheckedIn = obj.optBoolean("isCheckedIn", false)
                            )
                        )
                    }
                    ApiResult.Success(devices)
                }
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Patch a device: approve, reject, revoke, force check-out, or rename.
     */
    override suspend fun patchDevice(
        deviceId: String,
        action: String,
        label: String?
    ): ApiResult<DeviceDto> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext ApiResult.Success(
            DeviceDto(
                id = deviceId, deviceIdentifier = deviceId, label = label ?: "Demo device",
                role = "ORDERING", status = "ACTIVE", lastSeenAt = null, isCheckedIn = true
            )
        )
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val body = JSONObject().apply {
                put("action", action)
                if (label != null) put("label", label)
            }.toString()

            val request = Request.Builder()
                .url("${baseUrl()}/devices/$deviceId")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .patch(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val obj = JSONObject(responseBody)
                    ApiResult.Success(
                        DeviceDto(
                            id = obj.getString("id"),
                            deviceIdentifier = obj.optString("deviceIdentifier", ""),
                            label = obj.optString("label", ""),
                            role = obj.optString("role", ""),
                            status = obj.getString("status"),
                            lastSeenAt = obj.optStringOrNull("lastSeenAt"),
                            isCheckedIn = obj.optBoolean("isCheckedIn", false)
                        )
                    )
                }
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // --- Invitations ---

    /**
     * Get the current staff invitation URL (admin bearer).
     */
    override suspend fun getInvite(role: String?): ApiResult<InviteResponse> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.getInvite()
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val url = if (role != null) "${baseUrl()}/invite?role=$role" else "${baseUrl()}/invite"
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    ApiResult.Success(
                        InviteResponse(
                            token = json.getString("token"),
                            // Carries the café's backend, exactly as the owner recovery QR does.
                            // Without it a staff phone on a template APK scans a valid invite and
                            // has nowhere to send it — the token names no café.
                            url = withBackendDetails(json.getString("url")),
                        )
                    )
                }
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Regenerate the staff invitation token (admin bearer).
     */
    override suspend fun regenerateInvite(role: String?): ApiResult<InviteResponse> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.getInvite()
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val url = if (role != null) "${baseUrl()}/invite/regenerate?role=$role" else "${baseUrl()}/invite/regenerate"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    ApiResult.Success(
                        InviteResponse(
                            token = json.getString("token"),
                            // Carries the café's backend, exactly as the owner recovery QR does.
                            // Without it a staff phone on a template APK scans a valid invite and
                            // has nowhere to send it — the token names no café.
                            url = withBackendDetails(json.getString("url")),
                        )
                    )
                }
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // --- Ordering-role API methods ---

    private fun orderingBearerToken(): String? = secureStorage.getApiKey()

    /**
     * Determine which auth token to use based on the stored role.
     * Admin devices use the session token; ordering devices use the API key.
     */
    private fun getAuthToken(): String? {
        val role = secureStorage.getRole()
        return when (role) {
            SecureStorage.Role.ADMIN,
            SecureStorage.Role.ADMIN_SECONDARY -> adminBearerToken()
            SecureStorage.Role.ORDERING -> orderingBearerToken()
            null -> adminBearerToken() ?: orderingBearerToken()
        }
    }

    /** Fetch the permanent owner-recovery token + QR url (admin only) to show the owner. */
    override suspend fun getRecoveryToken(): ApiResult<InviteResponse> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.getInvite()
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")
            val request = Request.Builder()
                .url("${baseUrl()}/admin-recovery")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .get().build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    ApiResult.Success(
                        InviteResponse(
                            token = json.getString("token"),
                            url = withBackendDetails(json.getString("url")),
                        )
                    )
                }
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                // The key is stored hashed and can never be read back — not by the owner, not by
                // this endpoint. The only way forward is minting a new one; the caller offers that.
                409 -> ApiResult.Error(
                    "KEY_NOT_READABLE",
                    "This café's owner key is stored securely and cannot be shown again."
                )
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Mint a NEW owner key (admin only). The old key stops working the moment this returns; the
     * plaintext in the response is the only copy that will ever exist, so the caller must put it
     * in front of the owner (QR + saved PNG) immediately.
     */
    override suspend fun regenerateRecoveryToken(): ApiResult<InviteResponse> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.getInvite()
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")
            val request = Request.Builder()
                .url("${baseUrl()}/admin-recovery/regenerate")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    ApiResult.Success(
                        InviteResponse(
                            token = json.getString("token"),
                            url = withBackendDetails(json.getString("url")),
                        )
                    )
                }
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /** Recover Main Admin on this device using the permanent owner-recovery token (public). */
    override suspend fun recoverAdmin(
        recoveryToken: String,
        deviceId: String,
        deviceModel: String
    ): ApiResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val bodyJson = JSONObject().apply {
                    put("recoveryToken", recoveryToken)
                    put("deviceId", deviceId)
                    put("deviceModel", deviceModel)
                }
                val request = Request.Builder()
                    .url("${baseUrl()}/admin-recovery")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", anonKey())
                    .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val response = connectClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        // `admin-recovery` matches by `device_identifier`, but every later
                        // device-scoped call (`devices-status`, `devices`, `attendance`) looks up
                        // `devices.id` — see SecureStorage.getServerDeviceId. Stored here, at the
                        // one point both sign-in screens share, or the very next status poll 404s
                        // and the fresh session is torn down.
                        json.optStringOrNull("deviceId")?.let { secureStorage.setServerDeviceId(it) }
                        ApiResult.Success(json.getString("sessionToken"))
                    }
                    403 -> ApiResult.Error("INVALID_RECOVERY", "Invalid recovery key")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Create a new order using the ordering API key (staff device).
     * Staff devices submit orders with source=STAFF via their API key.
     */
    override suspend fun createOrderAsStaff(
        tableId: String,
        items: List<NewOrderItem>,
        splitShare: Boolean,
    ): ApiResult<CreateOrderResponse> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.createOrder(tableId, items, "STAFF")
        try {
            val token = orderingBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")

            val itemsArray = JSONArray()
            items.forEach { item ->
                itemsArray.put(JSONObject().apply {
                    put("menuItemId", item.menuItemId)
                    put("quantity", item.quantity)
                    if (item.note != null) put("note", item.note)
                    if (item.unitPrice != null) put("unitPrice", item.unitPrice)
                    if (item.size != null) put("size", item.size)
                    if (item.customName != null) put("customName", item.customName)
                })
            }
            val body = JSONObject().apply {
                put("tableId", tableId)
                put("items", itemsArray)
                if (splitShare) put("splitShare", true)
            }.toString()

            val request = Request.Builder()
                .url("${baseUrl()}/orders")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .addHeader("X-Order-Source", "STAFF")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                201 -> {
                    val json = JSONObject(responseBody)
                    ApiResult.Success(
                        CreateOrderResponse(
                            orderId = json.getString("orderId"),
                            total = json.getDouble("total"),
                            status = json.getString("status")
                        )
                    )
                }
                409 -> ApiResult.Error("TABLE_OCCUPIED", "Table already has an active session")
                422 -> {
                    val json = JSONObject(responseBody)
                    ApiResult.Error(
                        json.optString("error", "VALIDATION"),
                        json.optString("message", "Validation error")
                    )
                }
                429 -> ApiResult.Error("RATE_LIMITED", "Too many requests")
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired API key")
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Send order to kitchen using ordering API key (staff with SEND_TO_KITCHEN permission).
     * When [sessionNumber] is non-null, scopes to just that session's items (B4.3 confirm),
     * mirroring the admin [sendToKitchen] variant.
     */
    override suspend fun sendToKitchenAsStaff(
        orderId: String,
        sessionNumber: Int?
    ): ApiResult<KitchenResponse> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.sendToKitchen(orderId)
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")

                val bodyJson = if (sessionNumber != null) {
                    JSONObject().apply { put("sessionNumber", sessionNumber) }.toString()
                } else {
                    "{}"
                }

                val request = Request.Builder()
                    .url("${baseUrl()}/orders-kitchen/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        val order = parseOrderDto(json.getJSONObject("order"))
                        val linesArray = json.getJSONArray("linesToPrint")
                        val lines = mutableListOf<OrderItemDto>()
                        for (i in 0 until linesArray.length()) {
                            lines.add(parseOrderItemDto(linesArray.getJSONObject(i)))
                        }
                        ApiResult.Success(KitchenResponse(order = order, linesToPrint = lines))
                    }
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired API key")
                    403 -> ApiResult.Error("FORBIDDEN", "Permission denied")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Process payment using ordering API key (staff with TAKE_PAYMENT permission).
     */
    override suspend fun processPaymentAsStaff(orderId: String, method: String): ApiResult<OrderDto> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.processPayment(orderId, method)
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")

                val body = JSONObject().apply {
                    put("method", method)
                }.toString()

                val request = Request.Builder()
                    .url("${baseUrl()}/orders-payment/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        ApiResult.Success(parseOrderDto(json))
                    }
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired API key")
                    403 -> ApiResult.Error("FORBIDDEN", "Permission denied")
                    409 -> ApiResult.Error("NOT_SENT_TO_KITCHEN", "Order not yet sent to kitchen")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Cancel order using ordering API key (all staff can cancel).
     */
    override suspend fun cancelOrderAsStaff(
        orderId: String,
        reason: String,
        cancelledBy: String
    ): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.cancelOrder(orderId, reason, cancelledBy)
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")

                val body = JSONObject().apply {
                    put("reason", reason)
                    put("cancelledBy", cancelledBy)
                }.toString()

                val request = Request.Builder()
                    .url("${baseUrl()}/orders-cancel/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .delete(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                response.consumeBody()

                when (response.code) {
                    200 -> ApiResult.Success(Unit)
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired API key")
                    403 -> ApiResult.Error("CANCEL_NOT_ALLOWED", "Cannot cancel this order")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Add items to an existing order using ordering API key (staff amendment).
     */
    override suspend fun addItemsToOrderAsStaff(
        orderId: String,
        items: List<NewOrderItem>
    ): ApiResult<OrderDto> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.addItemsToOrder(orderId, items)
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")

                val itemsArray = JSONArray()
                items.forEach { item ->
                    itemsArray.put(JSONObject().apply {
                        put("menuItemId", item.menuItemId)
                        put("quantity", item.quantity)
                        if (item.note != null) put("note", item.note)
                        if (item.unitPrice != null) put("unitPrice", item.unitPrice)
                        if (item.size != null) put("size", item.size)
                        if (item.customName != null) put("customName", item.customName)
                    })
                }
                val body = JSONObject().apply {
                    put("items", itemsArray)
                }.toString()

                val request = Request.Builder()
                    .url("${baseUrl()}/orders-items/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        ApiResult.Success(parseOrderDto(json))
                    }
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired API key")
                    422 -> ApiResult.Error("VALIDATION", "Item unavailable or unknown")
                    409 -> ApiResult.Error("SESSION_LIMIT", "This table has reached the maximum order rounds — pay out and free it first")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Fetch orders since a timestamp using the ordering API key (staff catch-up sync).
     */
    override suspend fun getOrdersSinceAsStaff(since: String): ApiResult<OrdersSyncResponse> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.getOrdersSince()
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")

                val request = Request.Builder()
                    .url("${baseUrl()}/orders?since=$since")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        val serverTime = json.getString("serverTime")
                        val ordersArray = json.getJSONArray("orders")
                        val orders = mutableListOf<OrderDto>()
                        for (i in 0 until ordersArray.length()) {
                            orders.add(parseOrderDto(ordersArray.getJSONObject(i)))
                        }
                        ApiResult.Success(OrdersSyncResponse(orders = orders, serverTime = serverTime))
                    }
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired API key")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Fetch settings. Sends the admin session token when present so the backend returns the
     * FULL settings object — the staff-permission keys (`staffCanSendKitchen`/
     * `staffCanTakePayment`) are NOT public, so without this header they come back omitted
     * and the client defaults them to false, making saved permissions look like they never
     * persisted (they did — only the read-back was unauthenticated). Ordering devices without
     * an admin token still get the public subset, unchanged.
     */
    override suspend fun getSettings(): ApiResult<SettingsResponse> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.getSettings()
        try {
            val requestBuilder = Request.Builder()
                .url("${baseUrl()}/settings")
                .addHeader("apikey", anonKey())
                .get()
            // Send whichever token this device has — admin session token on the admin
            // device, ordering API key on a staff device. The backend authorizes either and
            // returns the full settings (incl. the non-public staff-permission keys). Without
            // a token, only the public subset comes back and permissions default to false.
            (adminBearerToken() ?: orderingBearerToken())?.let {
                requestBuilder.addHeader("Authorization", "Bearer $it")
            }
            val request = requestBuilder.build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    ApiResult.Success(
                        SettingsResponse(
                            printLanguage = json.optString("printLanguage", "EN"),
                            timezone = json.optString("timezone", "Asia/Kuala_Lumpur"),
                            topN = json.optInt("topN", 5),
                            staffCanSendKitchen = json.optBoolean("staffCanSendKitchen", false),
                            staffCanTakePayment = json.optBoolean("staffCanTakePayment", false),
                            customerOrderHoldSeconds = json.optInt("customerOrderHoldSeconds", 15),
                            customerOrderAutoPrint = json.optBoolean("customerOrderAutoPrint", true),
                            todaysSpecial = json.optString("todaysSpecial", ""),
                            reportEmail = json.optString("reportEmail", ""),
                            businessDayStartHour = json.optInt("businessDayStartHour", 15),
                            businessDayEndHour = json.optInt("businessDayEndHour", 2),
                            defaultLangAdmin = json.optString("defaultLangAdmin", "BM"),
                            defaultLangOrdering = json.optString("defaultLangOrdering", "BM"),
                            defaultLangCustomer = json.optString("defaultLangCustomer", "BM")
                        )
                    )
                }
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Get café GPS location and radius (ordering API key bearer).
     * Used by staff devices for attendance check-in radius validation.
     */
    override suspend fun getCafeLocation(): ApiResult<CafeLocationResponse> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.getCafeLocation()
            try {
                // Role-aware: admin devices (which set the location) use their session token;
                // ordering devices use their API key. Was ordering-only, so the admin's own
                // Settings screen could never read the saved location back.
                val token = getAuthToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No auth token")

                val request = Request.Builder()
                    .url("${baseUrl()}/cafe-location")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        // A never-configured café has null coordinates — treat as "not set".
                        if (json.isNull("latitude") || json.isNull("longitude")) {
                            ApiResult.Error("NOT_CONFIGURED", "Location not set")
                        } else {
                            ApiResult.Success(
                                CafeLocationResponse(
                                    latitude = json.getDouble("latitude"),
                                    longitude = json.getDouble("longitude"),
                                    radiusMeters = if (json.isNull("radiusMeters")) 100 else json.getInt("radiusMeters")
                                )
                            )
                        }
                    }
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired API key")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Post attendance event (CHECK_IN/CHECK_OUT) with GPS coordinates.
     * Uses ordering API key for staff devices.
     */
    override suspend fun postAttendance(
        event: String,
        lat: Double,
        lng: Double,
        forced: Boolean
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext ApiResult.Success(Unit)
        try {
            val token = orderingBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")

            val body = JSONObject().apply {
                put("event", event)
                put("latitude", lat)
                put("longitude", lng)
                put("forced", forced)
            }.toString()

            val request = Request.Builder()
                .url("${baseUrl()}/attendance")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            response.consumeBody()

            when (response.code) {
                200 -> ApiResult.Success(Unit)
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired API key")
                403 -> ApiResult.Error("OUTSIDE_RADIUS", "Device GPS outside café radius")
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // --- Café Location ---

    /**
     * Save café GPS location and radius (admin bearer).
     */
    override suspend fun putCafeLocation(
        lat: Double,
        lng: Double,
        radius: Int
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext ApiResult.Success(Unit)
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val body = JSONObject().apply {
                put("latitude", lat)
                put("longitude", lng)
                put("radiusMeters", radius)
            }.toString()

            val request = Request.Builder()
                .url("${baseUrl()}/cafe-location")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .put(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            response.consumeBody()

            when (response.code) {
                200 -> ApiResult.Success(Unit)
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // --- Tables ---

    /**
     * Fetch the current server-side table registry (public, no auth required).
     * Used to rehydrate local Room on a fresh install/relogin where the phone's own
     * table list is empty but a prior device already pushed tables to the backend —
     * without this, a fresh install looks like "my tables got deleted" even though
     * they're intact server-side (the phone is normally authoritative, but there's
     * nothing local to be authoritative *over* until this first pull happens).
     */
    override suspend fun getTables(): ApiResult<List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.getTables()
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/tables")
                .addHeader("apikey", anonKey())
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    val tablesArray = json.optJSONArray("tables") ?: JSONArray()
                    val tables = (0 until tablesArray.length()).map { i ->
                        val t = tablesArray.getJSONObject(i)
                        t.getString("id") to t.getString("displayName")
                    }
                    ApiResult.Success(tables)
                }
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Fetch the map of table id → opaque QR token (for generating unguessable table QR
     * codes). Public endpoint. Tables missing a token are simply omitted.
     */
    override suspend fun getTableTokens(): ApiResult<Map<String, String>> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.getTableTokens()
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/tables")
                .addHeader("apikey", anonKey())
                .get()
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            when (response.code) {
                200 -> {
                    val tablesArray = JSONObject(responseBody).optJSONArray("tables") ?: JSONArray()
                    val map = mutableMapOf<String, String>()
                    for (i in 0 until tablesArray.length()) {
                        val t = tablesArray.getJSONObject(i)
                        val id = t.optString("id", "")
                        val token = t.optStringOrNull("qrToken")
                        if (id.isNotBlank() && !token.isNullOrBlank()) map[id] = token
                    }
                    ApiResult.Success(map)
                }
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Push the full local table registry to the backend (admin bearer). Called after
     * every add/edit/delete in Manage Tables so orders/customer QR ordering — both of
     * which validate tableId against this backend registry — stay in sync with the
     * phone's local Room table list (the phone is the authoritative source, same
     * pattern as [putMenu]).
     */
    override suspend fun putTables(tables: List<Pair<String, String>>): ApiResult<List<String>> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext ApiResult.Success(emptyList())
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val tablesArray = JSONArray()
                tables.forEach { (id, displayName) ->
                    tablesArray.put(JSONObject().apply {
                        put("id", id)
                        put("displayName", displayName)
                    })
                }
                val body = JSONObject().apply {
                    put("tables", tablesArray)
                }.toString()

                val request = Request.Builder()
                    .url("${baseUrl()}/tables")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .put(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        val skippedArray = json.optJSONArray("skippedInUse")
                        val skipped = (0 until (skippedArray?.length() ?: 0)).map { skippedArray!!.getString(it) }
                        ApiResult.Success(skipped)
                    }
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    // --- Branding ---

    /**
     * Fetch current café branding (public, no auth required). Returns null cafeName
     * when branding isn't configured yet ({"configured": false}).
     */
    override suspend fun getBranding(): ApiResult<BrandingResponse> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.getBranding()
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/branding")
                .addHeader("apikey", anonKey())
                .get()
                .build()

            val response = connectClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    if (!json.optBoolean("configured", true)) {
                        ApiResult.Success(BrandingResponse(cafeName = "", logoUrl = ""))
                    } else {
                        ApiResult.Success(
                            BrandingResponse(
                                cafeName = json.optString("cafeName", ""),
                                logoUrl = json.optString("logoUrl", ""),
                                paymentQrHash = json.optString("paymentQrHash", null),
                                paymentQrUrl = json.optString("paymentQrUrl", null),
                            )
                        )
                    }
                }
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Update café branding (name, and optionally a new logo base64 JPEG ≤200KB).
     * Omitting [logoBase64] keeps whatever logo is already stored server-side —
     * e.g. the admin is only renaming the café, not replacing the logo.
     */
    override suspend fun putBranding(
        cafeName: String,
        logoBase64: String?,
        paymentQrBase64: String?,
        paymentQrHash: String?,
        removePaymentQr: Boolean,
    ): ApiResult<BrandingResponse> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.putBranding(cafeName)
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val body = JSONObject().apply {
                put("cafeName", cafeName)
                if (logoBase64 != null) put("logoBase64", logoBase64)
                // Three intents, deliberately distinguishable by the server: send bytes to set,
                // send an explicit JSON null to remove, omit the key entirely to leave alone.
                when {
                    paymentQrBase64 != null -> {
                        put("paymentQrBase64", paymentQrBase64)
                        if (paymentQrHash != null) put("paymentQrHash", paymentQrHash)
                    }
                    removePaymentQr -> put("paymentQrBase64", JSONObject.NULL)
                }
            }.toString()

            val request = Request.Builder()
                .url("${baseUrl()}/branding")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .put(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    ApiResult.Success(
                        BrandingResponse(
                            cafeName = json.getString("cafeName"),
                            logoUrl = json.getString("logoUrl"),
                            paymentQrHash = json.optString("paymentQrHash", null),
                            paymentQrUrl = json.optString("paymentQrUrl", null),
                        )
                    )
                }
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // --- Settings ---

    /**
     * Partially update system settings (admin bearer, merge semantics).
     */
    override suspend fun putSettings(body: JSONObject): ApiResult<Unit> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext ApiResult.Success(Unit)
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val request = Request.Builder()
                .url("${baseUrl()}/settings")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .put(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            response.consumeBody()

            when (response.code) {
                200 -> ApiResult.Success(Unit)
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                422 -> ApiResult.Error("VALIDATION", "Invalid settings value")
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // --- Manual Dine-In Order ---

    /**
     * Create a new order (manual dine-in entry with source=STAFF).
     */
    override suspend fun createOrder(
        tableId: String?,
        items: List<NewOrderItem>,
        source: String,
        // Cloud cafés have tables, so this is always null here. Kiosk never reaches ApiClient —
        // it runs entirely on LocalBackend with no network of any kind (Requirement 3.1).
        orderNumber: Int?,
        splitShare: Boolean,
    ): ApiResult<CreateOrderResponse> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.createOrder(tableId, items, source)
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val itemsArray = JSONArray()
            items.forEach { item ->
                itemsArray.put(JSONObject().apply {
                    put("menuItemId", item.menuItemId)
                    put("quantity", item.quantity)
                    if (item.note != null) put("note", item.note)
                    if (item.unitPrice != null) put("unitPrice", item.unitPrice)
                    if (item.size != null) put("size", item.size)
                    if (item.customName != null) put("customName", item.customName)
                })
            }
            val body = JSONObject().apply {
                put("tableId", tableId)
                put("items", itemsArray)
                if (splitShare) put("splitShare", true)
            }.toString()

            val request = Request.Builder()
                .url("${baseUrl()}/orders")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .addHeader("X-Order-Source", source)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                201 -> {
                    val json = JSONObject(responseBody)
                    ApiResult.Success(
                        CreateOrderResponse(
                            orderId = json.getString("orderId"),
                            total = json.getDouble("total"),
                            status = json.getString("status")
                        )
                    )
                }
                409 -> ApiResult.Error("TABLE_OCCUPIED", "Table already has an active session")
                422 -> {
                    val json = JSONObject(responseBody)
                    ApiResult.Error(
                        json.optString("error", "VALIDATION"),
                        json.optString("message", "Validation error")
                    )
                }
                429 -> ApiResult.Error("RATE_LIMITED", "Too many requests")
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }
            // Nudge staff devices to pull now instead of waiting for their poll tick.
            .also { r -> if (r is ApiResult.Success) announceOrderChange(null, "created") }

    // --- Menu item images ---

    /**
     * Upload a client-resized menu item thumbnail (JPEG, already cropped/downscaled).
     * Returns the public Storage URL and object path (path is kept so the old image
     * can be deleted once the new one is confirmed saved on the menu item).
     */
    override suspend fun uploadMenuImage(
        menuItemId: String,
        imageBase64: String
    ): ApiResult<MenuImageUploadResponse> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext ApiResult.Success(MenuImageUploadResponse("", ""))
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val body = JSONObject().apply {
                    put("menuItemId", menuItemId)
                    put("imageBase64", imageBase64)
                }.toString()

                val request = Request.Builder()
                    .url("${baseUrl()}/menu-image")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", anonKey())
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        ApiResult.Success(
                            MenuImageUploadResponse(
                                imageUrl = json.getString("imageUrl"),
                                path = json.getString("path")
                            )
                        )
                    }
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                    422 -> ApiResult.Error("VALIDATION", "Invalid image data")
                    else -> unexpectedStatus(response.code)
                }
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Delete a superseded menu item image by its Storage path (best-effort cleanup).
     */
    override suspend fun deleteMenuImage(path: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext ApiResult.Success(Unit)
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val body = JSONObject().apply {
                put("path", path)
            }.toString()

            val request = Request.Builder()
                .url("${baseUrl()}/menu-image")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .delete(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            response.consumeBody()

            when (response.code) {
                200 -> ApiResult.Success(Unit)
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Fetch current menu state from backend (public, no auth required).
     */
    override suspend fun getMenu(): ApiResult<MenuResponse> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.getMenu()
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/menu")
                .addHeader("apikey", anonKey())
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    if (!json.optBoolean("configured", true)) {
                        ApiResult.Success(MenuResponse(configured = false, items = emptyList()))
                    } else {
                        val items = mutableListOf<MenuItemDto>()
                        val itemsArray = json.optJSONArray("items") ?: JSONArray()
                        for (i in 0 until itemsArray.length()) {
                            val item = itemsArray.getJSONObject(i)
                            val nameObj = item.getJSONObject("name")
                            items.add(
                                MenuItemDto(
                                    id = item.getString("id"),
                                    category = item.getString("category"),
                                    // categories[] (if present) minus the primary → extraCategories.
                                    extraCategories = item.optJSONArray("categories")?.let { arr ->
                                        (0 until arr.length())
                                            .map { arr.optString(it, "").trim() }
                                            .filter { it.isNotBlank() && it != item.getString("category") }
                                            .joinToString(",")
                                    } ?: "",
                                    code = item.optString("code", ""),
                                    price = item.getDouble("price"),
                                    marketPrice = item.optBoolean("marketPrice", false),
                                    available = item.getBoolean("available"),
                                    askMeDaily = item.optBoolean("askMeDaily", false),
                                    imageUrl = item.optString("image", ""),
                                    hasVariablePrice = item.optBoolean("hasVariablePrice", false),
                                    variablePriceDailyPrompt = item.optBoolean("variablePriceDailyPrompt", false),
                                    // NOTE: has() is true even when the value is an explicit JSON null
                                    // (the preset emits "priceOption1": null), so guard with !isNull —
                                    // otherwise getDouble(null) throws and the whole menu fails to parse.
                                    priceOption1 = if (item.has("priceOption1") && !item.isNull("priceOption1")) item.getDouble("priceOption1") else null,
                                    priceOption2 = if (item.has("priceOption2") && !item.isNull("priceOption2")) item.getDouble("priceOption2") else null,
                                    priceOption3 = if (item.has("priceOption3") && !item.isNull("priceOption3")) item.getDouble("priceOption3") else null,
                                    nameEn = nameObj.getString("en"),
                                    nameBm = nameObj.optString("bm", ""),
                                    nameZh = nameObj.optString("zh", ""),
                                    nameTa = nameObj.optString("ta", ""),
                                    nameTh = nameObj.optString("th", ""),
                                    doNotTranslate = nameObj.optBoolean("doNotTranslate", false)
                                )
                            )
                        }
                        val categories = mutableListOf<MenuCategoryDto>()
                        val categoriesArray = json.optJSONArray("categories") ?: JSONArray()
                        for (i in 0 until categoriesArray.length()) {
                            val cat = categoriesArray.getJSONObject(i)
                            val name = cat.optString("name", "")
                            if (name.isNotBlank()) {
                                val i18nObj = cat.optJSONObject("nameI18n")
                                val i18n = mutableMapOf<String, String>()
                                if (i18nObj != null) {
                                    for (lang in listOf("en", "bm", "zh", "ta", "th")) {
                                        val v = i18nObj.optString(lang, "")
                                        if (v.isNotBlank()) i18n[lang] = v
                                    }
                                }
                                categories.add(
                                    MenuCategoryDto(
                                        name = name,
                                        sortOrder = cat.optInt("sortOrder", i),
                                        nameI18n = i18n
                                    )
                                )
                            }
                        }
                        ApiResult.Success(
                            MenuResponse(
                                configured = true,
                                items = items,
                                categories = categories.sortedBy { it.sortOrder }
                            )
                        )
                    }
                }
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // --- Payment gateway (task 6.1, 6.3) ---

    /**
     * Initiate a gateway payment attempt using admin bearer token.
     *
     * Forwards [PosCheckoutPayload] to the `payment-initiate` Edge Function, which holds the
     * aggregator secret and computes the gateway signature server-side. The POS never sees
     * the merchant secret key. (PG-REQ-4, PG-REQ-8, F3)
     *
     * **Money boundary**: [PosCheckoutPayload.amountSen] must already be in sen. The caller
     * must use [com.razstudio.pos.data.local.PaymentTransaction.fromRinggit] exactly once,
     * converting [Order.total] at the BackendGateway layer. No second conversion anywhere. (A8)
     *
     * **Idempotency**: [PosCheckoutPayload.idempotencyKey] must equal the
     * [com.razstudio.pos.data.local.PaymentTransaction.id] minted for this attempt — a UUID
     * stable across retries. (A6, 6.3)
     */
    override suspend fun initiatePayment(payload: PosCheckoutPayload): ApiResult<GatewayPaymentResult> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.initiatePayment(payload)
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")
                initiatePaymentRequest(payload, token)
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /** Staff-auth variant of [initiatePayment] — ordering API key, otherwise identical. */
    override suspend fun initiatePaymentAsStaff(payload: PosCheckoutPayload): ApiResult<GatewayPaymentResult> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.initiatePayment(payload)
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")
                initiatePaymentRequest(payload, token)
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /** Shared body of both initiatePayment variants — only the bearer differs. */
    private fun initiatePaymentRequest(payload: PosCheckoutPayload, bearer: String): ApiResult<GatewayPaymentResult> {
        val body = JSONObject().apply {
            put("orderId", payload.orderId)
            // amountSen is already sen — converted once via PaymentTransaction.fromRinggit (A8)
            put("amountSen", payload.amountSen)
            put("paymentMethodCode", payload.paymentMethodCode)
            put("currency", payload.currency)
            put("idempotencyKey", payload.idempotencyKey)
            put("isSandbox", payload.isSandbox)
            if (payload.customerAuthCode != null) put("customerAuthCode", payload.customerAuthCode)
        }.toString()

        val request = Request.Builder()
            .url("${baseUrl()}/payment-initiate")
            .addHeader("Content-Type", "application/json")
            .addHeader("apikey", anonKey())
            .addHeader("Authorization", "Bearer $bearer")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        return when (response.code) {
            200 -> ApiResult.Success(parseGatewayPaymentResult(JSONObject(responseBody)))
            401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired credentials")
            402 -> ApiResult.Error("GATEWAY_REJECTED", "Gateway rejected the payment request")
            422 -> {
                val json = runCatching { JSONObject(responseBody) }.getOrDefault(JSONObject())
                ApiResult.Error(
                    json.optString("error", "VALIDATION"),
                    json.optString("message", "Invalid payment request")
                )
            }
            else -> unexpectedStatus(response.code)
        }
    }

    /**
     * Query the gateway for a transaction's current status. Called by the polling loop.
     * Persisted status from the callback is authoritative after 24 h — do not rely on this
     * beyond that window. (F5, 6.2c)
     */
    override suspend fun queryPayment(transactionId: String): ApiResult<GatewayPaymentResult> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.queryPayment(transactionId)
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")
                queryPaymentRequest(transactionId, token)
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /** Staff-auth variant of [queryPayment]. */
    override suspend fun queryPaymentAsStaff(transactionId: String): ApiResult<GatewayPaymentResult> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.queryPayment(transactionId)
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")
                queryPaymentRequest(transactionId, token)
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    private fun queryPaymentRequest(transactionId: String, bearer: String): ApiResult<GatewayPaymentResult> {
        val body = JSONObject().apply {
            put("transactionId", transactionId)
        }.toString()

        val request = Request.Builder()
            .url("${baseUrl()}/payment-query")
            .addHeader("Content-Type", "application/json")
            .addHeader("apikey", anonKey())
            .addHeader("Authorization", "Bearer $bearer")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        return when (response.code) {
            200 -> ApiResult.Success(parseGatewayPaymentResult(JSONObject(responseBody)))
            401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired credentials")
            404 -> ApiResult.Error("NOT_FOUND", "Transaction not found at gateway")
            else -> unexpectedStatus(response.code)
        }
    }

    /**
     * List all payment attempts for an order. Used for retry history and crash-recovery. (8.5)
     */
    override suspend fun listPaymentTransactions(orderId: String): ApiResult<List<PaymentTransactionDto>> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.listPaymentTransactions(orderId)
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")
                listTransactionsRequest(orderId, token)
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /** Staff-auth variant of [listPaymentTransactions]. */
    override suspend fun listPaymentTransactionsAsStaff(orderId: String): ApiResult<List<PaymentTransactionDto>> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.listPaymentTransactions(orderId)
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")
                listTransactionsRequest(orderId, token)
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    private fun listTransactionsRequest(orderId: String, bearer: String): ApiResult<List<PaymentTransactionDto>> {
        val request = Request.Builder()
            .url("${baseUrl()}/payment-transactions?orderId=$orderId")
            .addHeader("apikey", anonKey())
            .addHeader("Authorization", "Bearer $bearer")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        return when (response.code) {
            200 -> {
                val json = JSONObject(responseBody)
                val arr = json.getJSONArray("transactions")
                val list = mutableListOf<PaymentTransactionDto>()
                for (i in 0 until arr.length()) {
                    list.add(parsePaymentTransactionDto(arr.getJSONObject(i)))
                }
                ApiResult.Success(list)
            }
            401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired credentials")
            else -> unexpectedStatus(response.code)
        }
    }

    /**
     * Read-only gateway configuration — never a secret's value, only whether one is set.
     * (PG-REQ-2, PG-REQ-8, task 7.1)
     */
    override suspend fun getGatewayConfig(): ApiResult<GatewayConfigDto> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.getGatewayConfig()
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")
                getGatewayConfigRequest(token)
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /** Staff-auth variant of [getGatewayConfig] — used to decide which gateway tiles to show. */
    override suspend fun getGatewayConfigAsStaff(): ApiResult<GatewayConfigDto> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.getGatewayConfig()
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")
                getGatewayConfigRequest(token)
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    private fun getGatewayConfigRequest(bearer: String): ApiResult<GatewayConfigDto> {
        val request = Request.Builder()
            .url("${baseUrl()}/gateway-config")
            .addHeader("apikey", anonKey())
            .addHeader("Authorization", "Bearer $bearer")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        return when (response.code) {
            200 -> ApiResult.Success(parseGatewayConfigDto(JSONObject(responseBody)))
            401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired credentials")
            else -> unexpectedStatus(response.code)
        }
    }

    /** Admin-only — there is no staff variant. See [BackendGateway.putGatewayConfig]. */
    override suspend fun putGatewayConfig(
        merchantId: String,
        verifyKey: String?,
        secretKey: String?,
        isSandbox: Boolean,
        enabledMethods: List<String>,
    ): ApiResult<GatewayConfigDto> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext demoBackend.getGatewayConfig()
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val body = JSONObject().apply {
                put("merchantId", merchantId)
                if (verifyKey != null) put("verifyKey", verifyKey)
                if (secretKey != null) put("secretKey", secretKey)
                put("isSandbox", isSandbox)
                put("enabledMethods", JSONArray(enabledMethods))
            }.toString()

            val request = Request.Builder()
                .url("${baseUrl()}/gateway-config")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .put(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> ApiResult.Success(parseGatewayConfigDto(JSONObject(responseBody)))
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired credentials")
                403 -> ApiResult.Error("FORBIDDEN", "Only the admin device can change gateway settings")
                422 -> {
                    val json = runCatching { JSONObject(responseBody) }.getOrDefault(JSONObject())
                    ApiResult.Error(
                        json.optString("error", "VALIDATION"),
                        json.optString("message", "Invalid gateway configuration")
                    )
                }
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    override suspend fun getGatewayProviders(): ApiResult<List<GatewayProviderDto>> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.getGatewayProviders()
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")
                getGatewayProvidersRequest(token)
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    override suspend fun getGatewayProvidersAsStaff(): ApiResult<List<GatewayProviderDto>> =
        withContext(Dispatchers.IO) {
            if (DemoSession.active) return@withContext demoBackend.getGatewayProviders()
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")
                getGatewayProvidersRequest(token)
            } catch (e: IOException) {
                networkError(e)
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    private fun getGatewayProvidersRequest(bearer: String): ApiResult<List<GatewayProviderDto>> {
        val request = Request.Builder()
            .url("${baseUrl()}/gateway-providers")
            .addHeader("apikey", anonKey())
            .addHeader("Authorization", "Bearer $bearer")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        return when (response.code) {
            200 -> {
                val arr = JSONObject(responseBody).getJSONArray("providers")
                val list = mutableListOf<GatewayProviderDto>()
                for (i in 0 until arr.length()) list.add(parseGatewayProviderDto(arr.getJSONObject(i)))
                ApiResult.Success(list)
            }
            401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired credentials")
            else -> unexpectedStatus(response.code)
        }
    }

    override suspend fun putGatewayProvider(
        provider: String,
        credentials: Map<String, String>,
        enabledMethods: List<String>,
        isSandbox: Boolean,
        isEnabled: Boolean,
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        if (DemoSession.active) return@withContext ApiResult.Success(Unit)
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val body = JSONObject().apply {
                put("provider", provider)
                // Only the fields the admin actually typed are sent. An omitted field keeps its
                // stored value server-side, which is how a masked secret survives a save.
                put("credentials", JSONObject().apply {
                    credentials.forEach { (k, v) -> put(k, v) }
                })
                put("enabledMethods", JSONArray(enabledMethods))
                put("isSandbox", isSandbox)
                put("isEnabled", isEnabled)
            }.toString()

            val request = Request.Builder()
                .url("${baseUrl()}/gateway-providers")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", anonKey())
                .addHeader("Authorization", "Bearer $token")
                .put(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> ApiResult.Success(Unit)
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired credentials")
                403 -> ApiResult.Error("FORBIDDEN", "Only the admin device can change gateway settings")
                422 -> {
                    val json = runCatching { JSONObject(responseBody) }.getOrDefault(JSONObject())
                    ApiResult.Error(
                        json.optString("error", "VALIDATION"),
                        json.optString("message", "Invalid provider configuration")
                    )
                }
                else -> unexpectedStatus(response.code)
            }
        } catch (e: IOException) {
            networkError(e)
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    private fun parseGatewayProviderDto(json: JSONObject): GatewayProviderDto {
        val fields = mutableListOf<GatewayCredentialFieldDto>()
        json.optJSONArray("credentialFields")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                fields.add(
                    GatewayCredentialFieldDto(
                        key = f.getString("key"),
                        label = f.getString("label"),
                        secret = f.optBoolean("secret", false),
                        required = f.optBoolean("required", false),
                        hint = f.optStringOrNull("hint"),
                    )
                )
            }
        }
        val fieldsSet = mutableMapOf<String, Boolean>()
        json.optJSONObject("fieldsSet")?.let { obj ->
            obj.keys().forEach { key -> fieldsSet[key] = obj.optBoolean(key, false) }
        }
        val methods = mutableListOf<String>()
        json.optJSONArray("enabledMethods")?.let { arr ->
            for (i in 0 until arr.length()) methods.add(arr.getString(i))
        }
        return GatewayProviderDto(
            provider = json.getString("provider"),
            displayName = json.optString("displayName", json.getString("provider")),
            status = json.optString("status", "AWAITING_ONBOARDING"),
            unavailableReason = json.optStringOrNull("unavailableReason"),
            credentialFields = fields,
            configured = json.optBoolean("configured", false),
            fieldsSet = fieldsSet,
            enabledMethods = methods,
            isSandbox = json.optBoolean("isSandbox", true),
            isEnabled = json.optBoolean("isEnabled", false),
        )
    }

    private fun parseGatewayConfigDto(json: JSONObject): GatewayConfigDto {
        val methods = mutableListOf<String>()
        val arr = json.optJSONArray("enabledMethods")
        if (arr != null) for (i in 0 until arr.length()) methods.add(arr.getString(i))
        return GatewayConfigDto(
            configured = json.optBoolean("configured", false),
            merchantId = json.optString("merchantId", ""),
            hasVerifyKey = json.optBoolean("hasVerifyKey", false),
            hasSecretKey = json.optBoolean("hasSecretKey", false),
            isSandbox = json.optBoolean("isSandbox", true),
            enabledMethods = methods,
        )
    }

    /** Parse a gateway result object from a JSON response. No @Serializable — hand-rolled (A16). */
    private fun parseGatewayPaymentResult(json: JSONObject): GatewayPaymentResult =
        GatewayPaymentResult(
            success = json.optBoolean("success", false),
            transactionId = json.optStringOrNull("transactionId"),
            qrString = json.optStringOrNull("qrString"),
            checkoutUrl = json.optStringOrNull("checkoutUrl"),
            status = json.optStringOrNull("status"),
            errorMessage = json.optStringOrNull("errorMessage"),
        )

    /** Parse a payment transaction DTO from a JSON response. */
    private fun parsePaymentTransactionDto(json: JSONObject): PaymentTransactionDto =
        PaymentTransactionDto(
            id = json.getString("id"),
            orderId = json.getString("orderId"),
            paymentMethod = json.getString("paymentMethod"),
            amountSen = json.getLong("amountSen"),
            status = json.getString("status"),
            gatewayTransactionId = json.optStringOrNull("gatewayTransactionId"),
            gatewayResponse = json.optStringOrNull("gatewayResponse"),
            isSandbox = json.optBoolean("isSandbox", false),
            createdAt = json.getString("createdAt"),
            settledAt = json.optStringOrNull("settledAt"),
        )
}

// --- Data classes ---

data class SessionResponse(
    val sessionId: String,
    val event: String,
    val timestamp: String
)

data class MenuResponse(
    val configured: Boolean,
    val items: List<MenuItemDto>,
    /** Ordered category definitions from the snapshot's top-level `categories` array. */
    val categories: List<MenuCategoryDto> = emptyList()
)

/** A category definition from the menu snapshot (identity = [name]). */
data class MenuCategoryDto(
    val name: String,
    val sortOrder: Int,
    /** Per-language display labels ("en","bm","zh","ta","th"); empty if not translated. */
    val nameI18n: Map<String, String> = emptyMap()
)

data class MenuItemDto(
    val id: String,
    val category: String,
    /** Comma-separated additional categories (beyond [category]) this item appears under. */
    val extraCategories: String = "",
    val code: String = "",
    val price: Double,
    val marketPrice: Boolean = false,
    val available: Boolean,
    val askMeDaily: Boolean,
    val imageUrl: String,
    val hasVariablePrice: Boolean = false,
    val variablePriceDailyPrompt: Boolean = false,
    val priceOption1: Double? = null,
    val priceOption2: Double? = null,
    val priceOption3: Double? = null,
    val nameEn: String,
    val nameBm: String,
    val nameZh: String,
    val nameTa: String,
    val nameTh: String,
    val doNotTranslate: Boolean
)

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val code: String, val message: String) : ApiResult<Nothing>()
    data class NetworkError(val message: String) : ApiResult<Nothing>()
}

data class RegisterResponse(
    val deviceId: String,
    val status: String
)

data class DeviceStatusResponse(
    val status: String,
    val role: String?,
    val apiKey: String?,
    /** Delivered once for an approved ADMIN_SECONDARY device (admin session token). */
    val sessionToken: String? = null
)

// --- Order management data classes ---

data class OrdersSyncResponse(
    val orders: List<OrderDto>,
    val serverTime: String
)

data class OrderDto(
    val id: String,
    /** Null in Kiosk Mode, which has no tables — see [orderNumber]. */
    val tableId: String?,
    /** Kiosk Mode's running number for the business day; null in every other mode. */
    val orderNumber: Int? = null,
    val source: String,
    val status: String,
    val paymentMethod: String?,
    val total: Double,
    val sentToKitchenAt: String?,
    val cancelReason: String?,
    val cancelledBy: String?,
    val createdAt: String,
    /** True for a Split Payment share order — its items belong to a bill someone else is still
     *  sitting at, already sent/cooked. Never a "new order" to alert on or a slip to print. */
    val isSplitShare: Boolean = false,
    val items: List<OrderItemDto> = emptyList()
)

data class OrderItemDto(
    val id: String,
    val menuItemId: String,
    val nameSnapshot: String,
    val unitPriceSnapshot: Double,
    val categorySnapshot: String,
    val quantity: Int,
    val note: String?,
    val sentToKitchen: Boolean,
    val sessionNumber: Int = 1
)

data class KitchenResponse(
    val order: OrderDto,
    val linesToPrint: List<OrderItemDto>
)

/**
 * One line's requested keep-quantity when reducing a bill for food that never arrived.
 *
 * [keepQuantity] is what STAYS on the order, not what is taken off — the cashier is looking at a row
 * that says "2×" and turning it down, so the number they land on is the number billed. 0 removes the
 * line entirely. The server refuses a value above the line's current quantity.
 */
data class VoidLine(
    val itemId: String,
    val keepQuantity: Int,
)

data class NewOrderItem(
    val menuItemId: String,
    val quantity: Int,
    val note: String? = null,
    /** Chosen Small/Medium/Large price for a variable-price item (null = use the item's base). */
    val unitPrice: Double? = null,
    /** Size label baked into the name server-side, e.g. "S"/"M"/"L". */
    val size: String? = null,
    /**
     * Cashier-typed name for a **custom charge** — a bill line with no menu item behind it (see
     * [CUSTOM_CHARGE_ID_PREFIX]). Non-null only when [menuItemId] carries that prefix, in which case
     * [unitPrice] is the typed price and the server snapshots both instead of pricing from the menu.
     * Honored for admin/staff callers only.
     */
    val customName: String? = null
)

// --- Device management data classes ---

data class DeviceDto(
    val id: String,
    /** The device's own persistent identifier (SecureStorage.getDeviceId on that device). */
    val deviceIdentifier: String,
    val label: String,
    val role: String,
    val status: String,
    val lastSeenAt: String?,
    val isCheckedIn: Boolean
)

data class InviteResponse(
    val token: String,
    val url: String
)

data class BrandingResponse(
    val cafeName: String,
    val logoUrl: String,
    val paymentQrHash: String? = null,
    val paymentQrUrl: String? = null,
)

data class MenuImageUploadResponse(
    val imageUrl: String,
    val path: String
)

data class CreateOrderResponse(
    val orderId: String,
    val total: Double,
    val status: String
)

data class CafeLocationResponse(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int
)

data class SettingsResponse(
    val printLanguage: String,
    val timezone: String,
    val topN: Int,
    val staffCanSendKitchen: Boolean,
    val staffCanTakePayment: Boolean,
    val customerOrderHoldSeconds: Int = 15,
    val customerOrderAutoPrint: Boolean = true,
    val todaysSpecial: String = "",
    val reportEmail: String = "",
    val businessDayStartHour: Int = 15,
    val businessDayEndHour: Int = 2,
    // Café-wide default UI language per surface (BM/EN/ZH/TA/TH). Applied by a device only
    // when it has no locally-saved language choice yet — see LanguageManager.applyDefaultIfUnset.
    val defaultLangAdmin: String = "BM",
    val defaultLangOrdering: String = "BM",
    val defaultLangCustomer: String = "BM"
)

// --- Payment gateway data classes (task 6.1) ---

/**
 * The payload sent to `payment-initiate` via [BackendGateway.initiatePayment].
 *
 * No @Serializable — this codebase hand-rolls JSON with org.json (A16). See [ApiClient]'s
 * implementation for the hand-rolled serialisation.
 *
 * **No `merchantId` field.** The Edge Function reads its own merchant identity from the
 * service-role-only `gateway_config` row — never from the client — which is the whole point of
 * A2/F3: the POS selects a payment *method*, not an *aggregator account*.
 *
 * [idempotencyKey] MUST equal [com.razstudio.pos.data.local.PaymentTransaction.idempotencyKeyFor]
 * `(orderId, amountSen)`: stable for this (order, amount) pair, replayed verbatim on every retry.
 * A timestamp-based key is a new key on every retry — the precise double-charge this field exists
 * to prevent. (A6, 6.3)
 */
data class PosCheckoutPayload(
    val orderId: String,
    /** Amount in **sen** (integer). Conversion from [Order.total] ringgit happens at the
     *  [ApiClient] boundary and nowhere else — [com.razstudio.pos.data.local.PaymentTransaction.fromRinggit]. (A8) */
    val amountSen: Long,
    val paymentMethodCode: String,
    val currency: String = "MYR",
    /** Barcode presented by the customer's wallet, for merchant-scan flows. */
    val customerAuthCode: String? = null,
    /** == PaymentTransaction.id. Stable across retries. (A6) */
    val idempotencyKey: String,
    val isSandbox: Boolean = false,
)

/**
 * The response from `payment-initiate` or `payment-query`.
 *
 * No @Serializable — parsed by hand in [ApiClient]. (A16)
 */
data class GatewayPaymentResult(
    val success: Boolean,
    val transactionId: String? = null,
    /** EMVCo/DuitNow QR string for the customer to scan (QR and e-wallet flows). */
    val qrString: String? = null,
    /** Hosted checkout URL (FPX / Card flows). */
    val checkoutUrl: String? = null,
    val status: String? = null,
    val errorMessage: String? = null,
)

/**
 * A single [com.razstudio.pos.data.local.PaymentTransaction] row, serialised for transport by
 * `payment-transactions`. Mirrors the Room entity's fields without the Room annotations.
 */
data class PaymentTransactionDto(
    val id: String,
    val orderId: String,
    val paymentMethod: String,
    val amountSen: Long,
    val status: String,
    val gatewayTransactionId: String? = null,
    val gatewayResponse: String? = null,
    val isSandbox: Boolean = false,
    val createdAt: String,
    val settledAt: String? = null,
)

/**
 * Read-only view of the café's gateway configuration — **never** carries a secret's value, only
 * whether one is set. Drives which gateway tiles task 7.2 shows at checkout, and lets the admin
 * settings screen (7.1) render "already configured" without ever re-displaying a secret. (PG-REQ-2,
 * PG-REQ-8)
 */
/**
 * One credential input a provider needs, as declared by that provider's server-side adapter.
 *
 * The settings screen renders its form from these rather than hardcoding one layout per provider —
 * which is the whole point: Touch 'n Go issues merchant id + verify/secret key, a bank's DuitNow
 * rail issues OAuth client id + secret, and neither is known until onboarding completes.
 */
data class GatewayCredentialFieldDto(
    val key: String,
    val label: String,
    /** Masked on entry and never returned once stored — see [GatewayProviderDto.fieldsSet]. */
    val secret: Boolean,
    val required: Boolean,
    val hint: String? = null,
)

/**
 * A payment provider the café can configure. (PG-REQ-2, PG-REQ-8)
 *
 * Carries **no credential values** — [fieldsSet] reports only which fields have something stored,
 * which is what lets the screen show a masked "already set" placeholder without a secret ever
 * leaving the server.
 */
data class GatewayProviderDto(
    val provider: String,
    val displayName: String,
    /** `AVAILABLE` — adapter implemented. `AWAITING_ONBOARDING` — fail-closed placeholder. */
    val status: String,
    /** Why the provider cannot be used yet, when [status] is `AWAITING_ONBOARDING`. */
    val unavailableReason: String?,
    val credentialFields: List<GatewayCredentialFieldDto>,
    /** True when every required field has a stored value. */
    val configured: Boolean,
    /** field key → whether a value is stored. Never the value itself. */
    val fieldsSet: Map<String, Boolean>,
    val enabledMethods: List<String>,
    val isSandbox: Boolean,
    /** Server forces this false unless the adapter is AVAILABLE and [configured] — a stub can
     *  never look live at the counter. */
    val isEnabled: Boolean,
)

data class GatewayConfigDto(
    /** True once a merchant id and both keys are set — [BackendGateway.initiatePayment] otherwise
     *  fails closed with `GATEWAY_NOT_CONFIGURED`. */
    val configured: Boolean,
    /** Not secret — the evaluated aggregator puts it in the payment URL path itself (F2). */
    val merchantId: String,
    val hasVerifyKey: Boolean,
    val hasSecretKey: Boolean,
    val isSandbox: Boolean,
    /** [com.razstudio.pos.data.local.PaymentMethod.code] values this café has enabled. */
    val enabledMethods: List<String>,
)
