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
        viewModelScope.launch {
            val token = authorize(activity) ?: return@launch

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
                savedAtMs = System.currentTimeMillis(),
                savedByDevice = android.os.Build.MODEL ?: "",
            )

            val failure = bundleStore.save(token, payload)
            _state.value = State(
                outcome = if (failure == null) Outcome.SAVED else Outcome.UPLOAD_REJECTED
            )
        }
    }

    /** Lets an owner take the café key back out of their Google account. */
    fun remove(activity: Activity) {
        _state.value = State(busy = true)
        viewModelScope.launch {
            val token = authorize(activity) ?: return@launch
            val failure = bundleStore.delete(token)
            _state.value = State(
                outcome = if (failure == null) Outcome.REMOVED else Outcome.UPLOAD_REJECTED
            )
        }
    }

    /**
     * Returns an access token, or sets an error state and returns null.
     *
     * `NeedsConsent` is reported rather than silently launched: this path runs from Settings, where
     * the owner is already in a deliberate flow, and the Authorization API will show its own consent
     * on the next attempt. Telling them to press it again is honest and needs no intent plumbing on
     * a screen that otherwise has none.
     */
    private suspend fun authorize(activity: Activity): String? =
        when (val auth = bundleStore.authorizeDrive(activity)) {
            is CafeBundleStore.AuthResult.Granted -> auth.accessToken
            is CafeBundleStore.AuthResult.NeedsConsent -> {
                _state.value = State(outcome = Outcome.NEEDS_CONSENT)
                null
            }
            is CafeBundleStore.AuthResult.Failed -> {
                _state.value = State(outcome = Outcome.NO_PERMISSION)
                null
            }
        }

    fun clearMessages() {
        _state.value = _state.value.copy(outcome = null)
    }
}
