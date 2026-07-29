package com.warungtomyam.pos.data

import com.warungtomyam.pos.BuildConfig
import com.warungtomyam.pos.data.json.OrderMapper
import com.warungtomyam.pos.data.json.optStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val authEventBus: AuthEventBus
) {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val BASE_URL = BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1"
    }

    private val client = OkHttpClient.Builder()
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

    private fun adminBearerToken(): String? = secureStorage.getSessionToken()

    /**
     * Admin handshake: first-claim admin registration with rotating key.
     * @return [ApiResult] with sessionToken on success, or error code.
     */
    suspend fun adminHandshake(deviceId: String, rotatingKey: String): ApiResult<String> =
        adminHandshakeRequest(deviceId) { put("rotatingKey", rotatingKey) }

    /**
     * Debug-only admin handshake: claims the admin slot using the café's plaintext
     * name instead of the rotating key. Only ever wired up from a `BuildConfig.DEBUG`
     * gated UI path — the backend independently requires its `ALLOW_DEBUG_ADMIN`
     * deployment secret to be set, so this is a no-op (401) against any deployment
     * that hasn't explicitly opted in, release or otherwise.
     */
    suspend fun adminHandshakeDebug(deviceId: String, cafeName: String): ApiResult<String> =
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
                .url("$BASE_URL/admin-handshake")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    val token = json.getString("sessionToken")
                    ApiResult.Success(token)
                }
                409 -> ApiResult.Error("ADMIN_EXISTS", "An admin device is already registered")
                401 -> ApiResult.Error("INVALID_KEY", "Invalid or expired rotating key")
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Register an ordering device with an invitation token.
     * @return [ApiResult] with device status on success.
     */
    suspend fun register(
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
                .url("$BASE_URL/register")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
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
                403 -> ApiResult.Error("INVALID_INVITE", "Invalid or expired invitation")
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Poll device approval status.
     * @return [ApiResult] with device status (PENDING/APPROVED/REVOKED) and optional apiKey.
     */
    suspend fun pollDeviceStatus(deviceId: String): ApiResult<DeviceStatusResponse> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/devices-status?deviceId=$deviceId")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
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
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    // --- Session lifecycle ---

    /**
     * Post a session event (OPEN/CLOSE) with optional reason and closing flag.
     * Backend broadcasts CAFE_OPEN or CAFE_CLOSED accordingly.
     */
    suspend fun postSession(
        event: String,
        reason: String? = null,
        closing: Boolean = false
    ): ApiResult<SessionResponse> = withContext(Dispatchers.IO) {
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val body = JSONObject().apply {
                put("event", event)
                if (reason != null) put("reason", reason)
                if (closing) put("closing", true)
            }.toString()

            val request = Request.Builder()
                .url("$BASE_URL/sessions")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Push daily aggregate summary at closing time.
     */
    suspend fun postAggregates(date: String, body: JSONObject): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                body.put("date", date)

                val request = Request.Builder()
                    .url("$BASE_URL/aggregates")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()

                when (response.code) {
                    200 -> ApiResult.Success(Unit)
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Push full menu snapshot (availability updates, daily popup changes).
     */
    suspend fun putMenu(
        menuItems: JSONArray,
        categories: JSONArray = JSONArray()
    ): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val body = JSONObject().apply {
                    put("items", menuItems)
                    put("categories", categories)
                }.toString()

                val request = Request.Builder()
                    .url("$BASE_URL/menu")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .put(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()

                when (response.code) {
                    200 -> ApiResult.Success(Unit)
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    // --- Order Management ---

    /**
     * Catch-up sync: fetch all orders since a given timestamp.
     * Called on every WebSocket (re)connect to reconcile with Room.
     */
    suspend fun getOrdersSince(since: String): ApiResult<OrdersSyncResponse> =
        withContext(Dispatchers.IO) {
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val request = Request.Builder()
                    .url("$BASE_URL/orders?since=$since")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Send order to kitchen — marks unsent items as sent, returns delta lines to print.
     * When [sessionNumber] is non-null, scopes the operation to just that session's
     * items (B4.3: confirm a single pending round without reprinting everything).
     */
    suspend fun sendToKitchen(orderId: String, sessionNumber: Int? = null): ApiResult<KitchenResponse> =
        withContext(Dispatchers.IO) {
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val bodyJson = if (sessionNumber != null) {
                    JSONObject().apply { put("sessionNumber", sessionNumber) }.toString()
                } else {
                    "{}"
                }

                val request = Request.Builder()
                    .url("$BASE_URL/orders-kitchen/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Add items to an existing order (amendment).
     */
    suspend fun addItemsToOrder(orderId: String, items: List<NewOrderItem>): ApiResult<OrderDto> =
        withContext(Dispatchers.IO) {
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val itemsArray = JSONArray()
                items.forEach { item ->
                    itemsArray.put(JSONObject().apply {
                        put("menuItemId", item.menuItemId)
                        put("quantity", item.quantity)
                        if (item.note != null) put("note", item.note)
                    })
                }
                val body = JSONObject().apply {
                    put("items", itemsArray)
                }.toString()

                val request = Request.Builder()
                    .url("$BASE_URL/orders-items/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Update order status (PREPARING/READY).
     */
    suspend fun updateOrderStatus(orderId: String, status: String): ApiResult<OrderDto> =
        withContext(Dispatchers.IO) {
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val body = JSONObject().apply {
                    put("status", status)
                }.toString()

                val request = Request.Builder()
                    .url("$BASE_URL/orders-status/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Process payment (Cash/QR). Only valid after SENT_TO_KITCHEN.
     */
    suspend fun processPayment(orderId: String, method: String): ApiResult<OrderDto> =
        withContext(Dispatchers.IO) {
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val body = JSONObject().apply {
                    put("method", method)
                }.toString()

                val request = Request.Builder()
                    .url("$BASE_URL/orders-payment/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Cancel an order with reason and who cancelled it.
     */
    suspend fun cancelOrder(orderId: String, reason: String, cancelledBy: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val body = JSONObject().apply {
                    put("reason", reason)
                    put("cancelledBy", cancelledBy)
                }.toString()

                val request = Request.Builder()
                    .url("$BASE_URL/orders-cancel/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .delete(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()

                when (response.code) {
                    200 -> ApiResult.Success(Unit)
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                    403 -> ApiResult.Error("CANCEL_NOT_ALLOWED", "Cannot cancel this order")
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
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
    suspend fun getDevices(): ApiResult<List<DeviceDto>> = withContext(Dispatchers.IO) {
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val request = Request.Builder()
                .url("$BASE_URL/devices")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Patch a device: approve, reject, revoke, force check-out, or rename.
     */
    suspend fun patchDevice(
        deviceId: String,
        action: String,
        label: String? = null
    ): ApiResult<DeviceDto> = withContext(Dispatchers.IO) {
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val body = JSONObject().apply {
                put("action", action)
                if (label != null) put("label", label)
            }.toString()

            val request = Request.Builder()
                .url("$BASE_URL/devices/$deviceId")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // --- Invitations ---

    /**
     * Get the current staff invitation URL (admin bearer).
     */
    suspend fun getInvite(role: String? = null): ApiResult<InviteResponse> = withContext(Dispatchers.IO) {
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val url = if (role != null) "$BASE_URL/invite?role=$role" else "$BASE_URL/invite"
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                            url = json.getString("url")
                        )
                    )
                }
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Regenerate the staff invitation token (admin bearer).
     */
    suspend fun regenerateInvite(role: String? = null): ApiResult<InviteResponse> = withContext(Dispatchers.IO) {
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val url = if (role != null) "$BASE_URL/invite/regenerate?role=$role" else "$BASE_URL/invite/regenerate"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                            url = json.getString("url")
                        )
                    )
                }
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
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

    /**
     * Create a new order using the ordering API key (staff device).
     * Staff devices submit orders with source=STAFF via their API key.
     */
    suspend fun createOrderAsStaff(
        tableId: String,
        items: List<NewOrderItem>
    ): ApiResult<CreateOrderResponse> = withContext(Dispatchers.IO) {
        try {
            val token = orderingBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")

            val itemsArray = JSONArray()
            items.forEach { item ->
                itemsArray.put(JSONObject().apply {
                    put("menuItemId", item.menuItemId)
                    put("quantity", item.quantity)
                    if (item.note != null) put("note", item.note)
                })
            }
            val body = JSONObject().apply {
                put("tableId", tableId)
                put("items", itemsArray)
            }.toString()

            val request = Request.Builder()
                .url("$BASE_URL/orders")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Send order to kitchen using ordering API key (staff with SEND_TO_KITCHEN permission).
     * When [sessionNumber] is non-null, scopes to just that session's items (B4.3 confirm),
     * mirroring the admin [sendToKitchen] variant.
     */
    suspend fun sendToKitchenAsStaff(orderId: String, sessionNumber: Int? = null): ApiResult<KitchenResponse> =
        withContext(Dispatchers.IO) {
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")

                val bodyJson = if (sessionNumber != null) {
                    JSONObject().apply { put("sessionNumber", sessionNumber) }.toString()
                } else {
                    "{}"
                }

                val request = Request.Builder()
                    .url("$BASE_URL/orders-kitchen/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Process payment using ordering API key (staff with TAKE_PAYMENT permission).
     */
    suspend fun processPaymentAsStaff(orderId: String, method: String): ApiResult<OrderDto> =
        withContext(Dispatchers.IO) {
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")

                val body = JSONObject().apply {
                    put("method", method)
                }.toString()

                val request = Request.Builder()
                    .url("$BASE_URL/orders-payment/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Cancel order using ordering API key (all staff can cancel).
     */
    suspend fun cancelOrderAsStaff(orderId: String, reason: String, cancelledBy: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")

                val body = JSONObject().apply {
                    put("reason", reason)
                    put("cancelledBy", cancelledBy)
                }.toString()

                val request = Request.Builder()
                    .url("$BASE_URL/orders-cancel/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .delete(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()

                when (response.code) {
                    200 -> ApiResult.Success(Unit)
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired API key")
                    403 -> ApiResult.Error("CANCEL_NOT_ALLOWED", "Cannot cancel this order")
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Add items to an existing order using ordering API key (staff amendment).
     */
    suspend fun addItemsToOrderAsStaff(orderId: String, items: List<NewOrderItem>): ApiResult<OrderDto> =
        withContext(Dispatchers.IO) {
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")

                val itemsArray = JSONArray()
                items.forEach { item ->
                    itemsArray.put(JSONObject().apply {
                        put("menuItemId", item.menuItemId)
                        put("quantity", item.quantity)
                        if (item.note != null) put("note", item.note)
                    })
                }
                val body = JSONObject().apply {
                    put("items", itemsArray)
                }.toString()

                val request = Request.Builder()
                    .url("$BASE_URL/orders-items/$orderId")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Fetch orders since a timestamp using the ordering API key (staff catch-up sync).
     */
    suspend fun getOrdersSinceAsStaff(since: String): ApiResult<OrdersSyncResponse> =
        withContext(Dispatchers.IO) {
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")

                val request = Request.Builder()
                    .url("$BASE_URL/orders?since=$since")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
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
    suspend fun getSettings(): ApiResult<SettingsResponse> = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder()
                .url("$BASE_URL/settings")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                            businessDayStartHour = json.optInt("businessDayStartHour", 15)
                        )
                    )
                }
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Get café GPS location and radius (ordering API key bearer).
     * Used by staff devices for attendance check-in radius validation.
     */
    suspend fun getCafeLocation(): ApiResult<CafeLocationResponse> =
        withContext(Dispatchers.IO) {
            try {
                val token = orderingBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No ordering API key")

                val request = Request.Builder()
                    .url("$BASE_URL/cafe-location")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        ApiResult.Success(
                            CafeLocationResponse(
                                latitude = json.getDouble("latitude"),
                                longitude = json.getDouble("longitude"),
                                radiusMeters = json.getInt("radiusMeters")
                            )
                        )
                    }
                    401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired API key")
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Post attendance event (CHECK_IN/CHECK_OUT) with GPS coordinates.
     * Uses ordering API key for staff devices.
     */
    suspend fun postAttendance(
        event: String,
        lat: Double,
        lng: Double,
        forced: Boolean = false
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
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
                .url("$BASE_URL/attendance")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()

            when (response.code) {
                200 -> ApiResult.Success(Unit)
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired API key")
                403 -> ApiResult.Error("OUTSIDE_RADIUS", "Device GPS outside café radius")
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // --- Café Location ---

    /**
     * Save café GPS location and radius (admin bearer).
     */
    suspend fun putCafeLocation(
        lat: Double,
        lng: Double,
        radius: Int
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val body = JSONObject().apply {
                put("latitude", lat)
                put("longitude", lng)
                put("radiusMeters", radius)
            }.toString()

            val request = Request.Builder()
                .url("$BASE_URL/cafe-location")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .put(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()

            when (response.code) {
                200 -> ApiResult.Success(Unit)
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
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
    suspend fun getTables(): ApiResult<List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/tables")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code} $responseBody")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Fetch the map of table id → opaque QR token (for generating unguessable table QR
     * codes). Public endpoint. Tables missing a token are simply omitted.
     */
    suspend fun getTableTokens(): ApiResult<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/tables")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
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
    suspend fun putTables(tables: List<Pair<String, String>>): ApiResult<List<String>> =
        withContext(Dispatchers.IO) {
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
                    .url("$BASE_URL/tables")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code} $responseBody")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    // --- Branding ---

    /**
     * Fetch current café branding (public, no auth required). Returns null cafeName
     * when branding isn't configured yet ({"configured": false}).
     */
    suspend fun getBranding(): ApiResult<BrandingResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/branding")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .get()
                .build()

            val response = client.newCall(request).execute()
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
                                logoUrl = json.optString("logoUrl", "")
                            )
                        )
                    }
                }
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Update café branding (name, and optionally a new logo base64 JPEG ≤200KB).
     * Omitting [logoBase64] keeps whatever logo is already stored server-side —
     * e.g. the admin is only renaming the café, not replacing the logo.
     */
    suspend fun putBranding(
        cafeName: String,
        logoBase64: String? = null
    ): ApiResult<BrandingResponse> = withContext(Dispatchers.IO) {
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val body = JSONObject().apply {
                put("cafeName", cafeName)
                if (logoBase64 != null) put("logoBase64", logoBase64)
            }.toString()

            val request = Request.Builder()
                .url("$BASE_URL/branding")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                            logoUrl = json.getString("logoUrl")
                        )
                    )
                }
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // --- Settings ---

    /**
     * Partially update system settings (admin bearer, merge semantics).
     */
    suspend fun putSettings(body: JSONObject): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val request = Request.Builder()
                .url("$BASE_URL/settings")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .put(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()

            when (response.code) {
                200 -> ApiResult.Success(Unit)
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                422 -> ApiResult.Error("VALIDATION", "Invalid settings value")
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // --- Manual Dine-In Order ---

    /**
     * Create a new order (manual dine-in entry with source=STAFF).
     */
    suspend fun createOrder(
        tableId: String,
        items: List<NewOrderItem>,
        source: String = "STAFF"
    ): ApiResult<CreateOrderResponse> = withContext(Dispatchers.IO) {
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val itemsArray = JSONArray()
            items.forEach { item ->
                itemsArray.put(JSONObject().apply {
                    put("menuItemId", item.menuItemId)
                    put("quantity", item.quantity)
                    if (item.note != null) put("note", item.note)
                })
            }
            val body = JSONObject().apply {
                put("tableId", tableId)
                put("items", itemsArray)
            }.toString()

            val request = Request.Builder()
                .url("$BASE_URL/orders")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // --- Menu item images ---

    /**
     * Upload a client-resized menu item thumbnail (JPEG, already cropped/downscaled).
     * Returns the public Storage URL and object path (path is kept so the old image
     * can be deleted once the new one is confirmed saved on the menu item).
     */
    suspend fun uploadMenuImage(menuItemId: String, imageBase64: String): ApiResult<MenuImageUploadResponse> =
        withContext(Dispatchers.IO) {
            try {
                val token = adminBearerToken()
                    ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

                val body = JSONObject().apply {
                    put("menuItemId", menuItemId)
                    put("imageBase64", imageBase64)
                }.toString()

                val request = Request.Builder()
                    .url("$BASE_URL/menu-image")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                    else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /**
     * Delete a superseded menu item image by its Storage path (best-effort cleanup).
     */
    suspend fun deleteMenuImage(path: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = adminBearerToken()
                ?: return@withContext ApiResult.Error("NO_TOKEN", "No admin session token")

            val body = JSONObject().apply {
                put("path", path)
            }.toString()

            val request = Request.Builder()
                .url("$BASE_URL/menu-image")
                .addHeader("Content-Type", "application/json")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .delete(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()

            when (response.code) {
                200 -> ApiResult.Success(Unit)
                401 -> ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /**
     * Fetch current menu state from backend (public, no auth required).
     */
    suspend fun getMenu(): ApiResult<MenuResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/menu")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
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
                else -> ApiResult.Error("UNKNOWN", "Server error: ${response.code}")
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }
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
    val tableId: String,
    val source: String,
    val status: String,
    val paymentMethod: String?,
    val total: Double,
    val sentToKitchenAt: String?,
    val cancelReason: String?,
    val cancelledBy: String?,
    val createdAt: String,
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

data class NewOrderItem(
    val menuItemId: String,
    val quantity: Int,
    val note: String? = null
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
    val logoUrl: String
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
    val businessDayStartHour: Int = 15
)
