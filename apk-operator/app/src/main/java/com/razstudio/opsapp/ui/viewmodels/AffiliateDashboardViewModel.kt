package com.razstudio.opsapp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.opsapp.data.promos.AffiliateProductEntity
import com.razstudio.opsapp.data.promos.AffiliateRepository
import com.razstudio.opsapp.data.promos.SyncResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Affiliate Dashboard (Requirement 7.4–7.6) — a thin stats projection over
 * [AffiliateRepository.getProducts()]. No new Room queries: the DAO already returns everything
 * `ORDER BY commissionRate DESC`, so [topProducts] is just that same list, capped.
 *
 * [DashboardState.isStale] is read from [AffiliateRepository.isCacheStale] rather than recomputed
 * from [DashboardState.lastSyncedAt] here — one source of truth for "is this stale," matching
 * design.md's Property 6.
 */
@HiltViewModel
class AffiliateDashboardViewModel @Inject constructor(
    private val repository: AffiliateRepository,
) : ViewModel() {

    companion object {
        private const val TOP_PRODUCTS_CAP = 10
        private const val SOURCE_SHOPEE_API = "SHOPEE_API"
        private const val SOURCE_GITHUB_FALLBACK = "GITHUB_FALLBACK"
    }

    data class DashboardState(
        val totalCount: Int = 0,
        val shopeeApiCount: Int = 0,
        val githubFallbackCount: Int = 0,
        val lastSyncedAt: String? = null,
        val isStale: Boolean = true,
        val topProducts: List<AffiliateProductEntity> = emptyList(),
        val syncing: Boolean = false,
        val syncMessage: String? = null,
    )

    private val _syncing = MutableStateFlow(false)
    private val _syncMessage = MutableStateFlow<String?>(null)

    val state: StateFlow<DashboardState> = repository.getProducts()
        .combine(_syncing) { entities, syncing -> entities to syncing }
        .combine(_syncMessage) { (entities, syncing), message ->
            DashboardState(
                totalCount = entities.size,
                shopeeApiCount = entities.count { it.source == SOURCE_SHOPEE_API },
                githubFallbackCount = entities.count { it.source == SOURCE_GITHUB_FALLBACK },
                lastSyncedAt = entities.maxByOrNull { it.lastFetchedAt }?.lastFetchedAt,
                isStale = repository.isCacheStale(),
                topProducts = entities.take(TOP_PRODUCTS_CAP),
                syncing = syncing,
                syncMessage = message,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    /** Mirrors AffiliateDebugViewModel.triggerSync() — same repository call, dashboard-scoped state. */
    fun syncNow() {
        viewModelScope.launch {
            _syncing.value = true
            _syncMessage.value = null
            when (val result = repository.syncNow()) {
                is SyncResult.Success -> _syncMessage.value = "Synced ${result.productCount} product(s)"
                is SyncResult.Failure -> _syncMessage.value = "Sync failed: ${result.message}"
            }
            _syncing.value = false
        }
    }
}
