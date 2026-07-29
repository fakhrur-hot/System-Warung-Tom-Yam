package com.warungtomyam.pos.util

import org.json.JSONObject

/**
 * Normalizes an item name for display/printing. New orders store `nameSnapshot` as a plain
 * string, but legacy orders (placed before the backend `extractDisplayName` fix) stored the
 * whole localized-name object as a JSON string, e.g.
 * `{"bm":"Nasi Putih","en":"Nasi Putih","doNotTranslate":true}`. This unwraps such a blob to
 * a single readable name (prefers `en`, else the first non-empty locale) so no screen or
 * receipt ever shows raw JSON. Plain strings pass through untouched.
 */
object MenuName {
    private val LOCALE_KEYS = listOf("en", "bm", "zh", "ta", "th")

    fun display(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return raw
        return try {
            val json = JSONObject(trimmed)
            LOCALE_KEYS.firstNotNullOfOrNull { key ->
                json.optString(key, "").takeIf { it.isNotBlank() }
            } ?: raw
        } catch (_: Exception) {
            raw
        }
    }
}
