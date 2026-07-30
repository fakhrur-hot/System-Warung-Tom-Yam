package com.razstudio.pos.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local settings for Ambient (screensaver) mode.
 *
 * Stored per-device rather than café-wide because it describes THIS terminal's physical situation —
 * whether it sits on a powered counter, and whether guests can see its screen — which differs
 * between the admin station and each staff tablet.
 *
 * Plain SharedPreferences (not encrypted): none of this is a credential.
 */
@Singleton
class AmbientSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS = "ambient_prefs"
        private const val KEY_ENABLED = "ambient_enabled"
        private const val KEY_TIMEOUT_MIN = "ambient_timeout_minutes"
        private const val KEY_CUSTOMER_FACING = "ambient_customer_facing"

        /** Selectable idle delays before ambient mode takes over. */
        val TIMEOUT_OPTIONS = listOf(1, 3, 5, 10, 15)
        private const val DEFAULT_TIMEOUT_MIN = 5
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Master switch. While on, the app also holds the screen awake (see the keep-screen-on flag in
     * AppNavGraph) so the display never sleeps mid-service — ambient mode is what protects the
     * pixels once the station goes idle.
     */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getTimeoutMinutes(): Int = prefs.getInt(KEY_TIMEOUT_MIN, DEFAULT_TIMEOUT_MIN)

    fun setTimeoutMinutes(minutes: Int) {
        if (minutes in TIMEOUT_OPTIONS) {
            prefs.edit().putInt(KEY_TIMEOUT_MIN, minutes).apply()
        }
    }

    fun getTimeoutMillis(): Long = getTimeoutMinutes() * 60_000L

    /**
     * When this terminal's screen is visible to guests, ambient mode shows table occupancy only —
     * no money, no item counts — so idle glanceable info can't leak revenue to the dining room.
     */
    fun isCustomerFacing(): Boolean = prefs.getBoolean(KEY_CUSTOMER_FACING, false)

    fun setCustomerFacing(customerFacing: Boolean) {
        prefs.edit().putBoolean(KEY_CUSTOMER_FACING, customerFacing).apply()
    }
}
