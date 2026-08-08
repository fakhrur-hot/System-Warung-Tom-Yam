package com.razstudio.opsapp.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OEM-resilient encrypted storage for the `PromoCatalogRepository` GitHub token.
 *
 * A scoped extraction of `apk/app`'s `com.razstudio.pos.data.SecureStorage` — that class holds
 * ~15 keys (session tokens, admin PIN, drawer PIN, device identity) none of which apply here; this
 * app only ever needs the one GitHub PAT the catalog editor uses. Same
 * create-EncryptedSharedPreferences-or-clear-and-retry fallback for budget OEM devices where
 * Android Keystore keys can get corrupted during OS updates or reboots.
 */
@Singleton
class PromoTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "PromoTokenStore"
        private const val PREFS_FILE = "promo_token_prefs"
        private const val KEY_PROMO_TOKEN = "promo_catalog_token"
    }

    private var prefs: SharedPreferences? = createEncryptedPrefs()

    private fun createEncryptedPrefs(): SharedPreferences? {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
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
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().apply()
            context.filesDir.parentFile?.resolve("shared_prefs/$PREFS_FILE.xml")?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear corrupted prefs", e)
        }
    }

    private fun getPrefs(): SharedPreferences? {
        if (prefs == null) prefs = createEncryptedPrefs()
        return prefs
    }

    fun getPromoToken(): String? = getPrefs()?.getString(KEY_PROMO_TOKEN, null)

    fun savePromoToken(token: String): Boolean =
        getPrefs()?.edit()?.putString(KEY_PROMO_TOKEN, token)?.commit() ?: false
}
