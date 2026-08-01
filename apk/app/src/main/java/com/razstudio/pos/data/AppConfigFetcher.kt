package com.razstudio.pos.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches `/app-config.json` from the café's own website and validates the payload.
 *
 * This is Channel 2 from the design: the café's Pages deployment already holds the two values
 * a device needs (`supabaseUrl`, `supabaseAnonKey`) and serves them publicly. `/app-config.json`
 * is a stable, parseable form of what the JS bundle already leaks — which matters because scraping
 * a minified bundle is not an interface.
 *
 * The three failure cases are all treated identically (report; fall back to manual; write nothing):
 *  - Unreachable host (network error or timeout)
 *  - Response body is not valid JSON (a Cloudflare Pages 404 returns HTML with a 200 status)
 *  - Valid JSON missing one or more of the required fields
 *
 * This mirrors [AppConfigStore.adoptBackendFromRecoveryQr]'s existing refusal of a half-present
 * pair. A partial write leaves a device that looks configured, cannot authenticate, and no longer
 * offers Setup — unrecoverable in the field short of clearing app data.
 *
 * Requirements: 3.2, 3.4
 */
@Singleton
/*
 * `open` so tests can substitute the two network calls. Verifying the *gate* — what save() will and
 * will not persist — must not depend on a live host, and there is no seam below this: the client is
 * built here. Hilt is unaffected; it still injects the real class.
 */
