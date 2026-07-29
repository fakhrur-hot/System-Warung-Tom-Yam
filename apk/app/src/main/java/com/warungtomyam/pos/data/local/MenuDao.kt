package com.warungtomyam.pos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for menu items.
 */
@Dao
interface MenuDao {

    @Query("SELECT * FROM menu_items")
    suspend fun getAll(): List<MenuItem>

    @Query("SELECT * FROM menu_items")
    fun getAllFlow(): Flow<List<MenuItem>>

    @Query("SELECT * FROM menu_items WHERE available = 1")
    suspend fun getAvailable(): List<MenuItem>

    @Query("SELECT * FROM menu_items WHERE available = 1")
    fun getAvailableFlow(): Flow<List<MenuItem>>

    /**
     * Items to surface in the daily-login popup: those flagged askMeDaily (availability
     * check), plus variable-price items with daily prompting on (today's price pick) —
     * the two are independent triggers, an item can qualify via either or both.
     */
    @Query("SELECT * FROM menu_items WHERE askMeDaily = 1 OR (hasVariablePrice = 1 AND variablePriceDailyPrompt = 1)")
    suspend fun getAskMeDaily(): List<MenuItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MenuItem>)

    @Query("UPDATE menu_items SET available = :available, price = :price WHERE id = :id")
    suspend fun updateAvailability(id: String, available: Boolean, price: Double)

    /** Set the active price for a variable-price item (e.g. daily popup preset pick). */
    @Query("UPDATE menu_items SET price = :price WHERE id = :id")
    suspend fun updateActivePrice(id: String, price: Double)

    @Query("UPDATE menu_items SET available = :available WHERE id = :id")
    suspend fun updateAvailabilityOnly(id: String, available: Boolean)

    @Query("DELETE FROM menu_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM menu_items")
    suspend fun deleteAll()
}
