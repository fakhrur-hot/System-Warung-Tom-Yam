package com.razstudio.pos.data.promos

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
 * Schedules periodic and on-demand affiliate product syncs via WorkManager.
 *
 * Periodic sync runs every 6 hours with a network connectivity constraint so the device
 * never wastes battery on a doomed request. Calling [schedulePeriodicSync] multiple times
 * (e.g. on every app launch) is safe — the KEEP policy preserves the existing schedule.
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

    /**
     * Schedule periodic sync every 6 hours. Uses KEEP existing policy so calling
     * this multiple times (e.g. on every app launch) doesn't reset the schedule.
     */
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

    /** Trigger immediate one-shot sync. */
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
