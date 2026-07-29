# Requirements Document

## Introduction

This document defines the requirements for a **Demo Mode** feature in the Warung Tom Yam POS APK. Demo Mode provides a fully functional, self-contained admin experience accessible directly from the role selection screen without requiring any Supabase backend connection. It uses an isolated in-memory Room database pre-populated with dummy seed data, allowing potential users to explore the POS workflow (table view, order lifecycle, menu management, and mocked printing) before committing to a real deployment.

## Glossary

- **Demo_Mode**: A self-contained, offline operational mode of the APK that simulates the full admin POS experience using local dummy data with no backend connectivity.
- **Demo_Database**: A separate in-memory Room database instance (`warung_demo_db`) used exclusively during Demo Mode, fully isolated from the production database.
- **Seed_Data**: Pre-defined dummy data (tables, menu items, orders) loaded into the Demo_Database on every Demo Mode entry.
- **Demo_Session**: The period from when the user taps "Try Demo" until they exit Demo Mode. All data modifications persist only within this session.
- **Guided_Walkthrough**: A brief 3-step onboarding overlay shown at the start of each Demo_Session to orient the user on core POS actions.
- **Role_Select_Screen**: The initial APK screen (`RoleSelectScreen`) where users choose their connection role or enter Demo Mode.
- **Table_View**: The primary admin dashboard surface showing a grid of tables with session states and order statuses.
- **Order_Lifecycle**: The complete flow of an order through statuses: RECEIVED → SENT_TO_KITCHEN → PREPARING → READY → COMPLETED or CANCELLED.
- **Demo_Printer**: The production Bluetooth thermal printer service reused in Demo Mode, printing real kitchen slips and receipts to a connected printer just like in production.

## Requirements

### Requirement 1: Demo Mode Entry Point

**User Story:** As a prospective user, I want to try the POS system from the role selection screen, so that I can evaluate the app without setting up a backend.

#### Acceptance Criteria

1. THE Role_Select_Screen SHALL display a "Try Demo" button below the existing "Connect as Admin" button.
2. WHEN the user taps the "Try Demo" button, THE Demo_Mode SHALL create a fresh Demo_Database instance populated with Seed_Data.
3. WHEN the user taps the "Try Demo" button, THE Demo_Mode SHALL navigate the user to the Guided_Walkthrough before entering the Table_View.
4. THE "Try Demo" button SHALL be accessible without any network connectivity, authentication, or prior configuration.

### Requirement 2: Data Isolation and Lifecycle

**User Story:** As a user exploring Demo Mode, I want my demo actions to be completely isolated from real data, so that nothing I do in the demo affects production.

#### Acceptance Criteria

1. THE Demo_Database SHALL be a separate in-memory Room database instance named `warung_demo_db`, distinct from the production database `warung_tom_yam_db`.
2. WHEN a Demo_Session starts, THE Demo_Mode SHALL populate the Demo_Database with Seed_Data consisting of 6 tables (T1 through T6), approximately 10 menu items across 4 categories (Food, Beverages, Side Dishes, Others), and 2 pre-existing orders in different statuses.
3. THE Demo_Mode SHALL operate exclusively on the Demo_Database for all read and write operations during the Demo_Session.
4. WHEN the user taps "Try Demo", THE Demo_Mode SHALL discard any previously existing Demo_Database and create a fresh instance with default Seed_Data.
5. THE Demo_Mode SHALL NOT read from, write to, or modify the production database at any point during a Demo_Session.
6. THE Demo_Mode SHALL NOT make any network calls, WebSocket connections, or SecureStorage writes during a Demo_Session.

### Requirement 3: Guided Walkthrough

**User Story:** As a first-time user, I want a brief orientation when entering Demo Mode, so that I understand how to use the core POS features.

#### Acceptance Criteria

1. WHEN a Demo_Session begins, THE Demo_Mode SHALL display a 3-step guided walkthrough overlay before granting free exploration access.
2. THE Guided_Walkthrough SHALL present the following steps in sequence: Step 1 — "This is your Table View" (highlighting the table grid), Step 2 — "Tap a table to create an order" (highlighting a table cell), Step 3 — "Send to kitchen, then take payment to complete" (highlighting action buttons).
3. WHEN the user taps "Next" on the final walkthrough step, THE Demo_Mode SHALL dismiss the overlay and grant full interactive access to the Table_View.
4. THE Guided_Walkthrough SHALL allow the user to skip all steps by tapping a "Skip" button at any point during the walkthrough.

### Requirement 4: Full Admin POS Workflow in Demo Mode

**User Story:** As a user exploring Demo Mode, I want to experience the complete order lifecycle, so that I can evaluate whether this POS system meets my needs.

#### Acceptance Criteria

