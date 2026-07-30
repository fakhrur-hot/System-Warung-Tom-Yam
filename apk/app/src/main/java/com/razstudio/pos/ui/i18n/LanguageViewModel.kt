package com.razstudio.pos.ui.i18n

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.data.local.SettingsDao
import com.razstudio.pos.data.local.SystemSettings
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
    private val settingsDao: SettingsDao,
) : ViewModel() {

    val language: StateFlow<AppLanguage> = languageManager.language

    fun select(language: AppLanguage) = languageManager.set(language)

    /**
     * On startup: fetch café settings and (a) mirror the print language into local Room so
     * PrintService uses it even before the Settings screen is opened, and (b) if this device has
     * no explicit language choice, adopt the café-wide UI default for its role (ordering-staff
     * devices → defaultLangOrdering, admin/secondary-admin → defaultLangAdmin). Best-effort.
     */
    fun bootstrapCafeDefault() {
        viewModelScope.launch { fetchAndApply() }
    }

    /**
     * Discard this device's manual language choice and re-adopt the café default (from the "Café
     * default" language-menu entry). Lets an operator undo a local override and follow the setting.
     */
    fun useCafeDefault() {
        languageManager.clearChoice()
        viewModelScope.launch { fetchAndApply() }
    }

    private suspend fun fetchAndApply() {
        when (val result = apiClient.getSettings()) {
            is ApiResult.Success -> {
                // Always mirror the print language into Room (independent of the UI language choice).
                val existing = settingsDao.get() ?: SystemSettings()
                if (existing.printLanguage != result.data.printLanguage) {
                    settingsDao.upsert(existing.copy(printLanguage = result.data.printLanguage))
                }
                // Adopt the role's café UI default only when there's no explicit local choice.
                if (!languageManager.hasUserChoice()) {
                    val code = when (secureStorage.getRole()) {
                        SecureStorage.Role.ORDERING -> result.data.defaultLangOrdering
                        else -> result.data.defaultLangAdmin // ADMIN / ADMIN_SECONDARY / null
                    }
                    languageManager.applyDefaultIfUnset(AppLanguage.fromServerCode(code))
                }
            }
            else -> { /* offline / unauthenticated: keep the current default */ }
        }
    }
}
