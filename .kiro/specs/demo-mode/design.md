# Design Document: Demo Mode

## Architecture Overview

Demo Mode is an isolated, offline simulation of the full admin POS experience. It is implemented as a parallel Hilt-qualified dependency graph that provides an in-memory Room database and a local-only repository — injected into existing UI composables via demo-specific ViewModels. Printing reuses the production `PrintService` to dispatch real print jobs to a connected Bluetooth thermal printer.

```
┌──────────────────────────────────────────────────────┐
│  RoleSelectScreen                                    │
│  ┌────────┐  ┌──────────────┐  ┌──────────────────┐ │
│  │ Admin  │  │ Ordering     │  │  Try Demo        │ │
│  └───┬────┘  └──────┬───────┘  └────────┬─────────┘ │
│      │               │                   │           │
│      ▼               ▼                   ▼           │
│  AdminNavGraph   OrderingNavGraph    DemoNavGraph     │
└──────────────────────────────────────────────────────┘

DemoNavGraph:
  DEMO_WALKTHROUGH → DEMO_HOME (reuses AdminHomeScreen)
                       ├── TableView (existing)
                       ├── MenuManagementScreen (existing)
                       ├── OrderDetailSheet (existing)
                       └── ReportsScreen (existing)
```

**Key architectural principle:** Demo Mode shares all UI composables with production. The separation exists only at the data layer (DemoDatabase + DemoRepository). The production `PrintService` is reused directly — no mock or abstraction needed. ViewModels are demo-qualified instances that inject DemoRepository instead of ApiClient.

## Components

### 1. DemoModule (Hilt DI)

A Hilt module installed in `SingletonComponent` that provides demo-specific bindings using `@Demo` qualifier annotation. This module is independent of `DatabaseModule` — both coexist but serve different injection targets.

```kotlin
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Demo

@Module
@InstallIn(SingletonComponent::class)
object DemoModule {

    @Provides
    @Demo
    fun provideDemoDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries() // demo-only; small dataset
            .build()
    }

    @Provides
    @Demo
    fun provideDemoMenuDao(@Demo db: AppDatabase): MenuDao = db.menuDao()

    @Provides
    @Demo
    fun provideDemoOrderDao(@Demo db: AppDatabase): OrderDao = db.orderDao()

    @Provides
    @Demo
    fun provideDemoTableDao(@Demo db: AppDatabase): TableDao = db.tableDao()

    @Provides
    @Demo
    fun provideDemoSettingsDao(@Demo db: AppDatabase): SettingsDao = db.settingsDao()
}
```

### 2. DemoDatabaseProvider

Manages the lifecycle of the in-memory demo database: creation, seeding, and teardown.

```kotlin
@Singleton
class DemoDatabaseProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var demoDb: AppDatabase? = null

    fun getOrCreate(): AppDatabase {
        return demoDb ?: Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { demoDb = it }
    }

    fun destroy() {
        demoDb?.close()
        demoDb = null
    }

    fun reset() {
        destroy()
        // Next call to getOrCreate() will produce a fresh instance
    }
}
```

### 3. DemoSeedData

A pure data object that populates the demo database with deterministic seed data on every session start.

