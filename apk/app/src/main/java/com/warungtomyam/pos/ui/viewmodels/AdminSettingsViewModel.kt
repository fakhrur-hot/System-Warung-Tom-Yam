package com.warungtomyam.pos.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warungtomyam.pos.data.ApiClient
import com.warungtomyam.pos.data.ApiResult
import com.warungtomyam.pos.data.InviteResponse
import com.warungtomyam.pos.data.local.SettingsDao
import com.warungtomyam.pos.data.local.SystemSettings
import com.warungtomyam.pos.ui.i18n.LanguageManager
import com.warungtomyam.pos.ui.i18n.uiStrings
import com.warungtomyam.pos.ui.util.LogoPipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.TimeZone
import javax.inject.Inject

/**
 * ViewModel for Admin Settings screen.
 * Manages invite state, staff permissions, café location, and branding/logo pipeline.
 *
 * All editable fields are staged locally. Nothing is written to the backend until
 * [saveAll] is called. [isDirty] is true when any field differs from the
 * last-loaded [UiState.Snapshot]. [cancelAll] discards staged changes and reloads
 * from the backend.
 */
@HiltViewModel
class AdminSettingsViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val settingsDao: SettingsDao,
    @ApplicationContext private val context: Context,
    private val languageManager: LanguageManager,
    private val printSettingsStore: com.warungtomyam.pos.data.local.PrintSettingsStore
) : ViewModel() {

    private fun str() = uiStrings(languageManager.language.value)

    /**
     * Last-known-good values loaded from the backend.
     * Used to compute [UiState.isDirty] and to restore on [cancelAll].
     */
    data class Snapshot(
        val staffCanSendKitchen: Boolean = false,
        val staffCanTakePayment: Boolean = false,
        val cafeName: String = "",
        val logoBase64: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val radiusMeters: Int = 100,
        val timezone: String = "",
        val holdSeconds: Int = 15,
        val autoPrintToKitchen: Boolean = true,
        val todaysSpecial: String = "",
        val reportEmail: String = "",
        val businessDayStartHour: Int = 15,
    )

    data class UiState(
        // Invite (staff / ordering)
        val invite: InviteResponse? = null,
        val inviteLoading: Boolean = false,
        // Invite for adding a SECONDARY ADMIN device (full management, no local printer)
        val adminInvite: InviteResponse? = null,
        val adminInviteLoading: Boolean = false,
        // Permanent owner-recovery key (restores Main Admin on a fresh device). Keep secret.
        val recoveryInvite: InviteResponse? = null,
        val recoveryLoading: Boolean = false,
        // Kitchen-slip menu-text size (device-local): XS/S/M/L/XL/XXL. Applied immediately.
        val kitchenFontSize: String = "S",

        // Staff Permissions
        val staffCanSendKitchen: Boolean = false,
        val staffCanTakePayment: Boolean = false,
        val permissionsLoading: Boolean = false,

        // GPS Location
        val latitude: Double? = null,
        val longitude: Double? = null,
        val radiusMeters: Int = 100,
        val locationLoading: Boolean = false,
        val locationSaved: Boolean = false,

        // Timezone — auto-detected when the café location is captured, synced everywhere
        // (DB, reports, kitchen slips, receipts, attendance) as the single source of truth.
        val timezone: String = "",

        // Customer "hold before kitchen" delay (seconds) — one of 10/15/30/60.
        val holdSeconds: Int = 15,

        // Auto-print to kitchen: ON = held order prints automatically; OFF = buffered into the
        // Pending Kitchen Prints modal for the cashier to release each one manually.
        val autoPrintToKitchen: Boolean = true,

        // Free-text "today's special" shown on the customer menu (cleared at closing).
        val todaysSpecial: String = "",

        // Recipient for the server-side (Brevo) closing/monthly report emails.
        val reportEmail: String = "",

        // Business-day start hour (0–23, default 15 = 3 PM) — late-night cafés anchor reports
        // to the opening day rather than the post-midnight calendar date.
        val businessDayStartHour: Int = 15,

        // Branding
        val cafeName: String = "",
        val existingLogoUrl: String? = null,
        val logoPreview: Bitmap? = null,
        val logoBase64: String? = null,
        val brandingLoading: Boolean = false,
        val brandingSaved: Boolean = false,

        // Snapshot — last-known-good state loaded from backend
        val savedSnapshot: Snapshot = Snapshot(),

        // General
        val error: String? = null,
        val successMessage: String? = null
    ) {
        /**
         * True when any editable field differs from the last-loaded snapshot.
         * Both Save and Cancel are enabled only while this is true.
         */
        val isDirty: Boolean
            get() = staffCanSendKitchen != savedSnapshot.staffCanSendKitchen ||
                staffCanTakePayment != savedSnapshot.staffCanTakePayment ||
                cafeName != savedSnapshot.cafeName ||
                logoBase64 != savedSnapshot.logoBase64 ||
                latitude != savedSnapshot.latitude ||
                longitude != savedSnapshot.longitude ||
                radiusMeters != savedSnapshot.radiusMeters ||
                timezone != savedSnapshot.timezone ||
                holdSeconds != savedSnapshot.holdSeconds ||
                autoPrintToKitchen != savedSnapshot.autoPrintToKitchen ||
                todaysSpecial != savedSnapshot.todaysSpecial ||
                reportEmail != savedSnapshot.reportEmail ||
                businessDayStartHour != savedSnapshot.businessDayStartHour
    }

    private val _uiState = MutableStateFlow(UiState(kitchenFontSize = printSettingsStore.getKitchenFontSize()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadInvite()
        loadBranding()
        loadPermissions()
    }

    /** Kitchen-slip menu font size is device-local — persist and apply immediately. */
    fun updateKitchenFontSize(size: String) {
        printSettingsStore.setKitchenFontSize(size)
        _uiState.value = _uiState.value.copy(kitchenFontSize = size)
    }

    /**
     * Populate the café name/logo fields with the server's current branding — needed
     * because this ViewModel (and Room) start empty on every fresh install/relogin,
     * and there was previously no load path at all, only save. Without this, the
     * Settings screen looked like the café name/logo had been lost after a relogin
     * even though the server-side data was untouched the whole time.
     *
     * Also updates [UiState.savedSnapshot] so [isDirty] is false right after a load.
     */
    fun loadBranding() {
        viewModelScope.launch {
            when (val result = apiClient.getBranding()) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        cafeName = result.data.cafeName,
                        existingLogoUrl = result.data.logoUrl.ifBlank { null },
                        // Update snapshot so isDirty stays false after a fresh load
                        savedSnapshot = _uiState.value.savedSnapshot.copy(
                            cafeName = result.data.cafeName
                        )
                    )
                }
                else -> {
                    // Best-effort: leave fields blank, admin can still re-enter/re-save.
                }
            }
        }
    }

    /**
     * Load staff permission settings from the backend and update both the editable
     * fields and the snapshot so [isDirty] is false right after a fresh load.
     */
    fun loadPermissions() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(permissionsLoading = true)
            when (val result = apiClient.getSettings()) {
                is ApiResult.Success -> {
                    val tz = result.data.timezone
                    // Mirror the backend timezone into local Room so PrintService (kitchen
                    // slips / receipts) renders timestamps in the café zone immediately.
                    if (tz.isNotBlank()) {
                        val existing = settingsDao.get() ?: SystemSettings()
                        settingsDao.upsert(existing.copy(timezone = tz))
                    }
                    _uiState.value = _uiState.value.copy(
                        staffCanSendKitchen = result.data.staffCanSendKitchen,
                        staffCanTakePayment = result.data.staffCanTakePayment,
                        timezone = tz,
                        holdSeconds = result.data.customerOrderHoldSeconds,
                        autoPrintToKitchen = result.data.customerOrderAutoPrint,
                        todaysSpecial = result.data.todaysSpecial,
                        reportEmail = result.data.reportEmail,
                        businessDayStartHour = result.data.businessDayStartHour,
                        permissionsLoading = false,
                        savedSnapshot = _uiState.value.savedSnapshot.copy(
                            staffCanSendKitchen = result.data.staffCanSendKitchen,
                            staffCanTakePayment = result.data.staffCanTakePayment,
                            timezone = tz,
                            holdSeconds = result.data.customerOrderHoldSeconds,
                            autoPrintToKitchen = result.data.customerOrderAutoPrint,
                            todaysSpecial = result.data.todaysSpecial,
                            reportEmail = result.data.reportEmail,
                            businessDayStartHour = result.data.businessDayStartHour
                        )
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        permissionsLoading = false,
                        error = result.message
                    )
                }
                is ApiResult.NetworkError -> {
                    _uiState.value = _uiState.value.copy(
                        permissionsLoading = false,
                        error = str().msgNetworkError.format(result.message)
                    )
                }
            }
        }
    }

    // --- Invite ---

    fun loadInvite() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(inviteLoading = true)
            when (val result = apiClient.getInvite()) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        invite = result.data,
                        inviteLoading = false
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        inviteLoading = false,
                        error = result.message
                    )
                }
                is ApiResult.NetworkError -> {
                    _uiState.value = _uiState.value.copy(
                        inviteLoading = false,
                        error = str().msgNetworkError.format(result.message)
                    )
                }
            }
        }
    }

    fun regenerateInvite() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(inviteLoading = true)
            when (val result = apiClient.regenerateInvite()) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        invite = result.data,
                        inviteLoading = false,
                        successMessage = str().invitationRegenerated
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        inviteLoading = false,
                        error = result.message
                    )
                }
                is ApiResult.NetworkError -> {
                    _uiState.value = _uiState.value.copy(
                        inviteLoading = false,
                        error = str().msgNetworkError.format(result.message)
                    )
                }
            }
        }
    }

    // --- Secondary-admin invite (role=admin) ---

    fun loadAdminInvite() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(adminInviteLoading = true)
            when (val result = apiClient.getInvite("admin")) {
                is ApiResult.Success ->
                    _uiState.value = _uiState.value.copy(adminInvite = result.data, adminInviteLoading = false)
                is ApiResult.Error ->
                    _uiState.value = _uiState.value.copy(adminInviteLoading = false, error = result.message)
                is ApiResult.NetworkError ->
                    _uiState.value = _uiState.value.copy(adminInviteLoading = false, error = str().msgNetworkError.format(result.message))
            }
        }
    }

    fun loadRecoveryToken() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(recoveryLoading = true)
            when (val result = apiClient.getRecoveryToken()) {
                is ApiResult.Success ->
                    _uiState.value = _uiState.value.copy(recoveryInvite = result.data, recoveryLoading = false)
                is ApiResult.Error ->
                    _uiState.value = _uiState.value.copy(recoveryLoading = false, error = result.message)
                is ApiResult.NetworkError ->
                    _uiState.value = _uiState.value.copy(recoveryLoading = false, error = str().msgNetworkError.format(result.message))
            }
        }
    }

    fun regenerateAdminInvite() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(adminInviteLoading = true)
            when (val result = apiClient.regenerateInvite("admin")) {
                is ApiResult.Success ->
                    _uiState.value = _uiState.value.copy(adminInvite = result.data, adminInviteLoading = false, successMessage = str().invitationRegenerated)
                is ApiResult.Error ->
                    _uiState.value = _uiState.value.copy(adminInviteLoading = false, error = result.message)
                is ApiResult.NetworkError ->
                    _uiState.value = _uiState.value.copy(adminInviteLoading = false, error = str().msgNetworkError.format(result.message))
            }
        }
    }

    // --- Staff Permissions ---

    /**
     * Stage a local change to the "staff can send to kitchen" toggle.
     * Does NOT call the backend — committed via [saveAll].
     */
    fun updateStaffCanSendKitchen(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(staffCanSendKitchen = enabled)
        // No pushPermissions() — staged, saved via saveAll()
    }

    /**
     * Stage a local change to the "staff can take payment" toggle.
     * Does NOT call the backend — committed via [saveAll].
     */
    fun updateStaffCanTakePayment(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(staffCanTakePayment = enabled)
        // No pushPermissions() — staged, saved via saveAll()
    }

    /**
     * Push the current permission state to the backend.
     * Called only from [saveAll], not directly from the UI.
     */
    private suspend fun pushPermissions(): Boolean {
        _uiState.value = _uiState.value.copy(permissionsLoading = true)
        val body = JSONObject().apply {
            put("staffCanSendKitchen", _uiState.value.staffCanSendKitchen)
            put("staffCanTakePayment", _uiState.value.staffCanTakePayment)
        }
        return when (val result = apiClient.putSettings(body)) {
            is ApiResult.Success -> {
                _uiState.value = _uiState.value.copy(permissionsLoading = false)
                true
            }
            is ApiResult.Error -> {
                _uiState.value = _uiState.value.copy(
                    permissionsLoading = false,
                    error = "Permissions: ${result.message}"
                )
                false
            }
            is ApiResult.NetworkError -> {
                _uiState.value = _uiState.value.copy(
                    permissionsLoading = false,
                    error = str().msgNetworkError.format(result.message)
                )
                false
            }
        }
    }

    // --- GPS Location ---

    fun updateLatitude(lat: Double) {
        _uiState.value = _uiState.value.copy(latitude = lat, locationSaved = false)
    }

    fun updateLongitude(lng: Double) {
        _uiState.value = _uiState.value.copy(longitude = lng, locationSaved = false)
    }

    fun updateRadius(radius: Int) {
        _uiState.value = _uiState.value.copy(radiusMeters = radius, locationSaved = false)
    }

    /** Stage the customer hold-before-kitchen delay (10/15/30/60s); committed via [saveAll]. */
    fun updateHoldSeconds(seconds: Int) {
        _uiState.value = _uiState.value.copy(holdSeconds = seconds)
    }

    /** Stage the auto-print-to-kitchen toggle; committed via [saveAll]. */
    fun updateAutoPrintToKitchen(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoPrintToKitchen = enabled)
    }

    /** Stage the "today's special" text; committed via [saveAll]. */
    fun updateTodaysSpecial(text: String) {
        _uiState.value = _uiState.value.copy(todaysSpecial = text.take(200))
    }

    /** Stage the report-email recipient; committed via [saveAll]. */
    fun updateReportEmail(email: String) {
        _uiState.value = _uiState.value.copy(reportEmail = email.trim())
    }

    /** Stage the business-day start hour (0–23); committed via [saveAll]. */
    fun updateBusinessDayStartHour(hour: Int) {
        _uiState.value = _uiState.value.copy(businessDayStartHour = hour.coerceIn(0, 23))
    }

    fun onLocationCaptured(lat: Double, lng: Double) {
        // Auto-detect the timezone from the device when the café location is captured.
        // The device's zone is authoritative here (the admin is physically at the café),
        // and it becomes the single timezone synced everywhere on save.
        _uiState.value = _uiState.value.copy(
            latitude = lat,
            longitude = lng,
            timezone = TimeZone.getDefault().id,
            locationSaved = false
        )
    }

    /**
     * Persist the current GPS location to the backend.
     * Called only from [saveAll], not directly from the UI.
     */
    private suspend fun saveCafeLocation(): Boolean {
        val state = _uiState.value
        val lat = state.latitude ?: return false
        val lng = state.longitude ?: return false

        _uiState.value = _uiState.value.copy(locationLoading = true)
        return when (val result = apiClient.putCafeLocation(lat, lng, state.radiusMeters)) {
            is ApiResult.Success -> {
                _uiState.value = _uiState.value.copy(
                    locationLoading = false,
                    locationSaved = true
                )
                true
            }
            is ApiResult.Error -> {
                _uiState.value = _uiState.value.copy(
                    locationLoading = false,
                    error = "Location: ${result.message}"
                )
                false
            }
            is ApiResult.NetworkError -> {
                _uiState.value = _uiState.value.copy(
                    locationLoading = false,
                    error = str().msgNetworkError.format(result.message)
                )
                false
            }
        }
    }

    // --- Branding ---

    fun updateCafeName(name: String) {
        _uiState.value = _uiState.value.copy(cafeName = name, brandingSaved = false)
    }

    /**
     * Process picked image through the logo pipeline.
     * Produces JPEG ≤ 200 KB + monochrome 1-bit raster for ESC/POS printing.
     */
    fun processLogo(imageUri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(brandingLoading = true)
            val result = withContext(Dispatchers.IO) {
                LogoPipeline.process(context, imageUri)
            }
            if (result != null) {
                _uiState.value = _uiState.value.copy(
                    logoPreview = result.previewBitmap,
                    logoBase64 = result.jpegBase64,
                    brandingLoading = false,
                    brandingSaved = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    brandingLoading = false,
                    error = str().logoProcessFailed
                )
            }
        }
    }

    /**
     * Persist the current café name and logo to the backend.
     * Called only from [saveAll], not directly from the UI.
     */
    private suspend fun saveBranding(): Boolean {
        val state = _uiState.value
        val name = state.cafeName.ifBlank { return false }
        // A logo isn't required on every save — the admin may just be renaming the
        // café — the server keeps the existing logo when logoBase64 is omitted.
        val base64 = state.logoBase64

        _uiState.value = _uiState.value.copy(brandingLoading = true)
        return when (val result = apiClient.putBranding(name, base64)) {
            is ApiResult.Success -> {
                _uiState.value = _uiState.value.copy(
                    brandingLoading = false,
                    brandingSaved = true,
                    existingLogoUrl = result.data.logoUrl.ifBlank { null }
                )
                true
            }
            is ApiResult.Error -> {
                _uiState.value = _uiState.value.copy(
                    brandingLoading = false,
                    error = "Branding: ${result.message}"
                )
                false
            }
            is ApiResult.NetworkError -> {
                _uiState.value = _uiState.value.copy(
                    brandingLoading = false,
                    error = str().msgNetworkError.format(result.message)
                )
                false
            }
        }
    }

    // --- Aggregate Save / Cancel ---

    /**
     * Persist every staged change to the backend in one user action.
     * Only sections that differ from [UiState.savedSnapshot] are sent.
     * On full success, updates the snapshot and shows "Settings saved".
     * On any failure, sets [UiState.error] with the section that failed.
     */
    fun saveAll() {
        viewModelScope.launch {
            val state = _uiState.value
            var anyError = false

            // Permissions — dirty when either toggle differs from snapshot
            if (state.staffCanSendKitchen != state.savedSnapshot.staffCanSendKitchen ||
                state.staffCanTakePayment != state.savedSnapshot.staffCanTakePayment
            ) {
                if (!pushPermissions()) anyError = true
            }

            // Location — dirty when coordinates or radius differ (only save when we have a fix)
            if (!anyError &&
                state.latitude != null && (
                    state.latitude != state.savedSnapshot.latitude ||
                    state.longitude != state.savedSnapshot.longitude ||
                    state.radiusMeters != state.savedSnapshot.radiusMeters
                )
            ) {
                if (!saveCafeLocation()) anyError = true
            }

            // Branding — dirty when name or logo differ
            if (!anyError && (
                state.cafeName != state.savedSnapshot.cafeName ||
                state.logoBase64 != state.savedSnapshot.logoBase64
            )) {
                if (!saveBranding()) anyError = true
            }

            // Timezone — dirty when the auto-detected zone differs. Push to backend AND
            // mirror to local Room so print docs pick it up right away.
            if (!anyError && state.timezone.isNotBlank() &&
                state.timezone != state.savedSnapshot.timezone
            ) {
                when (apiClient.putSettings(JSONObject().put("timezone", state.timezone))) {
                    is ApiResult.Success -> {
                        val existing = settingsDao.get() ?: SystemSettings()
                        settingsDao.upsert(existing.copy(timezone = state.timezone))
                    }
                    else -> {
                        anyError = true
                        _uiState.value = _uiState.value.copy(error = str().saveFailedGeneric)
                    }
                }
            }

            // Customer hold delay — dirty when it differs from the snapshot.
            if (!anyError && state.holdSeconds != state.savedSnapshot.holdSeconds) {
                when (apiClient.putSettings(JSONObject().put("customerOrderHoldSeconds", state.holdSeconds))) {
                    is ApiResult.Success -> { /* persisted */ }
                    else -> {
                        anyError = true
                        _uiState.value = _uiState.value.copy(error = str().saveFailedGeneric)
                    }
                }
            }

            // Auto-print-to-kitchen — dirty when it differs from the snapshot.
            if (!anyError && state.autoPrintToKitchen != state.savedSnapshot.autoPrintToKitchen) {
                when (apiClient.putSettings(JSONObject().put("customerOrderAutoPrint", state.autoPrintToKitchen))) {
                    is ApiResult.Success -> { /* persisted */ }
                    else -> {
                        anyError = true
                        _uiState.value = _uiState.value.copy(error = str().saveFailedGeneric)
                    }
                }
            }

            // Today's special — dirty when it differs from the snapshot.
            if (!anyError && state.todaysSpecial != state.savedSnapshot.todaysSpecial) {
                when (apiClient.putSettings(JSONObject().put("todaysSpecial", state.todaysSpecial))) {
                    is ApiResult.Success -> { /* persisted */ }
                    else -> {
                        anyError = true
                        _uiState.value = _uiState.value.copy(error = str().saveFailedGeneric)
                    }
                }
            }

            // Report email recipient — dirty when it differs from the snapshot.
            if (!anyError && state.reportEmail != state.savedSnapshot.reportEmail) {
                when (apiClient.putSettings(JSONObject().put("reportEmail", state.reportEmail))) {
                    is ApiResult.Success -> { /* persisted */ }
                    else -> {
                        anyError = true
                        _uiState.value = _uiState.value.copy(error = str().saveFailedGeneric)
                    }
                }
            }

            // Business-day start hour — dirty when it differs from the snapshot.
            if (!anyError && state.businessDayStartHour != state.savedSnapshot.businessDayStartHour) {
                when (apiClient.putSettings(JSONObject().put("businessDayStartHour", state.businessDayStartHour))) {
                    is ApiResult.Success -> { /* persisted */ }
                    else -> {
                        anyError = true
                        _uiState.value = _uiState.value.copy(error = str().saveFailedGeneric)
                    }
                }
            }

            if (!anyError) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    savedSnapshot = Snapshot(
                        staffCanSendKitchen = current.staffCanSendKitchen,
                        staffCanTakePayment = current.staffCanTakePayment,
                        cafeName = current.cafeName,
                        logoBase64 = current.logoBase64,
                        latitude = current.latitude,
                        longitude = current.longitude,
                        radiusMeters = current.radiusMeters,
                        timezone = current.timezone,
                        holdSeconds = current.holdSeconds,
                        autoPrintToKitchen = current.autoPrintToKitchen,
                        todaysSpecial = current.todaysSpecial,
                        reportEmail = current.reportEmail,
                        businessDayStartHour = current.businessDayStartHour,
                    ),
                    successMessage = str().settingsSaved
                )
            }
        }
    }

    /**
     * Discard all staged changes by reloading every section from the backend.
     * The snapshot is updated by each [load*] call, so [isDirty] returns to false.
     */
    fun cancelAll() {
        loadBranding()
        loadPermissions()
        // Staged logo is cleared — after reload the server state is authoritative
        _uiState.value = _uiState.value.copy(
            logoPreview = null,
            logoBase64 = null
        )
    }

    // --- General ---

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(error = null, successMessage = null)
    }
}
