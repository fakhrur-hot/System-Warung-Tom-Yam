package com.razstudio.opsapp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.opsapp.data.CloudflareMode
import com.razstudio.opsapp.data.OperatorSelfRegistrar
import com.razstudio.opsapp.data.ProvisionRequest
import com.razstudio.opsapp.data.ProvisionResult
import com.razstudio.opsapp.data.ProvisionerClient
import com.razstudio.opsapp.data.StepResult
import com.razstudio.opsapp.data.SupabaseMode
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

/**
 * Backs the Provision New Cafe wizard in the Operator APK.
 *
 * Collects wizard inputs across multiple steps, validates them, and drives provisioning via
 * [ProvisionerClient]. High-privilege credentials live only in this ViewModel's state and are
 * never persisted.
 *
 * Unlike the main Admin APK's ProvisionerViewModel, this does NOT use WorkManager — the operator
 * stays on the screen for the entire run, and the ProvisionerClient's long timeout handles the
 * multi-minute backend work.
 */
@HiltViewModel
class ProvisionWizardViewModel @Inject constructor(
    private val provisionerClient: ProvisionerClient,
    private val selfRegistrar: OperatorSelfRegistrar,
    private val connectedCafeDao: ConnectedCafeDao,
) : ViewModel() {

    private val _state = MutableStateFlow(ProvisionWizardState())
    val state: StateFlow<ProvisionWizardState> = _state.asStateFlow()

    fun update(transform: (ProvisionWizardState) -> ProvisionWizardState) {
        _state.value = transform(_state.value)
    }

    /** Advance to the next wizard step if the current step is valid. */
    fun nextStep() {
        val s = _state.value
        val reason = validateCurrentStep()
        if (reason != null) {
            _state.value = s.copy(errorMessage = reason)
            return
        }
        val next = WizardStep.entries.getOrNull(s.currentStep.ordinal + 1) ?: return
        _state.value = s.copy(currentStep = next, errorMessage = null)
    }

    /** Go back to the previous wizard step. */
    fun previousStep() {
        val s = _state.value
        val prev = WizardStep.entries.getOrNull(s.currentStep.ordinal - 1) ?: return
        _state.value = s.copy(currentStep = prev, errorMessage = null)
    }

    /** Validate the current step and return a blocking reason, or null if valid. */
    fun validateCurrentStep(): String? {
        val s = _state.value
        return when (s.currentStep) {
            WizardStep.CAFE_INFO -> {
                if (s.cafeName.isBlank()) "Enter the café name."
                else null
            }
            WizardStep.SUPABASE -> {
                if (s.supabasePat.isBlank()) return "Enter the Supabase personal access token."
                when (s.supabaseMode) {
                    SupabaseModeSelection.NEW -> {
                        if (s.supabaseOrgId.isBlank()) return "Enter the Supabase organization ID."
                        if (s.supabaseRegion.isBlank()) return "Enter the Supabase region."
                        if (s.supabaseProjectName.isBlank()) return "Enter the desired Supabase project name."
                        null
                    }
                    SupabaseModeSelection.EXISTING -> {
                        if (s.supabaseProjectRef.isBlank()) return "Enter the Supabase project reference."
                        if (s.supabaseAnonKey.isBlank()) return "Enter the Supabase anon key."
                        if (s.supabaseServiceRoleKey.isBlank()) return "Enter the Supabase service role key."
                        null
                    }
                }
            }
            WizardStep.CLOUDFLARE -> {
                if (s.cloudflareAccountId.isBlank()) return "Enter the Cloudflare account ID."
                if (s.cloudflareApiToken.isBlank()) return "Enter the Cloudflare API token."
                when (s.cloudflareMode) {
                    CloudflareModeSelection.NEW -> {
                        if (s.cloudflareCafeSlug.isBlank()) return "Enter the café slug for the Pages project."
                        null
                    }
                    CloudflareModeSelection.EXISTING -> {
                        if (s.cloudflareProjectName.isBlank()) return "Enter the existing Cloudflare Pages project name."
                        null
                    }
                }
            }
            WizardStep.RUN -> null
            WizardStep.DONE -> null
        }
    }

    /** Start provisioning — called from the Run step. */
    fun startProvisioning() {
        val s = _state.value
        if (s.isRunning) return

        // Validate the wizard URL
        val wizardUrl = s.provisionerWorkerUrl.trim()
        if (wizardUrl.isBlank()) {
            _state.value = s.copy(errorMessage = "Enter the provisioning Wizard URL.")
            return
        }
        if (!wizardUrl.startsWith("https://")) {
            _state.value = s.copy(errorMessage = "The Wizard URL must start with https://.")
            return
        }

        val supabaseMode = when (s.supabaseMode) {
            SupabaseModeSelection.NEW -> SupabaseMode.New(
                personalAccessToken = s.supabasePat,
                orgId = s.supabaseOrgId,
                region = s.supabaseRegion,
                projectName = s.supabaseProjectName,
            )
            SupabaseModeSelection.EXISTING -> SupabaseMode.Existing(
                personalAccessToken = s.supabasePat,
                projectRef = s.supabaseProjectRef,
                anonKey = s.supabaseAnonKey,
                serviceRoleKey = s.supabaseServiceRoleKey,
            )
        }

        val cloudflareMode = when (s.cloudflareMode) {
            CloudflareModeSelection.NEW -> CloudflareMode.New(
                accountId = s.cloudflareAccountId,
                apiToken = s.cloudflareApiToken,
                cafeSlug = s.cloudflareCafeSlug,
                zoneId = s.cloudflareZoneId.takeIf { it.isNotBlank() },
                customDomain = s.cloudflareCustomDomain.takeIf { it.isNotBlank() },
            )
            CloudflareModeSelection.EXISTING -> CloudflareMode.Existing(
                accountId = s.cloudflareAccountId,
                apiToken = s.cloudflareApiToken,
                projectName = s.cloudflareProjectName,
                zoneId = s.cloudflareZoneId.takeIf { it.isNotBlank() },
                customDomain = s.cloudflareCustomDomain.takeIf { it.isNotBlank() },
            )
        }

        val request = ProvisionRequest(
            provisionerWorkerUrl = wizardUrl,
            supabaseMode = supabaseMode,
            cloudflareMode = cloudflareMode,
            cafeName = s.cafeName,
            brevoApiKey = s.brevoApiKey.takeIf { it.isNotBlank() },
        )

        _state.value = s.copy(
            isRunning = true,
            errorMessage = null,
            results = emptyList(),
            provisionResult = null,
        )

        viewModelScope.launch {
            try {
                val result = provisionerClient.provision(request)
                _state.value = _state.value.copy(
                    isRunning = false,
                    results = result.results,
                    provisionResult = result,
                    currentStep = WizardStep.DONE,
                    errorMessage = if (!result.success) {
                        result.results.firstOrNull { it.isError }
                            ?.let { "${it.step}: ${it.detail ?: "failed"}" }
                    } else null,
                )

                // On successful provisioning, perform the one-time self-registration into the
                // new café's devices table using the service-role key, then discard it.
                if (result.success &&
                    result.supabaseUrl != null &&
                    result.supabaseAnonKey != null &&
                    result.supabaseServiceRoleKey != null
                ) {
                    performSelfRegistration(result)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isRunning = false,
                    errorMessage = e.message ?: "Provisioning failed.",
                    currentStep = WizardStep.DONE,
                )
            }
        }
    }

    /**
     * Performs the one-time self-registration of this operator device into the newly provisioned
     * café's `devices` table. The service-role key is used for this single request and never stored.
     *
     * On success: inserts a [ConnectedCafeEntity] so the café appears in the home list immediately.
     * On failure: sets an error message but does NOT crash or undo the provisioning — the operator
     * can reconnect later via an Operator Invite if this step fails.
     */
    private fun performSelfRegistration(result: ProvisionResult) {
        viewModelScope.launch {
            try {
                val registration = selfRegistrar.register(
                    supabaseUrl = result.supabaseUrl!!,
                    supabaseAnonKey = result.supabaseAnonKey!!,
                    supabaseServiceRoleKey = result.supabaseServiceRoleKey!!,
                )
                // The service-role key is now out of scope — it was passed as a parameter to
                // register() and is never stored anywhere by this app.

                val now = DateTimeFormatter.ISO_INSTANT.format(
                    Instant.now().atOffset(ZoneOffset.UTC)
                )

                val cafeEntity = ConnectedCafeEntity(
                    id = registration.deviceId,
                    cafeName = result.cafeName ?: _state.value.cafeName,
                    cafeSlug = (result.cafeName ?: _state.value.cafeName)
                        .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
                    supabaseUrl = result.supabaseUrl,
                    supabaseAnonKey = result.supabaseAnonKey,
                    sessionToken = registration.sessionToken,
                    connectedAt = now,
                    lastConnectedAt = now,
                    ownerKeyUrl = result.ownerKeyUrl,
                )

                connectedCafeDao.insert(cafeEntity)

                _state.value = _state.value.copy(
                    selfRegistrationComplete = true,
                    connectedCafeId = registration.deviceId,
                )
            } catch (e: Exception) {
                // Self-registration failed but provisioning itself succeeded.
                // The operator can reconnect later via invite — don't crash.
                _state.value = _state.value.copy(
                    selfRegistrationComplete = false,
                    errorMessage = "Provisioning succeeded but self-registration failed: ${e.message}. " +
                        "You can reconnect to this café later via an Operator Invite.",
                )
            }
        }
    }
}

