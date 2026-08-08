package com.razstudio.opsapp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.opsapp.data.ApiResult
import com.razstudio.opsapp.data.promos.AffiliateProductDao
import com.razstudio.opsapp.data.promos.AffiliateRepository
import com.razstudio.opsapp.data.promos.AffiliateSyncScheduler
import com.razstudio.opsapp.data.promos.LinkGenerator
import com.razstudio.opsapp.data.promos.LinkValidationResult
import com.razstudio.opsapp.data.promos.ShopeeAffiliateApi
import com.razstudio.opsapp.data.promos.ShopeeAuthSigner
import com.razstudio.opsapp.data.promos.ShopeeProductOffer
import com.razstudio.opsapp.data.promos.SyncResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ViewModel for the affiliate catalog debug/inspection screen.
 *
 * Ported from `apk/app`'s `com.razstudio.pos.ui.viewmodels.AffiliateDebugViewModel`. There it is
 * reachable only in debug builds; here it is a permanent, first-class Operator APK screen
 * (Requirement 7) — support staff, not café operators, are the audience.
 */
@HiltViewModel
class AffiliateDebugViewModel @Inject constructor(
    private val api: ShopeeAffiliateApi,
    private val repository: AffiliateRepository,
    private val syncScheduler: AffiliateSyncScheduler,
    private val dao: AffiliateProductDao,
) : ViewModel() {

    // ── API Query Tester ─────────────────────────────────────────────────────────

    private val _searchResults = MutableStateFlow<List<ShopeeProductOffer>>(emptyList())
    val searchResults: StateFlow<List<ShopeeProductOffer>> = _searchResults.asStateFlow()

    private val _searchStatus = MutableStateFlow("")
    val searchStatus: StateFlow<String> = _searchStatus.asStateFlow()

    fun searchProducts(keyword: String) {
        viewModelScope.launch {
            _searchStatus.value = "Searching \"$keyword\"…"
            _searchResults.value = emptyList()
            when (val result = api.searchProducts(keyword = keyword)) {
                is ApiResult.Success -> {
                    _searchResults.value = result.data
                    _searchStatus.value = "Found ${result.data.size} product(s)"
                }
                is ApiResult.Error -> {
                    _searchStatus.value = "Error [${result.code}]: ${result.message}"
                }
                is ApiResult.NetworkError -> {
                    _searchStatus.value = "Network error: ${result.message}"
                }
            }
        }
    }

    // ── Link Generator ───────────────────────────────────────────────────────────

    private val _generatedLink = MutableStateFlow("")
    val generatedLink: StateFlow<String> = _generatedLink.asStateFlow()

    private val _validationResult = MutableStateFlow<LinkValidationResult?>(null)
    val validationResult: StateFlow<LinkValidationResult?> = _validationResult.asStateFlow()

    fun generateLink(url: String) {
        val generated = LinkGenerator.generate(
            baseUrl = url,
            subId = "debug-panel",
        )
        _generatedLink.value = generated
        _validationResult.value = LinkGenerator.validate(generated)
    }

    // ── Cache Inspector ──────────────────────────────────────────────────────────

    private val _cacheInfo = MutableStateFlow("Tap refresh to load cache info")
    val cacheInfo: StateFlow<String> = _cacheInfo.asStateFlow()

    fun refreshCacheInfo() {
        viewModelScope.launch {
            val products = repository.getProducts().first()
            val stale = repository.isCacheStale()
            val lastFetched = products.maxByOrNull { it.lastFetchedAt }?.lastFetchedAt ?: "never"
            _cacheInfo.value = buildString {
                appendLine("Product count: ${products.size}")
                appendLine("Last sync: $lastFetched")
                appendLine("Cache stale: $stale")
            }
        }
    }

    // ── Sync Trigger ─────────────────────────────────────────────────────────────

    private val _syncStatus = MutableStateFlow("")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    fun triggerSync() {
        viewModelScope.launch {
            _syncStatus.value = "Syncing…"
            when (val result = repository.syncNow()) {
                is SyncResult.Success -> {
                    _syncStatus.value = "Sync OK — ${result.productCount} product(s) cached"
                }
                is SyncResult.Failure -> {
                    _syncStatus.value = "Sync failed: ${result.message}"
                }
            }
        }
    }
}
