package com.razstudio.pos.ui.viewmodels

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.AppConfigStore
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
 * Task 23 — the startup sign-in screen (Requirement 15).
 *
 * ## The one rule this screen must not break
 *
 * **Sign-in is never a gate** (Property 10). Every path out of here — success, cancellation, no Play
 * Services, no network, a corrupt bundle — must leave the owner able to reach their till. The state
 * machine below has no dead end; [skip] is always callable and always works.
 *
 * ## The three exits (task 23.3, Requirements 15.3–15.5)
 *
 * They are deliberately distinct, and the third is the one that is easy to get wrong:
 *
 *  - **Skip** → the entry screen, device unchanged.
 *  - **Signed in, account holds a café** → restore it, then the entry screen.
 *  - **Signed in, account holds nothing** → Setup Wizard *only*. No mode buttons, no Demo. Showing
 *    an owner three actions they cannot take is worse than showing them the one they must.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val signInService: GoogleSignInService,
    private val bundleStore: CafeBundleStore,
    private val appConfigStore: AppConfigStore,
    private val modeRepository: ModeRepository,
) : ViewModel() {

    sealed class State {
        /** The screen as it opens: sign-in button, Skip and Demo. */
        data object Idle : State()

        /** Sheet is up or Drive is being read. Skip stays available throughout. */
        data class Working(val step: Step) : State()

        /**
         * Task 23.8 — the device holds one café and the account holds another. Both can be right:
         * a borrowed device keeping its own café, or a replacement taking the account's. Nothing in
         * the data distinguishes them, so the owner decides.
         */
        data class Conflict(
            val onDevice: String,
            val inAccount: String,
            val payload: CafeConfigPayload,
        ) : State()

        /** Restored; the caller sends the owner to the entry screen. */
        data class Restored(val cafeName: String) : State()

        /** Signed in, nothing saved. Setup Wizard only — see the class note. */
        data class SignedInNoCafe(val email: String) : State()

        /**
         * Something did not work. Carries no severity: the screen shows the reason and the same
         * Skip that was there before, because the recovery is identical in every case.
         */
        data class Problem(val reason: Reason) : State()
    }

    /**
     * What the spinner is waiting on. An enum rather than a string because a ViewModel that holds
     * user-facing English cannot be translated — and this app ships in five languages, so a café in
     * Kelantan would read half a screen in Malay and half in English.
     */
    enum class Step { SIGNING_IN, LOOKING_UP, WAITING_FOR_DRIVE }

    /** Why it did not work. Same reasoning as [Step]; the screen owns the wording. */
    enum class Reason { SIGN_IN_UNAVAILABLE, BUNDLE_UNREADABLE, DRIVE_UNREACHABLE, RESTORE_INCOMPLETE }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The signed-in account, once there is one. Used by the save path and shown on screen. */
    private var account: GoogleSignInService.Result.Success? = null
    val signedInEmail: String? get() = account?.email

    /**
     * Task 23.4 / 23.1 — whether this device should see the screen at all.
     *
     * False for LAN and Kiosk, which have no internet by definition, and false for a build with no
     * OAuth client configured. In both cases the app opens on its entry screen as it always did.
     */
    fun shouldOfferSignIn(): Boolean =
        signInService.isAvailable() && modeRepository.currentMode() == OperatingMode.CLOUD

    fun signIn(activity: Activity) {
        _state.value = State.Working(Step.SIGNING_IN)
        viewModelScope.launch {
            when (val result = signInService.signIn(activity as Context)) {
                is GoogleSignInService.Result.Success -> {
                    account = result
                    lookUpSavedCafe(activity)
                }
                // Dismissing the sheet returns the owner to the screen unchanged. It is a choice,
                // not a failure, and an error message here would read as an accusation.
                is GoogleSignInService.Result.Cancelled -> _state.value = State.Idle
                is GoogleSignInService.Result.Unavailable ->
                    _state.value = State.Problem(Reason.SIGN_IN_UNAVAILABLE)
            }
        }
    }

    private suspend fun lookUpSavedCafe(activity: Activity) {
        _state.value = State.Working(Step.LOOKING_UP)

        // Drive access is asked for here, not at sign-in — an owner who has nothing saved and never
        // saves anything is never prompted for it (task 23.5b).
        val token = when (val auth = bundleStore.authorizeDrive(activity)) {
            is CafeBundleStore.AuthResult.Granted -> auth.accessToken
            is CafeBundleStore.AuthResult.NeedsConsent -> {
                // The screen launches the consent intent and calls back into [onDriveConsentResult].
                _state.value = State.Working(Step.WAITING_FOR_DRIVE)
                pendingConsent = auth.pendingIntent
                _consentRequest.value = auth.pendingIntent
                return
            }
            is CafeBundleStore.AuthResult.Failed -> {
                // Declining Drive is a legitimate answer: the owner signed in but does not want the
                // app in their Drive. They keep the account and lose only the backup.
                _state.value = State.SignedInNoCafe(account?.email ?: "")
                return
            }
        }
        applyLoad(bundleStore.load(token))
    }

    private var pendingConsent: android.app.PendingIntent? = null
    private val _consentRequest = MutableStateFlow<android.app.PendingIntent?>(null)
    val consentRequest: StateFlow<android.app.PendingIntent?> = _consentRequest.asStateFlow()

    fun consentRequestHandled() {
        _consentRequest.value = null
    }

    /** Called by the screen once the Drive consent activity returns. */
    fun onDriveConsentResult(activity: Activity, granted: Boolean) {
        pendingConsent = null
        if (!granted) {
            _state.value = State.SignedInNoCafe(account?.email ?: "")
            return
        }
        viewModelScope.launch {
            when (val auth = bundleStore.authorizeDrive(activity)) {
                is CafeBundleStore.AuthResult.Granted -> applyLoad(bundleStore.load(auth.accessToken))
                else -> _state.value = State.SignedInNoCafe(account?.email ?: "")
            }
        }
    }

    private fun applyLoad(load: CafeBundleStore.LoadResult) {
        when (load) {
            is CafeBundleStore.LoadResult.Found -> {
                val localName = appConfigStore.cafeName()
                val deviceIsConfigured = OperatingMode.entries.any { appConfigStore.isModeConfigured(it) }

                if (deviceIsConfigured && localName != load.payload.cafeName) {
                    _state.value = State.Conflict(
                        onDevice = localName,
                        inAccount = load.payload.cafeName,
                        payload = load.payload,
                    )
                } else {
                    restore(load.payload)
                }
            }
            is CafeBundleStore.LoadResult.None ->
                _state.value = State.SignedInNoCafe(account?.email ?: "")
            is CafeBundleStore.LoadResult.Unusable ->
                // Nothing is written. Half a café is worse than none: the device would report itself
                // ready, reach the counter, and fail at the first order (task 23.10).
                _state.value = State.Problem(Reason.BUNDLE_UNREADABLE)
            is CafeBundleStore.LoadResult.Failed ->
                _state.value = State.Problem(Reason.DRIVE_UNREACHABLE)
        }
    }

    /** Task 23.8 — the owner chose the account's café over the one already on the device. */
    fun keepAccountCafe() {
        (state.value as? State.Conflict)?.let { restore(it.payload) }
    }

    /** Task 23.8 — the owner kept the device's café. Nothing is written, nothing is overwritten. */
    fun keepDeviceCafe() {
        _state.value = State.Restored(appConfigStore.cafeName())
    }

    /**
     * Task 23.6 — applying a payload must leave the device exactly as finishing Setup would, so
     * `isModeConfigured` reports the mode ready with no further input. A device that looks signed in
     * and cannot host is the failure this guards against, which is why the check below is an
     * assertion about the *result* rather than a comment about the writes.
     */
    private fun restore(payload: CafeConfigPayload) {
        // Mode first: `isModeConfigured` is false for any mode other than the stored one, so writing
        // the fields before the mode would leave a window where the café reads as unconfigured.
        modeRepository.setMode(payload.mode)
        appConfigStore.setOperatingMode(payload.mode)
        appConfigStore.save(
            supabaseUrl = payload.supabaseUrl,
            supabaseAnonKey = payload.supabaseAnonKey,
            websiteUrl = payload.websiteUrl,
            cafeName = payload.cafeName,
        )

        _state.value = if (appConfigStore.isModeConfigured(payload.mode)) {
            State.Restored(payload.cafeName)
        } else {
            State.Problem(Reason.RESTORE_INCOMPLETE)
        }
    }

    /** Dismiss a [State.Problem] back to the opening screen. Never a dead end. */
    fun dismissProblem() {
        _state.value = State.Idle
    }

    /**
     * Record that the owner has answered this screen, whichever way they answered it, so the next
     * cold start opens on the entry screen instead (see `AppConfigStore.startupSignInSettled`).
     *
     * Called on *every* exit including Skip and Demo — a decision to not sign in is still a
     * decision, and asking again each morning would make it a gate.
     */
    fun settle() {
        appConfigStore.setStartupSignInSettled()
    }
}
