package com.razstudio.pos.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.razstudio.pos.data.local.PaymentMethod
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Payment-gateway credentials, encrypted at rest. (PG-REQ-2, PG-REQ-8, task 5.3)
 *
 * Mirrors [SecureStorage]'s handling of a dead Android Keystore, which this project has already
 * been bitten by on budget OEM devices: `MasterKeys.getOrCreate` throws, `prefs` stays null, and
 * every read silently returns null. Here that is *safe* — no credentials means the gateway is
 * simply unconfigured and the café falls back to cash — but it must be visible, so
 * [isKeystoreHealthy] lets the settings screen say so rather than looking like the owner never
 * typed anything.
 *
 * **What is secret and what is not** (designs.md F2, F3):
 * - [merchantId] is **not** secret. The evaluated aggregator puts it in the payment URL path, so
 *   treating it as a secret would be security theatre. Stored here only for cohesion.
 * - [verifyKey] and [secretKey] **are** secret. They hash callback signatures and requeries.
 *
 * The secret is **write-only after entry** — [hasSecretKey] answers "is one set" without returning
 * it, so no screen can render it and no log can capture it. The app itself never needs to read the
 * secret back: signatures are computed server-side in the Edge Function, because computing them
 * here would require shipping the merchant secret to every café's device. (F3)
 */
@Singleton
class GatewayCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "GatewayCreds"
        const val PREFS_FILE = "gateway_credentials"

        const val KEY_MERCHANT_ID = "merchant_id"
        const val KEY_VERIFY_KEY = "verify_key"
        const val KEY_SECRET_KEY = "secret_key"
        const val KEY_SANDBOX = "sandbox"
        const val KEY_ENABLED_METHODS = "enabled_methods"
    }

    private var prefs: SharedPreferences? = createEncryptedPrefs()

    /** Test seam, matching [SecureStorage]'s. Hilt never sees this constructor. */
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
        // Deliberately not clearing the file here, unlike SecureStorage: a transiently unavailable
        // keystore would otherwise destroy credentials the owner typed once and would have to find
        // again from their acquirer.
        Log.e(TAG, "Encrypted store unavailable; gateway will read as unconfigured", e)
        null
    }

    private fun store(): SharedPreferences? {
        if (prefs == null) prefs = createEncryptedPrefs()
        return prefs
    }

    /**
     * False when the encrypted store could not be opened. Credentials cannot be saved or read on
     * this device, and the settings screen should say so instead of showing an empty form that
     * silently discards what is typed into it.
     */
    fun isKeystoreHealthy(): Boolean = store() != null

    // ── Merchant identity (not secret) ───────────────────────────────────────────────────────

    var merchantId: String?
        get() = read(KEY_MERCHANT_ID)
        set(value) = write(KEY_MERCHANT_ID, value)

    // ── Secrets ──────────────────────────────────────────────────────────────────────────────

    /** Write-only from the UI's point of view — see [hasVerifyKey]. */
    fun setVerifyKey(value: String?) = write(KEY_VERIFY_KEY, value)

    /** Write-only from the UI's point of view — see [hasSecretKey]. */
    fun setSecretKey(value: String?) = write(KEY_SECRET_KEY, value)

    fun hasVerifyKey(): Boolean = !read(KEY_VERIFY_KEY).isNullOrBlank()

    fun hasSecretKey(): Boolean = !read(KEY_SECRET_KEY).isNullOrBlank()

    /**
     * Reads the secrets back.
     *
     * Exists only for the path that ships them to the backend once, over TLS, so the Edge Function
     * can sign with them. **Nothing in the UI layer may call this**, and the result must never be
     * logged or placed in a crash report. (PG-REQ-8)
     */
    internal fun readSecretsForUpload(): Pair<String?, String?> =
        read(KEY_VERIFY_KEY) to read(KEY_SECRET_KEY)

    // ── Environment ──────────────────────────────────────────────────────────────────────────

    /**
     * Sandbox switches the **gateway host**, not only the credentials — the evaluated aggregator
     * serves sandbox from a different domain entirely, and requery from a third. A build that
     * swapped keys but kept the production host would fail in a way that reads as bad credentials.
     * (designs.md F2, PG-REQ-10)
     */
    var isSandbox: Boolean
        get() = try { store()?.getBoolean(KEY_SANDBOX, true) ?: true } catch (e: Exception) { true }
        set(value) {
            try { store()?.edit()?.putBoolean(KEY_SANDBOX, value)?.apply() }
            catch (e: Exception) { Log.e(TAG, "Could not persist sandbox flag", e) }
        }

    // ── Enabled channels ─────────────────────────────────────────────────────────────────────

    /**
     * Which gateway methods this café has contracted. Empty means none — a café that has not
     * finished onboarding sees only cash and its static QR, which is the correct default rather
     * than offering tiles that will fail at the counter. (PG-REQ-2)
     */
    var enabledMethods: Set<PaymentMethod>
        get() = read(KEY_ENABLED_METHODS)
            ?.split(',')
            ?.mapNotNull { PaymentMethod.fromCode(it.trim()) }
            ?.toSet()
            .orEmpty()
        set(value) = write(KEY_ENABLED_METHODS, value.joinToString(",") { it.code })

    /** Wipe everything. Used when an owner disconnects the gateway. */
    fun clear() {
        try { store()?.edit()?.clear()?.apply() }
        catch (e: Exception) { Log.e(TAG, "Could not clear gateway credentials", e) }
    }

    private fun read(key: String): String? = try {
        store()?.getString(key, null)
    } catch (e: Exception) {
        Log.e(TAG, "Read failed ($key)", e)
        null
    }

    private fun write(key: String, value: String?) {
        try {
            val editor = store()?.edit() ?: return
            if (value.isNullOrBlank()) editor.remove(key) else editor.putString(key, value)
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Write failed ($key)", e)
        }
    }
}