```kotlin
object DemoSeedData {

    val tables = listOf(
        Table(id = "T1", label = "Table 1", sortOrder = 1),
        Table(id = "T2", label = "Table 2", sortOrder = 2),
        Table(id = "T3", label = "Table 3", sortOrder = 3),
        Table(id = "T4", label = "Table 4", sortOrder = 4),
        Table(id = "T5", label = "Table 5", sortOrder = 5),
        Table(id = "T6", label = "Table 6", sortOrder = 6),
    )

    val menuItems = listOf(
        // Food (3 items)
        MenuItem(id = "m1", category = "Food", price = 12.0,
            available = true, askMeDaily = false,
            nameEn = "Tom Yam Soup", nameBm = "Sup Tom Yam"),
        MenuItem(id = "m2", category = "Food", price = 10.0,
            available = true, askMeDaily = false,
            nameEn = "Pad Thai", nameBm = "Pad Thai"),
        MenuItem(id = "m3", category = "Food", price = 9.0,
            available = true, askMeDaily = true,
            nameEn = "Green Curry", nameBm = "Kari Hijau"),
        // Beverages (3 items)
        MenuItem(id = "m4", category = "Beverages", price = 5.0,
            available = true, askMeDaily = false,
            nameEn = "Thai Iced Tea", nameBm = "Teh Ais Thai"),
        MenuItem(id = "m5", category = "Beverages", price = 4.0,
            available = true, askMeDaily = false,
            nameEn = "Lemon Juice", nameBm = "Jus Lemon"),
        MenuItem(id = "m6", category = "Beverages", price = 3.0,
            available = false, askMeDaily = false,
            nameEn = "Coconut Water", nameBm = "Air Kelapa"),
        // Side Dishes (2 items)
        MenuItem(id = "m7", category = "Side Dishes", price = 6.0,
            available = true, askMeDaily = false,
            nameEn = "Spring Rolls", nameBm = "Popia"),
        MenuItem(id = "m8", category = "Side Dishes", price = 5.0,
            available = true, askMeDaily = false,
            nameEn = "Satay (3 pcs)", nameBm = "Satay (3 cucuk)"),
        // Others (2 items)
        MenuItem(id = "m9", category = "Others", price = 2.0,
            available = true, askMeDaily = false,
            nameEn = "White Rice", nameBm = "Nasi Putih"),
        MenuItem(id = "m10", category = "Others", price = 3.0,
            available = true, askMeDaily = false,
            nameEn = "Roti Canai", nameBm = "Roti Canai"),
    )

    // Two pre-existing orders in different statuses
    val orders = listOf(
        Order(id = "demo-order-1", tableId = "T2", source = "QR",
            status = OrderStatus.SENT_TO_KITCHEN.name, total = 22.0,
            sentToKitchenAt = "2025-01-01T10:00:00Z", createdAt = "2025-01-01T09:55:00Z"),
        Order(id = "demo-order-2", tableId = "T4", source = "STAFF",
            status = OrderStatus.RECEIVED.name, total = 15.0,
            createdAt = "2025-01-01T10:05:00Z"),
    )

    val orderItems = listOf(
        // Order 1 items (T2, sent to kitchen)
        OrderItem(id = "oi1", orderId = "demo-order-1", menuItemId = "m1",
            nameSnapshot = "Tom Yam Soup", unitPriceSnapshot = 12.0,
            categorySnapshot = "Food", quantity = 1, sentToKitchen = true),
        OrderItem(id = "oi2", orderId = "demo-order-1", menuItemId = "m4",
            nameSnapshot = "Thai Iced Tea", unitPriceSnapshot = 5.0,
            categorySnapshot = "Beverages", quantity = 2, sentToKitchen = true),
        // Order 2 items (T4, received but not yet sent)
        OrderItem(id = "oi3", orderId = "demo-order-2", menuItemId = "m2",
            nameSnapshot = "Pad Thai", unitPriceSnapshot = 10.0,
            categorySnapshot = "Food", quantity = 1, sentToKitchen = false),
        OrderItem(id = "oi4", orderId = "demo-order-2", menuItemId = "m5",
            nameSnapshot = "Lemon Juice", unitPriceSnapshot = 4.0,
            categorySnapshot = "Beverages", quantity = 1, sentToKitchen = false),
        OrderItem(id = "oi5", orderId = "demo-order-2", menuItemId = "m9",
            nameSnapshot = "White Rice", unitPriceSnapshot = 2.0,
            categorySnapshot = "Others", quantity = 1, sentToKitchen = false),
    )

    suspend fun seed(db: AppDatabase) {
        db.tableDao().let { dao -> tables.forEach { dao.insert(it) } }
        db.menuDao().upsertAll(menuItems)
        db.orderDao().insertOrders(orders)
        db.orderDao().insertOrderItems(orderItems)
    }
}
```

### 4. DemoRepository

A local-only repository that wraps demo-qualified DAOs and implements all POS operations without any network layer. It enforces the same business rules as production (order lifecycle state machine, payment-after-kitchen guard).

