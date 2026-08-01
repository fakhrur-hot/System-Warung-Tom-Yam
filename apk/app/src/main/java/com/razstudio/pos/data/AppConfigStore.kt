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

        /**
         * The LAN Server's base URL on a **Client** Device (tasks 7.2, 7.3).
         *
         * Kept separate from [KEY_SUPABASE_URL] rather than reusing it. They mean different things
         * and have opposite lifecycles: switching to LAN Mode *clears* the Supabase URL (task 9.3),
         * and a Client then *sets* this one — overloading a single key would make those two writes
         * fight, and would leave "is this a cloud project or a phone on the counter?" undecidable
         * from storage.
         */
        private const val KEY_LAN_SERVER_URL = "lan_server_url"
        private const val KEY_SUPABASE_ANON_KEY = "supabase_anon_key"
        private const val KEY_WEBSITE_URL = "website_url"
        private const val KEY_CAFE_NAME = "cafe_name"

        // Operating mode (Requirement 1.1 / 1.2)
        private const val KEY_OPERATING_MODE = "operating_mode"

        // Payment QR content-hash and resolved URL (Requirements 14.8, 14.9)
        // Absent = not configured = Show QR button hidden (no separate enabled flag needed)
        private const val KEY_PAYMENT_QR_HASH = "payment_qr_hash"
        private const val KEY_PAYMENT_QR_URL = "payment_qr_url"

        // Stored vault (not consumed by the app at runtime)
        private const val KEY_CF_ACCOUNT_ID = "cloudflare_account_id"
        private const val KEY_CF_DNS_ZONE = "cloudflare_dns_zone"
        private const val KEY_CF_API_TOKEN = "cloudflare_api_token"
        private const val KEY_CF_PAGES_PROJECT = "cloudflare_pages_project"
        private const val KEY_GH_REPO = "github_repo"
        private const val KEY_GH_TOKEN = "github_token"
    }

    private var prefs: SharedPreferences? = createEncryptedPrefs()

    /**
     * Test-only seam. Substitutes the backing store so this class's logic can be unit-tested.
     *
     * It is needed because [EncryptedSharedPreferences] requires the Android Keystore, which
     * Robolectric does not provide: under test [createEncryptedPrefs] fails, the store takes its
     * documented degrade-to-unconfigured path, and every write is silently dropped. That made
     * everything persisted here — `operating_mode`, the Payment QR hash, the Supabase settings —
     * impossible to cover, and a regression in any of it would have passed CI unnoticed.
     *
     * Deliberately NOT solved by falling back to plain [SharedPreferences] in production. This store
     * holds Supabase keys and Cloudflare tokens; silently writing those in plaintext when the keystore
     * misbehaves would be a far worse outcome than reporting "unconfigured". The fallback to `null`
     * stays exactly as it is, and only tests swap the implementation.
     *
     * Hilt is unaffected: it uses the `@Inject` primary constructor and never sees this one.
     */
    internal constructor(context: Context, backingStoreForTest: SharedPreferences) : this(context) {
        prefs = backingStoreForTest
    }

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

    /** The paired LAN Server's base URL, e.g. `http://192.168.43.1:8765`. Blank when unpaired. */
    fun lanServerUrl(): String = read(KEY_LAN_SERVER_URL)

    /**
     * Remember where the Server was last reached (task 7.3).
     *
     * Persisted rather than held in memory because address-change recovery starts by retrying the
     * last known address, and the common way a café meets that problem is the tablet rebooting and
     * the hotspot coming back on a different subnet — by which time nothing in memory survived.
     */
    fun setLanServerUrl(url: String) {
        write(KEY_LAN_SERVER_URL, url.trim().trimEnd('/'))
    }
    fun supabaseAnonKey(): String = read(KEY_SUPABASE_ANON_KEY)
    fun websiteUrl(): String = read(KEY_WEBSITE_URL)
    fun cafeName(): String = read(KEY_CAFE_NAME)

    /**
     * Update ONLY the café name, leaving the Supabase connection and every other stored value
     * untouched. The Setup Wizard's [save] sets the initial café name alongside the connection
     * fields; Admin Settings' Café Profile calls this narrower setter when the admin renames the
     * café later, so a rename can never accidentally clobber the connection settings that must
     * persist once Setup has succeeded.
     */
    fun setCafeName(name: String) {
        write(KEY_CAFE_NAME, name)
    }

    /**
     * Returns the persisted [OperatingMode], defaulting to [OperatingMode.CLOUD] when absent.
     * The CLOUD default ensures existing installs are unaffected — a device with no stored
     * `operating_mode` key continues to operate exactly as it did before this key existed
     * (Requirement 1.2).
     */
    fun operatingMode(): OperatingMode {
        val stored = read(KEY_OPERATING_MODE)
        return OperatingMode.entries.firstOrNull { it.name == stored } ?: OperatingMode.CLOUD
    }

    /** Persists the given [OperatingMode] as its enum name string. */
    fun setOperatingMode(mode: OperatingMode) {
        write(KEY_OPERATING_MODE, mode.name)
    }

    // --- Payment QR persistence (Requirements 14.8, 14.9) ---

    /**
     * Returns the SHA-256 hex digest of the stored Payment QR image, or null when absent.
     * Null means the Payment QR is not configured — the Show QR button should be hidden.
     */
    fun paymentQrHash(): String? = read(KEY_PAYMENT_QR_HASH).ifEmpty { null }

    /**
     * Persists [hash] (SHA-256 hex of the stored image file) or removes the key when null.
     * Removing the key hides the Show QR button on this device.
     */
    fun setPaymentQrHash(hash: String?) {
        if (hash == null) {
            try {
                (prefs ?: createEncryptedPrefs().also { prefs = it })
                    ?.edit()?.remove(KEY_PAYMENT_QR_HASH)?.apply()
            } catch (e: Exception) {
                Log.e(TAG, "Config remove failed ($KEY_PAYMENT_QR_HASH); clearing", e)
                clearCorruptedPrefs(); prefs = null
            }
        } else {
            write(KEY_PAYMENT_QR_HASH, hash)
        }
    }

    /**
     * Returns the resolved URL for the Payment QR image (used for distribution to staff
     * devices), or null when absent.
     */
    fun paymentQrUrl(): String? = read(KEY_PAYMENT_QR_URL).ifEmpty { null }

    /**
     * Persists [url] or removes the key when null.
     */
    fun setPaymentQrUrl(url: String?) {
        if (url == null) {
            try {
                (prefs ?: createEncryptedPrefs().also { prefs = it })
                    ?.edit()?.remove(KEY_PAYMENT_QR_URL)?.apply()
            } catch (e: Exception) {
                Log.e(TAG, "Config remove failed ($KEY_PAYMENT_QR_URL); clearing", e)
                clearCorruptedPrefs(); prefs = null
            }
        } else {
            write(KEY_PAYMENT_QR_URL, url)
        }
    }

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
