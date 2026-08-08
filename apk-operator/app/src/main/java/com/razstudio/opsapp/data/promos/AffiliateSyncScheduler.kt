package com.razstudio.opsapp.data.promos

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules periodic and on-demand affiliate catalog syncs via WorkManager.
 *
 * Ported from `apk/app`'s `com.razstudio.pos.data.promos.AffiliateSyncScheduler`.
 */
@Singleton
class AffiliateSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    companion object {
        const val PERIODIC_WORK_NAME = "affiliate_product_sync"
        const val ONE_SHOT_WORK_TAG = "affiliate_sync_now"
        private const val SYNC_INTERVAL_HOURS = 6L
    }

    fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<AffiliateSyncWorker>(
            SYNC_INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun syncNow() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<AffiliateSyncWorker>()
            .setConstraints(constraints)
            .addTag(ONE_SHOT_WORK_TAG)
            .build()

        workManager.enqueue(request)
    }
}
