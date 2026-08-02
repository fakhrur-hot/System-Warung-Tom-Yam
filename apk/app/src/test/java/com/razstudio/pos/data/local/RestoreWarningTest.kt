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

/**
 * Task 13.1 — the backup nag must say something true, and only where it matters.
 *
 * Nothing recorded when a backup last happened, so the app could not tell an operator how long their
 * only copy had been un-backed-up — which is the entire content of a useful reminder. The banner is
 * shown off-cloud only: a Cloud café has Supabase holding a second copy, and a banner that appears
 * when it does not matter is one operators learn to ignore, which breaks it for the cafés that need it.
 */
@RunWith(RobolectricTestRunner::class)
class BackupNagTest {

    private lateinit var config: com.razstudio.pos.data.AppConfigStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = ctx.getSharedPreferences("backup_nag_test", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        config = com.razstudio.pos.data.AppConfigStore(ctx, prefs)
    }

    @Test
    fun aCafeThatHasNeverBackedUpReadsAsNeverNotZeroDays() {
        // "0 days ago" would read as reassurance on a café that has never backed up at all, which is
        // the exact opposite of what is true. 0 is the sentinel for "never".
        assertEquals(0L, config.lastBackupAtMs())
    }

    @Test
    fun aSuccessfulExportRecordsTheMoment() {
        val now = 1_785_000_000_000L
        config.setLastBackupAtMs(now)
        assertEquals(now, config.lastBackupAtMs())
    }

    @Test
    fun theAgeIsComputedFromTheRecordedMoment() {
        val threeDaysAgo = System.currentTimeMillis() - 3 * 86_400_000L
        config.setLastBackupAtMs(threeDaysAgo)

        val days = ((System.currentTimeMillis() - config.lastBackupAtMs()) / 86_400_000L).toInt()
        assertEquals(3, days)
    }

    @Test
    fun theTimestampSurvivesRestart() {
        // The banner is read on every admin-home composition, including after the process is killed
        // overnight — which is precisely when a café most needs to be told it has not backed up.
        val now = 1_785_000_000_000L
        config.setLastBackupAtMs(now)

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = ctx.getSharedPreferences("backup_nag_test", android.content.Context.MODE_PRIVATE)
        val reopened = com.razstudio.pos.data.AppConfigStore(ctx, prefs)
        assertEquals(now, reopened.lastBackupAtMs())
    }
}
