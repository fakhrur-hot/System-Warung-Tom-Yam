# Implementation Plan: Demo Mode

## Overview

Implement a fully offline Demo Mode for the Warung Tom Yam POS app. This feature adds a "Try Demo" entry point on the role selection screen, creates an isolated in-memory Room database with seed data, provides a guided walkthrough, and reuses existing admin UI composables with demo-qualified ViewModels. All printing is mocked via Snackbar messages.

## Tasks

- [x] 1. Set up Demo Mode DI infrastructure and data layer
  - [x] 1.1 Create `@Demo` qualifier annotation and `DemoModule` Hilt module
    - Create file `di/DemoModule.kt` with `@Demo` qualifier annotation
    - Add `@Module @InstallIn(SingletonComponent::class) object DemoModule` providing in-memory `AppDatabase`, `MenuDao`, `OrderDao`, `TableDao`, and `SettingsDao` — all qualified with `@Demo`
    - _Requirements: 9.1, 2.1_

  - [x] 1.2 Create `DemoDatabaseProvider` for lifecycle management
    - Create file `data/demo/DemoDatabaseProvider.kt` as a `@Singleton` class
    - Implement `getOrCreate()`, `destroy()`, and `reset()` methods for in-memory DB lifecycle
    - Ensure `destroy()` is idempotent (null-safe close)
    - _Requirements: 2.1, 2.4, 8.3_

  - [x] 1.3 Create `DemoSeedData` object with deterministic dummy data
    - Create file `data/demo/DemoSeedData.kt`
    - Define 6 tables (T1–T6), 10 menu items across 4 categories, 2 pre-existing orders with order items
    - Implement `suspend fun seed(db: AppDatabase)` that inserts all seed data
    - _Requirements: 2.2_

  - [x] 1.4 Create `DemoRepository` wrapping demo-qualified DAOs
    - Create file `data/demo/DemoRepository.kt` as a `@Singleton` class injecting `@Demo`-qualified DAOs
    - Implement table, menu, order CRUD operations (tablesFlow, getMenuItems, createOrder, sendToKitchen, processPayment, cancelOrder, addItemsToOrder)
    - Enforce order lifecycle state machine rules (payment only after kitchen, no transitions from terminal states)
    - Implement report query methods (getCompletedOrderCount, getTotalRevenue, getAllOrders)
    - _Requirements: 9.2, 4.7, 2.3, 2.5, 2.6_

- [x] 2. Wire production PrintService for Demo Mode
  - [x] 2.1 Ensure `PrintService` is injectable into `DemoViewModel`
    - Verify existing `PrintService` is a `@Singleton` provided via Hilt (already done in production DI)
    - Ensure `PrinterConfigDao` and `SettingsDao` used by `PrintService` are accessible (production-qualified, not demo-qualified)
    - Add error handling wrapper in `DemoViewModel` to catch print failures when no printer is connected and emit a user-facing error message via `SharedFlow<String>`
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [x] 3. Checkpoint - Ensure data layer compiles
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement DemoViewModel
  - [x] 4.1 Create `DemoViewModel` with full POS workflow logic
    - Create file `ui/demo/DemoViewModel.kt` as a `@HiltViewModel`
    - Inject `DemoRepository`, `PrintService` (production), and `DemoDatabaseProvider`
    - Expose `tables`, `activeOrders`, `menuItems` as `StateFlow`
    - Expose `printError` as `SharedFlow<String>` for printer unavailability messages
    - Expose `walkthroughStep: MutableStateFlow<Int?>` initialized to 1
    - Implement `initSession()` (seed DB, start collecting flows)
    - Implement `advanceWalkthrough()`, `skipWalkthrough()`, `dismissWalkthrough()`
    - Implement order operations: `createOrder()`, `sendToKitchen()`, `processPayment()`, `cancelOrder()`, `addItemsToOrder()`
    - In `sendToKitchen()`: call `printService.printKitchenSlip()` with real Bluetooth dispatch, catch errors
    - In `processPayment()`: call `printService.printReceipt()` with real Bluetooth dispatch, catch errors
    - Implement menu operations: `addMenuItem()`, `editMenuItem()`, `deleteMenuItem()`
    - Implement `exitDemo()` calling `demoDatabaseProvider.destroy()`
    - Handle errors (IllegalStateException, IllegalArgumentException, print failures) with user-facing error state
    - _Requirements: 4.1–4.8, 5.1–5.5, 6.1–6.5, 7.1, 7.2, 8.3_

