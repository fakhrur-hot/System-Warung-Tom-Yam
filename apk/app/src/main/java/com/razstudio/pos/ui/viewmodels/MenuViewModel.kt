package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.MenuImageUploadResponse
import com.razstudio.pos.data.local.MenuCategory
import com.razstudio.pos.data.local.MenuCategoryStore
import com.razstudio.pos.data.local.MenuDao
import com.razstudio.pos.data.local.MenuItem
import com.razstudio.pos.ui.i18n.LanguageManager
import com.razstudio.pos.ui.i18n.uiStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

data class MenuUiState(
    val items: List<MenuItem> = emptyList(),
    /** Persisted ordered category names (from [MenuCategoryStore]); may be empty. */
    val categoryOrder: List<String> = emptyList(),
    /** Currently selected category NAME. Blank means "first available". */
    val selectedCategory: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSyncing: Boolean = false,
    /** Per-category display labels by language ("en".."th"), for the category editor. */
    val categoryTranslations: Map<String, Map<String, String>> = emptyMap()
) {
    /**
     * Dynamic ordered category names to render as tabs: persisted store order first, then any
     * categories present in items but not yet in the store, else the legacy 4 enum names.
     */
    val categories: List<String>
        get() {
            val ordered = LinkedHashSet<String>()
            ordered.addAll(categoryOrder)
            ordered.addAll(items.flatMap { it.allCategories() }.filter { it.isNotBlank() })
            return if (ordered.isNotEmpty()) ordered.toList()
            else MenuCategory.entries.map { it.name }
        }

    /** Resolves a blank selection to the first available category. */
    val effectiveSelectedCategory: String
        get() = selectedCategory.ifBlank { categories.firstOrNull() ?: "" }

    // An item appears under every category in its allCategories() (primary + extras).
    val itemsByCategory: Map<String, List<MenuItem>>
        get() = buildMap<String, MutableList<MenuItem>> {
            for (item in items) {
                for (cat in item.allCategories()) getOrPut(cat) { mutableListOf() }.add(item)
            }
        }

    val filteredItems: List<MenuItem>
        get() = itemsByCategory[effectiveSelectedCategory] ?: emptyList()
}

