package com.warungtomyam.pos.util

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Business-day dating for late-night cafés. A café whose day starts at [startHour] (e.g. 15 =
 * 3 PM) wants everything from that hour until the next day's start hour tagged to the OPENING
 * day's date — so a close at 3 AM still reports the previous calendar day.
 */
object BusinessDay {

    /** The business-day date for [instant] in [zone] given [startHour] (0–23). */
    fun of(instant: ZonedDateTime, startHour: Int): LocalDate =
        if (instant.hour < startHour) instant.toLocalDate().minusDays(1) else instant.toLocalDate()

    /** The current business-day date in [zone]. */
    fun current(zone: ZoneId, startHour: Int): LocalDate =
        of(ZonedDateTime.now(zone), startHour)

    /** Resolve a timezone id safely, falling back to the device zone. */
    fun zoneOf(timezone: String): ZoneId =
        try {
            if (timezone.isBlank()) ZoneId.systemDefault() else ZoneId.of(timezone)
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }
}
