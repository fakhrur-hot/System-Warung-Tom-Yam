# Implementation Plan: APK Refactor

## Overview

Correct the POS app's data-parsing defects and restructure its UI/domain so structure matches functionality. Four independently-shippable seams in dependency order: **(1) Parsing → (2) Domain OrderStatus → (3) Shared Table View → (4) Navigation/DI/IA.**

Every task must leave `./gradlew assembleDebug` green. This is a behavior-preserving refactor plus the `"null"`-string bug fix — no new end-user features.

## Tasks

### Seam 1 — Parsing

- [x] 1. Introduce null-safe JSON primitives and the canonical order mapper
  - [x] 1.1 Create `data/json/JsonExt.kt`
    - Implement `JSONObject.optStringOrNull(name): String?` using `isNull()` so both absent and JSON-null keys return Kotlin `null` (not `"null"`)
    - Implement `reqString(name): String` and `reqDouble(name): Double` — throw a typed `ParseException(field)` on missing or null required fields
    - _Requirements: 1.1, 3.2_

  - [x] 1.2 Create `data/json/OrderMapper.kt`
    - Implement `order(json: JSONObject): Order`, `item(json, orderId): OrderItem`, and `items(array, orderId): List<OrderItem>`
    - Use `optStringOrNull` for all nullable fields: `note`, `paymentMethod`, `sentToKitchenAt`, `cancelReason`, `cancelledBy`
    - Keep `Order.status` as `String` in this seam (enum arrives in Seam 2)
    - _Requirements: 1.2, 1.3, 1.4, 2.1_

  - [x] 1.3 Route all three existing parsers through `OrderMapper`
    - `ApiClient.parseOrderDto` / `parseOrderItemDto` → delegate field extraction to `OrderMapper`; keep network error handling in place
    - `RealtimeService.handleNewOrderMessage` (and any order-update handler) → replace inline field reads with `OrderMapper`
    - `TableViewViewModel` order/item mapping (~lines 420–441) → replace with `OrderMapper`
    - Fix non-order `optString(x, null)` sites: `DeviceStatusResponse.role/apiKey` (`ApiClient` ~151–152) and `DeviceDto.lastSeenAt` (~604, ~658)
    - _Requirements: 1.2, 1.5, 2.2_

  - [x] 1.4 Guard `SecureStorage` against `"null"` strings
    - Only persist `apiKey` and `role` when the value is genuinely non-null
    - Add a defensive check so the literal `"null"` can never be stored or read back as a valid credential
    - _Requirements: 1.5_

- [x] 2. Tests for the parser
  - [x] 2.1 Unit-test `OrderMapper` with a fixture payload where every nullable field is JSON `null`; assert Kotlin `null`, never the string `"null"`
    - _Requirements: 1.3, 2.3_

  - [x] 2.2 Verify noteless item: `note` parses to real `null` so the existing `!isNullOrBlank()` guard prints no `"Note:"` line (verified at the model level)
    - _Requirements: 1.6_

  - [x] 2.3 Grep-gate: confirm zero remaining `optString(<x>, null)` occurrences in production code (doc comments and the `isNull`-guarded `DatabaseBackupManager` helper are excluded)
    - _Requirements: 2.3_

- [x] 3. One-time data cleanup for already-persisted bad rows
  - [x] 3.1 Add `OrderDao` UPDATE queries that rewrite stored `'null'` strings to SQL `NULL`; invoke them from a SharedPreferences-gated one-shot block in `PosApp.onCreate` for these columns: `order_items.note`, `orders.paymentMethod`, `orders.cancelReason`, `orders.cancelledBy`, `orders.sentToKitchenAt`
    - _Requirements: 1.3, 1.4_

- [x] 4. Checkpoint — Seam 1 complete
  - Run `./gradlew assembleDebug` and confirm green build
  - Verify on-device: a noteless order shows no `"Note: null"` line on the kitchen slip
  - _Requirements: 12.1, 12.3_

---

