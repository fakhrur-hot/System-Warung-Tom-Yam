# Requirements Document

## Introduction

This spec covers three feature upgrades requested in the 2026-07-26/27 sessions, plus the
verification/finishing of work already partially applied. All three are independent and
can ship in any order, but they share one theme: making café-wide configuration (timezone,
order-hold delay, staff onboarding) consistent and correct across the Android app (APK),
the customer website, and the Supabase backend.

1. **Timezone everywhere (T)** — one café timezone, auto-detected from the device when the
   café location is captured, used as the single source of truth for every timestamp the
   café or a customer ever sees (database, reports PDF, kitchen slips, cash & QR receipts,
   attendance). Today these are out of sync: receipts render in UTC while kitchen slips use
   device-local time.
2. **Hold before kitchen (H)** — a configurable grace delay before a *customer* order is
   actually sent/printed to the kitchen, during which the customer can still cancel. Admin
   and ordering-staff orders use a short fixed 3 s hold. The delay is chosen in Settings
   (10 / 15 / 30 / 60 s).
3. **QR staff invitation + camera-scan onboarding (Q)** — the admin's staff-invite link is
   presented as a scannable QR code; a freshly-installed device connecting as an ordering
   device opens an in-app camera scanner to read that QR and connect. The QR encodes a
   deep link that opens *only* this app when scanned in-app, while remaining plain-text
   readable by any generic QR scanner.

**Status legend used throughout:** `[DONE]` already implemented + built, `[PARTIAL]` started,
`[TODO]` not started. Accurate as of 2026-07-27 (see `tasks.md` for the authoritative list).

## Glossary

- **Cafe_Timezone**: The single IANA timezone id (e.g. `Asia/Kuala_Lumpur`) stored as the
  `timezone` setting (DB key `timezone`, API key `timezone`). Authoritative for all
  timestamp rendering. Auto-detected from `TimeZone.getDefault()` when café location is
  captured; editable only via that capture (no free-text entry).
- **Hold_Delay**: The number of seconds a **customer-placed** order waits before it is
  actually submitted/sent to the kitchen. Stored as the `customerOrderHoldSeconds` setting
  (DB key `customer_order_hold_seconds`), one of {10, 15, 30, 60}. Public (readable by the
  customer website without auth).
- **Staff_Order_Hold**: A fixed 3 s hold applied to orders placed by the admin device or an
  ordering-staff device, independent of Hold_Delay. Not configurable.
- **Ordering_Key_Invite**: The single active staff-invitation token the admin shares so a
  device can register as an ordering device (existing `GET /invite` / regenerate flow).
- **Invite_QR**: A QR code encoding a deep link that carries the Ordering_Key_Invite. Scheme
  opens this app directly when scanned in-app; the payload is a plain `https://` (or custom
  scheme + https fallback) URL so any generic QR reader can still read it as text.
- **Onboarding_Scanner**: An in-app camera view shown to a fresh device choosing "connect as
  ordering device", which decodes an Invite_QR and drives the existing registration flow.

## Requirements — Feature T: Timezone everywhere

### Requirement T1: One auto-detected café timezone

**User Story:** As a café owner, I want the app to detect my timezone automatically when I
set my café location, so that I never have to configure it and every timestamp is correct.

#### Acceptance Criteria

1. WHEN the admin captures the café GPS location in Settings, THE app SHALL set Cafe_Timezone
   to the device's current zone (`TimeZone.getDefault().id`) as a staged edit.
2. WHEN the admin saves Settings, THE app SHALL persist Cafe_Timezone to the backend
   `timezone` setting AND mirror it into local Room `SystemSettings.timezone`.
3. WHEN Settings loads, THE app SHALL read Cafe_Timezone from the backend and display it
   read-only under the café-location section.
4. THE app SHALL NOT offer free-text timezone entry — Cafe_Timezone is only ever set by (1).

### Requirement T2: All printed/rendered timestamps use Cafe_Timezone

**User Story:** As a cashier, I want the kitchen slip, the customer receipt, and the reports
to all show the same wall-clock time, so that nothing looks inconsistent or wrong.

#### Acceptance Criteria

1. THE kitchen slip SHALL render its timestamp in Cafe_Timezone. *(DONE)*
2. THE customer receipt (cash and QR) SHALL render `order.createdAt` (stored UTC) converted
   to Cafe_Timezone. *(DONE)*
3. THE reports PDF SHALL compute its date ranges (today / this week / this month) and render
   all displayed dates/times in Cafe_Timezone, not device-local. *(TODO)*
4. THE attendance views (check-in/out lists and any exported/printed attendance) SHALL render
   timestamps in Cafe_Timezone. *(TODO)*
5. WHERE a timezone id is invalid or blank, THE app SHALL fall back to the device zone rather
   than crash. *(DONE for print docs)*

### Requirement T3: On-device verification of timezone consistency

**User Story:** As the developer, I want to confirm on real hardware that a receipt and a
kitchen slip printed for the same order show the same time.

#### Acceptance Criteria

1. WHEN an order is paid with "print receipt", THE printed receipt time SHALL equal the
   kitchen-slip time for that order (same Cafe_Timezone), verified on the paired printer.
2. THE backend `orders.created_at` SHALL remain stored in UTC (no schema change) — only
   *rendering* converts to Cafe_Timezone.

