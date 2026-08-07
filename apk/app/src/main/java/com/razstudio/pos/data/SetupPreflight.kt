package com.razstudio.pos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probes the three surfaces a Cloud café needs and reports which are actually working.
 *
 * ### Why this exists
 *
 * A café is not one deployment, it is three: a Supabase project, a customer website, and — during
 * onboarding — the provisioning Wizard. Each fails in a way that looks like something else from the
 * device: an undeployed Wizard serves its UI perfectly and 404s its API; a Supabase project with the
 * schema applied but no Edge Functions answers REST and refuses every sign-in; a website that is up
 * may still not serve the config the app bootstraps from. In every case the operator sees a page load
 * and concludes that side is fine.
 *
 * The step operators miss most is the Wizard, because it is the one that is invisible when wrong — and
 * asking for its URL does not help if nothing ever checks the answer. So each surface is probed for
 * the specific thing that distinguishes working from merely present, and named individually. A single
 * "setup failed" verdict would send someone to look at whichever part they last touched.
 */
@Singleton
class SetupPreflight @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Item(
        val label: String,
        val ok: Boolean,
        /**
         * What to do about it. Usually empty when [ok] — but a check can pass and still carry a
         * caveat (a Wizard reachable only at a throwaway deployment URL works today and rots later),
         * and silently passing that would be the same mistake this class exists to prevent.
         */
        val detail: String = "",
    )

    suspend fun check(
        websiteUrl: String,
        wizardUrl: String,
        supabaseUrl: String,
        supabaseKey: String,
    ): List<Item> = withContext(Dispatchers.IO) {
        listOf(
            checkSupabaseFunctions(supabaseUrl, supabaseKey),
            checkWebsite(websiteUrl),
            checkWizard(wizardUrl),
        )
    }

    /**
     * The one that actually decides whether the app can run this café.
     *
     * The APK reaches a café ONLY through its Edge Functions, so this is checked first and phrased as
     * the blocker it is. A 404 here with a healthy REST API is the single most misleading state a café
     * can be in — the project looks alive from every other angle.
     */
    private fun checkSupabaseFunctions(supabaseUrl: String, supabaseKey: String): Item {
        val base = supabaseUrl.trim().trimEnd('/')
        if (base.isBlank()) return Item("Café backend (Supabase)", false, "No Supabase URL entered.")

        return try {
            val request = Request.Builder()
                .url("$base/functions/v1/settings")
                .header("Authorization", "Bearer $supabaseKey")
                .header("apikey", supabaseKey)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Item("Café backend (Supabase)", true)
                    // 404 means the function slug is not deployed — not that the project is missing.
                    response.code == 404 -> Item(
                        "Café backend (Supabase)", false,
                        "The database is reachable but its Edge Functions are not deployed. " +
                            "Use \"Deploy Edge Functions\" on the Provision new café tab, or re-run " +
                            "provisioning. Sign-in cannot work until this passes.",
                    )
                    response.code == 401 || response.code == 403 -> Item(
                        "Café backend (Supabase)", false,
                        "The functions are deployed but rejected this key. Check the publishable key.",
                    )
                    else -> Item("Café backend (Supabase)", false, "Responded HTTP ${response.code}.")
                }
            }
        } catch (e: Exception) {
            Item("Café backend (Supabase)", false, "Could not reach it: ${e.message}")
        }
    }

    /** The customer site. Its `/app-config.json` is what a fresh device bootstraps from. */
    private fun checkWebsite(websiteUrl: String): Item {
        val base = websiteUrl.trim().trimEnd('/')
        if (base.isBlank()) {
            return Item(
                "Customer website", false,
                "Not entered. Without it a new device cannot self-configure by URL — it can still " +
                    "be set up by owner QR or by hand.",
            )
        }

        return try {
            val request = Request.Builder().url("$base/app-config.json").get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    !response.isSuccessful -> Item(
                        "Customer website", false,
                        "No /app-config.json (HTTP ${response.code}). If this URL is the Wizard " +
                            "rather than the café's own site, put it in the Wizard field instead.",
                    )
                    // Present but blank is the template default — deployed without its build-time
                    // VITE_SUPABASE_* variables, so it names no café.
                    runCatching { JSONObject(body).optString("supabaseUrl") }.getOrNull()
                        .isNullOrBlank() -> Item(
                        "Customer website", false,
                        "Serving app-config.json, but with no Supabase URL in it. Set " +
                            "VITE_SUPABASE_URL and VITE_SUPABASE_PUBLISHABLE_KEY on that Pages " +
                            "project and redeploy.",
                    )
                    else -> Item("Customer website", true)
                }
            }
        } catch (e: Exception) {
            Item("Customer website", false, "Could not reach it: ${e.message}")
        }
    }

    /**
     * The Wizard — checked by POST, deliberately.
     *
     * A GET proves nothing. Cloudflare Pages Functions export `onRequestPost`, so a GET falls through
     * to the static site and returns **200 with the Wizard's own HTML** whether or not the API is
     * mounted. That is exactly how an operator concludes the Wizard is fine when its entire API is
     * absent: they open the URL and see a working page.
     *
     * A POST reaches the handler, which answers JSON. HTML back means the deployment is serving only
     * `dist/` — the signature of `wrangler deploy` (Workers) instead of `wrangler pages deploy`.
     * An empty body is safe: `run.ts` fails on the missing `supabase` field before doing anything.
     */
    private fun checkWizard(wizardUrl: String): Item {
        val url = wizardUrl.trim().trimEnd('/')
        if (url.isBlank()) {
            return Item(
                "Provisioning Wizard", false,
                "Not entered. Only needed to provision or repair a café from this device.",
            )
        }

        // Steer off per-deployment preview URLs. They keep working, which is the problem: a café
        // pinned to one build never receives a Wizard fix.
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("")
        val previewHost = Regex("^[0-9a-f]{8}\\.").containsMatchIn(host)

        return try {
            val body = "{}".toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty().trimStart()
                val looksLikeHtml = text.startsWith("<")
                when {
                    looksLikeHtml || response.code == 404 -> Item(
                        "Provisioning Wizard", false,
                        "The page loads but its API is not there. It was deployed as a Worker; it " +
                            "must be deployed as Cloudflare Pages (wrangler pages deploy dist), " +
                            "which serves the /api/provision/* routes alongside the UI.",
                    )
                    previewHost -> Item(
                        "Provisioning Wizard", true,
                        "Working, but this is a one-off deployment URL — it will never receive " +
                            "Wizard updates. Use the project's stable address instead.",
                    )
                    else -> Item("Provisioning Wizard", true)
                }
            }
        } catch (e: Exception) {
            Item("Provisioning Wizard", false, "Could not reach it: ${e.message}")
        }
    }
}
