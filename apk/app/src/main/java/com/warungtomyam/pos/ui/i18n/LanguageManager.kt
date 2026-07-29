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

    fun set(language: AppLanguage) {
        _language.value = language
        prefs.edit().putString(KEY, language.name).apply()
    }

    companion object {
        private const val PREFS = "app_language_prefs"
        private const val KEY = "app_language"
    }
}
