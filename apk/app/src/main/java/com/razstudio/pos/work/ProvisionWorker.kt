package com.razstudio.pos.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.razstudio.pos.data.CloudflareMode
import com.razstudio.pos.data.ProvisionRequest
import com.razstudio.pos.data.ProvisionResult
import com.razstudio.pos.data.ProvisionerClient
import com.razstudio.pos.data.StepResult
import com.razstudio.pos.data.SupabaseMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * WorkManager worker that runs the backend provisioning call in the background.
 *
 * Provisioning can take several minutes (Supabase project creation + 33 Edge Function deploys), so
 * doing it on a ViewModel coroutine would be lost on configuration change or screen-off. WorkManager
 * keeps it alive under the normal OS constraints.
 */
class ProvisionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val request = parseRequest(inputData) ?: return Result.failure(
            errorOutput("No provisioning request was provided to the worker.")
        )

        return try {
            val client = ProvisionerClient()
            val result = client.provision(request)
            if (result.success) {
                Result.success(result.toOutputData())
            } else {
                Result.failure(result.toOutputData())
            }
        } catch (e: Exception) {
            Result.failure(errorOutput(e.message ?: "Provisioning failed"))
        }
    }

    private fun parseRequest(data: Data): ProvisionRequest? {
        val json = data.getString(KEY_REQUEST_JSON) ?: return null
        val obj = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }

        val cafeName = obj.optString("cafeName", "")
        val brevoApiKey = obj.optString("brevoApiKey", null)

        val supabaseObj = obj.getJSONObject("supabase")
        val supabaseMode = when (supabaseObj.optString("mode", "")) {
            "new" -> SupabaseMode.New(
                personalAccessToken = supabaseObj.getString("personalAccessToken"),
                orgId = supabaseObj.getString("orgId"),
                region = supabaseObj.getString("region"),
                projectName = supabaseObj.getString("projectName"),
            )
            "existing" -> SupabaseMode.Existing(
                personalAccessToken = supabaseObj.getString("personalAccessToken"),
                projectRef = supabaseObj.getString("projectRef"),
                anonKey = supabaseObj.getString("anonKey"),
                serviceRoleKey = supabaseObj.getString("serviceRoleKey"),
            )
            else -> return null
        }

        val cloudflareObj = obj.getJSONObject("cloudflare")
        val cloudflareMode = when (cloudflareObj.optString("mode", "")) {
            "new" -> CloudflareMode.New(
                accountId = cloudflareObj.getString("accountId"),
                apiToken = cloudflareObj.getString("apiToken"),
                cafeSlug = cloudflareObj.getString("cafeSlug"),
                zoneId = cloudflareObj.optString("zoneId", null),
                customDomain = cloudflareObj.optString("customDomain", null),
            )
            "existing" -> CloudflareMode.Existing(
                accountId = cloudflareObj.getString("accountId"),
                apiToken = cloudflareObj.getString("apiToken"),
                projectName = cloudflareObj.getString("projectName"),
                zoneId = cloudflareObj.optString("zoneId", null),
                customDomain = cloudflareObj.optString("customDomain", null),
            )
            else -> return null
        }

        return ProvisionRequest(
            supabaseMode = supabaseMode,
            cloudflareMode = cloudflareMode,
            cafeName = cafeName,
            brevoApiKey = brevoApiKey,
        )
    }

    private fun ProvisionResult.toOutputData(): Data {
        val resultsJson = JSONArray()
        results.forEach { r ->
            resultsJson.put(JSONObject().apply {
                put("step", r.step)
                put("status", r.status)
                putOpt("detail", r.detail)
            })
        }
        val output = Data.Builder()
            .putString(KEY_RESULTS_JSON, resultsJson.toString())
            .putString(KEY_SUPABASE_URL, supabaseUrl)
            .putString(KEY_SUPABASE_ANON_KEY, supabaseAnonKey)
            .putString(KEY_WEBSITE_URL, websiteUrl)
            .putString(KEY_CAFE_NAME, cafeName)
            .putString(KEY_OWNER_KEY_URL, ownerKeyUrl)
        supabaseServiceRoleKey?.let { output.putString(KEY_SUPABASE_SERVICE_ROLE_KEY, it) }
        return output.build()
    }

    private fun errorOutput(message: String): Data {
        return Data.Builder()
            .putString(KEY_ERROR, message)
            .build()
    }

    companion object {
        const val WORK_NAME = "provision_cafe"

        private const val KEY_REQUEST_JSON = "request_json"
        private const val KEY_RESULTS_JSON = "results_json"
        private const val KEY_SUPABASE_URL = "supabase_url"
        private const val KEY_SUPABASE_ANON_KEY = "supabase_anon_key"
        private const val KEY_SUPABASE_SERVICE_ROLE_KEY = "supabase_service_role_key"
        private const val KEY_WEBSITE_URL = "website_url"
        private const val KEY_CAFE_NAME = "cafe_name"
        private const val KEY_OWNER_KEY_URL = "owner_key_url"
        private const val KEY_ERROR = "error"

        fun parseResult(data: Data): ParsedResult {
            val error = data.getString(KEY_ERROR)
            if (!error.isNullOrBlank()) {
                return ParsedResult.Failed(error)
            }

            val resultsJson = data.getString(KEY_RESULTS_JSON)
            val results = if (resultsJson.isNullOrBlank()) {
                emptyList()
            } else {
                val array = JSONArray(resultsJson)
                List(array.length()) { i ->
                    val obj = array.getJSONObject(i)
                    StepResult(
                        step = obj.optString("step", ""),
                        status = obj.optString("status", ""),
                        detail = obj.optString("detail", null),
                    )
                }
            }

            return ParsedResult.Success(
                results = results,
                supabaseUrl = data.getString(KEY_SUPABASE_URL),
                supabaseAnonKey = data.getString(KEY_SUPABASE_ANON_KEY),
                supabaseServiceRoleKey = data.getString(KEY_SUPABASE_SERVICE_ROLE_KEY),
                websiteUrl = data.getString(KEY_WEBSITE_URL),
                cafeName = data.getString(KEY_CAFE_NAME),
                ownerKeyUrl = data.getString(KEY_OWNER_KEY_URL),
            )
        }

        fun start(context: Context, request: ProvisionRequest): androidx.work.Operation {
            val json = JSONObject().apply {
                put("cafeName", request.cafeName)
                putOpt("brevoApiKey", request.brevoApiKey)
            }

            val supabaseJson = when (request.supabaseMode) {
                is SupabaseMode.New -> JSONObject().apply {
                    put("mode", "new")
                    put("personalAccessToken", request.supabaseMode.personalAccessToken)
                    put("orgId", request.supabaseMode.orgId)
                    put("region", request.supabaseMode.region)
                    put("projectName", request.supabaseMode.projectName)
                }
                is SupabaseMode.Existing -> JSONObject().apply {
                    put("mode", "existing")
                    put("personalAccessToken", request.supabaseMode.personalAccessToken)
                    put("projectRef", request.supabaseMode.projectRef)
                    put("anonKey", request.supabaseMode.anonKey)
                    put("serviceRoleKey", request.supabaseMode.serviceRoleKey)
                }
            }

            val cloudflareJson = when (request.cloudflareMode) {
                is CloudflareMode.New -> JSONObject().apply {
                    put("mode", "new")
                    put("accountId", request.cloudflareMode.accountId)
                    put("apiToken", request.cloudflareMode.apiToken)
                    put("cafeSlug", request.cloudflareMode.cafeSlug)
                    putOpt("zoneId", request.cloudflareMode.zoneId)
                    putOpt("customDomain", request.cloudflareMode.customDomain)
                }
                is CloudflareMode.Existing -> JSONObject().apply {
                    put("mode", "existing")
                    put("accountId", request.cloudflareMode.accountId)
                    put("apiToken", request.cloudflareMode.apiToken)
                    put("projectName", request.cloudflareMode.projectName)
                    putOpt("zoneId", request.cloudflareMode.zoneId)
                    putOpt("customDomain", request.cloudflareMode.customDomain)
                }
            }

            json.put("supabase", supabaseJson)
            json.put("cloudflare", cloudflareJson)

            val input = Data.Builder()
                .putString(KEY_REQUEST_JSON, json.toString())
                .build()

            val workRequest = OneTimeWorkRequestBuilder<ProvisionWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(input)
                .build()

            return WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                workRequest,
            )
        }
    }

    sealed class ParsedResult {
        data class Success(
            val results: List<StepResult>,
            val supabaseUrl: String?,
            val supabaseAnonKey: String?,
            val supabaseServiceRoleKey: String?,
            val websiteUrl: String?,
            val cafeName: String?,
            val ownerKeyUrl: String?,
        ) : ParsedResult()

        data class Failed(val error: String) : ParsedResult()
    }
}
