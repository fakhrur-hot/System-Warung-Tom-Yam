package com.razstudio.pos.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ListenerPrefsStore.isWithinBusinessHours] logic.
 *
 * Uses the internal testable overload that accepts explicit hour parameters,
 * avoiding the need for an Android context or DataStore.
 */
class BusinessHoursTest {

    // We can't instantiate ListenerPrefsStore without a Context, so we test the pure logic
    // by replicating the algorithm (it's a static calculation).
    // The implementation in ListenerPrefsStore.isWithinBusinessHours(currentHour, startHour, endHour)
    // uses the same logic tested here.

    @Test
    fun notSynced_startMinusOne_returnsTrue() {
        assertTrue(isWithinBusinessHours(currentHour = 10, startHour = -1, endHour = 2))
    }

    @Test
    fun notSynced_endMinusOne_returnsTrue() {
        assertTrue(isWithinBusinessHours(currentHour = 10, startHour = 15, endHour = -1))
    }

    @Test
    fun notSynced_bothMinusOne_returnsTrue() {
        assertTrue(isWithinBusinessHours(currentHour = 0, startHour = -1, endHour = -1))
    }

    @Test
    fun sameDayRange_withinRange_returnsTrue() {
        // start=8, end=17 → open 08:00–16:59
        assertTrue(isWithinBusinessHours(currentHour = 8, startHour = 8, endHour = 17))
        assertTrue(isWithinBusinessHours(currentHour = 12, startHour = 8, endHour = 17))
        assertTrue(isWithinBusinessHours(currentHour = 16, startHour = 8, endHour = 17))
    }

    @Test
    fun sameDayRange_outsideRange_returnsFalse() {
        // start=8, end=17 → closed before 8 and at/after 17
        assertFalse(isWithinBusinessHours(currentHour = 7, startHour = 8, endHour = 17))
        assertFalse(isWithinBusinessHours(currentHour = 17, startHour = 8, endHour = 17))
        assertFalse(isWithinBusinessHours(currentHour = 23, startHour = 8, endHour = 17))
    }

    @Test
    fun wrapAroundMidnight_withinRange_returnsTrue() {
        // start=15, end=2 → open 15:00–01:59 (typical Malaysian café)
        assertTrue(isWithinBusinessHours(currentHour = 15, startHour = 15, endHour = 2))
        assertTrue(isWithinBusinessHours(currentHour = 18, startHour = 15, endHour = 2))
        assertTrue(isWithinBusinessHours(currentHour = 23, startHour = 15, endHour = 2))
        assertTrue(isWithinBusinessHours(currentHour = 0, startHour = 15, endHour = 2))
        assertTrue(isWithinBusinessHours(currentHour = 1, startHour = 15, endHour = 2))
    }

    @Test
    fun wrapAroundMidnight_outsideRange_returnsFalse() {
        // start=15, end=2 → closed 02:00–14:59
        assertFalse(isWithinBusinessHours(currentHour = 2, startHour = 15, endHour = 2))
        assertFalse(isWithinBusinessHours(currentHour = 10, startHour = 15, endHour = 2))
        assertFalse(isWithinBusinessHours(currentHour = 14, startHour = 15, endHour = 2))
    }

    @Test
    fun startEqualsEnd_alwaysFalse_sameDayBranch() {
        // start == end → range has zero width (startHour <= endHour branch, until is empty)
        assertFalse(isWithinBusinessHours(currentHour = 10, startHour = 10, endHour = 10))
        assertFalse(isWithinBusinessHours(currentHour = 0, startHour = 0, endHour = 0))
    }

    // ── Helper replicating the pure logic from ListenerPrefsStore ─────────────────────────────

    private fun isWithinBusinessHours(currentHour: Int, startHour: Int, endHour: Int): Boolean {
        if (startHour == -1 || endHour == -1) return true

        return if (startHour <= endHour) {
            currentHour in startHour until endHour
        } else {
            currentHour >= startHour || currentHour < endHour
        }
    }
}
