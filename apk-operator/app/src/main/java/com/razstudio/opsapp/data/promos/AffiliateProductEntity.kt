package com.razstudio.opsapp.data.promos

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity caching affiliate product data fetched from the Shopee Affiliate API.
 *
 * Ported from `apk/app`'s `com.razstudio.pos.data.promos.AffiliateProductEntity` (post-bugfix
 * version, includes the `source` tag). Money fields ([price], [originalPrice]) are stored in
 * **sen** (Malaysian cents).
 */
@Entity(tableName = "affiliate_products")
data class AffiliateProductEntity(
    @PrimaryKey val id: String,
    val itemId: Long,
    val productName: String,
    val offerLink: String,
    val imageUrl: String,
    val price: Long,
    val originalPrice: Long,
    val commissionRate: Double,
    val commissionXtra: Double?,
    val shopName: String,
    val isOfficialShop: Boolean,
    val salesCount: Long,
    val rating: Double,
    val subId: String,
    val validationStatus: String,
    val lastFetchedAt: String,
    val impressions: Long = 0,
    val clicks: Long = 0,
    /** Which sync path produced this row: `"SHOPEE_API"` or `"GITHUB_FALLBACK"`. */
    val source: String = "GITHUB_FALLBACK",
)
