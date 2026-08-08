package com.razstudio.pos.ui.viewmodels

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.google.CafeBundleStore
import com.razstudio.pos.data.google.CafeConfigPayload
import com.razstudio.pos.data.google.GoogleAccountSession
import com.razstudio.pos.data.google.GoogleSignInService
import com.razstudio.pos.data.local.DatabaseBackupManager
import com.razstudio.pos.data.local.LocalImageStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Google account panel on Settings: link, status, and the café's own Drive folder.
 *
 * ## The promise this exists to keep
 *
 * A phone is lost or wiped. The owner installs the app, signs into Google, and the café comes back —
 * backend, website, owner key, tables, menu, prices and photos. That only works if a bundle was
 * written *before* the phone was lost, which is why the panel nags with a named folder rather than
 * hiding behind a generic "Back up" button: an owner who has never created one should be able to
 * see exactly what is missing.
 */
@HiltViewModel
class GoogleBackupStatusViewModel @Inject constructor(
    private val session: GoogleAccountSession,
    private val signInService: GoogleSignInService,
    private val bundleStore: CafeBundleStore,
    private val appConfig: AppConfigStore,
    private val modeRepository: ModeRepository,
    private val backupManager: DatabaseBackupManager,
    private val imageStore: LocalImageStore,
    private val backend: BackendGateway,
) : ViewModel() {

    data class State(
        val account: GoogleAccountSession.Account? = null,
        val busy: Boolean = false,
        val driveReachable: Boolean = false,
        val bundleExists: Boolean = false,
        /** `RAZS.POS-FullQR-Kedai Kopi` — shown so the owner sees what is being promised. */
        val folderName: String = "",
        val message: String? = null,
        val isError: Boolean = false,
        /**
         * Set when the Authorization API says the `drive.appdata` scope needs an interactive
         * consent screen. The screen launches this via `startIntentSenderForResult` and reports
         * back through [onConsentResult] — see the class note on why this never used to happen.
         */
        val consentRequest: android.app.PendingIntent? = null,
    )

    private val _state = MutableStateFlow(
        State(account = session.account.value, folderName = currentFolderName())
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * What to redo once consent is granted. `authorizeDrive` returns `NeedsConsent` with a
     * `pendingIntent` that nobody launched — every retry re-asked the same still-unconsented
     * scope and got `NeedsConsent` again, forever. The screen owns the launcher (a `PendingIntent`
     * needs an Activity Result contract), so the retry itself has to live here, sent for once
     * consent has actually been shown to the owner.
     */
    private var pendingRetry: (suspend (String) -> Unit)? = null

    private fun currentFolderName(): String =
        CafeBundleStore.folderNameFor(modeRepository.currentMode(), appConfig.cafeName())

    /** "Link to Google account" — sign in, then immediately report what is in Drive. */
    fun link(activity: Activity) {
        _state.value = _state.value.copy(busy = true, message = null, isError = false)
        viewModelScope.launch {
            when (val result = signInService.signIn(activity)) {
                is GoogleSignInService.Result.Success -> {
                    session.setAccount(result.email, result.displayName, result.photoUrl)
                    _state.value = _state.value.copy(account = session.account.value)
                    refresh(activity)
                }
                is GoogleSignInService.Result.Cancelled ->
                    _state.value = _state.value.copy(busy = false)
                is GoogleSignInService.Result.Unavailable ->
                    _state.value = _state.value.copy(
                        busy = false, isError = true,
                        message = "Google sign-in isn't available on this device.",
                    )
            }
        }
    }

    /** Ask Drive what it holds for this café, in this mode. */
    fun refresh(activity: Activity) {
        _state.value = _state.value.copy(busy = true, message = null, isError = false)
        viewModelScope.launch { authorize(activity) { token -> doRefresh(token) } }
    }

    private suspend fun doRefresh(token: String) {
        val wanted = currentFolderName()
        when (val listed = bundleStore.listBundles(token)) {
            is CafeBundleStore.ListResult.Found -> {
                // Matched on mode AND name. An account may hold several cafés; only the one this
                // device is actually running counts as "backed up" here.
                val mine = listed.bundles.any {
                    CafeBundleStore.folderNameFor(modeRepository.currentMode(), it.cafeName) == wanted &&
                        it.mode == modeRepository.currentMode().name
                }
                _state.value = _state.value.copy(
                    busy = false, driveReachable = true,
                    bundleExists = mine, folderName = wanted,
                )
            }
            is CafeBundleStore.ListResult.Failed -> _state.value = _state.value.copy(
                busy = false, driveReachable = false, folderName = wanted,
                isError = true, message = listed.reason,
            )
        }
    }

    /** Create the folder, or refresh what is already in it. */
    fun saveBundle(activity: Activity) {
        _state.value = _state.value.copy(busy = true, message = null, isError = false)
        viewModelScope.launch { authorize(activity) { token -> doSaveBundle(token) } }
    }

    private suspend fun doSaveBundle(token: String) {
        // Fetched fresh rather than read from storage: it is minted by the backend and this
        // device may never have held one. A café that cannot supply it still backs up — the rest
        // of the bundle still spares the owner the whole wizard.
        val recoveryQr = when (val r = backend.getRecoveryToken()) {
            is ApiResult.Success -> r.data.url
            else -> ""
        }

        val payload = CafeConfigPayload(
            mode = modeRepository.currentMode(),
            cafeName = appConfig.cafeName(),
            supabaseUrl = appConfig.supabaseUrl(),
            supabaseAnonKey = appConfig.supabaseAnonKey(),
            websiteUrl = appConfig.websiteUrl(),
            ownerRecoveryQr = recoveryQr,
            // The café's own site IS the Cloudflare Pages domain — the backend builds the owner
            // QR as "${WEBSITE_ORIGIN}/join?recover=…", so it is already known and needs nobody
            // to type it. No API token is stored: the app never calls Cloudflare, and a token
            // that can edit DNS sitting in a Drive file would be a risk bought for nothing.
            cloudflareDomain = appConfig.websiteUrl(),
            setupData = setupDataJson(),
            savedAtMs = System.currentTimeMillis(),
            savedByDevice = android.os.Build.MODEL ?: "",
        )

        val failure = bundleStore.save(token, payload, imageStore.allFiles())
        _state.value = _state.value.copy(
            busy = false,
            driveReachable = failure == null,
            bundleExists = failure == null || _state.value.bundleExists,
            folderName = currentFolderName(),
            isError = failure != null,
            message = failure ?: "Saved. A new phone can restore this café by signing in.",
        )
    }

    private suspend fun authorize(activity: Activity, onGranted: suspend (String) -> Unit) {
        when (val auth = bundleStore.authorizeDrive(activity)) {
            is CafeBundleStore.AuthResult.Granted -> onGranted(auth.accessToken)
            is CafeBundleStore.AuthResult.NeedsConsent -> {
                pendingRetry = onGranted
                _state.value = _state.value.copy(busy = false, consentRequest = auth.pendingIntent)
            }
            is CafeBundleStore.AuthResult.Failed -> {
                _state.value = _state.value.copy(
                    busy = false, isError = true,
                    message = "Couldn't get permission for Google Drive.",
                )
            }
        }
    }

    /**
     * The screen calls this after showing [State.consentRequest] and getting a result back.
     *
     * A granted consent screen does not itself hand back an access token — the original
     * `authorize` call already returned, so the retry re-asks the Authorization API, which now
     * succeeds because the scope is actually consented, and resumes whatever the owner pressed.
     */
    fun onConsentResult(granted: Boolean, activity: Activity) {
        val retry = pendingRetry
        pendingRetry = null
        _state.value = _state.value.copy(consentRequest = null)
        if (!granted || retry == null) {
            _state.value = _state.value.copy(
                busy = false, isError = true,
                message = "Google Drive access wasn't granted.",
            )
            return
        }
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            when (val auth = bundleStore.authorizeDrive(activity)) {
                is CafeBundleStore.AuthResult.Granted -> retry(auth.accessToken)
                else -> _state.value = _state.value.copy(
                    busy = false, isError = true,
                    message = "Still couldn't get permission for Google Drive.",
                )
            }
        }
    }

    /** The café's setup, with the trading history emptied — see `CafeConfigPayload.setupData`. */
    private suspend fun setupDataJson(): String = try {
        val root = org.json.JSONObject(backupManager.exportToJson())
        root.put("orders", org.json.JSONArray())
        root.put("pendingOrders", org.json.JSONArray())
        root.toString()
    } catch (e: Exception) {
        ""
    }
}
