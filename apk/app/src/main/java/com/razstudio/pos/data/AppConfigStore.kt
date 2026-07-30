package com.razstudio.pos.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime deployment configuration for the generic (template) build.
 *
 * The template APK ships with NO baked-in café identity. Instead an operator fills these values in
 * the in-app Setup screen (three-dots on the login page) and they are stored encrypted here, so a
 * single template build can point at any café's backend. Café-specific builds may still bake the
 * connection values at compile time via `local.properties`/`BuildConfig`; consumers should prefer a
 * runtime value when present and fall back to `BuildConfig` otherwise.
 *
 * Fields split into two groups:
 * - **Used by the running app**: [supabaseUrl], [supabaseAnonKey], [websiteUrl], [cafeName].
 * - **Stored vault** (kept encrypted for the operator's reference / future deploy tooling; the app
 *   does not call these services itself): Cloudflare + GitHub credentials.
 *
 * Storage mirrors [SecureStorage]: EncryptedSharedPreferences with graceful clear-on-corruption so a
 * budget-OEM keystore hiccup degrades to "unconfigured" rather than crashing.
 */
@Singleton
class AppConfigStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AppConfigStore"
        private const val PREFS_FILE = "app_config_prefs"

        // Used by the running app
        private const val KEY_SUPABASE_URL = "supabase_url"
        private const val KEY_SUPABASE_ANON_KEY = "supabase_anon_key"
        private const val KEY_WEBSITE_URL = "website_url"
        private const val KEY_CAFE_NAME = "cafe_name"

        // Stored vault (not consumed by the app at runtime)
        private const val KEY_CF_ACCOUNT_ID = "cloudflare_account_id"
        private const val KEY_CF_DNS_ZONE = "cloudflare_dns_zone"
        private const val KEY_CF_API_TOKEN = "cloudflare_api_token"
        private const val KEY_CF_PAGES_PROJECT = "cloudflare_pages_project"
        private const val KEY_GH_REPO = "github_repo"
        private const val KEY_GH_TOKEN = "github_token"
    }

    private var prefs: SharedPreferences? = createEncryptedPrefs()

    private fun createEncryptedPrefs(): SharedPreferences? = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Encrypted prefs init failed; clearing", e)
        clearCorruptedPrefs()
        null
    }

    private fun clearCorruptedPrefs() {
        try {
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().apply()
            context.filesDir.parentFile?.resolve("shared_prefs/$PREFS_FILE.xml")?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear corrupted config prefs", e)
        }
    }

    private fun read(key: String): String = try {
        prefs?.getString(key, null).orEmpty()
    } catch (e: Exception) {
        Log.e(TAG, "Config read failed ($key); clearing", e)
        clearCorruptedPrefs(); prefs = null
        ""
    }

    private fun write(key: String, value: String) {
        try {
            (prefs ?: createEncryptedPrefs().also { prefs = it })
                ?.edit()?.putString(key, value.trim())?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Config write failed ($key); clearing", e)
            clearCorruptedPrefs(); prefs = null
        }
    }

    // --- Used by the running app ---
    fun supabaseUrl(): String = read(KEY_SUPABASE_URL)
    fun supabaseAnonKey(): String = read(KEY_SUPABASE_ANON_KEY)
    fun websiteUrl(): String = read(KEY_WEBSITE_URL)
    fun cafeName(): String = read(KEY_CAFE_NAME)

    // --- Stored vault ---
    fun cloudflareAccountId(): String = read(KEY_CF_ACCOUNT_ID)
    fun cloudflareDnsZone(): String = read(KEY_CF_DNS_ZONE)
    fun cloudflareApiToken(): String = read(KEY_CF_API_TOKEN)
    fun cloudflarePagesProject(): String = read(KEY_CF_PAGES_PROJECT)
    fun githubRepo(): String = read(KEY_GH_REPO)
    fun githubToken(): String = read(KEY_GH_TOKEN)

    /** True once the minimum needed to reach a backend (URL + anon key) is present. */
    fun isConfigured(): Boolean = supabaseUrl().isNotBlank() && supabaseAnonKey().isNotBlank()

    /** Persist the whole config in one shot from the Setup screen. Blank = leave/clear that field. */
    fun save(
        supabaseUrl: String,
        supabaseAnonKey: String,
        websiteUrl: String,
        cafeName: String,
        cloudflareAccountId: String,
        cloudflareDnsZone: String,
        cloudflareApiToken: String,
        cloudflarePagesProject: String,
        githubRepo: String,
        githubToken: String,
    ) {
        write(KEY_SUPABASE_URL, supabaseUrl)
        write(KEY_SUPABASE_ANON_KEY, supabaseAnonKey)
        write(KEY_WEBSITE_URL, websiteUrl)
        write(KEY_CAFE_NAME, cafeName)
        write(KEY_CF_ACCOUNT_ID, cloudflareAccountId)
        write(KEY_CF_DNS_ZONE, cloudflareDnsZone)
        write(KEY_CF_API_TOKEN, cloudflareApiToken)
        write(KEY_CF_PAGES_PROJECT, cloudflarePagesProject)
        write(KEY_GH_REPO, githubRepo)
        write(KEY_GH_TOKEN, githubToken)
    }
}
