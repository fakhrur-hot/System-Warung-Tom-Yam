package com.razstudio.opsapp.ui.home

import com.razstudio.opsapp.data.local.ConnectedCafeDao
import com.razstudio.opsapp.data.local.ConnectedCafeEntity
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CafesHomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeDao: FakeConnectedCafeDao
    private lateinit var viewModel: CafesHomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeDao = FakeConnectedCafeDao()
        viewModel = CafesHomeViewModel(fakeDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `connectedCafes emits list from dao`() = runTest(testDispatcher) {
        // Start collecting to activate WhileSubscribed
        val collectJob = backgroundScope.launch(testDispatcher) {
            viewModel.connectedCafes.collect {}
        }

        val cafe = makeCafe("cafe-1", "Cafe One")
        fakeDao.cafes.value = listOf(cafe)
        advanceUntilIdle()

        assertEquals(listOf(cafe), viewModel.connectedCafes.value)
    }

    @Test
    fun `connectedCafes starts with empty list`() = runTest(testDispatcher) {
        val collectJob = backgroundScope.launch(testDispatcher) {
            viewModel.connectedCafes.collect {}
        }
        advanceUntilIdle()

        assertTrue(viewModel.connectedCafes.value.isEmpty())
    }

    @Test
    fun `disconnect calls deleteById on dao`() = runTest(testDispatcher) {
        val collectJob = backgroundScope.launch(testDispatcher) {
            viewModel.connectedCafes.collect {}
        }

        val cafe = makeCafe("cafe-1", "Cafe One")
        fakeDao.cafes.value = listOf(cafe)
        advanceUntilIdle()

        viewModel.disconnect("cafe-1")
        advanceUntilIdle()

        assertEquals("cafe-1", fakeDao.lastDeletedId)
    }

    @Test
    fun `disconnect removes cafe from flow`() = runTest(testDispatcher) {
        val collectJob = backgroundScope.launch(testDispatcher) {
            viewModel.connectedCafes.collect {}
        }

        val cafe1 = makeCafe("cafe-1", "Cafe One")
        val cafe2 = makeCafe("cafe-2", "Cafe Two")
        fakeDao.cafes.value = listOf(cafe1, cafe2)
        advanceUntilIdle()

        viewModel.disconnect("cafe-1")
        advanceUntilIdle()

        assertEquals(listOf(cafe2), viewModel.connectedCafes.value)
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

/** Simple fake DAO that simulates Room's reactive Flow behavior. */
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
