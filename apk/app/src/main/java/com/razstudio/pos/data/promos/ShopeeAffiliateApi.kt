package com.razstudio.pos.data.promos

import com.razstudio.pos.data.ApiResult

/**
 * Shopee Affiliate API service for fetching product offers, generating short links,
 * and retrieving commission information.
 *
 * All calls target the Shopee Affiliate Open API GraphQL endpoint (`product_offer_v2`) and are
 * signed via [ShopeeAuthSigner] (plain SHA-256, per Shopee's documented auth scheme — not HMAC).
 * The [country] parameter is hard-coded to `"MY"`
 * (Shopee Malaysia only) — never configurable by operators in production.
 */
interface ShopeeAffiliateApi {

    /**
     * Search products by keyword, sorted by the given [sortBy] strategy.
     *
     * @param keyword Search term (e.g. "milo", "vacuum cleaner").
     * @param limit Maximum results to return (1–50).
     * @param sortBy Sort order applied to the results.
     * @param minDiscount Minimum discount percentage (0–100). Products below this are excluded.
     * @param country Locked to "MY" (Shopee Malaysia). Do not override in production.
     */
    suspend fun searchProducts(
        keyword: String,
        limit: Int = 50,
        sortBy: ProductSortType = ProductSortType.COMMISSION_DESC,
        minDiscount: Int = 0,
        country: String = "MY",
    ): ApiResult<List<ShopeeProductOffer>>

    /**
     * Generate a short affiliate link for a product URL.
     *
     * @param productUrl The full Shopee product URL to shorten.
     * @param subIds Tracking sub-IDs for analytics segmentation (e.g. café slug + surface).
     * @return The shortened `s.shopee.com.my` link, or an error.
     */
    suspend fun generateShortLink(productUrl: String, subIds: List<String>): ApiResult<String>

    /**
     * Fetch detailed product information by item ID.
     *
     * @param itemId Shopee's unique item identifier.
     */
    suspend fun getProductDetails(itemId: Long): ApiResult<ShopeeProductOffer>

    /**
     * Get commission info (base + XTRA rates) for a batch of products.
     *
     * @param itemIds List of Shopee item IDs to query.
     */
    suspend fun getCommissionInfo(itemIds: List<Long>): ApiResult<List<CommissionInfo>>
}

/**
 * Sort strategies for Shopee product search results.
 */
enum class ProductSortType {
    /** Highest commission rate first — default for maximizing affiliate earnings. */
    COMMISSION_DESC,
    /** Lowest price first. */
    PRICE_ASC,
    /** Highest price first. */
    PRICE_DESC,
    /** Most sales first. */
    SALES_DESC,
    /** Shopee's default relevance ranking. */
    RELEVANCE,
}
