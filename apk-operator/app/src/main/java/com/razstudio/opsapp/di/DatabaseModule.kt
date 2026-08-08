package com.razstudio.opsapp.di

import android.content.Context
import androidx.room.Room
import com.razstudio.opsapp.data.local.AppDatabase
import com.razstudio.opsapp.data.local.ConnectedCafeDao
import com.razstudio.opsapp.data.promos.AffiliateProductDao
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
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "ops_app.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideConnectedCafeDao(db: AppDatabase): ConnectedCafeDao =
        db.connectedCafeDao()

    @Provides
    fun provideAffiliateProductDao(db: AppDatabase): AffiliateProductDao =
        db.affiliateProductDao()
}
