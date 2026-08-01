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

    // ── Step 2: live verification (task 6.3) ─────────────────────────────────────────────────────

    /** True while the pending Supabase pair is being probed. */
    val isVerifying: Boolean = false,

    /**
     * Non-null when the last verification failed. Blocks [SetupViewModel.save] — "Saved" must never
     * be shown for a connection that was never proven to work.
     */
    val verifyError: String? = null,

    /** True once the pending pair has answered successfully. Reset whenever a field changes. */
    val verified: Boolean = false,

    // ── Other sections ───────────────────────────────────────────────────────────────────────────

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
            )
        }
    )
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun update(transform: (SetupState) -> SetupState) {
        // Editing any field invalidates a prior verification. Without this, an operator could
        // verify a working pair, then retype the key, and still be allowed to save — which is the
        // shape of bug that makes a verification step worthless.
        _state.value = transform(_state.value).copy(saved = false, verified = false, verifyError = null)
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

            // interactiveSetup: the operator is standing here with Cloud selected and their
            // website typed in. Guarding this on the *stored* mode blocked a Kiosk or LAN
            // device from ever reaching Cloud through the wizard — see AppConfigFetcher.
            when (val result = appConfigFetcher.fetch(url, interactiveSetup = true)) {
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
                        // Step 1 proved the *website* serves a payload. It says nothing about
                        // whether those credentials work, which is step 2's job.
                        verified = false,
                        verifyError = null,
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
     * The Cloudflare and GitHub fields this paragraph used to describe are gone (task 6.4). They
     * were never read by the running app — the screen said as much — and a credential collected for
     * no purpose is indistinguishable, to the person typing it, from one that matters.
     */
    /**
     * Step 2 (task 6.3) — prove the pending Supabase pair works before anything is written.
     *
     * Off-cloud there is nothing to verify: LAN and Kiosk store no Supabase values at all, so the
     * step is skipped rather than faked.
     */
    fun verifyConnection(onVerified: () -> Unit = {}) {
        val s = _state.value
        if (s.operatingMode != OperatingMode.CLOUD) {
            _state.value = s.copy(verified = true, verifyError = null)
            onVerified()
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isVerifying = true, verifyError = null, saved = false)
            val result = appConfigFetcher.verifyBackend(
                s.supabaseUrl, s.supabaseAnonKey, interactiveSetup = true,
            )
            val ok = result is AppConfigFetcher.VerifyResult.Ok
            _state.value = _state.value.copy(
                isVerifying = false,
                verified = ok,
                verifyError = result.messageOrNull,
            )
            if (ok) onVerified()
        }
    }

    /**
     * True when [save] would be allowed. Cloud requires a live verification; off-cloud does not,
     * because it stores no Supabase values to get wrong.
     */
    fun canSave(): Boolean {
        val s = _state.value
        return s.operatingMode != OperatingMode.CLOUD || s.verified
    }

    fun save() {
        val s = _state.value
        val cloud = s.operatingMode == OperatingMode.CLOUD

        // Nothing is written for a cloud café until the pair has answered. "Saved ✓" against
        // credentials that were never tried is the exact reassurance this rework removes.
        if (cloud && !s.verified) {
            _state.value = s.copy(
                verifyError = s.verifyError
                    ?: "Check the connection before saving, so a wrong value is caught here rather " +
                    "than on a later screen.",
                saved = false,
            )
            return
        }

        appConfig.save(
            supabaseUrl = if (cloud) s.supabaseUrl else "",
            supabaseAnonKey = if (cloud) s.supabaseAnonKey else "",
            websiteUrl = if (cloud) s.websiteUrl else "",
            cafeName = s.cafeName,
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
