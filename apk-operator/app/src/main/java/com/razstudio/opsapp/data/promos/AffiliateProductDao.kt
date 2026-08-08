package com.razstudio.opsapp.data.promos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for affiliate product CRUD operations.
 *
 * Ported from `apk/app`'s `com.razstudio.pos.data.promos.AffiliateProductDao` (post-bugfix
 * version — `getByValidation`/`updateValidation`/`deleteOlderThan` were removed there as dead
 * code; not re-added here).
 */
@Dao
interface AffiliateProductDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<AffiliateProductEntity>)

    @Query("SELECT * FROM affiliate_products ORDER BY commissionRate DESC")
    fun getAll(): Flow<List<AffiliateProductEntity>>

    /**
     * Delete every row whose [AffiliateProductEntity.source] differs from [keepSource]. Called
     * before [insertAll] on a successful sync so switching between the Shopee API path and the
     * GitHub-catalog fallback never leaves stale duplicate rows from the source left behind.
     */
    @Query("DELETE FROM affiliate_products WHERE source != :keepSource")
    suspend fun deleteAllExceptSource(keepSource: String)

    @Query("UPDATE affiliate_products SET impressions = impressions + 1 WHERE id = :id")
    suspend fun incrementImpressions(id: String)

    @Query("UPDATE affiliate_products SET clicks = clicks + 1 WHERE id = :id")
    suspend fun incrementClicks(id: String)
}