```kotlin
@Singleton
class DemoRepository @Inject constructor(
    @Demo private val menuDao: MenuDao,
    @Demo private val orderDao: OrderDao,
    @Demo private val tableDao: TableDao,
    @Demo private val settingsDao: SettingsDao
) {
    // --- Tables ---
    fun tablesFlow(): Flow<List<Table>> = tableDao.getAllFlow()
    suspend fun getTables(): List<Table> = tableDao.getAll()

    // --- Menu ---
    suspend fun getMenuItems(): List<MenuItem> = menuDao.getAll()
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
        require(order.status == OrderStatus.RECEIVED.name ||
                order.status == OrderStatus.SENT_TO_KITCHEN.name) {
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
        require(order.status == OrderStatus.SENT_TO_KITCHEN.name ||
                order.status == OrderStatus.PREPARING.name ||
                order.status == OrderStatus.READY.name) {
            "Payment only allowed after send-to-kitchen"
        }
        orderDao.completePayment(orderId, method)
    }

    suspend fun cancelOrder(orderId: String, reason: String) {
        val order = orderDao.getOrderById(orderId)
            ?: throw IllegalArgumentException("Order not found")
        require(order.status != OrderStatus.COMPLETED.name &&
                order.status != OrderStatus.CANCELLED.name) {
            "Cannot cancel a completed/cancelled order"
        }
        orderDao.cancelOrder(orderId, reason, "DEMO_USER")
    }

    suspend fun addItemsToOrder(orderId: String, items: List<OrderItem>) {
        val order = orderDao.getOrderById(orderId)
            ?: throw IllegalArgumentException("Order not found")
        require(order.status != OrderStatus.COMPLETED.name &&
                order.status != OrderStatus.CANCELLED.name) {
            "Cannot amend a terminal order"
        }
        orderDao.insertOrderItems(items)
        // Recalculate total
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

    private fun currentTimestamp(): String =
        java.time.Instant.now().toString()
}
```

### 5. Production PrintService (Reused)

Demo Mode reuses the existing production `PrintService` directly. No mock or wrapper is needed. The `DemoViewModel` injects the same `PrintService` singleton that production uses, which dispatches print jobs to the connected Bluetooth thermal printer via `PrinterDispatcher`.

If no printer is connected/configured, the `PrinterDispatcher` will fail gracefully and the ViewModel catches the error to show a Snackbar indicating printer unavailability.

```kotlin
// No new class needed — reuse existing PrintService:
// com.warungtomyam.pos.printing.PrintService
//
// DemoViewModel injects it directly:
//   private val printService: PrintService
//
// Usage in DemoViewModel:
//   printService.printKitchenSlip(tableId, unsentItems, isAmendment)
//   printService.printReceipt(order, items, paymentMethod, cafeName)
```

### 6. DemoWalkthroughScreen

A composable overlay displayed before free exploration. Manages a 3-step progression with highlight regions and skip capability.

```kotlin
@Composable
fun DemoWalkthroughScreen(
    currentStep: Int,            // 1, 2, or 3
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    // Semi-transparent overlay with spotlight cut-out
    // Step content:
    //   1 → "This is your Table View" (highlights table grid area)
    //   2 → "Tap a table to create an order" (highlights a table cell)
    //   3 → "Send to kitchen, then take payment to complete" (highlights action buttons)
    // Bottom bar: [Skip]  [Next →]  (or [Get Started] on step 3)
}
```

### 7. DemoViewModel

A demo-qualified ViewModel that combines DemoRepository and the production PrintService to drive the admin POS workflow.

```kotlin
@HiltViewModel
class DemoViewModel @Inject constructor(
    private val demoRepository: DemoRepository,
    private val printService: PrintService,
    private val demoDatabaseProvider: DemoDatabaseProvider,
    private val settingsDao: SettingsDao
) : ViewModel() {

    val tables: StateFlow<List<Table>> = ...
    val activeOrders: StateFlow<List<Order>> = ...
    val menuItems: StateFlow<List<MenuItem>> = ...
    val printError: SharedFlow<String> = ... // emits error message if printer unavailable
    val walkthroughStep: MutableStateFlow<Int?> = MutableStateFlow(1)

    fun initSession() { /* seed DB, start collecting flows */ }
    fun dismissWalkthrough() { walkthroughStep.value = null }
    fun advanceWalkthrough() { /* increment or dismiss at step 3 */ }
    fun skipWalkthrough() { walkthroughStep.value = null }

    fun createOrder(tableId: String, items: List<OrderItem>) { ... }
    fun sendToKitchen(orderId: String) {
        // calls demoRepository.sendToKitchen() then printService.printKitchenSlip()
        // catches print errors and emits to printError flow
    }
    fun processPayment(orderId: String, method: String) {
        // calls demoRepository.processPayment() then printService.printReceipt()
        // catches print errors and emits to printError flow
    }
    fun cancelOrder(orderId: String, reason: String) { ... }
    fun addItemsToOrder(orderId: String, items: List<OrderItem>) { ... }

    // Menu management
    fun addMenuItem(item: MenuItem) { ... }
    fun editMenuItem(item: MenuItem) { ... }
    fun deleteMenuItem(id: String) { ... }

    fun exitDemo() {
        demoDatabaseProvider.destroy()
    }
}
```

