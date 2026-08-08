package com.razstudio.pos.ui.viewmodels

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import com.razstudio.pos.data.google.CafeBundleStore
import com.razstudio.pos.data.google.CafeConfigPayload
import com.razstudio.pos.data.google.GoogleSignInService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Task 23.7 / 23.9 — saving the café to the owner's Google account, as a deliberate act
 * (Requirements 15.10, 15.12).
 *
 * ## Why this is not a side effect of finishing Setup
 *
 * Cafés are configured by installers, family members and whoever happened to be free that morning —
 * not necessarily by the person who owns them. Attaching the café to "whichever Google account is
 * signed in on this phone" at the end of the wizard would quietly hand a stranger the ability to
 * restore the café onto their own device, and nobody would ever see the moment it happened.
 *
 * So it is a button, pressed by someone who read what it says.
 *
 * ## What it says (task 23.9)
 *
 * The bundle carries the owner recovery QR, which *is* the café. The screen states that at the point
 * of saving, in those terms, because it is a genuine trade — never retyping the setup, in exchange
 * for the café key living in a Google account. An owner who shares that account, or loses it, has
 * shared or lost the café. That belongs on screen, not in a support doc nobody opens.
 */
@HiltViewModel
class CafeBundleViewModel @Inject constructor(
    private val bundleStore: CafeBundleStore,
    private val signInService: GoogleSignInService,
    private val appConfigStore: AppConfigStore,
    private val modeRepository: ModeRepository,
    private val backend: BackendGateway,
    private val backupManager: com.razstudio.pos.data.local.DatabaseBackupManager,
    private val imageStore: com.razstudio.pos.data.local.LocalImageStore,
    private val session: com.razstudio.pos.data.google.GoogleAccountSession,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    data class State(
        val busy: Boolean = false,
        val outcome: Outcome? = null,
        /** True while the confirm dialog carrying the trade (task 23.9) is up. */
        val confirming: Boolean = false,
    )

    /**
     * What happened, not what to say about it. The card owns the wording, because this app ships in
     * five languages and a ViewModel holding English would strand four of them.
     */
    enum class Outcome { SAVED, REMOVED, UPLOAD_REJECTED, NEEDS_CONSENT, NO_PERMISSION }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * What to redo once consent is granted — see the class note on [authorize] for why this
     * exists at all: a `NeedsConsent` result was previously a dead end.
     */
    private var pendingRetry: (suspend (String) -> Unit)? = null

    /** The Authorization API's consent screen, for the composable to launch. */
    private val _consentRequest = MutableStateFlow<android.app.PendingIntent?>(null)
    val consentRequest: StateFlow<android.app.PendingIntent?> = _consentRequest.asStateFlow()

    /**
     * Whether to show this at all. Off-cloud cafés store no backend and have no internet, so a
     * bundle would be both unfillable and unreachable (task 23.4).
     */
    fun isOffered(): Boolean =
        signInService.isAvailable() && modeRepository.currentMode() == OperatingMode.CLOUD

    fun askToSave() {
        _state.value = _state.value.copy(confirming = true, outcome = null)
    }

    fun cancelSave() {
        _state.value = _state.value.copy(confirming = false)
    }

    /** Runs only after the owner has confirmed the dialog that states the trade. */
    fun save(activity: Activity) {
        _state.value = State(busy = true)
        viewModelScope.launch { authorize(activity) { token -> doSave(token) } }
    }

    private suspend fun doSave(token: String) {
        // The recovery QR is fetched fresh rather than read from storage — it is minted by the
        // backend and this device may never have held one. A café that cannot supply it still
        // saves: the rest of the bundle spares the owner the whole wizard, and a blank recovery
        // field is a smaller loss than refusing to save anything at all.
        val recoveryQr = when (val r = backend.getRecoveryToken()) {
            is ApiResult.Success -> r.data.url
            else -> ""
        }

        val payload = CafeConfigPayload(
            mode = modeRepository.currentMode(),
            cafeName = appConfigStore.cafeName(),
            supabaseUrl = appConfigStore.supabaseUrl(),
            supabaseAnonKey = appConfigStore.supabaseAnonKey(),
            websiteUrl = appConfigStore.websiteUrl(),
            ownerRecoveryQr = recoveryQr,
            setupData = setupDataJson(),
            savedAtMs = System.currentTimeMillis(),
            savedByDevice = android.os.Build.MODEL ?: "",
        )

        // The café's menu photos travel with it. They live in app-private storage and are
        // deleted with the app, so a replacement phone would otherwise restore a picture menu
        // with no pictures.
        // The payment QR travels the same way and for a sharper reason: it lives only in
        // app-private storage and on the backend, so a café that loses its Supabase access
        // has no other copy of the code its customers pay into.
        val failure = bundleStore.save(
            token,
            payload,
            imageStore.allFiles(),
            com.razstudio.pos.ui.util.PaymentQrPipeline.storedFileOrNull(context),
        )
        _state.value = State(
            outcome = if (failure == null) Outcome.SAVED else Outcome.UPLOAD_REJECTED
        )
    }

    /**
     * The cafe's setup as JSON: a full export with the trading history emptied.
     *
     * Reuses `DatabaseBackupManager` rather than hand-rolling a second serialiser, so the bundle
     * cannot drift from the export/import format that `applyImport` already knows how to read. The
     * two arrays are emptied rather than the keys removed, because `applyImport` reads them
     * positionally-by-key and a missing key would restore whatever a previous import left behind.
     */
    private suspend fun setupDataJson(): String = try {
        val root = org.json.JSONObject(backupManager.exportToJson())
        root.put("orders", org.json.JSONArray())
        root.put("pendingOrders", org.json.JSONArray())
        root.toString()
    } catch (e: Exception) {
        // A cafe whose setup cannot be serialised still saves its config. Half a bundle is fine
        // here in a way half a *restore* is not: the missing half is additive, and the owner is
        // told nothing was lost because nothing was.
        ""
    }

    /** Lets an owner take this café back out of their Google account. */
    fun remove(activity: Activity) {
        _state.value = State(busy = true)
        viewModelScope.launch { authorize(activity) { token -> doRemove(token) } }
    }

    private suspend fun doRemove(token: String) {
        // Only the café this device is running. An account may hold several — a WLAN till, a
        // Kiosk — and removing all of them because the owner tidied up one would be a much
        // larger action than the button says.
        val folderId = session.selected.value?.folderId
            ?: run {
                _state.value = State(outcome = Outcome.REMOVED)
                return
            }
        val failure = bundleStore.delete(token, folderId)
        _state.value = State(
            outcome = if (failure == null) Outcome.REMOVED else Outcome.UPLOAD_REJECTED
        )
    }

    /**
     * Runs [onGranted] with a fresh access token, or sets an error/consent state.
     *
     * `NeedsConsent` carries a `pendingIntent` the Authorization API needs shown before the scope
     * is ever granted — without launching it, every retry re-asks the same still-unconsented scope
     * and gets `NeedsConsent` again, forever. [consentRequest] is what the screen launches; once it
     * reports back via [onConsentResult], [onGranted] runs with the token that attempt produced.
     */
    private suspend fun authorize(activity: Activity, onGranted: suspend (String) -> Unit) {
        when (val auth = bundleStore.authorizeDrive(activity)) {
            is CafeBundleStore.AuthResult.Granted -> onGranted(auth.accessToken)
            is CafeBundleStore.AuthResult.NeedsConsent -> {
                pendingRetry = onGranted
                _state.value = State(outcome = Outcome.NEEDS_CONSENT)
                _consentRequest.value = auth.pendingIntent
            }
            is CafeBundleStore.AuthResult.Failed -> {
                _state.value = State(outcome = Outcome.NO_PERMISSION)
            }
        }
    }

    /** The screen calls this after showing [consentRequest] and getting a result back. */
    fun onConsentResult(granted: Boolean, activity: Activity) {
        val retry = pendingRetry
        pendingRetry = null
        _consentRequest.value = null
        if (!granted || retry == null) {
            _state.value = State(outcome = Outcome.NO_PERMISSION)
            return
        }
        _state.value = State(busy = true)
        viewModelScope.launch {
            when (val auth = bundleStore.authorizeDrive(activity)) {
                is CafeBundleStore.AuthResult.Granted -> retry(auth.accessToken)
                else -> _state.value = State(outcome = Outcome.NO_PERMISSION)
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(outcome = null)
    }
}
