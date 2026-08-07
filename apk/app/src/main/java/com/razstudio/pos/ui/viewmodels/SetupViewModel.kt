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

    /**
     * Which way the owner is connecting a Cloud café. Only meaningful for `CLOUD`.
     *
     * Defaults to [ConnectionTab.OWNER_QR] because that is the path almost every owner takes and
     * the only one that needs nothing typed: the owner key carries the Supabase URL, the
     * publishable key and the café's website origin, and signing in with it fetches the café name
     * from branding. Everything the manual tab asks for is already in that QR.
     */
    val connectionTab: ConnectionTab = ConnectionTab.OWNER_QR,

    /**
     * The provisioning Wizard endpoint, offered on the existing-café tab and stored on the device.
     *
     * Optional, and deliberately so: connecting a device to a running café needs nothing from the
     * Wizard. It is collected here only because this is the one screen where an owner has all of their
     * café's deployment values in front of them, and the alternative is hunting for the URL later on
     * the Provision screen with no prompt telling them where it came from.
     */
    val provisionerWorkerUrl: String = "",

    /** True while [SetupViewModel.runPreflight] is probing. */
    val isPreflighting: Boolean = false,

    /** Per-surface results from the last preflight. Empty until one has been run. */
    val preflight: List<com.razstudio.pos.data.SetupPreflight.Item> = emptyList(),
)

/**
 * The three ways to point a device at a Cloud café.
 *
 * [OWNER_QR] joins a café whose owner has their key to hand — nothing typed. [PROVISION_NEW_CAFE]
 * builds a café that does not exist yet. [EXISTING_CAFE] is the case neither covered: a café that is
 * already running, whose QR is lost or was never saved, reached by typing the same values its own
 * Cloudflare Pages project holds (`VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`, the site URL).
 *
 * That path used to exist as an "Enter manually" toggle and was dropped when these tabs arrived — the
 * fields and the fetch/verify logic stayed in this ViewModel, but nothing rendered them, so a running
 * café with no QR had no way in at all.
 */
