# Implementation Plan: Timezone, Order-Hold & QR Onboarding

## Overview

Three independent features (T, H, Q). Tasks already completed in the 2026-07-26/27 sessions
are checked and annotated `[done: …]`; everything else is open. Every task must leave
`./gradlew assembleDebug` (and, where the website changes, `npm run build`) green, and any
backend function change must be redeployed + spot-checked against the live Supabase project.

## Tasks

### Feature T — Timezone everywhere

- [x] 1. Auto-detect + sync the café timezone
  - [x] 1.1 Add `customer`/print timezone resolution: `PrintService.resolveTimezone()` reads
    `settingsDao.get()?.timezone` (default `Asia/Kuala_Lumpur`) and threads it into both
    document generators. _[done]_
    - _Requirements: T1.2, T2.5_
  - [x] 1.2 `KitchenSlipDocument.generate`/`generatePerCategory` take a `timezone` param and
    stamp the slip via `ZonedDateTime.now(zoneOf(timezone))` (safe fallback). _[done]_
    - _Requirements: T2.1_
  - [x] 1.3 `ReceiptDocument.generate` takes `timezone` and renders `createdAt` via
    `withZoneSameInstant(zone)` so cash & QR receipts show café-local time (was UTC). _[done]_
    - _Requirements: T2.2_
  - [x] 1.4 `AdminSettingsViewModel`: `onLocationCaptured` auto-detects
    `TimeZone.getDefault().id`; `loadPermissions` loads + mirrors to Room; `saveAll` pushes
    `putSettings({timezone})` + upserts Room; `isDirty`/`Snapshot` include timezone;
    `AdminSettingsScreen` shows it read-only. Seeded backend `timezone` row. _[done]_
    - _Requirements: T1.1, T1.2, T1.3, T1.4_

- [x] 2. Reports in café timezone
  - [x] 2.1 Injected `SettingsDao` into `ReportsViewModel`; resolves Cafe_Timezone once per
    report load (`settingsDao.get()?.timezone ?: DEFAULT_TZ`). _[done]_
    - _Requirements: T2.3_
  - [x] 2.2 `getDateRange` now builds its `Calendar` with `TimeZone.getTimeZone(cafeTz)` and a
    `dateOnlyFormat` bound to that zone, so today/this-week/this-month boundaries are
    café-local, not device-local. _[done]_
    - _Requirements: T2.3_
  - [x] 2.3 The report body only shows the café-local date range (no per-row wall-clock times),
    so once 2.2 makes the range café-local the whole PDF is consistent; café-name header
    already present. _[done]_
    - _Requirements: T2.3_

- [x] 3. Attendance in café timezone
  - [x] 3.1 Investigated: attendance events are stored UTC server-side (`attendance/index.ts`
    `new Date().toISOString()`) and there is NO client-rendered attendance timestamp view in
    the APK (check-in screen only validates GPS + posts). Nothing renders attendance time in a
    wrong zone, so there is no surface to convert. Documented: any future attendance list /
    export must format via `withZoneSameInstant(Cafe_Timezone)`. _[done — N/A, no surface]_
    - _Requirements: T2.4_

- [ ] 4. On-device timezone verification
  - [~] 4.1 Pay an order with "print receipt" on the paired printer; confirm the receipt time
    equals the kitchen-slip time for the same order (both Cafe_Timezone).
    - _Requirements: T3.1_
  - [~] 4.2 Confirm `orders.created_at` is still UTC in the DB (rendering-only conversion).
    - _Requirements: T3.2_

### Feature H — Hold before kitchen

- [x] 5. Backend hold setting
  - [x] 5.1 `settings/index.ts`: add `customer_order_hold_seconds → customerOrderHoldSeconds`,
    add to `PUBLIC_KEYS`, `coerceValue`→int, `validateSetting`→one of {10,15,30,60}. Deploy.
    _[done, deployed]_
    - _Requirements: H1.2_
  - [x] 5.2 Seed default `15` (`insert … on conflict do nothing`). _[done]_
    - _Requirements: H1.3_

- [ ] 6. Website customer hold
  - [x] 6.1 Add `holdSeconds` (fetched from public `GET settings`) + `holdRemaining` state;
    `handleConfirmOrder` starts the countdown; `useEffect` ticks and calls `submitHeldOrder()`
    at 0; `handleCancelHold` aborts; full-screen countdown overlay + i18n keys (5 locales).
    _[partial — coded, NOT built/verified]_
    - _Requirements: H2.1, H2.2, H2.3, H2.4_
  - [~] 6.2 `npm run build` to typecheck. If TS flags `submitHeldOrder` used-before-declaration
    in the effect, reorder so the function is declared before the effect (or wrap in a ref).
    - _Requirements: H2.1_
  - [~] 6.3 Deploy website; verify in-browser: confirm → countdown shows → Cancel aborts (no
    order created) → letting it elapse creates the order and shows status.
    - _Requirements: H2.1, H2.2, H2.3_

- [x] 7. APK admin/staff fixed 3 s hold _[done — HoldCountdownOverlay + 3s hold wired into TableViewViewModel.submitOrder, ManualDineInViewModel.submitOrder, StaffOrderViewModel.submitOrder; cancel discards cart; overlays rendered in AdminHomeScreen/ManualDineInScreen/StaffTableViewScreen; builds green]_
  - [~] 7.1 Add a `HoldCountdownOverlay(seconds, onCancel)` composable (or extend
    `BlockingLoadingOverlay`) with a visible countdown + Cancel.
    - _Requirements: H3.1_
  - [~] 7.2 Apply a 3 s hold before the real POST in `TableViewViewModel.submitOrder`,
    `ManualDineInViewModel.submitOrder`, and the `StaffOrderViewModel` create path; Cancel
    discards the pending cart and creates nothing.
    - _Requirements: H3.1, H3.2, H3.3_
  - [~] 7.3 On-device: submit as admin, confirm a 3 s countdown fires the order at 0 and Cancel
    aborts it.
    - _Requirements: H3.1, H3.3_

