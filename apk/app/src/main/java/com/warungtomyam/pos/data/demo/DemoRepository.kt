package com.warungtomyam.pos.data.demo

import com.warungtomyam.pos.data.local.MenuItem
import com.warungtomyam.pos.data.local.MenuDao
import com.warungtomyam.pos.data.local.Order
import com.warungtomyam.pos.data.local.OrderDao
import com.warungtomyam.pos.data.local.OrderItem
import com.warungtomyam.pos.data.local.OrderStatus
import com.warungtomyam.pos.data.local.Table
import com.warungtomyam.pos.data.local.TableDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A local-only repository that always obtains its DAOs from DemoDatabaseProvider,
 * so it always reads/writes the current active in-memory demo database instance.
 *
 * Previously, DAOs were injected at construction time via Hilt, which caused a
 * stale-reference bug: seeding wrote to a new DB instance created by reset(), but
 * the injected DAOs still held references to the old instance. This approach fixes
 * that by resolving DAOs lazily from the provider on every call.
 */
@Singleton
class DemoRepository @Inject constructor(
    private val provider: DemoDatabaseProvider
) {
    // Helper accessors — always returns DAOs from the current live DB instance
    private val menuDao: MenuDao get() = provider.getOrCreate().menuDao()
    private val orderDao: OrderDao get() = provider.getOrCreate().orderDao()
    private val tableDao: TableDao get() = provider.getOrCreate().tableDao()

    // --- Tables ---

    fun tablesFlow(): Flow<List<Table>> = tableDao.getAllFlow()

    suspend fun getTables(): List<Table> = tableDao.getAll()

    suspend fun addTable(table: Table) = tableDao.insert(table)

    suspend fun updateTable(table: Table) = tableDao.update(table)

    suspend fun deleteTable(tableId: String) = tableDao.delete(tableId)

    // --- Menu ---

    suspend fun getMenuItems(): List<MenuItem> = menuDao.getAll()

    fun getAllMenuFlow(): Flow<List<MenuItem>> = menuDao.getAllFlow()

    fun getAvailableMenuFlow(): Flow<List<MenuItem>> = menuDao.getAvailableFlow()

    suspend fun addMenuItem(item: MenuItem) = menuDao.upsertAll(listOf(item))

    suspend fun updateMenuItem(item: MenuItem) = menuDao.upsertAll(listOf(item))

    suspend fun deleteMenuItem(id: String) = menuDao.deleteById(id)

    // --- Orders ---

    fun activeOrdersFlow(): Flow<List<Order>> = orderDao.getActiveOrdersFlow()

    suspend fun getActiveOrderForTable(tableId: String): Order? =
        orderDao.getActiveOrderForTable(tableId)

    suspend fun createOrder(order: Order, items: List<OrderItem>) {
        orderDao.insertOrder(order)
        orderDao.insertOrderItems(items)
    }

    suspend fun sendToKitchen(orderId: String): List<OrderItem> {
        val order = orderDao.getOrderById(orderId)
            ?: throw IllegalArgumentException("Order not found")
        require(
            order.status == OrderStatus.RECEIVED ||
                order.status == OrderStatus.SENT_TO_KITCHEN
        ) {
            "Cannot send to kitchen from status: ${order.status}"
        }
        val unsentItems = orderDao.getUnsentItems(orderId)
        orderDao.markAllItemsSentToKitchen(orderId)
        orderDao.markSentToKitchen(orderId, currentTimestamp(), OrderStatus.SENT_TO_KITCHEN.name)
        return unsentItems
    }

    suspend fun processPayment(orderId: String, method: String) {
        val order = orderDao.getOrderById(orderId)
            ?: throw IllegalArgumentException("Order not found")
        require(
            order.status == OrderStatus.SENT_TO_KITCHEN ||
                order.status == OrderStatus.PREPARING ||
                order.status == OrderStatus.READY
        ) {
            "Payment only allowed after send-to-kitchen"
        }
        orderDao.completePayment(orderId, method)
    }

    suspend fun cancelOrder(orderId: String, reason: String) {
        val order = orderDao.getOrderById(orderId)
            ?: throw IllegalArgumentException("Order not found")
        require(
            order.status != OrderStatus.COMPLETED &&
                order.status != OrderStatus.CANCELLED
        ) {
            "Cannot cancel a completed/cancelled order"
        }
        orderDao.cancelOrder(orderId, reason, "DEMO_USER")
    }

    suspend fun addItemsToOrder(orderId: String, items: List<OrderItem>) {
        val order = orderDao.getOrderById(orderId)
            ?: throw IllegalArgumentException("Order not found")
        require(
            order.status != OrderStatus.COMPLETED &&
                order.status != OrderStatus.CANCELLED
        ) {
            "Cannot amend a terminal order"
        }
        orderDao.insertOrderItems(items)
        val allItems = orderDao.getItemsForOrder(orderId)
        val newTotal = allItems.sumOf { it.unitPriceSnapshot * it.quantity }
        orderDao.insertOrder(order.copy(total = newTotal))
    }

    // --- Reports ---

    suspend fun getCompletedOrderCount(since: String): Int =
        orderDao.getCompletedOrderCount(since)

    suspend fun getTotalRevenue(since: String): Double =
        orderDao.getTotalRevenue(since)

    suspend fun getAllOrders(): List<Order> = orderDao.getAllOrders()

    suspend fun getItemsForOrder(orderId: String): List<OrderItem> =
        orderDao.getItemsForOrder(orderId)

    // --- Utilities ---

    private fun currentTimestamp(): String =
        java.time.Instant.now().toString()
}
