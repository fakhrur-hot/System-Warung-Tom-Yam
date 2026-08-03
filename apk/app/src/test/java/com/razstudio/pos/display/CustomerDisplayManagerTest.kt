package com.razstudio.pos.display

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.razstudio.pos.data.local.LocalPrefs
import com.razstudio.pos.printing.DriverAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 4.3 — the null-object default, and the resolution rule around it.
 *
 * The property under test: **`CustomerDisplayManager` always resolves to a driver.** Call sites in
 * the order flow do an unconditional `show(state)`; if resolution could return null, every one of
 * them would need a branch, and the one that got forgotten would crash a till over an accessory.
 */
@RunWith(RobolectricTestRunner::class)
class CustomerDisplayManagerTest {

    private lateinit var prefs: LocalPrefs

    /** Records whether it was driven, so routing can be asserted without a real screen. */
    private class FakeDriver(
        override val kind: DisplayDriverKind,
        override val canRenderQr: Boolean = false,
    ) : CustomerDisplayDriver {
        var shown: CustomerDisplayState? = null
        override suspend fun availability(context: Context) = DriverAvailability(true)
        override suspend fun show(state: CustomerDisplayState) { shown = state }
        override suspend fun clear() { shown = null }
    }

    private val none = NoDisplayDriver()
    private val presentation = FakeDriver(DisplayDriverKind.PRESENTATION, canRenderQr = true)

    private fun manager(drivers: Set<CustomerDisplayDriver> = setOf(none, presentation)) =
        CustomerDisplayManager(prefs, drivers)

    @Before
    fun setUp() {
        prefs = LocalPrefs(ApplicationProvider.getApplicationContext())
        prefs.selectedDisplayDriver = null
    }

    @Test
    fun noSelectionResolvesToTheNullObject() {
        // A fresh café has chosen nothing. It must still be safe to call show().
        assertEquals(DisplayDriverKind.NONE, manager().current().kind)
    }

    @Test
    fun aStoredSelectionResolvesToThatDriver() {
        prefs.selectedDisplayDriver = DisplayDriverKind.PRESENTATION.name
        assertEquals(DisplayDriverKind.PRESENTATION, manager().current().kind)
    }

    @Test
    fun aSelectionThisBuildDoesNotContainFallsBackRatherThanFailing() {
        // A café that downgraded, or a preference written by a later build. Showing nothing beats
        // taking the till down for a customer screen.
        prefs.selectedDisplayDriver = DisplayDriverKind.VFD_SERIAL.name
        assertEquals(DisplayDriverKind.NONE, manager(setOf(none, presentation)).current().kind)
    }

    @Test
    fun anUnparseableSelectionFallsBackToo() {
        // Not reachable through the UI, but a corrupted preference must not throw here.
        prefs.selectedDisplayDriver = "SOMETHING_ELSE"
        assertEquals(DisplayDriverKind.NONE, manager().current().kind)
    }

    @Test
    fun qrCapabilityFollowsTheSelectedDriver() {
        // HW-REQ-4: the payment flow asks this before offering to mirror a QR, because a
        // text-strip display cannot render one.
        assertFalse(manager().canRenderQr())

        prefs.selectedDisplayDriver = DisplayDriverKind.PRESENTATION.name
        assertTrue(manager().canRenderQr())
    }

    @Test
    fun theSelectionIsReReadSoAChangeTakesEffectWithoutARestart() {
        // Devices & Hardware writes LocalPrefs while the till is running; a cached driver would
        // need an invalidation path that nothing currently calls.
        val m = manager()
        assertEquals(DisplayDriverKind.NONE, m.current().kind)

        prefs.selectedDisplayDriver = DisplayDriverKind.PRESENTATION.name
        assertEquals(DisplayDriverKind.PRESENTATION, m.current().kind)
    }

    @Test
    fun theNullObjectAcceptsEveryStateWithoutFailing() = kotlinx.coroutines.test.runTest {
        // It exists precisely so callers never branch — so it must swallow all four states.
        none.show(CustomerDisplayState.Idle("Tani Tom Yam"))
        none.show(CustomerDisplayState.Order(emptyList(), 0.0))
        none.show(CustomerDisplayState.ThankYou(changeDue = 2.50))
        none.clear()
        assertTrue(none.availability(ApplicationProvider.getApplicationContext()).available)
    }

    @Test
    fun displayKindNamesAreStableStorageKeys() {
        // These names are persisted in LocalPrefs. Renaming an entry silently resets every café's
        // choice back to "no display", which nobody would connect to a refactor.
        assertEquals(
            listOf("NONE", "PRESENTATION", "VFD_SERIAL"),
            DisplayDriverKind.entries.map { it.name }
        )
    }
}
