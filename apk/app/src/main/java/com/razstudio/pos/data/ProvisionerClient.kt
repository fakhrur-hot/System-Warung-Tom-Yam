package com.razstudio.pos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP client that calls the backend provisioning worker from the APK installer.
 *
 * The worker performs the heavy lifting (Supabase project creation, schema, Edge Functions,
 * secrets, storage, auth config, Cloudflare Pages, DNS, owner key) in one long-running request.
 * This client only marshals the request and response; it does not hold provisioning state.
 */
@Singleton
class ProvisionerClient @Inject constructor() {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Long timeouts: creating a Supabase project and waiting for it to become ACTIVE can take
     * several minutes, and deploying 33 Edge Functions sequentially is not fast. The request is
     * a deliberate one-off installer action, so keeping the connection open is preferable to
     * adding a polling/synchronization layer on the first version.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun provision(request: ProvisionRequest): ProvisionResult = withContext(Dispatchers.IO) {
        val body = request.toJson().toString().toRequestBody(JSON_MEDIA_TYPE)
        // The endpoint comes from the request, i.e. from what the installer typed on the Provision
        // screen — never from BuildConfig. It used to be baked in, which meant every APK carried a
        // live URL that accepts Supabase and Cloudflare credentials, unchangeable without a rebuild,
        // and a build with the property unset failed here with "not configured in this build" — a
        // dead end no operator could act on from the device in their hands.
        val url = request.provisionerWorkerUrl.trim()
        if (url.isBlank()) {
            throw IllegalStateException("Enter the provisioning Wizard URL on the Provision screen.")
        }
        val httpRequest = Request.Builder()
            .url(url)
            .post(body)
            .build()

        try {
            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw IOException("Provisioner worker returned HTTP ${response.code}: $responseBody")
                }
                parseResponse(JSONObject(responseBody))
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Failed to call provisioner worker: ${e.message}", e)
        }
    }

    /**
     * Deploy the café's Edge Functions and nothing else.
     *
     * ### Why this exists separately from [provision]
     *
     * The functions are the only part of a café the APK cannot work without — it talks to a backend
     * exclusively through them, so a project with the schema applied but no functions deployed answers
     * every REST query and refuses every sign-in. That state is reachable in ordinary use: a café
     * provisioned before this Wizard existed, a run that failed partway, or a project set up by hand.
     * Until now the only remedy was a full provisioning run, which would try to create a Supabase
     * project and a Pages site the café already has.
     *
     * Idempotent by construction — the Management API's deploy endpoint updates a slug in place — so
     * running it against a healthy café is a no-op that costs 26 uploads, not a hazard.
     */
    suspend fun deployFunctions(
        wizardUrl: String,
        personalAccessToken: String,
        projectRef: String,
    ): ProvisionResult = withContext(Dispatchers.IO) {
        val url = functionsEndpoint(wizardUrl)
            ?: throw IllegalStateException("Enter the provisioning Wizard URL first.")

        val body = JSONObject().apply {
            put("personalAccessToken", personalAccessToken)
            put("projectRef", projectRef)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder().url(url).post(body).build()
        try {
            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw IOException("Provisioner worker returned HTTP ${response.code}: $responseBody")
                }
                parseResponse(JSONObject(responseBody))
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Failed to call provisioner worker: ${e.message}", e)
        }
    }

    private fun parseResponse(json: JSONObject): ProvisionResult {
        val results = mutableListOf<StepResult>()
        val resultsArray = json.optJSONArray("results")
        if (resultsArray != null) {
            for (i in 0 until resultsArray.length()) {
                val obj = resultsArray.optJSONObject(i) ?: continue
                results.add(
                    StepResult(
                        step = obj.optString("step", ""),
                        status = obj.optString("status", "error"),
                        detail = obj.optString("detail", null),
                    )
                )
            }
        }
        return ProvisionResult(
            results = results,
            supabaseUrl = json.optString("supabaseUrl", null),
            supabaseAnonKey = json.optString("supabaseAnonKey", null),
            supabaseServiceRoleKey = json.optString("supabaseServiceRoleKey", null),
            websiteUrl = json.optString("websiteUrl", null),
            cafeName = json.optString("cafeName", null),
            ownerKeyUrl = json.optString("ownerKeyUrl", null),
        )
    }
}

/**
 * Turn the Wizard's `/run` URL into its `/functions` sibling.
 *
 * The operator types one endpoint, not a base and a set of paths — asking for both would be asking
 * them to know the Wizard's routing table. The two endpoints are siblings on the same deployment, so
 * the second is derivable from the first, and a URL already pointing at `/functions` is left alone so
 * pasting either one works.
 *
 * Returns null for anything unusable, so the caller reports "enter the Wizard URL" rather than
 * silently POSTing credentials at a guessed address.
 */