@HiltViewModel
class MenuViewModel @Inject constructor(
    private val menuDao: MenuDao,
    private val apiClient: ApiClient,
    private val modeRepository: com.razstudio.pos.data.ModeRepository,
    private val localImageStore: com.razstudio.pos.data.local.LocalImageStore,
    private val categoryStore: MenuCategoryStore,
    private val languageManager: LanguageManager
) : ViewModel() {

    private fun str() = uiStrings(languageManager.language.value)

    private val _uiState = MutableStateFlow(MenuUiState())
    val uiState: StateFlow<MenuUiState> = _uiState.asStateFlow()

    init {
        // Observe Room for live updates instead of a one-shot load. Re-read the (cheap,
        // SharedPreferences-backed) category order on each emit so externally-synced
        // category changes are reflected here too.
        viewModelScope.launch {
            try {
                menuDao.getAllFlow().collect { items ->
                    _uiState.update {
                        it.copy(
                            items = items,
                            categoryOrder = categoryStore.get(),
                            categoryTranslations = categoryStore.getTranslations(),
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = str().msgFailed.format(e.message)) }
            }
        }
    }

    /** Current kitchen route for [category]: "FOOD" (default) or "BEVERAGE". */
    fun routeForCategory(category: String): String = categoryStore.getCategoryRoute(category)

    /**
     * Save a category's per-language labels and its kitchen route (Food/Beverage), then
     * re-publish the menu so the customer web picks up the translations. The route drives
     * which of the two kitchen slips this category's items print on.
     */
    fun saveCategory(name: String, labels: Map<String, String>, route: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            categoryStore.setTranslation(name, labels)
            categoryStore.setCategoryRoute(name, route)
            _uiState.update { it.copy(categoryTranslations = categoryStore.getTranslations()) }
            pushMenuToBackend()
        }
    }

    /**
     * Move a category up or down in the shared order, then persist + re-publish the menu so
     * the new order drives the tabs here, the admin/staff ordering screens, and the customer
     * web (which sort by the snapshot's category sortOrder). Operates on the full effective
     * category list so every category ends up with an explicit position.
     */
    fun moveCategory(name: String, up: Boolean) {
        viewModelScope.launch {
            val current = _uiState.value.categories.toMutableList()
            val idx = current.indexOf(name)
            if (idx < 0) return@launch
            val target = if (up) idx - 1 else idx + 1
            if (target !in current.indices) return@launch
            val tmp = current[idx]
            current[idx] = current[target]
            current[target] = tmp
            categoryStore.set(current)
            _uiState.update { it.copy(categoryOrder = categoryStore.get()) }
            pushMenuToBackend()
        }
    }

    /**
     * Auto-sort items: within each category (in the current category order), order by the numeric
     * part of the item code ascending, then by name (A–Z). Items without a code number fall last,
     * alphabetically. The resulting flat order is written to Room and re-published so it drives the
     * admin table view, New Dine-In, and the customer web (all render in snapshot-array order).
     */
    fun autoSortItems() {
        viewModelScope.launch {
            val all = menuDao.getAll()
            val comparator = compareBy<MenuItem>(
                { codeNumber(it.code) ?: Int.MAX_VALUE },
                { itemDisplayName(it).lowercase() }
            )
            val result = mutableListOf<MenuItem>()
            val seen = mutableSetOf<String>()
            val byPrimary = all.groupBy { it.category }
            for (cat in _uiState.value.categories) {
                byPrimary[cat]?.sortedWith(comparator)?.forEach { if (seen.add(it.id)) result.add(it) }
            }
            all.forEach { if (seen.add(it.id)) result.add(it) } // safety: anything uncategorized
            persistItemOrder(result)
        }
    }

    /**
     * Manually move [itemId] up/down within the currently-selected category tab. The category's
     * items are reordered in place within the global list (other categories keep their positions),
     * then persisted + re-published. Reflected on every ordering surface.
     */
    fun moveItem(itemId: String, up: Boolean) {
        viewModelScope.launch {
            val cat = _uiState.value.effectiveSelectedCategory
            val all = menuDao.getAll()
            val catIds = all.filter { it.allCategories().contains(cat) }.map { it.id }.toMutableList()
            val idx = catIds.indexOf(itemId)
            if (idx < 0) return@launch
            val target = if (up) idx - 1 else idx + 1
            if (target !in catIds.indices) return@launch
            catIds[idx] = catIds[target].also { catIds[target] = catIds[idx] }
            // Refill this category's slots in the global list from the reordered id queue.
            val queue = ArrayDeque(catIds)
            val byId = all.associateBy { it.id }
            val newGlobal = all.map { item ->
                if (item.allCategories().contains(cat)) byId.getValue(queue.removeFirst()) else item
            }
            persistItemOrder(newGlobal)
        }
    }

    /** Rewrite Room in [ordered] order (same deleteAll+upsertAll the sync uses) and re-publish. */
    private suspend fun persistItemOrder(ordered: List<MenuItem>) {
        menuDao.deleteAll()
        menuDao.upsertAll(ordered)
        loadItems()
        pushMenuToBackend()
    }

    /** Trailing digits of an item code as an Int (e.g. "A01"→1, "U07"→7); null when none. */
    private fun codeNumber(code: String): Int? =
        Regex("(\\d+)\\s*$").find(code.trim())?.groupValues?.get(1)?.toIntOrNull()

    private fun itemDisplayName(item: MenuItem): String = item.nameBm.ifBlank { item.nameEn }

    fun loadItems() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val items = menuDao.getAll()
                _uiState.update { it.copy(items = items, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = str().msgFailed.format(e.message), isLoading = false) }
            }
        }
    }

    fun selectCategory(category: String) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    /** Number of items whose PRIMARY category is [name] (they'd be permanently deleted with it). */
    fun itemCountInCategory(name: String): Int = _uiState.value.items.count { it.category == name }

    /**
     * Delete a category. Items whose PRIMARY category is this one are permanently removed
     * everywhere (including any other category where they were "also shown"). Items that merely
     * listed it as an extra ("also shown") keep existing — only that tag is dropped. The category
     * is removed from the shared store; if it was selected, selection moves to a remaining tab
     * (avoids a ScrollableTabRow out-of-bounds when the tab count shrinks). Re-published after.
     */
    fun deleteCategory(name: String) {
        viewModelScope.launch {
            val all = menuDao.getAll()
            // 1. Items owned by this category (primary) → gone entirely.
            all.filter { it.category == name }.forEach { menuDao.deleteById(it.id) }
            // 2. Items only "also shown" here → drop the extra tag, keep the item.
            all.filter { it.category != name && it.extraCategories.split(",").map { s -> s.trim() }.contains(name) }
                .forEach { item ->
                    val newExtras = item.extraCategories.split(",").map { it.trim() }
                        .filter { it.isNotBlank() && it != name }.joinToString(",")
                    menuDao.upsertAll(listOf(item.copy(extraCategories = newExtras)))
                }
            // 3. Remove from the shared store (order + translations).
            categoryStore.set(categoryStore.get().filter { it != name })
            categoryStore.setTranslations(categoryStore.getTranslations().filterKeys { it != name })
            // 4. Refresh + move selection off the deleted tab.
            loadItems()
            _uiState.update {
                val remaining = it.categories.filter { c -> c != name }
                it.copy(
                    categoryOrder = categoryStore.get(),
                    categoryTranslations = categoryStore.getTranslations(),
                    selectedCategory = if (it.selectedCategory == name) (remaining.firstOrNull() ?: "") else it.selectedCategory
                )
            }
            pushMenuToBackend()
        }
    }

    /**
     * Create a new (initially empty) category from Menu Management, persist it to the shared
     * category store, select its tab, and re-publish the menu so it appears everywhere (admin,
     * ordering staff, customer web). A duplicate name just selects the existing tab.
     */
    fun addCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            if (_uiState.value.categories.any { it.equals(trimmed, ignoreCase = true) }) {
                selectCategory(trimmed)
                return@launch
            }
            // Add + persist, but DON'T move the tab selection to the new last tab in the same
            // frame — ScrollableTabRow reads tabPositions[selectedIndex] before its positions
            // list has grown, which throws IndexOutOfBounds. The new tab simply appears at the
            // end and the admin taps it. (The list stays on the current selection, always valid.)
            ensureCategory(trimmed)
            pushMenuToBackend()
        }
    }

    /** Persist a (possibly new) category name into the ordered store and refresh state. */
    private fun ensureCategory(category: String) {
        if (category.isBlank()) return
        categoryStore.add(category)
        _uiState.update { it.copy(categoryOrder = categoryStore.get()) }
    }

    fun addItem(
        category: String,
        nameEn: String,
        price: Double,
        askMeDaily: Boolean,
        doNotTranslate: Boolean,
        code: String = "",
        marketPrice: Boolean = false,
        imageUrl: String = "",
        imagePath: String = "",
        hasVariablePrice: Boolean = false,
        variablePriceDailyPrompt: Boolean = false,
        priceOption1: Double? = null,
        priceOption2: Double? = null,
        priceOption3: Double? = null,
        nameBm: String = "",
        nameZh: String = "",
        nameTa: String = "",
        nameTh: String = "",
        extraCategories: String = "",
        id: String = UUID.randomUUID().toString()
    ) {
        viewModelScope.launch {
            ensureCategory(category)
            extraCategories.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { ensureCategory(it) }
            val newItem = MenuItem(
                id = id,
                category = category,
                extraCategories = extraCategories,
                code = code,
                price = price,
                marketPrice = marketPrice,
                available = true,
                askMeDaily = askMeDaily,
                imageUrl = imageUrl,
                imagePath = imagePath,
                nameEn = nameEn,
                nameBm = nameBm,
                nameZh = nameZh,
                nameTa = nameTa,
                nameTh = nameTh,
                doNotTranslate = doNotTranslate,
                hasVariablePrice = hasVariablePrice,
                variablePriceDailyPrompt = variablePriceDailyPrompt,
                priceOption1 = priceOption1,
                priceOption2 = priceOption2,
                priceOption3 = priceOption3
            )
            menuDao.upsertAll(listOf(newItem))
            loadItems()
            pushMenuToBackend()
        }
    }

    fun updateItem(
        id: String,
        category: String,
        nameEn: String,
        price: Double,
        askMeDaily: Boolean,
        doNotTranslate: Boolean,
        code: String = "",
        marketPrice: Boolean = false,
        imageUrl: String = "",
        imagePath: String = "",
        hasVariablePrice: Boolean = false,
        variablePriceDailyPrompt: Boolean = false,
        priceOption1: Double? = null,
        priceOption2: Double? = null,
        priceOption3: Double? = null,
        nameBm: String = "",
        nameZh: String = "",
        nameTa: String = "",
        nameTh: String = "",
        extraCategories: String = ""
    ) {
        viewModelScope.launch {
            ensureCategory(category)
            extraCategories.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { ensureCategory(it) }
            val updatedItem = MenuItem(
                id = id,
                category = category,
                extraCategories = extraCategories,
                code = code,
                price = price,
                marketPrice = marketPrice,
                available = true,
                askMeDaily = askMeDaily,
                imageUrl = imageUrl,
                imagePath = imagePath,
                nameEn = nameEn,
                nameBm = nameBm,
                nameZh = nameZh,
                nameTa = nameTa,
                nameTh = nameTh,
                doNotTranslate = doNotTranslate,
                hasVariablePrice = hasVariablePrice,
                variablePriceDailyPrompt = variablePriceDailyPrompt,
                priceOption1 = priceOption1,
                priceOption2 = priceOption2,
                priceOption3 = priceOption3
            )
            menuDao.upsertAll(listOf(updatedItem))
            loadItems()
            pushMenuToBackend()
        }
    }

    /**
     * Upload a client-prepared thumbnail (base64 JPEG) for [menuItemId]. On success,
     * deletes the previously-stored image at [previousImagePath] (best-effort — a
     * failure here doesn't block the new image from being used).
     */
    suspend fun uploadImage(
        menuItemId: String,
        imageBase64: String,
        previousImagePath: String?
    ): ApiResult<MenuImageUploadResponse> {
        // Task 11.3 / Requirement 7.3: off-cloud there is no Supabase Storage to upload to.
        //
        // This ViewModel injects ApiClient directly rather than BackendGateway, so without this
        // branch a LAN or Kiosk café would POST its menu photos at a cloud project it is not using —
        // failing if the URL was cleared on the mode switch, or worse, succeeding against a stale
        // one. The image is written to app-private storage instead, which is where LocalBackend
        // already serves menu images from, so both paths agree on where a photo lives.
        if (!modeRepository.currentCapabilities().cloudImageHosting) {
            return try {
                if (!previousImagePath.isNullOrBlank()) localImageStore.delete(previousImagePath)
                val stored = localImageStore.save(menuItemId, imageBase64, System.currentTimeMillis())
                ApiResult.Success(MenuImageUploadResponse(imageUrl = stored.url, path = stored.path))
            } catch (e: IllegalArgumentException) {
                ApiResult.Error("VALIDATION", e.message ?: "Image data could not be decoded")
            } catch (e: java.io.IOException) {
                ApiResult.Error("STORAGE_ERROR", e.message ?: "Could not write the image to storage")
            }
        }

        val result = apiClient.uploadMenuImage(menuItemId, imageBase64)
        if (result is ApiResult.Success && !previousImagePath.isNullOrBlank()) {
            apiClient.deleteMenuImage(previousImagePath)
        }
        return result
    }

    fun deleteItem(id: String) {
        viewModelScope.launch {
            menuDao.deleteById(id)
            loadItems()
            pushMenuToBackend()
        }
    }

    fun toggleAvailability(id: String) {
        viewModelScope.launch {
            val item = _uiState.value.items.find { it.id == id } ?: return@launch
            menuDao.updateAvailabilityOnly(id, !item.available)
            loadItems()
            pushMenuToBackend()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private suspend fun pushMenuToBackend() {
        _uiState.update { it.copy(isSyncing = true) }
        try {
            val allItems = menuDao.getAll()
            val jsonArray = JSONArray()
            allItems.forEach { item ->
                jsonArray.put(menuItemToJson(item))
            }
            val categoriesArray = buildCategoriesJson(categoryStore.get(), allItems, categoryStore.getTranslations())
            when (val result = apiClient.putMenu(jsonArray, categoriesArray)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isSyncing = false) }
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(error = str().msgFailed.format(result.message), isSyncing = false) }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(error = str().msgNetworkError.format(result.message), isSyncing = false) }
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(error = str().msgFailed.format(e.message), isSyncing = false) }
        }
    }

    companion object {
        /** Serialize a [MenuItem] to the backend menu-snapshot item JSON (incl. code + marketPrice). */
        fun menuItemToJson(item: MenuItem): JSONObject {
            val nameObj = JSONObject().apply {
                put("en", item.nameEn)
                put("bm", item.nameBm)
                put("zh", item.nameZh)
                put("ta", item.nameTa)
                put("th", item.nameTh)
                put("doNotTranslate", item.doNotTranslate)
            }
            return JSONObject().apply {
                put("id", item.id)
                put("category", item.category)
                // Full category membership so an item shows on multiple category pages.
                put("categories", JSONArray().apply { item.allCategories().forEach { put(it) } })
                put("code", item.code)
                put("price", item.price)
                put("marketPrice", item.marketPrice)
                put("available", item.available)
                put("image", item.imageUrl)
                put("askMeDaily", item.askMeDaily)
                put("hasVariablePrice", item.hasVariablePrice)
                put("variablePriceDailyPrompt", item.variablePriceDailyPrompt)
                item.priceOption1?.let { put("priceOption1", it) }
                item.priceOption2?.let { put("priceOption2", it) }
                item.priceOption3?.let { put("priceOption3", it) }
                put("name", nameObj)
            }
        }

        /**
         * Build the top-level `categories` array as [{name, sortOrder, nameI18n?}], from the
         * stored ordered names (falling back to distinct item categories in stable order).
         * [translations] carries each category's per-language labels so a menu save re-emits
         * them — otherwise saving the menu would wipe the customer-web category translations.
         */
        fun buildCategoriesJson(
            order: List<String>,
            items: List<MenuItem>,
            translations: Map<String, Map<String, String>> = emptyMap(),
        ): JSONArray {
            val ordered = LinkedHashSet<String>()
            ordered.addAll(order)
            ordered.addAll(items.map { it.category }.filter { it.isNotBlank() })
            val arr = JSONArray()
            ordered.forEachIndexed { index, name ->
                arr.put(JSONObject().apply {
                    put("name", name)
                    put("sortOrder", index)
                    val labels = translations[name]
                    if (!labels.isNullOrEmpty()) {
                        put("nameI18n", JSONObject().apply {
                            for ((lang, value) in labels) if (value.isNotBlank()) put(lang, value)
                        })
                    }
                })
            }
            return arr
        }
    }
}
