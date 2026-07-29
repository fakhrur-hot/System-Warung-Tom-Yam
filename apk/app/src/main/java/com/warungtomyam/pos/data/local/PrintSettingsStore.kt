package com.warungtomyam.pos.data.local

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

    private companion object {
        const val KEY_KITCHEN_FONT = "kitchen_font_size"
        const val DEFAULT = "S"
    }
}

/**
 * Maps the six user-facing size levels to DantSu ESC/POS `<font size='…'>` values, for both
 * the menu-item text and the (deliberately smaller) special-instruction note text.
 *
 * Menu text:  XS→normal  S→tall  M→big  L→big-2  XL→big-3  XXL→big-4
 * Note text:  keeps the smallest size up to M, then one level below the menu size but never
 *             larger than L — so menu L→note M, menu XL→note L, menu XXL→note L.
 */
object KitchenFontSize {
    val LEVELS = listOf("XS", "S", "M", "L", "XL", "XXL")

    private val MENU = mapOf(
        "XS" to "normal",
        "S" to "tall",
        "M" to "big",
        "L" to "big-2",
        "XL" to "big-3",
        "XXL" to "big-4",
    )

    private val NOTE = mapOf(
        "XS" to "normal",
        "S" to "normal",
        "M" to "normal",
        "L" to "big",     // = M
        "XL" to "big-2",  // = L
        "XXL" to "big-2", // one below XXL is XL, capped at L
    )

    /** DantSu font size for the menu-item line at [level] (falls back to the S default). */
    fun menu(level: String): String = MENU[level] ?: MENU["S"]!!

    /** DantSu font size for the note line at [level]. */
    fun note(level: String): String = NOTE[level] ?: NOTE["S"]!!
}
