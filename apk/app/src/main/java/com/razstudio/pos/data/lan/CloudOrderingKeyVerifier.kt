package com.razstudio.pos.data.lan

import android.util.Log
import com.razstudio.pos.BuildConfig
import com.razstudio.pos.data.AppConfigStore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Decides whether a staff device may open the admin's push socket **in Cloud mode**.
 *
 * ### Why this exists at all
 *
 * In LAN mode the admin issued the credential itself, so `LanServer` authenticates by hashing the
 * presented Bearer and finding it in its own `paired_devices` table. In Cloud mode that table is
 * empty of cloud-paired staff: the ordering key is minted by the backend and **the admin device
 * never sees its value**, so there is nothing local to compare against. Offline verification is not
 * available here — not as a shortcut, but as a fact about who holds the secret.
 *
 * So the admin asks the backend the one question it cannot answer alone: *is this key currently
 * valid for this café?* `GET /settings` is the probe — it accepts an ordering key
 * (`verifyOrderingKey` for non-admin callers), returns a small body, and mutates nothing. No new
 * endpoint and no new table, which is the whole point of choosing this route.
 *
 * ### The cost, stated plainly
 *
 * One HTTP round trip per socket connect, and it needs network at that moment. In Cloud mode that is
 * a given — the café is already talking to Supabase for everything authoritative. A blip during a
 * reconnect costs the *push*, not correctness: the staff device's poll is unaffected and the floor
 * converges on the next tick.
 *
 * ### Caching, and why failures are not cached
 *
 * A verdict holds for [CACHE_TTL_MS] so a flapping socket does not re-probe on every retry. Only
 * *successes* are cached: caching a failure would keep a legitimate phone locked out for the rest of
 * the TTL because of one dropped request — the opposite of the reconnect behaviour the push path
 * depends on. A revoked key therefore keeps working for at most the TTL, which is why the TTL is
 * minutes and not hours; anything a revocation must cut off immediately (payments, orders) goes
 * through the cloud, where the revocation is enforced on every call.
 */
@Singleton
class CloudOrderingKeyVerifier @Inject constructor(
    private val appConfig: AppConfigStore,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val verified = HashMap<String, Long>()

    suspend fun isValid(bearer: String): Boolean {
        if (bearer.isBlank()) return false

        val now = System.currentTimeMillis()
        synchronized(verified) {
            verified[bearer]?.let { at -> if (now - at < CACHE_TTL_MS) return true }
        }

        val base = appConfig.supabaseUrl().ifBlank { BuildConfig.SUPABASE_URL }.trimEnd('/')
        if (base.isBlank()) return false
        val anon = appConfig.supabaseAnonKey().ifBlank { BuildConfig.SUPABASE_ANON_KEY }

        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$base/functions/v1/settings")
                    .addHeader("apikey", anon)
                    .addHeader("Authorization", "Bearer $bearer")
                    .get()
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrElse {
                Log.d(TAG, "Key probe failed (${it.message}) — refusing this connect, poll unaffected")
                false
            }
        }

        if (ok) synchronized(verified) { verified[bearer] = now }
        return ok
    }

    private companion object {
        const val TAG = "CloudKeyVerifier"
        const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
