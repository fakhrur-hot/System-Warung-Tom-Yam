package com.razstudio.pos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for orders and order items.
 */
@Dao
interface OrderDao {

    @Query("SELECT * FROM orders ORDER BY createdAt DESC")
    suspend fun getAllOrders(): List<Order>

    @Query("SELECT * FROM orders WHERE status NOT IN ('COMPLETED', 'CANCELLED') ORDER BY createdAt DESC")
    fun getActiveOrdersFlow(): Flow<List<Order>>

    @Query("SELECT * FROM orders WHERE id = :orderId")
    suspend fun getOrderById(orderId: String): Order?

    @Query("SELECT * FROM orders WHERE status NOT IN ('COMPLETED', 'CANCELLED') ORDER BY createdAt DESC")
    suspend fun getActiveOrders(): List<Order>

    @Query("SELECT * FROM orders WHERE tableId = :tableId AND status NOT IN ('COMPLETED', 'CANCELLED')")
    suspend fun getActiveOrderForTable(tableId: String): Order?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: Order)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrders(orders: List<Order>)

    @Query("UPDATE orders SET status = :status WHERE id = :orderId")
    suspend fun updateOrderStatus(orderId: String, status: String)

    @Query("UPDATE orders SET sentToKitchenAt = :timestamp, status = :status WHERE id = :orderId")
    suspend fun markSentToKitchen(orderId: String, timestamp: String, status: String)

    @Query("UPDATE orders SET paymentMethod = :method, status = 'COMPLETED' WHERE id = :orderId")
    suspend fun completePayment(orderId: String, method: String)

    @Query("UPDATE orders SET status = 'CANCELLED', cancelReason = :reason, cancelledBy = :cancelledBy WHERE id = :orderId")
    suspend fun cancelOrder(orderId: String, reason: String, cancelledBy: String)

    @Query("DELETE FROM orders WHERE id = :orderId")
    suspend fun deleteOrder(orderId: String)

    @Query("DELETE FROM orders")
    suspend fun deleteAllOrders()

    @Query("DELETE FROM order_items")
    suspend fun deleteAllOrderItems()

    @Query("SELECT * FROM order_items")
    suspend fun getAllOrderItems(): List<OrderItem>

    // --- Order Items ---

    @Query("SELECT * FROM order_items WHERE orderId = :orderId")
    suspend fun getItemsForOrder(orderId: String): List<OrderItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrderItems(items: List<OrderItem>)

    @Query("UPDATE order_items SET sentToKitchen = 1 WHERE orderId = :orderId AND sentToKitchen = 0")
    suspend fun markAllItemsSentToKitchen(orderId: String)

    @Query("SELECT * FROM order_items WHERE orderId = :orderId AND sentToKitchen = 0")
    suspend fun getUnsentItems(orderId: String): List<OrderItem>

    /** All not-yet-sent kitchen items across every order (reactive) — feeds the global
     *  Pending Kitchen Prints modal when auto-print is off. */
    @Query("SELECT * FROM order_items WHERE sentToKitchen = 0")
    fun getPendingKitchenItemsFlow(): Flow<List<OrderItem>>

    @Query("DELETE FROM order_items WHERE orderId = :orderId")
    suspend fun deleteItemsForOrder(orderId: String)

    // --- One-time cleanup: rewrite literal "null" strings written by the old
    // optString(name, null) parsing bug back to real SQL NULL (apk-refactor Seam 1). ---

    @Query("UPDATE orders SET paymentMethod = NULL WHERE paymentMethod = 'null'")
    suspend fun fixNullPaymentMethod(): Int

    @Query("UPDATE orders SET sentToKitchenAt = NULL WHERE sentToKitchenAt = 'null'")
    suspend fun fixNullSentToKitchenAt(): Int

    @Query("UPDATE orders SET cancelReason = NULL WHERE cancelReason = 'null'")
    suspend fun fixNullCancelReason(): Int

    @Query("UPDATE orders SET cancelledBy = NULL WHERE cancelledBy = 'null'")
    suspend fun fixNullCancelledBy(): Int

    @Query("UPDATE order_items SET note = NULL WHERE note = 'null'")
    suspend fun fixNullItemNote(): Int

    // --- Aggregation queries for reports ---

    /**
     * Fetch all orders whose [Order.createdAt] is strictly greater than [sinceTimestamp].
     * Used by `LocalBackend.getOrdersSince` to serve the `?since=` catch-up poll that
     * `RealtimeService` runs in place of the Supabase Realtime WebSocket in LAN and Kiosk Mode.
     *
     * This compares timestamps as TEXT, so it is only chronologically correct while every value
     * written is the same width. `LocalBackend` guarantees that with a fixed 6-fractional-digit
     * formatter; do not switch it to `Instant.toString()`, whose fraction is variable-width and
     * makes `…:05.5Z` compare as less than `…:05Z` — which silently skips orders.
     */
    @Query("SELECT * FROM orders WHERE createdAt > :sinceTimestamp ORDER BY createdAt ASC")
    suspend fun getOrdersSince(sinceTimestamp: String): List<Order>

    @Query("SELECT COUNT(*) FROM orders WHERE createdAt >= :since AND status = 'COMPLETED'")
    suspend fun getCompletedOrderCount(since: String): Int

    @Query("SELECT COALESCE(SUM(total), 0.0) FROM orders WHERE createdAt >= :since AND status = 'COMPLETED'")
    suspend fun getTotalRevenue(since: String): Double

    // --- Report queries (Task 25) ---

    @Query("SELECT * FROM orders WHERE createdAt >= :startDate AND createdAt < :endDate AND status = 'COMPLETED' ORDER BY createdAt ASC")
    suspend fun getCompletedOrdersBetween(startDate: String, endDate: String): List<Order>

    @Query("""
        SELECT tableId, COUNT(*) AS orderCount, COALESCE(SUM(total), 0.0) AS revenue
        FROM orders
        WHERE createdAt >= :startDate AND createdAt < :endDate AND status = 'COMPLETED'
        GROUP BY tableId
        ORDER BY revenue DESC
    """)
    suspend fun getRevenueByTable(startDate: String, endDate: String): List<TableRevenue>

    @Query("SELECT * FROM orders WHERE createdAt >= :startDate AND createdAt < :endDate AND status = 'CANCELLED'")
    suspend fun getCancelledOrders(startDate: String, endDate: String): List<Order>

    @Query("""
        SELECT paymentMethod, COUNT(*) AS orderCount, COALESCE(SUM(total), 0.0) AS revenue
        FROM orders
        WHERE createdAt >= :startDate AND createdAt < :endDate AND status = 'COMPLETED'
        GROUP BY paymentMethod
    """)
    suspend fun getOrdersByPaymentMethod(startDate: String, endDate: String): List<PaymentMethodCount>

    @Query("SELECT COALESCE(SUM(total), 0.0) FROM orders WHERE createdAt >= :startDate AND createdAt < :endDate AND status = 'COMPLETED'")
    suspend fun getTotalRevenueBetween(startDate: String, endDate: String): Double

    @Query("SELECT COUNT(*) FROM orders WHERE createdAt >= :startDate AND createdAt < :endDate AND status = 'COMPLETED'")
    suspend fun getCompletedOrderCountBetween(startDate: String, endDate: String): Int

    @Query("""
        SELECT oi.menuItemId, oi.nameSnapshot, oi.categorySnapshot,
               SUM(oi.quantity) AS totalQuantity,
               SUM(oi.unitPriceSnapshot * oi.quantity) AS totalRevenue
        FROM order_items oi
        INNER JOIN orders o ON oi.orderId = o.id
        WHERE o.createdAt >= :startDate AND o.createdAt < :endDate AND o.status = 'COMPLETED'
        GROUP BY oi.menuItemId, oi.nameSnapshot, oi.categorySnapshot
        ORDER BY totalQuantity DESC
    """)
    suspend fun getPopularItems(startDate: String, endDate: String): List<PopularItemRow>
}
