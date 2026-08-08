package com.razstudio.pos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a menu item.
 * Mirrors the backend menu item structure with multilingual name support.
 *
 * Pricing: most items use plain [price]. A "special" item with [hasVariablePrice] set
 * instead offers up to 3 admin-defined presets ([priceOption1]/[priceOption2]/[priceOption3],
 * entered once at creation, editable anytime); [price] always holds whichever preset is
 * currently active/effective — that's the only price customers or the website ever see.
 * When [variablePriceDailyPrompt] is on, the daily-availability popup asks the admin to
 * pick that day's active preset; when off, the admin instead changes it manually in
 * Menu Management.
 */
@Entity(tableName = "menu_items")
data class MenuItem(
    @PrimaryKey val id: String,
    /** Primary category (used for the order snapshot / kitchen routing bucket). */
    val category: String,
    /**
     * Additional categories this item also appears under, comma-separated. An item shows
     * on every page in [allCategories]. Empty = single-category (the common case).
     */
    val extraCategories: String = "",
    /** Optional short admin code shown on slips/receipts (e.g. "S01", "TY3"). Empty when unused. */
    val code: String = "",
    val price: Double,
    /**
     * Market-price ("harga pasaran") item: price is not fixed and is decided at the counter.
     * When true, [price] is 0 and the numeric price is not required/validated.
     */
    val marketPrice: Boolean = false,
    val available: Boolean,
    val askMeDaily: Boolean,
    val imageUrl: String = "",
    val imagePath: String = "", // Storage object path (e.g. "{id}-{ts}.jpg"), used to delete the old image on replacement
    val nameEn: String,
    val nameBm: String = "",
    val nameZh: String = "",
    val nameTa: String = "",
    val nameTh: String = "",
    val doNotTranslate: Boolean = false,
    val hasVariablePrice: Boolean = false,
    val variablePriceDailyPrompt: Boolean = false,
    val priceOption1: Double? = null,
    val priceOption2: Double? = null,
    val priceOption3: Double? = null,

    // ── NEW ──────────────────────────────────────────────────────────────
    /**
     * Meaningful only when [hasVariablePrice] is true: 2 (Small/Large, using
     * [priceOption1]/[priceOption3] — priceOption2 stays null) or 3 (Small/Medium/Large,
     * all three slots used). Stored explicitly rather than derived from which slots are
     * non-null, because a null [priceOption2] must unambiguously mean "2-tier item", not
     * "admin left Medium blank by mistake".
     */
    val priceTierCount: Int? = null,

    /** True only for a Beverage-routed category item that offers separate Hot/Cold pricing. */
    val hotColdEnabled: Boolean = false,

    /** Cold counterpart of [price], used only when [hotColdEnabled] && !hasVariablePrice. */
    val coldPrice: Double? = null,

    /** Cold counterparts of priceOption1/2/3, used only when [hotColdEnabled] && [hasVariablePrice]. */
    val coldPriceOption1: Double? = null,
    val coldPriceOption2: Double? = null,
    val coldPriceOption3: Double? = null
) {
    /** All categories this item belongs to: the primary [category] plus any [extraCategories]. */
    fun allCategories(): List<String> =
        (listOf(category) + extraCategories.split(",").map { it.trim() })
            .filter { it.isNotBlank() }
            .distinct()
}
