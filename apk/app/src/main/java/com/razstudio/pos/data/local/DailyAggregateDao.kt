package com.razstudio.pos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Daily closing aggregates (task 4.5). */
@Dao
interface DailyAggregateDao {

    /** REPLACE, so re-closing a day overwrites rather than failing or duplicating. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(aggregate: DailyAggregate)

    @Query("SELECT * FROM daily_aggregates WHERE date = :date")
    suspend fun getByDate(date: String): DailyAggregate?

    @Query("SELECT * FROM daily_aggregates ORDER BY date DESC")
    suspend fun getAll(): List<DailyAggregate>

    @Query("DELETE FROM daily_aggregates")
    suspend fun deleteAll()
}
