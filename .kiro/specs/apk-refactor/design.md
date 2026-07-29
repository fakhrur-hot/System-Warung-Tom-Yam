# Design Document: APK Refactor

## Architecture Overview

This refactor corrects the parsing layer and the UI/domain structure of the POS app without changing observable behavior (except fixing the `"null"`-string bug). It touches four seams, in dependency order:

```
┌──────────────────────────────────────────────────────────────┐
│  1. Parsing seam        json/ + JsonExt.kt                     │
│     one null-safe helper + one Order/OrderItem mapper          │
│                    │                                           │
│                    ▼                                           │
│  2. Domain seam         domain/OrderStatus.kt                  │
│     one enum + transition rules + Room TypeConverter           │
│                    │                                           │
│                    ▼                                           │
│  3. UI seam             ui/tableview/ (shared)                 │
│     one TableGrid + TableCell + OrderDetailSheet               │
│     parameterized by StaffPermissions                          │
│                    │                                           │
│                    ▼                                           │
│  4. Navigation/DI seam  AppNavGraph + Hilt                     │
│     explicit menu-item mode, Hilt-only DI, IA cleanup          │
└──────────────────────────────────────────────────────────────┘
```

**Key principle:** each seam is landed and shipped independently behind a green build. The parsing fix (seam 1) is the highest-value, lowest-risk change and goes first; the UI unification (seam 3) depends on the domain enum (seam 2).

The three current order parsers are:
- `ApiClient.parseOrderDto` / `parseOrderItemDto` (`data/ApiClient.kt:536–570`)
- inline parser in `RealtimeService.handleNewOrderMessage` (`realtime/RealtimeService.kt:296–331`)
- mapping in `TableViewViewModel` (~`ui/viewmodels/TableViewViewModel.kt:420–441`)

All three collapse into seam 1.

---

## Components

### Seam 1 — Parsing

#### 1a. `data/json/JsonExt.kt` — null-safe primitives

```kotlin
package com.warungtomyam.pos.data.json

import org.json.JSONObject

/** Returns null when the key is absent OR present as JSON null. */
fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() || has(name) }

/** Required string; throws a typed ParseException naming the field. */
fun JSONObject.reqString(name: String): String =
    if (isNull(name)) throw ParseException(name) else getString(name)

fun JSONObject.reqDouble(name: String): Double =
    if (isNull(name)) throw ParseException(name) else getDouble(name)

class ParseException(val field: String) :
    Exception("Missing or null required field: $field")
```

> Note: the root cause is that Android's `optString(name, fallback)` returns `fallback` only when the key is **absent**; a present JSON `null` stringifies to `"null"`. `optStringOrNull` uses `isNull()` (which is true for both absent and JSON-null) to fix this.

#### 1b. `data/json/OrderMapper.kt` — the single canonical mapper

```kotlin
object OrderMapper {

    fun order(json: JSONObject): Order = Order(
        id = json.reqString("id"),
        tableId = json.reqString("tableId"),
        source = json.optStringOrNull("source") ?: "QR",
        status = OrderStatus.fromWire(json.reqString("status")),
        paymentMethod = json.optStringOrNull("paymentMethod"),
        total = json.reqDouble("total"),
        sentToKitchenAt = json.optStringOrNull("sentToKitchenAt"),
        cancelReason = json.optStringOrNull("cancelReason"),
        cancelledBy = json.optStringOrNull("cancelledBy"),
        createdAt = json.reqString("createdAt"),
    )

    fun item(json: JSONObject, orderId: String): OrderItem = OrderItem(
        id = json.reqString("id"),
        orderId = orderId,
        menuItemId = json.reqString("menuItemId"),
        nameSnapshot = json.reqString("nameSnapshot"),
        unitPriceSnapshot = json.reqDouble("unitPriceSnapshot"),
        categorySnapshot = json.reqString("categorySnapshot"),
        quantity = json.getInt("quantity"),
        note = json.optStringOrNull("note"),            // ← no more "null"
        sentToKitchen = json.optBoolean("sentToKitchen", false),
    )

    fun items(array: JSONArray?, orderId: String): List<OrderItem> =
        buildList {
            if (array == null) return@buildList
            for (i in 0 until array.length())
                add(item(array.getJSONObject(i), orderId))
        }
}
```