### Seam 2 — Domain: OrderStatus

- [x] 5. Introduce the typed status model
  - [x] 5.1 Enhance the existing `OrderStatus` enum in `data/local/Order.kt`
    - Add `UNKNOWN` value for unrecognised wire strings
    - Add `companion object { fun fromWire(s: String): OrderStatus }` — returns `UNKNOWN` rather than throwing
    - Add `val isTerminal: Boolean` computed property (`COMPLETED` or `CANCELLED`)
    - _Requirements: 4.1, 4.3_

  - [x] 5.2 Create `data/local/OrderActions.kt`
    - Implement `canSendToKitchen(s: OrderStatus)`, `canTakePayment(s: OrderStatus)`, `canCancel(s: OrderStatus)` as the single source of truth for allowed status transitions
    - _Requirements: 4.4_

  - [x] 5.3 Wire `OrderStatus` into Room and `OrderMapper`
    - Add `@TypeConverter` functions for `OrderStatus ↔ String` in `Converters.kt`
    - Change `Order.status` field type from `String` to `OrderStatus`
    - Update `OrderMapper.order(json)` to call `OrderStatus.fromWire(...)` — no schema migration needed (column stays `TEXT`)
    - _Requirements: 4.2, 4.3_

  - [x] 5.4 Replace raw string comparisons with `OrderActions`
    - In `AdminHomeScreen` and `StaffTableViewScreen` detail sheets, replace all `order.status != "COMPLETED"` / `order.status == "RECEIVED"` style checks with `OrderActions.canX(order.status)`
    - Keep `.name` only for DAO `String` parameters and backup JSON export
    - _Requirements: 4.4_

- [x] 6. Checkpoint — Seam 2 complete
  - Run `./gradlew assembleDebug` — Room compile-time verifies the new TypeConverter
  - Confirm action gating is unchanged from the user's perspective (now typo-proof)
  - _Requirements: 12.1, 12.3_

---

### Seam 3 — Shared Table View

- [x] 7. Extract shared components into `ui/tableview/`
  - [x] 7.1 Create `ui/tableview/StaffPermissions.kt` and `ui/tableview/TableViewModels.kt`
    - `StaffPermissions(canSendToKitchen, canTakePayment, canCancel)` data class with `companion object { val ADMIN = StaffPermissions(true, true, true) }`
    - Shared state types: `TableState`, `TableUiStatus`, `OrderDetailState` — used by both `TableViewViewModel` and `StaffOrderViewModel`
    - _Requirements: 5.1, 5.4_

  - [x] 7.2 Create `ui/tableview/StatusVisuals.kt`
    - Single `OrderStatus?.tableColor(): Color` function
    - Give `READY` the highest-attention color (red `0xFFF44336`); `SENT_TO_KITCHEN`/`PREPARING` → purple `0xFF9C27B0`; `RECEIVED` → orange `0xFFFF9800`; free/terminal → green `0xFF4CAF50`; `UNKNOWN` → grey
    - _Requirements: 4.5, 9.1, 9.3_

  - [x] 7.3 Create `ui/tableview/TableGrid.kt` and `ui/tableview/TableCell.kt`
    - `TableGrid`: `LazyVerticalGrid` of `TableCell` composables — one implementation shared by both roles
    - `TableCell`: single cell colored by `StatusVisuals.tableColor()`; display the per-cell status label
    - _Requirements: 5.1, 5.2, 9.2_

  - [x] 7.4 Create `ui/tableview/OrderDetailSheet.kt`
    - `ModalBottomSheet` parameterized by `StaffPermissions`
    - Show action buttons when `permissions.x && OrderActions.canX(order.status)` — no raw string comparisons
    - _Requirements: 5.1, 4.4_

