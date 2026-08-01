package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.AppConfigFetcher
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import com.razstudio.pos.data.SecureStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** All editable deployment-config fields for the Setup screen. */
data class SetupState(
    /** Task 9.2 — the topology this café will run. Decides which fields below are even asked for. */
    val operatingMode: OperatingMode = OperatingMode.CLOUD,

    // ── Connection section (Cloud mode) ─────────────────────────────────────────────────────────

    /**
     * The café's website URL: the primary input for Channel 2.
     * Tapping "Connect via website URL" triggers a fetch from here.
     */
    val websiteUrl: String = "",

    /**
     * When true the three manual fields (supabaseUrl, supabaseAnonKey, cafeName) are shown.
     * Set automatically on fetch failure, or manually by tapping "Enter manually".
     * Also pre-set when there is already a Supabase URL stored (the device is already configured).
     */
    val showManualFields: Boolean = false,

    val supabaseUrl: String = "",
    val supabaseAnonKey: String = "",
    val cafeName: String = "",

    // ── Fetch status ─────────────────────────────────────────────────────────────────────────────

    /** True while an /app-config.json fetch is in progress. */
    val isFetching: Boolean = false,

    /**
     * Non-null when the last fetch produced an error or an incomplete payload.
     * Cleared at the start of every new fetch attempt.
     */
    val fetchError: String? = null,

    // ── Other sections ───────────────────────────────────────────────────────────────────────────

    val cloudflareAccountId: String = "",
    val cloudflareDnsZone: String = "",
    val cloudflareApiToken: String = "",
    val cloudflarePagesProject: String = "",
    val githubRepo: String = "",
    val githubToken: String = "",
    val saved: Boolean = false,
)

/**
 * Backs the in-app Setup screen (reachable from the three-dots menu on the login page).
 *
 * The Connection section is now a single "Café website URL" field that fetches `/app-config.json`
 * and populates all three connection values atomically.  The three manual fields are retained
 * behind an "Enter manually" toggle for cafés whose site is not yet deployed (Requirement 3.2).
 *
 * Failure handling mirrors [AppConfigStore.adoptBackendFromRecoveryQr]: on any fetch failure the
 * error is surfaced and nothing is written (Requirement 3.4, Design Property 2).
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val appConfig: AppConfigStore,
    private val modeRepository: ModeRepository,
    private val secureStorage: SecureStorage,
    private val appConfigFetcher: AppConfigFetcher,
) : ViewModel() {

    private val _state = MutableStateFlow(
        run {
            val existingUrl = appConfig.supabaseUrl()
            val existingKey = appConfig.supabaseAnonKey()
            // If the device is already configured, pre-populate and show manual fields so the
            // operator can see and edit what is stored.
            val alreadyConfigured = existingUrl.isNotBlank() || existingKey.isNotBlank()
            SetupState(
                operatingMode = modeRepository.currentMode(),
                websiteUrl = appConfig.websiteUrl(),
                showManualFields = alreadyConfigured,
                supabaseUrl = existingUrl,
                supabaseAnonKey = existingKey,
                cafeName = appConfig.cafeName(),
                cloudflareAccountId = appConfig.cloudflareAccountId(),
                cloudflareDnsZone = appConfig.cloudflareDnsZone(),
                cloudflareApiToken = appConfig.cloudflareApiToken(),
                cloudflarePagesProject = appConfig.cloudflarePagesProject(),
                githubRepo = appConfig.githubRepo(),
                githubToken = appConfig.githubToken(),
            )
        }
    )
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun update(transform: (SetupState) -> SetupState) {
        _state.value = transform(_state.value).copy(saved = false)
    }

    /** Task 9.2 — pick the topology. Nothing is persisted until [save]. */
    fun selectMode(mode: OperatingMode) {
        _state.value = _state.value.copy(operatingMode = mode, saved = false)
    }

    /** Toggle the "Enter manually" affordance — expands or collapses the three manual fields. */
    fun toggleManualFields() {
        _state.value = _state.value.copy(
            showManualFields = !_state.value.showManualFields,
            fetchError = null,
            saved = false,
        )
    }

    /**
     * Fetch `/app-config.json` from the stored [SetupState.websiteUrl], validate the payload,
     * and populate the three connection fields atomically on success.
     *
     * On any failure: surface [SetupState.fetchError] and reveal [SetupState.showManualFields] so
     * the operator can enter the values themselves.  Write nothing — a partial write is worse than
     * no write (Design Property 2, Requirement 3.4).
     *
     * Tasks 4.1, 4.2, 4.3 (Requirements 3.2, 3.4)
     */
    fun fetchFromWebsite() {
        val url = _state.value.websiteUrl.trim()
        if (url.isBlank()) {
            _state.value = _state.value.copy(
                fetchError = "Enter the café's website address first.",
                showManualFields = true,
                saved = false,
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(
                isFetching = true,
                fetchError = null,
                saved = false,
            )

            when (val result = appConfigFetcher.fetch(url)) {
                is AppConfigFetcher.FetchResult.Success -> {
                    // Populate all three fields from the fetched payload.
                    // The operator can still review them in the manual section if they want.
                    _state.value = _state.value.copy(
                        supabaseUrl = result.supabaseUrl,
                        supabaseAnonKey = result.supabaseAnonKey,
                        cafeName = result.cafeName,
                        isFetching = false,
                        fetchError = null,
                        showManualFields = false,
                        saved = false,
                    )
                }

                is AppConfigFetcher.FetchResult.NetworkError -> {
                    _state.value = _state.value.copy(
                        isFetching = false,
                        fetchError = result.message,
                        showManualFields = true,
                        saved = false,
                    )
                }

                is AppConfigFetcher.FetchResult.ParseError -> {
                    _state.value = _state.value.copy(
                        isFetching = false,
                        fetchError = result.message,
                        showManualFields = true,
                        saved = false,
                    )
                }

                is AppConfigFetcher.FetchResult.IncompletePayload -> {
                    _state.value = _state.value.copy(
                        isFetching = false,
                        fetchError = result.message,
                        showManualFields = true,
                        saved = false,
                    )
                }
            }
        }
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

        // Task 9.3: the cloud session token and ordering api key go too. Clearing the URL alone is
        // not enough — SecureStorage.isAuthenticated() answers from whichever credential matches the
        // stored role, so a device switched to LAN while still holding one keeps reporting itself as
        // signed in against a backend it no longer talks to, and routes straight past the screens
        // that are meant to establish the new topology.
        if (!cloud) secureStorage.clearCloudCredentials()

        // Written last, so a device can never end up flagged as LAN while still holding live cloud
        // credentials if the process dies mid-save. The reverse ordering leaves it merely
        // un-switched, which the operator can see and retry.
        modeRepository.setMode(s.operatingMode)

        _state.value = s.copy(saved = true)
    }
}