`ApiClient` keeps its network/error handling but delegates all field extraction to `OrderMapper`. `RealtimeService` and `TableViewViewModel` call the same mapper.

> **Note on `Order.status` type:** the snippet above shows `OrderStatus` (seam 2). If seam 2 lands after seam 1, the first pass of `OrderMapper` may keep `status: String` and be updated when the enum is introduced. The tasks sequence this explicitly.

### Seam 2 — Domain: `OrderStatus`

#### 2a. `domain/OrderStatus.kt`

```kotlin
enum class OrderStatus {
    RECEIVED, SENT_TO_KITCHEN, PREPARING, READY, COMPLETED, CANCELLED, UNKNOWN;

    companion object {
        fun fromWire(s: String): OrderStatus =
            entries.firstOrNull { it.name == s } ?: UNKNOWN
    }

    val isTerminal get() = this == COMPLETED || this == CANCELLED
}

/** Single source of truth for which actions are allowed in a state. */
object OrderActions {
    fun canSendToKitchen(s: OrderStatus) = s == OrderStatus.RECEIVED
    fun canTakePayment(s: OrderStatus) =
        s == OrderStatus.SENT_TO_KITCHEN || s == OrderStatus.PREPARING || s == OrderStatus.READY
    fun canCancel(s: OrderStatus) = !s.isTerminal
}
```

#### 2b. `data/local/Converters.kt` — extend

```kotlin
@TypeConverter fun fromOrderStatus(v: OrderStatus): String = v.name
@TypeConverter fun toOrderStatus(v: String): OrderStatus = OrderStatus.fromWire(v)
```

#### 2c. Color mapping — one place

```kotlin
// ui/tableview/StatusVisuals.kt
fun OrderStatus?.tableColor(): Color = when (this) {
    null, OrderStatus.COMPLETED, OrderStatus.CANCELLED -> Color(0xFF4CAF50) // Free/green
    OrderStatus.RECEIVED        -> Color(0xFFFF9800) // Orange — new, needs sending
    OrderStatus.SENT_TO_KITCHEN -> Color(0xFF9C27B0) // Purple — cooking
    OrderStatus.PREPARING       -> Color(0xFF9C27B0)
    OrderStatus.READY           -> Color(0xFFF44336) // Red — highest attention: serve now
    OrderStatus.UNKNOWN         -> Color(0xFF9E9E9E) // Grey
}
```

> Rationale (Req 9): today `SENT_TO_KITCHEN` is red and `READY` is blue — urgency inverted. The redesign gives `READY` the highest-attention color.

### Seam 3 — Shared Table View: `ui/tableview/`

```
ui/tableview/
  TableGrid.kt        // LazyVerticalGrid of TableCell — used by both roles
  TableCell.kt        // single cell; color from StatusVisuals; status label
  OrderDetailSheet.kt // ModalBottomSheet; actions gated by StaffPermissions + OrderActions
  TableViewModels.kt  // shared TableState, TableUiStatus mapping, OrderDetailState
  StaffPermissions.kt // data class(canSendToKitchen, canTakePayment, canCancel)
```

```kotlin
data class StaffPermissions(
    val canSendToKitchen: Boolean,
    val canTakePayment: Boolean,
    val canCancel: Boolean,
) {
    companion object { val ADMIN = StaffPermissions(true, true, true) }
}

@Composable
fun TableView(
    tableStates: List<TableState>,
    onTableClick: (Table) -> Unit,
    modifier: Modifier = Modifier,
)   // shared grid; no per-role copy

@Composable
fun OrderDetailSheet(
    state: OrderDetailState,
    tableLabel: String,
    permissions: StaffPermissions,      // admin passes StaffPermissions.ADMIN
    onSendToKitchen: (String) -> Unit,
    onPayment: (String, String) -> Unit,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit,
)
```

