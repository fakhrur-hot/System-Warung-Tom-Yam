package com.razstudio.pos.data.local

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.OperatingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Touches the café's Supabase project on a schedule, so a free-tier project is not paused for
 * inactivity.
 *
 * ## The problem
 *
 * Supabase pauses a free project after **7 consecutive days with no API requests**. The data
 * survives, but the café is offline until somebody opens the Supabase dashboard and clicks Restore.
 * A trading café never gets near this — every order is an API call — so it only bites in the gaps:
 * a project provisioned weeks before opening, or a stall closed for Raya or a renovation.
 *
 * ## What this does and does not fix — read this before relying on it
 *
 * **WorkManager cannot run while the device is powered off.** That is not a limitation of this
 * class, it is what "off" means. And the case this exists for — a café closed for a week — is
 * usually a café whose tablet is also unplugged and in a drawer.
 *
 * So this covers exactly one shape of the problem: **a terminal left powered on through a quiet
 * stretch.** Many POS tablets do live permanently on a charger, which is why it is worth having.
 * A café that switches its terminal off still needs an *external* pinger — a free cron on
 * cron-job.org or UptimeRobot hitting the café's website — because only something off-device can
 * run when the device cannot. Neither replaces the other; the external one is strictly more
 * reliable and this one is strictly more convenient.
 *
 * ## Cloud only
 *
 * LAN and Kiosk cafés have no Supabase to keep awake, and `NoInternetGuard` blocks non-local hosts
 * in those modes by design — a heartbeat there would fail every attempt and retry forever, burning
 * battery to be refused by the app's own network guard.
 *
 * ## Why it stops itself
 *
 * An unlinked device returns [Result.failure], not [Result.retry]. Failure is terminal for a
 * WorkManager job, which is what we want: after `AppConfigStore.unlinkFromCafe` there is no café to
 * keep alive, and a device sitting on a shelf should not keep waking up to ping a project it no
 * longer belongs to.
 */
class KeepAliveHeartbeatWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = AppConfigStore(applicationContext)

        // Nothing to keep alive. Terminal, not a retry — see the class note.
        if (!config.isConfigured()) return@withContext Result.failure()
        if (config.operatingMode() != OperatingMode.CLOUD) return@withContext Result.failure()

        val url = config.supabaseUrl().trimEnd('/')
        val key = config.supabaseAnonKey()
        if (url.isBlank() || key.isBlank()) return@withContext Result.failure()

        return@withContext try {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()

            // A real query against a real table, deliberately.
            //
            // Supabase counts *API requests*, and the cheapest thing that unambiguously is one is a
            // one-row select against a table that always exists. Pinging the REST root or an Edge
            // Function would be lighter, but both leave room for doubt about whether Postgres was
            // touched at all — and the entire value of this job is that it definitely was.
            val request = Request.Builder()
                .url("$url/rest/v1/system_settings?select=id&limit=1")
                .header("apikey", key)
                .header("Authorization", "Bearer $key")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Keep-alive ping ok")
                    Result.success()
                } else {
                    // A 4xx is usually a rotated key or a project that is already paused; a 5xx is
                    // transient. Retrying covers the second and costs one wake-up for the first,
                    // which is cheaper than giving up on a café that is merely mid-deploy.
                    Log.w(TAG, "Keep-alive ping failed: HTTP ${response.code}")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Keep-alive ping could not reach the backend", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "KeepAliveHeartbeat"
        private const val WORK_NAME = "supabase_keepalive_heartbeat"

        /**
         * Every two days, against a seven-day deadline.
         *
         * Deliberately not daily: the margin is what makes the job safe to miss. Android batches
         * deferrable work and a dozed tablet can slip a window, so a schedule that only just fits
         * inside the deadline would fail exactly when it matters. Three chances before the pause
         * lands means two can be dropped entirely.
         */
        private const val INTERVAL_DAYS = 2L

        /**
         * Schedule the heartbeat, or clear it when the café cannot use one.
         *
         * Safe to call on every launch — WorkManager deduplicates by work name, and
         * [ExistingPeriodicWorkPolicy.KEEP] leaves an already-running schedule undisturbed rather
         * than resetting its next-run clock on each cold start. That distinction matters here: a
         * till restarted several times a day would, under UPDATE, keep pushing the next ping into
         * the future and might never actually fire one.
         */
        fun scheduleForMode(context: Context, mode: OperatingMode) {
            val manager = WorkManager.getInstance(context)
            if (mode != OperatingMode.CLOUD) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }

            val request = PeriodicWorkRequestBuilder<KeepAliveHeartbeatWorker>(
                INTERVAL_DAYS, TimeUnit.DAYS,
            ).setConstraints(
                // No point waking to make an HTTP call with no network — Android will run it as
                // soon as one appears instead.
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            ).build()

            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Stop pinging — used when a device is unlinked from its café. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