1. THE Demo_Mode SHALL display the Table_View showing all 6 seed tables with their current session states (Free or Occupied).
2. WHEN the user taps a Free table, THE Demo_Mode SHALL allow the user to create a new order by selecting menu items, specifying quantities, and submitting the order.
3. WHEN the user submits an order, THE Demo_Mode SHALL assign the order status RECEIVED and mark the table as Occupied.
4. WHEN the user taps "Send to Kitchen" on an order, THE Demo_Mode SHALL update the order status to SENT_TO_KITCHEN and trigger the Mocked_Printer for a kitchen slip.
5. WHEN the user taps "Payment" on an order that has been sent to the kitchen, THE Demo_Mode SHALL allow selection of Cash or QR payment method, mark the order COMPLETED, and end the table session.
6. WHEN the user taps "Cancel" on an order, THE Demo_Mode SHALL mark the order CANCELLED and end the table session.
7. THE Demo_Mode SHALL enforce the same order lifecycle rules as production: Payment is enabled only after Send to Kitchen, and all status transitions follow the defined Order_Lifecycle sequence.
8. THE Demo_Mode SHALL allow adding items to an existing order (amendment) and re-sending to kitchen to print only the newly added lines via the Mocked_Printer.

### Requirement 5: Menu Management in Demo Mode

**User Story:** As a user exploring Demo Mode, I want to add, edit, and delete menu items, so that I can understand the menu management workflow.

#### Acceptance Criteria

1. THE Demo_Mode SHALL provide access to the Menu Management screen with all seed menu items displayed across the 4 categories.
2. WHEN the user adds a new menu item, THE Demo_Mode SHALL persist the item in the Demo_Database for the duration of the Demo_Session.
3. WHEN the user edits a menu item (name, price, or category), THE Demo_Mode SHALL update the item in the Demo_Database for the duration of the Demo_Session.
4. WHEN the user deletes a menu item, THE Demo_Mode SHALL remove the item from the Demo_Database for the duration of the Demo_Session.
5. THE Demo_Mode SHALL reflect menu changes immediately in the order creation flow within the same Demo_Session.

### Requirement 6: Real Printer Output in Demo Mode

**User Story:** As a user exploring Demo Mode, I want printing to work with a real connected Bluetooth thermal printer, so that I can evaluate the actual print output quality.

#### Acceptance Criteria

1. WHEN a print action is triggered during a Demo_Session (kitchen slip or receipt), THE Demo_Mode SHALL use the production PrintService to dispatch print jobs to the connected Bluetooth thermal printer.
2. THE Demo_Mode SHALL reuse the same printer configuration (paper width, printer role assignments) as production mode.
3. WHEN a receipt print is triggered after completing payment, THE Demo_Mode SHALL print a full receipt via the production PrintService (e.g., formatted receipt for Table T5 — RM 42.00 (Cash)).
4. WHEN a delta kitchen slip is triggered for amended items, THE Demo_Mode SHALL print a kitchen slip with the "TAMBAHAN/ADDED" prefix via the production PrintService, containing only the newly added items.
5. IF no Bluetooth printer is connected or configured, THE Demo_Mode SHALL display a Snackbar error indicating the printer is unavailable, without crashing.

### Requirement 7: Reports in Demo Mode

**User Story:** As a user exploring Demo Mode, I want to see sample reports, so that I can understand what business insights the system provides.

#### Acceptance Criteria

1. THE Demo_Mode SHALL provide access to the reports screen showing aggregated data from the Seed_Data and any orders created during the Demo_Session.
2. THE Demo_Mode SHALL compute report metrics (total orders, total revenue, average order value, category breakdown) from the Demo_Database only.

### Requirement 8: Demo Mode Exit

**User Story:** As a user finished exploring Demo Mode, I want to exit cleanly back to the role selection screen, so that I can then connect for real use if I choose.

#### Acceptance Criteria

1. THE Demo_Mode SHALL display an "Exit Demo" button accessible from the demo admin home screen.
2. WHEN the user taps "Exit Demo", THE Demo_Mode SHALL navigate the user back to the Role_Select_Screen.
3. WHEN the user exits Demo Mode, THE Demo_Mode SHALL discard the Demo_Database and release all associated memory resources.
4. WHEN the user exits Demo Mode, THE Demo_Mode SHALL NOT persist any demo data, preferences, or session state.

### Requirement 9: Architectural Isolation

**User Story:** As a developer, I want Demo Mode to be cleanly isolated via dependency injection, so that demo code does not pollute production logic.

#### Acceptance Criteria

1. THE Demo_Mode SHALL use a Hilt-qualified DI module that provides the Demo_Database and demo-specific repository implementations separately from production bindings.
2. THE Demo_Mode SHALL provide a DemoRepository (or FakeApiClient) that operates directly on the Demo_Database without invoking any network-backed ApiClient or Supabase service.
3. THE Demo_Mode SHALL reuse existing UI composables (AdminHomeScreen, TableView, MenuManagementScreen, OrderDetailSheet) with demo-qualified ViewModel instances backed by the DemoRepository.
4. THE Demo_Mode SHALL add a `DEMO_HOME` route to NavRoutes for demo-specific navigation, branching from the Role_Select_Screen.
