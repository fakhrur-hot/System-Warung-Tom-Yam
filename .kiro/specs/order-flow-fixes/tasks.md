# Implementation Plan: Order Flow & Printing Fixes

## Overview

Part A (bugs) lands fully before Part B (features) starts — B4 is unusable until A1 is fixed, and B1/B2/B3 are cleaner to build on top of a verified-working order-lifecycle. Every task must leave `./gradlew assembleDebug` green and, where a backend function changes, must be redeployed and spot-checked against the live Supabase project before moving on.

## Tasks

### Part A — Bug Fixes

- [x] 1. Fix Order_Lifecycle_Endpoint URL routing (client-only)
  - [x] 1.1 In `ApiClient.kt`, change the five admin methods' URLs to the correct flat function slug: `sendToKitchen` → `orders-kitchen/$orderId`, `addItemsToOrder` → `orders-items/$orderId`, `updateOrderStatus` → `orders-status/$orderId`, `processPayment` → `orders-payment/$orderId`, `cancelOrder` → `orders-cancel/$orderId`
    - _Requirements: A1.1, A1.2_
  - [x] 1.2 Repeat for the four staff-variant methods (`sendToKitchenAsStaff`, `addItemsToOrderAsStaff`, `processPaymentAsStaff`, `cancelOrderAsStaff`)
    - _Requirements: A1.1, A1.2_
  - [x] 1.3 Build, install, and verify on-device: tapped Reprint to Kitchen → `print_jobs` row `dc7b116d…|unassigned|KITCHEN_SLIP|QUEUED` now created (was empty before the fix). Reaches PrinterDispatcher; queues as "no printer configured" as expected until task 3.
    - _Requirements: A1.3_
  - [x] 1.4 Verified add-items: added a 2nd round to order `7f2a6cfb` (table T0004) → backend `items_json` now has 2 entries, second with `sessionNumber: 2`; both `nameSnapshot` plain strings (confirms extractDisplayName fix too).
    - _Requirements: A1.4_

- [x] 2. Wire printer alerts to visible UI
  - [x] 2.1 Add `PrintAlert.PrintSucceeded(printerName, documentType)`; emit it from `PrinterDispatcher.executePrintJob` on the `COMPLETED` path
    - _Requirements: A2.2_
  - [x] 2.2 Add a `PrintAlert.toMessage(): String` mapping for all three alert types
    - _Requirements: A2.1, A2.3_
  - [x] 2.3 Collect `printerDispatcher.alerts` once from `AdminHomeScreen` (survives screen navigation via the shared snackbarHostState) and show each as a snackbar
    - _Requirements: A2.1_

- [ ] 3. Replace the Bluetooth print stub with real DantSu calls
  - [x] 3.1 Rewrote `PrinterDispatcher.connectAndPrint`: resolves the configured printer by `macAddress` via `BluetoothPrintersConnections().list` (not "first paired"), builds `EscPosPrinter(connection, 203, 48f/72f, charWidth)`, calls `printFormattedTextAndCut`. Throws on unreachable printer so executePrintJob's retry/FAILED + PrintAlert path fires. Builds green.
    - _Requirements: A3.1, A3.2_
  - [x] 3.2 Added a `RequestMultiplePermissions` launcher + `withBtPermissions{}` gate in `PrintersScreen`; scan and test-print now request `BLUETOOTH_CONNECT`+`BLUETOOTH_SCAN` (API 31+) from the Activity before running, then run the deferred action on grant.
    - _Requirements: A3.3_
  - [ ] 3.3 On-device: pair a real (or emulated) thermal printer, configure it in the Printers screen, place an order, confirm a physical/simulated print occurs and the job reaches `COMPLETED` — DEFERRED: needs a real paired thermal printer, none available in this environment.
    - _Requirements: A3.1, A3.2_

- [x] 4. Fix menu photo upload 500
  - [x] 4.1 Create the `menu-images` Storage bucket (public) via `supabase db query`, matching `logos`' configuration
    - _Requirements: A4.1_
  - [x] 4.2 Verified on-device: picked a photo for the Nasi Putih item → object `be21c608-…-1784908780083.jpg` now present in the `menu-images` bucket and preview renders in Edit Item. Same flow that 500'd before now succeeds.
    - _Requirements: A4.2_

### Part B — New Features

