package com.razstudio.opsapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectedCafeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cafe: ConnectedCafeEntity)

    @Query("SELECT * FROM connected_cafes ORDER BY lastConnectedAt DESC")
    fun listAll(): Flow<List<ConnectedCafeEntity>>

    @Query("DELETE FROM connected_cafes WHERE id = :cafeId")
    suspend fun deleteById(cafeId: String)

    @Query("UPDATE connected_cafes SET lastConnectedAt = :timestamp WHERE id = :cafeId")
    suspend fun touchLastConnected(cafeId: String, timestamp: String)
}
