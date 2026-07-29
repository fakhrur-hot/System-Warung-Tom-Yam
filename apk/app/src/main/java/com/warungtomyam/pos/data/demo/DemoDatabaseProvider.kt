package com.warungtomyam.pos.data.demo

import android.content.Context
import androidx.room.Room
import com.warungtomyam.pos.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the lifecycle of the in-memory demo database.
 * Provides creation, destruction, and reset capabilities for Demo Mode sessions.
 */
@Singleton
class DemoDatabaseProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var demoDb: AppDatabase? = null

    /**
     * Returns the existing demo database or creates a new in-memory instance.
     */
    fun getOrCreate(): AppDatabase {
        return demoDb ?: Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { demoDb = it }
    }

    /**
     * Closes and discards the demo database, releasing all associated memory resources.
     * This method is idempotent — safe to call even if the database is already null or closed.
     */
    fun destroy() {
        demoDb?.close()
        demoDb = null
    }

    /**
     * Destroys the current demo database so the next call to [getOrCreate] produces a fresh instance.
     * Used when the user taps "Try Demo" to discard any previously existing demo session.
     */
    fun reset() {
        destroy()
        // Next call to getOrCreate() will produce a fresh instance
    }
}
