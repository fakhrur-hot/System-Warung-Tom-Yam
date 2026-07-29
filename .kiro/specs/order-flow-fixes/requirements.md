# Requirements Document

## Introduction

This spec covers two classes of work discovered/requested in the 2026-07-24 session:

1. **Bug fixes** — a systemic URL-routing defect that silently breaks nearly every order-lifecycle action except placing a brand-new order (reprint, add-items/2nd session, status update, payment, cancel — for both admin and staff), plus three related printing/upload defects found while diagnosing it (kitchen printing never actually implemented, print failures never surfaced to the user, menu-photo upload always 500s).
2. **New features** — a café-owner setting controlling whether an order round prints to the kitchen automatically or waits for cashier confirmation, a blocking loading overlay while an order is being placed, a settings-page redesign to one universal Save/Cancel, and session-grouped order-detail behavior driven by the new setting.

This is a **bug-fix-first** effort: Part A must land and be verified before Part B begins, per explicit instruction.

## Glossary

- **Order_Lifecycle_Endpoint**: One of the five separately-deployed Edge Functions that act on an *existing* order — `orders-kitchen` (reprint), `orders-items` (add a round), `orders-status` (PREPARING/READY), `orders-payment` (cash/QR), `orders-cancel` (cancel). Each is a distinct Supabase Functions slug, distinct from the `orders` function that only handles `GET`/`POST /orders` (list/create).
- **Session**: One round of items placed on a still-occupied table — session 1 is the initial order, session 2+ are amendments added via `orders-items` while the table remains occupied (see `sessionNumber` on `OrderItem`).
- **Auto_Print_Setting**: A new café-wide boolean (`customerOrderAutoPrint`) controlling whether a newly-placed order/round is marked `sentToKitchen` and printed immediately, or left pending until the cashier explicitly confirms it.
- **Pending_Section**: A session's items when `Auto_Print_Setting` is off and they have not yet been confirmed — shown in the order-detail sheet as its own group with a "Print to Kitchen" action, distinct from already-confirmed sessions.
- **Print_Job**: A row in the local `print_jobs` Room table tracking one dispatch attempt to one physical printer (queued/printing/completed/failed).

## Requirements — Part A: Bug Fixes

### Requirement A1: Order-lifecycle calls reach the correct Edge Function

**User Story:** As a cashier, I want reprint, add-items, status update, payment, and cancel to actually work, so that the POS reflects what really happened instead of silently failing.

#### Acceptance Criteria

1. THE app SHALL construct every Order_Lifecycle_Endpoint request URL using that endpoint's actual deployed function slug (`orders-kitchen`, `orders-items`, `orders-status`, `orders-payment`, `orders-cancel`) as the first path segment, not the generic `orders` slug.
2. THIS SHALL be fixed for both the admin and staff client methods (`sendToKitchen`/`sendToKitchenAsStaff`, `addItemsToOrder`/`addItemsToOrderAsStaff`, `updateOrderStatus`, `processPayment`/`processPaymentAsStaff`, `cancelOrder`/`cancelOrderAsStaff`).
3. WHEN "Reprint to Kitchen" is tapped, THE app SHALL create a `print_jobs` row (or reach the "no printer configured" alert path) — verified by inspecting local Room state after the tap, not just the absence of a crash.
4. WHEN a second round of items is added to an occupied table, THE new items SHALL persist to the backend `orders.items_json` and appear in the order-detail sheet grouped under the next `sessionNumber`.
5. THE existing Edge Functions (`orders-kitchen`, `orders-items`, `orders-status`, `orders-payment`, `orders-cancel`) SHALL NOT require changes — each already accepts the order id via path segment or `orderId` query param; only the client URL construction is wrong.

### Requirement A2: Print failures and successes are visible

**User Story:** As a cashier, I want to know whether a kitchen slip actually printed, so that I don't wonder whether an order went to the kitchen.

#### Acceptance Criteria

1. WHEN `PrinterDispatcher` emits `NoPrinterConfigured` or `PrintFailed`, THE app SHALL surface it as a visible, human-readable message (snackbar or equivalent) on the current screen.
2. WHEN a print job completes successfully, THE app SHALL surface a brief confirmation distinct from the "no printer" / "failed" cases.
3. THE alert SHALL name the affected document (kitchen slip vs. receipt) and, for failures, the printer name where known.

### Requirement A3: Real Bluetooth printing is wired into production

**User Story:** As a café owner, I want kitchen slips and receipts to actually print on my paired thermal printer, so that the kitchen and customers get physical output.

#### Acceptance Criteria