enum class ConnectionTab { OWNER_QR, PROVISION_NEW_CAFE, EXISTING_CAFE }

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
    private val setupPreflight: com.razstudio.pos.data.SetupPreflight,
) : ViewModel() {

    /**
     * Probe all three surfaces and report each separately.
     *
     * Distinct from [verifyConnection], which asks one question — "can this device talk to this
     * backend?" — and gates Save on it. This asks "is this café fully stood up?", including the parts
     * that do not block Save: a customer website serving no config, or a Wizard whose API never
     * deployed. Those are invisible from the device until someone needs them and they are missing.
     */
    fun runPreflight() {
        val s = _state.value
        _state.value = s.copy(isPreflighting = true, preflight = emptyList())
        viewModelScope.launch {
            val items = setupPreflight.check(
                websiteUrl = s.websiteUrl,
                wizardUrl = s.provisionerWorkerUrl,
                supabaseUrl = s.supabaseUrl,
                supabaseKey = s.supabaseAnonKey,
            )
            _state.value = _state.value.copy(isPreflighting = false, preflight = items)
        }
    }

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
                provisionerWorkerUrl = secureStorage.getProvisionerWorkerUrl().orEmpty(),
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

    /**
     * May the home screen let this device enter [mode]?
     *
     * Reads what was **saved**, never [SetupState.operatingMode] — that field tracks the radio the
     * operator is currently touching, and an unsaved selection must not unlock a mode button.
     */
    fun isModeReady(mode: OperatingMode): Boolean = appConfig.isModeConfigured(mode)

    /**
     * True on a device that has never completed Setup for any mode.
     *
     * This is what separates "offer everything" from "offer what was chosen". A blank device must
     * reach the QR and pairing flows, because those are how it gets configured in the first place —
     * the owner QR and the invite QR each carry the café's backend. A device whose owner has
     * *already saved a mode* is a different situation: they answered the question, and continuing to
     * offer the modes they did not pick invites a tap that lands somewhere wrong.
     */
    fun noModeConfiguredYet(): Boolean = OperatingMode.entries.none { appConfig.isModeConfigured(it) }

    /**
     * Task 23 follow-up — this device is the LAN Server, so it is the café's admin by construction.
     *
     * There is no handshake to perform: off-cloud, the host holds the database and authenticates to
     * nobody. Recording the role matters anyway, because [StartupViewModel] routes on it — without
     * this, an owner would land back on the mode picker every morning and have to re-declare that
     * they are running the café they are standing in.
     */
    fun beginHostingLocally() {
        // A device that was previously a Client still holds the old host's address, and
        // `BackendModule` reads exactly that to decide who serves whom. Leaving it would make this
        // device try to forward its own café to a phone that may no longer exist.
        appConfig.setLanServerUrl("")
        secureStorage.setRole(SecureStorage.Role.ADMIN)
        secureStorage.setSessionToken(LOCAL_HOST_SESSION)
    }

    companion object {
        /**
         * Marks a session that exists only on this device. `LocalBackend` never inspects it — there
         * is no remote party to present it to — but `SecureStorage.isAuthenticated()` requires a
         * non-null token, and a sentinel that says what it is beats an empty string that looks like
         * a bug.
         */
        const val LOCAL_HOST_SESSION = "local-host"
    }

    fun selectConnectionTab(tab: ConnectionTab) {
        _state.value = _state.value.copy(connectionTab = tab, saved = false)
    }

    /**
     * The owner signed in with their key from inside Setup, so this device runs that café in Cloud
     * Mode. Called after `AdminConnectScreen` reports success.
     *
     * ## Why the mode is written here and not before the scan
     *
     * `AdminConnectScreen` already writes the backend (via `adoptBackendFromRecoveryQr`) and the
     * café name (from branding). What it cannot know is the *topology* the owner chose — it is
     * reachable from the home screen too, where no such choice was made. Persisting CLOUD up front
     * instead would leave a device claiming a mode it had not finished configuring if the owner
     * simply backed out of the scanner.
     *
     * With this, `isModeConfigured(CLOUD)` is satisfied by the QR alone: URL, key, website and name
     * all arrive from it, which is exactly why the Save button has nothing to do on that tab.
     */
    fun completeOwnerQrSetup() {
        modeRepository.setMode(OperatingMode.CLOUD)
        appConfig.setOperatingMode(OperatingMode.CLOUD)
        _state.value = _state.value.copy(operatingMode = OperatingMode.CLOUD, saved = true)
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
     * True when [save] would be allowed.
     *
     * The rule is *every field this screen shows for the selected mode must be filled*, plus — for
     * Cloud — a live check that the backend answers. That matters more than it sounds: the home
     * screen enables exactly one mode button, the one whose setup was completed and saved, so a Save
     * that accepted blanks would light up a mode button for a café that cannot actually run.
     *
     * Off-cloud asks for no connection at all, so the café name is the whole requirement there.
     */
    fun canSave(): Boolean = blockingReason() == null

    /**
     * Why [save] is refused, or null when it is allowed. Drives both the disabled Save button and
     * the message under it, so the operator is never left guessing which field is missing.
     */
    fun blockingReason(): String? {
        val s = _state.value

        // The owner-key and provision-new-café tabs have no form to block. Everything Save would
        // check arrives from the owner key or the provisioning run, so demanding a typed café name
        // here would ask the owner to retype details that are about to be supplied automatically.
        if (s.operatingMode == OperatingMode.CLOUD &&
            (s.connectionTab == ConnectionTab.OWNER_QR || s.connectionTab == ConnectionTab.PROVISION_NEW_CAFE)
        ) {
            return null
        }

        if (s.cafeName.isBlank()) return "Enter the café name."

        if (s.operatingMode != OperatingMode.CLOUD) return null

        if (s.supabaseUrl.isBlank() || s.supabaseAnonKey.isBlank()) {
            return "Connect using the café's website address, or enter the Supabase URL and " +
                "publishable key manually."
        }
        if (!s.verified) {
            return "Check the connection before saving, so a wrong value is caught here rather " +
                "than on a later screen."
        }
        return null
    }

    fun save() {
        val s = _state.value
        val cloud = s.operatingMode == OperatingMode.CLOUD

        // The same rule the button is disabled by, enforced again here. A disabled button is a
        // hint; this is the boundary. "Saved ✓" against a half-filled form would light up a mode
        // button on the home screen for a café that cannot run.
        blockingReason()?.let { reason ->
            _state.value = s.copy(verifyError = reason, saved = false)
            return
        }

        appConfig.save(
            supabaseUrl = if (cloud) s.supabaseUrl else "",
            supabaseAnonKey = if (cloud) s.supabaseAnonKey else "",
            websiteUrl = if (cloud) s.websiteUrl else "",
            cafeName = s.cafeName,
        )

        // The Wizard URL is not part of the café's identity — it is a tool endpoint — so it goes to
        // SecureStorage rather than into appConfig.save. Same slot the Provision screen prefills from,
        // so an owner who records it here while connecting to their running café does not have to find
        // it again the day they need to re-run a provisioning step against that café.
        s.provisionerWorkerUrl.trim().takeIf { it.isNotBlank() }?.let {
            secureStorage.saveProvisionerWorkerUrl(it)
        }

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
