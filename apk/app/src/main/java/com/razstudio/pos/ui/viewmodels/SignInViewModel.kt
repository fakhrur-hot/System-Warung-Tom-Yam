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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val signInService: GoogleSignInService,
    private val bundleStore: CafeBundleStore,
    private val appConfigStore: AppConfigStore,
    private val modeRepository: ModeRepository,
    private val backupManager: com.razstudio.pos.data.local.DatabaseBackupManager,
    private val session: com.razstudio.pos.data.google.GoogleAccountSession,
    private val imageStore: com.razstudio.pos.data.local.LocalImageStore,
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
         * The account holds several cafés — a WLAN till, a Kiosk, a full-QR shop — so the owner
         * picks. Shown only when there is an actual choice: one café loads without asking, because
         * a dialog with a single button is a step, not a decision.
         *
         * The pick is not a filter. Choosing *loads*, replacing what is on this device, which is
         * why it is confirmed and why switching afterwards needs a deliberate Mode Logout.
         */
        data class ChooseCafe(
            val bundles: List<CafeBundleStore.RemoteBundle>,
        ) : State()

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
                    // Recorded before the Drive step: the avatar should appear the moment sign-in
                    // succeeds, not only once a café has been found. An owner with no bundle yet is
                    // still signed in, and the header should say so.
                    session.setAccount(result.email, result.displayName, result.photoUrl)
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
        listAndDecide(token)
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
                is CafeBundleStore.AuthResult.Granted -> listAndDecide(auth.accessToken)
                else -> _state.value = State.SignedInNoCafe(account?.email ?: "")
            }
        }
    }

    /** Kept for the duration of a sign-in so the chooser can load whichever café is picked. */
    private var driveToken: String? = null

    /**
     * Zero cafés, one café, or a choice.
     *
     * One loads straight through — the sample calls this "auto load to café directly", and it is
     * what makes a replacement phone a two-tap recovery instead of a setup session.
     */
    private suspend fun listAndDecide(token: String) {
        driveToken = token
        _state.value = State.Working(Step.LOOKING_UP)

        when (val listed = bundleStore.listBundles(token)) {
            is CafeBundleStore.ListResult.Failed ->
                _state.value = State.Problem(Reason.DRIVE_UNREACHABLE)

            is CafeBundleStore.ListResult.Found -> when {
                listed.bundles.isEmpty() ->
                    _state.value = State.SignedInNoCafe(account?.email ?: "")
                listed.bundles.size == 1 ->
                    choose(listed.bundles.first())
                else ->
                    _state.value = State.ChooseCafe(listed.bundles)
            }
        }
    }

    /** The owner picked a café — load it, restore it, and remember it for next launch. */
    fun choose(bundle: CafeBundleStore.RemoteBundle) {
        val token = driveToken ?: return
        viewModelScope.launch {
            _state.value = State.Working(Step.LOOKING_UP)
            applyLoad(bundleStore.load(token, bundle.folderId), token, bundle)
        }
    }

    private suspend fun applyLoad(
        load: CafeBundleStore.LoadResult,
        token: String? = null,
        bundle: CafeBundleStore.RemoteBundle? = null,
    ) {
        when (load) {
            is CafeBundleStore.LoadResult.Found -> {
                val localName = appConfigStore.cafeName()
                val deviceIsConfigured = OperatingMode.entries.any { appConfigStore.isModeConfigured(it) }

                if (deviceIsConfigured && localName != load.payload.cafeName) {
                    pendingChoice = bundle
                    pendingToken = token
                    _state.value = State.Conflict(
                        onDevice = localName,
                        inAccount = load.payload.cafeName,
                        payload = load.payload,
                    )
                } else {
                    restore(load.payload)
                    if (token != null) restorePhotos(token, load.payload)
                    bundle?.let { session.setSelected(it.toChoice()) }
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
        val conflict = state.value as? State.Conflict ?: return
        viewModelScope.launch {
            restore(conflict.payload)
            pendingToken?.let { restorePhotos(it, conflict.payload) }
            pendingChoice?.let { session.setSelected(it.toChoice()) }
        }
    }

    private var pendingChoice: CafeBundleStore.RemoteBundle? = null
    private var pendingToken: String? = null

    private fun CafeBundleStore.RemoteBundle.toChoice() =
        com.razstudio.pos.data.google.GoogleAccountSession.CafeChoice(folderId, mode, cafeName)

    /**
     * Pull the menu photos down beside the menu that references them.
     *
     * Best-effort and per-photo: `LocalImageStore` keeps these in app-private storage, so a café
     * restoring onto a new phone has none of them, and a picture menu with no pictures is most of
     * the menu missing. But a café whose tables and prices came back is open for business, so one
     * failed image must not fail the restore.
     */
    private suspend fun restorePhotos(token: String, payload: CafeConfigPayload) {
        if (payload.photoFileIds.isEmpty()) return
        payload.photoFileIds.forEach { (fileName, fileId) ->
            bundleStore.downloadPhoto(token, fileId, imageStore.fileFor(fileName))
        }
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
    internal suspend fun restore(payload: CafeConfigPayload) {
        // Whether this device has a café of its own to lose. Read BEFORE anything is written, since
        // the writes below are exactly what would change the answer.
        val deviceWasBlank = OperatingMode.entries.none { appConfigStore.isModeConfigured(it) }

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

        // Tables, menu, settings and printers — but only onto a device that had no café.
        //
        // `applyImport` wipes the database before importing, including the order history. On a new
        // or replacement device there is nothing there and this is the whole point of the feature:
        // off-cloud there is no backend to sync from, so without it the owner gets a correctly-named
        // till with no tables and an empty menu.
        //
        // On a device that already runs a café it is withheld. The owner may have picked the
        // account's café in the conflict dialog while this device still holds a day of unsynced
        // orders, and silently destroying them to import a menu is not a trade anyone consented to.
        // Config still crosses over, so the device points at the right café; its data stays put.
        //
        // Awaited, not fired and forgotten. The state below is what sends the owner into the till,
        // and a detached import would race it — the table view would open on an empty café and fill
        // in underneath them, which reads as data loss even though nothing was lost.
        if (deviceWasBlank && payload.setupData.isNotBlank()) {
            try {
                backupManager.applyImport(
                    payload.setupData,
                    restoreHardwareConfig = hardwareConfigFitsThisDevice(payload.setupData)
                )
            } catch (e: Exception) {
                // The café is already configured and reachable at this point; a failed setup import
                // costs the owner a menu re-entry, not their café.
                android.util.Log.w("SignInViewModel", "Setup data could not be applied", e)
            }
        }

        _state.value = if (appConfigStore.isModeConfigured(payload.mode)) {
            State.Restored(payload.cafeName)
        } else {
            State.Problem(Reason.RESTORE_INCOMPLETE)
        }
    }

    /**
     * True when the printer set in [setupData] can actually work on this device. (HW-REQ-8, task 3.4)
     *
     * The case this exists for: a café's Sunmi till dies and the owner signs in on a phone. The
     * bundle carries a `SUNMI_AIDL` printer with no MAC address, and importing it produces a till
     * that looks configured and prints nowhere — the worst kind of failure, because nothing reports
     * an error. Withholding it leaves the owner on a visible "no printer configured" alert, which
     * they can act on.
     *
     * The inverse matters too, and is why this is not simply hardcoded false: replacing a Sunmi
     * with another Sunmi, or a phone with another phone, *should* carry the printer set across.
     * That is the whole point of the bundle.
     *
     * Only Sunmi is checked. Bluetooth, USB and network printers are addressed by MAC, VID/PID or
     * host — all re-pairable on any device — whereas the Sunmi AIDL is either present on the
     * hardware or it is not. A malformed bundle is treated as unfit rather than throwing: this runs
     * inside the restore path, and failing the whole café restore over a printer row would be a
     * poor trade.
     */
    internal fun hardwareConfigFitsThisDevice(setupData: String): Boolean = try {
        val printers = org.json.JSONObject(setupData).optJSONArray("printerConfigs")
        val needsSunmi = (0 until (printers?.length() ?: 0)).any { i ->
            when (printers!!.getJSONObject(i).optString("transport", "BLUETOOTH")) {
                // Both spellings are accepted: bundles written before the transport enum settled
                // may carry either, and guessing wrong here silently restores an unusable printer.
                "SUNMI_AIDL", "SUNMI_INTERNAL" -> true
                else -> false
            }
        }
        !needsSunmi || sunmiServicePresent()
    } catch (e: Exception) {
        android.util.Log.w("SignInViewModel", "Could not inspect printer configs; withholding", e)
        false
    }

    /** Does this device expose Sunmi's printer AIDL? (designs.md H9) */
    private fun sunmiServicePresent(): Boolean =
        context.packageManager
            .queryIntentServices(
                android.content.Intent("woyou.aidlservice.jiuiv5.IWoyouService")
                    .setPackage("woyou.aidlservice.jiuiv5"),
                0
            )
            .isNotEmpty()

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
