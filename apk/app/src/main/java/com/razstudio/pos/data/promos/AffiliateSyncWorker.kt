package com.razstudio.pos.data.promos

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Narrow Hilt entry point so a plain [CoroutineWorker] (WorkManager instantiates it by reflection,
 * not through Hilt) can reach the app's singleton [AffiliateRepository] without re-wiring its whole
 * dependency graph by hand — the pattern every other worker in this app uses instead
 * (`ProvisionWorker`, `KeepAliveHeartbeatWorker`) doesn't fit here because `AffiliateRepository`
 * itself has several injected dependencies of its own.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AffiliateSyncEntryPoint {
    fun affiliateRepository(): AffiliateRepository
}

/**
 * WorkManager worker that performs affiliate product sync in the background.
 *
 * Delegates to [AffiliateRepository.syncNow], which tries the Shopee Affiliate API first and falls
 * back to the GitHub catalog when Shopee credentials are not configured.
 */
class AffiliateSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(
            applicationContext,
            AffiliateSyncEntryPoint::class.java,
        ).affiliateRepository()

        return when (repository.syncNow()) {
            is SyncResult.Success -> Result.success()
            is SyncResult.Failure -> Result.retry()
        }
    }
}
