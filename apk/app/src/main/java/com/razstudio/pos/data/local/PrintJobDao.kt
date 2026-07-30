package com.razstudio.pos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for the print job queue.
 */
@Dao
interface PrintJobDao {

    @Query("SELECT * FROM print_jobs WHERE printerId = :printerId AND status = 'QUEUED' ORDER BY createdAt ASC")
    suspend fun getQueued(printerId: String): List<PrintJob>

    @Query("SELECT * FROM print_jobs ORDER BY createdAt DESC")
    suspend fun getAll(): List<PrintJob>

    /** Most-recent print jobs (any status) for the persistent kitchen-print status view. */
    @Query("SELECT * FROM print_jobs ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentFlow(limit: Int = 20): Flow<List<PrintJob>>

    @Query("SELECT * FROM print_jobs WHERE status = 'QUEUED' ORDER BY createdAt ASC")
    suspend fun getAllQueued(): List<PrintJob>

    @Query("SELECT * FROM print_jobs WHERE status = 'QUEUED' ORDER BY createdAt ASC")
    fun getAllQueuedFlow(): Flow<List<PrintJob>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(printJob: PrintJob)

    @Query("UPDATE print_jobs SET status = :status, lastError = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String? = null)

    @Query("UPDATE print_jobs SET retryCount = retryCount + 1, status = 'QUEUED', lastError = :error WHERE id = :id")
    suspend fun markForRetry(id: String, error: String)

    @Query("DELETE FROM print_jobs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM print_jobs WHERE status = 'COMPLETED'")
    suspend fun deleteCompleted()

    @Query("DELETE FROM print_jobs")
    suspend fun deleteAll()
}
