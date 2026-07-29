package com.warungtomyam.pos.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for printer configurations.
 */
@Dao
interface PrinterConfigDao {

    @Query("SELECT * FROM printer_configs ORDER BY name ASC")
    suspend fun getAll(): List<PrinterConfig>

    @Query("SELECT * FROM printer_configs ORDER BY name ASC")
    fun getAllFlow(): Flow<List<PrinterConfig>>

    @Query("SELECT * FROM printer_configs WHERE id = :id")
    suspend fun getById(id: String): PrinterConfig?

    @Query("SELECT * FROM printer_configs WHERE printerRole = :role AND isActive = 1")
    suspend fun getByRole(role: PrinterRole): List<PrinterConfig>

    @Query("SELECT * FROM printer_configs WHERE isActive = 1")
    suspend fun getActive(): List<PrinterConfig>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(printerConfig: PrinterConfig)

    @Update
    suspend fun update(printerConfig: PrinterConfig)

    @Delete
    suspend fun delete(printerConfig: PrinterConfig)

    @Query("DELETE FROM printer_configs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM printer_configs")
    suspend fun deleteAll()
}
