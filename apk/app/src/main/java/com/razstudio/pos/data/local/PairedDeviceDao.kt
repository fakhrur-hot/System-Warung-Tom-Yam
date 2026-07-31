package com.razstudio.pos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy.Companion.REPLACE
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [PairedDevice].
 *
 * Exposes the minimal surface the Server Device needs to manage Client Devices in LAN Mode:
 * inserting or replacing a device record on first pairing ([insert]), updating the full record
 * when device details change ([update]), observing the full list reactively ([getAll]), looking
 * up a single device ([getById]), removing a device when the operator revokes it ([deleteById]),
 * and stamping the last-seen timestamp on each successful request ([updateStatus]).
 */
@Dao
interface PairedDeviceDao {

    /**
     * Insert a new paired device or replace an existing one with the same [PairedDevice.id].
     * Used during the pairing handshake and when re-pairing a previously known device.
     */
    @Insert(onConflict = REPLACE)
    suspend fun insert(device: PairedDevice)

    /**
     * Update all fields of an existing [PairedDevice] row identified by [PairedDevice.id].
     * Use this when the device's name, model, role, or credential hash changes after initial pairing.
     */
    @Update
    suspend fun update(device: PairedDevice)

    /**
     * Returns a [Flow] that emits the full list of paired devices whenever the table changes.
     * Collected by the Devices screen so the operator sees live status without manual refresh.
     */
    @Query("SELECT * FROM paired_devices")
    fun getAll(): Flow<List<PairedDevice>>

    /**
     * Returns the [PairedDevice] with the given [id], or `null` if no such device is paired.
     * Used during request authentication to look up the device making the request.
     */
    @Query("SELECT * FROM paired_devices WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PairedDevice?

    /**
     * Permanently removes the paired device with the given [id] from the database.
     * Called when the operator revokes a device from the Devices screen (Requirement 5.6).
     */
    @Query("DELETE FROM paired_devices WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Updates the [PairedDevice.status] and [PairedDevice.lastSeenMs] fields for the device
     * with the given [id]. Called on every successful authenticated request so the admin can
     * see which devices are recently active without rewriting the full row.
     */
    @Query("UPDATE paired_devices SET status = :status, lastSeenMs = :lastSeenMs WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, lastSeenMs: Long)
}
