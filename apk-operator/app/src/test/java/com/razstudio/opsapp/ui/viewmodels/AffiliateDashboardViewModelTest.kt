package com.razstudio.opsapp.ui.viewmodels

import androidx.test.core.app.ApplicationProvider
import com.razstudio.opsapp.data.ApiResult
import com.razstudio.opsapp.data.promos.AffiliateCatalogFetcher
import com.razstudio.opsapp.data.promos.AffiliateProductDao
import com.razstudio.opsapp.data.promos.AffiliateProductEntity
import com.razstudio.opsapp.data.promos.AffiliateRepository
import com.razstudio.opsapp.data.promos.AffiliateSubIdProvider
import com.razstudio.opsapp.data.promos.CommissionInfo
import com.razstudio.opsapp.data.promos.ProductSortType
import com.razstudio.opsapp.data.promos.ShopeeAffiliateApi
import com.razstudio.opsapp.data.promos.ShopeeProductOffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [AffiliateDashboardViewModel]'s aggregation logic (task 6.4.4). No mocking library
 * is used anywhere in this project (see `CafesHomeViewModelTest`'s hand-rolled `FakeConnectedCafeDao`
 * and `OperatorApiClientTest`'s reflection-based checks) — this follows the same pattern with a fake
 * [AffiliateProductDao] rather than introducing MockK for one test class.
 *
 * Robolectric only because [AffiliateSubIdProvider] needs a real `Context` to construct (it's never
 * actually invoked by these tests — `syncNow()` is not called here).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AffiliateDashboardViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun entity(
        id: String,
        source: String,
        commissionRate: Double = 0.05,
        lastFetchedAt: String = "2026-01-01T00:00:00Z",
    ) = AffiliateProductEntity(
        id = id,
        itemId = id.hashCode().toLong(),
        productName = "Product $id",
        offerLink = "https://s.shopee.com.my/$id",
        imageUrl = "",
        price = 1000L,
        originalPrice = 1200L,
        commissionRate = commissionRate,
        commissionXtra = null,
        shopName = "Shop",
        isOfficialShop = false,
        salesCount = 0L,
        rating = 0.0,
        subId = "operator-catalog",
        validationStatus = "UNCHECKED",
        lastFetchedAt = lastFetchedAt,
        source = source,
    )

    /** Never-called stand-in — these tests only read `state`, never trigger `syncNow()`. */
    private class UncalledShopeeAffiliateApi : ShopeeAffiliateApi {
        override suspend fun searchProducts(
            keyword: String, limit: Int, sortBy: ProductSortType, minDiscount: Int, country: String,
        ): ApiResult<List<ShopeeProductOffer>> = error("not expected to be called in this test")
        override suspend fun generateShortLink(productUrl: String, subIds: List<String>): ApiResult<String> =
            error("not expected to be called in this test")
        override suspend fun getProductDetails(itemId: Long): ApiResult<ShopeeProductOffer> =
            error("not expected to be called in this test")
        override suspend fun getCommissionInfo(itemIds: List<Long>): ApiResult<List<CommissionInfo>> =
            error("not expected to be called in this test")
    }

    private class FakeAffiliateProductDao(initial: List<AffiliateProductEntity>) : AffiliateProductDao {
        val rows = MutableStateFlow(initial)
        override suspend fun insertAll(products: List<AffiliateProductEntity>) { rows.value = products }
        override fun getAll(): Flow<List<AffiliateProductEntity>> = rows
        override suspend fun deleteAllExceptSource(keepSource: String) {
            rows.value = rows.value.filter { it.source == keepSource }
        }
        override suspend fun incrementImpressions(id: String) {}
        override suspend fun incrementClicks(id: String) {}
    }

    private fun buildViewModel(entities: List<AffiliateProductEntity>): AffiliateDashboardViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = AffiliateRepository(
            api = UncalledShopeeAffiliateApi(),
            dao = FakeAffiliateProductDao(entities),
            catalogFetcher = AffiliateCatalogFetcher(),
            subIdProvider = AffiliateSubIdProvider(context),
        )
        return AffiliateDashboardViewModel(repository)
    }

    @Test
    fun `breakdown sums to total for a mix of sources`() = runTest(testDispatcher) {
        val entities = listOf(
            entity("a", "SHOPEE_API"),
            entity("b", "SHOPEE_API"),
            entity("c", "GITHUB_FALLBACK"),
        )
        val viewModel = buildViewModel(entities)
        val collectJob = backgroundScope.launch(testDispatcher) { viewModel.state.collect {} }
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(3, state.totalCount)
        assertEquals(2, state.shopeeApiCount)
        assertEquals(1, state.githubFallbackCount)
        assertEquals(state.totalCount, state.shopeeApiCount + state.githubFallbackCount)
    }

    @Test
    fun `lastSyncedAt is the max lastFetchedAt across all rows`() = runTest(testDispatcher) {
        val entities = listOf(
            entity("a", "SHOPEE_API", lastFetchedAt = "2026-01-01T00:00:00Z"),
            entity("b", "SHOPEE_API", lastFetchedAt = "2026-03-01T00:00:00Z"),
            entity("c", "GITHUB_FALLBACK", lastFetchedAt = "2026-02-01T00:00:00Z"),
        )
        val viewModel = buildViewModel(entities)
        val collectJob = backgroundScope.launch(testDispatcher) { viewModel.state.collect {} }
        advanceUntilIdle()

        assertEquals("2026-03-01T00:00:00Z", viewModel.state.value.lastSyncedAt)
    }

    @Test
    fun `lastSyncedAt is null when there are no cached rows`() = runTest(testDispatcher) {
        val viewModel = buildViewModel(emptyList())
        val collectJob = backgroundScope.launch(testDispatcher) { viewModel.state.collect {} }
        advanceUntilIdle()

        assertNull(viewModel.state.value.lastSyncedAt)
        assertEquals(0, viewModel.state.value.totalCount)
    }

    @Test
    fun `topProducts never exceeds its cap regardless of how many rows are cached`() = runTest(testDispatcher) {
        val entities = (1..25).map { entity(id = "p$it", source = "SHOPEE_API") }
        val viewModel = buildViewModel(entities)
        val collectJob = backgroundScope.launch(testDispatcher) { viewModel.state.collect {} }
        advanceUntilIdle()

        assertEquals(25, viewModel.state.value.totalCount)
        assertTrue(viewModel.state.value.topProducts.size <= 10)
    }

    @Test
    fun `isStale is read from the repository, never computed independently`() = runTest(testDispatcher) {
        // A freshly constructed AffiliateRepository has never synced, so isCacheStale() is true —
        // this is the actual value the dashboard must agree with (Property 6), not a fixed default.
        val viewModel = buildViewModel(listOf(entity("a", "SHOPEE_API")))
        val collectJob = backgroundScope.launch(testDispatcher) { viewModel.state.collect {} }
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isStale)
    }
}