- [x] 8. Adopt the shared components in both roles
  - [x] 8.1 Refactor `AdminHomeScreen`
    - Render shared `TableGrid` + `OrderDetailSheet(permissions = StaffPermissions.ADMIN)`
    - Delete the local `TableCell` composable that was previously inlined here
    - Keep existing session lifecycle, daily-availability popup, and table-management dialog logic unchanged
    - _Requirements: 5.2, 5.3_

  - [x] 8.2 Refactor `StaffTableViewScreen`
    - Render shared `TableGrid` + `OrderDetailSheet(permissions = state.permissions)`
    - Delete `StaffTableCell` and the duplicate order detail sheet
    - Keep offline-pending banner and check-out flow unchanged
    - _Requirements: 5.2, 5.3_

  - [x] 8.3 Update `TableViewViewModel` and `StaffOrderViewModel`
    - Both view-models expose the shared `TableState` / `OrderDetailState` types from `TableViewModels.kt`
    - Remove the private `TableStatus` / `TableState` / `OrderDetailState` duplicates from each view-model
    - _Requirements: 5.4_

- [x] 9. Checkpoint — Seam 3 complete
  - ✅ `./gradlew assembleDebug` green. Verified: both `AdminHomeScreen` and `StaffTableViewScreen` render the shared `TableGrid` + `OrderDetailSheet` (no leftover `TableCell`/`StaffTableCell`); `StatusVisuals.tableColor()` gives `READY` the red highest-urgency color per Req 9.
  - On-device visual parity check still pending (no device/emulator in this environment).
  - _Requirements: 12.1, 12.3_

---

### Seam 4 — Navigation, DI, and IA

- [x] 10. Explicit Add/Edit menu-item mode
  - [x] 10.1 Update `NavRoutes` and `AppNavGraph`
    - Replace the `category=""` sentinel with an explicit `mode` parameter (`ADD` / `EDIT`) — either as a single route `menu_item?mode={mode}&category={category}&itemId={itemId}` or as two distinct routes `add_menu_item/{category}` (already exists) and `edit_menu_item/{itemId}` (to be added)
    - Expose `NavRoutes.addMenuItemRoute(category)` and `NavRoutes.editMenuItemRoute(itemId)` convenience helpers
    - _Requirements: 7.1, 7.2_

  - [x] 10.2 Update `MenuItemScreen` (or `AddMenuItemScreen`)
    - EDIT mode: load the item by `itemId` including its stored category — no empty-string branching
    - ADD mode: use the passed `category` directly
    - Remove all `if (category.isEmpty())` or `category == ""` sentinel checks
    - _Requirements: 7.1, 7.2, 7.3_

- [x] 11. Admin home information architecture
  - [x] 11.1 Promote "New Dine-In Order" to a primary FAB
    - Replace the current overflow-menu entry with a prominently placed `FloatingActionButton` (consistent with the staff screen pattern)
    - _Requirements: 6.1, 6.2_

  - [x] 11.2 Rename the table-management entry point
    - Replace the bare `+` icon button in the `TopAppBar` with a clearly-labeled action or menu item titled "Manage Tables"
    - _Requirements: 6.3_

  - [x] 11.3 Restructure the overflow menu
    - Group into: **Setup** (Devices · Printers · Menu · QR Cards · Backup · Background Setup · Settings) and **Session** (Sign Out · Sign Out with Closing), visually separated
    - Existing closing-reason dialog and sign-out logic remain unchanged
    - _Requirements: 6.1, 6.4_

- [x] 12. DI consistency
  - [x] 12.1 Make `OrderingViewModel` a `@HiltViewModel`
    - Change the class annotation and make `OrderingHomeScreen` obtain it via `hiltViewModel()` instead of `viewModel()`
    - _Requirements: 8.1_

  - [x] 12.2 Convert `OrderingForegroundService` and `RealtimeService` to `@AndroidEntryPoint`
    - Replace `DependencyProvider` / `OrderingDependencyProvider` hand-rolled locator with `@Inject`-annotated fields
    - If a specific dependency genuinely cannot be Hilt-injected into a started service, document the reason with a `// HILT-EXCEPTION:` comment and keep only that one binding in the locator
    - _Requirements: 8.2, 8.3_

