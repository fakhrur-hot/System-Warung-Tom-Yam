package com.razstudio.pos.data.promos

/**
 * Display-ready affiliate product after validation and URL processing.
 *
 * This is the final model rendered in the UI (table grid tiles, ambient display card).
 * All fields are guaranteed safe for display: the URL is always HTTPS with sub_id appended,
 * and the label always has a value (falling back to "Shopee pick" if the original name was blank).
 */
data class AffiliateProduct(
    /**
     * Room row id this product was read from, used to attribute impression/click counts back to
     * the right cached row. Empty when there is nothing to attribute to (e.g. a preview built
     * outside the normal cache-backed flow).
     */
    val id: String = "",
    /** Final affiliate URL with sub_id appended. Always HTTPS. */
    val url: String,
    /** Product image URL, or empty string when unavailable (UI shows fallback icon). */
    val imageUrl: String,
    /** Display label for the product tile. Never blank — falls back to "Shopee pick". */
    val label: String,
)
