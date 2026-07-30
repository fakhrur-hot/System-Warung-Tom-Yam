package com.razstudio.pos.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plain (non-secret) device-local UI preferences that don't belong in the café-wide backend
 * settings — each is specific to this physical device's behavior/UX.
 */
@Singleton
class LocalPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("app_local_prefs", Context.MODE_PRIVATE)

    /** Show a persistent recent-kitchen-prints status list (vs only transient snackbars). */
    var showPrintStatus: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PRINT_STATUS, false)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_PRINT_STATUS, value).apply() }

    /** Placeholder: enable low-stock/ingredient alerts (no inventory backend yet). */
    var lowStockAlerts: Boolean
        get() = prefs.getBoolean(KEY_LOW_STOCK_ALERTS, false)
        set(value) { prefs.edit().putBoolean(KEY_LOW_STOCK_ALERTS, value).apply() }

    companion object {
        private const val KEY_SHOW_PRINT_STATUS = "show_print_status"
        private const val KEY_LOW_STOCK_ALERTS = "low_stock_alerts"
    }
}
