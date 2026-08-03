package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.GatewayCredentialStore
import com.razstudio.pos.data.GatewayProviderDto
import com.razstudio.pos.data.local.PaymentMethod
import com.razstudio.pos.ui.i18n.LanguageManager
import com.razstudio.pos.ui.i18n.uiStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Admin gateway settings, per provider. (PG-REQ-2, PG-REQ-8, task 7.1)
 *
 * Reworked from a single fixed Merchant ID / Verify Key / Secret Key form. That shape only ever
 * described one aggregator; Touch 'n Go direct and DuitNow through an acquiring bank are separate
 * merchant relationships with different credential fields entirely. The server now declares each
 * provider's **field spec** and this screen renders whatever it declares, so a provider can be
 * added — or its real field names learned at onboarding — without an app release.
 *
 * Secrets are never read back from the server. [GatewayProviderDto.fieldsSet] says only whether a
 * field has something stored, which drives the masked "already set" placeholder; leaving such a
 * field untouched sends nothing for it and the server keeps what it has.
 */
@HiltViewModel
class PaymentGatewayViewModel @Inject constructor(
    private val apiClient: BackendGateway,
    private val credentialStore: GatewayCredentialStore,
    private val languageManager: LanguageManager,
) : ViewModel() {

    private fun str() = uiStrings(languageManager.language.value)

    data class UiState(
        val isLoading: Boolean = true,
        val isSaving: Boolean = false,
        val keystoreHealthy: Boolean = true,
        val providers: List<GatewayProviderDto> = emptyList(),
        /** Which provider's form is open. Null until the list loads. */
        val selectedProvider: String? = null,
        /** field key → what the admin has typed now. Absent/blank means "leave unchanged". */
        val credentialInputs: Map<String, String> = emptyMap(),
        val enabledMethods: Set<PaymentMethod> = emptySet(),
        val isSandbox: Boolean = true,
        val isEnabled: Boolean = false,
        val error: String? = null,
        val successMessage: String? = null,
    ) {
        val selected: GatewayProviderDto?
            get() = providers.firstOrNull { it.provider == selectedProvider }

        /** A provider whose adapter is a fail-closed placeholder cannot be switched on, and its
         *  form is read-only apart from storing credentials ahead of the adapter landing. */
        val selectedIsAvailable: Boolean
            get() = selected?.status == "AVAILABLE"

        /**
         * Every required field must either already be stored or be filled in now. Mirrors the
         * server's own `configured` rule so the button doesn't invite a save the server rejects.
         */
        val canSave: Boolean
            get() {
                val p = selected ?: return false
                if (isSaving) return false
                return p.credentialFields.filter { it.required }.all { field ->
                    p.fieldsSet[field.key] == true || !credentialInputs[field.key].isNullOrBlank()
                }
            }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Gateway channels this app can drive at checkout — cash and static QR are not gateway
     *  channels and are configured nowhere near here. */
    val configurableMethods: List<PaymentMethod> = PaymentMethod.entries.filter { !it.worksOffline }

    init {
        load()
    }

    fun load() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val keystoreHealthy = credentialStore.isKeystoreHealthy()
            when (val result = apiClient.getGatewayProviders()) {
                is ApiResult.Success -> {
                    val providers = result.data
                    // Open on the first provider that is actually usable, so the common case needs
                    // no tapping; fall back to the first listed when none are.
                    val initial = providers.firstOrNull { it.status == "AVAILABLE" }
                        ?: providers.firstOrNull()
                    _uiState.value = UiState(
                        isLoading = false,
                        keystoreHealthy = keystoreHealthy,
                        providers = providers,
                        selectedProvider = initial?.provider,
                        enabledMethods = initial?.enabledMethods
                            ?.mapNotNull { PaymentMethod.fromCode(it) }?.toSet().orEmpty(),
                        isSandbox = initial?.isSandbox ?: true,
                        isEnabled = initial?.isEnabled ?: false,
                    )
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false, keystoreHealthy = keystoreHealthy, error = result.message,
                )
                is ApiResult.NetworkError -> _uiState.value = _uiState.value.copy(
                    isLoading = false, keystoreHealthy = keystoreHealthy,
                    error = str().msgNetworkError.format(result.message),
                )
            }
        }
    }

    /** Switch the open form. Typed-but-unsaved input is dropped deliberately — carrying one
     *  provider's half-typed secret into another provider's form would be a way to save it against
     *  the wrong merchant account. */
    fun selectProvider(provider: String) {
        val target = _uiState.value.providers.firstOrNull { it.provider == provider } ?: return
        _uiState.value = _uiState.value.copy(
            selectedProvider = provider,
            credentialInputs = emptyMap(),
            enabledMethods = target.enabledMethods.mapNotNull { PaymentMethod.fromCode(it) }.toSet(),
            isSandbox = target.isSandbox,
            isEnabled = target.isEnabled,
            error = null,
            successMessage = null,
        )
    }

    fun updateCredential(key: String, value: String) {
        _uiState.value = _uiState.value.copy(
            credentialInputs = _uiState.value.credentialInputs + (key to value),
        )
    }

    /** Called directly to turn sandbox ON; the OFF direction is confirmed first by the screen. */
    fun updateSandbox(value: Boolean) {
        _uiState.value = _uiState.value.copy(isSandbox = value)
    }

    fun updateEnabled(value: Boolean) {
        _uiState.value = _uiState.value.copy(isEnabled = value)
    }

    fun toggleMethod(method: PaymentMethod, enabled: Boolean) {
        val current = _uiState.value.enabledMethods
        _uiState.value = _uiState.value.copy(
            enabledMethods = if (enabled) current + method else current - method,
        )
    }

    fun save() {
        val state = _uiState.value
        val provider = state.selected ?: return
        if (!state.canSave) return

        // Only non-blank inputs are sent. A blank field for an already-stored secret means "leave
        // it alone" — the server merges rather than replaces, so nothing is cleared by omission.
        val credentials = state.credentialInputs.filterValues { it.isNotBlank() }

        _uiState.value = state.copy(isSaving = true, error = null, successMessage = null)
        viewModelScope.launch {
            val result = apiClient.putGatewayProvider(
                provider = provider.provider,
                credentials = credentials,
                enabledMethods = state.enabledMethods.map { it.code },
                isSandbox = state.isSandbox,
                // A fail-closed adapter can never be switched on, whatever the toggle says. The
                // server enforces this too; matching it here keeps the UI honest rather than
                // showing "enabled" for a second until the next load contradicts it.
                isEnabled = state.isEnabled && provider.status == "AVAILABLE",
            )
            when (result) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        credentialInputs = emptyMap(),
                        successMessage = str().paymentGatewaySaveSuccess,
                    )
                    // Reload so fieldsSet/configured reflect what actually stuck.
                    load()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = str().paymentGatewaySaveFailed.format(result.message),
                )
                is ApiResult.NetworkError -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = str().msgNetworkError.format(result.message),
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(error = null, successMessage = null)
    }
}
