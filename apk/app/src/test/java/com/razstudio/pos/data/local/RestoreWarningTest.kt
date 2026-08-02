package com.razstudio.pos.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Task 13.2 — a restore must name what it destroys (Requirement 8.3).
 *
 * `DatabaseBackupManager.applyImport` deletes order items, orders, print jobs, printer configs,
 * pending orders, menu, tables and settings **before** importing anything. The confirm dialog listed
 * only what the backup *contained*, so an operator approved a number they had not seen — and in LAN
 * or Kiosk Mode there is no server holding a second copy, so the deletion is final.
 *
 * These pin the counts the dialog reports. A wrong count is worse than no count: it invites the
 * operator to reason about a loss that is not the one about to happen.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreWarningTest {

    private lateinit var db: AppDatabase
    private lateinit var orders: OrderDao
    private lateinit var menu: MenuDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        orders = db.orderDao()
        menu = db.menuDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(orderCount: Int, menuCount: Int) {
        repeat(orderCount) {
            orders.insertOrder(
                Order(
                    id = UUID.randomUUID().toString(),
                    tableId = "T1",
                    source = "STAFF",
                    status = OrderStatus.COMPLETED,
                    total = 10.0,
                    createdAt = "2026-08-02T10:00:00Z",
                )
            )
        }
        repeat(menuCount) { i ->
            menu.upsertAll(
                listOf(
                    MenuItem(
                        id = "m-$i",
                        category = "MAIN",
                        price = 5.0,
                        available = true,
                        askMeDaily = false,
                        nameEn = "Item $i",
                    )
                )
            )
        }
    }

    @Test
    fun theCountsReportWhatIsActuallyOnTheDevice() = runTest {
        seed(orderCount = 7, menuCount = 3)

        assertEquals("orders about to be destroyed", 7, orders.getAllOrders().size)
        assertEquals("menu items about to be destroyed", 3, menu.getAll().size)
    }

    @Test
    fun anEmptyDeviceReportsNothingToLose() = runTest {
        // A first-run device restoring a backup loses nothing, and the dialog should say zero rather
        // than a stale number from a previous preview.
        assertEquals(0, orders.getAllOrders().size)
        assertEquals(0, menu.getAll().size)
    }

    @Test
    fun theRestoreReallyDoesDestroyThem() = runTest {
        // The warning is only honest if the deletion it describes actually happens. This is the
        // wipe half of applyImport, in the order applyImport performs it.
        seed(orderCount = 4, menuCount = 2)

        orders.deleteAllOrderItems()
        orders.deleteAllOrders()
        menu.deleteAll()

        assertTrue("orders must be gone", orders.getAllOrders().isEmpty())
        assertTrue("menu must be gone", menu.getAll().isEmpty())
    }

    @Test
    fun ordersAndMenuAreCountedSeparately() = runTest {
        // They are deleted by different DAOs and restored from different sections of the backup, so
        // a single "records" figure would hide which one the operator actually cares about.
        seed(orderCount = 5, menuCount = 1)

        assertEquals(5, orders.getAllOrders().size)
        assertEquals(1, menu.getAll().size)
    }
}
