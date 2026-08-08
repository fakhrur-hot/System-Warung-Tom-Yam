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

    /**
     * Print the device-local receipt logo inverted (see
     * [com.razstudio.pos.printing.ReceiptLogoStore]).
     *
     * A wordmark drawn light-on-dark is the normal way to design a logo and the worst possible
     * thing to hand a thermal head: the background becomes near-total ink coverage, which the head
     * cannot sustain, so it prints as black bands separated by white gutters. Inverting turns that
     * into a few strokes of ink on bare paper.
     *
     * Defaulted from the picked image at upload time rather than fixed here, because only the image
     * itself knows which way round it is — this stores the operator's answer, not a guess.
     */
    fun getReceiptLogoInvert(): Boolean = prefs.getBoolean(KEY_RECEIPT_LOGO_INVERT, false)

    fun setReceiptLogoInvert(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RECEIPT_LOGO_INVERT, enabled).apply()
    }

    /**
     * Cut the paper after a receipt prints. Device-local, and ON by default so every printer
     * that has a cutter keeps behaving as it always did.
     *
     * Off is for terminals with a **tear bar instead of a cutter**, which are common at this
     * price point and are not detectable: the D3 MINI accepts Sunmi's `cutPaper()` over the
     * AIDL and returns without error, having done nothing — there is no capability flag to
     * read, and no failure to catch. The visible cost is not the silent cut but the blank feed
     * that precedes it: three lines are fed to clear the print head before a cut that never
     * comes, on every receipt, forever. Turning this off reclaims them.
     */
    fun getReceiptAutoCut(): Boolean = prefs.getBoolean(KEY_RECEIPT_AUTO_CUT, true)

    fun setReceiptAutoCut(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RECEIPT_AUTO_CUT, enabled).apply()
    }

    /**
     * Master switch for the PHYSICAL drawer kick (cash-drawer-settings Requirement 1).
     *
     * OFF by default — most terminals at this price point have no drawer wired, and a kick pulse
     * sent to nothing is at best a no-op and at worst a Bluetooth printer beeping mid-service.
     * The switch gates only the solenoid pulse in `PrinterDispatcher.kickCashDrawer()`; the cash
     * LEDGER never consults it — sales, floats, and cash-outs are recorded identically either way
     * (Requirement 4), because money was still handled whether or not a drawer sprang open.
     */
    fun isCashDrawerEnabled(): Boolean = prefs.getBoolean(KEY_CASH_DRAWER_ENABLED, false)

    fun setCashDrawerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CASH_DRAWER_ENABLED, enabled).apply()
    }

    private companion object {
        const val KEY_KITCHEN_FONT = "kitchen_font_size"
        const val KEY_RECEIPT_LOGO = "receipt_logo"
        const val KEY_RECEIPT_LOGO_INVERT = "receipt_logo_invert"
        const val KEY_ESC_ASTERISK = "esc_asterisk_image_mode"
        const val KEY_RECEIPT_AUTO_CUT = "receipt_auto_cut"
        const val KEY_CASH_DRAWER_ENABLED = "cash_drawer_enabled"
        const val DEFAULT = "M"
    }
}

/**
 * Kitchen-slip menu-text size. Common 58mm thermal printers cap magnification at 2×, so we
 * expose three reliable sizes that all render on such hardware:
 *   S (Small)  = normal (1×1, the old default)
 *   M (Medium) = big    (2×2 — proportional, the readable default)
 *   L (Large)  = big-2  (3×3, for a busy pass or poor lighting)
 *
 * ## Why M is no longer `tall`
 *
 * `tall` doubles the HEIGHT and leaves the width alone, so every glyph is stretched into a narrow
 * column. On a thermal head that reads as spindly rather than large — kitchen staff reported the
 * menu line as thin and hard to read at a glance, which is the one thing a kitchen slip has to be.
 * `big` doubles both axes, so the text grows without distorting. It costs a little paper width,
 * which is the correct trade for a slip somebody reads across a hot pass.
 * The special-instruction note prints one step smaller than the menu text.
 */
object KitchenFontSize {
    val LEVELS = listOf("S", "M", "L")

    private val MENU = mapOf("S" to "normal", "M" to "big", "L" to "big-2")
    // The note stays one step below the menu line so the dish still reads first.
    private val NOTE = mapOf("S" to "normal", "M" to "tall", "L" to "big")

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
