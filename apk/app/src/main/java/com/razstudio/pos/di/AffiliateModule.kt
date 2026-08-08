package com.razstudio.pos.di

import com.razstudio.pos.data.promos.ShopeeAffiliateApi
import com.razstudio.pos.data.promos.ShopeeAffiliateApiImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds [ShopeeAffiliateApiImpl] to the [ShopeeAffiliateApi] interface.
 *
 * This allows Hilt to inject [ShopeeAffiliateApi] wherever it is requested, resolving
 * it to the singleton [ShopeeAffiliateApiImpl] instance.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AffiliateModule {

    @Binds
    @Singleton
    abstract fun bindShopeeAffiliateApi(impl: ShopeeAffiliateApiImpl): ShopeeAffiliateApi
}
