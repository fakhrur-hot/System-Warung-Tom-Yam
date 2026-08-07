package com.razstudio.pos.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.CloudflareMode
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import com.razstudio.pos.data.ProvisionRequest
import com.razstudio.pos.data.ProvisionerClient
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.data.StepResult
import com.razstudio.pos.data.SupabaseMode
import com.razstudio.pos.work.ProvisionWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the in-app Provisioner screen (installer flow).
 *
 * High-privilege credentials live only in this ViewModel's state and in the WorkManager input data
 * that is passed straight to the backend worker. They are never written to any persisted store.
 */
@HiltViewModel
class ProvisionerViewModel @Inject constructor(
    application: Application,
    private val appConfig: AppConfigStore,
    private val modeRepository: ModeRepository,
    private val secureStorage: SecureStorage,
    private val provisionerClient: ProvisionerClient,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ProvisionerState())
    val state: StateFlow<ProvisionerState> = _state.asStateFlow()

    private val workManager = WorkManager.getInstance(application)

    init {
        // Prefill the Wizard URL from whatever this device was last pointed at. It is not baked into
        // the build any more, so without this an installer re-types it on every attempt — and
        // provisioning is retried more often than it succeeds first time.
        secureStorage.getProvisionerWorkerUrl()?.takeIf { it.isNotBlank() }?.let { saved ->
            _state.value = _state.value.copy(provisionerWorkerUrl = saved)
        }

        workManager.getWorkInfosForUniqueWorkLiveData(ProvisionWorker.WORK_NAME)
            .asFlow()
            .map { infos -> infos.firstOrNull() }
            .onEach { info -> updateFromWorkInfo(info) }
            .launchIn(viewModelScope)
    }

    fun update(transform: (ProvisionerState) -> ProvisionerState) {
        _state.value = transform(_state.value)
    }

    /** True when the form has enough filled for the selected modes. */
    fun canStart(): Boolean = blockingReason() == null

    fun blockingReason(): String? {
        val s = _state.value

        // Checked first, and checked properly. Every credential on this form is posted to this URL, so
        // a typo does not merely fail — it hands a Supabase personal access token and a Cloudflare API
        // token to whatever host was actually typed. Requiring https is the part that matters: over
        // plaintext http those same credentials cross the network readable.
        val wizardUrl = s.provisionerWorkerUrl.trim()
        if (wizardUrl.isBlank()) return "Enter the provisioning Wizard URL."
        if (!wizardUrl.startsWith("https://")) {
            return "The Wizard URL must start with https:// — credentials are posted to it."
        }
        if (android.net.Uri.parse(wizardUrl).host.isNullOrBlank()) {
            return "The Wizard URL is not a valid URL."
        }

        if (s.supabaseMode == SupabaseModeSelection.NEW) {
            if (s.supabasePat.isBlank()) return "Enter the Supabase personal access token."
            if (s.supabaseOrgId.isBlank()) return "Enter the Supabase organization ID."
            if (s.supabaseRegion.isBlank()) return "Enter the Supabase region."
            if (s.supabaseProjectName.isBlank()) return "Enter the desired Supabase project name."
        } else {
            if (s.supabasePat.isBlank()) return "Enter the Supabase personal access token."
            if (s.supabaseProjectRef.isBlank()) return "Enter the Supabase project reference."
            if (s.supabaseAnonKey.isBlank()) return "Enter the Supabase anon key."
            if (s.supabaseServiceRoleKey.isBlank()) return "Enter the Supabase service role key."
        }

        if (s.cloudflareMode == CloudflareModeSelection.NEW) {
            if (s.cloudflareAccountId.isBlank()) return "Enter the Cloudflare account ID."
            if (s.cloudflareApiToken.isBlank()) return "Enter the Cloudflare API token."
            if (s.cloudflareCafeSlug.isBlank()) return "Enter the café slug for the Pages project."
        } else {
            if (s.cloudflareAccountId.isBlank()) return "Enter the Cloudflare account ID."
            if (s.cloudflareApiToken.isBlank()) return "Enter the Cloudflare API token."
            if (s.cloudflareProjectName.isBlank()) return "Enter the existing Cloudflare Pages project name."
        }

        if (s.cafeName.isBlank()) return "Enter the café name."
        return null
    }

    fun startProvisioning() {
        val reason = blockingReason()
        if (reason != null) {
            _state.value = _state.value.copy(errorMessage = reason, isRunning = false)
            return
        }

        val s = _state.value
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

        val wizardUrl = s.provisionerWorkerUrl.trim()
        // Remembered only once the form is otherwise valid and provisioning is actually being started,
        // so a half-typed URL never becomes the prefill for the next attempt.
        secureStorage.saveProvisionerWorkerUrl(wizardUrl)

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
            success = false,
            resolvedSupabaseUrl = null,
            resolvedSupabaseAnonKey = null,
            resolvedWebsiteUrl = null,
            resolvedOwnerKeyUrl = null,
        )
        ProvisionWorker.start(getApplication(), request)
    }

    /**
     * Deploy the Edge Functions to an existing Supabase project, without provisioning anything else.
     *
     * Runs on the ViewModel scope rather than through [ProvisionWorker]: this is one request that
     * finishes in tens of seconds, not the multi-minute project-creation run WorkManager exists to
     * survive. Routing it through the worker would also fight the full run for the same unique work
     * name, so a repair could cancel a provisioning in progress.
     *
     * Deliberately requires only the PAT and the project ref — the two things a café's owner already
     * has for a project that exists. Demanding a Cloudflare token and a café name to redeploy 26
     * functions is what made the full run useless as a repair tool.
     */
    fun deployFunctionsOnly() {
        val s = _state.value
        val wizardUrl = s.provisionerWorkerUrl.trim()
        if (wizardUrl.isBlank() || !wizardUrl.startsWith("https://")) {
            _state.value = s.copy(errorMessage = "Enter the provisioning Wizard URL (https://…).")
            return
        }
        if (s.supabasePat.isBlank()) {
            _state.value = s.copy(errorMessage = "Enter the Supabase personal access token.")
            return
        }
        // The project ref is only asked for in EXISTING mode; in NEW mode there is no project yet, so
        // there is nothing to deploy to and the button is not offered.
        val projectRef = s.supabaseProjectRef.trim()
        if (projectRef.isBlank()) {
            _state.value = s.copy(errorMessage = "Enter the Supabase project reference.")
            return
        }

        secureStorage.saveProvisionerWorkerUrl(wizardUrl)
        _state.value = s.copy(isRunning = true, errorMessage = null, results = emptyList(), success = false)

        viewModelScope.launch {
            try {
                val result = provisionerClient.deployFunctions(wizardUrl, s.supabasePat, projectRef)
                _state.value = _state.value.copy(
                    isRunning = false,
                    results = result.results,
                    // Only the deploy succeeded — NOT a provisioned café. `success` drives the owner-key
                    // QR dialog, and there is no owner key here, so it stays false on purpose.
                    success = false,
                    errorMessage = result.results.firstOrNull { it.isError }
                        ?.let { "${it.step}: ${it.detail ?: "failed"}" },
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isRunning = false,
                    errorMessage = e.message ?: "Deploying the Edge Functions failed.",
                )
            }
        }
    }

    fun saveConfiguration() {
        val s = _state.value
        val url = s.resolvedSupabaseUrl?.trim()?.trimEnd('/')
        val key = s.resolvedSupabaseAnonKey?.trim()
        val website = s.resolvedWebsiteUrl?.trim()?.trimEnd('/')
        val name = s.cafeName.trim()
        if (url.isNullOrBlank() || key.isNullOrBlank() || website.isNullOrBlank()) return

        appConfig.save(
            supabaseUrl = url,
            supabaseAnonKey = key,
            websiteUrl = website,
            cafeName = name,
        )
        modeRepository.setMode(OperatingMode.CLOUD)
        appConfig.setOperatingMode(OperatingMode.CLOUD)
        _state.value = s.copy(configurationSaved = true)
    }

    private fun updateFromWorkInfo(info: WorkInfo?) {
        if (info == null) return
        val current = _state.value
        when (info.state) {
            WorkInfo.State.RUNNING -> {
                // The WorkManager job is alive; the backend itself is doing the sequential work.
                // We only know success/failure once the request completes, so we show a spinner.
                _state.value = current.copy(isRunning = true)
            }
            WorkInfo.State.SUCCEEDED -> {
                val result = ProvisionWorker.parseResult(info.outputData)
                if (result is ProvisionWorker.ParsedResult.Success) {
                    _state.value = current.copy(
                        isRunning = false,
                        results = result.results,
                        success = result.results.none { it.isError },
                        errorMessage = result.results.firstOrNull { it.isError }?.detail,
                        resolvedSupabaseUrl = result.supabaseUrl,
                        resolvedSupabaseAnonKey = result.supabaseAnonKey,
                        resolvedWebsiteUrl = result.websiteUrl,
                        cafeName = result.cafeName ?: current.cafeName,
                        resolvedOwnerKeyUrl = result.ownerKeyUrl,
                    )
                } else if (result is ProvisionWorker.ParsedResult.Failed) {
                    _state.value = current.copy(isRunning = false, errorMessage = result.error)
                }
            }
            WorkInfo.State.FAILED -> {
                val result = ProvisionWorker.parseResult(info.outputData)
                if (result is ProvisionWorker.ParsedResult.Failed) {
                    _state.value = current.copy(isRunning = false, errorMessage = result.error)
                } else {
                    _state.value = current.copy(isRunning = false, errorMessage = "Provisioning failed.")
                }
            }
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.CANCELLED -> {
                // ENQUEUED/BLOCKED are still pre-running; CANCELLED is user/back-stack.
                _state.value = current.copy(isRunning = info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED)
            }
        }
    }
}

