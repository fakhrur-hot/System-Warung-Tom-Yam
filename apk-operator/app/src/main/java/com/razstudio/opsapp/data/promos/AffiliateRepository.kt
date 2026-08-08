package com.razstudio.opsapp.data.promos

import com.razstudio.opsapp.data.ApiResult
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Result of a sync operation against the Shopee Affiliate API. */
sealed class SyncResult {
    data class Success(val productCount: Int) : SyncResult()
    data class Failure(val message: String) : SyncResult()
}

/**
 * Mediates between [ShopeeAffiliateApi] and [AffiliateProductDao].
 *
 * Ported from `apk/app`'s `com.razstudio.pos.data.promos.AffiliateRepository` (post-bugfix
 * version — real `subId` provider, source tagging + cross-source pruning; see
 * .kiro/specs/shopee-affiliate-ads-apk/bugfix). Serves products from Room first; API refresh
 * happens via [syncNow]. On API failure the repository falls back to stale Room data; on first
 * launch with no cache it returns an empty list.
 */
@Singleton
class AffiliateRepository @Inject constructor(
    private val api: ShopeeAffiliateApi,
    private val dao: AffiliateProductDao,
    private val catalogFetcher: AffiliateCatalogFetcher,
    private val subIdProvider: AffiliateSubIdProvider,
) {
    companion object {
        private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000

        private const val SOURCE_SHOPEE_API = "SHOPEE_API"
        private const val SOURCE_GITHUB_FALLBACK = "GITHUB_FALLBACK"
        private const val SYNC_SURFACE = "catalog"
    }

    @Volatile
    private var lastSyncTimestamp: Long = 0L

    fun getProducts(): Flow<List<AffiliateProductEntity>> = dao.getAll()

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

    /** Fallback: fetch affiliate products from the GitHub catalog (Phase 1 behavior). */
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

    fun isCacheStale(): Boolean {
        if (lastSyncTimestamp == 0L) return true
        return (System.currentTimeMillis() - lastSyncTimestamp) > CACHE_TTL_MS
    }

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

    suspend fun recordImpression(productId: String) {
        if (productId.isNotBlank()) dao.incrementImpressions(productId)
    }

    suspend fun recordClick(productId: String) {
        if (productId.isNotBlank()) dao.incrementClicks(productId)
    }
}
