package com.razstudio.pos.ui.theme

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Exposes the active [ThemePreset] to any screen via `hiltViewModel()`, so the top-right
 * theme button can be dropped onto multiple screens without each screen's own ViewModel
 * needing to know about theming.
 *
 * Mirrors [com.razstudio.pos.ui.i18n.LanguageViewModel]'s shape exactly.
 */
@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val themeManager: ThemeManager,
) : ViewModel() {

    val theme: StateFlow<ThemePreset> = themeManager.theme

    fun select(preset: ThemePreset) = themeManager.set(preset)
}
