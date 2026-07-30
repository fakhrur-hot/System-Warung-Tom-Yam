package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.SecureStorage
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
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val sessionPrefs: SessionPrefs
) : ViewModel() {

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
                    else -> NavRoutes.ROLE_SELECT
                }
            }
            _state.value = State.Ready(dest)
        }
    }
}
