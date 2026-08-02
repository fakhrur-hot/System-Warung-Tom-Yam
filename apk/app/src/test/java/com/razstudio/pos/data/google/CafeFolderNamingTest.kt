package com.razstudio.pos.data.google

import com.razstudio.pos.data.OperatingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `RAZS.POS-{MODE}-{café name}` has to survive the round trip, because the chooser reads a café's
 * identity out of its folder name and nowhere else.
 *
 * One Google account can hold several cafés — a WLAN till, a Kiosk, a full-QR shop. The chooser
 * lists them by parsing these names, so a name that does not parse is a café the owner cannot pick,
 * and a name that parses *wrongly* is a café labelled as something it is not. Loading is
 * destructive, so mislabelling is the worse of the two.
 */
class CafeFolderNamingTest {

    @Test
    fun aNameRoundTripsBackToItsModeAndCafe() {
        val name = CafeBundleStore.folderNameFor(OperatingMode.LAN, "Tani Tom Yam")
        assertEquals("RAZS.POS-LAN-Tani Tom Yam", name)
        assertEquals("LAN" to "Tani Tom Yam", CafeBundleStore.parseFolderName(name))
    }

    @Test
    fun everyModeRoundTrips() {
        OperatingMode.entries.forEach { mode ->
            val name = CafeBundleStore.folderNameFor(mode, "Kopitiam")
            assertEquals(mode.name to "Kopitiam", CafeBundleStore.parseFolderName(name))
        }
    }

    @Test
    fun aCafeNameContainingHyphensSurvives() {
        // The split is on the FIRST hyphen after the prefix. A mode never contains one; a café name
        // routinely does, and splitting on the last would turn "Kopi-O Corner" into mode "LAN-Kopi".
        val name = CafeBundleStore.folderNameFor(OperatingMode.KIOSK, "Kopi-O Corner")
        assertEquals("KIOSK" to "Kopi-O Corner", CafeBundleStore.parseFolderName(name))
    }

    @Test
    fun theSameCafeInTwoModesProducesTwoDistinctFolders() {
        // The case the chooser exists for. If these collided, running a café as both a Kiosk and a
        // WLAN till would silently overwrite one bundle with the other.
        val kiosk = CafeBundleStore.folderNameFor(OperatingMode.KIOSK, "Warung Tom Yam")
        val lan = CafeBundleStore.folderNameFor(OperatingMode.LAN, "Warung Tom Yam")
        assert(kiosk != lan)
    }

    @Test
    fun quotesAreStrippedBecauseTheyWouldBreakTheDriveQuery() {
        // Folder lookup builds a `q=name = '...'` filter. An apostrophe in a café name would close
        // the string early and the query would either fail or match the wrong thing.
        val name = CafeBundleStore.folderNameFor(OperatingMode.CLOUD, "Mak'cik Kopi")
        assert(!name.contains("'"))
        assertEquals("CLOUD" to "Makcik Kopi", CafeBundleStore.parseFolderName(name))
    }

    // ── Anything that is not one of ours must not appear in the chooser ──────────────────────────

    @Test
    fun foreignFoldersAreIgnored() {
        listOf("", "Documents", "RAZS.POS", "RAZS.POS-", "RAZS.POS-LAN", "RAZS.POS-LAN-",
               "razs.pos-LAN-Cafe").forEach {
            assertNull("must not parse: $it", CafeBundleStore.parseFolderName(it))
        }
    }

    @Test
    fun anUnknownModeIsNotACafe() {
        // A bundle written by a future version with a mode this build cannot run. Listing it would
        // offer the owner a café that cannot load.
        assertNull(CafeBundleStore.parseFolderName("RAZS.POS-SATELLITE-Kopitiam"))
    }

    @Test
    fun aNameWithNoCafePartIsRejected() {
        assertNull(CafeBundleStore.parseFolderName("RAZS.POS-LAN-   "))
    }
}
