package com.razstudio.opsapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.razstudio.opsapp.data.promos.AffiliateProductDao
import com.razstudio.opsapp.data.promos.AffiliateProductEntity

/**
 * Version 2 — added `ownerKeyUrl` to `ConnectedCafeEntity` so the Cafe_Profile_Shell can
 * re-share the owner recovery key for cafés provisioned by this device. This app has no
 * shipped installs yet, so destructive migration is acceptable during development.
 */
@Database(
    entities = [ConnectedCafeEntity::class, AffiliateProductEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectedCafeDao(): ConnectedCafeDao
    abstract fun affiliateProductDao(): AffiliateProductDao
}
