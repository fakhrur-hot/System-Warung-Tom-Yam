package com.razstudio.pos.ui.viewmodels

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.local.MenuCategoryStore
import com.razstudio.pos.data.local.MenuDao
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
 * Loads a bundled menu preset from the assets/presets folder into Room, persists its category
 * order, pushes the snapshot to the backend, then performs a clean "soft reboot" so all
 * app state re-initialises from the freshly loaded menu.
 */
@HiltViewModel
class MenuPresetViewModel @Inject constructor(
    private val menuDao: MenuDao,
    private val apiClient: BackendGateway,
    private val categoryStore: MenuCategoryStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * Load the bundled sample menu and soft-restart the app on completion.
     *
     * The asset was named after one café and the function after it — a starter menu every café
     * inherits should not be, and could not be, that café's. The content is unchanged: a generic
     * Malaysian menu whose own `presetName` already read "Sample Menu".
     */
    fun loadSampleMenuPreset() {
        if (_loading.value) return
        _loading.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val root = JSONObject(readAsset("presets/sample-menu.json"))

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

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

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
