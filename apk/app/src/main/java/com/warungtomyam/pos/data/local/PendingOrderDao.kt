package com.warungtomyam.pos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for the offline order queue.
 */
@Dao
interface PendingOrderDao {

    @Query("SELECT * FROM pending_orders ORDER BY createdAt ASC")
    fun getAllFlow(): Flow<List<PendingOrder>>

    @Query("SELECT * FROM pending_orders ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingOrder>

    @Query("SELECT COUNT(*) FROM pending_orders")
    fun getCountFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pendingOrder: PendingOrder)

    @Query("DELETE FROM pending_orders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE pending_orders SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetryCount(id: String)

    @Query("DELETE FROM pending_orders")
    suspend fun deleteAll()
}
