package com.razstudio.pos.data.promos

/**
 * Raw product offer from the Shopee Affiliate API (`product_offer_v2`).
 *
 * Represents a single product listing as returned by Shopee's GraphQL endpoint,
 * before any local filtering, validation, or link processing is applied.
 *
 * Money fields ([price], [originalPrice]) are in **sen** (Malaysian cents) to avoid
 * floating-point issues — same convention used throughout the POS app.
 */
data class ShopeeProductOffer(
    /** Shopee's unique item identifier. */
    val itemId: Long,
    /**
     * Stable identifier for impression/click tracking — the Room row id this offer was read from.
     * Defaults to [itemId]'s string form so call sites building a fresh offer straight from an API
     * response (no Room row yet) don't need to set it explicitly.
     */
    val id: String = itemId.toString(),
    /** Product display name from the seller's listing. */
    val productName: String,
    /** Raw affiliate offer link from Shopee (may need sub_id appending). */
    val offerLink: String,
    /** Product image URL from the listing. */
    val imageUrl: String,
    /** Current price in sen (Malaysian cents). */
    val price: Long,
    /** Original price in sen, before any discount — used for discount calculation. */
    val originalPrice: Long,
    /** Base commission rate as a decimal (e.g. 0.08 = 8%). */
    val commissionRate: Double,
    /** Extra commission rate from XTRA campaigns, if active. Null when no XTRA applies. */
    val commissionXtra: Double?,
    /** Name of the Shopee seller/shop. */
    val shopName: String,
    /** Whether the seller is a Shopee Official Shop (higher trust signal). */
    val isOfficialShop: Boolean,
    /** Total sales count for this listing. */
    val salesCount: Long,
    /** Average product rating (0.0–5.0 scale). */
    val rating: Double,
)
