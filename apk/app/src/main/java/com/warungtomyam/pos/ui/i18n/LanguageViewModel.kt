package com.warungtomyam.pos.ui.i18n

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Exposes the active [AppLanguage] to any screen via `hiltViewModel()`, so the
 * top-right language button can be dropped onto multiple screens without each
 * screen's own view-model needing to know about language.
 */
@HiltViewModel
class LanguageViewModel @Inject constructor(
    private val languageManager: LanguageManager,
) : ViewModel() {

    val language: StateFlow<AppLanguage> = languageManager.language

    fun select(language: AppLanguage) = languageManager.set(language)
}
