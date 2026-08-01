package com.razstudio.pos.ui.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide holder for the active [ThemePreset], persisted across launches via plain
 * SharedPreferences (no secrets here — a colour name needs no encryption).
 *
 * Mirrors the shape of [com.razstudio.pos.ui.i18n.LanguageManager] exactly so the pattern
 * is immediately familiar: a singleton State, a `set()` that both updates the flow and
 * persists, and a [StateFlow] for Compose to observe.
 */
@Singleton
class ThemeManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        ThemePreset.fromName(prefs.getString(KEY, null))
    )
    val theme: StateFlow<ThemePreset> = _theme.asStateFlow()

    fun set(preset: ThemePreset) {
        _theme.value = preset
        prefs.edit().putString(KEY, preset.name).apply()
    }

    companion object {
        private const val PREFS = "app_theme_prefs"
        private const val KEY  = "app_theme"
    }
}
