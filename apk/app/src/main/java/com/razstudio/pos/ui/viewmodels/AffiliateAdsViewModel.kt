package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.local.LocalPrefs
import com.razstudio.pos.data.promos.AffiliateProduct
import com.razstudio.pos.data.promos.AffiliateProductFilter
import com.razstudio.pos.data.promos.AffiliateRepository
import com.razstudio.pos.data.promos.AffiliateSubIdProvider
import com.razstudio.pos.data.promos.AffiliateSyncScheduler
import com.razstudio.pos.data.promos.ShopeeProductOffer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * ViewModel powering the affiliate product tile row on the table grid and dashboard.
 *
 * Collects cached products from [AffiliateRepository], validates URLs via
 * [AffiliateProductFilter.validate], and applies offset-based rotation windowing so
 * all products get fair exposure across successive displays.
 *
 * Exposes an empty list on failure or while loading — the UI renders nothing in that case,
 * keeping the affiliate module invisible to operators when there are no products to show.
 */
@HiltViewModel
class AffiliateAdsViewModel @Inject constructor(
    private val repository: AffiliateRepository,
    private val syncScheduler: AffiliateSyncScheduler,
    private val subIdProvider: AffiliateSubIdProvider,
    private val localPrefs: LocalPrefs,
) : ViewModel() {

    private companion object {
        /** Table grid, dashboard, and ambient screen all share this one product feed. */
        const val SURFACE = "tableview"
    }

    /**
     * Current rotation offset — incremented every 30s and each time [nextRotation] is called, and
     * persisted to [LocalPrefs.affiliateRotationOffset] on every change.
     *
     * Round-robin, not random: this used to start at `Random.nextInt()` on every ViewModel
     * creation, so a device reboot or the app being killed and reopened — routine on a POS tablet —
     * jumped to a fresh random point instead of continuing the cycle, and the table grid, dashboard
     * and ambient screen (three separate ViewModel instances) each seeded independently. Reading
     * the persisted cursor here means every screen starts from wherever the cycle last left off,
     * and every advance below writes it back so the next screen — or the next cold start — picks up
     * from there. The very first rotation ever on a device still needs a starting point with no
     * "previous" to continue from; that one case falls back to a random seed, matching the
     * website's own first-ever-visit behaviour in `website/src/lib/shopee.ts`.
     * [AffiliateProductFilter.rotate] already wraps any `Int` (via `.mod(size)`), negative or
     * positive, so this needs no extra bounds logic.
     */
    private val rotationOffset = MutableStateFlow(
        localPrefs.affiliateRotationOffset ?: Random.nextInt().also {
            // Persist the seed itself, not just future advances — otherwise a ViewModel that
            // never lives long enough to auto-rotate or have nextRotation() called (a quick
            // screen visit) leaves nothing written, and the next instance re-seeds randomly
            // again, never actually starting a cycle.
            localPrefs.affiliateRotationOffset = it
        }
    )

    /**
     * Display-ready products for the UI, validated and windowed.
     *
     * The flow combines Room-backed product entities with the rotation offset:
     * 1. Entities are mapped back to [ShopeeProductOffer] for filter compatibility.
     * 2. [AffiliateProductFilter.validate] ensures only HTTPS links pass through.
     * 3. [AffiliateProductFilter.rotate] selects a window of 5 products at the current offset.
     *
     * Emits an empty list when Room has no data, filtering removes everything, or on errors.
     */
    val products: StateFlow<List<AffiliateProduct>> = repository.getProducts()
        .combine(rotationOffset) { entities, offset ->
            val subId = subIdProvider.forSurface(SURFACE)
            val offers = entities.map { entity ->
                ShopeeProductOffer(
                    id = entity.id,
                    itemId = entity.itemId,
                    productName = entity.productName,
                    offerLink = entity.offerLink,
                    imageUrl = entity.imageUrl,
                    price = entity.price,
                    originalPrice = entity.originalPrice,
                    commissionRate = entity.commissionRate,
                    commissionXtra = entity.commissionXtra,
                    shopName = entity.shopName,
                    isOfficialShop = entity.isOfficialShop,
                    salesCount = entity.salesCount,
                    rating = entity.rating,
                )
            }
            val validated = AffiliateProductFilter.validate(offers, subId)
            AffiliateProductFilter.rotate(validated, windowSize = 2, offset = offset)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        syncScheduler.schedulePeriodicSync()
        if (repository.isCacheStale()) {
            viewModelScope.launch { repository.syncNow() }
        }
        // Auto-rotate products every 30 seconds so the display doesn't go stale
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000L)
                advanceRotation()
            }
        }
    }

    /** Advance rotation to show the next set of products. */
    fun nextRotation() {
        advanceRotation()
    }

    /** Bumps the cursor and writes it back so the cycle survives past this ViewModel. */
    private fun advanceRotation() {
        rotationOffset.value++
        localPrefs.affiliateRotationOffset = rotationOffset.value
    }

    /** Record that [product]'s tile was shown. Called once per product each time it composes. */
    fun onImpression(product: AffiliateProduct) {
        viewModelScope.launch { repository.recordImpression(product.id) }
    }

    /** Record that [product]'s tile was tapped. Called from every surface's click handler. */
    fun onClick(product: AffiliateProduct) {
        viewModelScope.launch { repository.recordClick(product.id) }
    }
}
