package com.razstudio.pos.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local print preferences (the printer/output setup is per-device, like the printer
 * registry itself). Currently holds the kitchen-slip menu-text size.
 *
 * Sizes: XS, S (default), M, L, XL, XXL — see [KitchenFontSize] for how each maps to a
 * DantSu ESC/POS font size and the derived (smaller) note size.
 */
@Singleton
class PrintSettingsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("print_settings_prefs", Context.MODE_PRIVATE)

    fun getKitchenFontSize(): String = prefs.getString(KEY_KITCHEN_FONT, DEFAULT) ?: DEFAULT

    fun setKitchenFontSize(size: String) {
        prefs.edit().putString(KEY_KITCHEN_FONT, size).apply()
    }

    /** Whether to print the café logo as a header on customer receipts (device-local). */
    fun getReceiptLogo(): Boolean = prefs.getBoolean(KEY_RECEIPT_LOGO, false)

    fun setReceiptLogo(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RECEIPT_LOGO, enabled).apply()
    }

    /**
     * Use the older ESC * bit-image command for printing bitmaps (logos + multilingual slips)
     * instead of DantSu's default GS v 0 raster. Many low-cost 58mm printers only render images
     * correctly with ESC * (GS v 0 comes out blank/garbled), so this defaults ON. Device-local.
     */
    fun getEscAsteriskImageMode(): Boolean = prefs.getBoolean(KEY_ESC_ASTERISK, true)

    fun setEscAsteriskImageMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ESC_ASTERISK, enabled).apply()
    }

    private companion object {
        const val KEY_KITCHEN_FONT = "kitchen_font_size"
        const val KEY_RECEIPT_LOGO = "receipt_logo"
        const val KEY_ESC_ASTERISK = "esc_asterisk_image_mode"
        const val DEFAULT = "M"
    }
}

/**
 * Kitchen-slip menu-text size. Common 58mm thermal printers cap magnification at 2×, so we
 * expose three reliable sizes that all render on such hardware:
 *   S (Small)  = normal (1×1, the old default)
 *   M (Medium) = tall   (2× height, same width — best for long names)
 *   L (Large)  = big    (2×2, like the table-name header)
 * The special-instruction note prints one step smaller than the menu text.
 */
object KitchenFontSize {
    val LEVELS = listOf("S", "M", "L")

    private val MENU = mapOf("S" to "normal", "M" to "tall", "L" to "big")
    private val NOTE = mapOf("S" to "normal", "M" to "normal", "L" to "tall")

    /** DantSu font size for the menu-item line at [level] (falls back to the M default). */
    fun menu(level: String): String = MENU[level] ?: MENU["M"]!!

    /** DantSu font size for the note line at [level]. */
    fun note(level: String): String = NOTE[level] ?: NOTE["M"]!!

    /** Human label for the size selector. */
    fun label(level: String): String = when (level) {
        "S" -> "Small"
        "M" -> "Medium"
        "L" -> "Large"
        else -> level
    }
}
