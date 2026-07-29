package com.warungtomyam.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warungtomyam.pos.data.ApiClient
import com.warungtomyam.pos.data.ApiResult
import com.warungtomyam.pos.data.MenuImageUploadResponse
import com.warungtomyam.pos.data.local.MenuCategory
import com.warungtomyam.pos.data.local.MenuCategoryStore
import com.warungtomyam.pos.data.local.MenuDao
import com.warungtomyam.pos.data.local.MenuItem
import com.warungtomyam.pos.ui.i18n.LanguageManager
import com.warungtomyam.pos.ui.i18n.uiStrings
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
