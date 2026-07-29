package com.warungtomyam.pos.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warungtomyam.pos.data.ApiClient
import com.warungtomyam.pos.data.ApiResult
import com.warungtomyam.pos.data.MenuItemDto
import com.warungtomyam.pos.util.BusinessDay
import com.warungtomyam.pos.data.local.MenuCategoryStore
import com.warungtomyam.pos.data.local.MenuDao
import com.warungtomyam.pos.data.local.MenuItem
import com.warungtomyam.pos.data.local.SessionPrefs
import com.warungtomyam.pos.realtime.RealtimeService
import com.warungtomyam.pos.ui.i18n.LanguageManager
import com.warungtomyam.pos.ui.i18n.uiStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * ViewModel managing admin session lifecycle:
 * - Opening session (CAFE_OPEN)
 * - Sign Out (CLOSE, keep token)
 * - Sign Out with Closing (aggregate push, CLOSE, email report)
 * - Daily Availability popup logic
 */
@HiltViewModel
class AdminSessionViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val menuDao: MenuDao,
    private val categoryStore: MenuCategoryStore,
    private val sessionPrefs: SessionPrefs,
    @ApplicationContext private val context: Context,
    private val languageManager: LanguageManager,
    private val secureStorage: com.warungtomyam.pos.data.SecureStorage
) : ViewModel() {

    private fun str() = uiStrings(languageManager.language.value)

    // This device's admin role, observable so the UI (greyed printer controls) reacts when a
    // promote/demote changes it. The print gating itself reads SecureStorage live per-print.
    private val _currentRole = MutableStateFlow(secureStorage.getRole())
    val currentRole: StateFlow<com.warungtomyam.pos.data.SecureStorage.Role?> = _currentRole.asStateFlow()

    /** True on a secondary-admin device — used to grey out printer controls it can't use. */
    val isSecondaryAdmin: Boolean
        get() = _currentRole.value == com.warungtomyam.pos.data.SecureStorage.Role.ADMIN_SECONDARY

    /**
     * Re-check this device's role from the server (it may have been promoted to Main or demoted
     * to Secondary from another device). Updates the stored role so printing + the greyed printer
     * controls follow. Best-effort; safe to call on every admin-home resume.
     */
    fun refreshRole() {
        viewModelScope.launch {
            val deviceId = secureStorage.getDeviceId()
            if (deviceId.isBlank()) return@launch
            when (val result = apiClient.pollDeviceStatus(deviceId)) {
                is ApiResult.Success -> {
                    val newRole = when (result.data.role) {
                        "ADMIN" -> com.warungtomyam.pos.data.SecureStorage.Role.ADMIN
                        "ADMIN_SECONDARY" -> com.warungtomyam.pos.data.SecureStorage.Role.ADMIN_SECONDARY
                        else -> null
                    }
                    val current = secureStorage.getRole()
                    // Only reconcile between the two admin roles (never touch ORDERING/null here).
                    if (newRole != null && newRole != current &&
                        (current == com.warungtomyam.pos.data.SecureStorage.Role.ADMIN ||
                            current == com.warungtomyam.pos.data.SecureStorage.Role.ADMIN_SECONDARY)
                    ) {
                        secureStorage.setRole(newRole)
                        _currentRole.value = newRole
                    }
                }
                else -> { /* best-effort */ }
            }
        }
    }

    data class UiState(
        val isSessionOpen: Boolean = false,
        val isLoading: Boolean = false,
        val showDailyPopup: Boolean = false,
        val dailyItems: List<MenuItem> = emptyList(),
        val error: String? = null,
        val closingState: ClosingState = ClosingState.Idle,
        val navigateToLock: Boolean = false,
        /** True when a 401 / missing token means the admin must re-handshake. */
        val navigateToReconnect: Boolean = false
    )

    enum class ClosingState {
        Idle,
        ComputingAggregate,
        SendingAggregate,
        ClosingSession,
        Done
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Open admin session — POST OPEN event.
     * Also fetches menu from backend and syncs to Room for daily popup.
     */
    fun openSession() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // Post OPEN to backend
            when (val result = apiClient.postSession("OPEN")) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(isSessionOpen = true)
                    // Capture "is this the first open of the day?" BEFORE marking today opened —
                    // otherwise checkDailyPopup()'s own isNewDay() check would always be false
                    // (we'd have just stamped today), so the daily popup could never appear.
                    val firstOpenToday = sessionPrefs.isNewDay()
                    sessionPrefs.markTodayOpened()

                    // Sync menu from backend to Room
                    syncMenuFromBackend()

                    // Check if daily popup is needed
                    checkDailyPopup(firstOpenToday)
                }
                is ApiResult.Error -> {
                    // UNAUTHORIZED or NO_TOKEN = expired/revoked token → must re-handshake
                    if (result.code == "UNAUTHORIZED" || result.code == "NO_TOKEN") {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            navigateToReconnect = true
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            error = str().failedToOpenSession.format(result.message),
                            isLoading = false
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.value = _uiState.value.copy(
                        error = str().msgNetworkError.format(result.message),
                        isLoading = false
                    )
                }
            }

            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /**
     * Simple sign-out: POST CLOSE → navigate to the lock screen. The token is kept in
     * SecureStorage AND the RealtimeService keeps running — the café is only "locked", not
     * closed, so the background listener must stay alive (persistent status-bar notification,
     * live new-order sound/print). Only [signOutWithClosing] fully tears the service down.
     */
    fun signOut() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // Blocking CLOSE delivery. NOTE: RealtimeService is intentionally NOT stopped here.
            val closeResult = withContext(Dispatchers.IO) {
                apiClient.postSession("CLOSE")
            }

            // Regardless of the CLOSE result, go to the lock screen (café must lock); the
            // background service stays running so orders keep arriving while locked.
            when (closeResult) {
                is ApiResult.Success,
                is ApiResult.Error,
                is ApiResult.NetworkError -> {
                    _uiState.value = _uiState.value.copy(
                        isSessionOpen = false,
                        isLoading = false,
                        navigateToLock = true
                    )
                }
            }
        }
    }

    /**
     * Sign Out with Closing:
     * 1. Compute today's aggregate from Room
     * 2. POST /api/aggregates
     * 3. POST /api/sessions { event: CLOSE, reason, closing: true }
     * 4. Stop services, navigate to lock
     */
    fun signOutWithClosing(reason: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                closingState = ClosingState.ComputingAggregate
            )

            // Step 1: Compute aggregate (placeholder — full implementation in Task 17
            // when Order entities exist in Room; for now send a minimal aggregate).
            // Tag it with the BUSINESS DAY (opening-day date) so a post-midnight close still
            // reports the opening day — and so it matches reports-closing's server-side lookup,
            // which derives the same business day from the same settings.
            val (bizZone, bizStartHour) = when (val r = apiClient.getSettings()) {
                is ApiResult.Success -> BusinessDay.zoneOf(r.data.timezone) to r.data.businessDayStartHour
                else -> BusinessDay.zoneOf("") to 15
            }
            val today = BusinessDay.current(bizZone, bizStartHour.coerceIn(0, 23))
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
            val aggregate = JSONObject().apply {
                put("totalOrders", 0)
                put("totalRevenue", 0.0)
                put("avgOrderValue", 0.0)
                put("paymentSplit", JSONObject().apply {
                    put("cash", JSONObject().apply {
                        put("count", 0)
                        put("amount", 0.0)
                    })
                    put("qr", JSONObject().apply {
                        put("count", 0)
                        put("amount", 0.0)
                    })
                })
                put("cancelledCount", 0)
                put("cancelledValue", 0.0)
                put("topItemsPerCategory", JSONObject())
            }

            // Step 2: Post aggregate
            _uiState.value = _uiState.value.copy(closingState = ClosingState.SendingAggregate)
            val aggResult = withContext(Dispatchers.IO) {
                apiClient.postAggregates(today, aggregate)
            }

            if (aggResult is ApiResult.Error || aggResult is ApiResult.NetworkError) {
                // Log but continue — closing the café is more important
            }

            // Step 3: Blocking CLOSE delivery with reason and closing flag
            _uiState.value = _uiState.value.copy(closingState = ClosingState.ClosingSession)
            withContext(Dispatchers.IO) {
                apiClient.postSession("CLOSE", reason = reason, closing = true)
                // "Today's special" auto-expires at closing — clear it so it doesn't carry
                // into the next service day.
                runCatching {
                    apiClient.putSettings(org.json.JSONObject().put("todaysSpecial", ""))
                }
            }

            // Step 4: Stop services and navigate
            RealtimeService.stop(context)
            _uiState.value = _uiState.value.copy(
                isSessionOpen = false,
                isLoading = false,
                closingState = ClosingState.Done,
                navigateToLock = true
            )
        }
    }

    /**
     * Check if the daily availability popup should be shown.
     * Shown on first OPEN of the day if any askMeDaily items exist.
     */
    private suspend fun checkDailyPopup(isNewDay: Boolean) {
        val askMeDailyItems = menuDao.getAskMeDaily()
        if (askMeDailyItems.isNotEmpty() && isNewDay) {
            _uiState.value = _uiState.value.copy(
                showDailyPopup = true,
                dailyItems = askMeDailyItems
            )
        }
    }

    /**
     * Update a menu item's availability (and optionally price) in Room,
     * then push full menu to backend via PUT /api/menu.
     */
    fun updateItemAvailability(itemId: String, available: Boolean, price: Double? = null) {
        viewModelScope.launch {
            if (price != null) {
                menuDao.updateAvailability(itemId, available, price)
            } else {
                menuDao.updateAvailabilityOnly(itemId, available)
            }
        }
    }

    /**
     * Confirm daily availability selections: push all menu items to backend.
     */
    fun confirmDailyAvailability() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // Build the full menu snapshot from Room and push to backend (items + categories,
            // incl. code + marketPrice) — reuse the shared serialization so the snapshot
            // round-trips consistently with MenuViewModel's push.
            val allItems = menuDao.getAll()
            val menuArray = JSONArray()
            for (item in allItems) {
                menuArray.put(MenuViewModel.menuItemToJson(item))
            }
            val categoriesArray = MenuViewModel.buildCategoriesJson(categoryStore.get(), allItems, categoryStore.getTranslations())

            apiClient.putMenu(menuArray, categoriesArray)

            sessionPrefs.markTodayOpened()
            _uiState.value = _uiState.value.copy(
                showDailyPopup = false,
                dailyItems = emptyList(),
                isLoading = false
            )
        }
    }

    /** Dismiss the daily popup without changes. */
    fun dismissDailyPopup() {
        _uiState.value = _uiState.value.copy(
            showDailyPopup = false,
            dailyItems = emptyList()
        )
        sessionPrefs.markTodayOpened()
    }

    /** Reset navigation flag after consuming it. */
    fun onNavigatedToLock() {
        _uiState.value = _uiState.value.copy(navigateToLock = false)
    }

    /** Reset reconnect navigation flag after consuming it. */
    fun onNavigatedToReconnect() {
        _uiState.value = _uiState.value.copy(navigateToReconnect = false)
    }

    /** Dismiss error message. */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** Fetch menu from backend and sync to local Room database. */
    private suspend fun syncMenuFromBackend() {
        when (val result = apiClient.getMenu()) {
            is ApiResult.Success -> {
                if (result.data.configured) {
                    val entities = result.data.items.map { it.toEntity() }
                    menuDao.deleteAll()
                    menuDao.upsertAll(entities)
                    // Persist the category order from the snapshot (fall back to distinct
                    // item categories in stable order if the snapshot omitted them).
                    val categoryNames = result.data.categories.map { it.name }
                        .ifEmpty { entities.map { it.category }.filter { it.isNotBlank() }.distinct() }
                    if (categoryNames.isNotEmpty()) categoryStore.set(categoryNames)
                    // Preserve per-language category labels so a later menu save re-emits them
                    // instead of wiping the customer-web translations.
                    val translations = result.data.categories
                        .filter { it.nameI18n.isNotEmpty() }
                        .associate { it.name to it.nameI18n }
                    if (translations.isNotEmpty()) categoryStore.setTranslations(translations)
                }
            }
            is ApiResult.Error -> { /* silent — menu sync is best-effort */ }
            is ApiResult.NetworkError -> { /* silent */ }
        }
    }

    private fun MenuItemDto.toEntity() = MenuItem(
        id = id,
        category = category,
        extraCategories = extraCategories,
        code = code,
        price = price,
        marketPrice = marketPrice,
        available = available,
        askMeDaily = askMeDaily,
        imageUrl = imageUrl,
        nameEn = nameEn,
        nameBm = nameBm,
        nameZh = nameZh,
        nameTa = nameTa,
        nameTh = nameTh,
        doNotTranslate = doNotTranslate,
        hasVariablePrice = hasVariablePrice,
        variablePriceDailyPrompt = variablePriceDailyPrompt,
        priceOption1 = priceOption1,
        priceOption2 = priceOption2,
        priceOption3 = priceOption3
    )
}