- [x] 8. Settings Hold_Delay selector _[done + verified on-device: SettingsResponse parses customerOrderHoldSeconds; AdminSettingsViewModel stages/loads/saves it; segmented 10/15/30/60 selector renders in AdminSettingsScreen with 15s selected]_
  - [~] 8.1 Add `customerOrderHoldSeconds` to the APK `SettingsResponse` parse + `getSettings`.
    - _Requirements: H1.1_
  - [~] 8.2 Add `holdSeconds` to `AdminSettingsViewModel` `UiState`+`Snapshot`+`isDirty`; load
    it; push in `saveAll` via `putSettings({customerOrderHoldSeconds})`.
    - _Requirements: H1.1_
  - [~] 8.3 Add a segmented 10/15/30/60 selector to `AdminSettingsScreen` (staged Save/Cancel).
    - _Requirements: H1.1_
  - [~] 8.4 On-device: change the delay, Save, place a customer order, confirm the new hold.
    - _Requirements: H1.1, H2.1_

### Feature Q — QR invite + camera onboarding

- [x] 9. Deep-link scheme _[done: App Link intent-filter (autoVerify) for warungtomyam.pages.dev/join in AndroidManifest; MainActivity parses ?invite= into DeepLinkInvite; OrderingConnectViewModel consumes it on init. Host caveat: invite URL currently emits the Supabase host, so full verified auto-open needs the backend to emit the website host + assetlinks.json — documented follow-up]_
  - [~] 9.1 Add an App Link `intent-filter` (`autoVerify=true`) for the café web host + `/join`
    path in `AndroidManifest.xml`, routed to an activity/entry that starts ordering
    registration with the `?invite=` token.
    - _Requirements: Q3.1, Q3.2_
  - [~] 9.2 Parse the `invite` param on cold-start/deep-link intent and feed it into the
    existing ordering-registration flow (server validates the token as today).
    - _Requirements: Q3.3, Q3.4_

- [x] 10. Admin invite QR _[done + verified on-device: QrCodeUtil (ZXing) encodes the invite URL; rendered as a scannable QR in the Staff Invitation section with URL text + Share/Regenerate kept]_
  - [~] 10.1 Add a QR encoder dependency (ZXing `core` or a Compose QR wrapper).
    - _Requirements: Q1.1_
  - [~] 10.2 Render the invite URL as a QR in the Staff Invitation section; re-encode on
    regenerate; keep the existing Share/link fallback.
    - _Requirements: Q1.1, Q1.2, Q1.3_

- [x] 11. Onboarding camera scanner _[done: CameraX + ZXing QrScannerScreen (FILL_CENTER preview, correct aspect ratio, single-fire decode, permission gate + manual fallback); wired into OrderingConnectScreen "Scan QR to connect"; builds+installs. On-device scan-with-real-QR verification pending (11.4)]_
  - [~] 11.1 Add CameraX + ML Kit barcode (or ZXing android-embedded) deps and the `CAMERA`
    permission (manifest + runtime request via `rememberPermissionHelper`).
    - _Requirements: Q2.1, Q2.3_
  - [~] 11.2 New scanner screen from Role Select → "connect as ordering device" (with "enter
    code instead" fallback); bind CameraX `Preview`/`PreviewView` at the correct display aspect
    ratio + orientation, with a framing guide.
    - _Requirements: Q2.1, Q2.4_
  - [~] 11.3 On decode: extract `invite` from the URL, stop the camera (debounced single fire),
    drive registration; on permission denial fall back to manual entry.
    - _Requirements: Q2.2, Q2.3_
  - [~] 11.4 On-device: show a real admin Invite_QR, scan it from a fresh device, land in
    registration and connect; verify preview isn't stretched and works in portrait.
    - _Requirements: Q2.1, Q2.2, Q2.4, Q3.1_

## Notes

- Features T, H, Q are independent — ship in any order. Within each, backend/settings tasks
  precede the client tasks that read them.
- Feature T is the closest to done: only Reports (task 2) + attendance (task 3) + on-device
  verify (task 4) remain; the print-path fix (the actual "out of sync" bug) is already live.
- Feature H's website hold (task 6) is coded but unbuilt — finish that build before the APK
  hold (task 7) so the two countdowns stay consistent in feel.
- Feature Q is greenfield and the largest; App Links (task 9) unblock both the QR payload
  (task 10) and the scanner's success action (task 11).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "1.4", "5.1", "5.2", "6.1"] },
    { "id": 1, "tasks": ["2.1", "2.2", "2.3", "3.1"] },
    { "id": 2, "tasks": ["4.1", "4.2"] },
    { "id": 3, "tasks": ["6.2"] },
    { "id": 4, "tasks": ["6.3"] },
    { "id": 5, "tasks": ["8.1", "8.2", "8.3"] },
    { "id": 6, "tasks": ["7.1", "7.2"] },
    { "id": 7, "tasks": ["7.3", "8.4"] },
    { "id": 8, "tasks": ["9.1", "9.2"] },
    { "id": 9, "tasks": ["10.1", "10.2", "11.1"] },
    { "id": 10, "tasks": ["11.2", "11.3"] },
    { "id": 11, "tasks": ["11.4"] }
  ]
}
```