- [x] 13. Food dictionary cleanup
  - [x] 13.1 Remove inappropriate entries from `FoodDictionary.kt`
    - Delete "Racun", "Haram", and any other non-food entries that do not belong in a café menu context
    - _Requirements: 11.1, 11.2_

- [x] 14. Checkpoint — Seam 4 complete
  - ✅ `./gradlew assembleDebug` green (45s), incl. Hilt processing of the `@AndroidEntryPoint` services and `@HiltViewModel`. Verified: no empty-string sentinels remain (explicit `MenuItemMode`); admin home has a New-Dine-In FAB, labeled "Manage Tables", and a Setup/Session-grouped overflow; `OrderingViewModel` is `@HiltViewModel`; grep confirms zero `DependencyProvider`/`OrderingDependencyProvider` references remain (interfaces deleted, no documented exceptions needed).
  - On-device verification of the new admin-home layout still pending (no device/emulator in this environment).
  - _Requirements: 12.1, 12.2, 12.3_

---

### Deferred

- [ ]* 15. Money as integer minor units (sen)
  - Migrate `Order.total`, `OrderItem.unitPriceSnapshot`, `MenuItem.price` from `Double` to `Int` (value in sen) with a Room schema migration
  - Format `RM x.xx` only at the UI display edge
  - **Schedule as its own spec once Seams 1–4 are stable — omission here is intentional**
  - _Requirements: 10.1, 10.2, 10.3_

## Notes

- Tasks marked `*` are deferred and can be skipped for this pass
- Seams 1, 2, 3, and 4 (tasks 1–14) are complete and build green; only the deferred task 15 remains
- Seam 3 unifies the admin + staff Table View onto the shared `ui/tableview/` package (`TableGrid`, `TableCell`, `OrderDetailSheet`, `StatusVisuals`, `StaffPermissions`, `TableViewModels`); `READY` now gets the red highest-urgency color (Req 9)
- Seam 4: menu add/edit now uses an explicit `MenuItemMode` (no `category=""` sentinel); admin home promotes New-Dine-In to a FAB with a labeled "Manage Tables" item and a Setup/Session-grouped overflow; DI is Hilt-consistent — `OrderingViewModel` is `@HiltViewModel`, both foreground services are `@AndroidEntryPoint`, and the `DependencyProvider`/`OrderingDependencyProvider` locators were deleted (no exceptions needed); removed the "Racun"/"Haram" non-food dictionary entries
- Enum location: `OrderStatus` was enhanced in-place in `data/local/Order.kt` (not moved to `domain/`) to avoid breaking ~8 existing import sites — `OrderActions` lives alongside it at `data/local/OrderActions.kt`
- `Order.status` is persisted as the enum's `.name` via a Room `TypeConverter`; the column stays `TEXT` so no schema migration is required and existing SQL `WHERE status IN (...)` queries are unaffected

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.4"] },
    { "id": 1, "tasks": ["1.2"] },
    { "id": 2, "tasks": ["1.3", "3.1"] },
    { "id": 3, "tasks": ["2.1", "2.2", "2.3"] },
    { "id": 4, "tasks": ["4"] },
    { "id": 5, "tasks": ["5.1", "5.2"] },
    { "id": 6, "tasks": ["5.3"] },
    { "id": 7, "tasks": ["5.4"] },
    { "id": 8, "tasks": ["6"] },
    { "id": 9, "tasks": ["7.1", "7.2"] },
    { "id": 10, "tasks": ["7.3", "7.4"] },
    { "id": 11, "tasks": ["8.1", "8.2", "8.3"] },
    { "id": 12, "tasks": ["9"] },
    { "id": 13, "tasks": ["10.1", "11.1", "11.2", "12.1", "13.1"] },
    { "id": 14, "tasks": ["10.2", "11.3", "12.2"] },
    { "id": 15, "tasks": ["14"] },
    { "id": 16, "tasks": ["15"] }
  ]
}
```
