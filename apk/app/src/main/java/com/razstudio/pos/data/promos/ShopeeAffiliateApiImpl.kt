package com.razstudio.pos.data.promos

import com.razstudio.pos.BuildConfig
import com.razstudio.pos.data.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [ShopeeAffiliateApi] that sends GraphQL queries to the
 * Shopee Affiliate Open API endpoint (`https://open-api.affiliate.shopee.com.my/graphql`).
 *
 * Requests are authenticated using [ShopeeAuthSigner] (plain SHA-256 of
 * `appId + timestamp + payload + secret`, per Shopee's documented Open API auth scheme).
 * Credentials (APP_ID and SECRET) are read from BuildConfig fields injected via
 * `local.properties`. If credentials are missing or blank, all methods return
 * [ApiResult.Error] with code `MISSING_CREDENTIALS` — the module gracefully degrades
 * without crashing.
 */
@Singleton
class ShopeeAffiliateApiImpl @Inject constructor(
    private val signer: ShopeeAuthSigner,
) : ShopeeAffiliateApi {

    companion object {
        private const val GRAPHQL_ENDPOINT = "https://open-api.affiliate.shopee.com.my/graphql"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder().build()

    private val appId: String
        get() = BuildConfig.SHOPEE_APP_ID

    private val appSecret: String
        get() = BuildConfig.SHOPEE_APP_SECRET

    private fun checkCredentials(): ApiResult.Error? {
        if (appId.isBlank() || appSecret.isBlank()) {
            return ApiResult.Error(
                code = "MISSING_CREDENTIALS",
                message = "Shopee API credentials not configured. Set SHOPEE_APP_ID and SHOPEE_APP_SECRET in local.properties.",
            )
        }
        return null
    }

    override suspend fun searchProducts(
        keyword: String,
        limit: Int,
        sortBy: ProductSortType,
        minDiscount: Int,
        country: String,
    ): ApiResult<List<ShopeeProductOffer>> {
        checkCredentials()?.let { return it }

        val sortKey = when (sortBy) {
            ProductSortType.COMMISSION_DESC -> "commission"
            ProductSortType.PRICE_ASC -> "price_asc"
            ProductSortType.PRICE_DESC -> "price_desc"
            ProductSortType.SALES_DESC -> "sales"
            ProductSortType.RELEVANCE -> "relevance"
        }

        val query = """
            {
              "query": "query { productOfferV2(keyword: \"${keyword.escapeJson()}\", sortType: $sortKey, limit: $limit, country: \"$country\") { nodes { itemId productName offerLink imageUrl price originalPrice commissionRate commissionXtra shopName isOfficialShop salesCount rating } } }"
            }
        """.trimIndent()

        return executeGraphQL(query) { responseJson ->
            val nodes = responseJson
                .optJSONObject("data")
                ?.optJSONObject("productOfferV2")
                ?.optJSONArray("nodes")
                ?: JSONArray()

            val products = mutableListOf<ShopeeProductOffer>()
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                val offer = parseProductOffer(node)
                // Apply minDiscount filter client-side
                if (minDiscount > 0) {
                    val discount = if (offer.originalPrice > 0) {
                        ((offer.originalPrice - offer.price) * 100 / offer.originalPrice).toInt()
                    } else 0
                    if (discount >= minDiscount) products.add(offer)
                } else {
                    products.add(offer)
                }
            }
            ApiResult.Success(products)
        }
    }

    override suspend fun generateShortLink(
        productUrl: String,
        subIds: List<String>,
    ): ApiResult<String> {
        checkCredentials()?.let { return it }

        val subIdParam = subIds.joinToString(",") { "\"${it.escapeJson()}\"" }
        val query = """
            {
              "query": "mutation { generateShortLink(input: { url: \"${productUrl.escapeJson()}\", subIds: [$subIdParam] }) { shortLink } }"
            }
        """.trimIndent()

        return executeGraphQL(query) { responseJson ->
            val shortLink = responseJson
                .optJSONObject("data")
                ?.optJSONObject("generateShortLink")
                ?.optString("shortLink", "")
                ?: ""

            if (shortLink.isNotBlank()) {
                ApiResult.Success(shortLink)
            } else {
                ApiResult.Error("EMPTY_RESPONSE", "Short link generation returned empty result.")
            }
        }
    }

    override suspend fun getProductDetails(itemId: Long): ApiResult<ShopeeProductOffer> {
        checkCredentials()?.let { return it }

        val query = """
            {
              "query": "query { productOfferV2(itemId: $itemId) { nodes { itemId productName offerLink imageUrl price originalPrice commissionRate commissionXtra shopName isOfficialShop salesCount rating } } }"
            }
        """.trimIndent()

        return executeGraphQL(query) { responseJson ->
            val nodes = responseJson
                .optJSONObject("data")
                ?.optJSONObject("productOfferV2")
                ?.optJSONArray("nodes")

            if (nodes != null && nodes.length() > 0) {
                ApiResult.Success(parseProductOffer(nodes.getJSONObject(0)))
            } else {
                ApiResult.Error("NOT_FOUND", "Product with itemId=$itemId not found.")
            }
        }
    }

    override suspend fun getCommissionInfo(itemIds: List<Long>): ApiResult<List<CommissionInfo>> {
        checkCredentials()?.let { return it }

        val idsParam = itemIds.joinToString(",")
        val query = """
            {
              "query": "query { commissionInfo(itemIds: [$idsParam]) { nodes { itemId baseRate xtraRate campaignName expiresAt } } }"
            }
        """.trimIndent()

        return executeGraphQL(query) { responseJson ->
            val nodes = responseJson
                .optJSONObject("data")
                ?.optJSONObject("commissionInfo")
                ?.optJSONArray("nodes")
                ?: JSONArray()

            val infos = mutableListOf<CommissionInfo>()
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                infos.add(
                    CommissionInfo(
                        itemId = node.optLong("itemId"),
                        baseRate = node.optDouble("baseRate", 0.0),
                        xtraRate = if (node.has("xtraRate") && !node.isNull("xtraRate")) {
                            node.optDouble("xtraRate")
                        } else null,
                        campaignName = if (node.has("campaignName") && !node.isNull("campaignName")) {
                            node.optString("campaignName")
                        } else null,
                        expiresAt = if (node.has("expiresAt") && !node.isNull("expiresAt")) {
                            node.optString("expiresAt")
                        } else null,
                    ),
                )
            }
            ApiResult.Success(infos)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────

    /**
     * Executes a signed GraphQL POST to the Shopee endpoint and maps the JSON response
     * using the supplied [parseResponse] lambda.
     */
    private suspend fun <T> executeGraphQL(
        payload: String,
        parseResponse: (JSONObject) -> ApiResult<T>,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis() / 1000
            val headers = signer.buildHeaders(appId, appSecret, payload, timestamp)

            val requestBuilder = Request.Builder()
                .url(GRAPHQL_ENDPOINT)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))

            headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error(
                    code = "HTTP_${response.code}",
                    message = "Shopee API returned ${response.code}: $body",
                )
            }

            val json = JSONObject(body)

            // Check for GraphQL-level errors
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val firstError = errors.getJSONObject(0)
                return@withContext ApiResult.Error(
                    code = "GRAPHQL_ERROR",
                    message = firstError.optString("message", "Unknown GraphQL error"),
                )
            }

            parseResponse(json)
        } catch (e: Exception) {
            ApiResult.Error(
                code = "NETWORK_ERROR",
                message = e.message ?: "Unknown network error",
            )
        }
    }

    private fun parseProductOffer(node: JSONObject): ShopeeProductOffer = ShopeeProductOffer(
        itemId = node.optLong("itemId"),
        productName = node.optString("productName", ""),
        offerLink = node.optString("offerLink", ""),
        imageUrl = node.optString("imageUrl", ""),
        price = node.optLong("price"),
        originalPrice = node.optLong("originalPrice"),
        commissionRate = node.optDouble("commissionRate", 0.0),
        commissionXtra = if (node.has("commissionXtra") && !node.isNull("commissionXtra")) {
            node.optDouble("commissionXtra")
        } else null,
        shopName = node.optString("shopName", ""),
        isOfficialShop = node.optBoolean("isOfficialShop", false),
        salesCount = node.optLong("salesCount"),
        rating = node.optDouble("rating", 0.0),
    )

    /** Escapes double quotes and backslashes for safe embedding in JSON string literals. */
    private fun String.escapeJson(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
