package com.razstudio.pos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Append-only log of café open/close events (task 4.5). */
@Dao
interface CafeSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: CafeSession)

    /** Most recent event first — the café's current open/closed state is the head of this list. */
    @Query("SELECT * FROM cafe_sessions ORDER BY timestamp DESC")
    suspend fun getAll(): List<CafeSession>

    @Query("SELECT * FROM cafe_sessions ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): CafeSession?

    @Query("DELETE FROM cafe_sessions")
    suspend fun deleteAll()
}
