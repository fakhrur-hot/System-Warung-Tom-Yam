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

    /**
     * Hide the Android status bar and navigation bar, giving the app the whole panel.
     *
     * Device-local, like everything else that describes one physical terminal. A till bolted
     * to a counter has no use for a clock, a battery icon or a Back button a customer can
     * press — and on the D3's short landscape canvas the two system bars are a real fraction
     * of the screen the table grid could be using instead.
     *
     * Off by default: hiding the navigation bar on a device somebody is still setting up is a
     * good way to strand them, and it should be a choice made by someone who knows the swipe
     * gesture brings the bars back.
     */
    var fullscreenMode: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN, false)
        set(value) { prefs.edit().putBoolean(KEY_FULLSCREEN, value).apply() }

    /**
     * Which café the locally-stored tables were last synced for — see [TableSync].
     *
     * Null means unknown provenance: written before this marker existed, or seeded by Demo Mode
     * straight into the real database. Both are treated as foreign, because both produced a till
     * showing a floor plan that belonged to nothing.
     */
    var tablesSyncedForCafe: String?
        get() = prefs.getString(KEY_TABLES_SYNCED_FOR, null)
        set(value) { prefs.edit().putString(KEY_TABLES_SYNCED_FOR, value).apply() }

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

    // ── Hardware driver selection (HW-REQ-8: device-local, never café-wide) ──────────────────

    /**
     * The [com.razstudio.pos.data.local.PrinterTransport] name of the selected printer driver
     * for this device. Null = no explicit selection (first available driver is used).
     */
    var selectedPrinterTransport: String?
        get() = if (prefs.contains(KEY_PRINTER_TRANSPORT)) prefs.getString(KEY_PRINTER_TRANSPORT, null) else null
        set(value) {
            if (value != null) prefs.edit().putString(KEY_PRINTER_TRANSPORT, value).apply()
            else prefs.edit().remove(KEY_PRINTER_TRANSPORT).apply()
        }

    /**
     * Which payment rail the café's uploaded Payment QR is for — a `PaymentQrBrand` name, or null
     * before the cashier has said.
     *
     * Device-local rather than a café setting, deliberately: the QR image itself already syncs
     * from branding, and this is only the label shown beside it. Storing it here means a till can
     * be corrected on the spot without an admin round-trip, which matters when the wrong label is
     * actively confusing a customer at the counter.
     */
    var paymentQrBrand: String?
        get() = if (prefs.contains(KEY_PAYMENT_QR_BRAND)) prefs.getString(KEY_PAYMENT_QR_BRAND, null) else null
        set(value) {
            if (value != null) prefs.edit().putString(KEY_PAYMENT_QR_BRAND, value).apply()
            else prefs.edit().remove(KEY_PAYMENT_QR_BRAND).apply()
        }

    /**
     * The selected cash-drawer source for this device — either the printer id that has a
     * drawer, or null for none.
     */
    var selectedDrawerPrinterId: String?
        get() = if (prefs.contains(KEY_DRAWER_PRINTER_ID)) prefs.getString(KEY_DRAWER_PRINTER_ID, null) else null
        set(value) {
            if (value != null) prefs.edit().putString(KEY_DRAWER_PRINTER_ID, value).apply()
            else prefs.edit().remove(KEY_DRAWER_PRINTER_ID).apply()
        }

    /**
     * The selected customer display driver class name for this device. Null = no display.
     */
    var selectedDisplayDriver: String?
        get() = if (prefs.contains(KEY_DISPLAY_DRIVER)) prefs.getString(KEY_DISPLAY_DRIVER, null) else null
        set(value) {
            if (value != null) prefs.edit().putString(KEY_DISPLAY_DRIVER, value).apply()
            else prefs.edit().remove(KEY_DISPLAY_DRIVER).apply()
        }

    companion object {
        private const val KEY_SHOW_PRINT_STATUS = "show_print_status"
        private const val KEY_LOW_STOCK_ALERTS = "low_stock_alerts"
        private const val KEY_FULLSCREEN = "fullscreen_mode"
        private const val KEY_TABLES_SYNCED_FOR = "tables_synced_for_cafe"
        private const val KEY_ORDER_SOUND_URI = "new_order_sound_uri"
        private const val KEY_ORDER_SOUND_VOLUME = "new_order_sound_volume"
        private const val KEY_PRINTER_TRANSPORT = "selected_printer_transport"
        private const val KEY_DRAWER_PRINTER_ID = "selected_drawer_printer_id"
        private const val KEY_DISPLAY_DRIVER = "selected_display_driver"
        private const val KEY_PAYMENT_QR_BRAND = "payment_qr_brand"
    }
}
