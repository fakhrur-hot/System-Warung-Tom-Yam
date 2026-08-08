package com.razstudio.opsapp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.opsapp.data.ApiResult
import com.razstudio.opsapp.data.OperatorSelfRegistrar
import com.razstudio.opsapp.data.api.OperatorApiClient
import com.razstudio.opsapp.data.local.ConnectedCafeDao
import com.razstudio.opsapp.data.local.ConnectedCafeEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

enum class ConnectExistingPhase {
    IDLE,
    REGISTERING,
    VERIFYING,
    DONE,
    ERROR,
}

data class ConnectExistingState(
    val phase: ConnectExistingPhase = ConnectExistingPhase.IDLE,
    val cafeName: String = "",
    val errorMessage: String? = null,
)

/**
 * Connect this operator device to an **already-provisioned** café.
 *
 * ## Why this replaced the Operator Invite flow
 *
 * The invite flow needed four things to line up before an operator could do anything: the café admin
 * had to display the right QR, the backend had to mint the right invite row, the device had to poll,
 * and the admin had to approve. Each was its own failure surface, and each failed in a way that
 * looked identical from the operator's side — a screen that simply never advanced.
 *
 * This path removes all four. [OperatorSelfRegistrar] inserts an `OPERATOR` device row that is
 * `APPROVED` on arrival with a session token this app generated, exactly as the provisioning wizard
 * already does for a brand-new café. There is no invite, no approval, and nothing to poll: the
 * request either succeeds or reports why.
 *
 * The cost, stated plainly: the operator must hold the café's **service-role key**. That is
 * acceptable here and nowhere else — this is an internal support tool whose operators already handle
 * these keys to provision cafés in the first place. The key is passed straight through to the one
 * request that needs it and is never written to disk.
 */
@HiltViewModel
class ConnectExistingCafeViewModel @Inject constructor(
    private val connectedCafeDao: ConnectedCafeDao,
    private val selfRegistrar: OperatorSelfRegistrar,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectExistingState())
    val state: StateFlow<ConnectExistingState> = _state.asStateFlow()

    fun connect(supabaseUrlRaw: String, anonKeyRaw: String, serviceRoleKeyRaw: String) {
        val supabaseUrl = normalizeSupabaseUrl(supabaseUrlRaw)
        val anonKey = anonKeyRaw.trim()
        val serviceRoleKey = serviceRoleKeyRaw.trim()

        if (supabaseUrl == null) {
            fail("Enter the café's Supabase URL, e.g. https://abcdefgh.supabase.co")
            return
        }
        if (anonKey.isBlank()) {
            fail("The anon / publishable key is required.")
            return
        }
        if (serviceRoleKey.isBlank()) {
            fail("The service-role key is required to register this device.")
            return
        }

        viewModelScope.launch {
            _state.value = ConnectExistingState(phase = ConnectExistingPhase.REGISTERING)

            val registration = try {
                selfRegistrar.register(
                    supabaseUrl = supabaseUrl,
                    supabaseAnonKey = anonKey,
                    supabaseServiceRoleKey = serviceRoleKey,
                )
            } catch (e: Exception) {
                fail(
                    "Could not register this device: ${e.message ?: "unknown error"}. " +
                        "Check the URL and that the service-role key belongs to this café."
                )
                return@launch
            }
            // The service-role key goes no further than the call above — it is never stored.

            val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
            val hostSlug = supabaseUrl.removePrefix("https://").substringBefore('.')

            // Verify the credential before saving anything. A device row alone proved nothing in the
            // old flow: an entry that cannot authenticate is what produced a café tile that opened
            // straight into "Access revoked". Branding is the cheapest OPERATOR-scoped read, so it
            // doubles as the check that this café's Edge Functions actually accept operator tokens.
            _state.value = _state.value.copy(phase = ConnectExistingPhase.VERIFYING)

            val probe = ConnectedCafeEntity(
                id = registration.deviceId,
                cafeName = hostSlug,
                cafeSlug = hostSlug,
                supabaseUrl = supabaseUrl,
                supabaseAnonKey = anonKey,
                sessionToken = registration.sessionToken,
                connectedAt = now,
                lastConnectedAt = now,
            )

            // No revocation manager: a 401 here is a setup problem to report inline, not an
            // app-wide "your access was revoked" event.
            when (val branding = OperatorApiClient(probe).getBranding()) {
                is ApiResult.Success -> {
                    val resolvedName = branding.data.cafeName.trim().ifBlank { hostSlug }
                    connectedCafeDao.insert(
                        probe.copy(
                            cafeName = resolvedName,
                            cafeSlug = resolvedName
                                .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                                .ifBlank { hostSlug },
                        )
                    )
                    _state.value = ConnectExistingState(
                        phase = ConnectExistingPhase.DONE,
                        cafeName = resolvedName,
                    )
                }

                is ApiResult.Error ->
                    if (branding.code == "UNAUTHORIZED") {
                        fail(
                            "This device was registered, but the café rejected its operator token. " +
                                "That café's Edge Functions are probably older than operator support " +
                                "— redeploy them (menu, tables, branding, cafe-location, menu-image) " +
                                "and connect again."
                        )
                    } else {
                        fail("Registered, but verifying access failed: ${branding.message}")
                    }

                is ApiResult.NetworkError ->
                    fail("Registered, but could not reach the café to verify access: ${branding.message}")
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(phase = ConnectExistingPhase.IDLE, errorMessage = null)
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(
            phase = ConnectExistingPhase.ERROR,
            errorMessage = message,
        )
    }

    /**
     * Accepts what an operator realistically pastes — a bare host, a project URL, or a REST endpoint
     * copied out of the Supabase dashboard — and reduces it to the `https://<ref>.supabase.co` origin
     * the API calls need. Returns null when there is no usable host.
     *
     * The scheme is always forced to **https**, never carried over from the input. A Supabase project
     * is HTTPS-only, and a keyboard that helpfully completes `http://` used to be preserved verbatim,
     * fail deep inside OkHttp, and surface as "CLEARTEXT communication not permitted by network
     * security policy" — which reads as a device or app problem rather than one character of input.
     */
    private fun normalizeSupabaseUrl(raw: String): String? {
        val host = raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')
            .trim()
        return if (host.isBlank()) null else "https://$host"
    }
}