- [x] 5. `customerOrderAutoPrint` setting — backend
  - [x] 5.1 Add `customer_order_auto_print` → `customerOrderAutoPrint` to `settings/index.ts`'s `KEY_MAP`/`validateSetting`/`coerceValue`, boolean, following the `staffCanSendKitchen` pattern
    - _Requirements: B1.1_
  - [x] 5.2 Seed the default row (`value = 'true'`) via `supabase db query` (`insert ... on conflict do nothing`)
    - _Requirements: B1.1_
  - [x] 5.3 In `orders/index.ts` `handleCreateOrder`, read the setting server-side and set `sentToKitchen`/`sent_to_kitchen_at`/initial `status` conditionally instead of unconditionally `true`/`SENT_TO_KITCHEN`
    - _Requirements: B1.2, B1.3_
  - [x] 5.4 Same conditional in `orders-items/index.ts` for amendment lines
    - _Requirements: B1.2, B1.3_
  - [x] 5.5 In `RealtimeService.handleNewOrderMessage`/`handleItemsAddedMessage`, skip the `printService.printKitchenSlip` call when the incoming items are `sentToKitchen: false` (still insert to Room, still beep — "ping" without printing)
    - _Requirements: B1.3_
  - [x] 5.6 Deploy `settings`, `orders`, `orders-items`; verify a test order placed with the setting off does NOT print and does NOT set `sent_to_kitchen_at`
    - _Requirements: B1.2, B1.3_

- [x] 6. Settings page — staged edits + universal Save/Cancel
  - [x] 6.1 Add a "last-loaded snapshot" + `isDirty` computed property to `AdminSettingsViewModel`'s `UiState`
    - _Requirements: B3.1, B3.4_
  - [x] 6.2 Change `updateStaffCanSendKitchen`/`updateStaffCanTakePayment` to stop calling `pushPermissions()` immediately — local state only
    - _Requirements: B3.1_
  - [x] 6.3 Replace `saveCafeLocation()`/`saveBranding()` as independent UI actions with one `saveAll()` that pushes every dirty section and reports one aggregate result; add `cancelAll()` that reloads every section from the backend
    - _Requirements: B3.2, B3.3_
  - [x] 6.4 Replace the three separate save buttons in `AdminSettingsScreen` with one bottom Save/Cancel bar, both `enabled = uiState.isDirty`; leave Regenerate-invite and Capture-GPS-location as immediate actions per B3.5
    - _Requirements: B3.4, B3.5_
  - [x] 6.5 On-device: change a toggle + the café name without tapping anything else, confirm nothing persists until Save; confirm Cancel reverts every field
    - _Requirements: B3.1, B3.2, B3.3_

- [x] 7. Order-submit loading overlay
  - [x] 7.1 Add a small `BlockingLoadingOverlay(visible: Boolean)` composable per the design doc
    - _Requirements: B2.1_
  - [x] 7.2 Render it from `AdminHomeScreen`/`StaffTableViewScreen` keyed on `orderEntry.isSubmitting` and `orderDetail.isLoading`; add `BackHandler(enabled = <either flag>) {}` to block back-navigation mid-submit
    - _Requirements: B2.1, B2.2, B2.3_
  - [x] 7.3 On-device: submit an order, confirm the overlay blocks taps and disappears on success/failure with the existing snackbar still showing
    - _Requirements: B2.2_

- [x] 8. Pending-session UI for non-auto-print orders
  - [x] 8.1 Extend `orders-kitchen/index.ts`'s reprint body to accept an optional `sessionNumber`; when present, scope the `sentToKitchen` mutation and returned `linesToPrint` to just that session's items instead of the whole order
    - _Requirements: B4.3_
  - [x] 8.2 In `OrderDetailSheet.kt`, partition each session's items by `sentToKitchen`; render a non-empty pending partition as its own "Session N — Pending confirmation" block with a scoped "Print to Kitchen" button
    - _Requirements: B4.2, B4.3_
  - [x] 8.3 Added `confirmSession(orderId, sessionNumber)` to both `TableViewViewModel` (prints via `printKitchenSlip` isAmendment=true) and `StaffOrderViewModel` (no printer — reflects to Room only); added `sessionNumber` to `sendToKitchenAsStaff`; wired `onConfirmSession` at both `AdminHomeScreen` and `StaffTableViewScreen` call sites. NOTE: this subtask was previously marked done but the VM method + call-site wiring were missing (button was a no-op) — implemented 2026-07-26.
    - _Requirements: B4.3_
  - [x] 8.4 Verified on-device: with both sessions pending, Subtotal/Grand Total showed RM 3.00 (both sessions counted); after confirming session 1, total stayed RM 3.00. Totals sum all items regardless of `sentToKitchen`.
    - _Requirements: B4.4_
  - [x] 8.5 Verified on-device 2026-07-26 with `customer_order_auto_print=false`: placed order on T0004 → created `status=RECEIVED`, `sent_to_kitchen_at=null`, session-1 item `sentToKitchen=false` (table showed orange "New"). Added a 2nd round → session 2 also `sentToKitchen=false`, no auto-print. UI rendered both as "Session N — Pending confirmation" with scoped Print buttons. Tapped "Print to Kitchen (Session 1)" → backend session-1 items flipped to `sentToKitchen=true` (session 2 unchanged), a `KITCHEN_SLIP` print_job (`b7f19d07…`, "TAMBAHAN / ADDED", exactly 1 item) was created, and session 1 folded into the normal confirmed display while session 2 stayed pending.
    - _Requirements: B4.1, B4.2, B4.3_

