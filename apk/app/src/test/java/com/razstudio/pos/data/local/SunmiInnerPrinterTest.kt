package com.razstudio.pos.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Sunmi internal printer must never be reachable as a Bluetooth device.
 *
 * Observed on a D3 Mini: the terminal registers its own built-in printer as a bonded Bluetooth
 * device named `InnerPrinter` on `00:11:22:33:44:55`. It appeared in the app's Bluetooth scan, a
 * café selected it, and the app opened an RFCOMM socket to it —
 * `D BluetoothSocket: -------Write Address: 00:11:22:33:44:55` — saving a printer row on the wrong
 * transport. That row loses `openDrawer()`, paper detection and the hardware status broadcasts,
 * all of which live on the AIDL, and needs `BLUETOOTH_CONNECT`, which that unit had denied.
 *
 * The entry stays visible in the scan — that is where a café looks for it — so [SunmiInnerPrinter]
 * is what lets the *selection* be routed to the AIDL transport instead of being taken literally.
 */
@RunWith(RobolectricTestRunner::class)
class SunmiInnerPrinterTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PrinterConfigDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.printerConfigDao()
    }

    @After
    fun tearDown() = db.close()

    private fun printer(
        id: String,
        name: String,
        address: String?,
        transport: PrinterTransport = PrinterTransport.BLUETOOTH,
    ) = PrinterConfig(
        id = id,
        name = name,
        address = address,
        transport = transport,
        drawerKick = DrawerKick.NONE,
        paperWidth = PaperWidth.FIFTY_EIGHT_MM,
        printerRole = PrinterRole.BOTH,
    )

    // ── recognition ──────────────────────────────────────────────────────────────────────────

    @Test
    fun theBondedInnerPrinterIsRecognisedByNameOrByMac() {
        assertTrue(SunmiInnerPrinter.matches("InnerPrinter", "00:11:22:33:44:55"))
        // Either signal alone is enough: the name varies across Sunmi models, and the MAC is a
        // placeholder that no real printer ships with.
        assertTrue(SunmiInnerPrinter.matches("InnerPrinter", "AA:BB:CC:DD:EE:FF"))
        assertTrue(SunmiInnerPrinter.matches("Something Else", "00:11:22:33:44:55"))
        assertTrue(SunmiInnerPrinter.matches("innerprinter", null))
    }

    @Test
    fun anOrdinaryBluetoothPrinterIsNotMistakenForIt() {
        // The filter must not swallow a café's real printer.
        assertFalse(SunmiInnerPrinter.matches("XP-58IIH", "66:22:5C:11:0A:3B"))
        assertFalse(SunmiInnerPrinter.matches("Kitchen Printer", "DC:0D:30:11:22:33"))
        assertFalse(SunmiInnerPrinter.matches(null, null))
    }

    // ── repair ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun aBluetoothInnerPrinterRowIsMovedOntoTheAidl() = runTest {
        // Exactly the row found on the device.
        dao.insert(printer("p1", "InnerPrinter", "00:11:22:33:44:55"))

        assertEquals(1, dao.repairSunmiInnerPrinterTransport())

        val fixed = dao.getById("p1")!!
        assertEquals(PrinterTransport.SUNMI_AIDL, fixed.transport)
        // The placeholder MAC is cleared: an AIDL printer has no address, and leaving a fake one
        // there would survive into a backup and look like a real pairing on the next device.
        assertNull(fixed.address)
    }

    @Test
    fun theRepairLeavesTheDrawerChoiceAlone() = runTest {
        dao.insert(printer("p1", "InnerPrinter", "00:11:22:33:44:55"))
        dao.repairSunmiInnerPrinterTransport()

        // Had the café gone through the correct flow it would have picked the drawer separately in
        // Devices & Hardware. Silently enabling one nobody selected is a surprise, not a repair.
        assertEquals(DrawerKick.NONE, dao.getById("p1")!!.drawerKick)
    }

    @Test
    fun aRealBluetoothPrinterIsUntouched() = runTest {
        dao.insert(printer("p1", "XP-58IIH", "66:22:5C:11:0A:3B"))

        assertEquals(0, dao.repairSunmiInnerPrinterTransport())

        val untouched = dao.getById("p1")!!
        assertEquals(PrinterTransport.BLUETOOTH, untouched.transport)
        assertEquals("66:22:5C:11:0A:3B", untouched.address)
    }

    @Test
    fun aPrinterAlreadyOnTheAidlIsNotRewritten() = runTest {
        // The repair runs behind a one-time flag, but it must also be idempotent — a retry after a
        // failure part-way through must not corrupt rows it already handled.
        dao.insert(printer("p1", "InnerPrinter", null, PrinterTransport.SUNMI_AIDL))
        assertEquals(0, dao.repairSunmiInnerPrinterTransport())
        assertEquals(PrinterTransport.SUNMI_AIDL, dao.getById("p1")!!.transport)
    }

    @Test
    fun repairingTwiceChangesNothingTheSecondTime() = runTest {
        dao.insert(printer("p1", "InnerPrinter", "00:11:22:33:44:55"))
        assertEquals(1, dao.repairSunmiInnerPrinterTransport())
        assertEquals(0, dao.repairSunmiInnerPrinterTransport())
    }

    @Test
    fun aMixedSetupRepairsOnlyTheInternalOne() = runTest {
        dao.insert(printer("p1", "InnerPrinter", "00:11:22:33:44:55"))
        dao.insert(printer("p2", "Kitchen XP-80", "DC:0D:30:AA:BB:CC"))

        assertEquals(1, dao.repairSunmiInnerPrinterTransport())

        assertEquals(PrinterTransport.SUNMI_AIDL, dao.getById("p1")!!.transport)
        assertEquals(PrinterTransport.BLUETOOTH, dao.getById("p2")!!.transport)
        assertEquals("DC:0D:30:AA:BB:CC", dao.getById("p2")!!.address)
    }
}