## Navigation

```kotlin
// Addition to NavRoutes:
object NavRoutes {
    // ... existing routes ...
    const val DEMO_WALKTHROUGH = "demo_walkthrough"
    const val DEMO_HOME = "demo_home"
    const val DEMO_MENU_MANAGEMENT = "demo_menu_management"
}

// DemoNavGraph — nested graph branching from RoleSelectScreen
fun NavGraphBuilder.demoNavGraph(navController: NavHostController) {
    navigation(startDestination = NavRoutes.DEMO_WALKTHROUGH, route = "demo") {
        composable(NavRoutes.DEMO_WALKTHROUGH) {
            val viewModel: DemoViewModel = hiltViewModel()
            DemoWalkthroughScreen(
                currentStep = viewModel.walkthroughStep.collectAsState().value ?: 1,
                onNext = { viewModel.advanceWalkthrough() },
                onSkip = {
                    viewModel.skipWalkthrough()
                    navController.navigate(NavRoutes.DEMO_HOME) {
                        popUpTo(NavRoutes.DEMO_WALKTHROUGH) { inclusive = true }
                    }
                }
            )
        }
        composable(NavRoutes.DEMO_HOME) {
            val viewModel: DemoViewModel = hiltViewModel()
            // Reuse AdminHomeScreen with demo ViewModel
            AdminHomeScreen(
                // Pass demo ViewModel state/actions
                onExitDemo = {
                    viewModel.exitDemo()
                    navController.navigate(NavRoutes.ROLE_SELECT) {
                        popUpTo("demo") { inclusive = true }
                    }
                }
            )
        }
        composable(NavRoutes.DEMO_MENU_MANAGEMENT) {
            val viewModel: DemoViewModel = hiltViewModel()
            MenuManagementScreen(/* pass demo ViewModel */)
        }
    }
}
```

## Data Models

All data models are reused from existing Room entities:

| Entity | Table Name | Key Fields |
|--------|-----------|------------|
| `Table` | `tables` | id, label, sortOrder |
| `MenuItem` | `menu_items` | id, category, price, available, askMeDaily, nameEn, nameBm |
| `Order` | `orders` | id, tableId, source, status, paymentMethod, total, createdAt |
| `OrderItem` | `order_items` | id, orderId, menuItemId, nameSnapshot, unitPriceSnapshot, quantity, sentToKitchen |

No new entities are needed. The in-memory demo database uses the same `AppDatabase` schema.

## Interfaces

### DemoDatabaseProvider interface

```kotlin
interface IDemoDatabaseProvider {
    fun getOrCreate(): AppDatabase
    fun destroy()
    fun reset()
}
```

## Error Handling

| Scenario | Handling |
|----------|----------|
| Invalid state transition (e.g., payment before kitchen) | `IllegalStateException` caught in ViewModel, surfaced as Snackbar error |
| Order not found | `IllegalArgumentException` caught in ViewModel |
| Demo DB already destroyed on exit | `destroy()` is idempotent (null check) |
| Walkthrough step out of bounds | Clamped to 1..3 range |
| Empty order submission (0 items) | Rejected at ViewModel level before DB write |
| Bluetooth printer not connected/configured | Print error caught in ViewModel, surfaced as Snackbar "Printer unavailable" |

## Sequence Diagrams

### Demo Entry Flow

```
User                RoleSelectScreen       DemoDatabaseProvider    DemoSeedData     DemoNavGraph
 │                       │                        │                    │               │
 ├─ Tap "Try Demo" ────►│                        │                    │               │
 │                       ├─ reset() ────────────►│                    │               │
 │                       │                        ├─ destroy old ─────►               │
 │                       │                        ├─ create in-memory DB              │
 │                       │◄────── new AppDatabase─┤                    │               │
 │                       ├─ seed(db) ────────────────────────────────►│               │
 │                       │                        │                    ├─ insert tables │
 │                       │                        │                    ├─ insert menu   │
 │                       │                        │                    ├─ insert orders │
 │                       │◄───────────────────────────── seeded ──────┤               │
 │                       ├─ navigate(DEMO_WALKTHROUGH) ──────────────────────────────►│
 │◄── Walkthrough UI ───────────────────────────────────────────────────────────────── │
```

