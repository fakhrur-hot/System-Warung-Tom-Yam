package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.ui.screens.extractRecoverToken
import com.razstudio.pos.ui.screens.originOf
import com.razstudio.pos.ui.screens.queryParam
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sign a Full QR café in from its owner key, inside the Setup Wizard.
 *
 * ## What one QR does
 *
 * The owner key is a `${WEBSITE_ORIGIN}/join?recover=<token>&api=…&key=…` link, so a single scan
 * carries four things: the Supabase project, its publishable key, the café's website, and proof of
 * ownership. This applies them in the only order that works — adopt the backend, then use it —
 * and finishes by reading the café name off `branding`, which is the first moment a device that
 * arrived with nothing but a QR can know what café it belongs to.
 *
 * That is why the Owner QR tab asks for nothing: there is nothing left to ask.
 *
 * ## Why this is separate from the sign-in screen
 *
 * `AdminConnectScreen` also signs in with the owner key, but it is a *login* screen: it offers
 * manual entry, secondary-admin invites and a debug path, and it does not know what topology the
 * device is meant to run. Setup does. Routing Setup through it meant a wizard handing off to a
 * login screen and hoping it came back — this owns the three steps it needs and nothing else.
 */
@HiltViewModel
class OwnerKeyLoginViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val appConfig: AppConfigStore,
    private val secureStorage: SecureStorage,
    private val modeRepository: ModeRepository,
) : ViewModel() {

    sealed class State {
        data object Idle : State()
        data object Working : State()
        data class Done(val cafeName: String) : State()
        data class Failed(val reason: Reason) : State()
    }

    /** The screen owns the wording; this app ships in five languages. */
    enum class Reason { NOT_AN_OWNER_KEY, NO_QR_IN_IMAGE, REJECTED, UNREACHABLE }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun reset() {
        _state.value = State.Idle
    }

    /** Camera scan or decoded image — both arrive here as the raw decoded text. */
    fun load(scanned: String) {
        val token = extractRecoverToken(scanned)
        if (token == null) {
            // A staff invite, a table QR, or somebody's Wi-Fi code. Naming it beats "failed".
            _state.value = State.Failed(Reason.NOT_AN_OWNER_KEY)
            return
        }

        _state.value = State.Working
        viewModelScope.launch {
            // Backend first. Without it the recovery call has nowhere to go, and the failure would
            // read as a bad key when the key is fine.
            val api = queryParam(scanned, ApiClient.QR_PARAM_API)
            val key = queryParam(scanned, ApiClient.QR_PARAM_KEY)
            if (api != null && key != null) {
                appConfig.adoptBackendFromRecoveryQr(api, key, websiteUrl = originOf(scanned))
            }
            if (!apiClient.isBackendConfigured()) {
                _state.value = State.Failed(Reason.NOT_AN_OWNER_KEY)
                return@launch
            }

            val deviceId = secureStorage.getDeviceId()
            val deviceModel = android.os.Build.MODEL ?: "Phone"
            when (val result = apiClient.recoverAdmin(token, deviceId, deviceModel)) {
                is ApiResult.Success -> {
                    secureStorage.setSessionToken(result.data)
                    secureStorage.setRole(SecureStorage.Role.ADMIN)

                    // Only now is branding reachable, and only now can a device that arrived with a
                    // QR and nothing else learn its own café's name.
                    val name = when (val branding = apiClient.getBranding()) {
                        is ApiResult.Success -> branding.data.cafeName.trim()
                        else -> ""
                    }
                    if (name.isNotBlank()) appConfig.setCafeName(name)

                    // The topology is Setup's contribution: the owner picked Full QR, and the QR
                    // supplied everything else. Written last, so a failure anywhere above leaves
                    // the device claiming nothing it cannot back up.
                    modeRepository.setMode(OperatingMode.CLOUD)
                    appConfig.setOperatingMode(OperatingMode.CLOUD)

                    _state.value = State.Done(appConfig.cafeName())
                }
                is ApiResult.Error -> _state.value = State.Failed(Reason.REJECTED)
                is ApiResult.NetworkError -> _state.value = State.Failed(Reason.UNREACHABLE)
            }
        }
    }

    /** A saved image that held no QR at all — distinct from one holding the wrong QR. */
    fun imageHeldNoQr() {
        _state.value = State.Failed(Reason.NO_QR_IN_IMAGE)
    }
}
