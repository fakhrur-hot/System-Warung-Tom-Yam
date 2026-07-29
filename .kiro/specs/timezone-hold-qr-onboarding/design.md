# Design Document: Timezone, Order-Hold & QR Onboarding

## Architecture Overview

Three independent features. T and H are mostly landed or scaffolded; Q is greenfield.

```
┌──────────────────────────────────────────────────────────────────────┐
│  FEATURE T — Timezone everywhere            single source: `timezone` │
│  T1 Auto-detect on location capture → save (backend + local Room) DONE│
│  T2 Kitchen slip + receipt render in Cafe_Timezone               DONE │
│  T2 Reports + attendance render in Cafe_Timezone                 TODO │
│  T3 On-device verify receipt time == kitchen time                TODO │
├──────────────────────────────────────────────────────────────────────┤
│  FEATURE H — Hold before kitchen (client-side hold)                   │
│  H1 Setting customerOrderHoldSeconds {10,15,30,60}      backend  DONE │
│  H1 Settings selector UI (staged Save/Cancel)                    TODO │
│  H2 Website: confirm → countdown → POST (cancellable)         PARTIAL │
│  H3 APK admin/staff fixed 3s hold before POST                    TODO │
├──────────────────────────────────────────────────────────────────────┤
│  FEATURE Q — QR invite + camera onboarding (greenfield)               │
│  Q1 Admin invite rendered as QR                                  TODO │
│  Q2 Fresh ordering device → in-app camera scanner → register     TODO │
│  Q3 Deep link opens only this app, plain-text readable           TODO │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Feature T — Timezone everywhere

### Root cause of "out of sync"

- `orders.created_at` is stored UTC (Postgres `now()`), correct.
- `ReceiptDocument` did `ZonedDateTime.parse(order.createdAt).format(...)` — the parsed zone
  is the UTC offset in the ISO string, so the receipt printed **UTC**.
- `KitchenSlipDocument` used `LocalTime.now()` — **device-local** time.
- A `timezone` setting existed (`Asia/Kuala_Lumpur` default) but **no renderer read it**.

Result: for a Malaysian café (device = MYT), the kitchen slip showed MYT while the receipt
showed UTC — an 8-hour mismatch.

### Approach — single source of truth

`timezone` setting → mirrored into local Room `SystemSettings.timezone` → read by
`PrintService.resolveTimezone()` and passed into both document generators. Backend stays UTC;
only rendering converts.

**Done:**
- `KitchenSlipDocument.generate/generatePerCategory(..., timezone)` — timestamp via
  `ZonedDateTime.now(zoneOf(timezone))`; `zoneOf` falls back to device zone on invalid id.
- `ReceiptDocument.generate(..., timezone)` — `ZonedDateTime.parse(createdAt)
  .withZoneSameInstant(zone).format(DATE_FORMAT)`.
- `PrintService.resolveTimezone()` = `settingsDao.get()?.timezone ?: "Asia/Kuala_Lumpur"`,
  threaded into both `printKitchenSlip` and `printReceipt`.
- `AdminSettingsViewModel`: `onLocationCaptured` sets `timezone = TimeZone.getDefault().id`;
  `loadPermissions` loads `timezone` from `getSettings()` and upserts local Room; `saveAll`
  pushes `putSettings({timezone})` + upserts Room; `isDirty`/`Snapshot` include timezone;
  `AdminSettingsScreen` shows it read-only.

**Remaining (TODO):**
- `ReportsViewModel`: `getDateRange` uses `Calendar.getInstance()` (device-local) and
  `dateOnlyFormat`/`isoFormat` have no zone. Convert both to Cafe_Timezone: build the
  `Calendar`/formatters with `TimeZone.getTimeZone(cafeTz)`. The PDF already draws a café
  header; also render any per-row times in Cafe_Timezone. Source the zone from
  `settingsDao.get()?.timezone` (inject `SettingsDao` — already have `apiClient`).
- Attendance: find the attendance list/print path and apply the same `ZonedDateTime …
  withZoneSameInstant(zone)` conversion at render.

### Decisions

- **Device zone, not GPS→zone lookup.** The admin is physically at the café when capturing
  location, so `TimeZone.getDefault()` is authoritative and needs no offline tz database.
- **Render-only conversion.** Never rewrite stored timestamps — avoids a migration and keeps
  the DB canonical in UTC.

---

## Feature H — Hold before kitchen

### Approach — client-side hold (no backend delayed job)

Edge Functions can't cheaply schedule per-order delayed work, and the user's intent is "the
customer can still cancel before it fires." So the order is simply **not submitted** until the
hold elapses. During the hold nothing exists server-side; cancel = don't submit. This is the
simplest design that literally holds the order before the kitchen sees it.

### Backend (DONE)

`settings/index.ts`: `customer_order_hold_seconds → customerOrderHoldSeconds`, added to
`PUBLIC_KEYS` (customer website reads it unauthenticated), `coerceValue` → int,
`validateSetting` → must be in `CUSTOMER_HOLD_OPTIONS = [10,15,30,60]`. Deployed. Seeded
default `15`.

### Website (PARTIAL — coded, not built/verified)

`App.tsx`: new state `holdSeconds` (fetched from public `GET settings`) and `holdRemaining`.
`handleConfirmOrder` now starts the countdown (`setHoldRemaining(holdSeconds)`) instead of
POSTing; a `useEffect` ticks it down and calls `submitHeldOrder()` (the former POST body) at
zero; `handleCancelHold` aborts. A full-screen countdown overlay shows the remaining seconds +
Cancel. i18n keys `holdSending` / `holdCancelBtn` / `holdHint` added to all 5 locales.
**Remaining:** `npm run build` to typecheck (note: `submitHeldOrder` is referenced by the
effect before its declaration — verify TS is happy; if not, reorder so the function is defined
before the effect), then deploy the website; on-device/browser verify the countdown + cancel.

### APK admin/staff 3 s hold (TODO)

Apply Staff_Order_Hold to the three submit paths:
- `TableViewViewModel.submitOrder()` (admin free-table entry)
- `ManualDineInViewModel.submitOrder()` (admin "+ New Dine-In Order")
- `StaffOrderViewModel` create-order path (ordering staff)

Design: introduce a small reusable "submitting hold" state (`holdRemaining: Int?`) on each VM,
plus a shared `BlockingLoadingOverlay`-style countdown composable with a Cancel button (the
existing `BlockingLoadingOverlay` from the order-flow-fixes spec can be extended, or a sibling
`HoldCountdownOverlay(seconds, onCancel)` added). The actual `apiClient.createOrder…` call fires
when the countdown hits 0; Cancel clears the pending cart. 3 s is a constant, not the setting.

### Settings selector UI (TODO)

Add a Hold_Delay selector (segmented 10/15/30/60) to `AdminSettingsScreen`, staged like the
other fields: add `holdSeconds` to `AdminSettingsViewModel.UiState` + `Snapshot` + `isDirty`,
load via `getSettings()` (add `customerOrderHoldSeconds` to the settings DTO parse), and push
in `saveAll` via `putSettings({customerOrderHoldSeconds})`.

### Decisions

- **Client-side, not server-scheduled.** Matches "cancel before it fires" and avoids fragile
  delayed-job infra.
- **Staff hold is a constant.** Trusted counter staff only need a mis-tap guard, not the
  customer grace window; keeping it out of the setting avoids confusion.

---

## Feature Q — QR invite + camera onboarding (greenfield)

### Deep-link scheme (Q3)

Encode an **Android App Link**: `https://<cafe-web-domain>/join?invite=<token>` (the same host
the customer site already uses). Add an `intent-filter` with `android:autoVerify="true"` for
that host + `/join` path in `AndroidManifest.xml` so a device **with the app** opens it
directly; a device without it opens the website (which can show "install the app" or ignore).
Because the payload is a normal `https://` URL, any generic QR reader reads it as a link —
satisfying "plain-text readable" and "opens only this app when installed" simultaneously.
(Alternative: a custom scheme `warungtomyam://join?invite=…` opens only the app but is *not*
readable/openable by generic readers — so App Links are preferred. If universal verification is
a problem, ship both: App Link primary, custom scheme as a secondary intent-filter.)