- `AdminHomeScreen` renders `TableView` + `OrderDetailSheet(permissions = ADMIN)` and keeps its session/daily-popup/table-management logic.
- `StaffTableViewScreen` renders the same `TableView` + `OrderDetailSheet(permissions = state.permissions)` and keeps the offline-pending banner + check-out.
- Action buttons inside the sheet are shown when `permissions.x && OrderActions.canX(order.status)` — no raw string comparisons.
- `TableViewViewModel` and `StaffOrderViewModel` remain separate view-models (different data sources: local Room+session vs. ordering API key), but both expose the shared `TableState`/`OrderDetailState` types and both map via `OrderMapper`.

### Seam 4 — Navigation & DI

#### 4a. Menu-item route (Req 7)

Replace the empty-string sentinel with an explicit mode:

```kotlin
// NavRoutes
const val MENU_ITEM = "menu_item?mode={mode}&category={category}&itemId={itemId}"
fun addMenuItemRoute(category: String) = "menu_item?mode=ADD&category=$category"
fun editMenuItemRoute(itemId: String)  = "menu_item?mode=EDIT&itemId=$itemId"
```

`MenuItemScreen` reads `mode` (ADD/EDIT) explicitly; EDIT loads the item (including its category) by id; ADD uses the passed category. No `category = ""` sentinel.

#### 4b. Admin home IA (Req 6)

```
TopAppBar: "Table View"
  ▸ primary FAB: "New Dine-In Order"        (was buried in overflow)
  ▸ toolbar action: "Manage Tables" (labeled, not a bare + icon)
  ▸ overflow, grouped:
      — Operations —      (empty here; ops live on the grid)
      — Setup —           Devices · Printers · Menu · QR Cards · Backup · Background · Settings
      — Session —         Sign Out · Sign Out with Closing   (visually separated, existing dialogs)
```

#### 4c. DI (Req 8)

- `OrderingViewModel` → `@HiltViewModel`; `OrderingHomeScreen` uses `hiltViewModel()` (was `viewModel()`).
- `OrderingForegroundService` / `RealtimeService` → `@AndroidEntryPoint` with `@Inject` fields, retiring the `DependencyProvider`/`OrderingDependencyProvider` service-locator. If a specific dependency cannot be Hilt-injected into a started service, document why and keep only that one in a locator.

---

## Data model changes

| Entity | Field | Before | After |
|---|---|---|---|
| `Order` | `status` | `String` | `OrderStatus` (via TypeConverter) |
| `Order` | `paymentMethod`, `sentToKitchenAt`, `cancelReason`, `cancelledBy` | may hold `"null"` | true `null` |
| `OrderItem` | `note` | may hold `"null"` | true `null` |
| `Order`/`OrderItem`/`MenuItem` | money | `Double` | `Double` (Req 10 deferred; integer sen is a later phase) |

**Room migration:** adding the `OrderStatus` converter does not change the column type (still stored as `TEXT`), so no schema migration is required for seam 2. A **data-cleanup** step is advisable to rewrite any already-persisted `"null"` strings to SQL `NULL` on first launch after upgrade (one-time `UPDATE ... SET note = NULL WHERE note = 'null'` etc.), because the P-1 bug may have already written bad rows.

---

## Testing strategy

- **Unit (highest value):** `OrderMapper` against a fixture payload containing JSON `null` in every Nullable_Field, asserting Kotlin `null` (not `"null"`); `OrderStatus.fromWire` incl. unknown; `OrderActions` truth table.
- **Regression:** a golden kitchen-slip/receipt render for a noteless item asserting no `"Note:"` line.
- **Realtime resilience:** a `NEW_ORDER` payload with one malformed optional field asserts the order is still persisted and a diagnostic is logged (Req 3).
- **Build gates:** `./gradlew assembleDebug` at every checkpoint (Req 12).
- Existing tests for printing/attendance/reports must remain green.

## Rollout / sequencing

1. Seam 1 (parsing) — self-contained, ships the visible bug fix. Keep `Order.status: String` initially.
2. Seam 2 (OrderStatus) — introduce enum + converter + data cleanup; update `OrderMapper` to emit the enum.
3. Seam 3 (shared Table View) — depends on seam 2.
4. Seam 4 (nav/DI/IA) — independent of 1–3 but landed last to minimize churn during the higher-risk UI unification.

Each seam is one or more PRs, each behind a green `assembleDebug`.
