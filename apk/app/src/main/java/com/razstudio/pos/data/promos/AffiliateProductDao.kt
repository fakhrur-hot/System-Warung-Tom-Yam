package com.razstudio.pos.data.promos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for affiliate product CRUD operations.
 *
 * All write operations use `suspend` for structured concurrency. The [getAll] query
 * returns a [Flow] so the UI layer recomposes automatically when the cache is refreshed.
 */
@Dao
interface AffiliateProductDao {

    /** Upsert a batch of products (replace on conflict by primary key). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<AffiliateProductEntity>)

    /** Observe all cached products, ordered by commission rate descending. */
    @Query("SELECT * FROM affiliate_products ORDER BY commissionRate DESC")
    fun getAll(): Flow<List<AffiliateProductEntity>>

    /**
     * Delete every row whose [AffiliateProductEntity.source] differs from [keepSource].
     *
     * Called right before [insertAll] on a successful sync so a café that switches between the
     * Shopee API path and the GitHub-catalog fallback never keeps displaying stale duplicate tiles
     * from whichever source it left.
     */
    @Query("DELETE FROM affiliate_products WHERE source != :keepSource")
    suspend fun deleteAllExceptSource(keepSource: String)

    /** Increment the impression counter for a product (displayed on screen). */
    @Query("UPDATE affiliate_products SET impressions = impressions + 1 WHERE id = :id")
    suspend fun incrementImpressions(id: String)

    /** Increment the click counter for a product (user tapped the tile). */
    @Query("UPDATE affiliate_products SET clicks = clicks + 1 WHERE id = :id")
    suspend fun incrementClicks(id: String)
}
