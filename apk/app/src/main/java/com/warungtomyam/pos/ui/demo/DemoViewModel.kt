package com.warungtomyam.pos.ui.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warungtomyam.pos.data.demo.DemoDatabaseProvider
import com.warungtomyam.pos.data.demo.DemoRepository
import com.warungtomyam.pos.data.demo.DemoSeedData
import com.warungtomyam.pos.data.local.MenuItem
import com.warungtomyam.pos.data.local.Order
import com.warungtomyam.pos.data.local.OrderItem
import com.warungtomyam.pos.data.local.OrderStatus
import com.warungtomyam.pos.data.local.Table
import com.warungtomyam.pos.printing.PrintService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds computed report metrics for the demo reports screen.
 */
data class DemoReportState(
    val totalCompletedOrders: Int = 0,
    val totalRevenue: Double = 0.0,
    val averageOrderValue: Double = 0.0,
    val allOrders: List<Order> = emptyList()
)

/**
 * State for the table management dialog in demo mode.
 */
data class DemoTableManagementState(
    val isVisible: Boolean = false,
    val newTableId: String = "",
    val newTableLabel: String = "",
    val editingTable: Table? = null,
    val editLabel: String = ""
)

@HiltViewModel
class DemoViewModel @Inject constructor(
    private val demoRepository: DemoRepository,
    private val printService: PrintService,
    private val demoDatabaseProvider: DemoDatabaseProvider
) : ViewModel() {

    private val _tables = MutableStateFlow<List<Table>>(emptyList())
    val tables: StateFlow<List<Table>> = _tables.asStateFlow()

    private val _activeOrders = MutableStateFlow<List<Order>>(emptyList())
    val activeOrders: StateFlow<List<Order>> = _activeOrders.asStateFlow()

    private val _menuItems = MutableStateFlow<List<MenuItem>>(emptyList())
    val menuItems: StateFlow<List<MenuItem>> = _menuItems.asStateFlow()

    private val _allMenuItems = MutableStateFlow<List<MenuItem>>(emptyList())
    val allMenuItems: StateFlow<List<MenuItem>> = _allMenuItems.asStateFlow()

    private val _printError = MutableSharedFlow<String>()
    val printError: SharedFlow<String> = _printError.asSharedFlow()

    private val _uiError = MutableSharedFlow<String>()
    val uiError: SharedFlow<String> = _uiError.asSharedFlow()

    private val _walkthroughStep = MutableStateFlow<Int?>(1)
    val walkthroughStep: StateFlow<Int?> = _walkthroughStep.asStateFlow()

    private val _reportState = MutableStateFlow(DemoReportState())
    val reportState: StateFlow<DemoReportState> = _reportState.asStateFlow()

    private val _tableManagement = MutableStateFlow(DemoTableManagementState())
    val tableManagement: StateFlow<DemoTableManagementState> = _tableManagement.asStateFlow()

    init {
        initSession()
    }

    fun initSession() {
        viewModelScope.launch {
            // Discard any previously existing demo database (Requirement 2.4)
            demoDatabaseProvider.reset()
            // Create a fresh in-memory database instance
            val db = demoDatabaseProvider.getOrCreate()
            // Seed with deterministic demo data (Requirement 1.2)
            DemoSeedData.seed(db)

            launch {
                demoRepository.tablesFlow()
                    .catch { emit(emptyList()) }
                    .collect { _tables.value = it }
            }

            launch {
                demoRepository.activeOrdersFlow()
                    .catch { emit(emptyList()) }
                    .collect { _activeOrders.value = it }
            }

            launch {
                demoRepository.getAvailableMenuFlow()
                    .catch { emit(emptyList()) }
                    .collect { _menuItems.value = it }
            }

            launch {
                demoRepository.getAllMenuFlow()
                    .catch { emit(emptyList()) }
                    .collect { _allMenuItems.value = it }
            }
        }
    }

    // --- Walkthrough ---

    fun advanceWalkthrough() {
        val current = _walkthroughStep.value
        if (current != null && current < 3) {
            _walkthroughStep.value = current + 1
        } else {
            _walkthroughStep.value = null
        }
    }

    fun skipWalkthrough() {
        _walkthroughStep.value = null
    }

    fun dismissWalkthrough() {
        _walkthroughStep.value = null
    }

    // --- Reports ---

    fun loadReports() {
        viewModelScope.launch {
            try {
                val allOrders = demoRepository.getAllOrders()
                val completedOrders = allOrders.filter { it.status == OrderStatus.COMPLETED }
                val totalCompleted = completedOrders.size
                val totalRevenue = completedOrders.sumOf { it.total }
                val avgOrderValue = if (totalCompleted > 0) totalRevenue / totalCompleted else 0.0

                _reportState.value = DemoReportState(
                    totalCompletedOrders = totalCompleted,
                    totalRevenue = totalRevenue,
                    averageOrderValue = avgOrderValue,
                    allOrders = allOrders
                )
            } catch (e: Exception) {
                _uiError.emit(e.message ?: "Failed to load reports")
            }
        }
    }

    // --- Order Operations ---

    /** Load the line items for an order (used by the receipt-style detail sheet). */
    suspend fun itemsForOrder(orderId: String): List<OrderItem> =
        demoRepository.getItemsForOrder(orderId)

    fun createOrder(order: Order, items: List<OrderItem>) {
        viewModelScope.launch {
            try {
                demoRepository.createOrder(order, items)
            } catch (e: Exception) {
                _uiError.emit(e.message ?: "Failed to create order")
            }
        }
    }

    fun sendToKitchen(orderId: String, tableId: String) {
        viewModelScope.launch {
            try {
                // If any item was already sent before this call, this is a delta send
                // (items added via the + button after the initial send) — print it as
                // an amendment slip rather than a fresh kitchen ticket.
                val hadSentItems = demoRepository.getItemsForOrder(orderId).any { it.sentToKitchen }
                val unsentItems = demoRepository.sendToKitchen(orderId)
                if (unsentItems.isNotEmpty()) {
                    printKitchenSlipSafely(tableId, unsentItems, isAmendment = hadSentItems)
                }
            } catch (e: Exception) {
                _uiError.emit(e.message ?: "Failed to send to kitchen")
            }
        }
    }

    fun processPayment(orderId: String, method: String, order: Order, cafeName: String) {
        viewModelScope.launch {
            try {
                demoRepository.processPayment(orderId, method)
                val items = demoRepository.getItemsForOrder(orderId)
                printReceiptSafely(order, items, method, cafeName)
            } catch (e: Exception) {
                _uiError.emit(e.message ?: "Failed to process payment")
            }
        }
    }

    fun cancelOrder(orderId: String, reason: String) {
        viewModelScope.launch {
            try {
                demoRepository.cancelOrder(orderId, reason)
            } catch (e: Exception) {
                _uiError.emit(e.message ?: "Failed to cancel order")
            }
        }
    }

    /**
     * Stage new items on an existing order. This does NOT send to kitchen or print —
     * items are added as unsent ("New Order" section in the detail sheet) until
     * [sendToKitchen] is explicitly pressed, matching the live admin/staff behavior.
     */
    fun addItemsToOrder(orderId: String, tableId: String, items: List<OrderItem>) {
        viewModelScope.launch {
            try {
                demoRepository.addItemsToOrder(orderId, items)
            } catch (e: Exception) {
                _uiError.emit(e.message ?: "Failed to add items to order")
            }
        }
    }

    // --- Menu Operations ---

    fun addMenuItem(item: MenuItem) {
        viewModelScope.launch {
            try {
                demoRepository.addMenuItem(item)
            } catch (e: Exception) {
                _uiError.emit(e.message ?: "Failed to add menu item")
            }
        }
    }

    fun editMenuItem(item: MenuItem) {
        viewModelScope.launch {
            try {
                demoRepository.updateMenuItem(item)
            } catch (e: Exception) {
                _uiError.emit(e.message ?: "Failed to update menu item")
            }
        }
    }

    fun deleteMenuItem(id: String) {
        viewModelScope.launch {
            try {
                demoRepository.deleteMenuItem(id)
            } catch (e: Exception) {
                _uiError.emit(e.message ?: "Failed to delete menu item")
            }
        }
    }

    fun toggleMenuItemAvailability(item: MenuItem) {
        viewModelScope.launch {
            try {
                demoRepository.updateMenuItem(item.copy(available = !item.available))
            } catch (e: Exception) {
                _uiError.emit(e.message ?: "Failed to toggle availability")
            }
        }
    }

    // --- Printing Error Wrappers ---

    fun printKitchenSlipSafely(tableId: String, items: List<OrderItem>, isAmendment: Boolean) {
        viewModelScope.launch {
            try {
                printService.printKitchenSlip(tableId, items, isAmendment)
            } catch (e: Exception) {
                _printError.emit("Demo Mode: Printer error when printing kitchen slip (${e.message ?: "No printer connected"})")
            }
        }
    }

    fun printReceiptSafely(order: Order, items: List<OrderItem>, paymentMethod: String, cafeName: String) {
        viewModelScope.launch {
            try {
                printService.printReceipt(order, items, paymentMethod, cafeName)
            } catch (e: Exception) {
                _printError.emit("Demo Mode: Printer error when printing receipt (${e.message ?: "No printer connected"})")
            }
        }
    }

    // --- Table Management ---

    fun showTableManagement() {
        _tableManagement.value = _tableManagement.value.copy(isVisible = true)
    }

    fun hideTableManagement() {
        _tableManagement.value = DemoTableManagementState()
    }

    fun updateNewTableId(id: String) {
        _tableManagement.value = _tableManagement.value.copy(newTableId = id)
    }

    fun updateNewTableLabel(label: String) {
        _tableManagement.value = _tableManagement.value.copy(newTableLabel = label)
    }

    fun addTable() {
        val state = _tableManagement.value
        if (state.newTableId.isBlank()) return
        viewModelScope.launch {
            try {
                val label = state.newTableLabel.ifBlank { state.newTableId }
                val nextSort = (_tables.value.maxOfOrNull { it.sortOrder } ?: 0) + 1
                demoRepository.addTable(Table(id = state.newTableId, label = label, sortOrder = nextSort))
                _tableManagement.value = _tableManagement.value.copy(newTableId = "", newTableLabel = "")
            } catch (e: Exception) {
                _uiError.emit(e.message ?: "Failed to add table")
            }
        }
    }

    fun startEditTable(table: Table) {
        _tableManagement.value = _tableManagement.value.copy(editingTable = table, editLabel = table.label)
    }

    fun updateEditLabel(label: String) {
        _tableManagement.value = _tableManagement.value.copy(editLabel = label)
    }

    fun cancelEdit() {
        _tableManagement.value = _tableManagement.value.copy(editingTable = null, editLabel = "")
    }

    fun saveEditTable() {
        val state = _tableManagement.value
        val table = state.editingTable ?: return
        viewModelScope.launch {
            try {
                demoRepository.updateTable(table.copy(label = state.editLabel))
                _tableManagement.value = _tableManagement.value.copy(editingTable = null, editLabel = "")
            } catch (e: Exception) {
                _uiError.emit(e.message ?: "Failed to rename table")
            }
        }
    }

    fun deleteTable(tableId: String) {
        viewModelScope.launch {
            try {
                demoRepository.deleteTable(tableId)
            } catch (e: Exception) {
                _uiError.emit(e.message ?: "Failed to delete table")
            }
        }
    }

    // --- Demo Exit ---

    fun exitDemo() {
        viewModelScope.launch {
            demoDatabaseProvider.destroy()
        }
    }
}
