# System Warung Tom Yam — Design Document

## Overview

The system is composed of three independently deployable components that communicate over HTTPS and WebSocket (WSS):

1. **Proxy Website** — a React SPA + Supabase Edge Functions backend, free-hosted on **Cloudflare Pages** (free tier allows commercial use and unlimited bandwidth; Vercel's Hobby tier forbids commercial projects). Serves the customer ordering page, manages device registration, routes orders to admin phones via Supabase Realtime, and exposes a superadmin dashboard.
2. **Android APK** — a single Kotlin/Jetpack Compose app that dynamically renders the Admin or Ordering UI based on the role stored in encrypted on-device storage. First launch shows two connect options — **Connect as Ordering Staff** (invitation URL) and **Connect as Admin** (webhook URL + rotating key, first-claim one-time). The Admin role is the full POS terminal centred on a Table View; the Ordering role is a lightweight order entry client.
3. **Table QR Codes** — generated either from the website dashboard or the admin APK, output as a printable PDF of A6 portrait cards (105 × 148 mm) tiled 4 per A4 portrait sheet in a 2×2 grid.

All data persistence for business records (orders, menu, devices, reports) lives in a local SQLite database on the admin phone. The backend (Supabase) holds only transient routing state and device registration records.

---

## Architecture

### Component Diagram

```
┌─────────────────────────────────────────────────────┐
│              CLOUDFLARE PAGES (free)                │
│  ┌────────────────┐   ┌────────────────────────┐   │
│  │  React SPA     │   │  Supabase Edge Funcs   │   │
│  │  - Customer    │   │  - /api/register        │   │
│  │    ordering    │   │  - /api/orders          │   │
│  │    page        │   │  - /api/menu            │   │
│  │  - Superadmin  │   │  - /api/devices         │   │
│  │    dashboard   │   │  - /api/status          │   │
│  └────────────────┘   └────────────────────────┘   │
└──────────────────────────┬──────────────────────────┘
                           │ HTTPS / WSS
          ┌────────────────┼────────────────┐
          │                │                │
   ┌──────▼──────┐  ┌──────▼──────┐  ┌─────▼──────┐
   │  Admin APK  │  │ Ordering APK│  │  Customer  │
   │  (Admin     │  │ (Ordering   │  │  Browser   │
   │   Role)     │  │  Role)      │  │  (QR scan) │
   │  SQLite DB  │  │  Local cache│  │            │
   └─────────────┘  └─────────────┘  └────────────┘
```

### Layer 1 — Proxy Website (Vercel + Supabase)

**Hosting**: Cloudflare Pages (static frontend, free tier — commercial use allowed, unlimited bandwidth, stable `*.pages.dev` domain) + Supabase (database + Edge Functions + Realtime, free tier).

**Customer Ordering Page** (`/order?table=<id>`)
- React SPA page, statically generated.
- On load: fetches current menu from `GET /api/menu` (cached via Supabase or the Cloudflare CDN, 60s TTL).
- Customer builds cart → submits → `POST /api/orders` with `{ tableId, items[], timestamp }`.
- After submission, subscribes to a Supabase Realtime channel `order:<orderId>` to receive status updates.

**Customer page UI pattern** (Shell + Engine approach):
- **Engine**: state machine (LOADING → SESSION_CHECK → MENU / STATUS_VIEW / TABLE_OCCUPIED), `order:<orderId>` Realtime subscription, cookie + localStorage browser ID, `X-Browser-Id` header enforcement.
- **Shell** (preferred visual style): emerald-themed Tailwind, inline category tabs (sticky below header), inline selection summary tray (amber highlight showing selected items across tabs), sticky bottom bar with total + "Confirm & Place Order" button, bottom-sheet confirmation modal with item summary and "Send to Kitchen" CTA. Mobile-first, `max-w-md mx-auto`, zero asset bloat.

**Backend API — Supabase Edge Functions** (Deno runtime, free tier)
- `POST /api/register` — ordering device sends `{ inviteToken, deviceId, deviceModel, androidId, appVersion }`. Backend validates the invite token, creates a record with status `pending` (label = deviceModel), returns `deviceId`. No key yet.
- `POST /api/admin/handshake` — admin APK submits `{ deviceId, rotatingKey }`. Backend validates the rotating key against the current 30s window. If valid, issues a long-lived session token (`adminSessionToken`) stored against the device record. Returns `{ sessionToken }`.
- `GET /api/rotating-key` — superadmin auth required. Returns `{ key, expiresInSeconds }`. Backend generates a new TOTP-style key every 30s using a server-side HMAC secret. The key is never stored persistently — it is derived on-the-fly from `floor(unixTime / 30)`.
- `GET /api/devices/status?deviceId=X` — ordering device polls this to check if approved and retrieve its API key.
- `POST /api/orders` — customer (public, browser ID) or staff/admin (API key). Validates the table ID against registered tables, rejects if the table already has an active session, re-prices items server-side from the current menu snapshot (client prices ignored), rate-limits by IP/browser ID, inserts the order transiently in Supabase DB, emits to Realtime channel `admin-orders`. Returns `{ orderId }`.
- `PUT /api/orders/:id/status` — admin session token required. Updates order status, broadcasts to `order:<orderId>` Realtime channel.
- `GET /api/orders?since=<timestamp>` — admin session token. **Catch-up sync**: returns all active orders and events since the given timestamp; called whenever the admin APK's Realtime connection (re)establishes so dropped-WebSocket windows lose nothing.
- `GET /api/menu` — returns current multilingual menu JSON. Cache-Control: 60s. Returns placeholder response `{ configured: false }` until branding/menu has been pushed.
- `PUT /api/menu` — admin session token required. Upserts menu snapshot.
- `PUT /api/branding` — admin session token required. Accepts `{ cafeName, logoBase64 }`. Stores in Supabase DB + Storage. Broadcasts to `branding` Realtime channel so open customer pages refresh.
- `GET /api/branding` — returns `{ cafeName, logoUrl }` or `{ configured: false }` if never set.
- `GET /api/settings` — returns system settings.
- `PUT /api/settings` — admin session token required. Updates settings, broadcasts to `settings` channel.
- `PUT /api/cafe-location` — admin session token required. Stores `{ latitude, longitude, radiusMeters }` captured via GPS lock; the website database holds the authoritative copy used for attendance validation.
- `GET /api/cafe-location` — ordering-device API key required. Returns the registered café location and radius for check-in/check-out validation.
- `GET /api/devices` — superadmin auth required. Lists all registered devices.
- `PATCH /api/devices/:id` — superadmin auth **or admin session token**. Approve/reject pending devices, rename, or revoke a key. Role changes are not supported (no promotion).
- `POST /api/orders/:id/kitchen` — admin or RBAC-permitted staff. Marks the order sent-to-kitchen; the admin APK prints the kitchen slip.
- `POST /api/orders/:id/payment` — admin or RBAC-permitted staff. Body `{ method: "CASH" | "QR" }`. Only valid after sent-to-kitchen. Marks the order `Completed` and ends the table session.
- `DELETE /api/orders/:id` — cancel. Admin/staff may cancel anytime; the customer's browser ID may cancel only while status is `Received`.
- `GET /api/tables/:tableId/session` — public. Returns `{ state: "FREE" }`, `{ state: "OCCUPIED" }`, or the caller's own order + status when the request carries the owning browser ID.
- `POST /api/aggregates` — admin session token. Upserts a daily summary `{ date, totalOrders, totalRevenue, paymentSplit, topItemsPerCategory }` used by dashboard metrics and reports.
- `GET /api/invite` / `POST /api/invite/regenerate` — admin session token. Returns / rotates the ordering-role invitation token (regeneration invalidates the previous URL).

**Supabase Realtime Channels**
- `admin-orders` — admin APK(s) subscribe here. Backend broadcasts every new order to this channel.
- `order:<orderId>` — customer browser subscribes after placing an order. Admin APK publishes status updates here.

**Superadmin Dashboard** (`/admin`)
- Password-protected React SPA (Supabase Auth email/password + email verification + password recovery).

**Onboarding flow**:
```
First visit (no account)
    → /admin/register — email + password form
    → Supabase sends verification email
    → Superadmin clicks link → account activated → redirected to /admin/login
    → Login → session cookie set → /admin/dashboard

Forgot password
    → /admin/login → "Forgot password?" → email input
    → Supabase sends recovery link → superadmin sets new password
```

**Pre-café state** (no admin phone ever connected):
```
/admin/dashboard → Setup screen
┌─────────────────────────────────────────┐
│  Connect your Admin Phone               │
│                                         │
│  [ 4 8 3 9 2 1 ]   ████░░░░ 18s        │
│                                         │
│  Open Admin APK → Settings →            │
│  Backend Connection → enter this key    │
│                                         │
│  [ ✓ Key copied — I stored it safely ]  │
└─────────────────────────────────────────┘
Key rotates every 30s. Once handshake succeeds, dashboard
permanently transitions to Café Dashboard view.
```

**Café Dashboard** (post-connection), page structure:
```
/admin
├── /admin/dashboard     ← Home: metrics grid (numbers only)
├── /admin/devices       ← All registered devices, roles, actions
├── /admin/orders        ← Live order feed (Realtime)
├── /admin/branding      ← Café name + logo (read-only, pushed by APK)
└── /admin/settings      ← Settings page (see below)
```

**Dashboard metrics** (`/admin/dashboard`) — numbers only, no charts:

| Metric | Today | This week | This month | Last month | Monthly (up to 12 months) |
|---|---|---|---|---|---|
| Total orders | ✓ | ✓ | ✓ | ✓ | ✓ |
| Total profit (revenue) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Café open hours | ✓ | ✓ | ✓ | ✓ | ✓ |

- Metrics are computed server-side via `GET /api/metrics?period=<period>` from the **daily aggregate rows** pushed by the admin APK (`POST /api/aggregates`) — raw orders are not retained on the backend.
- "Café open hours" = sum of `(CLOSE.timestamp − OPEN.timestamp)` pairs from the `sessions` table for the given period. A new OPEN arriving while a prior session is unclosed implicitly closes the dangling session at the last recorded backend activity timestamp.
- All period boundaries ("today", "this week", …) use the café's configured time zone (default `Asia/Kuala_Lumpur`), not raw UTC.
- "Admin phone offline" warning shown if last `sessions.OPEN` event was > 30 minutes ago with no matching CLOSE.

**Settings page** (`/admin/settings`):
```
Settings
├── Download APK
│     Link to latest signed APK (GitHub Release or static URL).
│
├── Deregister Admin Phone
│     Revokes session token + clears admin device record.
│     Requires superadmin password re-entry.
│     After deregistration: dashboard returns to Pre-Café Setup screen.
│
├── Closing Report
│     Toggle: auto-send on "Sign Out with Closing" (on/off)
│     Email recipient: text field (pre-filled with superadmin email)
│
├── Monthly Report
│     Button: "Generate & Send Monthly Report for [current month]"
│     Also generates downloadable PDF inline.
│
├── Top Items Count
│     Number input (default 5, range 1–20).
│     Applied to Top-N items per category in all reports.
│
└── Connected Ordering Devices
      List of all ordering-role devices:
        - Label (phone model on registration, editable via Rename)
        - Last-seen timestamp
        - Online / Offline badge
      Actions per device:
        - Rename (edit label)
        - Force Sign Out (ends session; device stays registered)
        - Deregister (permanently removes device)
```

**New backend endpoints for dashboard**:
- `POST /api/sessions` — admin session token required. Records `{ event: OPEN|CLOSE, reason?, timestamp }` in `sessions` table.
- `GET /api/metrics?period=today|week|month|last_month|monthly` — superadmin auth. Returns `{ orders, revenue, openHours }` for the requested period. Monthly returns an array of 12 objects `[{ month, orders, revenue, openHours }]`.
- `GET /api/reports/closing` — superadmin auth or admin session token. Generates and returns closing report PDF.
- `GET /api/reports/monthly?month=YYYY-MM` — superadmin auth. Generates and returns monthly report PDF.
- `GET /api/settings/top-n` — returns current top-N value.
- `PUT /api/settings/top-n` — superadmin auth. Updates top-N value.

**Free Tier Limits Fit-Check (Supabase)**

Planning for **30 tables** and ~60–70 max concurrent connections (up to 2 active browser sessions per table + 10 devices). This puts the system at ~35% of Supabase's free-tier Realtime quota — a 70% safety buffer for unexpected spikes.

| Resource | Free Limit | Expected Usage (30 tables) | Utilisation |
|---|---|---|---|
| DB storage | 500 MB | < 5 MB (transient orders only) | ~1% |
| Realtime concurrent connections | 200 | ~70 peak (30 tables × 2 browsers + 10 devices) | ~35% |
| Realtime messages | 2,000,000 / month | ~50,000 / month (handful of broadcasts per order × daily volume) | ~2.5% |
| Edge Function invocations | 500,000 / month | < 15,000 / month | ~3% |
| Bandwidth | 5 GB / month | < 500 MB / month | ~10% |

**Operational impact of 30-table ceiling:**
- **Admin APK RAM**: ~30–60 active order objects ≈ 150 KB — negligible for Android memory management.
- **Table View UI**: 5×6 grid fits comfortably on a 6.5" phone without virtualised scrolling.
- **Print queue throughput**: peak ~1 order/30–60s; thermal printer clears in ~2s per slip — no queue backlog.
- **Edge Function load**: ~0.1–0.5 req/sec peak — zero cold-start queueing concern.

---

### Layer 2 — Android APK (Single Binary, Dual Role)

**Language / Stack**: Kotlin, Jetpack Compose, MVVM + Clean Architecture, Hilt (DI), Room (SQLite), Retrofit/OkHttp, Supabase Kotlin SDK.

#### Role-Based UI Architecture

On first launch, the app has no role and shows a **role-selection screen**: a primary **Connect as Ordering Staff** button (enter invitation URL → fingerprint sent → admin approval) and a secondary **Connect as Admin** button (enter webhook URL + rotating key → handshake). The resulting role and credential are stored in Android `EncryptedSharedPreferences`. On every app start, the role is read from encrypted storage and the appropriate navigation graph is loaded:

```
App Start
    │
    ▼
Read Role from EncryptedSharedPreferences
    │
    ├── NO ROLE → RoleSelectScreen
    │       ├── [Connect as Ordering Staff] → InvitationSetupScreen
    │       │        → PendingApprovalScreen (polls /api/devices/status every 10s)
    │       └── [Connect as Admin] → webhook URL + rotating-key handshake → AdminNavGraph
    ├── ORDERING → OrderingNavGraph
    └── ADMIN    → AdminNavGraph
```

There is no role promotion. Admin is **first-claim and one-time**: the backend rejects admin handshakes while an admin device is registered; deregistering the admin phone from the website re-opens the claim window. Changing a device's role means deregistering it and reconnecting through the other flow.

#### Admin Role — Screen Structure

```
AdminNavGraph
├── Dashboard — Table View (café POS grid: per-table session state Free/Occupied
│   + active order status, quick stats). Per-table actions:
│   Send to Kitchen (prints slip) · Update Status · Cancel ·
│   Payment (Cash | QR — enabled only after Send to Kitchen;
│   marks order Completed and ends the table session)
├── Orders
│   ├── Live Queue (real-time, Supabase Realtime)
│   ├── Order Detail (status update, re-print)
│   └── Manual Dine-In Entry (table select → menu → submit)
├── Menu
│   ├── Category Tabs (Food | Beverages | Side Dishes | Others — fixed)
│   ├── Item List per Category
│   └── Add / Edit Item — type-first: choose menu type → key in name + price
│         (+ optional translations: EN / 中文 / தமிழ் / ไทย — no images;
│          + "Ask me daily" flag for uncertain-availability items, e.g. market fish)
├── Printers
│   ├── Printer List (name, role badge, paper width, status)
│   ├── Add Printer (BT scan → name → paper width → role: Receipt/Kitchen/Both)
│   ├── Edit / Remove Printer
│   └── Test Print (per printer)
├── QR Codes
│   ├── Table List (add / rename / delete tables)
│   └── Generate PDF (A4 portrait, 4×A6 portrait per sheet)
├── Devices
│   ├── Pending Approval List
│   └── Connected Devices (revoke · Force Check-Out — no GPS, attendance saved with forced flag)
├── Reports
│   ├── Daily Summary
│   ├── Weekly Summary
│   └── Export (PDF / CSV)
└── Settings
    ├── Café Profile (name, logo upload → syncs to website)
    ├── Café Location (GPS lock: capture current fix + check-in radius → PUT /api/cafe-location)
    ├── Staff Invitation (ordering-role invitation URL with invite token, Regenerate button)
    ├── Staff Permissions (toggles: Send to Kitchen — default off, Payment — default off; Cancel always on)
    ├── Language (English default / Malay — applies to all print output and APK UI)
    ├── Backend Connection (one-time webhook + rotating-key handshake setup)
    ├── Sign Out — ends session, stays registered, no report
    ├── Sign Out with Closing — closing reason prompt → sends closing report → ends session
    ├── Database Export / Import
    └── Role Info
```

#### Ordering Role — Screen Structure

```
OrderingNavGraph
├── InvitationSetupScreen    ← First launch only: enter invitation URL (validated ordering-role format), device fingerprint sent
├── PendingApprovalScreen    ← Waiting for admin to approve (polls /api/devices/status)
│
│   [Café Closed State — broadcast from backend when admin signs out with closing]
├── CafeClosedScreen         ← Only shows "Check Out" button + closure message
│
│   [Café Open State — normal operation]
├── CheckInScreen            ← GPS check required; shows "Check In" button
│       └── GPS validation → within radius → POST attendance CHECK_IN → OrderingScreen
├── OrderingScreen           ← Only accessible after GPS check-in during open café
│   ├── Table View (real-time table/session states — same data as admin;
│   │     RBAC default: Cancel only; Send to Kitchen / Payment hidden unless admin enables)
│   ├── Table Selection
│   ├── Menu Browse → Add to Cart
│   ├── Cart Review → Submit Order
│   ├── Order Submitted Confirmation
│   └── Check Out button (always visible) → GPS validation → POST CHECK_OUT → CheckInScreen
└── [Background: OrderingForegroundService always running — keeps app alive]
```

**Ordering role state machine**:

```
APK Install
    │
    ▼
RoleSelectScreen → [Connect as Ordering Staff] → InvitationSetupScreen (enter invitation URL)
    → POST /api/register { inviteToken, deviceId, deviceModel, androidId, appVersion }
    → Backend notifies admin APK → admin approves
    ▼
PendingApprovalScreen (poll /api/devices/status every 10s)
    → Approved → store permanent apiKey
    ▼
    ┌────────────────────────────────────────────────────────────┐
    │ SUBSCRIBE to "cafe-status" Realtime channel                │
    │                                                            │
    │  CAFE_CLOSED event → CafeClosedScreen (Check Out only)    │
    │  CAFE_OPEN event   → CheckInScreen                        │
    └────────────────────────────────────────────────────────────┘
    ▼
CheckInScreen
    → Staff taps Check In
    → GPS check: within radiusMeters of CafeLocation?
        ✗ → "You must be at the café to check in"
        ✓ → POST /api/attendance { event: CHECK_IN, lat, lng }
           → Admin APK notified
           → OrderingScreen
    ▼
OrderingScreen (take orders normally)
    → Staff taps Check Out
    → GPS check: within radius?
        ✓ → POST /api/attendance { event: CHECK_OUT, lat, lng }
           → Admin APK notified
           → CheckInScreen
```

#### Bluetooth Thermal Printer Integration — Multiple Printers

Library: **`ESCPOS-ThermalPrinter-Android`** (`com.github.DantSu:ESCPOS-ThermalPrinter-Android`) — supports 58mm and 80mm via Bluetooth, TCP, and USB.

The APK supports **registering multiple Bluetooth printers**, each with its own name, MAC address, paper width, and assigned print role.

**Printer Model** (stored in Room `PrinterConfig` entity):

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Local identifier |
| `name` | String | User-given label (e.g., "Counter Printer", "Kitchen") |
| `macAddress` | String | Bluetooth MAC address |
| `paperWidth` | Enum | `MM_58` or `MM_80` |
| `printRole` | Enum | `RECEIPT_ONLY`, `KITCHEN_ONLY`, or `BOTH` |
| `isActive` | Boolean | Whether this printer is enabled |

**Print dispatch logic** — when a print job is triggered:

```
Send to Kitchen triggered (manual tap on Table View, or auto-send toggle)
    │
    ├── Select lines: only OrderItems where sentToKitchen == false
    │     (first send = all lines; later send = delta slip of newly added lines only)
    │     → mark those lines sentToKitchen = true; set Order.status = SENT_TO_KITCHEN
    │
    ├── Dispatch KITCHEN job → find printer where printRole == KITCHEN_ONLY
    │       └── if none found → find printer where printRole == BOTH
    │               └── if none found → queue job, notify admin "No kitchen printer configured"
    │
    └── On Payment (order Completed) → Dispatch RECEIPT job → find printer where printRole == RECEIPT_ONLY
            └── if none found → find printer where printRole == BOTH
                    └── if none found → queue job, notify admin "No receipt printer configured"
```

**Virtual-printer routing (design headroom)** — the three `printRole` values are the common
case of a more general model: each line could be routed to a printer by menu category, so
drinks print at a bar printer and food at the kitchen. To keep that upgrade path open without
over-building now, `PrinterConfig` reserves an optional `categoryFilter` (null = handle all
categories, current behaviour). When set, the KITCHEN dispatch first tries a printer whose
`categoryFilter` matches the line's category, then falls back to the role match, then to BOTH.
No migration is needed to enable it later.

**Paper width per printer** — character width and image width constants are resolved from the individual printer's `paperWidth` setting at render time:

| Paper | Char width (text) | Max image width |
|---|---|---|
| 58mm | 32 chars | 384px |
| 80mm | 48 chars | 576px |

**Printers settings screen** (Admin NavGraph → Printers):
```
Printers
├── Printer List (name, role badge, paper width, online/offline status)
├── Add Printer (Bluetooth scan → select → name → paper width → role → Save)
├── Edit Printer (rename, change role, change paper width)
├── Remove Printer (confirmation dialog)
└── Test Print (sends test page to selected printer)
```

**Print document types**:
- `KitchenSlip` — table number (large), item list (name + qty), special instructions, timestamp. Dispatched to Kitchen or Both printer on Send to Kitchen (or immediately if auto-send is enabled). A **delta slip** (only newly added lines) is headed "TAMBAHAN / ADDED — Table N" so the kitchen never re-cooks already-sent lines.
- `CustomerReceipt` — café name, logo (if set), table, order items, subtotal, total, thank-you footer. Dispatched to Receipt or Both printer on Payment (Cash/QR) or manual trigger; shows payment method.
- `AuditReport` — date range, total orders, revenue, item breakdown. Dispatched to any available printer.

Print jobs are queued in Room `PrintJob` table per target printer. If a printer is offline, the queue retries when Bluetooth reconnects to that specific MAC address.

#### QR Code PDF Generation

Library: **ZXing** (QR bitmap generation) + **Android PdfDocument API** (built-in, no dependency).

The PDF is designed for **print shop output and manual cutting**. Each table card is sized at **A6 portrait (105 × 148 mm)**. Cards are tiled onto **A4 portrait sheets (210 × 297 mm)** in a **2-column × 2-row grid** — exactly 4 cards per sheet with near-zero paper waste (0 mm horizontally, 1 mm vertically).

**Why A4 portrait + A6 portrait?**

| Sheet | Card | Grid | Cards/sheet | Waste |
|-------|------|------|-------------|-------|
| A4 portrait (210×297mm) | A6 portrait (105×148mm) | 2×2 | **4** | 0mm right, 1mm bottom ✓ |
| A4 landscape (297×210mm) | A6 portrait (105×148mm) | 2×1 | 2 | 87mm right — wasteful ✗ |
| A4 landscape (297×210mm) | A6 landscape (148×105mm) | 2×2 | 4 | 1mm right, 0mm bottom |

A4 portrait + A6 portrait is chosen because the card itself must be portrait orientation (taller than wide) to hold the logo, café name, QR code, and table label with adequate breathing room.

**Page layout** (A4 portrait, 210 × 297 mm):

```
A4 Portrait  (210 × 297 mm)
┌──────────────┬──────────────┐  ─┐
│  ┌────────┐  │  ┌────────┐  │   │
│  │  Logo  │  │  │  Logo  │  │   │  A6 card height
│  │  Café  │  │  │  Café  │  │   │  148 mm
│  │  Name  │  │  │  Name  │  │   │
│  │        │  │  │        │  │   │
│  │ [QR 1] │  │  │ [QR 2] │  │   │
│  │        │  │  │        │  │   │
│  │TABLE 1 │  │  │TABLE 2 │  │   │
│  └────────┘  │  └────────┘  │  ─┘
├──────────────┼──────────────┤  ─┐
│  ┌────────┐  │  ┌────────┐  │   │  A6 card height
│  │  Logo  │  │  │  Logo  │  │   │  148 mm
│  │  Café  │  │  │  Café  │  │   │
│  │  Name  │  │  │  Name  │  │   │
│  │        │  │  │        │  │   │
│  │ [QR 3] │  │  │ [QR 4] │  │   │
│  │        │  │  │        │  │   │
│  │TABLE 3 │  │  │TABLE 4 │  │   │
│  └────────┘  │  └────────┘  │  ─┘
└──────────────┴──────────────┘
   ← 105mm →← 105mm →            1mm unused at bottom
   ↑ hairline borders = cut guides
```

**Cell internal layout** — each A6 card (top → bottom, all elements centred):

| Zone | Content | Sizing |
|------|---------|--------|
| Top padding | 8mm margin | — |
| Logo | Café logo bitmap, square crop | max 40×40mm; omitted gracefully if not set |
| Café name | Bold, 11pt | max 2 lines, truncated with ellipsis |
| Spacer | 4mm | — |
| QR code | ZXing bitmap, error correction **H** | 60×60mm, 1-module quiet zone |
| Spacer | 4mm | — |
| Table label | e.g. `TABLE 1` or custom slug, bold 13pt | centred |
| Bottom padding | 8mm margin | — |

- Each QR encodes: `https://<site-domain>/order?table=<tableId>`
- Hairline border (0.5pt) on every card edge acts as the print shop cut guide.
- For more than 4 tables: additional A4 portrait pages added automatically (tables 5–8 on page 2, etc.).
- Blank cells on the last page if table count is not a multiple of 4.
- Output saved to `Downloads/<CafeName>-QR-Tables.pdf` via `MediaStore` API (Android 10+) with `FileProvider` fallback.
- Share button triggers `Intent.ACTION_SEND` with `application/pdf` MIME type — compatible with WhatsApp, email, Google Drive.

#### Local Database — Room (SQLite)

**Entities**:

| Entity | Key Fields |
|---|---|
| `MenuItem` | id, nameBM, nameEN, nameZH, nameTM, nameTH, descBM, descEN, descZH, descTM, descTH, price, category (FOOD\|BEVERAGES\|SIDE_DISHES\|OTHERS), askMeDaily, isAvailable, lastAvailabilityConfirmedDate |
| `Order` | id, tableId, source (QR / STAFF), status (`RECEIVED\|SENT_TO_KITCHEN\|PREPARING\|READY\|COMPLETED\|CANCELLED`), paymentMethod (CASH\|QR\|null), sentToKitchenAt, cancelReason, cancelledBy, browserId, createdAt, totalAmount |
| `OrderItem` | id, orderId, menuItemId, **nameSnapshot, unitPriceSnapshot, categorySnapshot** (frozen at add time — never joined live), quantity, note, **sentToKitchen** (bool, per-line kitchen-print flag) |
| `PaymentTransaction` | id, orderId (FK → Order), method (CASH\|QR), amount, createdAt — append-only; one row today, but modelled as a table so split/partial payments can be added later without schema change |
| `Device` | id, deviceIdentifier, androidId, deviceModel, role, apiKey, label, isCheckedIn, approvedAt |
| `AttendanceRecord` | id, deviceId (FK → Device), event (CHECK_IN\|CHECK_OUT), latitude, longitude, timestamp |
| `CafeLocation` | id (singleton), latitude, longitude, radiusMeters — local cache; the authoritative copy lives in the website database |
| `PrinterConfig` | id, name, macAddress, paperWidth (MM_58\|MM_80), printRole (RECEIPT_ONLY\|KITCHEN_ONLY\|BOTH), categoryFilter (nullable — reserved for future per-category routing), isActive |
| `PrintJob` | id, printerId (FK → PrinterConfig), type (KITCHEN\|RECEIPT\|REPORT), payload (JSON), status (QUEUED\|PRINTING\|DONE\|FAILED), createdAt |
| `Report` | id, type (DAILY/WEEKLY), generatedAt, dataJson |
| `SystemSettings` | key, value — stores `print_language` (EN default\|BM), `cafe_name`, `cafe_logo_uri`, `admin_session_token` (**encrypted — see REQ-12 Gap B**), etc. |

**Export / Import**: The full database is serialized to a JSON envelope (`{ version, exportedAt, menuItems[], orders[], orderItems[], paymentTransactions[], devices[], attendance[], printers[], cafeLocation, settings[] }`) and written to a file. Import validates the version field before applying.

**Data-model design principles**:
- **Line-item snapshotting**: order lines freeze name/price/category at add time. Reports and receipts read snapshots, so a menu price change never rewrites history. This is why `OrderItem` carries `*Snapshot` fields rather than only a `menuItemId` join.
- **Table vs. table-status separation**: the static `tables` registry is kept separate from mutable session/occupancy state, so a layout rarely changes while occupancy changes constantly. The `tables` table + active-`orders` session reflect this.
- **Append-only payments**: payments are rows, not a mutated field — `PaymentTransaction` is a table now (one row per order today) so partial/split settlement is a data change, not a schema change, later.
- **Single status enum, not scattered booleans**: spreading order lifecycle across several independent booleans is a known source of inconsistent states, so one `Order.status` enum is used instead.

---

### Layer 3 — Table QR Codes

QR codes encode the URL: `https://<site-domain>/order?table=<tableId>`

Each `tableId` is a short slug set by the admin (e.g., `T1`, `T2`, `window-1`). The slug is stable — the QR code does not need to be reprinted unless the domain changes or the table is renamed.

---

### Layer 4 — Internationalisation (i18n) Architecture

The system uses a **split-language model with an English base**: the customer website
supports 4 languages; all operational output (APK UI, printing, dashboard) uses one of 2
admin-controlled languages. **English is the single authored source** — every other
language is produced by **direct dictionary translation** over English, not by
hand-entered per-language content (see `requirements.md` REQ-9 and the research item
REQ-12 Gap A).

#### Customer Website — 4-Language i18n (English source + dictionaries)

The website uses **i18next** (React) with English as the base locale and the other three
delivered as dictionary translations:

| Locale key | Language | Source |
|---|---|---|
| `en` | English | **base — authored** |
| `bm` | Bahasa Melayu | dictionary translation of `en` |
| `zh` | Simplified Mandarin | dictionary translation of `en` |
| `ta` | Tamil | dictionary translation of `en` |

- **Static UI strings** (buttons, status, category names): English is the authored
  i18next resource; `bm`/`zh`/`ta` are bundled dictionary tables keyed off the English
  string. All ship offline and complete.
- **Menu item names/descriptions**: authored in English only. The backend resolves other
  languages via a **food-term dictionary** at menu-push time (or the client resolves at
  render time), with English fallback for unknown terms, a "do not translate" flag for
  proper nouns/dish names, and an admin manual override that always wins.

```json
{
  "id": "item_001",
  "category": "FOOD",
  "price": 6.50,
  "name": {
    "en": "Coconut Rice",           // authored source
    "bm": "Nasi Lemak",             // dictionary (or admin override)
    "zh": "椰浆饭", "ta": "நாசி லெமாக்",
    "doNotTranslate": false
  },
  "description": { "en": "...", "bm": "...", "zh": "...", "ta": "..." }
}
```

The React ordering page reads i18next's active language and picks the matching key; if a
key is missing it **falls back to `en`** (never blank). Default on first load is `en`.
Language persistence: `localStorage` key `lang`. A sticky language selector (flag icons)
in the header allows switching at any time.

#### Admin APK and Print Output — 2-Language i18n (English default)

The APK uses Android's standard **string resources** pattern:
- `res/values/strings.xml` — **English (default/base)**
- `res/values-ms/strings.xml` — Malay (dictionary-translated, generated at build time from
  the English source; overridable). *(Note: `values-ms`, not `values-in`/Indonesian.)*

The active print language is stored in `SystemSettings` Room entity (`print_language = EN | BM`,
default `EN`). On language change, the admin APK:
1. Updates `SystemSettings` locally.
2. Calls `PUT /api/settings { printLanguage: "BM" }` so the backend knows.
3. All connected ordering-role APKs fetch the new setting on next poll or app restart.

Print documents (`KitchenSlipDocument`, `ReceiptDocument`) pull string resources via the
active locale context, not hardcoded strings. Set `generateLocaleConfig = true` (AGP) so
the per-app language list is auto-generated from the `values-*` folders.

#### Menu Snapshot API Response — Language Structure

The `GET /api/menu` response includes all four language variants (English authored + three
dictionary-resolved) so the React customer page can switch instantly without re-fetching:

```json
{
  "printLanguage": "EN",
  "categories": ["FOOD", "BEVERAGES", "SIDE_DISHES", "OTHERS"],
  "items": [
    {
      "id": "item_001",
      "category": "FOOD",
      "price": 6.50,
      "available": true,
      "name": { "en": "Coconut Rice", "bm": "Nasi Lemak", "zh": "椰浆饭", "ta": "நாசி லெமாக்" },
      "description": { "en": "...", "bm": "...", "zh": "...", "ta": "..." }
    }
  ]
}
```

The `printLanguage` field (default `EN`) tells the website dashboard which language to
display in the superadmin order feed.

---

## Data Flow

### Customer Places Order (QR)

```
Customer scans QR
    → Browser opens /order?table=T3
    → GET /api/tables/T3/session
        ├── FREE                        → GET /api/menu → renders menu
        ├── OCCUPIED (other browser ID) → "Table occupied" screen — no order details, no ordering
        └── OCCUPIED (own browser ID)   → order status view (rescan flow below)
    → Customer submits cart
    → POST /api/orders { tableId: "T3", browserId, items: [...] }
    → Backend opens table session (T3 → OCCUPIED), emits to Realtime "admin-orders" channel
    → Admin APK receives order event, saves to local SQLite; Table View marks T3 Occupied
    → Admin taps Send to Kitchen (or auto-send toggle) → kitchen slip prints via Bluetooth
    → Status updates from Table View → PUT /api/orders/:id/status
    → Backend broadcasts to "order:<orderId>" channel
    → Customer's open page updates status display

Customer rescans the same table QR (active session, own browser ID)
    → Page shows ordered items + live status
    → Cancel button visible only while status = Received (pre-kitchen)

Admin takes Payment from Table View (Cash | QR — enabled after Send to Kitchen)
    → POST /api/orders/:id/payment { method }
    → Order marked Completed → table session ends → T3 back to FREE
    → Next scan of T3 shows the fresh menu
```

### Staff Places Order (Ordering Role APK)

```
Staff selects table and items on Ordering APK
    → POST /api/orders (with ordering-role API key)
    → Same downstream flow as customer order
    → Admin APK notified, prints kitchen slip
```

### Order Amendment After Send-to-Kitchen (delta slip)

```
Order already SENT_TO_KITCHEN; staff/admin adds items to the same table session
    → New OrderItems appended with sentToKitchen = false
    → Admin (or permitted staff) taps Send to Kitchen again
    → Dispatcher selects ONLY lines where sentToKitchen == false
        → prints a delta slip headed "TAMBAHAN / ADDED — Table N" (new lines only)
        → marks those lines sentToKitchen = true
    → Already-printed lines are never re-sent (no double-cooking)
    → Receipt at Payment shows the full consolidated order (all lines, snapshotted prices)
```

### Admin Syncs Menu

```
Admin adds/edits item on Admin APK
    → Saved to local SQLite immediately (optimistic)
    → PUT /api/menu (with admin API key) → full menu JSON pushed to backend
    → Backend stores in Supabase DB
    → Customer ordering page fetches fresh menu on next load (60s cache TTL)
```

### Admin APK Initial Handshake (Rotating Key)

```
Superadmin logs into website → navigates to Connection page
    → Website calls GET /api/rotating-key (superadmin auth)
    → Backend derives key from HMAC(secret, floor(unixTime/30)) — valid 30s window
    → Website displays key + countdown timer

Admin opens APK → Settings → Backend Connection → enters key
    → APK calls POST /api/admin/handshake { deviceId, rotatingKey }
    → Backend validates key against current 30s window
    → If valid: issues long-lived sessionToken, stores in devices table
    → APK stores sessionToken in EncryptedSharedPreferences
    → APK transitions from setup → AdminNavGraph
    → All subsequent APK API calls use sessionToken in Authorization header
```

### Admin Pushes Café Branding

```
Admin → Settings → Café Profile → enters name, selects logo image
    → APK compresses logo to JPEG ≤ 200KB, encodes base64
    → PUT /api/branding { cafeName, logoBase64 } (sessionToken auth)
    → Backend stores cafeName in settings table
    → Backend uploads logo to Supabase Storage, stores public URL
    → Backend broadcasts { cafeName, logoUrl } to "branding" Realtime channel
    → Open customer browser pages refresh café name and logo instantly
    → Superadmin dashboard Branding page updates
```

### Admin Registers Café Location (GPS Lock)

```
Admin → Settings → Café Location → taps "Lock Current Location"
    → APK requests a fresh GPS fix (accuracy shown; warns if fix is poor)
    → Admin adjusts check-in radius (default 100m) → Save
    → PUT /api/cafe-location { latitude, longitude, radiusMeters } (sessionToken auth)
    → Backend stores the location in the website database (authoritative copy)
    → Ordering APKs fetch GET /api/cafe-location on every check-in / check-out validation
```

### Admin APK Sign-In (Session Open Event)

```
Admin opens APK → sessionToken exists in EncryptedSharedPreferences
    → AdminNavGraph loads
    → APK calls POST /api/sessions { event: "OPEN", timestamp }
    → Backend inserts row into sessions table
    → Superadmin dashboard "Admin phone offline" warning clears
    → If first sign-in of the day (café local time) AND any item has askMeDaily = true:
        → Daily Availability popup — top-layer modal, background dimmed
        → Admin marks each item Available / Not available today (optional price update)
        → APK updates MenuItem rows locally → PUT /api/menu pushes fresh snapshot
        → Customer page and ordering APKs reflect today's availability immediately
```

### Admin APK Sign-Out (Session only — no report)

```
Admin taps Settings → Sign Out
    → APK calls POST /api/sessions { event: "CLOSE", reason: null, timestamp }
    → Backend inserts CLOSE row into sessions table
    → APK stops OrderReceiverService, disconnects Realtime WebSocket
    → APK shows lock / sign-in screen (sessionToken remains in EncryptedSharedPreferences)
    → On next app open: sessionToken valid → auto sign-in → OPEN event posted
    (No re-handshake required)
```

### Admin APK Sign-Out with Closing (Closing Report generated)

```
Admin taps Settings → Sign Out with Closing
    → APK shows closing reason dialog (free text, e.g. "End of day")
    → Admin enters reason → confirms
    → APK computes today's aggregate from local SQLite → POST /api/aggregates
    → APK calls POST /api/sessions { event: "CLOSE", reason: "End of day", timestamp }
    → Backend inserts CLOSE row
    → Backend triggers report generation:
        GET /api/reports/closing (with sessionToken)
        → Edge Function reads today's aggregate row and sessions for open hours
        → Builds PDF: date, open hours, total orders, revenue, top-N items per category
        → Emails PDF to report_email configured in settings
    → APK stops services, shows lock screen (token preserved, no re-handshake)
```

### New Device Registration (Ordering Role — Webhook URL method)

```
Staff installs APK → first launch → RoleSelectScreen → [Connect as Ordering Staff]
    → Staff enters the invitation URL from admin APK Settings → Staff Invitation
    → APK validates the URL is ordering-role format (embedded invite token)
    → APK reads Build.MODEL + Settings.Secure.ANDROID_ID
    → POST /api/register { inviteToken, deviceId (UUID), deviceModel, androidId, appVersion }
    → Backend creates device record (status: pending, label = deviceModel)
    → Backend emits to "admin-devices" Realtime channel:
      { type: "JOIN_REQUEST", deviceId, label: "Samsung Galaxy A23" }
    → Admin APK receives alert: "Samsung Galaxy A23 is requesting to join as Ordering role"
    → Admin taps Approve
    → Backend issues permanent apiKey, sets role = ORDERING
    → Device polls GET /api/devices/status?deviceId=X → receives { role, apiKey }
    → App stores apiKey in EncryptedSharedPreferences (permanent — never expires)
    → Subscribes to "cafe-status" Realtime channel
    → Loads state based on current cafe status (open → CheckInScreen, closed → CafeClosedScreen)
```

### Staff Check-In (GPS Attendance)

```
Staff taps "Check In" on CheckInScreen
    → APK requests GPS location (foreground location permission)
    → GPS fix obtained: { lat, lng }
    → GET /api/cafe-location → receives { lat, lng, radiusMeters }
    → Calculate distance between device GPS and café GPS
    → If distance > radiusMeters:
        → Show error: "You must be at the café to check in"
        → Stay on CheckInScreen
    → If within radius:
        → POST /api/attendance { event: "CHECK_IN", deviceId, lat, lng, timestamp }
        → Backend stores attendance record
        → Backend emits to "admin-attendance" channel: { label, event: CHECK_IN, timestamp }
        → Admin APK shows notification: "Maria (Redmi Note 11) checked in at 09:14"
        → Admin APK saves AttendanceRecord to local Room DB
        → Ordering APK transitions to OrderingScreen
```

### Staff Check-Out (GPS Attendance)

```
Staff taps "Check Out" (from OrderingScreen or CafeClosedScreen)
    → GPS check (same radius validation as check-in)
    → If within radius:
        → POST /api/attendance { event: "CHECK_OUT", deviceId, lat, lng, timestamp }
        → Backend stores attendance record
        → Backend emits to "admin-attendance" channel
        → Admin APK notified, saves CheckOut record
        → Ordering APK returns to CheckInScreen
```

### Café Closed — Broadcast to Ordering Devices

```
Admin taps "Sign Out with Closing" on Admin APK
    → POST /api/sessions { event: "CLOSE", reason: "End of day", timestamp }
    → Backend inserts CLOSE row
    → Backend broadcasts { event: "CAFE_CLOSED" } to "cafe-status" Realtime channel
    → All subscribed ordering-role APKs receive event
    → Each ordering APK: hide ordering screen → show CafeClosedScreen (Check Out only)

Admin opens APK again (next session start)
    → POST /api/sessions { event: "OPEN", timestamp }
    → Backend broadcasts { event: "CAFE_OPEN" } to "cafe-status" Realtime channel
    → All subscribed ordering APKs: hide CafeClosedScreen → show CheckInScreen
```

---

## Tech Stack

| Component | Technology | Rationale |
|---|---|---|
| Website frontend | React + Vite + TailwindCSS | Lightweight, fast static build, deploys to Cloudflare Pages free |
| Website i18n | i18next + react-i18next | Industry-standard React i18n, locale JSON files, easy fallback |
| Website backend | Supabase Edge Functions (Deno) | Free tier, co-located with DB, no cold-start penalty |
| Real-time messaging | Supabase Realtime (WebSocket) | Free up to 200 concurrent connections — 30 tables + 10 devices ≈ 70 peak (~35% utilisation) |
| Backend database | Supabase PostgreSQL (transient) | Stores device registry and live orders only |
| Website hosting | Cloudflare Pages (free tier) | CDN-backed, HTTPS, unlimited bandwidth, **commercial use allowed on free tier** (Vercel Hobby is not) |
| Report email | Brevo (free tier, 300/day) | Sender verified by email address — no paid custom domain required (Resend free needs a verified domain) |
| APK language | Kotlin + Jetpack Compose | Modern Android, type-safe, composable UI |
| APK architecture | MVVM + Clean Architecture + Hilt | Testable, role-based nav is clean |
| APK local DB | Room (SQLite) | Standard Android local persistence |
| APK networking | Retrofit + OkHttp | Mature, well-supported |
| APK i18n | Android string resources (values = English base / values-ms = Malay, dictionary-generated) + `generateLocaleConfig` | Standard Android pattern, no extra dependency; English is the source, Malay derived |
| Bluetooth printing | ESCPOS-ThermalPrinter-Android | Supports 58mm + 80mm, Bluetooth + USB |
| QR generation | ZXing (Android) | Zero-config QR bitmap generation |
| PDF generation | Android PdfDocument API | Built-in, no extra dependency |
| Auth (superadmin) | Supabase Auth (email/password) | Free, integrated with Edge Functions |
| API key storage (APK) | EncryptedSharedPreferences | Android Keystore-backed encryption |

---

## Design Decisions and Trade-offs

### Decision 1: Local SQLite on Admin Phone, Not Cloud Database

**Decision**: All business data lives in SQLite on the admin phone. Supabase holds only device records and live (transient) orders.

**Rationale**: Zero hosting cost, works fully offline, no GDPR cloud data concerns. Data stays within the café's control.

**Trade-off**: Admin phone is a single point of failure. Mitigated by the export/import backup feature and optional Google Drive sync.

### Decision 2: Single APK, Role-Based Navigation

**Decision**: One APK binary, role stored in EncryptedSharedPreferences, nav graph switched at runtime.

**Rationale**: One codebase to maintain, one file to distribute, shared business logic (menu, orders). Matches the small-team constraint.

**Trade-off**: Slightly more complex navigation architecture. Changing a device's role requires deregistering and reconnecting through the other flow (no reinstall).

### Decision 3: Supabase Realtime Instead of Firebase FCM for Order Delivery

**Decision**: Use Supabase Realtime WebSocket channel instead of push notifications.

**Rationale**: FCM push notifications require the app to be backgrounded/killed and have delivery delays. Realtime WebSocket delivers in < 300ms when the admin APK is in foreground (normal during service). The admin phone is kept open during service hours.

**Trade-off**: If the admin APK is backgrounded or the screen is off, the WebSocket may be closed by Android's battery optimization. Mitigation: a foreground service with a persistent notification keeps the socket alive during business hours, and catch-up sync (`GET /api/orders?since=<lastSeen>`) on every reconnect guarantees no order is lost even if the socket does drop.

### Decision 4: Supabase Edge Functions Over Website-Host Serverless

**Decision**: API logic runs on Supabase Edge Functions, not serverless functions at the website host (Cloudflare Workers / Pages Functions).

**Rationale**: Edge Functions are co-located with the Supabase DB and Realtime broker, reducing round-trip latency for order events. The free tier allows 500,000 invocations/month, far above expected volume.

**Trade-off**: Deno runtime instead of Node.js. Slightly different module import model but well-documented.

### Decision 5: Android PdfDocument API for QR PDF

**Decision**: Use the built-in Android `PdfDocument` API instead of a third-party PDF library.

**Rationale**: No additional dependency, no licence concern, sufficient for a fixed A4 layout with bitmaps and text.

**Trade-off**: The API is lower-level than iText or Apache PDFBox. Layout math (margins, cell sizing) is manual. For a fixed 4-per-page layout this is acceptable.

---

### Decision 6: Split-Language Model, English Base + Dictionary Translation

**Decision**: The customer website supports 4 languages (EN default, BM, ZH, TA). All operational output — APK UI, printing, superadmin dashboard — uses one of 2 languages (EN default or BM) set globally by the admin. **English is the single authored source**; all other languages are produced by direct dictionary translation over English (REQ-9, research item REQ-12 Gap A), not hand-entered.

**Rationale**: Customers in a Malaysian hawker stall come from three main language communities (Malay, Chinese, Tamil) plus English speakers to deal with Rohingya. Supporting 4 languages on the ordering page maximises accessibility with zero runtime cost (dictionaries are bundled). Anchoring on **English as the base** means the admin keys in content once, in English, and other languages are dictionary-derived — matching REQ-11's minimal-on-site-expertise constraint, and letting the standard Android default resource set (`values/`) hold the source strings. Operational output (receipts, kitchen slips) stays English or Malay only — thermal ESC/POS font support for Tamil/Mandarin is unreliable.

**Trade-off**: Kitchen staff cannot receive orders printed in Mandarin or Tamil even if that is their preferred language (accepted, stall context). Dictionary translation of arbitrary menu descriptions is best-effort — mitigated by English fallback, a "do not translate" flag for dish names, and an admin manual override. The dictionary mechanism itself is unproven and is a tracked research item (REQ-12 Gap A).

### Decision 7: Rotating 30-Second Key for Admin Handshake, Not Static API Key

**Decision**: The admin APK authenticates to the backend using a one-time rotating key (30s TOTP-style, HMAC-derived) shown on the superadmin website. After the handshake, a long-lived session token is used for ongoing communication.

**Rationale**: A static API key hard-coded or shared via message could be intercepted and reused permanently. A 30-second window severely limits the replay attack surface. The key is never persisted on the backend — it is derived on-the-fly — so there is nothing to leak from a database breach. The superadmin must be actively logged in to view the key, adding a human gate.

**Trade-off**: The admin must have the superadmin website open and be logged in during the initial APK setup. This is a one-time step, so the friction is acceptable. If the rotating key expires mid-entry (unlucky timing), the admin simply waits for the next key.

### Decision 8: Multiple Printers with Role Assignment

**Decision**: The APK supports N registered Bluetooth printers, each tagged as `RECEIPT_ONLY`, `KITCHEN_ONLY`, or `BOTH`. Print jobs are dispatched to the appropriate printer by role, falling back to a `BOTH` printer if the specific role printer is unavailable.

**Rationale**: Real-world café setups often have a counter printer (80mm, receipts) and a kitchen printer (58mm, kitchen slips). Forcing a single printer means kitchen staff and counter staff share one device — impractical. The role-based dispatch model covers single-printer setups (one `BOTH` printer) and dual-printer setups transparently without code changes.

**Trade-off**: Printer configuration UI is slightly more complex than a single "connect a printer" flow. Mitigated by clear role labels and a test-print button per printer.

## Identified Gaps and Improvements

| # | Gap | Impact | Recommended Improvement |
|---|-----|--------|--------------------------|
| 1 | Admin phone is single point of failure for all data | High | Scheduled auto-export to Google Drive / Dropbox; prompt admin to backup weekly |
| 2 | ~~Supabase Realtime WebSocket closed by Android battery optimization~~ | Resolved | Admin foreground service during open sessions + catch-up sync (`GET /api/orders?since=`) on every reconnect |
| 3 | ~~No customer feedback after order submission~~ | Resolved | Rescan-the-QR status view with browser-ID lock (table session model) |
| 4 | QR codes become invalid if the domain changes | Medium | Embed a short redirect URL (e.g., via a free service or own subdomain) so QR codes survive domain migrations |
| 5 | Staff ordering role has no order history view | Low | Add a read-only order history screen to the Ordering role (own submitted orders only) |
| 6 | ~~No table-level occupancy tracking~~ | Resolved | Table View with Free/Occupied session states, ended by Cancel or Payment |
| 7 | Free Supabase project pauses after 1 week of DB inactivity | Low | Daily scheduled ping via a GitHub Actions cron workflow hitting `GET /api/menu` (UptimeRobot's free plan bans commercial use since Dec 2024, so it is not an option) |
| 8 | Single admin phone — no failover if owner's phone dies mid-service | Medium | Superadmin deregisters the dead phone from the website, re-opening the one-time admin claim so a replacement phone can handshake (no promotion path by design) |
| 9 | Missing translations for Mandarin/Tamil/Thai on menu items | Medium | Add a "Translations" sub-screen in menu item edit; highlight untranslated items with a warning badge |
| 10 | Language change not instantly reflected on already-open customer browsers | Low | Customer page subscribes to a Supabase Realtime `settings` channel; re-fetches menu on `printLanguage` change event |
| A | Dictionary-translation i18n (English base → BM/ZH/TA/TH) is unproven | Medium | Tracked as REQ-12 Gap A — research static-string dictionaries, curated food-term dictionary, English fallback, offline/zero-cost constraint |
| B | Secure on-device token storage still to be designed | Medium | Tracked as REQ-12 Gap B — implement `EncryptedSharedPreferences`/Keystore for admin session token + ordering API key |
| 11 | Rotating key expires (30s) while admin is typing it into the APK | Low | APK setup screen accepts the key from the previous window too (60s total grace, ±1 window tolerance — standard TOTP practice) |
| 12 | Logo upload from APK may be slow on poor connectivity | Low | Compress logo client-side to ≤ 200KB JPEG before upload; show progress indicator; retry on failure |
| 13 | All printers offline simultaneously — no kitchen slip printed | Medium | Show a persistent "No printer available" banner on the admin dashboard; queue all jobs and batch-print when any printer reconnects |
| 14 | Supabase free-tier quota drain from bot traffic or client over-refresh | Medium | Cloudflare WAF + rate limiting on `*.pages.dev` — blocks attack vectors, caps at 10 req/s per IP on `/api/*` routes, monitor mode first then enforce. Protects the 500k/month Edge Function invocation quota. |

---

## Cloudflare WAF & Rate Limiting (Future Hardening)

**Role**: Protects Supabase free-tier endpoints and Edge Functions from malicious traffic, bot floods, or accidental client over-refresh. Ensures request quotas are preserved for legitimate users.

**Why this matters**:
- Supabase free tier allows 500k Edge Function invocations/month — bots or runaway clients can drain this quickly.
- The `POST /api/orders` endpoint already has server-side rate limiting by IP/browser ID, but Cloudflare WAF stops abusive traffic *before* it reaches Supabase.
- Edge Functions have a 60s CDN cache on `GET /api/menu`, but direct API hits bypass this unless Cloudflare intervenes.

**Components**:

| Layer | Function | Configuration |
|---|---|---|
| WAF rules | Block SQL injection, XSS, malformed payloads | Cloudflare managed ruleset (free tier) |
| Rate limiting | Cap requests per IP/device on `/api/*` | 10 req/s per IP; 60 req/min burst on order endpoints |
| Bot management | Throttle automated crawlers before Supabase | Challenge suspicious user-agents |
| Geo-blocking | Optional — filter traffic from irrelevant regions | Only if abuse detected from specific origins |

**Implementation approach**:
1. Start in **monitor mode** — observe traffic patterns for 1–2 service weeks.
2. Baseline legitimate traffic: customer ordering (~1–3 req per order), admin APK Realtime (WSS, not HTTP), staff check-in (1 req).
3. Set thresholds conservatively above baseline, then tighten.
4. Document thresholds in ops runbook (Task 29) so they can be tuned as usage grows.

**Traffic flow with WAF**:
```
Customer/Staff request → Cloudflare edge (WAF check + rate limit)
    ├── PASS → Supabase Edge Function
    ├── CHALLENGE → Cloudflare challenge page (bots)
    └── BLOCK → 403 (abusive IPs)
```

**Impact on system components**:
- Customer PWA: loads via CDN cache (static assets unaffected); legitimate API requests pass through.
- Staff/Admin APKs: authenticated traffic passes without throttling (known tokens exempt from challenge).
- Supabase free tier: protected from quota drain, extending longevity.

**When to enable**: Task 27 (Phase 10 — Hardening). Start in monitor mode during the field rehearsal (Task 30); enforce after production baseline is established.
