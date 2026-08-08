package com.razstudio.pos.data.promos

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity caching affiliate product data fetched from the Shopee Affiliate API.
 *
 * Each row represents a single product listing that has been processed (link validated,
 * sub_id appended) and is ready to be displayed on the table grid or ambient display.
 *
 * Money fields ([price], [originalPrice]) are stored in **sen** (Malaysian cents).
 */
@Entity(tableName = "affiliate_products")
data class AffiliateProductEntity(
    /** Primary key — itemId as string for stable Room identity. */
    @PrimaryKey val id: String,
    /** Shopee's unique item identifier. */
    val itemId: Long,
    /** Product display name from the seller's listing. */
    val productName: String,
    /** Final affiliate offer link (with sub_id appended). */
    val offerLink: String,
    /** Product image URL from the listing. */
    val imageUrl: String,
    /** Current price in sen. */
    val price: Long,
    /** Original price in sen, before any discount. */
    val originalPrice: Long,
    /** Base commission rate as a decimal (e.g. 0.08 = 8%). */
    val commissionRate: Double,
    /** Extra commission rate from XTRA campaigns. Null when no XTRA applies. */
    val commissionXtra: Double?,
    /** Name of the Shopee seller/shop. */
    val shopName: String,
    /** Whether the seller is a Shopee Official Shop. */
    val isOfficialShop: Boolean,
    /** Total sales count for this listing. */
    val salesCount: Long,
    /** Average product rating (0.0–5.0 scale). */
    val rating: Double,
    /** Sub-ID used for commission attribution analytics. */
    val subId: String,
    /** Link validation status: VALID, BROKEN, or UNCHECKED. */
    val validationStatus: String,
    /** ISO-8601 timestamp of the last successful fetch from Shopee API. */
    val lastFetchedAt: String,
    /** Number of times this product tile has been shown. */
    val impressions: Long = 0,
    /** Number of times a user tapped/clicked this product tile. */
    val clicks: Long = 0,
    /**
     * Which sync path produced this row: `"SHOPEE_API"` or `"GITHUB_FALLBACK"`. Lets
     * [AffiliateRepository] prune the other source's rows on a successful sync instead of
     * accumulating stale duplicates from a source the café is no longer using.
     */
    val source: String = "GITHUB_FALLBACK",
)
