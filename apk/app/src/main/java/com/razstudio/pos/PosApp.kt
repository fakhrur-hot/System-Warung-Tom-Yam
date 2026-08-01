package com.razstudio.pos

import android.app.Application
import android.content.Context
import android.util.Log
import com.razstudio.pos.data.local.BackupReminderWorker
import com.razstudio.pos.data.local.OrderDao
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hilt application entry point. Services and view-models obtain their dependencies
 * directly via Hilt (`@AndroidEntryPoint` / `@HiltViewModel`); the old hand-rolled
 * DependencyProvider service-locator has been retired.
 */
@HiltAndroidApp
class PosApp : Application(), coil.ImageLoaderFactory {

    companion object {
        private const val MAINTENANCE_PREFS = "app_maintenance"
        private const val KEY_NULL_CLEANUP_DONE = "null_string_cleanup_done_v1"
    }

    // Retained only for the one-time legacy cleanup below.
    @Inject lateinit var orderDao: OrderDao

    /** Task 18.1 — see [newImageLoader]. */
    @Inject lateinit var noInternetGuard: com.razstudio.pos.data.net.NoInternetGuard

    override fun onCreate() {
        super.onCreate()
        BackupReminderWorker.schedule(this)
        cleanupLegacyNullStringsOnce()
    }

    /**
     * Coil's image loader, guarded like every HTTP client in the app
     * (task 18.1 — Requirement 11.2.1, Property 3).
     *
     * This is the hole the requirement calls out by name, and the reason it matters is that it does
     * not look like network code. Menu rows carry an `imageUrl`, and a café that ran in Cloud Mode
     * before switching has rows still holding `https://…supabase.co/storage/…/x.jpg`. Coil fetches
     * those the moment someone opens the menu — a real request to a real server, carrying the café's
     * IP, from a device that is supposed to have no internet traffic at all. A guard applied only to
     * the API clients would never see it.
     *
     * Implemented as [coil.ImageLoaderFactory] on the Application, which is how Coil finds a custom
     * loader for `AsyncImage` without every call site passing one — so a screen added later is
     * covered automatically rather than needing to remember.
     */
    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            .okHttpClient {
                okhttp3.OkHttpClient.Builder()
                    .dns(noInternetGuard)
                    .build()
            }
            .build()

    /**
     * One-time repair: earlier builds parsed JSON nulls with `optString(name, null)`,
     * which stored the literal string "null" in nullable columns. Rewrite those to
     * real SQL NULL. Runs at most once per install (guarded by a prefs flag); safe to
     * retry on the next launch if it fails.
     */
    private fun cleanupLegacyNullStringsOnce() {
        val prefs = getSharedPreferences(MAINTENANCE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NULL_CLEANUP_DONE, false)) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                orderDao.fixNullPaymentMethod()
                orderDao.fixNullSentToKitchenAt()
                orderDao.fixNullCancelReason()
                orderDao.fixNullCancelledBy()
                orderDao.fixNullItemNote()
                prefs.edit().putBoolean(KEY_NULL_CLEANUP_DONE, true).apply()
            } catch (e: Exception) {
                Log.w("PosApp", "Legacy null-string cleanup failed; will retry next launch", e)
            }
        }
    }
}
