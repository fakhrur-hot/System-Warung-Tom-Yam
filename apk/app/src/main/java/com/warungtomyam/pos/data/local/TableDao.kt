package com.warungtomyam.pos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for table registry management.
 */
@Dao
interface TableDao {

    @Query("SELECT * FROM tables ORDER BY sortOrder ASC, id ASC")
    fun getAllFlow(): Flow<List<Table>>

    @Query("SELECT * FROM tables ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<Table>

    @Query("SELECT * FROM tables WHERE id = :tableId")
    suspend fun getById(tableId: String): Table?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(table: Table)

    @Update
    suspend fun update(table: Table)

    @Query("DELETE FROM tables WHERE id = :tableId")
    suspend fun delete(tableId: String)

    @Query("SELECT COUNT(*) FROM tables")
    suspend fun getCount(): Int

    @Query("DELETE FROM tables")
    suspend fun deleteAll()
}