enum class SupabaseModeSelection { NEW, EXISTING }
enum class CloudflareModeSelection { NEW, EXISTING }

data class ProvisionerState(
    /** The Wizard's `/api/provision/run` endpoint. Typed on the screen; never a build constant. */
    val provisionerWorkerUrl: String = "",

    val supabaseMode: SupabaseModeSelection = SupabaseModeSelection.NEW,
    val supabasePat: String = "",
    val supabaseOrgId: String = "",
    val supabaseRegion: String = "ap-southeast-1",
    val supabaseProjectName: String = "",
    val supabaseProjectRef: String = "",
    val supabaseAnonKey: String = "",
    val supabaseServiceRoleKey: String = "",

    val cloudflareMode: CloudflareModeSelection = CloudflareModeSelection.NEW,
    val cloudflareAccountId: String = "",
    val cloudflareApiToken: String = "",
    val cloudflareCafeSlug: String = "",
    val cloudflareProjectName: String = "",
    val cloudflareZoneId: String = "",
    val cloudflareCustomDomain: String = "",

    val cafeName: String = "",
    val brevoApiKey: String = "",

    val isRunning: Boolean = false,
    val results: List<StepResult> = emptyList(),
    val success: Boolean = false,
    val errorMessage: String? = null,

    val resolvedSupabaseUrl: String? = null,
    val resolvedSupabaseAnonKey: String? = null,
    val resolvedWebsiteUrl: String? = null,
    val resolvedOwnerKeyUrl: String? = null,

    val configurationSaved: Boolean = false,
)
