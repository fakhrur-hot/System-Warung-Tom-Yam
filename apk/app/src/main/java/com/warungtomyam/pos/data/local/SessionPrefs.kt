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
        const val PREFS_NAME = "session_prefs"
        private const val KEY_LAST_OPEN_DATE = "last_open_date"
        const val KEY_LOCKED = "cafe_locked"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Whether the café is signed out / locked. Persisted so a cold relaunch after sign-out shows
     * the lock screen instead of auto-signing back into the home (the credentials stay valid, so
     * without this the admin was silently logged back in). Set true on sign-out, false when the
     * session is (re)opened.
     */
    fun isLocked(): Boolean = prefs.getBoolean(KEY_LOCKED, false)

    fun setLocked(locked: Boolean) {
        prefs.edit().putBoolean(KEY_LOCKED, locked).apply()
    }

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
