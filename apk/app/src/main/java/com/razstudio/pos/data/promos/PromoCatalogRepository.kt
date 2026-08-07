package com.razstudio.pos.data.promos

import android.util.Base64
import android.util.Log
import com.razstudio.pos.BuildConfig
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** One affiliate placement, as the customer web reads it. */
data class PromoProduct(
    val href: String,
    val img: String = "",
    val alt: String = "",
)

/**
 * Read and write the central affiliate catalog that every café's web app fetches at runtime
 * (`promos/partners.json` on `main`).
 *
 * ### Why the app edits `main` directly
 *
 * `main` is the single source: each café's `partnerCatalogUrl` points at main's raw URL, so a café
 * never holds a copy and there is nothing to sync. Editing anywhere else would create the drift the
 * runtime fetch exists to prevent — so this writes through the GitHub Contents API to that one file,
 * and every café picks it up within the raw CDN's 5-minute cache.
 *
 * ### The token is never baked into the build
 *
 * A PAT compiled into an APK leaks the moment the APK is shared, and a debug APK gets shared freely.
 * The caller supplies it and it lives in EncryptedSharedPreferences on the device that typed it.
 *
 * ### Resolving a shortlink
 *
 * Shopee short links resolve client-side, so a normal fetch returns a JS shell with no destination.
 * A **link-preview User-Agent** does get the real page with its `og:` tags — WhatsApp's works where
 * Facebook's and Twitter's are refused with 403. That is the only way found to fill `img`/`alt`
 * without the owner reading them off the Shopee app by hand.
 */
@Singleton
class PromoCatalogRepository @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Catalog(
        val subId: String,
        val products: List<PromoProduct>,
        /** Blob sha of the file this was read from; required by GitHub to write without clobbering. */
        val sha: String?,
        /** Everything else in the file, preserved verbatim so a save cannot drop keys it never read. */
        val raw: JSONObject,
    )

    /** Read the live catalog through the GitHub API (not raw), because a write needs the blob sha. */
    suspend fun load(token: String): Result<Catalog> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(CONTENTS_URL)
                .addHeader("Accept", "application/vnd.github+json")
                .apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("GitHub ${response.code}: ${body.take(180)}")
                val envelope = JSONObject(body)
                val decoded = String(
                    Base64.decode(envelope.getString("content"), Base64.DEFAULT),
                    Charsets.UTF_8,
                )
                val json = JSONObject(decoded)
                val default = json.optJSONObject("default") ?: JSONObject()
                val products = default.optJSONArray("products") ?: JSONArray()
                Catalog(
                    subId = default.optString("subId", ""),
                    products = (0 until products.length()).mapNotNull { i ->
                        products.optJSONObject(i)?.let {
                            PromoProduct(
                                href = it.optString("href", ""),
                                img = it.optString("img", ""),
                                alt = it.optString("alt", ""),
                            )
                        }
                    },
                    sha = envelope.optString("sha").ifBlank { null },
                    raw = json,
                )
            }
        }
    }

    /**
     * Commit the edited list back to `main`.
     *
     * The rest of the file is written back untouched — `_comment`, `_compliance`, `byCafe` and any key
     * added later all survive, because a tool that silently drops what it does not understand is how
     * a per-café `subId` or the compliance note would disappear on the first save.
     */
    suspend fun save(
        token: String,
        catalog: Catalog,
        products: List<PromoProduct>,
        subId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(token.isNotBlank()) { "A GitHub token is required to publish." }
            val json = JSONObject(catalog.raw.toString())
            val default = json.optJSONObject("default") ?: JSONObject().also { json.put("default", it) }
            default.put("subId", subId)
            default.put(
                "products",
                JSONArray().apply {
                    products.forEach {
                        put(
                            JSONObject()
                                .put("href", it.href.trim())
                                .put("img", it.img.trim())
                                .put("alt", it.alt.trim()),
                        )
                    }
                },
            )
            val pretty = json.toString(2) + "\n"
            val payload = JSONObject()
                .put("message", "chore(promos): update affiliate catalog from the POS wizard")
                .put(
                    "content",
                    Base64.encodeToString(pretty.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                )
                .apply { catalog.sha?.let { put("sha", it) } }
                .put("branch", BRANCH)

            val request = Request.Builder()
                .url(CONTENTS_URL)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer $token")
                .put(payload.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                // 409 is the one worth naming: someone else wrote the file since it was loaded, and
                // saving would overwrite them. Reload rather than force.
                if (response.code == 409) error("The catalog changed on GitHub — reload before saving.")
                if (!response.isSuccessful) error("GitHub ${response.code}: ${body.take(180)}")
            }
        }
    }

    /**
     * Resolve a Shopee short link to its title and creative, so an entry can be added by pasting one
     * link instead of typing three fields on a phone.
     *
     * Returns a product with the shortlink **unchanged** as `href`: it must stay the clickable
     * destination or the affiliate commission does not track. Only `img`/`alt` come from the page.
     */
    suspend fun resolve(shortLink: String): Result<PromoProduct> = withContext(Dispatchers.IO) {
        runCatching {
            val href = shortLink.trim()
            require(href.startsWith("https://")) { "That does not look like a link." }
            val request = Request.Builder()
                .url(href)
                // See the class note: a link-preview UA is what gets the real page.
                .addHeader("User-Agent", PREVIEW_UA)
                .get()
                .build()
            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Shopee returned ${response.code}")
                response.body?.string().orEmpty()
            }
            PromoProduct(
                href = href,
                img = OG_IMAGE.find(html)?.groupValues?.get(1).orEmpty(),
                alt = OG_TITLE.find(html)?.groupValues?.get(1)
                    ?.let { cleanTitle(it) }
                    .orEmpty(),
            ).also {
                if (it.img.isBlank()) Log.w(TAG, "No og:image on $href — entry will show the fallback")
            }
        }
    }

    /** Shopee suffixes every title with its own branding, which is noise inside a café's menu. */
    private fun cleanTitle(raw: String): String = raw
        .replace("&amp;", "&").replace("&#x27;", "'").replace("&quot;", "\"")
        .substringBefore(" | Shopee")
        .trim()
        .take(60)

    private companion object {
        const val TAG = "PromoCatalog"
        const val BRANCH = "main"
        // Owner/repo baked from template-repo.properties, same source the Wizard and the preset
        // catalog use — the affiliate catalog and the café website must come from one repository.
        val CONTENTS_URL =
            "https://api.github.com/repos/${BuildConfig.RAZSTUDIO_GITHUB_OWNER}/" +
                "${BuildConfig.RAZSTUDIO_GITHUB_REPO}/contents/promos/partners.json"
        const val PREVIEW_UA = "WhatsApp/2.23.20.0"
        val JSON = "application/json; charset=utf-8".toMediaType()
        val OG_TITLE = Regex("""og:title"\s+content="([^"]{1,200})""")
        val OG_IMAGE = Regex("""og:image"\s+content="([^"]{1,300})""")
    }
}
