package com.razstudio.opsapp.ui.viewmodels

import com.razstudio.opsapp.data.api.AccessRevocationManager
import com.razstudio.opsapp.data.local.ConnectedCafeDao
import com.razstudio.opsapp.data.local.ConnectedCafeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CafeProfileViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeDao: FakeConnectedCafeDao
    private lateinit var revocationManager: AccessRevocationManager
    private lateinit var viewModel: CafeProfileViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeDao = FakeConnectedCafeDao()
        revocationManager = AccessRevocationManager()
        viewModel = CafeProfileViewModel(fakeDao, revocationManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadCafe loads entity and touches lastConnectedAt`() = runTest(testDispatcher) {
        val cafe = makeCafe("cafe-1", "Cafe One")
        fakeDao.cafes.value = listOf(cafe)

        viewModel.loadCafe("cafe-1")
        advanceUntilIdle()

        assertEquals(cafe, viewModel.cafe.value)
        assertNotNull(viewModel.apiClient.value)
        assertEquals("cafe-1", fakeDao.lastTouchedId)
        assertNotNull(fakeDao.lastTouchedTimestamp)
    }

    @Test
    fun `loadCafe returns null for unknown id`() = runTest(testDispatcher) {
        fakeDao.cafes.value = emptyList()

        viewModel.loadCafe("missing")
        advanceUntilIdle()

        assertNull(viewModel.cafe.value)
        assertNull(viewModel.apiClient.value)
    }

    @Test
    fun `selectTab updates selected tab`() {
        assertEquals(ShellTab.PROFILE, viewModel.selectedTab.value)

        viewModel.selectTab(ShellTab.MENU)

        assertEquals(ShellTab.MENU, viewModel.selectedTab.value)
    }

    @Test
    fun `disconnect deletes cafe from dao`() = runTest(testDispatcher) {
        val cafe = makeCafe("cafe-1", "Cafe One")
        fakeDao.cafes.value = listOf(cafe)
        viewModel.loadCafe("cafe-1")
        advanceUntilIdle()

        viewModel.disconnect()
        advanceUntilIdle()

        assertEquals("cafe-1", fakeDao.lastDeletedId)
        assertTrue(fakeDao.cafes.value.isEmpty())
    }

    @Test
    fun `revoked becomes true when revocation event matches loaded cafe`() = runTest(testDispatcher) {
        val cafe = makeCafe("cafe-1", "Cafe One")
        fakeDao.cafes.value = listOf(cafe)
        viewModel.loadCafe("cafe-1")
        advanceUntilIdle()

        revocationManager.notifyRevoked("cafe-1", "Cafe One")
        advanceUntilIdle()

        assertTrue(viewModel.revoked.value)
    }

    @Test
    fun `revoked stays false when revocation event is for different cafe`() = runTest(testDispatcher) {
        val cafe = makeCafe("cafe-1", "Cafe One")
        fakeDao.cafes.value = listOf(cafe)
        viewModel.loadCafe("cafe-1")
        advanceUntilIdle()

        revocationManager.notifyRevoked("cafe-2", "Cafe Two")
        advanceUntilIdle()

        assertEquals(false, viewModel.revoked.value)
    }

    private fun makeCafe(id: String, name: String) = ConnectedCafeEntity(
        id = id,
        cafeName = name,
        cafeSlug = name.lowercase().replace(" ", "-"),
        supabaseUrl = "https://$id.supabase.co",
        supabaseAnonKey = "anon-key-$id",
        sessionToken = "token-$id",
        connectedAt = "2024-01-01T00:00:00Z",
        lastConnectedAt = "2024-01-02T00:00:00Z",
    )
}

private class FakeConnectedCafeDao : ConnectedCafeDao {

    val cafes = MutableStateFlow<List<ConnectedCafeEntity>>(emptyList())
    var lastDeletedId: String? = null
    var lastTouchedId: String? = null
    var lastTouchedTimestamp: String? = null

    override suspend fun insert(cafe: ConnectedCafeEntity) {
        cafes.value = cafes.value + cafe
    }

    override fun listAll(): Flow<List<ConnectedCafeEntity>> = cafes

    override suspend fun deleteById(cafeId: String) {
        lastDeletedId = cafeId
        cafes.value = cafes.value.filter { it.id != cafeId }
    }

    override suspend fun touchLastConnected(cafeId: String, timestamp: String) {
        lastTouchedId = cafeId
        lastTouchedTimestamp = timestamp
        cafes.value = cafes.value.map {
            if (it.id == cafeId) it.copy(lastConnectedAt = timestamp) else it
        }
    }
}
