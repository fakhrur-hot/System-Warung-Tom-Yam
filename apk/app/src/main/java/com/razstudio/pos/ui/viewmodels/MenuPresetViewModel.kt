package com.razstudio.pos.ui.viewmodels

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.local.MenuCategoryStore
import com.razstudio.pos.data.local.MenuDao
import com.razstudio.pos.data.local.MenuPreset
import com.razstudio.pos.data.local.MenuPresetCatalog
import com.razstudio.pos.data.local.MenuItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Loads a starter menu into Room, persists its category order, pushes the snapshot to the backend,
 * then performs a clean "soft reboot" so all app state re-initialises from the new menu.
 *
 * Presets come from [MenuPresetCatalog] — bundled in `assets/presets/`, plus published ones in
 * Cloud Mode. This used to load a single hardcoded asset; a café choosing its own starting point is
 * the difference between a useful head start and one café's menu imposed on everybody.
 */
@HiltViewModel
class MenuPresetViewModel @Inject constructor(
    private val menuDao: MenuDao,
    private val apiClient: BackendGateway,
    private val categoryStore: MenuCategoryStore,
    private val catalog: MenuPresetCatalog,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _presets = MutableStateFlow<List<MenuPreset>>(emptyList())

    /**
     * Starter menus this device can offer. Empty until [refreshPresets] completes — remote entries
     * need a network round-trip, so the list cannot be built synchronously.
     */
    val presets: StateFlow<List<MenuPreset>> = _presets.asStateFlow()

    /** Non-null when a preset load failed, for the screen to surface. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    // After the properties above are initialised — refreshPresets() touches _presets.
    init { refreshPresets() }

    fun refreshPresets() {
        viewModelScope.launch {
            _presets.value = runCatching { catalog.list() }.getOrElse {
                android.util.Log.w("MenuPreset", "Could not list presets", it)
                emptyList()
            }
        }
    }

    /**
     * Replace the café's menu with [preset], then soft-restart so every screen re-reads it.
     *
     * **This is destructive** — the existing menu and category order are deleted first. That is
     * why the screen confirms before calling it, and why it is presented as a fresh-install
     * convenience rather than an import.
     *
     * A remote preset is fetched *before* anything is deleted: a download that fails must leave the
     * café's current menu exactly as it was, not wipe it and then discover there is nothing to
     * replace it with.
     */
    fun loadPreset(preset: MenuPreset) {
        if (_loading.value) return
        _loading.value = true
        viewModelScope.launch {
            val payload = runCatching { catalog.payload(preset) }.getOrElse { e ->
                android.util.Log.e("MenuPreset", "Preset '${preset.presetId}' could not be read", e)
                _error.value = preset.presetName
                _loading.value = false
                return@launch
            }
            withContext(Dispatchers.IO) {
                val root = JSONObject(payload)

                // Category order from the preset (sorted by sortOrder).
                val categoriesArray = root.optJSONArray("categories") ?: JSONArray()
                val categoryNames = buildList {
                    val pairs = mutableListOf<Pair<String, Int>>()
                    for (i in 0 until categoriesArray.length()) {
                        val c = categoriesArray.getJSONObject(i)
                        val name = c.optString("name", "")
                        if (name.isNotBlank()) pairs.add(name to c.optInt("sortOrder", i))
                    }
                    pairs.sortedBy { it.second }.forEach { add(it.first) }
                }

                // Items.
                val itemsArray = root.optJSONArray("items") ?: JSONArray()
                val items = buildList {
                    for (i in 0 until itemsArray.length()) {
                        val o = itemsArray.getJSONObject(i)
                        val name = o.optJSONObject("name") ?: JSONObject()
                        add(
                            MenuItem(
                                id = o.getString("id"),
                                category = o.optString("category", ""),
                                code = o.optString("code", ""),
                                price = o.optDouble("price", 0.0),
                                marketPrice = o.optBoolean("marketPrice", false),
                                available = o.optBoolean("available", true),
                                askMeDaily = o.optBoolean("askMeDaily", false),
                                imageUrl = o.optString("image", ""),
                                imagePath = "",
                                nameEn = name.optString("en", ""),
                                nameBm = name.optString("bm", ""),
                                nameZh = name.optString("zh", ""),
                                nameTa = name.optString("ta", ""),
                                nameTh = name.optString("th", ""),
                                doNotTranslate = name.optBoolean("doNotTranslate", false),
                                hasVariablePrice = o.optBoolean("hasVariablePrice", false),
                                variablePriceDailyPrompt = o.optBoolean("variablePriceDailyPrompt", false),
                                priceOption1 = if (o.isNull("priceOption1")) null else o.optDouble("priceOption1"),
                                priceOption2 = if (o.isNull("priceOption2")) null else o.optDouble("priceOption2"),
                                priceOption3 = if (o.isNull("priceOption3")) null else o.optDouble("priceOption3")
                            )
                        )
                    }
                }

                // Replace local menu + category order.
                menuDao.deleteAll()
                menuDao.upsertAll(items)
                categoryStore.set(categoryNames)

                // Push the full snapshot (items + categories, incl. code/marketPrice) to backend.
                val itemsJson = JSONArray()
                items.forEach { itemsJson.put(MenuViewModel.menuItemToJson(it)) }
                val categoriesJson = MenuViewModel.buildCategoriesJson(categoryNames, items)
                runCatching { apiClient.putMenu(itemsJson, categoriesJson) }
            }
            softRestart()
        }
    }

    /** Relaunch the app cleanly so all state re-initialises from the new menu. */
    private fun softRestart() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        Runtime.getRuntime().exit(0)
    }
}