1. THE production print path (`PrinterDispatcher.connectAndPrint`) SHALL use the real DantSu `BluetoothConnection`/`EscPosPrinter` calls (already proven in `BluetoothPrintSpike.kt`), not the placeholder that unconditionally throws.
2. THE dispatcher SHALL still honor existing retry/status semantics (`QUEUED → PRINTING → COMPLETED`/`FAILED`, `MAX_RETRIES`) unchanged.
3. IF Bluetooth permissions are not yet granted, THEN THE app SHALL request them at the point printing is attempted (or a clear one-time setup screen), not fail silently.

### Requirement A4: Menu photo upload succeeds

**User Story:** As a café owner, I want to upload a menu item photo without a server error, so that my menu can show real photos.

#### Acceptance Criteria

1. THE Supabase Storage project SHALL have a `menu-images` bucket (public, matching the existing `logos` bucket's configuration) — its absence is the entire cause of the current `500 Image upload failed`.
2. WHEN an admin uploads a menu photo within the documented size/format limits, THE request SHALL succeed and return a public URL.
3. THE fix SHALL NOT require any change to `menu-image/index.ts` or the client upload code — both already assume the bucket exists.

## Requirements — Part B: New Features

### Requirement B1: Café-owner auto-print vs. ping-cashier setting

**User Story:** As a café owner, I want to choose whether new orders print to the kitchen automatically or wait for my cashier to confirm, so that I can control exactly when food prep starts.

#### Acceptance Criteria

1. THE app SHALL provide a new settings key `customerOrderAutoPrint: Boolean`, defaulting to `true` (preserves current always-auto-print behavior for anyone who doesn't touch the new setting).
2. WHEN `customerOrderAutoPrint` is `true`, THE order-creation and add-items paths SHALL behave exactly as they do today: every new/added item is `sentToKitchen: true` and prints immediately.
3. WHEN `customerOrderAutoPrint` is `false`, THE order-creation and add-items paths SHALL mark new items `sentToKitchen: false` and SHALL NOT trigger a kitchen print — the admin device SHALL still be notified (sound/badge) that a new order/round arrived, without printing it ("ping the cashier").
4. THE setting SHALL be changeable from Admin Settings and take effect for orders placed after the change (in-flight orders are not retroactively altered).

### Requirement B2: Blocking loading overlay while an order is placed

**User Story:** As a cashier, I want the screen to clearly show "working" while I submit an order, so that I don't double-tap or think the app froze.

#### Acceptance Criteria

1. WHEN an order submission (new order or add-items) is in flight, THE app SHALL show a full-screen (or full-sheet) loading overlay that blocks further taps on the underlying UI until the request resolves.
2. THE overlay SHALL be dismissed automatically on success or failure, with the existing success/error snackbar still shown.
3. THE overlay SHALL NOT be dismissible by the back button while the request is in flight (prevents a race where the user navigates away mid-submit).

### Requirement B3: Settings page — one universal Save/Cancel

**User Story:** As a café owner, I want one Save and one Cancel for the whole Settings page, so that I'm not hunting for which section's button applies to which change.

#### Acceptance Criteria

1. THE Admin Settings screen SHALL stage all edits (staff permission toggles, café location, café name, café logo) locally in view-model state without writing to the backend until Save is pressed.
2. A single Save action SHALL persist every staged change across all sections in one user-visible action (may be multiple network calls internally, but one tap and one resulting confirmation).
3. A single Cancel action SHALL discard all staged changes and reload the last-saved server state for every section.
4. THE Save/Cancel controls SHALL only be enabled when there is at least one staged, unsaved change; otherwise they SHALL appear disabled.
5. Actions that are not "settings" in this sense (Regenerate invite, Capture current GPS location as a candidate) remain immediate — only committing that candidate value to the backend is deferred to Save.

### Requirement B4: Session-grouped order detail reflects the auto-print setting

**User Story:** As a cashier, I want to see clearly which rounds of an order have gone to the kitchen and which are waiting on me, so that nothing gets forgotten or double-sent.

#### Acceptance Criteria

1. WHEN `customerOrderAutoPrint` is `true`, adding a new round of items SHALL behave as today: the new session's items are immediately `sentToKitchen: true` and merge into the order display with no extra action required.
2. WHEN `customerOrderAutoPrint` is `false`, a newly-added round SHALL render as a distinct Pending_Section within the order-detail sheet (its own "Session N — Pending" grouping), separate from already-confirmed sessions, regardless of the order's overall status (PREPARING/READY/etc. do not block this).
3. THE Pending_Section SHALL show a "Print to Kitchen" action scoped to just that session's items; tapping it SHALL mark those items `sentToKitchen: true`, trigger the kitchen print for exactly that session's lines, and fold the section into the normal (non-pending) session display.
4. Until confirmed, Pending_Section items SHALL still count toward the order's displayed subtotal/grand total.
5. THIS requirement builds on Requirement A1 (add-items must actually persist) — without A1 fixed, no session, pending or otherwise, can appear at all.
