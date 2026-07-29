package com.warungtomyam.pos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for system settings (singleton row).
 */
@Dao
interface SettingsDao {

    @Query("SELECT * FROM system_settings WHERE id = 1")
    suspend fun get(): SystemSettings?

    @Query("SELECT * FROM system_settings WHERE id = 1")
    fun getFlow(): Flow<SystemSettings?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: SystemSettings)

    @Query("DELETE FROM system_settings")
    suspend fun deleteAll()
}
