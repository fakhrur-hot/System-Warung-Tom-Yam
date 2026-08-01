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
class AppConfigFetcher @Inject constructor(
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
    suspend fun fetch(websiteUrl: String): FetchResult = withContext(Dispatchers.IO) {
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

    /** Extract the scheme+host from a full URL for display in error messages. */
    private fun originOf(url: String): String = try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) {
        url
    }
}
