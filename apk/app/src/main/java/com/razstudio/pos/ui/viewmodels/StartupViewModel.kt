package com.razstudio.pos.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.AppConfigFetcher
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.data.google.GoogleSignInService
import com.razstudio.pos.data.local.SessionPrefs
import com.razstudio.pos.ui.navigation.NavRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Resolves the app start destination on [Dispatchers.IO] so that
 * [SecureStorage]'s EncryptedSharedPreferences / Keystore operations
 * never block the main thread at Activity startup.
 *
 * **Task 4.5 — re-read `/app-config.json` on launch (Requirement 3.5):**
 * For a Cloud-mode device that has a stored `website_url`, a background coroutine fetches
 * `/app-config.json` and updates the publishable key and café name in [AppConfigStore].
 *
 * Deliberately fire-and-forget: a failure (network down, café site not yet deployed) leaves
 * the stored values untouched and is logged but never shown to the user.  A network blip
 * must not deconfigure a working till.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val sessionPrefs: SessionPrefs,
    private val appConfigStore: AppConfigStore,
    private val modeRepository: ModeRepository,
    private val appConfigFetcher: AppConfigFetcher,
    private val googleSignInService: GoogleSignInService,
) : ViewModel() {

    /**
     * Task 23.1 / 23.4 — should this launch open on the sign-in screen?
     *
     * Four conditions, and every one of them is a way for the screen to be actively wrong rather
     * than merely unhelpful:
     *
     *  - **No OAuth client in this build.** A café that rebranded its `applicationId` has no
     *    registered Android client, so the button could only ever fail (task 23.11).
     *  - **Already answered.** Skip is a decision, and re-asking every morning is a gate with extra
     *    steps.
     *  - **LAN or Kiosk.** No internet by definition; the attempt would hang, then fail
     *    (Requirement 15.9, 11.1).
     *  - **A configured Cloud café that already knows who it is** still sees it once, because that
     *    is the device whose owner most benefits from saving their setup before they lose the phone.
     */
    private fun offersStartupSignIn(): Boolean {
        if (!googleSignInService.isAvailable()) return false
        if (appConfigStore.startupSignInSettled()) return false
        return modeRepository.currentMode() == OperatingMode.CLOUD
    }

    companion object {
        private const val TAG = "StartupViewModel"
    }

    sealed class State {
        data object Loading : State()
        data class Ready(val startDestination: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    fun resolve(deepLinkInvite: String?, deepLinkRecover: String?) {
        // Only resolve once — guard against re-entry on config change.
        if (_state.value is State.Ready) return
        viewModelScope.launch {
            val dest = withContext(Dispatchers.IO) {
                when {
                    deepLinkRecover != null && !secureStorage.isAuthenticated() ->
                        NavRoutes.ADMIN_CONNECT
                    deepLinkInvite != null && !secureStorage.isAuthenticated() ->
                        NavRoutes.ORDERING_CONNECT
                    secureStorage.isAuthenticated() ->
                        when (secureStorage.getRole()) {
                            // If the admin signed out (café locked), stay on the lock screen and
                            // require a manual reopen instead of silently logging back in.
                            SecureStorage.Role.ADMIN,
                            SecureStorage.Role.ADMIN_SECONDARY ->
                                if (sessionPrefs.isLocked()) NavRoutes.ADMIN_LOCK else NavRoutes.ADMIN_HOME
                            SecureStorage.Role.ORDERING -> NavRoutes.ORDERING_HOME
                            null -> NavRoutes.ROLE_SELECT
                        }
                    // ── Task 23.1 / 23.4: sign-in ahead of the entry screen ─────────────────
                    // This branch is last on purpose. A device already carrying a session, or
                    // arriving on an invite/recovery link, has a destination that sign-in cannot
                    // improve on — putting the account screen in front of those would interrupt a
                    // staff member mid-join to ask about a café key they do not hold.
                    //
                    // Cloud and unconfigured devices only: LAN and Kiosk have no internet by
                    // definition, so the attempt would hang on the network and NoInternetGuard
                    // would refuse the lookup anyway (Requirements 15.9, 11.1).
                    offersStartupSignIn() -> NavRoutes.SIGN_IN

                    else -> NavRoutes.ROLE_SELECT
                }
            }
            _state.value = State.Ready(dest)

            // ── Task 4.5: background config re-fetch (Requirement 3.5) ────────────────────────
            // Launch independently so any delay does not hold up the navigation transition.
            // Uses Dispatchers.IO via AppConfigFetcher.fetch(); no UI is updated on failure.
            maybeRefreshCloudConfig()
        }
    }

    /**
     * If this is a Cloud-mode device with a stored `website_url`, re-fetch `/app-config.json`
     * and update the publishable key and café name.
     *
     * - On success: overwrites `supabase_anon_key` and `cafe_name` with fresh values from the
     *   payload.  Does NOT overwrite `supabase_url` or `website_url` — a key rotation should
     *   never change which project the device talks to.
     * - On any failure: the stored values are left completely untouched.  No error is shown.
     *
     * The Supabase URL itself is not updated intentionally: if a café migrates to a new Supabase
     * project the admin should reconfigure via Setup, not have the project silently switched.
     *
     * Requirements: 3.5
     */
    private fun maybeRefreshCloudConfig() {
        if (modeRepository.currentMode() != OperatingMode.CLOUD) return
        val websiteUrl = appConfigStore.websiteUrl()
        if (websiteUrl.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Background config refresh from $websiteUrl")
                when (val result = appConfigFetcher.fetch(websiteUrl)) {
                    is AppConfigFetcher.FetchResult.Success -> {
                        // Only update the two fields that can legitimately rotate between launches.
                        // Writing supabaseUrl would silently move the device to a different project
                        // if the response ever got corrupted or the wrong site was stored.
                        appConfigStore.supabaseAnonKeyRefresh(result.supabaseAnonKey)
                        appConfigStore.setCafeName(result.cafeName)
                        Log.d(TAG, "Background config refresh succeeded")
                    }
                    is AppConfigFetcher.FetchResult.NetworkError -> {
                        Log.d(TAG, "Background config refresh — network error (keeping stored values): ${result.message}")
                    }
                    is AppConfigFetcher.FetchResult.ParseError -> {
                        Log.d(TAG, "Background config refresh — parse error (keeping stored values): ${result.message}")
                    }
                    is AppConfigFetcher.FetchResult.IncompletePayload -> {
                        Log.d(TAG, "Background config refresh — incomplete payload (keeping stored values): ${result.message}")
                    }
                }
            } catch (e: Exception) {
                // Belt-and-suspenders: fetch() already catches internally, but any unexpected
                // exception must never propagate and crash the app.
                Log.w(TAG, "Background config refresh threw unexpectedly (keeping stored values)", e)
            }
        }
    }
}
