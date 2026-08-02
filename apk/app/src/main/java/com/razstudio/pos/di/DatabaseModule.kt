package com.razstudio.pos.di

import android.content.Context
import androidx.room.Room
import com.razstudio.pos.data.local.AppDatabase
import com.razstudio.pos.data.local.MIGRATION_10_11
import com.razstudio.pos.data.local.MIGRATION_11_12
import com.razstudio.pos.data.local.MIGRATION_12_13
import com.razstudio.pos.data.local.MIGRATION_13_14
import com.razstudio.pos.data.local.MIGRATION_14_15
import com.razstudio.pos.data.local.MIGRATION_8_9
import com.razstudio.pos.data.local.MIGRATION_9_10
import com.razstudio.pos.data.local.CafeSessionDao
import com.razstudio.pos.data.local.DailyAggregateDao
import com.razstudio.pos.data.local.MenuDao
import com.razstudio.pos.data.local.OrderDao
import com.razstudio.pos.data.local.OrderNumberSequenceDao
import com.razstudio.pos.data.local.PairedDeviceDao
import com.razstudio.pos.data.local.PairingTokenDao
import com.razstudio.pos.data.local.PendingOrderDao
import com.razstudio.pos.data.local.PrintJobDao
import com.razstudio.pos.data.local.PrinterConfigDao
import com.razstudio.pos.data.local.SettingsDao
import com.razstudio.pos.data.local.TableDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        // Migration chain starts at v8→v9. Versions 1–7 pre-date the migration chain and no
        // explicit migrations exist for those steps. Any device still carrying a v1–v7 schema
        // is no longer supported: Room will throw an IllegalStateException rather than silently
        // destroying data (fallbackToDestructiveMigration has been removed per Requirement 8.1).
        // In LAN and Kiosk Mode the local database is the café's only copy of its orders and
        // takings, so a loud failure is far safer than a silent wipe.
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "warung_tom_yam_db"
        )
            .addMigrations(
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
            )
            .build()
    }

    @Provides
    fun provideCafeSessionDao(database: AppDatabase): CafeSessionDao {
        return database.cafeSessionDao()
    }

    @Provides
    fun provideDailyAggregateDao(database: AppDatabase): DailyAggregateDao {
        return database.dailyAggregateDao()
    }

    @Provides
    fun provideMenuDao(database: AppDatabase): MenuDao {
        return database.menuDao()
    }

    @Provides
    fun provideOrderDao(database: AppDatabase): OrderDao {
        return database.orderDao()
    }

    @Provides
    fun provideSettingsDao(database: AppDatabase): SettingsDao {
        return database.settingsDao()
    }

    @Provides
    fun provideTableDao(database: AppDatabase): TableDao {
        return database.tableDao()
    }

    @Provides
    fun providePendingOrderDao(database: AppDatabase): PendingOrderDao {
        return database.pendingOrderDao()
    }

    @Provides
    fun providePrinterConfigDao(database: AppDatabase): PrinterConfigDao {
        return database.printerConfigDao()
    }

    @Provides
    fun providePrintJobDao(database: AppDatabase): PrintJobDao {
        return database.printJobDao()
    }

    @Provides
    fun provideOrderNumberSequenceDao(database: AppDatabase): OrderNumberSequenceDao {
        return database.orderNumberSequenceDao()
    }

    @Provides
    fun providePairedDeviceDao(database: AppDatabase): PairedDeviceDao {
        return database.pairedDeviceDao()
    }

    @Provides
    fun providePairingTokenDao(database: AppDatabase): PairingTokenDao {
        return database.pairingTokenDao()
    }
}