open class AppConfigFetcher @Inject constructor(
    private val noInternetGuard: com.razstudio.pos.data.net.NoInternetGuard,
) {

    companion object {
        private const val TAG = "AppConfigFetcher"
        private const val PATH = "/app-config.json"
    }

    /**
     * The outcome of a fetch attempt.
     *
     * All failure variants carry a user-facing [message] naming a cause and an action.
     * No raw HTTP status or exception text is exposed — Property 6 of the design.
     */
    sealed class FetchResult {
        /**
         * All three required fields were present and non-blank.
         * Safe to write all three atomically to [AppConfigStore].
         */
        data class Success(
            val supabaseUrl: String,
            val supabaseAnonKey: String,
            val cafeName: String,
        ) : FetchResult()

        /**
         * The host was unreachable, the connection timed out, or the network returned an error.
         * Write nothing. Fall back to manual entry.
         */
        data class NetworkError(val message: String) : FetchResult()

        /**
         * The response body was not valid JSON (the most common case: a Pages 404 page has
         * status 200 and returns HTML). Write nothing. Fall back to manual entry.
         */
        data class ParseError(val message: String) : FetchResult()

        /**
         * The JSON parsed correctly but one or more required fields were missing or blank.
         * Write nothing — a half-present pair is as dangerous as no config at all.
         * Fall back to manual entry.
         */
        data class IncompletePayload(val message: String, val missing: List<String>) : FetchResult()
    }

    // Short timeout: this is a foreground user-initiated fetch. A café's Pages CDN should respond
    // in under a second; 10 s is generous and prevents the UI from hanging.
    // Task 18.1: guarded first, so no later builder call can be added "above" it. This is the
    // seventh HTTP client in the app and it was initially built without the guard — exactly the
    // omission NoInternetGuard.PROTECTED_CLIENTS exists to make visible.
    //
    // Guarding it is also correct on the merits, not just for consistency: a café in LAN or Kiosk
    // Mode has no website to fetch a config from, so a request leaving the premises there is always
    // wrong. During Cloud setup the mode is CLOUD and the guard passes it through untouched.
    private val client = OkHttpClient.Builder()
        .dns(noInternetGuard)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Normalises [websiteUrl], appends `/app-config.json`, performs a GET, and validates the
     * response. Runs on [Dispatchers.IO]; safe to call from a ViewModel coroutine.
     *
     * @param websiteUrl  The café's website URL as the operator typed it.  May or may not include
     *                    a scheme, a trailing slash, or a path.  Normalised internally.
     */
    open suspend fun fetch(websiteUrl: String): FetchResult = withContext(Dispatchers.IO) {
        val configUrl = buildConfigUrl(websiteUrl)
            ?: return@withContext FetchResult.NetworkError(
                "That doesn't look like a valid website address. " +
                    "Enter a full URL, e.g. https://your-cafe.pages.dev"
            )

        Log.d(TAG, "Fetching $configUrl")
        try {
            val request = Request.Builder()
                .url(configUrl)
                .get()
                .build()

            val responseBody = client.newCall(request).execute().use { response ->
                response.body?.string() ?: ""
            }

            parsePayload(responseBody, configUrl)
        } catch (e: IOException) {
            Log.w(TAG, "Network error fetching $configUrl", e)
            FetchResult.NetworkError(
                "Couldn't reach ${originOf(configUrl)}. " +
                    "Check the address and your internet connection, or enter the details manually."
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error fetching $configUrl", e)
            FetchResult.NetworkError(
                "Something went wrong fetching the config from ${originOf(configUrl)}. " +
                    "Try again, or enter the details manually."
            )
        }
    }

    /**
     * Parse and validate the JSON payload. Returns [FetchResult.ParseError] for invalid JSON
     * (e.g. an HTML 404 page), and [FetchResult.IncompletePayload] if any required field is absent.
     */
    internal fun parsePayload(body: String, configUrl: String): FetchResult {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "Response from $configUrl was not JSON (HTML 404?): ${body.take(80)}")
            return FetchResult.ParseError(
                "The website responded, but the config file wasn't found or wasn't valid JSON. " +
                    "Make sure the site is deployed and the /app-config.json endpoint exists."
            )
        }

        val supabaseUrl = json.optString("supabaseUrl", "").trim().trimEnd('/')
        val supabaseAnonKey = json.optString("supabaseAnonKey", "").trim()
        val cafeName = json.optString("cafeName", "").trim()

        val missing = buildList {
            if (supabaseUrl.isBlank()) add("supabaseUrl")
            if (supabaseAnonKey.isBlank()) add("supabaseAnonKey")
            if (cafeName.isBlank()) add("cafeName")
        }

        if (missing.isNotEmpty()) {
            Log.w(TAG, "Incomplete app-config.json from $configUrl — missing: $missing")
            return FetchResult.IncompletePayload(
                "The config file at ${originOf(configUrl)} is missing required fields: " +
                    "${missing.joinToString(", ")}. Enter the details manually or redeploy the site.",
                missing = missing,
            )
        }

        return FetchResult.Success(
            supabaseUrl = supabaseUrl,
            supabaseAnonKey = supabaseAnonKey,
            cafeName = cafeName,
        )
    }

    /**
     * Normalise [websiteUrl] and append the config path.
     *
     * Handles the common operator input patterns:
     *  - `https://your-cafe.pages.dev`   → `https://your-cafe.pages.dev/app-config.json`
     *  - `your-cafe.pages.dev`           → `https://your-cafe.pages.dev/app-config.json`
     *  - `https://your-cafe.pages.dev/`  → `https://your-cafe.pages.dev/app-config.json`
     *
     * Returns null for clearly invalid input (empty, or fails URI parsing after normalisation).
     */
    internal fun buildConfigUrl(websiteUrl: String): String? {
        var url = websiteUrl.trim()
        if (url.isBlank()) return null
        // Prepend https:// if no scheme present. Bare domains like "your-cafe.pages.dev" are the
        // most common input, and the operator should not have to know what HTTPS is to use this.
        if (!url.contains("://")) url = "https://$url"
        return try {
            val uri = URI(url)
            // Only allow http / https — not file://, content://, etc.
            if (uri.scheme != "https" && uri.scheme != "http") return null
            val origin = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            "$origin$PATH"
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse website URL: '$websiteUrl'", e)
            null
        }
    }


    /**
     * Prove a Supabase URL and publishable key actually work, before anything is written (task 6.3).
     *
     * Setup used to end at a **Save** button, and Save proved only that text reached disk. A café
     * owner who mistyped a key learned nothing until some later screen failed for a reason that
     * never mentioned the key — which is the failure this whole wizard rework exists to remove.
     *
     * Deliberately does **not** go through [com.razstudio.pos.data.ApiClient]: that resolves its base
     * URL from the *stored* config, so it would verify whatever the device was already pointed at
     * rather than the values on screen. Verification has to run against the pending pair or it is
     * theatre.
     *
     * Probes `GET /functions/v1/branding`, which is public — `verifyAdminToken` guards only its PUT —
     * so no session is needed, and it still carries the `apikey` header. That makes the three
     * outcomes distinguishable: a bad host fails to connect, a bad key is refused by the gateway with
     * 401, and a working pair answers 200.
     */
    open suspend fun verifyBackend(supabaseUrl: String, anonKey: String): VerifyResult =
        withContext(Dispatchers.IO) {
            val base = supabaseUrl.trim().trimEnd('/')
            if (base.isBlank() || anonKey.isBlank()) {
                return@withContext VerifyResult.Invalid(
                    "Both the project URL and the publishable key are needed before this can be checked."
                )
            }
            if (!base.startsWith("https://") && !base.startsWith("http://")) {
                return@withContext VerifyResult.Invalid(
                    "The project URL must start with https://, e.g. https://your-project.supabase.co"
                )
            }

            val probe = "$base/functions/v1/branding"
            try {
                val request = Request.Builder().url(probe).addHeader("apikey", anonKey).get().build()
                val code = client.newCall(request).execute().use { it.code }
                when {
                    code == 401 || code == 403 -> VerifyResult.BadKey(
                        "Reached ${originOf(probe)}, but it refused the publishable key. " +
                            "Check you copied the whole key, and that it is the publishable one — " +
                            "not the secret."
                    )
                    code == 404 -> VerifyResult.NotDeployed(
                        "Reached ${originOf(probe)}, but its Edge Functions aren't deployed yet. " +
                            "Deploy the café's backend, then check again."
                    )
                    code in 200..299 -> VerifyResult.Ok
                    // Anything else means something answered and the credentials were not the
                    // problem — good enough to proceed, and better than blocking on a transient 5xx.
                    else -> VerifyResult.Ok
                }
            } catch (e: IOException) {
                VerifyResult.Unreachable(
                    "Couldn't reach ${originOf(probe)}. Check the project URL and this device's " +
                        "internet connection."
                )
            } catch (e: Exception) {
                VerifyResult.Invalid(
                    "That project URL doesn't look usable. Expected something like " +
                        "https://your-project.supabase.co"
                )
            }
        }

    /** The outcome of [verifyBackend]. Only [Ok] may proceed to save. */
    sealed class VerifyResult {
        data object Ok : VerifyResult()

        /** Host answered, credential refused. */
        data class BadKey(val message: String) : VerifyResult()

        /** Host answered, but the Edge Functions are absent. */
        data class NotDeployed(val message: String) : VerifyResult()

        /** Nothing answered. */
        data class Unreachable(val message: String) : VerifyResult()

        /** Never left the device — malformed input. */
        data class Invalid(val message: String) : VerifyResult()

        val messageOrNull: String?
            get() = when (this) {
                is Ok -> null
                is BadKey -> message
                is NotDeployed -> message
                is Unreachable -> message
                is Invalid -> message
            }
    }

    /** Extract the scheme+host from a full URL for display in error messages. */
    private fun originOf(url: String): String = try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) {
        url
    }
}
