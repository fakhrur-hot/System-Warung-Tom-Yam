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

    /**
     * Re-price an order after its lines change.
     *
     * A targeted UPDATE, and it must stay one. The obvious alternative —
     * `insertOrder(order.copy(total = …))` — goes through `@Insert(onConflict = REPLACE)`, which
     * SQLite implements as DELETE-then-INSERT. `order_items` has an `ON DELETE CASCADE` foreign key
     * to this table, so replacing the parent row **deletes every line item belonging to it**. The
     * total ends up correct while the bill it was computed from is gone: the receipt prints with no
     * lines and the reports' popular-items rollup loses the sale entirely.
     */
    @Query("UPDATE orders SET total = :total WHERE id = :orderId")
    suspend fun updateOrderTotal(orderId: String, total: Double)

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

    /**
     * Past bills (settled or cancelled) for the Bill History screen, newest first.
     *
     * One free-text box searches four things a café actually remembers about a bill: the order
     * number, the table it was on, how it was paid, and — via the join — **any item that was on
     * it**. "the table that had the tom yam" is a realistic way to look for a bill, and the item
     * name is often the only detail anyone recalls.
     *
     * `LEFT JOIN` (not `INNER`) so a bill whose lines were all voided still appears; `DISTINCT`
     * because the join multiplies a bill by its line count. [query] must be lower-cased by the
     * caller — SQLite's `LOWER()` is ASCII-only, so folding non-Latin item names has to happen in
     * Kotlin, which handles Chinese, Tamil and Thai correctly.
     *
     * [limit] caps the result set. Unlike [getAllOrders] (used only by backup) this is a screen
     * query, and a café two years in has tens of thousands of bills.
     */
    @Query("""
        SELECT DISTINCT o.* FROM orders o
        LEFT JOIN order_items oi ON oi.orderId = o.id
        LEFT JOIN tables t ON t.id = o.tableId
        WHERE o.status IN ('COMPLETED', 'CANCELLED')
          AND o.createdAt >= :startDate AND o.createdAt < :endDate
          AND (:query = ''
               OR CAST(o.orderNumber AS TEXT) LIKE '%' || :query || '%'
               OR LOWER(t.label) LIKE '%' || :query || '%'
               OR LOWER(o.paymentMethod) LIKE '%' || :query || '%'
               OR LOWER(oi.nameSnapshot) LIKE '%' || :query || '%')
        ORDER BY o.createdAt DESC
        LIMIT :limit
    """)
    suspend fun searchBills(
        startDate: String,
        endDate: String,
        query: String,
        limit: Int
    ): List<Order>

    /** Line items for several bills at once, so the list can show a one-line summary per bill. */
    @Query("SELECT * FROM order_items WHERE orderId IN (:orderIds)")
    suspend fun getItemsForOrders(orderIds: List<String>): List<OrderItem>

    // --- Dashboard live queries ------------------------------------------------

    /** Completed revenue grouped by hour (0–23) for a date range. Used by the live dashboard. */
    @Query("""
        SELECT CAST(SUBSTR(createdAt, 12, 2) AS INTEGER) AS hour,
               COALESCE(SUM(total), 0.0) AS revenue,
               COUNT(*) AS orderCount
        FROM orders
        WHERE createdAt >= :startDate AND createdAt < :endDate AND status = 'COMPLETED'
        GROUP BY hour
        ORDER BY hour ASC
    """)
    suspend fun getHourlyRevenue(startDate: String, endDate: String): List<HourlyRevenue>

    /** Active (non-terminal) orders — reactive for live dashboard counter. */
    @Query("SELECT COUNT(*) FROM orders WHERE status NOT IN ('COMPLETED', 'CANCELLED')")
    fun getActiveOrderCountFlow(): Flow<Int>

    /** Today's completed order count — reactive. */
    @Query("SELECT COUNT(*) FROM orders WHERE createdAt >= :since AND status = 'COMPLETED'")
    fun getCompletedOrderCountFlow(since: String): Flow<Int>

    /** Today's total revenue — reactive. */
    @Query("SELECT COALESCE(SUM(total), 0.0) FROM orders WHERE createdAt >= :since AND status = 'COMPLETED'")
    fun getTotalRevenueFlow(since: String): Flow<Double>

    /** Completed revenue grouped by date (YYYY-MM-DD) for a date range. Used for the daily trend chart. */
    @Query("""
        SELECT SUBSTR(createdAt, 1, 10) AS date,
               COALESCE(SUM(total), 0.0) AS revenue,
               COUNT(*) AS orderCount
        FROM orders
        WHERE createdAt >= :startDate AND createdAt < :endDate AND status = 'COMPLETED'
        GROUP BY date
        ORDER BY date ASC
    """)
    suspend fun getDailyRevenue(startDate: String, endDate: String): List<DailyRevenue>
}

/** Room result for hourly revenue aggregation. */
data class HourlyRevenue(
    val hour: Int,
    val revenue: Double,
    val orderCount: Int
)

/** Room result for daily revenue aggregation. */
data class DailyRevenue(
    val date: String,
    val revenue: Double,
    val orderCount: Int
)