## Notes

- Part A must fully land before Part B — B4 (pending-session UI) is meaningless until A1 (add-items reaches the backend) works.
- Task 1 (A1) is the highest-value fix: one client-only URL correction restored reprint, add-items/2nd-session, status, payment, and cancel for both admin and staff. Verified on-device 2026-07-24: add-items produced a `sessionNumber: 2` entry in backend `items_json`; reprint produced a `print_jobs` row (`QUEUED`/`unassigned` — expected until task 3 wires real Bluetooth).
- No server-side Edge Function changes are needed for Part A tasks 1–2; task 4 is pure infra (create a Storage bucket); task 3 is client-only (DantSu wiring).
- Part B tasks 5, 8 touch Edge Functions (`settings`, `orders`, `orders-items`, `orders-kitchen`) and must be redeployed + spot-checked before their on-device verification subtasks.
- `customerOrderAutoPrint` defaults to `true` so existing always-auto-print behavior is preserved for anyone who never opens the new setting.

## Re-audit (2026-07-26)

All completed tasks re-audited against the live code/DB. Two findings:
- **8.3 was marked done but not implemented** — `OrderDetailSheet` had the `onConfirmSession` param + "Print to Kitchen (Session N)" button, but no `confirmSession` view-model method existed and neither `AdminHomeScreen` nor `StaffTableViewScreen` passed `onConfirmSession`, so the button was a silent no-op. Implemented + wired end-to-end (admin + staff) and verified via 8.5. Fixed.
- **No in-app toggle for `customerOrderAutoPrint`** (FOLLOW-UP, out of the original task scope). The setting is backend-only — `settings/index.ts` KEY_MAP + `orders`/`orders-items` read it — and there is no `Switch` for it in `AdminSettingsScreen`. The feature is fully functional but a café owner cannot turn the pending-confirmation mode on/off without a direct DB/API call. Recommend adding a staged toggle in the Settings page (fits the task-6 save/cancel pattern) as a follow-up.
- Setting was flipped to `false` for the 8.5 test, then restored to the documented default `true`.
- Tasks verified sound with no changes needed: 1 (URL routing), 2 (`PrintAlertsViewModel` + `toMessage` + snackbar collection), 4 (`menu-images` bucket + upload), 5 (backend conditional auto-print — confirmed live: order placed with setting off came back `RECEIVED`/pending and did NOT auto-print), 6 (`isDirty`/`saveAll`/`cancelAll` + single Save/Cancel bar, toggles staged), 7 (`BlockingLoadingOverlay` + `BackHandler` in both admin & staff screens), 8.1/8.2/8.4.
- 3.3 remains the only genuinely open item: needs a real paired thermal printer to verify a job reaches `COMPLETED` (deferred — no hardware in this environment). `connectAndPrint` DantSu wiring (3.1) and BT-permission gating (3.2) are done and build green.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["1.3", "1.4"] },
    { "id": 2, "tasks": ["2.1", "2.2", "2.3", "4.1"] },
    { "id": 3, "tasks": ["4.2", "3.1", "3.2"] },
    { "id": 4, "tasks": ["3.3"] },
    { "id": 5, "tasks": ["5.1", "5.2"] },
    { "id": 6, "tasks": ["5.3", "5.4", "5.5"] },
    { "id": 7, "tasks": ["5.6"] },
    { "id": 8, "tasks": ["6.1", "6.2", "6.3", "6.4"] },
    { "id": 9, "tasks": ["6.5", "7.1"] },
    { "id": 10, "tasks": ["7.2", "7.3"] },
    { "id": 11, "tasks": ["8.1", "8.2"] },
    { "id": 12, "tasks": ["8.3", "8.4"] },
    { "id": 13, "tasks": ["8.5"] }
  ]
}
```
