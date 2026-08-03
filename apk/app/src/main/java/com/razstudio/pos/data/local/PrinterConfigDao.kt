package com.razstudio.pos.data.local

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

    /**
     * **Not currently called.** Kept because it is tested and correct, and because the decision it
     * implements may be revisited — see designs.md H11.
     *
     * Repairs a Sunmi internal printer that was added over the wrong transport.
     *
     * Sunmi terminals expose their built-in printer as a bonded Bluetooth device called
     * `InnerPrinter` (see [SunmiInnerPrinter]), so before that entry was filtered out of the scan
     * a café could — and on at least one D3 Mini did — add its own internal printer as a Bluetooth
     * one. The resulting row prints through DantSu over RFCOMM instead of the AIDL, which costs the
     * cash drawer, paper detection and the hardware status broadcasts, and needs
     * `BLUETOOTH_CONNECT`, which these terminals commonly have denied.
     *
     * **Only `transport` is corrected.** `drawerKick` is left alone deliberately: had the café gone
     * through the correct flow it would have chosen the drawer separately in Devices & Hardware,
     * and silently enabling a drawer nobody selected is a surprise, not a repair. `paperWidth`
     * needs no fixing either — `SunmiPrinterDriver` detects the real width via `getPrinterPaper()`
     * and overrides the stored value at print time.
     *
     * @return how many rows were repaired, so the caller can log a real event rather than a no-op.
     */
    @Query("""
        UPDATE printer_configs
        SET transport = 'SUNMI_AIDL', address = NULL
        WHERE transport = 'BLUETOOTH'
          AND (address = :placeholderMac OR name = :innerPrinterName)
    """)
    suspend fun repairSunmiInnerPrinterTransport(
        placeholderMac: String = SunmiInnerPrinter.PLACEHOLDER_MAC,
        innerPrinterName: String = SunmiInnerPrinter.BONDED_NAME,
    ): Int
}
