package com.razstudio.opsapp.data.promos

import com.razstudio.opsapp.data.ApiResult

/**
 * Shopee Affiliate API service for fetching product offers, generating short links,
 * and retrieving commission information.
 *
 * Ported from `apk/app`'s `com.razstudio.pos.data.promos.ShopeeAffiliateApi` (post-bugfix version).
 * All calls target the Shopee Affiliate Open API GraphQL endpoint (`product_offer_v2`) and are
 * signed via [ShopeeAuthSigner] (plain SHA-256, per Shopee's documented auth scheme — not HMAC).
 * The [country] parameter is hard-coded to `"MY"` (Shopee Malaysia only).
 */
interface ShopeeAffiliateApi {

    suspend fun searchProducts(
        keyword: String,
        limit: Int = 50,
        sortBy: ProductSortType = ProductSortType.COMMISSION_DESC,
        minDiscount: Int = 0,
        country: String = "MY",
    ): ApiResult<List<ShopeeProductOffer>>

    suspend fun generateShortLink(productUrl: String, subIds: List<String>): ApiResult<String>

    suspend fun getProductDetails(itemId: Long): ApiResult<ShopeeProductOffer>

    suspend fun getCommissionInfo(itemIds: List<Long>): ApiResult<List<CommissionInfo>>
}

enum class ProductSortType {
    COMMISSION_DESC,
    PRICE_ASC,
    PRICE_DESC,
    SALES_DESC,
    RELEVANCE,
}
