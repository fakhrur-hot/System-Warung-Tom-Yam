package com.razstudio.pos.data.local

import android.content.Context
import android.util.Log
import com.razstudio.pos.BuildConfig
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One starter menu a café can adopt on a fresh install.
 *
 * Described by the index rather than by opening the (80 KB+) payload, so the picker can show
 * counts without parsing every preset.
 */
data class MenuPreset(
    val presetId: String,
    val presetName: String,
    val description: String,
    val cuisine: String,
    val categoryCount: Int,
    val itemCount: Int,
    /** Where the payload comes from when this preset is chosen. */
    val source: Source,
) {
    sealed interface Source {
        /** A file inside `assets/presets/`. Always available, works offline. */
        data class Bundled(val assetPath: String) : Source
        /** An absolute URL. Cloud Mode only — see [MenuPresetCatalog]. */
        data class Remote(val url: String) : Source
    }
}

/**
 * Lists the menu presets a café can choose from. (Load sample menu)
 *
 * **Bundled presets are discovered, not hardcoded.** `assets/presets/index.json` names them, so
 * adding a starter menu is dropping a JSON file and adding a line to the index — no Kotlin change,
 * and no risk of a preset existing on disk that the picker cannot see.
 *
 * **Remote presets are Cloud Mode only.** A LAN or Kiosk café has no route to the internet by
 * design — `NoInternetGuard` blocks non-local hosts — so offering a downloadable preset there would
 * produce a tile that always fails. They are omitted rather than shown greyed, because unlike a
 * piece of hardware a café might buy, this is not something the owner can act on.
 *
 * A remote fetch that fails is never fatal: the bundled list is returned and the café can still set
 * up its till. A starter menu is a convenience, not a dependency.
 */
@Singleton
class MenuPresetCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modeRepository: ModeRepository,
) {
    companion object {
        private const val TAG = "MenuPresetCatalog"
        private const val BUNDLED_DIR = "presets"
        private const val BUNDLED_INDEX = "$BUNDLED_DIR/index.json"

        /**
         * Where shared presets are published. Raw GitHub rather than an API endpoint: it needs no
         * key, no Edge Function and no Supabase row, and a café's starter menu is public data.
         *
         * Owner/repo are baked from `template-repo.properties` at the monorepo root — the same file
         * the provisioning Wizard derives from, so the repo a café's website is deployed from and the
         * repo its starter menu comes from cannot drift apart. A fork edits that one file.
         */
        val REMOTE_INDEX_URL =
            "https://raw.githubusercontent.com/${BuildConfig.RAZSTUDIO_GITHUB_OWNER}/" +
                "${BuildConfig.RAZSTUDIO_GITHUB_REPO}/main/presets/index.json"

        private const val TIMEOUT_SECONDS = 10L
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** Every preset this device can offer right now, bundled first. */
    suspend fun list(): List<MenuPreset> = withContext(Dispatchers.IO) {
        val bundled = readBundledIndex()
        val remote = if (modeRepository.currentMode() == OperatingMode.CLOUD) {
            readRemoteIndex()
        } else {
            emptyList()
        }
        // A remote preset that repeats a bundled id is dropped: the bundled copy needs no
        // download and cannot fail mid-setup.
        val bundledIds = bundled.map { it.presetId }.toSet()
        bundled + remote.filterNot { it.presetId in bundledIds }
    }

    /** The preset's payload JSON, fetched or read as its [MenuPreset.Source] requires. */
    suspend fun payload(preset: MenuPreset): String = withContext(Dispatchers.IO) {
        when (val src = preset.source) {
            is MenuPreset.Source.Bundled -> readAsset(src.assetPath)
            is MenuPreset.Source.Remote -> fetch(src.url)
        }
    }

    private fun readBundledIndex(): List<MenuPreset> = try {
        parseIndex(readAsset(BUNDLED_INDEX)) { file -> MenuPreset.Source.Bundled("$BUNDLED_DIR/$file") }
    } catch (e: Exception) {
        Log.e(TAG, "Bundled preset index unreadable", e)
        emptyList()
    }

    private fun readRemoteIndex(): List<MenuPreset> = try {
        val base = REMOTE_INDEX_URL.substringBeforeLast('/')
        parseIndex(fetch(REMOTE_INDEX_URL)) { file ->
            // Entries may carry a full URL or a filename relative to the index.
            MenuPreset.Source.Remote(if (file.startsWith("http")) file else "$base/$file")
        }
    } catch (e: Exception) {
        // Expected offline, on a captive portal, or if the index has not been published. The café
        // keeps every bundled preset.
        Log.i(TAG, "Remote presets unavailable (${e.message}); using bundled only")
        emptyList()
    }

    private fun parseIndex(json: String, source: (String) -> MenuPreset.Source): List<MenuPreset> {
        val arr = JSONObject(json).optJSONArray("presets") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val file = o.optString("file", "")
                if (file.isBlank()) continue
                add(
                    MenuPreset(
                        presetId = o.optString("presetId", file.substringBeforeLast('.')),
                        presetName = o.optString("presetName", file),
                        description = o.optString("description", ""),
                        cuisine = o.optString("cuisine", ""),
                        categoryCount = o.optInt("categoryCount", 0),
                        itemCount = o.optInt("itemCount", 0),
                        source = source(file),
                    )
                )
            }
        }
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            return response.body?.string() ?: error("Empty body for $url")
        }
    }
}
