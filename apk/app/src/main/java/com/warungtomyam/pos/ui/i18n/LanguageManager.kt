package com.warungtomyam.pos.ui.i18n

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide holder for the currently selected [AppLanguage], persisted across launches.
 * Defaults to [AppLanguage.DEFAULT] (Bahasa Malaysia) on first launch.
 */
@Singleton
class LanguageManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(
        AppLanguage.fromName(prefs.getString(KEY, null))
    )
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    /** True once the operator has explicitly picked a language on THIS device. */
    fun hasUserChoice(): Boolean = prefs.contains(KEY)

    fun set(language: AppLanguage) {
        _language.value = language
        prefs.edit().putString(KEY, language.name).apply()
    }

    /**
     * Apply a café-wide default language, but ONLY when this device has no explicit choice
     * yet. Deliberately NOT persisted to [KEY] — so [hasUserChoice] stays false and the device
     * keeps following the café default (even if the admin later changes it) until the operator
     * picks a language here, at which point [set] records their choice and this stops applying.
     */
    fun applyDefaultIfUnset(default: AppLanguage) {
        if (!hasUserChoice()) {
            _language.value = default
        }
    }

    /**
     * Discard this device's explicit language choice so it follows the café default again.
     * Resets to [AppLanguage.DEFAULT] immediately; the caller re-applies the café default
     * (via the settings fetch) right after.
     */
    fun clearChoice() {
        prefs.edit().remove(KEY).apply()
        _language.value = AppLanguage.DEFAULT
    }

    companion object {
        private const val PREFS = "app_language_prefs"
        private const val KEY = "app_language"
    }
}
