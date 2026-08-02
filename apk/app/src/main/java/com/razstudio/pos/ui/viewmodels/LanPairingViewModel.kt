package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.lan.LanAddress
import com.razstudio.pos.data.lan.LanServer
import com.razstudio.pos.data.lan.PairingQrPayload
import com.razstudio.pos.data.local.LocalBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Server Device's pairing screen (task 7.1, Requirement 5.1).
 *
 * Produces the QR a Client scans, and — just as importantly — refuses to produce one when this
 * device has no usable address (task 21.3). A pairing QR is read once and never re-checked, so one
 * carrying a stale or unreachable host turns into "the staff phone won't connect" twenty minutes
 * later, with nothing on either screen pointing at the network.
 */
@HiltViewModel
class LanPairingViewModel @Inject constructor(
    private val backend: LocalBackend,
    private val lanAddress: LanAddress,
    private val lanServer: LanServer,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        /** The JSON to encode as a QR, or null when pairing is not currently possible. */
        val payload: String? = null,
        val host: String = "",
        val port: Int = 0,
        /** Operator-facing reason there is no QR. Null when [payload] is present. */
        val error: String? = null,
        /**
         * Whether the HTTP server is actually accepting connections. Reported separately from the
         * address: a device can have a perfectly good IP while the server failed to bind, and a QR
         * that encodes a reachable host with nothing listening fails in the same opaque way.
         */
        val serverRunning: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Resolve the address and mint (or reuse) a pairing code.
     *
     * Re-runnable, because the operator's fix for "no network" is to turn the hotspot on and come
     * back — so the screen needs a way to ask again without being rebuilt.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.value = State(loading = true)

            val address = lanAddress.resolve()
            if (address is LanAddress.Result.Unavailable) {
                _state.value = State(loading = false, error = address.reason)
                return@launch
            }
            val host = (address as LanAddress.Result.Found).ip

            // getInvite is idempotent while a code is live, so returning to this screen shows the
            // same code the operator may already be holding up to a phone.
            when (val invite = backend.getInvite(role = null)) {
                is ApiResult.Success -> {
                    val payload = PairingQrPayload(host = host, port = PORT, pairingToken = invite.data.token)
                    _state.value = State(
                        loading = false,
                        payload = payload.encode(),
                        host = host,
                        port = PORT,
                        serverRunning = lanServer.isRunning,
                    )
                }
                is ApiResult.Error -> _state.value = State(loading = false, error = invite.message)
                is ApiResult.NetworkError -> _state.value = State(loading = false, error = invite.message)
            }
        }
    }

    /** Rotate the code — see `LocalBackend.regenerateInvite`; approved devices are unaffected. */
    fun regenerate() {
        viewModelScope.launch {
            when (val invite = backend.regenerateInvite(role = null)) {
                is ApiResult.Success -> refresh()
                is ApiResult.Error -> _state.value = _state.value.copy(error = invite.message)
                is ApiResult.NetworkError -> _state.value = _state.value.copy(error = invite.message)
            }
        }
    }

    private companion object {
        /** Must match `LanServer.PORT`. */
        const val PORT = PairingQrPayload.PORT
    }
}
