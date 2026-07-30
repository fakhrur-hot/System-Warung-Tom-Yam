package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.razstudio.pos.data.AppConfigStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** All editable deployment-config fields for the Setup screen. */
data class SetupState(
    val supabaseUrl: String = "",
    val supabaseAnonKey: String = "",
    val websiteUrl: String = "",
    val cafeName: String = "",
    val cloudflareAccountId: String = "",
    val cloudflareDnsZone: String = "",
    val cloudflareApiToken: String = "",
    val cloudflarePagesProject: String = "",
    val githubRepo: String = "",
    val githubToken: String = "",
    val saved: Boolean = false,
)

/**
 * Backs the in-app Setup screen (reachable from the three-dots menu on the login page). Loads the
 * current [AppConfigStore] values on init and persists them on save. The connection fields
 * (Supabase URL/key, website URL, café name) are consumed by the running app; the Cloudflare/GitHub
 * fields are stored encrypted for the operator's reference.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val appConfig: AppConfigStore
) : ViewModel() {

    private val _state = MutableStateFlow(
        SetupState(
            supabaseUrl = appConfig.supabaseUrl(),
            supabaseAnonKey = appConfig.supabaseAnonKey(),
            websiteUrl = appConfig.websiteUrl(),
            cafeName = appConfig.cafeName(),
            cloudflareAccountId = appConfig.cloudflareAccountId(),
            cloudflareDnsZone = appConfig.cloudflareDnsZone(),
            cloudflareApiToken = appConfig.cloudflareApiToken(),
            cloudflarePagesProject = appConfig.cloudflarePagesProject(),
            githubRepo = appConfig.githubRepo(),
            githubToken = appConfig.githubToken(),
        )
    )
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun update(transform: (SetupState) -> SetupState) {
        _state.value = transform(_state.value).copy(saved = false)
    }

    fun save() {
        val s = _state.value
        appConfig.save(
            supabaseUrl = s.supabaseUrl,
            supabaseAnonKey = s.supabaseAnonKey,
            websiteUrl = s.websiteUrl,
            cafeName = s.cafeName,
            cloudflareAccountId = s.cloudflareAccountId,
            cloudflareDnsZone = s.cloudflareDnsZone,
            cloudflareApiToken = s.cloudflareApiToken,
            cloudflarePagesProject = s.cloudflarePagesProject,
            githubRepo = s.githubRepo,
            githubToken = s.githubToken,
        )
        _state.value = s.copy(saved = true)
    }
}