Server trust is unchanged: the token in `?invite=` is validated by the existing invite
verification on registration — the QR is only a transport.

### Admin QR rendering (Q1)

Generate the QR from the invite URL in `AdminSettingsScreen`'s Staff Invitation section. Use a
local QR encoder (e.g. ZXing `com.google.zxing:core` → `BitMatrix` → `Bitmap`) rendered in an
`Image`, or a Compose QR lib. Regenerate re-encodes on token change. Keep the existing Share
action for link fallback.

### Onboarding scanner (Q2, Q4)

New screen reached from Role Select → "connect as ordering device" (before the current manual
token screen, with a "enter code instead" fallback). Use CameraX + ML Kit Barcode Scanning
(`com.google.mlkit:barcode-scanning`) or ZXing's `DecoratedBarcodeView`. Key correctness points:
- **Aspect ratio:** bind the CameraX `Preview` with a `PreviewView` using
  `PreviewView.ScaleType.FILL_CENTER` and a `ResolutionSelector`/aspect-ratio strategy matching
  the display; drive it from a `LifecycleCameraController` so rotation/orientation are handled.
- On decode: parse the URL, extract `invite`, cancel the camera, and hand the token to the
  existing ordering-registration call. Debounce so one decode fires once.
- Permission: request `CAMERA` at entry; on denial, fall back to manual entry with guidance.

### Decisions

- **App Link over custom scheme** for readability + install-degradation.
- **Reuse the existing invite/registration backend** — no new endpoint, no new trust surface.
- **CameraX + ML Kit** (or ZXing) rather than hand-rolled camera — correct ratio/rotation is a
  stated requirement and these handle it.

### New dependencies (Q)

- QR encode: ZXing `core` (or a Compose QR wrapper).
- QR decode + camera: CameraX (`androidx.camera:*`) + ML Kit barcode, or ZXing android-embedded.
- New runtime permission: `CAMERA` (manifest + runtime request via the existing
  `rememberPermissionHelper` pattern).

---

## Cross-cutting notes

- **Settings DTO:** both `customerOrderHoldSeconds` (H) and `timezone` (T) flow through the
  same `GET/PUT settings` path; keep the APK `SettingsResponse` parse and `putSettings` in sync
  with the backend `KEY_MAP`.
- **No migration required** for any of the three features (settings rows + manifest +
  client code only).
- **Verification bias:** each feature has an explicit on-device verification task — timezone
  (receipt vs kitchen time), hold (customer countdown + cancel; staff 3 s), QR (scan a real
  admin QR on a fresh device and land in registration).