- [x] 5. Implement Walkthrough UI and Demo navigation
  - [x] 5.1 Create `DemoWalkthroughScreen` composable
    - Create file `ui/demo/DemoWalkthroughScreen.kt`
    - Implement 3-step overlay with semi-transparent background and spotlight regions
    - Step 1: "This is your Table View" highlighting table grid
    - Step 2: "Tap a table to create an order" highlighting a table cell
    - Step 3: "Send to kitchen, then take payment to complete" highlighting action buttons
    - Add "Skip" button visible on all steps, "Next" button (becomes "Get Started" on step 3)
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

  - [x] 5.2 Add demo routes to `NavRoutes` and create `DemoNavGraph`
    - Add `DEMO_WALKTHROUGH`, `DEMO_HOME`, `DEMO_MENU_MANAGEMENT` constants to `NavRoutes.kt`
    - Create file `ui/navigation/DemoNavGraph.kt` with `NavGraphBuilder.demoNavGraph()` extension function
    - Wire `DEMO_WALKTHROUGH` → `DEMO_HOME` navigation with `DemoViewModel`
    - Reuse `AdminHomeScreen` composable on `DEMO_HOME` route with demo ViewModel data
    - Add "Exit Demo" navigation back to `ROLE_SELECT` with `popUpTo("demo") { inclusive = true }`
    - _Requirements: 9.3, 9.4, 8.1, 8.2_

  - [x] 5.3 Add "Try Demo" button to `RoleSelectScreen`
    - Add a "Try Demo" button below the existing "Connect as Admin" button
    - On tap: call `DemoDatabaseProvider.reset()`, seed the database, then navigate to `DEMO_WALKTHROUGH`
    - Ensure button is accessible without network, auth, or prior config
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [ ] 6. Checkpoint - Verify navigation and walkthrough flow
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Wire Demo Mode into existing admin screens
  - [x] 7.1 Connect DemoViewModel to AdminHomeScreen for table view and order management
    - Pass demo ViewModel state (tables, activeOrders) to reused AdminHomeScreen composable
    - Wire order creation flow (tap free table → select items → submit)
    - Wire send-to-kitchen action triggering production PrintService kitchen slip via Bluetooth
    - Wire payment flow triggering production PrintService receipt via Bluetooth and order completion
    - Wire cancel order action
    - Wire amendment flow (add items → re-send to kitchen with delta print)
    - Display Snackbar for `printError` emissions (printer unavailable)
    - _Requirements: 4.1–4.8, 6.1, 6.3, 6.4_

  - [x] 7.2 Connect DemoViewModel to MenuManagementScreen
    - Pass demo ViewModel menu state and CRUD actions to reused MenuManagementScreen
    - Wire add, edit, delete menu item operations through DemoRepository
    - Ensure menu changes reflect immediately in order creation flow
    - _Requirements: 5.1–5.5_

  - [x] 7.3 Connect DemoViewModel to ReportsScreen
    - Pass demo report data (total orders, revenue, avg order value) computed from Demo_Database
    - Ensure reports reflect both seed data and demo-session orders
    - _Requirements: 7.1, 7.2_

- [x] 8. Implement Demo Mode exit and cleanup
  - [x] 8.1 Wire "Exit Demo" button and cleanup logic
    - Add "Exit Demo" button to demo admin home screen header/menu
    - On tap: call `DemoViewModel.exitDemo()` to destroy DB, navigate to ROLE_SELECT
    - Ensure no demo data, preferences, or session state persists after exit
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

- [x] 9. Final checkpoint - Full integration verification
  - Ensure all tests pass, ask the user if questions arise.

- [ ]* 10. Property-based tests
  - [ ]* 10.1 Write property test for Demo Data Isolation
    - **Property 1: Demo Data Isolation**
    - **Validates: Requirements 2.3, 2.5**

  - [ ]* 10.2 Write property test for Order Creation Initial State
    - **Property 2: Order Creation Initial State**
    - **Validates: Requirements 4.2, 4.3**

  - [ ]* 10.3 Write property test for Order State Machine Enforcement
    - **Property 3: Order State Machine Enforcement**
    - **Validates: Requirements 4.4, 4.5, 4.6, 4.7**

  - [ ]* 10.4 Write property test for Amendment Delta Printing
    - **Property 4: Amendment Delta Printing**
    - **Validates: Requirements 4.8, 6.4**

  - [ ]* 10.5 Write property test for Menu CRUD Persistence Within Session
    - **Property 5: Menu CRUD Persistence Within Session**
    - **Validates: Requirements 5.2, 5.3, 5.4, 5.5**

  - [ ]* 10.6 Write property test for Real Printer Output Correctness
    - **Property 6: Real Printer Output Correctness**
    - **Validates: Requirements 6.1, 6.3, 6.4**

  - [ ]* 10.7 Write property test for Report Metrics Consistency
    - **Property 7: Report Metrics Consistency**
    - **Validates: Requirements 7.2**

  - [ ]* 10.8 Write property test for Walkthrough Skip Dismissal
    - **Property 8: Walkthrough Skip Dismissal**
    - **Validates: Requirements 3.4**

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- All UI composables are reused from production — only the data layer is demo-specific
- The production `PrintService` is reused directly for real Bluetooth thermal printing
- The implementation language is Kotlin with Jetpack Compose, Hilt DI, and Room database
- `DemoModule` coexists alongside `DatabaseModule` — no modifications to production DI are required

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.3"] },
    { "id": 1, "tasks": ["1.2", "1.4", "2.1"] },
    { "id": 2, "tasks": ["4.1"] },
    { "id": 3, "tasks": ["5.1", "5.2"] },
    { "id": 4, "tasks": ["5.3", "7.1", "7.2", "7.3"] },
    { "id": 5, "tasks": ["8.1"] },
    { "id": 6, "tasks": ["10.1", "10.2", "10.3", "10.4", "10.5", "10.6", "10.7", "10.8"] }
  ]
}
```
