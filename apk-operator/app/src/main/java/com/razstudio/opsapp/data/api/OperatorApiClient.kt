package com.razstudio.opsapp.data.api

import com.razstudio.opsapp.data.ApiResult
import com.razstudio.opsapp.data.local.ConnectedCafeEntity
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

/**
 * Thin OkHttp client scoped to ONE café's Edge Functions, given its stored credential.
 *
 * Exposes only the five endpoint families the OPERATOR role is authorized for:
 *   menu, menu-image, tables, cafe-location, branding
 *
 * Deliberately has NO methods for orders, payments, devices, reports, settings,
 * attendance, aggregates, sessions, gateway, rotating-key, admin-recovery, or
 * admin-handshake. This is a compile-time guarantee: if `OperatorApiClient` doesn't
 * declare the method, the shell UI cannot call it by accident.
 *
 * On 401, emits an [AccessRevocationEvent] via [revocationManager] so the UI can
 * surface "Access revoked for this café" and offer disconnect (Requirement 6.2).
 */
class OperatorApiClient(
    private val cafe: ConnectedCafeEntity,
    private val revocationManager: AccessRevocationManager? = null,
) {
    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Base URL for this café's Edge Functions: `{supabaseUrl}/functions/v1` */
    private val baseUrl: String
        get() = cafe.supabaseUrl.trimEnd('/') + "/functions/v1"

    // ── Menu ────────────────────────────────────────────────────────────────────

    /** Fetch the full menu snapshot (items + categories). */
    suspend fun getMenu(): ApiResult<MenuResponse> = withContext(Dispatchers.IO) {
        try {
            val request = buildGet("/menu")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(body)
                    if (!json.optBoolean("configured", true)) {
                        ApiResult.Success(MenuResponse(configured = false, items = emptyList()))
                    } else {
                        val items = parseMenuItems(json.optJSONArray("items") ?: JSONArray())
                        val categories = parseCategories(json.optJSONArray("categories") ?: JSONArray())
                        ApiResult.Success(MenuResponse(configured = true, items = items, categories = categories))
                    }
                }
                401 -> unauthorizedError()
                else -> unexpectedError(response.code)
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /** Push a full menu snapshot (all items + categories). */
    suspend fun upsertMenuItem(items: List<MenuItemDto>, categories: List<MenuCategoryDto>): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val itemsArray = JSONArray().apply {
                    items.forEach { item -> put(menuItemToJson(item)) }
                }
                val catsArray = JSONArray().apply {
                    categories.forEach { cat ->
                        put(JSONObject().apply {
                            put("name", cat.name)
                            put("sortOrder", cat.sortOrder)
                            if (cat.nameI18n.isNotEmpty()) {
                                put("nameI18n", JSONObject(cat.nameI18n))
                            }
                        })
                    }
                }
                val body = JSONObject().apply {
                    put("items", itemsArray)
                    put("categories", catsArray)
                }.toString()

                val request = buildPut("/menu", body)
                val response = client.newCall(request).execute()
                response.body?.close()

                when (response.code) {
                    200 -> ApiResult.Success(Unit)
                    401 -> unauthorizedError()
                    else -> unexpectedError(response.code)
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /** Delete a menu item by ID (sends a DELETE to /menu with the item id). */
    suspend fun deleteMenuItem(menuItemId: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("menuItemId", menuItemId)
            }.toString()

            val request = buildDelete("/menu", body)
            val response = client.newCall(request).execute()
            response.body?.close()

            when (response.code) {
                200 -> ApiResult.Success(Unit)
                401 -> unauthorizedError()
                else -> unexpectedError(response.code)
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // ── Menu Image ──────────────────────────────────────────────────────────────

    /**
     * Upload a menu item image (base64-encoded JPEG).
     * Returns the public image URL and the storage path for later cleanup.
     */
    suspend fun uploadMenuImage(menuItemId: String, imageBase64: String): ApiResult<MenuImageUploadResult> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("menuItemId", menuItemId)
                    put("imageBase64", imageBase64)
                }.toString()

                val request = buildPost("/menu-image", body)
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        ApiResult.Success(
                            MenuImageUploadResult(
                                imageUrl = json.getString("imageUrl"),
                                path = json.getString("path"),
                            )
                        )
                    }
                    401 -> unauthorizedError()
                    else -> unexpectedError(response.code)
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
            }
        }

    /** Delete a menu item image by its storage path (best-effort cleanup). */
    suspend fun deleteMenuImage(path: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("path", path)
            }.toString()

            val request = buildDelete("/menu-image", body)
            val response = client.newCall(request).execute()
            response.body?.close()

            when (response.code) {
                200 -> ApiResult.Success(Unit)
                401 -> unauthorizedError()
                else -> unexpectedError(response.code)
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // ── Tables ──────────────────────────────────────────────────────────────────

    /** Fetch the current table registry. */
    suspend fun getTables(): ApiResult<List<TableDto>> = withContext(Dispatchers.IO) {
        try {
            val request = buildGet("/tables")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(body)
                    val tablesArray = json.optJSONArray("tables") ?: JSONArray()
                    val tables = (0 until tablesArray.length()).map { i ->
                        val t = tablesArray.getJSONObject(i)
                        TableDto(
                            id = t.getString("id"),
                            displayName = t.getString("displayName"),
                            qrToken = if (t.has("qrToken") && !t.isNull("qrToken")) t.getString("qrToken") else null,
                        )
                    }
                    ApiResult.Success(tables)
                }
                401 -> unauthorizedError()
                else -> unexpectedError(response.code)
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /** Push the full table registry (PUT, replaces backend state). */
    suspend fun upsertTable(tables: List<TableDto>): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val tablesArray = JSONArray().apply {
                tables.forEach { table ->
                    put(JSONObject().apply {
                        put("id", table.id)
                        put("displayName", table.displayName)
                    })
                }
            }
            val body = JSONObject().apply {
                put("tables", tablesArray)
            }.toString()

            val request = buildPut("/tables", body)
            val response = client.newCall(request).execute()
            response.body?.close()

            when (response.code) {
                200 -> ApiResult.Success(Unit)
                401 -> unauthorizedError()
                else -> unexpectedError(response.code)
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /** Delete a table by its ID. */
    suspend fun deleteTable(tableId: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("tableId", tableId)
            }.toString()

            val request = buildDelete("/tables", body)
            val response = client.newCall(request).execute()
            response.body?.close()

            when (response.code) {
                200 -> ApiResult.Success(Unit)
                401 -> unauthorizedError()
                else -> unexpectedError(response.code)
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // ── Café Location ───────────────────────────────────────────────────────────

    /** Fetch the café's GPS location and geofence radius. */
    suspend fun getCafeLocation(): ApiResult<CafeLocationDto> = withContext(Dispatchers.IO) {
        try {
            val request = buildGet("/cafe-location")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(body)
                    if (json.isNull("latitude") || json.isNull("longitude")) {
                        ApiResult.Error("NOT_CONFIGURED", "Location not set")
                    } else {
                        ApiResult.Success(
                            CafeLocationDto(
                                latitude = json.getDouble("latitude"),
                                longitude = json.getDouble("longitude"),
                                radiusMeters = if (json.isNull("radiusMeters")) 100 else json.getInt("radiusMeters"),
                            )
                        )
                    }
                }
                401 -> unauthorizedError()
                else -> unexpectedError(response.code)
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /** Save/update the café's GPS location and geofence radius. */
    suspend fun updateCafeLocation(location: CafeLocationDto): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("radiusMeters", location.radiusMeters)
            }.toString()

            val request = buildPut("/cafe-location", body)
            val response = client.newCall(request).execute()
            response.body?.close()

            when (response.code) {
                200 -> ApiResult.Success(Unit)
                401 -> unauthorizedError()
                else -> unexpectedError(response.code)
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // ── Branding ────────────────────────────────────────────────────────────────

    /** Fetch the café's branding (name + logo URL). */
    suspend fun getBranding(): ApiResult<BrandingDto> = withContext(Dispatchers.IO) {
        try {
            val request = buildGet("/branding")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(body)
                    if (!json.optBoolean("configured", true)) {
                        ApiResult.Success(BrandingDto(cafeName = "", logoUrl = ""))
                    } else {
                        ApiResult.Success(
                            BrandingDto(
                                cafeName = json.optString("cafeName", ""),
                                logoUrl = json.optString("logoUrl", ""),
                                paymentQrHash = if (json.isNull("paymentQrHash")) null else json.optString("paymentQrHash", ""),
                                paymentQrUrl = if (json.isNull("paymentQrUrl")) null else json.optString("paymentQrUrl", ""),
                            )
                        )
                    }
                }
                401 -> unauthorizedError()
                else -> unexpectedError(response.code)
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    /** Update café branding (name, and optionally a new logo as base64 JPEG). */
    suspend fun updateBranding(branding: BrandingDto): ApiResult<BrandingDto> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("cafeName", branding.cafeName)
                if (branding.logoBase64 != null) put("logoBase64", branding.logoBase64)
            }.toString()

            val request = buildPut("/branding", body)
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    ApiResult.Success(
                        BrandingDto(
                            cafeName = json.getString("cafeName"),
                            logoUrl = json.getString("logoUrl"),
                            paymentQrHash = if (json.isNull("paymentQrHash")) null else json.optString("paymentQrHash", ""),
                            paymentQrUrl = if (json.isNull("paymentQrUrl")) null else json.optString("paymentQrUrl", ""),
                        )
                    )
                }
                401 -> unauthorizedError()
                else -> unexpectedError(response.code)
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error("PARSE_ERROR", e.message ?: "Unexpected error")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun buildGet(path: String): Request =
        Request.Builder()
            .url("$baseUrl$path")
            .addHeader("apikey", cafe.supabaseAnonKey)
            .addHeader("Authorization", "Bearer ${cafe.sessionToken}")
            .get()
            .build()

    private fun buildPost(path: String, body: String): Request =
        Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Content-Type", "application/json")
            .addHeader("apikey", cafe.supabaseAnonKey)
            .addHeader("Authorization", "Bearer ${cafe.sessionToken}")
            .post(body.toRequestBody(JSON))
            .build()

    private fun buildPut(path: String, body: String): Request =
        Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Content-Type", "application/json")
            .addHeader("apikey", cafe.supabaseAnonKey)
            .addHeader("Authorization", "Bearer ${cafe.sessionToken}")
            .put(body.toRequestBody(JSON))
            .build()

    private fun buildDelete(path: String, body: String): Request =
        Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Content-Type", "application/json")
            .addHeader("apikey", cafe.supabaseAnonKey)
            .addHeader("Authorization", "Bearer ${cafe.sessionToken}")
            .delete(body.toRequestBody(JSON))
            .build()

    private fun <T> unauthorizedError(): ApiResult<T> {
        revocationManager?.notifyRevoked(cafeId = cafe.id, cafeName = cafe.cafeName)
        return ApiResult.Error("UNAUTHORIZED", "Invalid or expired token")
    }

    private fun <T> unexpectedError(code: Int): ApiResult<T> =
        ApiResult.Error("HTTP_$code", "Unexpected status code: $code")

    // ── JSON parsing ────────────────────────────────────────────────────────────

    private fun parseMenuItems(array: JSONArray): List<MenuItemDto> =
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val nameObj = item.optJSONObject("name")
            MenuItemDto(
                id = item.getString("id"),
                category = item.getString("category"),
                extraCategories = item.optString("extraCategories", ""),
                code = item.optString("code", ""),
                price = item.getDouble("price"),
                marketPrice = item.optBoolean("marketPrice", false),
                available = item.optBoolean("available", true),
                askMeDaily = item.optBoolean("askMeDaily", false),
                imageUrl = item.optString("imageUrl", ""),
                hasVariablePrice = item.optBoolean("hasVariablePrice", false),
                variablePriceDailyPrompt = item.optBoolean("variablePriceDailyPrompt", false),
                priceOption1 = if (item.has("priceOption1") && !item.isNull("priceOption1")) item.getDouble("priceOption1") else null,
                priceOption2 = if (item.has("priceOption2") && !item.isNull("priceOption2")) item.getDouble("priceOption2") else null,
                priceOption3 = if (item.has("priceOption3") && !item.isNull("priceOption3")) item.getDouble("priceOption3") else null,
                nameEn = nameObj?.optString("en", "") ?: item.optString("nameEn", ""),
                nameBm = nameObj?.optString("bm", "") ?: item.optString("nameBm", ""),
                nameZh = nameObj?.optString("zh", "") ?: item.optString("nameZh", ""),
                nameTa = nameObj?.optString("ta", "") ?: item.optString("nameTa", ""),
                nameTh = nameObj?.optString("th", "") ?: item.optString("nameTh", ""),
                doNotTranslate = item.optBoolean("doNotTranslate", false),
            )
        }

    private fun parseCategories(array: JSONArray): List<MenuCategoryDto> =
        (0 until array.length()).map { i ->
            val cat = array.getJSONObject(i)
            val nameI18n = mutableMapOf<String, String>()
            val nameI18nObj = cat.optJSONObject("nameI18n")
            if (nameI18nObj != null) {
                nameI18nObj.keys().forEach { key -> nameI18n[key] = nameI18nObj.optString(key, "") }
            }
            MenuCategoryDto(
                name = cat.getString("name"),
                sortOrder = cat.optInt("sortOrder", i),
                nameI18n = nameI18n,
            )
        }

    private fun menuItemToJson(item: MenuItemDto): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("category", item.category)
        if (item.extraCategories.isNotEmpty()) put("extraCategories", item.extraCategories)
        if (item.code.isNotEmpty()) put("code", item.code)
        put("price", item.price)
        put("marketPrice", item.marketPrice)
        put("available", item.available)
        put("askMeDaily", item.askMeDaily)
        if (item.imageUrl.isNotEmpty()) put("imageUrl", item.imageUrl)
        put("hasVariablePrice", item.hasVariablePrice)
        put("variablePriceDailyPrompt", item.variablePriceDailyPrompt)
        if (item.priceOption1 != null) put("priceOption1", item.priceOption1)
        if (item.priceOption2 != null) put("priceOption2", item.priceOption2)
        if (item.priceOption3 != null) put("priceOption3", item.priceOption3)
        put("name", JSONObject().apply {
            put("en", item.nameEn)
            put("bm", item.nameBm)
            put("zh", item.nameZh)
            put("ta", item.nameTa)
            put("th", item.nameTh)
        })
        put("doNotTranslate", item.doNotTranslate)
    }
}

/** Result from a menu image upload. */
data class MenuImageUploadResult(
    val imageUrl: String,
    val path: String,
)
