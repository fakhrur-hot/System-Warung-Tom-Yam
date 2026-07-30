package com.razstudio.pos.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.razstudio.pos.data.local.AppDatabase
import com.razstudio.pos.data.local.MenuDao
import com.razstudio.pos.data.local.OrderDao
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

    /**
     * v8 -> v9: add MenuItem.code and MenuItem.marketPrice columns for the dynamic-menu revamp.
     */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE menu_items ADD COLUMN code TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE menu_items ADD COLUMN marketPrice INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v9 -> v10: add MenuItem.extraCategories so an item can appear under multiple category
     * pages (primary [category] + comma-separated extras).
     */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE menu_items ADD COLUMN extraCategories TEXT NOT NULL DEFAULT ''")
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "warung_tom_yam_db"
        )
            .addMigrations(MIGRATION_8_9, MIGRATION_9_10)
            .fallbackToDestructiveMigration()
            .build()
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
}