## Requirements — Feature H: Hold before kitchen

### Requirement H1: Configurable customer hold delay

**User Story:** As a café owner, I want customer orders to wait a chosen number of seconds
before hitting my kitchen, so that customers can fix mistakes and my kitchen isn't spammed.

#### Acceptance Criteria

1. THE Settings page SHALL let the admin choose Hold_Delay from {10, 15, 30, 60} seconds,
   staged and committed via the existing universal Save/Cancel bar. *(TODO)*
2. THE Hold_Delay SHALL be stored as `customerOrderHoldSeconds` and be readable by the
   customer website without authentication (public setting). *(DONE — backend)*
3. THE default Hold_Delay SHALL be 15 s so existing behavior is a short, sane grace period.
   *(DONE — seeded)*

### Requirement H2: Customer order is held before the kitchen sees it

**User Story:** As a customer, after I confirm my order I want a brief countdown during which
I can still cancel, so that a mis-tap doesn't send the wrong food.

#### Acceptance Criteria

1. WHEN a customer confirms an order on the website, THE website SHALL start a visible
   Hold_Delay countdown and SHALL NOT create the order in the backend until it elapses.
   *(PARTIAL — coded, not built/verified)*
2. WHILE the countdown is running, THE website SHALL offer a Cancel action that aborts the
   order entirely (nothing is sent to the kitchen). *(PARTIAL)*
3. WHEN the countdown reaches zero, THE website SHALL submit the order exactly as today
   (`POST /orders`), after which normal auto-print/pending behavior applies. *(PARTIAL)*
4. THE countdown UI SHALL be localized in all supported website languages. *(DONE — i18n keys added)*

### Requirement H3: Admin/staff orders use a fixed short hold

**User Story:** As a cashier, I want a brief 3-second chance to catch a wrong entry before it
fires, without the longer customer delay.

#### Acceptance Criteria

1. WHEN the admin device or an ordering-staff device submits an order, THE app SHALL apply a
   fixed Staff_Order_Hold of 3 s before the actual `POST /orders`, with a visible
   countdown + Cancel. *(TODO)*
2. THE Staff_Order_Hold SHALL be independent of Hold_Delay and SHALL NOT read the setting.
3. IF the user cancels during Staff_Order_Hold, THEN THE app SHALL discard the pending cart
   and create nothing in the backend. *(TODO)*

## Requirements — Feature Q: QR invite + camera-scan onboarding

### Requirement Q1: Staff invitation shown as a QR code

**User Story:** As an admin, I want to show a QR code for the staff invite, so that a new
device can join by scanning instead of me copying a long link.

#### Acceptance Criteria

1. THE Admin Settings "Staff Invitation" section SHALL render the current Ordering_Key_Invite
   as a scannable Invite_QR, in addition to (or replacing) the raw URL text. *(TODO)*
2. WHEN the admin regenerates the invite, THE Invite_QR SHALL update to encode the new token.
   *(TODO)*
3. THE Invite_QR SHALL remain shareable — the admin SHALL still be able to share the
   underlying link (existing Share action) for devices that cannot scan. *(TODO)*

### Requirement Q2: Fresh device onboards by scanning

**User Story:** As new ordering-device staff, I want to point my camera at the admin's QR to
connect, so that setup is fast and I don't type anything.

#### Acceptance Criteria

1. WHEN a freshly-installed device chooses "connect as ordering device", THE app SHALL open
   an in-app Onboarding_Scanner (camera) after requesting camera permission. *(TODO)*
2. WHEN the Onboarding_Scanner decodes a valid Invite_QR, THE app SHALL extract the invite
   token and drive the existing ordering-device registration flow with it. *(TODO)*
3. IF camera permission is denied, THEN THE app SHALL fall back to manual link/token entry
   and explain how to grant permission. *(TODO)*
4. THE Onboarding_Scanner preview SHALL render correctly at the device's screen aspect ratio
   (no stretch/squash; correct orientation), with a framing guide. *(TODO)*

### Requirement Q3: Deep link opens only this app, stays readable everywhere

**User Story:** As an admin, I want the QR to jump straight into our app when a phone that has
it scans, but still be a normal readable link otherwise, so it works for everyone.

#### Acceptance Criteria

1. THE Invite_QR SHALL encode a URL that, when opened on a device with this app installed,
   launches this app directly to the ordering-registration flow (Android App Link / deep
   link intent-filter scoped to this package). *(TODO)*
2. THE encoded payload SHALL be a plain, human-readable URL (not opaque binary), so a generic
   QR scanner reads it as text/link. *(TODO)*
3. THE deep link SHALL carry the invite token as a parameter and SHALL be validated
   server-side exactly as the existing invite flow (no new trust surface). *(TODO)*
4. WHERE the app is not installed, THE link SHALL degrade gracefully (open an https page or
   do nothing harmful) rather than error. *(TODO)*

## Non-Goals

- No change to how `orders.created_at` (or any timestamp) is *stored* — UTC stays UTC; only
  rendering changes (Feature T).
- No server-side scheduled/delayed job for the hold — the hold is client-side (Feature H).
- No account system or per-staff credentials — Feature Q reuses the existing single
  ordering-key invite; the QR is just a faster transport for it.
- No change to the debug quick-connect admin path.
