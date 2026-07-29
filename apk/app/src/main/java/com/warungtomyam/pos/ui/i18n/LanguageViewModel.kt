package com.warungtomyam.pos.ui.i18n

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warungtomyam.pos.data.ApiClient
import com.warungtomyam.pos.data.ApiResult
import com.warungtomyam.pos.data.SecureStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Exposes the active [AppLanguage] to any screen via `hiltViewModel()`, so the
 * top-right language button can be dropped onto multiple screens without each
 * screen's own view-model needing to know about language.
 */
@HiltViewModel
class LanguageViewModel @Inject constructor(
    private val languageManager: LanguageManager,
    private val apiClient: ApiClient,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    val language: StateFlow<AppLanguage> = languageManager.language

    fun select(language: AppLanguage) = languageManager.set(language)

    /**
     * On startup, if this device has no explicit language choice yet, adopt the café-wide
     * default for its role: ordering-staff devices follow `defaultLangOrdering`, the admin
     * (and secondary-admin) device follows `defaultLangAdmin`. Once the operator picks a
     * language via the language button, [LanguageManager.set] records it and this no longer
     * applies. Best-effort: on any failure the device keeps its current default (BM).
     */
    fun bootstrapCafeDefault() {
        if (languageManager.hasUserChoice()) return
        viewModelScope.launch {
            when (val result = apiClient.getSettings()) {
                is ApiResult.Success -> {
                    val code = when (secureStorage.getRole()) {
                        SecureStorage.Role.ORDERING -> result.data.defaultLangOrdering
                        else -> result.data.defaultLangAdmin // ADMIN / ADMIN_SECONDARY / null
                    }
                    languageManager.applyDefaultIfUnset(AppLanguage.fromServerCode(code))
                }
                else -> { /* offline / unauthenticated: keep the current default */ }
            }
        }
    }
}
