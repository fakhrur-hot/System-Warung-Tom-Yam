package com.razstudio.opsapp.data.promos

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Ported from `apk/app`'s `com.razstudio.pos.data.promos.AffiliateSyncEntryPoint`/
 * `AffiliateSyncWorker` (post-bugfix version — the original shipped as a `// TODO` no-op; this app
 * starts from the fixed version directly).
 *
 * A plain [CoroutineWorker] (WorkManager instantiates it by reflection, not through Hilt) reaches
 * the app's singleton [AffiliateRepository] via this narrow entry point rather than a full
 * `@HiltWorker` setup — matching this project's own [com.razstudio.opsapp.work.ProvisionWorker],
 * which is also a plain worker.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AffiliateSyncEntryPoint {
    fun affiliateRepository(): AffiliateRepository
}

/** WorkManager worker that performs affiliate catalog sync in the background. */
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
