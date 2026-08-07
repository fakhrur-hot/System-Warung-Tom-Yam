package com.razstudio.pos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Reads and writes for [CashDrawerEvent] — the cash-drawer ledger. */
@Dao
interface CashDrawerEventDao {

    @Insert
    suspend fun insert(event: CashDrawerEvent): Long

    /** The latest event; its `balanceAfterSen` is the current expected drawer content. */
    @Query("SELECT * FROM cash_drawer_events ORDER BY id DESC LIMIT 1")
    suspend fun getLatest(): CashDrawerEvent?

    /** Live view of the same, for the Drawer screen's balance headline. */
    @Query("SELECT * FROM cash_drawer_events ORDER BY id DESC LIMIT 1")
    fun getLatestFlow(): Flow<CashDrawerEvent?>

    /**
     * The audit trail, newest first. Capped: the screen shows recent history, reports aggregate
     * the rest — an unbounded list on a till that takes cash all day would only grow.
     */
    @Query("SELECT * FROM cash_drawer_events ORDER BY id DESC LIMIT :limit")
    fun getRecentFlow(limit: Int = 200): Flow<List<CashDrawerEvent>>

    /** Events inside a reporting window (fixed-width ISO timestamps make this a text compare). */
    @Query("SELECT * FROM cash_drawer_events WHERE timestamp >= :startIso AND timestamp < :endIso ORDER BY id")
    suspend fun getBetween(startIso: String, endIso: String): List<CashDrawerEvent>

    @Query("DELETE FROM cash_drawer_events")
    suspend fun deleteAll()
}
