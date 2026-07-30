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

    /**
     * Ringtone played when a new order arrives, as a content-URI string.
     *
     * Three distinct states, which is why this is nullable AND has a separate "set" marker:
     * - key absent  → never chosen; fall back to the system notification default.
     * - key present, non-blank → the operator's pick.
     * - key present, blank → the operator explicitly chose "Silent".
     */
    var newOrderSoundUri: String?
        get() = if (prefs.contains(KEY_ORDER_SOUND_URI)) prefs.getString(KEY_ORDER_SOUND_URI, null) else null
        set(value) { prefs.edit().putString(KEY_ORDER_SOUND_URI, value ?: "").apply() }

    /** True once the operator has made an explicit choice (including "Silent"). */
    val newOrderSoundChosen: Boolean get() = prefs.contains(KEY_ORDER_SOUND_URI)

    /** Alert volume as a percentage (0–100) of the device's notification stream level. */
    var newOrderSoundVolume: Int
        get() = prefs.getInt(KEY_ORDER_SOUND_VOLUME, 100).coerceIn(0, 100)
        set(value) { prefs.edit().putInt(KEY_ORDER_SOUND_VOLUME, value.coerceIn(0, 100)).apply() }

    companion object {
        private const val KEY_SHOW_PRINT_STATUS = "show_print_status"
        private const val KEY_LOW_STOCK_ALERTS = "low_stock_alerts"
        private const val KEY_ORDER_SOUND_URI = "new_order_sound_uri"
        private const val KEY_ORDER_SOUND_VOLUME = "new_order_sound_volume"
    }
}