enum class WizardStep {
    CAFE_INFO,
    SUPABASE,
    CLOUDFLARE,
    RUN,
    DONE,
}

enum class SupabaseModeSelection { NEW, EXISTING }
enum class CloudflareModeSelection { NEW, EXISTING }

data class ProvisionWizardState(
    val currentStep: WizardStep = WizardStep.CAFE_INFO,

    // Wizard URL
    val provisionerWorkerUrl: String = "",

    // Step 1: Café info
    val cafeName: String = "",
    val brevoApiKey: String = "",

    // Step 2: Supabase
    val supabaseMode: SupabaseModeSelection = SupabaseModeSelection.NEW,
    val supabasePat: String = "",
    val supabaseOrgId: String = "",
    val supabaseRegion: String = "ap-southeast-1",
    val supabaseProjectName: String = "",
    val supabaseProjectRef: String = "",
    val supabaseAnonKey: String = "",
    val supabaseServiceRoleKey: String = "",

    // Step 3: Cloudflare
    val cloudflareMode: CloudflareModeSelection = CloudflareModeSelection.NEW,
    val cloudflareAccountId: String = "",
    val cloudflareApiToken: String = "",
    val cloudflareCafeSlug: String = "",
    val cloudflareProjectName: String = "",
    val cloudflareZoneId: String = "",
    val cloudflareCustomDomain: String = "",

    // Step 4: Run
    val isRunning: Boolean = false,
    val results: List<StepResult> = emptyList(),
    val provisionResult: ProvisionResult? = null,
    val errorMessage: String? = null,

    // Self-registration outcome (task 7.2)
    val selfRegistrationComplete: Boolean = false,
    val connectedCafeId: String? = null,
)
