# Design Document: Order Flow & Printing Fixes

## Architecture Overview

Two parts, strictly sequenced — Part A must be verified working before Part B starts, since B4 (session-grouped pending UI) is unusable until A1 (add-items actually reaches the backend) is fixed.

```
┌────────────────────────────────────────────────────────────────┐
│  PART A — Bug fixes                                             │
│  A1. Fix Order_Lifecycle_Endpoint URLs (client-only change)      │
│  A2. Wire PrinterDispatcher.alerts to visible UI                 │
│  A3. Replace connectAndPrint() stub with real DantSu calls       │
│  A4. Create missing menu-images Storage bucket                  │
├────────────────────────────────────────────────────────────────┤
│  PART B — New features (depends on A1, A2)                      │
│  B1. customerOrderAutoPrint setting (settings table + Edge Fns)  │
│  B3. Settings page → staged edits + universal Save/Cancel        │
│  B2. Blocking loading overlay on order submit                    │
│  B4. Pending-session UI + scoped "Print to Kitchen" (needs B1)   │
└────────────────────────────────────────────────────────────────┘
```

---

## Part A — Bug Fixes

### A1. Order-lifecycle URL routing

**Root cause.** Supabase Edge Functions route on the *first path segment* after `/functions/v1/` matching a deployed function slug exactly. `orders`, `orders-kitchen`, `orders-items`, `orders-status`, `orders-payment`, and `orders-cancel` are six **separate** deployed functions (confirmed via `supabase functions list`). `ApiClient` builds URLs like `$BASE_URL/orders/$orderId/kitchen` — the first segment is `orders`, so every one of these calls is silently routed to the `orders` function instead of the intended one:

- POST calls (`kitchen`, `items`, `payment`) land in `orders`'s `handleCreateOrder`, which requires `tableId` + non-empty `items[]` in the body — absent from these requests — so they 422.
- PUT/DELETE calls (`status`, cancel) aren't handled by `orders` at all (`orders` only accepts GET/POST) — hard 405.

Confirmed empirically: after a clean tap on "Reprint to Kitchen," the local `print_jobs` Room table has zero rows — proof `printService.printKitchenSlip()` (which would insert a job row via `PrinterDispatcher.dispatch`) is never reached at all.

**Fix.** Client-only. Each target Edge Function already extracts the order id flexibly — from a query param `orderId` first, falling back to scanning the URL path for a UUID-shaped segment (see `extractOrderIdFromPath` duplicated across `orders-kitchen`, `orders-items`, `orders-status`, `orders-payment`, `orders-cancel`). So the fix is purely changing the client's base path segment to the correct slug, keeping the order id as a trailing path segment:

| Method | Before | After |
|---|---|---|
| `sendToKitchen` / `sendToKitchenAsStaff` | `$BASE_URL/orders/$orderId/kitchen` | `$BASE_URL/orders-kitchen/$orderId` |
| `addItemsToOrder` / `addItemsToOrderAsStaff` | `$BASE_URL/orders/$orderId/items` | `$BASE_URL/orders-items/$orderId` |
| `updateOrderStatus` | `$BASE_URL/orders/$orderId/status` | `$BASE_URL/orders-status/$orderId` |
| `processPayment` / `processPaymentAsStaff` | `$BASE_URL/orders/$orderId/payment` | `$BASE_URL/orders-payment/$orderId` |
| `cancelOrder` / `cancelOrderAsStaff` | `$BASE_URL/orders/$orderId` | `$BASE_URL/orders-cancel/$orderId` |

No server-side changes. No contract (`shared/api-contract.md`) changes beyond noting the correct path shape if it currently documents the wrong one.

