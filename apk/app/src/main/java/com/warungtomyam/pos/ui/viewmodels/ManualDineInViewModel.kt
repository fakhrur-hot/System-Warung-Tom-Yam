package com.warungtomyam.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warungtomyam.pos.data.ApiClient
import com.warungtomyam.pos.data.ApiResult
import com.warungtomyam.pos.data.NewOrderItem
import com.warungtomyam.pos.data.local.MenuDao
import com.warungtomyam.pos.data.local.OrderDao
import com.warungtomyam.pos.data.local.TableDao
import com.warungtomyam.pos.ui.i18n.LanguageManager
import com.warungtomyam.pos.ui.i18n.uiStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Manual Dine-In entry screen.
 * Manages the table → menu → cart → submit flow (source: STAFF).
 */
@HiltViewModel
class ManualDineInViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val menuDao: MenuDao,
    private val orderDao: OrderDao,
    private val tableDao: TableDao,
    private val languageManager: LanguageManager
) : ViewModel() {

    private fun str() = uiStrings(languageManager.language.value)

    enum class Step {
        SELECT_TABLE, SELECT_ITEMS, CART_REVIEW
    }

    data class TableItem(
        val id: String,
        val label: String,
        val isFree: Boolean
    )

    data class MenuItemDisplay(
        val id: String,
        val name: String,
        val price: Double,
        val category: String,
        /** All categories this item shows under (primary + "also show in" extras). */
        val categories: List<String> = emptyList(),
        val available: Boolean,
        val imageUrl: String = "",
        val marketPrice: Boolean = false,
        /** Small/Medium/Large item: offer the three sizes separately when ordering. */
        val hasVariablePrice: Boolean = false,
        val priceOption1: Double? = null,
        val priceOption2: Double? = null,
        val priceOption3: Double? = null,
    )

    data class CartItem(
        val menuItemId: String,
        val quantity: Int,
        val note: String? = null,
        /** Size label ("S"/"M"/"L") + chosen price for a variable-price item; null otherwise. */
        val size: String? = null,
        val unitPrice: Double? = null,
    )

    data class UiState(
        val step: Step = Step.SELECT_TABLE,
        val tables: List<TableItem> = emptyList(),
        val menuItems: List<MenuItemDisplay> = emptyList(),
        val cartItems: List<CartItem> = emptyList(),
        val selectedTableId: String? = null,
        val selectedTableLabel: String = "",
        val isLoading: Boolean = false,
        val isSubmitting: Boolean = false,
        val holdRemaining: Int? = null,
        val orderSubmitted: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var orderHoldJob: kotlinx.coroutines.Job? = null

    companion object {
        // Fixed pre-send hold for admin/staff dine-in orders (mis-tap guard).
        const val STAFF_ORDER_HOLD_SECONDS = 3
    }

    init {
        loadTables()
    }

    private fun loadTables() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                // Get all tables from Room
                val tables = tableDao.getAll()
                // Get active orders to determine which tables are occupied
                val activeOrders = orderDao.getActiveOrders()
                val occupiedTableIds = activeOrders.map { it.tableId }.toSet()

                val tableItems = tables.map { table ->
                    TableItem(
                        id = table.id,
                        label = table.label,
                        isFree = table.id !in occupiedTableIds
                    )
                }
                _uiState.value = _uiState.value.copy(
                    tables = tableItems,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = str().msgFailed.format(e.message)
                )
            }
        }
    }

    fun selectTable(tableId: String) {
        val table = _uiState.value.tables.find { it.id == tableId } ?: return
        _uiState.value = _uiState.value.copy(
            selectedTableId = tableId,
            selectedTableLabel = table.label,
            step = Step.SELECT_ITEMS
        )
        loadMenuItems()
    }

    private fun loadMenuItems() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val items = menuDao.getAll()
                val displayItems = items
                    .filter { it.available }
                    .map { item ->
                        MenuItemDisplay(
                            id = item.id,
                            name = item.nameEn,
                            price = item.price,
                            category = item.category,
                            categories = item.allCategories(),
                            available = item.available,
                            imageUrl = item.imageUrl,
                            marketPrice = item.marketPrice,
                            hasVariablePrice = item.hasVariablePrice,
                            priceOption1 = item.priceOption1,
                            priceOption2 = item.priceOption2,
                            priceOption3 = item.priceOption3,
                        )
                    }
                _uiState.value = _uiState.value.copy(
                    menuItems = displayItems,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = str().msgFailed.format(e.message)
                )
            }
        }
    }

    fun addToCart(menuItemId: String) {
        val currentCart = _uiState.value.cartItems.toMutableList()
        // Only the plain (no-size) line for this item — sized lines are managed via [addSized].
        val existing = currentCart.find { it.menuItemId == menuItemId && it.size == null }
        if (existing != null) {
            val index = currentCart.indexOf(existing)
            currentCart[index] = existing.copy(quantity = existing.quantity + 1)
        } else {
            currentCart.add(CartItem(menuItemId = menuItemId, quantity = 1))
        }
        _uiState.value = _uiState.value.copy(cartItems = currentCart)
    }

    /** Add one unit of a specific Small/Medium/Large size (its own cart line). */
    fun addSized(menuItemId: String, size: String, unitPrice: Double) {
        val currentCart = _uiState.value.cartItems.toMutableList()
        val idx = currentCart.indexOfFirst { it.menuItemId == menuItemId && it.size == size }
        if (idx >= 0) {
            currentCart[idx] = currentCart[idx].copy(quantity = currentCart[idx].quantity + 1)
        } else {
            currentCart.add(CartItem(menuItemId = menuItemId, quantity = 1, size = size, unitPrice = unitPrice))
        }
        _uiState.value = _uiState.value.copy(cartItems = currentCart)
    }

    fun removeFromCart(menuItemId: String) {
        val currentCart = _uiState.value.cartItems.toMutableList()
        val existing = currentCart.find { it.menuItemId == menuItemId && it.size == null } ?: return
        if (existing.quantity > 1) {
            val index = currentCart.indexOf(existing)
            currentCart[index] = existing.copy(quantity = existing.quantity - 1)
        } else {
            currentCart.remove(existing)
        }
        _uiState.value = _uiState.value.copy(cartItems = currentCart)
    }

    /** Remove one unit of a specific size line (for the size steppers on variable-price items). */
    fun removeSized(menuItemId: String, size: String) {
        val currentCart = _uiState.value.cartItems.toMutableList()
        val idx = currentCart.indexOfFirst { it.menuItemId == menuItemId && it.size == size }
        if (idx < 0) return
        val line = currentCart[idx]
        if (line.quantity > 1) currentCart[idx] = line.copy(quantity = line.quantity - 1)
        else currentCart.removeAt(idx)
        _uiState.value = _uiState.value.copy(cartItems = currentCart)
    }

    fun updateNote(menuItemId: String, note: String) {
        val currentCart = _uiState.value.cartItems.toMutableList()
        val existing = currentCart.find { it.menuItemId == menuItemId } ?: return
        val index = currentCart.indexOf(existing)
        currentCart[index] = existing.copy(note = note.ifBlank { null })
        _uiState.value = _uiState.value.copy(cartItems = currentCart)
    }

    /**
     * Set a special instruction for an item straight from the menu list. Adds the item to
     * the cart (qty 1) if it isn't there yet, so a note typed "under the menu item" sticks;
     * clearing the note keeps the item in the cart.
     */
    fun setItemNote(menuItemId: String, note: String) {
        val currentCart = _uiState.value.cartItems.toMutableList()
        val n = note.ifBlank { null }
        val idx = currentCart.indexOfFirst { it.menuItemId == menuItemId }
        if (idx >= 0) {
            currentCart[idx] = currentCart[idx].copy(note = n)
        } else if (n != null) {
            currentCart.add(CartItem(menuItemId = menuItemId, quantity = 1, note = n))
        }
        _uiState.value = _uiState.value.copy(cartItems = currentCart)
    }

    fun goToTableSelect() {
        _uiState.value = _uiState.value.copy(step = Step.SELECT_TABLE)
    }

    fun goToMenuSelect() {
        _uiState.value = _uiState.value.copy(step = Step.SELECT_ITEMS)
    }

    fun goToCartReview() {
        _uiState.value = _uiState.value.copy(step = Step.CART_REVIEW)
    }

    /** Cancel the pre-send hold — nothing is created; the cart stays for editing. */
    fun cancelSubmitHold() {
        orderHoldJob?.cancel()
        orderHoldJob = null
        _uiState.value = _uiState.value.copy(holdRemaining = null, isSubmitting = false)
    }

    fun submitOrder() {
        val state = _uiState.value
        val tableId = state.selectedTableId ?: return
        if (state.cartItems.isEmpty()) return
        if (orderHoldJob?.isActive == true) return

        val items = state.cartItems.map { cartItem ->
            NewOrderItem(
                menuItemId = cartItem.menuItemId,
                quantity = cartItem.quantity,
                note = cartItem.note,
                unitPrice = cartItem.unitPrice,
                size = cartItem.size
            )
        }

        orderHoldJob = viewModelScope.launch {
            for (s in STAFF_ORDER_HOLD_SECONDS downTo 1) {
                _uiState.value = _uiState.value.copy(holdRemaining = s, error = null)
                kotlinx.coroutines.delay(1000)
            }
            _uiState.value = _uiState.value.copy(holdRemaining = null, isSubmitting = true)

            // If the table already has an active order (occupied), append to it; otherwise
            // create a new order. This lets a New Dine-In order top up an occupied table.
            val existingOrder = orderDao.getActiveOrderForTable(tableId)
            val result = if (existingOrder != null) {
                apiClient.addItemsToOrder(existingOrder.id, items)
            } else {
                apiClient.createOrder(tableId, items, "STAFF")
            }
            when (result) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        orderSubmitted = true
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        error = result.message
                    )
                }
                is ApiResult.NetworkError -> {
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        error = str().msgNetworkError.format(result.message)
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
