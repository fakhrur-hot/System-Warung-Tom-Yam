package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** All editable deployment-config fields for the Setup screen. */
data class SetupState(
    /** Task 9.2 — the topology this café will run. Decides which fields below are even asked for. */
    val operatingMode: OperatingMode = OperatingMode.CLOUD,
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
    private val appConfig: AppConfigStore,
    private val modeRepository: ModeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SetupState(
            operatingMode = modeRepository.currentMode(),
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

    /** Task 9.2 — pick the topology. Nothing is persisted until [save]. */
    fun selectMode(mode: OperatingMode) {
        _state.value = _state.value.copy(operatingMode = mode, saved = false)
    }

    /**
     * Persist the setup (task 9.2, Requirements 2.2–2.4).
     *
     * Off-cloud, the Supabase and website fields are written as **blank** rather than being left as
     * whatever the operator typed before switching. That is the difference between a mode choice and
     * a cosmetic one: `ApiClient` falls back to `BuildConfig.SUPABASE_URL` when the runtime value is
     * blank, so a LAN café that still had a URL stored would keep a live path to somebody's cloud
     * project — and `ModeCapabilities` exists precisely so nothing infers its topology from whether a
     * URL happens to be set. Clearing them makes the stored state agree with the chosen mode.
     *
     * The Cloudflare and GitHub fields are left untouched in every mode: they are reference material
     * for the operator's own deploy tooling and are never read by the running app, so discarding them
     * on a mode switch would destroy something the user typed for no benefit.
     */
    fun save() {
        val s = _state.value
        val cloud = s.operatingMode == OperatingMode.CLOUD

        appConfig.save(
            supabaseUrl = if (cloud) s.supabaseUrl else "",
            supabaseAnonKey = if (cloud) s.supabaseAnonKey else "",
            websiteUrl = if (cloud) s.websiteUrl else "",
            cafeName = s.cafeName,
            cloudflareAccountId = s.cloudflareAccountId,
            cloudflareDnsZone = s.cloudflareDnsZone,
            cloudflareApiToken = s.cloudflareApiToken,
            cloudflarePagesProject = s.cloudflarePagesProject,
            githubRepo = s.githubRepo,
            githubToken = s.githubToken,
        )

        // Written after the credentials, so a device can never be left in LAN Mode while still
        // holding a live Supabase URL if the process dies between the two writes.
        modeRepository.setMode(s.operatingMode)

        _state.value = s.copy(saved = true)
    }
}