### Order Lifecycle in Demo

```
User              DemoViewModel         DemoRepository         OrderDao           PrintService
 │                     │                      │                   │                  │
 ├─ Tap free table ──►│                      │                   │                  │
 ├─ Select items ────►│                      │                   │                  │
 ├─ Submit ──────────►├─ createOrder() ─────►├─ insertOrder() ──►│                  │
 │                     │                      ├─ insertItems() ──►│                  │
 │◄── Table=Occupied ──┤                      │                   │                  │
 │                     │                      │                   │                  │
 ├─ Send to Kitchen ─►├─ sendToKitchen() ───►├─ getUnsentItems()►│                  │
 │                     │                      ├─ markSent() ─────►│                  │
 │                     ├─ printKitchenSlip() ─────────────────────────────────────► │
 │                     │                      │                   │    (Bluetooth)   │
 │◄── Print sent ──────┤                      │                   │                  │
 │                     │                      │                   │                  │
 ├─ Payment (Cash) ──►├─ processPayment() ──►├─ completePayment()►│                 │
 │                     ├─ printReceipt() ─────────────────────────────────────────► │
 │                     │                      │                   │    (Bluetooth)   │
 │◄── Print sent ──────┤                      │                   │                  │
 │◄── Table=Free ──────┤                      │                   │                  │
```

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Demo Data Isolation

*For any* sequence of operations performed during a Demo_Session (order creation, menu edits, order status changes), the production database `warung_tom_yam_db` SHALL remain completely unmodified — no rows inserted, updated, or deleted.

**Validates: Requirements 2.3, 2.5**

### Property 2: Order Creation Initial State

*For any* free table and *for any* non-empty set of valid menu items with positive quantities, submitting an order SHALL result in an order with status `RECEIVED` and the table having an active order (i.e., the table is no longer free).

**Validates: Requirements 4.2, 4.3**

### Property 3: Order State Machine Enforcement

*For any* order in a given status, only valid status transitions SHALL succeed. Specifically: from `RECEIVED` only `SENT_TO_KITCHEN` or `CANCELLED` are reachable; from `SENT_TO_KITCHEN` only `PREPARING`, `COMPLETED` (via payment), or `CANCELLED` are reachable; from `PREPARING` only `READY`, `COMPLETED`, or `CANCELLED`; from `READY` only `COMPLETED` or `CANCELLED`; from `COMPLETED` or `CANCELLED` no further transitions are allowed. Any invalid transition SHALL be rejected with an error.

**Validates: Requirements 4.4, 4.5, 4.6, 4.7**

### Property 4: Amendment Delta Printing

*For any* order with existing sent items, when new items are added (amendment) and "Send to Kitchen" is triggered, only the newly added unsent items SHALL be included in the kitchen slip print — previously sent items SHALL NOT appear in the delta output.

**Validates: Requirements 4.8, 6.4**

### Property 5: Menu CRUD Persistence Within Session

*For any* valid menu item, inserting it into the Demo_Database makes it queryable; *for any* existing menu item, updating its fields (name, price, category) persists the new values; *for any* existing menu item, deleting it removes it from query results. All changes are visible immediately in the same Demo_Session.

**Validates: Requirements 5.2, 5.3, 5.4, 5.5**

### Property 6: Real Printer Output Correctness

*For any* print action triggered during a Demo_Session, the production PrintService SHALL dispatch the print job to the connected Bluetooth thermal printer containing: (a) the table ID, (b) for receipts: the total amount and payment method, (c) for amendments: the "TAMBAHAN/ADDED" prefix. If no printer is connected, a user-facing error SHALL be surfaced without crashing.

**Validates: Requirements 6.1, 6.3, 6.4**

### Property 7: Report Metrics Consistency

*For any* set of orders in the Demo_Database, the computed report metrics SHALL satisfy: `totalOrders` equals the count of COMPLETED orders, `totalRevenue` equals the sum of `total` for all COMPLETED orders, and `averageOrderValue` equals `totalRevenue / totalOrders` (or 0 when no completed orders exist).

**Validates: Requirements 7.2**

### Property 8: Walkthrough Skip Dismissal

*For any* walkthrough step (1, 2, or 3), tapping "Skip" SHALL immediately dismiss the walkthrough overlay and grant full interactive access to the Table_View, regardless of the current step position.

**Validates: Requirements 3.4**
