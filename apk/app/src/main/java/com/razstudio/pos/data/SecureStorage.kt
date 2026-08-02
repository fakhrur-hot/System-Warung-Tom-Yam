package com.razstudio.pos.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OEM-resilient encrypted storage for session tokens, API keys, and device identity.
 *
 * Wraps EncryptedSharedPreferences with full fallback handling for budget OEM devices
 * (Xiaomi MIUI, older Samsung Android 10–12) where Android Keystore keys can get
 * corrupted during OS updates or reboots. On any crypto failure, the corrupted prefs
 * are cleared gracefully — the caller gets null/empty and routes back to re-auth.
 */
@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SecureStorage"
        private const val PREFS_FILE = "secure_prefs"

        /**
         * A separate, unencrypted file for the installation id alone.
         *
         * Not in [PREFS_FILE]: that one is wiped wholesale on corruption, and the id must outlive
         * exactly that event. Nothing else may be written here.
         */
        private const val DEVICE_ID_PREFS = "device_identity"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SERVER_DEVICE_ID = "server_device_id"
        private const val KEY_ROLE = "role"
        private const val KEY_ADMIN_PIN = "admin_pin"
        private const val KEY_PIN_LOCK_ENABLED = "pin_lock_enabled"
    }

    // ADMIN = main admin (printer host). ADMIN_SECONDARY = full admin management but no
    // local printer — authenticates with an admin session token just like ADMIN.
    enum class Role { ADMIN, ORDERING, ADMIN_SECONDARY }

    private var prefs: SharedPreferences? = null

    init {
        prefs = createEncryptedPrefs()
    }

    /**
     * Test seam, mirroring [AppConfigStore]'s.
     *
     * `EncryptedSharedPreferences` needs a real Android Keystore, which Robolectric does not provide
     * — `MasterKeys.getOrCreate` fails, `prefs` stays null, and every read returns null. That makes
     * [getDeviceId] mint a fresh UUID on every call, so a test against the real implementation is not
     * merely awkward, it silently asserts nothing.
     *
     * The production fallback path is deliberately unchanged; only tests swap the backing store.
     * Hilt is unaffected: it uses the `@Inject` primary constructor and never sees this one.
     */
    internal constructor(context: Context, backingStoreForTest: SharedPreferences) : this(context) {
        prefs = backingStoreForTest
    }

    private fun createEncryptedPrefs(): SharedPreferences? {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: KeyStoreException) {
            Log.e(TAG, "KeyStore corrupted during init, clearing prefs", e)
            clearCorruptedPrefs()
            null
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Security exception during init, clearing prefs", e)
            clearCorruptedPrefs()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception during init, clearing prefs", e)
            clearCorruptedPrefs()
            null
        }
    }

    private fun clearCorruptedPrefs() {
        try {
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
            // Also try deleting the file directly
            val prefsFile = context.filesDir.parentFile?.resolve("shared_prefs/$PREFS_FILE.xml")
            prefsFile?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear corrupted prefs", e)
        }
    }

    private fun getPrefs(): SharedPreferences? {
        if (prefs == null) {
            prefs = createEncryptedPrefs()
        }
        return prefs
    }

    private fun safeRead(key: String): String? {
        return try {
            getPrefs()?.getString(key, null)
        } catch (e: KeyStoreException) {
            Log.e(TAG, "KeyStore corrupted on read ($key), clearing", e)
            handleCorruption()
            null
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Security exception on read ($key), clearing", e)
            handleCorruption()
            null
        } catch (e: InvalidKeyException) {
            Log.e(TAG, "Invalid key on read ($key), clearing", e)
            handleCorruption()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception on read ($key)", e)
            handleCorruption()
            null
        }
    }

    private fun safeWrite(key: String, value: String?): Boolean {
        return try {
            val editor = getPrefs()?.edit() ?: return false
            if (value != null) {
                editor.putString(key, value)
            } else {
                editor.remove(key)
            }
            editor.apply()
            true
        } catch (e: KeyStoreException) {
            Log.e(TAG, "KeyStore corrupted on write ($key), clearing", e)
            handleCorruption()
            false
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Security exception on write ($key), clearing", e)
            handleCorruption()
            false
        } catch (e: InvalidKeyException) {
            Log.e(TAG, "Invalid key on write ($key), clearing", e)
            handleCorruption()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception on write ($key)", e)
            handleCorruption()
            false
        }
    }

    private fun handleCorruption() {
        clearCorruptedPrefs()
        prefs = null
    }

    // --- Public API ---

    /**
     * This installation's identity, stable for as long as the app is installed.
     *
     * ## Why it does not live only in the encrypted store
     *
     * When the keystore is unavailable — a budget-OEM hiccup, a corrupted master key —
     * [createEncryptedPrefs] returns null and [safeWrite] quietly returns false. This function used
     * to mint a UUID, fail to store it, and mint a *different* one on the very next call. Every
     * request then carried a new `devices.device_identifier`, so one phone enrolled itself over and
     * over and the café's Devices screen filled with copies of the same handset.
     *
     * So the id is kept in three places, in order of preference: memory for this process, the
     * encrypted store, and a plain [SharedPreferences] file. Plaintext is deliberate and safe here —
     * unlike the tokens beside it, this is an opaque installation id that grants nothing. Losing it
     * is the harm; reading it is not.
     */
    fun getDeviceId(): String {
        cachedDeviceId?.let { return it }

        val stored = safeRead(KEY_DEVICE_ID) ?: plainPrefs().getString(KEY_DEVICE_ID, null)
        if (stored != null) {
            cachedDeviceId = stored
            // Heal whichever copy is missing, so a keystore that recovers stops depending on the
            // plaintext fallback — and one that never recovers still has somewhere to read from.
            safeWrite(KEY_DEVICE_ID, stored)
            plainPrefs().edit().putString(KEY_DEVICE_ID, stored).apply()
            return stored
        }

        val newId = UUID.randomUUID().toString()
        cachedDeviceId = newId
        safeWrite(KEY_DEVICE_ID, newId)
        plainPrefs().edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    /** In-process cache: the floor, for when neither store can be written at all. */
    @Volatile private var cachedDeviceId: String? = null

    private fun plainPrefs(): SharedPreferences =
        context.getSharedPreferences(DEVICE_ID_PREFS, Context.MODE_PRIVATE)

    /**
     * The id the **server** knows this device by, learned from `register`'s response.
     *
     * There are two device ids, and confusing them is what broke the staff join flow entirely:
     *
     *  - [getDeviceId] is the client-generated UUID. It is written to `devices.device_identifier`,
     *    and it is what `admin-handshake` and `admin-recovery` look up by.
     *  - This one is `devices.id`, the row's primary key, generated by Postgres on insert. It is what
     *    `devices-status`, `devices` and `attendance` look up by.
     *
     * `register` returns the second under the field name `deviceId` — which reads exactly like the
     * first, and is why every caller passed its local UUID to `devices-status` and got a permanent
     * `404 NOT_FOUND`. A staff device therefore registered successfully and then failed forever on
     * the very next call, which is why it never appeared to wait for approval.
     *
     * Falls back to [getDeviceId] when unset, so LAN Mode is untouched: `LocalBackend.register`
     * returns the client id as `deviceId`, because in that topology the two genuinely are the same.
     */
    fun getServerDeviceId(): String = safeRead(KEY_SERVER_DEVICE_ID) ?: getDeviceId()

    /** Remember the server's id for this device. Ignores blanks so a bad parse cannot poison it. */
    fun setServerDeviceId(id: String) {
        if (id.isNotBlank() && id != "null") safeWrite(KEY_SERVER_DEVICE_ID, id)
    }

    fun getSessionToken(): String? = safeRead(KEY_SESSION_TOKEN)

    /** Rejects blank or the literal "null" so a mis-parsed value can never become a credential. */
    fun setSessionToken(token: String): Boolean {
        if (!isStorableCredential(token)) {
            Log.w(TAG, "Refused to store invalid session token")
            return false
        }
        return safeWrite(KEY_SESSION_TOKEN, token)
    }

    fun getApiKey(): String? = safeRead(KEY_API_KEY)

    /** Rejects blank or the literal "null" so a mis-parsed value can never become a credential. */
    fun setApiKey(key: String): Boolean {
        if (!isStorableCredential(key)) {
            Log.w(TAG, "Refused to store invalid API key")
            return false
        }
        return safeWrite(KEY_API_KEY, key)
    }

    /** A credential is storable only if it is non-blank and not the literal string "null". */
    private fun isStorableCredential(value: String): Boolean =
        value.isNotBlank() && value != "null"

    fun getRole(): Role? {
        val value = safeRead(KEY_ROLE) ?: return null
        return try {
            Role.valueOf(value)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun setRole(role: Role): Boolean = safeWrite(KEY_ROLE, role.name)

    // --- Admin settings PIN lock (device-local, AES-256-GCM like all other credentials) ---

    /** Store the 4-digit admin PIN (encrypted). */
    fun saveAdminPin(pin: String): Boolean = safeWrite(KEY_ADMIN_PIN, pin)

    /** The stored admin PIN, or null if none set. */
    fun getAdminPin(): String? = safeRead(KEY_ADMIN_PIN)

    /** Remove the stored admin PIN. */
    fun clearAdminPin() {
        safeWrite(KEY_ADMIN_PIN, null)
    }

    /** Whether the admin has turned on the PIN gate for Settings. */
    fun isPinLockEnabled(): Boolean = safeRead(KEY_PIN_LOCK_ENABLED) == "true"

    fun setPinLockEnabled(enabled: Boolean): Boolean =
        safeWrite(KEY_PIN_LOCK_ENABLED, if (enabled) "true" else "false")

    /** Check if the user is authenticated (has a valid token/key for their role). */
    fun isAuthenticated(): Boolean {
        val role = getRole() ?: return false
        return when (role) {
            Role.ADMIN, Role.ADMIN_SECONDARY -> getSessionToken() != null
            Role.ORDERING -> getApiKey() != null
        }
    }

    /** Clear all stored credentials — routes user back to role selection. */
    /**
     * Clear every credential — but NOT this device's identity.
     *
     * ## Why the id survives a sign-out
     *
     * [KEY_DEVICE_ID] is written to `devices.device_identifier`, and that column is how the backend
     * decides whether a device it has seen before is signing in again or a new one is enrolling.
     * Wiping it made every sign-out mint a fresh UUID, so one physical phone accumulated a row per
     * sign-in: a café's Devices screen filled up with revoked copies of the same handset, and the
     * owner could not tell which entry was the phone in their hand.
     *
     * The identity is a property of the hardware, not of the session. Signing out ends a session.
     *
     * The server-assigned id is cleared, deliberately: it belongs to a registration, and the next
     * one will hand back a fresh one. Keeping a stale `devices.id` would point later calls at a row
     * this device no longer owns.
     */
    fun clearAll() {
        val deviceId = safeRead(KEY_DEVICE_ID)
        try {
            getPrefs()?.edit()?.clear()?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear prefs normally, forcing", e)
            handleCorruption()
        }
        // Restored after the wipe rather than excluded from it: EncryptedSharedPreferences has no
        // "clear everything except" and enumerating the keys to remove would silently miss any key
        // added later.
        val keep = deviceId ?: cachedDeviceId
        if (keep != null) {
            cachedDeviceId = keep
            safeWrite(KEY_DEVICE_ID, keep)
        }
    }

    /**
     * Clear only the admin session token (e.g. on 401 — expired/revoked).
     * Keeps device ID, role, and ordering API key so the device remembers its
     * identity and role; the admin just needs to re-handshake for a fresh token.
     */
    fun clearSessionToken() {
        safeWrite(KEY_SESSION_TOKEN, null)
    }

    /**
     * Drop both cloud credentials when a café switches to LAN or Kiosk Mode (task 9.3,
     * Requirements 1.1, 11.3).
     *
     * Neither is usable off-cloud, and leaving them is not merely untidy: `isAuthenticated()` above
     * answers from whichever of them matches the stored role, so a stale token would keep reporting
     * a device as signed in against a backend it no longer talks to, and the app would route past
     * the screens that are supposed to set the new topology up.
     *
     * The device id and role deliberately survive. The id is this device's stable identity in every
     * mode — regenerating it would make the Server Device look like a brand-new peer to its own
     * paired clients — and the role is what it will still be after the switch.
     */
    fun clearCloudCredentials() {
        safeWrite(KEY_SESSION_TOKEN, null)
        safeWrite(KEY_API_KEY, null)
    }
}
