package com.razstudio.pos.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight persistence for the ordered list of menu category names and their
 * per-language display labels.
 *
 * Category identity is the raw name string (e.g. "SAYUR", "UDANG/SOTONG"). The order
 * here drives the tab order in Menu Management and is round-tripped with the backend
 * menu snapshot's `categories` array. When empty, callers fall back to the distinct
 * categories present in the loaded menu items.
 *
 * Translations map the canonical name to labels keyed by language ("en","bm","zh",
 * "ta","th"). They're what the customer website shows for the category tabs, so they
 * must survive a menu re-save — [buildCategoriesJson] re-emits them on every putMenu.
 */
@Singleton
class MenuCategoryStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("menu_categories_prefs", Context.MODE_PRIVATE)

    /** Ordered category names. Empty if never set. */
    fun get(): List<String> {
        val raw = prefs.getString(KEY_NAMES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i, "")
                    if (name.isNotBlank()) add(name)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Replace the ordered category names (de-duplicated, order preserved). */
    fun set(names: List<String>) {
        val ordered = LinkedHashSet<String>()
        names.forEach { if (it.isNotBlank()) ordered.add(it) }
        val arr = JSONArray()
        ordered.forEach { arr.put(it) }
        prefs.edit().putString(KEY_NAMES, arr.toString()).apply()
    }

    /** Append a category name if not already present, preserving existing order. */
    fun add(name: String) {
        if (name.isBlank()) return
        val current = get()
        if (current.contains(name)) return
        set(current + name)
    }

    /** All stored translations: canonical name → { lang → label }. */
    fun getTranslations(): Map<String, Map<String, String>> {
        val raw = prefs.getString(KEY_I18N, null) ?: return emptyMap()
        return try {
            val root = JSONObject(raw)
            buildMap {
                for (name in root.keys()) {
                    val obj = root.optJSONObject(name) ?: continue
                    val labels = buildMap<String, String> {
                        for (lang in LANGS) {
                            val v = obj.optString(lang, "")
                            if (v.isNotBlank()) put(lang, v)
                        }
                    }
                    if (labels.isNotEmpty()) put(name, labels)
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Labels for one category (may be empty). */
    fun getTranslation(name: String): Map<String, String> = getTranslations()[name] ?: emptyMap()

    /** Replace ALL translations (used when syncing the whole snapshot down). */
    fun setTranslations(map: Map<String, Map<String, String>>) {
        val root = JSONObject()
        for ((name, labels) in map) {
            if (name.isBlank() || labels.isEmpty()) continue
            val obj = JSONObject()
            for (lang in LANGS) labels[lang]?.takeIf { it.isNotBlank() }?.let { obj.put(lang, it) }
            if (obj.length() > 0) root.put(name, obj)
        }
        prefs.edit().putString(KEY_I18N, root.toString()).apply()
    }

    /** Update the labels for a single category, leaving the others untouched. */
    fun setTranslation(name: String, labels: Map<String, String>) {
        if (name.isBlank()) return
        val current = getTranslations().toMutableMap()
        val cleaned = labels.filterValues { it.isNotBlank() }
        if (cleaned.isEmpty()) current.remove(name) else current[name] = cleaned
        setTranslations(current)
    }

    // ── Kitchen print route (bucket) per category ────────────────────────────
    // Every category routes to one of two kitchen slips: "FOOD" (default) or "BEVERAGE".
    // Each customer order then prints at most two slips, one per bucket.

    private fun routes(): MutableMap<String, String> {
        val raw = prefs.getString(KEY_ROUTES, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(raw)
            buildMap<String, String> {
                for (name in obj.keys()) {
                    val v = obj.optString(name, "")
                    if (v == ROUTE_BEVERAGE || v == ROUTE_FOOD) put(name, v)
                }
            }.toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    /** The kitchen bucket for [name]: "FOOD" (default) or "BEVERAGE". */
    fun getCategoryRoute(name: String): String = routes()[name] ?: ROUTE_FOOD

    /** Assign [name] to a bucket. */
    fun setCategoryRoute(name: String, route: String) {
        if (name.isBlank()) return
        val current = routes()
        // Store BEVERAGE explicitly; FOOD is the default so we can drop it to stay compact.
        if (route == ROUTE_BEVERAGE) current[name] = ROUTE_BEVERAGE else current.remove(name)
        val obj = JSONObject()
        for ((k, v) in current) obj.put(k, v)
        prefs.edit().putString(KEY_ROUTES, obj.toString()).apply()
    }

    private companion object {
        const val KEY_NAMES = "category_names"
        const val KEY_I18N = "category_i18n"
        const val KEY_ROUTES = "category_routes"
        const val ROUTE_FOOD = "FOOD"
        const val ROUTE_BEVERAGE = "BEVERAGE"
        val LANGS = listOf("en", "bm", "zh", "ta", "th")
    }
}
