package com.razstudio.opsapp.data.promos

import com.razstudio.opsapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 1 fallback fetcher that reads the affiliate catalog directly from the
 * GitHub raw URL (`promos/partners.json` on `main`).
 *
 * Ported from `apk/app`'s `com.razstudio.pos.data.promos.AffiliateCatalogFetcher`. Used when the
 * Shopee Affiliate API credentials are not configured (no APP_ID/SECRET in local.properties).
 */
@Singleton
class AffiliateCatalogFetcher @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): List<PromoProduct> = withContext(Dispatchers.IO) {
        try {
            val url = "https://raw.githubusercontent.com/" +
                "${BuildConfig.RAZSTUDIO_GITHUB_OWNER}/" +
                "${BuildConfig.RAZSTUDIO_GITHUB_REPO}/main/promos/partners.json"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) return@withContext emptyList()

            val json = JSONObject(body)
            val default = json.optJSONObject("default") ?: return@withContext emptyList()
            val products = default.optJSONArray("products") ?: return@withContext emptyList()

            (0 until products.length()).mapNotNull { i ->
                products.optJSONObject(i)?.let { obj ->
                    PromoProduct(
                        href = obj.optString("href", ""),
                        img = obj.optString("img", ""),
                        alt = obj.optString("alt", ""),
                    )
                }
            }.filter { it.href.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
