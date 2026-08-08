package com.razstudio.pos.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.NewOrderItem
import com.razstudio.pos.data.OperatingMode
import com.razstudio.pos.data.VoidLine
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 4.6 — the [LocalBackend] order lifecycle, end to end
 * (Property 7: order ids cannot collide on the single-device path. Validates Requirement 8.4).
 *
 * Two things are being protected here.
 *
 * **Order identity.** Off-cloud the Server Device is the sole id assigner, so nothing external
 * prevents a collision — the guarantee has to come from the id being minted here and never accepted
 * from a caller. A repeat would silently merge two tables' bills.
 *
 * **The state machine.** Every transition goes through [OrderActions], the same predicates the
 * order-detail sheets use to decide which buttons to show. If the endpoint and the button ever
 * disagree, staff get a control that fails when pressed — or worse, one that succeeds when it should
 * not, like taking payment twice on one order.
 */
@RunWith(RobolectricTestRunner::class)
class LocalBackendOrderLifecycleTest {

    private lateinit var db: AppDatabase
    private lateinit var backend: LocalBackend

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // AppConfigStore is backed by EncryptedSharedPreferences, which needs a keystore Robolectric
        // does not provide — hence its test-only constructor. Without it the store silently drops
        // every write and ModeRepository would read CLOUD no matter what we set.
        val prefs = context.getSharedPreferences("lifecycle_test_cfg", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val config = AppConfigStore(context, prefs)
        config.setOperatingMode(OperatingMode.KIOSK)

        backend = LocalBackend(
            orderDao = db.orderDao(),
            menuDao = db.menuDao(),
            settingsDao = db.settingsDao(),
            tableDao = db.tableDao(),
            cafeSessionDao = db.cafeSessionDao(),
            dailyAggregateDao = db.dailyAggregateDao(),
            modeRepository = ModeRepository(config),
            menuCategoryStore = MenuCategoryStore(context),
            localImageStore = LocalImageStore(context),
            pairedDeviceDao = db.pairedDeviceDao(),
            pairingTokenDao = db.pairingTokenDao(),
            lanAddress = com.razstudio.pos.data.lan.LanAddress(context),
            pushBus = com.razstudio.pos.data.lan.LanPushBus(),
            appConfigStore = config,
            appContext = context,
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedMenu() {
        db.menuDao().upsertAll(
            listOf(
                MenuItem(
                    id = "m-nasi", category = "FOOD", price = 8.0,
                    available = true, askMeDaily = false, nameEn = "Nasi Lemak",
                ),
                MenuItem(
                    id = "m-teh", category = "BEVERAGES", price = 3.0,
                    available = true, askMeDaily = false, nameEn = "Teh Tarik",
                ),
            )
        )
    }

    private suspend fun newOrder(table: String = "T0001"): String {
        val result = backend.createOrder(
            tableId = table,
            items = listOf(
                NewOrderItem(menuItemId = "m-nasi", quantity = 1),
                NewOrderItem(menuItemId = "m-teh", quantity = 2),
            ),
            source = "STAFF",
        )
        return (result as ApiResult.Success).data.orderId
    }

    // ── Property 7: ids cannot collide ────────────────────────────────────────────────────────────

    @Test
    fun everyOrderGetsAUniqueId_evenForTheSameTableInTheSameMillisecond() = runTest {
        seedMenu()
        val ids = (1..200).map { newOrder() }

        assertEquals(
            "two orders sharing an id would merge two tables' bills into one",
            ids.size, ids.toSet().size,
        )
        assertTrue("ids must be non-blank", ids.all { it.isNotBlank() })
    }

    @Test
    fun theIdIsMintedByTheBackend_notTakenFromTheCaller() = runTest {
        seedMenu()
        // createOrder's signature has nowhere to pass an id. That is the guarantee — a Client cannot
        // propose one, so it cannot force a collision. Asserted as a shape check because there is no
        // runtime path to test: the absence of the parameter IS Requirement 8.4.
        val id = newOrder()
        assertNotNull(db.orderDao().getOrderById(id))
        assertTrue("a minted id should be a UUID", Regex("^[0-9a-f-]{36}$").matches(id))
    }

    // ── The happy path: create → kitchen → payment → closed ───────────────────────────────────────

    @Test
    fun createToKitchenToPaymentClosesTheOrderWithTheRightTotal() = runTest {
        seedMenu()
        val id = newOrder()

        // Created, priced from the menu snapshot: 8.00 + (3.00 x 2).
        // Staff orders are immediately SENT_TO_KITCHEN — the cashier IS the confirmation.
        val created = db.orderDao().getOrderById(id)!!
        assertEquals(OrderStatus.SENT_TO_KITCHEN, created.status)
        assertEquals(14.0, created.total, 0.001)

        // The item lines must agree with the order status: a line still flagged "not sent" on an
        // order already marked SENT_TO_KITCHEN would sit in the Pending Kitchen Prints queue
        // forever, with nothing left to call sendToKitchen() on it.
        assertTrue(
            "a staff order's lines must already be marked sent, matching its SENT_TO_KITCHEN status",
            db.orderDao().getItemsForOrder(id).all { it.sentToKitchen },
        )

        // Payment is valid once past RECEIVED — staff orders start at SENT_TO_KITCHEN already.
        backend.updateOrderStatus(id, "READY")
        val paid = backend.processPayment(id, "CASH")
        assertTrue(paid is ApiResult.Success)

        val closed = db.orderDao().getOrderById(id)!!
        assertEquals(OrderStatus.COMPLETED, closed.status)
        assertEquals("CASH", closed.paymentMethod)
        assertEquals("payment must not alter the amount owed", 14.0, closed.total, 0.001)
    }

    // ── OrderActions is genuinely enforced, not merely consulted ──────────────────────────────────

    @Test
    fun paymentIsRefusedOnAFreshCustomerOrderWhenAutoPrintIsDisabled() = runTest {
        seedMenu()
        // Disable autoPrint so customer orders land in RECEIVED.
        db.settingsDao().upsert(SystemSettings(customerOrderAutoPrint = false))

        val result = backend.createOrder(
            tableId = "T0001",
            items = listOf(
                NewOrderItem(menuItemId = "m-nasi", quantity = 1),
            ),
            source = "CUSTOMER",
        )
        val id = (result as ApiResult.Success).data.orderId

        // RECEIVED is not payable — OrderActions.canTakePayment covers SENT_TO_KITCHEN/PREPARING/READY.
        val payResult = backend.processPayment(id, "CASH")
        assertTrue(payResult is ApiResult.Error)
        assertEquals("PAYMENT_CONFLICT", (payResult as ApiResult.Error).code)
        assertEquals(OrderStatus.RECEIVED, db.orderDao().getOrderById(id)!!.status)

        // Held for cashier confirmation on the item lines too — ping mode is the point.
        assertTrue(
            "a held customer order's lines must not be marked sent yet",
            db.orderDao().getItemsForOrder(id).none { it.sentToKitchen },
        )
    }

    @Test
    fun anOrderCannotBePaidTwice() = runTest {
        seedMenu()
        val id = newOrder()
        backend.updateOrderStatus(id, "READY")
        backend.processPayment(id, "CASH")

        val second = backend.processPayment(id, "QR")
        assertTrue(second is ApiResult.Error)
        assertEquals("ALREADY_PAID", (second as ApiResult.Error).code)
        assertEquals(
            "a second payment must not overwrite how the customer actually paid",
            "CASH", db.orderDao().getOrderById(id)!!.paymentMethod,
        )
    }

    @Test
    fun aClosedOrderRejectsEveryFurtherMutation() = runTest {
        seedMenu()
        val id = newOrder()
        backend.updateOrderStatus(id, "READY")
        backend.processPayment(id, "CASH")

        val items = db.orderDao().getItemsForOrder(id)
        assertTrue(backend.updateOrderStatus(id, "PREPARING") is ApiResult.Error)
        assertTrue(backend.sendToKitchen(id, null) is ApiResult.Error)
        assertTrue(backend.addItemsToOrder(id, listOf(NewOrderItem("m-teh", 1))) is ApiResult.Error)
        assertTrue(backend.cancelOrder(id, "changed mind", "ADMIN") is ApiResult.Error)
        assertTrue(
            backend.voidOrderItems(id, listOf(VoidLine(items.first().id, 0)), "not served") is ApiResult.Error
        )

        val after = db.orderDao().getOrderById(id)!!
        assertEquals(OrderStatus.COMPLETED, after.status)
        assertEquals(14.0, after.total, 0.001)
    }

    @Test
    fun cancellingIsTerminalAndBlocksPayment() = runTest {
        seedMenu()
        val id = newOrder()

        assertTrue(backend.cancelOrder(id, "walked out", "ADMIN") is ApiResult.Success)
        assertEquals(OrderStatus.CANCELLED, db.orderDao().getOrderById(id)!!.status)

        val pay = backend.processPayment(id, "CASH")
        assertTrue("a cancelled order must never become payable", pay is ApiResult.Error)
    }

    @Test
    fun onlyPreparingAndReadyCanBeSetThroughTheStatusEndpoint() = runTest {
        seedMenu()
        val id = newOrder()

        // COMPLETED and CANCELLED record more than a status — a payment method, a reason — so routing
        // them through here would be a way to close an order with neither.
        for (forbidden in listOf("COMPLETED", "CANCELLED", "RECEIVED", "NONSENSE")) {
            val r = backend.updateOrderStatus(id, forbidden)
            assertTrue("$forbidden must be refused by the status endpoint", r is ApiResult.Error)
        }
        // Staff orders start at SENT_TO_KITCHEN; the forbidden attempts above must not change that.
        assertEquals(OrderStatus.SENT_TO_KITCHEN, db.orderDao().getOrderById(id)!!.status)

        assertTrue(backend.updateOrderStatus(id, "PREPARING") is ApiResult.Success)
        assertTrue(backend.updateOrderStatus(id, "READY") is ApiResult.Success)
    }

    // ── Amendments keep the bill and the rounds coherent ──────────────────────────────────────────

    @Test
    fun addingARoundBumpsTheSessionAndTheTotal() = runTest {
        seedMenu()
        val id = newOrder()

        val amended = backend.addItemsToOrder(id, listOf(NewOrderItem("m-teh", 1)))
        assertTrue(amended is ApiResult.Success)
        assertEquals(17.0, (amended as ApiResult.Success).data.total, 0.001)

        val bySession = db.orderDao().getItemsForOrder(id).groupBy { it.sessionNumber }
        assertEquals("each call is one round, so one kitchen slip", setOf(1, 2), bySession.keys)
    }

    @Test
    fun voidingCannotEmptyAnOrderNorIncreaseAQuantity() = runTest {
        seedMenu()
        val id = newOrder()
        val items = db.orderDao().getItemsForOrder(id)
        val teh = items.first { it.menuItemId == "m-teh" }

        // Reducing 2 -> 1 is fine and re-prices the bill.
        val reduced = backend.voidOrderItems(id, listOf(VoidLine(teh.id, 1)), "not served")
        assertEquals(11.0, ((reduced as ApiResult.Success).data).total, 0.001)

        // Going back up is not this endpoint's job.
        val up = backend.voidOrderItems(id, listOf(VoidLine(teh.id, 5)), "oops")
        assertEquals("CANNOT_INCREASE", (up as ApiResult.Error).code)

        // Removing everything is a cancellation, which is recorded differently.
        val all = db.orderDao().getItemsForOrder(id).map { VoidLine(it.id, 0) }
        val emptied = backend.voidOrderItems(id, all, "none of it came")
        assertEquals("WOULD_EMPTY_ORDER", (emptied as ApiResult.Error).code)
        assertTrue("the bill must survive the refused void", db.orderDao().getItemsForOrder(id).isNotEmpty())
    }
}