internal fun functionsEndpoint(wizardUrl: String): String? {
    val trimmed = wizardUrl.trim().trimEnd('/')
    if (trimmed.isBlank() || !trimmed.startsWith("https://")) return null
    return when {
        trimmed.endsWith("/functions") -> trimmed
        trimmed.endsWith("/run") -> trimmed.removeSuffix("/run") + "/functions"
        // A bare origin: assume the Wizard's standard route rather than failing, since that is the
        // one layout every deployment of this Wizard has.
        else -> "$trimmed/api/provision/functions"
    }
}

data class StepResult(
    val step: String,
    val status: String,
    val detail: String? = null,
) {
    val isOk: Boolean get() = status == "ok"
    val isError: Boolean get() = status == "error"
}

data class ProvisionResult(
    val results: List<StepResult>,
    val supabaseUrl: String? = null,
    val supabaseAnonKey: String? = null,
    val supabaseServiceRoleKey: String? = null,
    val websiteUrl: String? = null,
    val cafeName: String? = null,
    val ownerKeyUrl: String? = null,
) {
    val success: Boolean get() = results.isNotEmpty() && results.none { it.isError }
}

/** Form data for the installer screen. High-privilege credentials are only kept in ViewModel state. */
data class ProvisionRequest(
    /**
     * The provisioning Wizard's `/api/provision/run` endpoint, typed on the Provision screen.
     *
     * Part of the request rather than a build constant so that one APK can point at a staging Wizard,
     * a disposable one, or a self-hosted deployment without being rebuilt — which is also the only way
     * the two unverified provisioning steps can be rehearsed against a throwaway project.
     */
    val provisionerWorkerUrl: String,
    val supabaseMode: SupabaseMode,
    val cloudflareMode: CloudflareMode,
    val cafeName: String,
    val brevoApiKey: String? = null,
)

sealed class SupabaseMode {
    abstract val personalAccessToken: String

    data class New(
        override val personalAccessToken: String,
        val orgId: String,
        val region: String,
        val projectName: String,
    ) : SupabaseMode()

    data class Existing(
        override val personalAccessToken: String,
        val projectRef: String,
        val anonKey: String,
        val serviceRoleKey: String,
    ) : SupabaseMode()
}

sealed class CloudflareMode {
    abstract val accountId: String
    abstract val apiToken: String
    abstract val customDomain: String?
    abstract val zoneId: String?

    data class New(
        override val accountId: String,
        override val apiToken: String,
        val cafeSlug: String,
        override val zoneId: String? = null,
        override val customDomain: String? = null,
    ) : CloudflareMode()

    data class Existing(
        override val accountId: String,
        override val apiToken: String,
        val projectName: String,
        override val zoneId: String? = null,
        override val customDomain: String? = null,
    ) : CloudflareMode()
}

private fun ProvisionRequest.toJson(): JSONObject {
    val supabaseJson = when (supabaseMode) {
        is SupabaseMode.New -> JSONObject().apply {
            put("mode", "new")
            put("personalAccessToken", supabaseMode.personalAccessToken)
            put("orgId", supabaseMode.orgId)
            put("region", supabaseMode.region)
            put("projectName", supabaseMode.projectName)
        }
        is SupabaseMode.Existing -> JSONObject().apply {
            put("mode", "existing")
            put("personalAccessToken", supabaseMode.personalAccessToken)
            put("projectRef", supabaseMode.projectRef)
            put("anonKey", supabaseMode.anonKey)
            put("serviceRoleKey", supabaseMode.serviceRoleKey)
        }
    }

    val cloudflareJson = when (cloudflareMode) {
        is CloudflareMode.New -> JSONObject().apply {
            put("mode", "new")
            put("accountId", cloudflareMode.accountId)
            put("apiToken", cloudflareMode.apiToken)
            put("cafeSlug", cloudflareMode.cafeSlug)
            putOpt("zoneId", cloudflareMode.zoneId)
            putOpt("customDomain", cloudflareMode.customDomain)
        }
        is CloudflareMode.Existing -> JSONObject().apply {
            put("mode", "existing")
            put("accountId", cloudflareMode.accountId)
            put("apiToken", cloudflareMode.apiToken)
            put("projectName", cloudflareMode.projectName)
            putOpt("zoneId", cloudflareMode.zoneId)
            putOpt("customDomain", cloudflareMode.customDomain)
        }
    }

    return JSONObject().apply {
        put("supabase", supabaseJson)
        put("cloudflare", cloudflareJson)
        put("cafe", JSONObject().apply {
            put("cafeName", cafeName)
            putOpt("brevoApiKey", brevoApiKey)
        })
    }
}
