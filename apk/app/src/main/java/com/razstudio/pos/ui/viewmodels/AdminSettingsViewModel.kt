package com.razstudio.pos.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.InviteResponse
import com.razstudio.pos.data.local.SettingsDao
import com.razstudio.pos.data.local.SystemSettings
import com.razstudio.pos.ui.i18n.LanguageManager
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.util.LogoPipeline
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
    private val printSettingsStore: com.razstudio.pos.data.local.PrintSettingsStore,
    private val appConfigStore: com.razstudio.pos.data.AppConfigStore
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
        val defaultLangAdmin: String = "BM",
        val defaultLangOrdering: String = "BM",
        val defaultLangCustomer: String = "BM",
        val printLanguage: String = "EN",
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

        // Print the café logo as a header on customer receipts (device-local). Applied immediately.
        val receiptLogo: Boolean = false,

        // Use ESC * bit-image mode for bitmap printing (device-local; default on for cheap 58mm
        // printers that can't do GS v 0 raster). Applied immediately.
        val escAsteriskMode: Boolean = true,

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

        // Café-wide default UI language per surface (BM/EN/ZH/TA/TH). A device/browser applies
        // its surface's default only when it has no locally-saved language choice yet.
        val defaultLangAdmin: String = "BM",
        val defaultLangOrdering: String = "BM",
        val defaultLangCustomer: String = "BM",

        // Printer language for kitchen slips & receipts (BM/EN only). ALWAYS used for prints
        // regardless of any device's UI language; mirrored into Room for PrintService.
        val printLanguage: String = "EN",

        // Branding
        val cafeName: String = "",
        val existingLogoUrl: String? = null,
        val logoPreview: Bitmap? = null,
        val logoBase64: String? = null,
        // Payment QR (Requirement 14.1). paymentQrPreview doubles as the "configured" signal for the
        // settings thumbnail; paymentQrHash is what actually gates the Show QR button elsewhere, so a
        // null hash means not-configured no matter what the preview holds.
        val paymentQrPreview: Bitmap? = null,
        val paymentQrHash: String? = null,
        val paymentQrBusy: Boolean = false,
        val paymentQrError: String? = null,
        val brandingLoading: Boolean = false,
        val brandingSaved: Boolean = false,
        // Set after a save that renamed the café — several screens (RoleSelectScreen, the OEM
        // keep-alive instructions) read the café name once rather than observing it reactively,
        // so a full app restart is the simple, reliable way to guarantee they all pick up the
        // new name instead of auditing every read site for live-update correctness.
        val restartRequired: Boolean = false,

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
                businessDayStartHour != savedSnapshot.businessDayStartHour ||
                defaultLangAdmin != savedSnapshot.defaultLangAdmin ||
                defaultLangOrdering != savedSnapshot.defaultLangOrdering ||
                defaultLangCustomer != savedSnapshot.defaultLangCustomer ||
                printLanguage != savedSnapshot.printLanguage
    }

    private val _uiState = MutableStateFlow(
        UiState(
            kitchenFontSize = printSettingsStore.getKitchenFontSize(),
            receiptLogo = printSettingsStore.getReceiptLogo(),
            escAsteriskMode = printSettingsStore.getEscAsteriskImageMode(),
            // Seed instantly from the Setup Wizard's stored café name — avoids a blank-field
            // flash before loadBranding()'s network call resolves, and gives Admin Settings a
            // real starting value even on the very first run, before the backend has ever
            // returned branding data. loadBranding() overwrites this with the backend's value
            // (the multi-device source of truth) as soon as that call succeeds.
            cafeName = appConfigStore.cafeName(),
            paymentQrHash = appConfigStore.paymentQrHash(),
            paymentQrPreview = com.razstudio.pos.ui.util.PaymentQrPipeline.loadFromInternal(context),
            savedSnapshot = Snapshot(cafeName = appConfigStore.cafeName())
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadInvite()
        loadBranding()
        loadPermissions()
        loadCafeLocation()
    }

    /** Kitchen-slip menu font size is device-local — persist and apply immediately. */
    fun updateKitchenFontSize(size: String) {
        printSettingsStore.setKitchenFontSize(size)
        _uiState.value = _uiState.value.copy(kitchenFontSize = size)
    }

    /** "Logo on receipt" is device-local — persist and apply immediately. */
    fun updateReceiptLogo(enabled: Boolean) {
        printSettingsStore.setReceiptLogo(enabled)
        _uiState.value = _uiState.value.copy(receiptLogo = enabled)
    }

    /** ESC * bit-image mode is device-local — persist and apply immediately. */
    fun updateEscAsteriskMode(enabled: Boolean) {
        printSettingsStore.setEscAsteriskImageMode(enabled)
        _uiState.value = _uiState.value.copy(escAsteriskMode = enabled)
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
    /**
     * Load the saved café GPS location from the backend so the Settings screen shows the persisted
     * coordinates/radius on open (and [isDirty] stays false afterwards). Best-effort: if the
     * location was never configured, the fields simply stay empty.
     */
    fun loadCafeLocation() {
        viewModelScope.launch {
            when (val result = apiClient.getCafeLocation()) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        latitude = result.data.latitude,
                        longitude = result.data.longitude,
                        radiusMeters = result.data.radiusMeters,
                        savedSnapshot = _uiState.value.savedSnapshot.copy(
                            latitude = result.data.latitude,
                            longitude = result.data.longitude,
                            radiusMeters = result.data.radiusMeters
                        )
                    )
                }
                else -> { /* not configured / unreachable — leave fields empty */ }
            }
        }
    }

    fun loadPermissions() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(permissionsLoading = true)
            when (val result = apiClient.getSettings()) {
                is ApiResult.Success -> {
                    val tz = result.data.timezone
                    // Mirror the backend timezone + print language into local Room so
                    // PrintService (kitchen slips / receipts) uses the café zone and the
                    // configured print language immediately.
                    run {
                        val existing = settingsDao.get() ?: SystemSettings()
                        settingsDao.upsert(existing.copy(
                            timezone = if (tz.isNotBlank()) tz else existing.timezone,
                            printLanguage = result.data.printLanguage
                        ))
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
                        defaultLangAdmin = result.data.defaultLangAdmin,
                        defaultLangOrdering = result.data.defaultLangOrdering,
                        defaultLangCustomer = result.data.defaultLangCustomer,
                        printLanguage = result.data.printLanguage,
                        permissionsLoading = false,
                        savedSnapshot = _uiState.value.savedSnapshot.copy(
                            staffCanSendKitchen = result.data.staffCanSendKitchen,
                            staffCanTakePayment = result.data.staffCanTakePayment,
                            timezone = tz,
                            holdSeconds = result.data.customerOrderHoldSeconds,
                            autoPrintToKitchen = result.data.customerOrderAutoPrint,
                            todaysSpecial = result.data.todaysSpecial,
                            reportEmail = result.data.reportEmail,
                            businessDayStartHour = result.data.businessDayStartHour,
                            defaultLangAdmin = result.data.defaultLangAdmin,
                            defaultLangOrdering = result.data.defaultLangOrdering,
                            defaultLangCustomer = result.data.defaultLangCustomer,
                            printLanguage = result.data.printLanguage
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

    /** Stage the café default UI language for the admin app (BM/EN/ZH/TA/TH); saved via [saveAll]. */
    fun updateDefaultLangAdmin(code: String) {
        _uiState.value = _uiState.value.copy(defaultLangAdmin = code)
    }

    /** Stage the café default UI language for ordering-staff devices; saved via [saveAll]. */
    fun updateDefaultLangOrdering(code: String) {
        _uiState.value = _uiState.value.copy(defaultLangOrdering = code)
    }

    /** Stage the café default UI language for the customer website; saved via [saveAll]. */
    fun updateDefaultLangCustomer(code: String) {
        _uiState.value = _uiState.value.copy(defaultLangCustomer = code)
    }

    /** Stage the printer language (BM/EN) for slips & receipts; saved via [saveAll]. */
    fun updatePrintLanguage(code: String) {
        _uiState.value = _uiState.value.copy(printLanguage = code)
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
    /**
     * Task 16.1 — accept an uploaded Payment QR (Requirements 14.1, 14.2, 14.3).
     *
     * The pipeline is what enforces the hard part: it rejects an image that carries no readable QR
     * rather than storing a picture nobody's bank app can scan, and it refuses to keep a re-encoded
     * copy whose payload no longer matches the original. Both failures surface here as a message the
     * admin can act on, and neither disturbs a previously working QR.
     */
    fun processPaymentQr(imageUri: Uri, mimeType: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(paymentQrBusy = true, paymentQrError = null)
            val outcome = withContext(Dispatchers.IO) {
                com.razstudio.pos.ui.util.PaymentQrPipeline.process(context, imageUri, mimeType)
            }
            _uiState.value = when (outcome) {
                is com.razstudio.pos.ui.util.PaymentQrPipeline.PipelineResult.Success -> {
                    // URL is a local file reference for now; LAN/Cloud distribution is task 16.2.
                    val url = outcome.qrResult.storedFile.toURI().toString()
                    withContext(Dispatchers.IO) {
                        com.razstudio.pos.ui.util.PaymentQrPipeline.saveAndPersist(
                            context, outcome.qrResult, url, appConfigStore
                        )
                    }
                    _uiState.value.copy(
                        paymentQrBusy = false,
                        paymentQrError = null,
                        paymentQrHash = appConfigStore.paymentQrHash(),
                        paymentQrPreview = withContext(Dispatchers.IO) {
                            com.razstudio.pos.ui.util.PaymentQrPipeline.loadFromInternal(context)
                        },
                    )
                }
                is com.razstudio.pos.ui.util.PaymentQrPipeline.PipelineResult.NoQrFound ->
                    _uiState.value.copy(paymentQrBusy = false, paymentQrError = outcome.message)
                is com.razstudio.pos.ui.util.PaymentQrPipeline.PipelineResult.Error ->
                    _uiState.value.copy(paymentQrBusy = false, paymentQrError = outcome.message)
            }
        }
    }

    /** Removes the Payment QR entirely, which hides the Show QR button (Requirement 14.5). */
    fun removePaymentQr() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(paymentQrBusy = true, paymentQrError = null)
            withContext(Dispatchers.IO) {
                com.razstudio.pos.ui.util.PaymentQrPipeline.deleteFromInternal(context)
                appConfigStore.setPaymentQrHash(null)
                appConfigStore.setPaymentQrUrl(null)
            }
            _uiState.value = _uiState.value.copy(
                paymentQrBusy = false,
                paymentQrPreview = null,
                paymentQrHash = null,
            )
        }
    }

    /** Clears only the inline error, so a failed upload can be retried without leaving a stale message. */
    fun clearPaymentQrError() {
        _uiState.value = _uiState.value.copy(paymentQrError = null)
    }

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
                // Persist the picked logo for LOCAL use (printed receipts, QR-card PDFs) only once
                // the backend confirms the save — matches this screen's "nothing takes effect
                // until Save" contract. base64 is null when the admin didn't change the logo this
                // save, so there's nothing new to persist.
                if (base64 != null) {
                    try {
                        LogoPipeline.saveJpegToInternal(context, android.util.Base64.decode(base64, android.util.Base64.NO_WRAP))
                    } catch (e: Exception) {
                        // Non-fatal: printed/generated output falls back to the bundled default logo
                    }
                }
                // Keep AppConfigStore's café name in sync with the backend (the multi-device
                // source of truth) using the NARROW setter — this touches only the café-name key,
                // never the Supabase connection the Setup Wizard persisted.
                appConfigStore.setCafeName(name)
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

    /** Admin declined the restart prompt — dismiss it without relaunching. */
    fun dismissRestartPrompt() {
        _uiState.value = _uiState.value.copy(restartRequired = false)
    }

    /**
     * Relaunch the app cleanly so every screen that read the café name once at composition
     * (RoleSelectScreen, OEM keep-alive instructions) re-initialises with the new value. Same
     * pattern as [com.razstudio.pos.ui.viewmodels.MenuPresetViewModel]'s restart after a menu
     * reload — a full process relaunch, not just an Activity recreate.
     */
    fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        Runtime.getRuntime().exit(0)
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
            val cafeNameChanged = state.cafeName != state.savedSnapshot.cafeName
            if (!anyError && (
                cafeNameChanged ||
                state.logoBase64 != state.savedSnapshot.logoBase64
            )) {
                if (!saveBranding()) anyError = true
                // A rename specifically (not just a logo change) needs a restart — several
                // screens read the café name once at composition rather than observing it
                // reactively, so a fresh process launch is the reliable way to guarantee every
                // one of them (RoleSelectScreen, the OEM keep-alive instructions) picks up the
                // new name. Only offered on success — a failed save has nothing to refresh.
                if (!anyError && cafeNameChanged) {
                    _uiState.value = _uiState.value.copy(restartRequired = true)
                }
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

            // Default UI languages — one PUT carrying whichever of the three changed.
            if (!anyError && (
                state.defaultLangAdmin != state.savedSnapshot.defaultLangAdmin ||
                state.defaultLangOrdering != state.savedSnapshot.defaultLangOrdering ||
                state.defaultLangCustomer != state.savedSnapshot.defaultLangCustomer
            )) {
                val body = JSONObject()
                if (state.defaultLangAdmin != state.savedSnapshot.defaultLangAdmin) {
                    body.put("defaultLangAdmin", state.defaultLangAdmin)
                }
                if (state.defaultLangOrdering != state.savedSnapshot.defaultLangOrdering) {
                    body.put("defaultLangOrdering", state.defaultLangOrdering)
                }
                if (state.defaultLangCustomer != state.savedSnapshot.defaultLangCustomer) {
                    body.put("defaultLangCustomer", state.defaultLangCustomer)
                }
                when (apiClient.putSettings(body)) {
                    is ApiResult.Success -> { /* persisted */ }
                    else -> {
                        anyError = true
                        _uiState.value = _uiState.value.copy(error = str().saveFailedGeneric)
                    }
                }
            }

            // Printer language — dirty when it differs. Push to backend AND mirror into Room
            // so PrintService uses it for the next slip/receipt right away.
            if (!anyError && state.printLanguage != state.savedSnapshot.printLanguage) {
                when (apiClient.putSettings(JSONObject().put("printLanguage", state.printLanguage))) {
                    is ApiResult.Success -> {
                        val existing = settingsDao.get() ?: SystemSettings()
                        settingsDao.upsert(existing.copy(printLanguage = state.printLanguage))
                    }
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
                        defaultLangAdmin = current.defaultLangAdmin,
                        defaultLangOrdering = current.defaultLangOrdering,
                        defaultLangCustomer = current.defaultLangCustomer,
                        printLanguage = current.printLanguage,
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
        loadCafeLocation()
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
