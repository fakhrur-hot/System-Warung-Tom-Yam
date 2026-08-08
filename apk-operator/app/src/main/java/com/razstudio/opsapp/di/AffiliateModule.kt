package com.razstudio.opsapp.di

import com.razstudio.opsapp.data.promos.ShopeeAffiliateApi
import com.razstudio.opsapp.data.promos.ShopeeAffiliateApiImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Ported from `apk/app`'s `com.razstudio.pos.di.AffiliateModule`. Binds
 * [ShopeeAffiliateApiImpl] to the [ShopeeAffiliateApi] interface.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AffiliateModule {

    @Binds
    @Singleton
    abstract fun bindShopeeAffiliateApi(impl: ShopeeAffiliateApiImpl): ShopeeAffiliateApi
}