**Verification.** After the fix, repeat the same empirical check: tap Reprint to Kitchen, confirm a `print_jobs` row appears (even if it then fails at the Bluetooth layer per A3 — the point is it's *dispatched*, not silently dropped before reaching the dispatcher).

### A2. Printer alerts → visible UI

**Root cause.** `PrinterDispatcher.alerts` is a `SharedFlow<PrintAlert>` that nothing collects — confirmed via a full-codebase grep for `.alerts.collect` returning zero matches.

**Fix.** Collect the flow once at an app-wide scope (not per-screen, since printing can be triggered from `RealtimeService` which outlives any single Composable) and surface it via the existing snackbar pattern already used for order actions. Concretely:
- Inject `PrinterDispatcher` into `AdminHomeScreen` (or a small always-alive holder) and `LaunchedEffect(Unit) { printerDispatcher.alerts.collect { alert -> snackbarHostState.showSnackbar(alert.toMessage()) } }`.
- Add a `PrintAlert.toMessage(): String` mapping: `NoPrinterConfigured(type)` → "No printer configured for {type}"; `PrintFailed(name, error)` → "Print failed on {name}: {error}".
- Add a success case: today `PrinterDispatcher` has no "success" alert type, only failure/no-printer. Add `PrintAlert.PrintSucceeded(printerName: String, documentType: String)`, emitted from `executePrintJob` right after `printJobDao.updateStatus(job.id, COMPLETED)`.

### A3. Real Bluetooth printing

**Root cause.** `PrinterDispatcher.connectAndPrint()` is a hardcoded stub: `throw RuntimeException("Bluetooth printing requires Android runtime...")`, unconditional. The real logic already exists and was proven working in `BluetoothPrintSpike.kt` (DantSu `BluetoothPrintersConnections` + `EscPosPrinter`), but was never ported into the production dispatcher.

**Fix.** Replace the stub body with a real connection built from `printer.macAddress` (not `selectFirstPaired()` — production must target the *specific configured* printer, not just any paired device, since a café may have both a kitchen and a receipt printer paired simultaneously):

```kotlin
private fun connectAndPrint(printer: PrinterConfig, payload: String) {
    val device = BluetoothPrintersConnections().list
        ?.firstOrNull { it.device?.address == printer.macAddress }
        ?: throw IllegalStateException("Printer ${printer.name} (${printer.macAddress}) not paired or unreachable")

    val dpi = 203
    val printingWidthMM = if (printer.paperWidth == PaperWidth.FIFTY_EIGHT_MM) 48f else 72f
    val escPosPrinter = EscPosPrinter(device, dpi, printingWidthMM, printer.paperWidth.charWidth)
    escPosPrinter.printFormattedTextAndCut(payload)
}
```

Existing retry/status semantics in `executePrintJob` (MAX_RETRIES, job status transitions) are untouched — only the body of `connectAndPrint` changes.

**Permissions.** `BluetoothPrintSpike.ensureBluetoothPermissions(activity)` already implements the correct Android 12+ (`BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN`) vs. legacy permission logic. Call it from wherever a printer is first configured/tested (Printers screen), not from the background dispatch path — a background `Service`/coroutine cannot prompt for a runtime permission.

### A4. `menu-images` Storage bucket

**Root cause.** `menu-image/index.ts` uploads to `supabase.storage.from("menu-images")`. Querying `storage.buckets` shows only `logos` exists — `menu-images` was never created, so every upload fails with `500 Image upload failed: <bucket not found error>`.

**Fix.** One-time infrastructure fix, no code change:
```sql
insert into storage.buckets (id, name, public)
values ('menu-images', 'menu-images', true)
on conflict (id) do nothing;
```
Apply directly via `supabase db query` (same pattern already used for the `one_live_admin` index drop this session) rather than a full migration replay, to avoid re-triggering the already-applied `0001_initial_schema.sql`.

---

## Part B — New Features

### B1. `customerOrderAutoPrint` setting

Extends the existing `settings` key/value table and the `settings` Edge Function's `KEY_MAP` — the same mechanism already used for `staffCanSendKitchen`/`staffCanTakePayment`:

```ts
// settings/index.ts KEY_MAP addition
customer_order_auto_print: "customerOrderAutoPrint",
```
Boolean validation/coercion follows the existing `staffCanSendKitchen` pattern exactly. Seed a default row (`value = 'true'`) via a one-off `insert ... on conflict do nothing`, same style as A4.

**Where it's read:** server-side only, via direct DB query (not the public `/settings` API) from `orders/index.ts` (`handleCreateOrder`) and `orders-items/index.ts`, at the point each currently hardcodes `sentToKitchen: true`:

```ts
const { data: autoPrintSetting } = await supabase
  .from("settings").select("value").eq("key", "customer_order_auto_print").single();
const autoPrint = autoPrintSetting?.value !== "false"; // default true if row missing
```

When `autoPrint` is false: new lines get `sentToKitchen: false`; the order's `sent_to_kitchen_at` is left `null` (order creation) or simply not touched (add-items); the existing `NEW_ORDER`/`ITEMS_ADDED` broadcast still fires unconditionally (so the admin device is still "pinged" — beep + badge via the already-wired `RealtimeService` listener) but `RealtimeService.handleNewOrderMessage`/`handleItemsAddedMessage` must skip the `printService.printKitchenSlip(...)` call when the incoming payload's items are all `sentToKitchen: false`.

### B3. Settings page — staged edits + universal Save/Cancel

Current state: three different save models coexist on one screen — Staff Permissions toggles save instantly per-flip; Café Location and Café Profile each have their own section-scoped Save button; Staff Invitation's Regenerate is an immediate one-shot action (correctly excluded from "settings" per Requirement B3.5).

**Fix.** Introduce a staged/dirty layer in `AdminSettingsViewModel`:
- Add `savedState` (last-known-good, loaded from backend) alongside the existing mutable `uiState` fields, or simpler: add an `isDirty: Boolean` computed by comparing current field values to a cached "as-loaded" snapshot taken right after each `load*()` call.
- `updateStaffCanSendKitchen`/`updateStaffCanTakePayment` stop calling `pushPermissions()` immediately — they just update local state and mark dirty.
- `saveCafeLocation()`/`saveBranding()` are removed as independent entry points from the UI; their logic is absorbed into one `saveAll()` that runs whichever of {permissions, location, branding} are dirty, sequentially, and reports one aggregate success/error.
- Add `cancelAll()`: reloads from `load*()` for every section, discarding local edits.
- The screen replaces the three separate Button/Switch-triggered saves with one bottom (or top) bar: `Cancel` / `Save`, both `enabled = uiState.isDirty`.

### B2. Order-submit loading overlay

A small reusable composable, not tied to any one screen:
```kotlin
@Composable
fun BlockingLoadingOverlay(visible: Boolean) {
    if (!visible) return
    Box(Modifier.fillMaxSize().pointerInput(Unit) { /* consume all touches */ }
        .background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
```
Rendered from `AdminHomeScreen`/`StaffTableViewScreen` keyed on `orderEntry.isSubmitting` (new-order path, already exists as a field) and `orderDetail.isLoading` (add-items path, already exists). No new state needed — both flags already exist in the respective view-models; this is purely a UI addition plus intercepting the system back button while either flag is true (`BackHandler(enabled = isSubmitting) {}`).

### B4. Pending-session UI

Builds directly on the existing session-grouping already in `OrderDetailSheet.kt` (`state.items.groupBy { it.sessionNumber }`). Extend the grouping to also partition by `sentToKitchen` within a session:

```kotlin
val (confirmed, pending) = state.items.groupBy { it.sessionNumber }
    .mapValues { (_, items) -> items.partition { it.sentToKitchen } }
```

For a session where the pending partition is non-empty, render it as its own labeled block ("Session N — Pending confirmation") with a scoped `onConfirmSession(sessionNumber)` button instead of folding straight into the normal item list. Confirming calls a new, narrow endpoint (or extends `orders-kitchen`) that:
1. Sets `sentToKitchen = true` on just that session's items in `items_json`.
2. Returns exactly those lines as `linesToPrint` so the client prints only the newly-confirmed round (matching the existing amendment-print/`isAmendment=true` path already in `RealtimeService`/`PrintService`).

This reuses A1's now-correct `orders-kitchen` plumbing rather than inventing a new Edge Function; the smallest change is a `sessionNumber` optional field on the existing reprint request body — when present, scope the mutation/print to that session instead of the whole order.
