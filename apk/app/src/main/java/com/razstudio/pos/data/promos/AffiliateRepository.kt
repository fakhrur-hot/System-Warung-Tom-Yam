package com.razstudio.pos.data.promos

import com.razstudio.pos.data.ApiResult
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a sync operation against the Shopee Affiliate API.
 */
sealed class SyncResult {
    data class Success(val productCount: Int) : SyncResult()
    data class Failure(val message: String) : SyncResult()
}

/**
 * Mediates between [ShopeeAffiliateApi] and [AffiliateProductDao].
 *
 * Serves products from Room first (the Flow auto-updates on DB changes). API refresh
 * happens via [syncNow] — either triggered by WorkManager or manually from a debug panel.
 * On API failure the repository gracefully falls back to stale Room data; on first launch
 * with no cache it returns an empty list (the Flow emits an empty list from Room).
 */
@Singleton
class AffiliateRepository @Inject constructor(
    private val api: ShopeeAffiliateApi,
    private val dao: AffiliateProductDao,
    private val catalogFetcher: AffiliateCatalogFetcher,
    private val subIdProvider: AffiliateSubIdProvider,
) {
    companion object {
        /** Cache TTL in milliseconds — 6 hours. */
        private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000

        private const val SOURCE_SHOPEE_API = "SHOPEE_API"
        private const val SOURCE_GITHUB_FALLBACK = "GITHUB_FALLBACK"

        /** Every sync currently feeds the same shared product row (table grid + dashboard + ambient
         * all read from one cache) — "tableview" names the primary surface for sub_id purposes. */
        private const val SYNC_SURFACE = "tableview"
    }

    /**
     * Epoch millis of the last successful sync. Process-lifetime only — not persisted.
     * WorkManager handles periodic refresh so persistence is unnecessary.
     */
    @Volatile
    private var lastSyncTimestamp: Long = 0L

    /**
     * Observe all cached affiliate products, ordered by commission rate descending.
     *
     * Returns Room's Flow directly — the UI recomposes automatically when the cache
     * is refreshed by [syncNow]. On first launch with no cache, emits an empty list.
     */
    fun getProducts(): Flow<List<AffiliateProductEntity>> = dao.getAll()

    /**
     * Force refresh from the Shopee Affiliate API and store results in Room.
     *
     * 1. Fetches popular products sorted by commission.
     * 2. Maps API response to Room entities.
     * 3. Upserts into Room (replacing stale rows via primary key).
     * 4. Updates [lastSyncTimestamp].
     *
     * On API failure, returns [SyncResult.Failure] — stale Room data continues to be served
     * via [getProducts].
     */
    suspend fun syncNow(): SyncResult {
        return when (val result = api.searchProducts(
            keyword = "popular",
            limit = 50,
            sortBy = ProductSortType.COMMISSION_DESC,
            country = "MY",
        )) {
            is ApiResult.Success -> {
                val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
                val subId = subIdProvider.forSurface(SYNC_SURFACE)

                val entities = result.data.map { it.toEntity(subId, now) }
                dao.deleteAllExceptSource(SOURCE_SHOPEE_API)
                dao.insertAll(entities)
                lastSyncTimestamp = System.currentTimeMillis()

                SyncResult.Success(productCount = entities.size)
            }

            is ApiResult.Error -> {
                // Fall back to GitHub catalog when Shopee credentials are missing
                if (result.code == "MISSING_CREDENTIALS") {
                    return syncFromGitHubCatalog()
                }
                SyncResult.Failure(message = result.message)
            }

            is ApiResult.NetworkError -> {
                SyncResult.Failure(message = result.message)
            }
        }
    }

    /**
     * Fallback: fetch affiliate products from the GitHub catalog (Phase 1 behavior).
     *
     * Used when Shopee API credentials are not configured. Fetches the catalog from
     * the raw GitHub URL, converts [PromoProduct] entries to [AffiliateProductEntity],
     * and stores them in Room just like the API path does.
     */
    private suspend fun syncFromGitHubCatalog(): SyncResult {
        val products = catalogFetcher.fetch()
        if (products.isEmpty()) {
            return SyncResult.Failure(message = "GitHub catalog returned no products.")
        }

        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
        val subId = subIdProvider.forSurface(SYNC_SURFACE)

        val entities = products.mapIndexed { index, product ->
            product.toEntity(subId, now, index)
        }
        dao.deleteAllExceptSource(SOURCE_GITHUB_FALLBACK)
        dao.insertAll(entities)
        lastSyncTimestamp = System.currentTimeMillis()

        return SyncResult.Success(productCount = entities.size)
    }

    /**
     * Returns true if the cache is stale (older than 6 hours) or has never been synced.
     */
    fun isCacheStale(): Boolean {
        if (lastSyncTimestamp == 0L) return true
        return (System.currentTimeMillis() - lastSyncTimestamp) > CACHE_TTL_MS
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Mapping
    // ──────────────────────────────────────────────────────────────────────────

    private fun ShopeeProductOffer.toEntity(subId: String, now: String): AffiliateProductEntity =
        AffiliateProductEntity(
            id = itemId.toString(),
            itemId = itemId,
            productName = productName,
            offerLink = offerLink,
            imageUrl = imageUrl,
            price = price,
            originalPrice = originalPrice,
            commissionRate = commissionRate,
            commissionXtra = commissionXtra,
            shopName = shopName,
            isOfficialShop = isOfficialShop,
            salesCount = salesCount,
            rating = rating,
            subId = subId,
            validationStatus = "UNCHECKED",
            lastFetchedAt = now,
            source = SOURCE_SHOPEE_API,
        )

    /**
     * Maps a [PromoProduct] from the GitHub catalog into a Room entity.
     *
     * Since GitHub catalog products don't carry Shopee metadata (price, commission, etc.),
     * those fields are zeroed out. The essential fields for display (offerLink, imageUrl,
     * productName) are populated from the catalog entry.
     */
    private fun PromoProduct.toEntity(subId: String, now: String, index: Int): AffiliateProductEntity =
        AffiliateProductEntity(
            id = "github_$index",
            itemId = index.toLong(),
            productName = alt.ifBlank { "Shopee pick" },
            offerLink = href,
            imageUrl = img,
            price = 0L,
            originalPrice = 0L,
            commissionRate = 0.0,
            commissionXtra = null,
            shopName = "",
            isOfficialShop = false,
            salesCount = 0L,
            rating = 0.0,
            subId = subId,
            validationStatus = "UNCHECKED",
            lastFetchedAt = now,
            source = SOURCE_GITHUB_FALLBACK,
        )

    /** Attach an impression to the row this display product was read from. No-op for a blank id. */
    suspend fun recordImpression(productId: String) {
        if (productId.isNotBlank()) dao.incrementImpressions(productId)
    }

    /** Attach a click to the row this display product was read from. No-op for a blank id. */
    suspend fun recordClick(productId: String) {
        if (productId.isNotBlank()) dao.incrementClicks(productId)
    }
}
