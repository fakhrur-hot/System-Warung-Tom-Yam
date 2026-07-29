package com.warungtomyam.pos.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-encrypted SharedPreferences for lightweight session tracking.
 * Tracks the last date the café was opened (for daily availability popup logic).
 */
@Singleton
class SessionPrefs @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        private const val PREFS_NAME = "session_prefs"
        private const val KEY_LAST_OPEN_DATE = "last_open_date"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Get the last date the session was opened (ISO format, e.g. "2026-07-19"). */
    fun getLastOpenDate(): String? = prefs.getString(KEY_LAST_OPEN_DATE, null)

    /** Save today's date as the last opened date. */
    fun setLastOpenDate(date: String) {
        prefs.edit().putString(KEY_LAST_OPEN_DATE, date).apply()
    }

    /** Check if today is a new day compared to the last open date. */
    fun isNewDay(): Boolean {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        return getLastOpenDate() != today
    }

    /** Mark today as opened. */
    fun markTodayOpened() {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        setLastOpenDate(today)
    }
}
