# System Warung Tom Yam — Implementation Plan (v2)

## Overview

Redesigned plan, July 2026, aligned with the current `requirements.md` and `designs.md`
(table sessions + payment, first-claim admin, invitation-based staff join, GPS attendance,
daily availability popup, aggregates-based metrics, catch-up sync).

**Planning principles**

1. **De-risk first.** The three things most likely to sink this project are Bluetooth
   thermal printing on the owner's actual hardware, Android OEM background-killing, and
   free-tier terms-of-service violations. They are Phase 0 spikes, not Phase 5+ surprises.
2. **Walking skeleton early.** A thin end-to-end slice (QR scan → order → admin phone
   beep) is built and measured against the < 3s latency NFR (REQ-8) before any feature
   depth is added. If the skeleton can't hit 3 seconds, the architecture changes while
   it's still cheap to change.
3. **Money path before everything else.** Order → kitchen → payment is the stall's
   lifeline (Phases 3–5). Attendance, reports, and i18n polish follow (Phases 6–9).
4. **Every task cites the requirements it satisfies** (REQ-x) for traceability.
5. **Zero cost, zero commitments** — every service below was re-verified against its
   July 2026 free-tier terms, including *commercial-use* clauses (a café is a commercial
   project; several popular free tiers forbid that).

## Free-Tier Stack (verified July 2026)

| Service | Role | Free limits | Commercial use OK? | Notes / fallback |
|---|---|---|---|---|
| **Supabase** | Postgres, Edge Functions, Realtime, Auth, Storage | 500 MB DB, 200 concurrent Realtime, 2M Realtime msgs/mo, 500k function calls/mo, 1 GB storage, 50k MAU | ✅ Yes | Pauses after **7 days of DB inactivity**; mitigated by Task 29 daily ping. Max 2 active free projects. |
| **Cloudflare Pages** | Website hosting (SPA) | Unlimited bandwidth, unlimited requests, 500 builds/mo, stable `*.pages.dev` subdomain | ✅ Yes | Chosen over Vercel: **Vercel Hobby forbids commercial use**. Fallback: Netlify free. |
| **Brevo** | Report emails (closing / monthly) | 300 emails/day, attachments ≤ 4 MB, sender verified by *email address* | ✅ Yes | Chosen over Resend: **Resend free requires a verified custom domain** (domains cost money = a yearly commitment). Usage is ~2 emails/day — far under limit. |
| **GitHub** (repo, Actions, Releases) | Source, CI, APK hosting, keep-alive cron | Actions free (2,000 min/mo private, unlimited public); Releases free | ✅ Yes | Also replaces UptimeRobot: **UptimeRobot free bans commercial use since Dec 2024**. A daily Actions cron `curl` keeps Supabase awake. |

**Explicitly rejected:** Vercel Hobby (no commercial use), UptimeRobot free (no commercial
use), Resend free (needs paid domain), Firebase FCM (design uses Supabase Realtime), any
paid domain (QRs encode the stable `*.pages.dev` URL — never rename the Pages project
after QR cards are printed).

---

## Tasks

### Phase 0 — Spikes and Contract (de-risk before building)

- [ ] 1. API contract and channel map *(blocks everything)*
  - Write `shared/api-contract.md`: every endpoint from `designs.md` with request/response
    JSON schemas, auth type (superadmin JWT / admin session token / ordering API key /
    public + browser ID), and error codes.
  - Define the Realtime channel map: `admin-orders`, `order:<orderId>`, `admin-devices`,
    `admin-attendance`, `cafe-status`, `settings`, `branding` — payload schema per event.
  - Define shared enums: order status (`RECEIVED, SENT_TO_KITCHEN, PREPARING, READY,
    COMPLETED, CANCELLED`), categories (`FOOD, BEVERAGES, SIDE_DISHES, OTHERS`), payment
    (`CASH, QR`), attendance (`CHECK_IN, CHECK_OUT`, `forced` flag).
  - **Deliverable**: contract agreed; website and APK teams can build in parallel against it.
  - *(REQ-1…REQ-10 — cross-cutting)*

- [ ] 2. SPIKE — Bluetooth thermal printing on real hardware *(highest project risk)*
  - Obtain the café's actual printer model(s) **before** writing feature code.
  - Prototype with `ESCPOS-ThermalPrinter-Android`: pairing, 58 mm and 80 mm text + logo
    bitmap, Malay diacritics, reconnect-after-power-cycle behaviour.
  - Output: a one-page compatibility note (works / quirks / fallback library needed).
  - **Deliverable**: proven print path on the owner's hardware; go/no-go on the library.
  - *(REQ-3 Printing)*

- [ ] 3. SPIKE — background survival + Realtime latency
  - Minimal APK with a foreground service holding a Supabase Realtime subscription;
    measure end-to-end broadcast latency (must support the < 3s NFR) and test survival:
    screen off 30 min, battery saver on, on at least one Xiaomi/Samsung budget device.
  - Minimal Edge Function echo test to confirm cold-start latency is acceptable.
  - **Deliverable**: measured latency numbers + OEM keep-alive checklist feeding Task 28.
  - *(REQ-4 Background Survival, REQ-8 Performance)*

### Phase 1 — Foundation

- [ ] 4. Repository, CI, and hosting scaffold
  - Monorepo: `website/` (Vite + React + Tailwind), `apk/` (Kotlin, minSdk 26, Compose,
    Hilt, Room, Retrofit), `shared/` (contract).
  - Cloudflare Pages project connected to the repo (pick the permanent project name —
    the `*.pages.dev` URL goes on printed QR cards and must never change).
  - GitHub Actions: lint + build on PR (website and APK); signed-APK release workflow
    publishing to GitHub Releases (feeds the dashboard Download APK link).
  - Establish the APK **build environment** per the section below (pinned toolchain,
    `local.properties`, `keystore.properties`).
  - **Deliverable**: empty shells build and deploy; APK release pipeline works.
  - *(REQ-6 Settings/Download APK, REQ-11)*

#### Build Environment — APK toolchain

The APK targets a **stable, mainstream** Android toolchain (deliberately not the newest
canary AGP/Kotlin/compileSdk, which are wrong for a production stall app). Everything the
build needs is standard and either already installed or auto-downloaded by the Gradle wrapper.

**Prerequisites:**
- Android SDK with stable platforms `android-34`/`android-36` and matching build-tools,
  plus platform-tools and `cmdline-tools/latest`. No NDK/CMake needed — the app has no
  native code.
- JDK **17** (Temurin recommended) to run Gradle.

**Pinned versions for `apk/`:**

| Tool | Pin | Why |
|---|---|---|
| Gradle (wrapper) | **8.9** | Auto-downloaded by `gradlew`; nothing to install. |
| Android Gradle Plugin | **8.7.x** | Mature; first-class Hilt/Room/Compose support. |
| Kotlin | **2.0.x** | Stable; pairs with the Compose Compiler Gradle plugin. |
| KSP | matching `2.0.x-*` | For Room + Hilt annotation processing. |
| compileSdk / targetSdk | **36** | Current stable platform. |
| minSdk | **26** | Per REQ-11 (Android 8.0). |
| JDK to run Gradle | **17** | `jvmTarget = 17`; AGP 8.7's sweet spot. |
| Java/Kotlin `jvmTarget` | **17** | — |

**Per-project files (both git-ignored):**
- `apk/local.properties` — one line wiring to the local SDK, e.g.:
  `sdk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk`
- `apk/keystore.properties` — path + passwords in a git-ignored file; the release build
  falls back to UNSIGNED when absent so CI still compiles:
  `storeFile=`, `storePassword=`, `keyAlias=`, `keyPassword=`. **Generate a keystore**
  for this app (`keytool -genkeypair -kealg RSA -keysize 2048 -validity 10000`) — never
  commit the `.jks` or this file.

**Gotchas:**
- If `JAVA_HOME` and the PATH `java` point at different JDK versions, the build can be
  non-deterministic. Fix the compile JDK with a **Gradle Java toolchain**
  (`kotlin { jvmToolchain(17) }`) so it is pinned regardless of which `java` is resolved.
- `local.properties` is per-project and git-ignored, so each fresh clone needs its own
  `sdk.dir` line — document this in the repo README (CI sets `ANDROID_SDK_ROOT` instead).

**Local build sanity check:** `cd apk && ./gradlew assembleDebug` (debug) /
`./gradlew assembleRelease` (signed, needs `keystore.properties`). The GitHub Actions
release workflow uses `actions/setup-java@v4` (Temurin 17) + `android-actions/setup-android`
and runs `assembleRelease`, uploading the APK to the GitHub Release.

- [ ] 5. Supabase project and schema
  - Tables (fixes the v1 schema bug where `settings` was mislabeled as a second
    `sessions` table):
    - `devices` — id, device_identifier, android_id, device_model, role (ADMIN|ORDERING),
      status (PENDING|APPROVED|REVOKED), api_key_hash, session_token_hash, label,
      is_checked_in, last_seen_at, created_at
    - `settings` — key/value. Seeds: `print_language=EN` (English is the base/default),
      `timezone=Asia/Kuala_Lumpur`,
      `top_n_items=5`, `report_email`, `closing_report_auto=true`,
      `staff_can_send_kitchen=false`, `staff_can_take_payment=false`
    - `tables` — id (slug, e.g. `T1`), display_name, created_at *(validates incoming
      tableIds; enforces the ≤ 20 scale)*
    - `orders` (ACTIVE only) — id, table_id FK, source (QR|STAFF), browser_id,
      status (RECEIVED|SENT_TO_KITCHEN|PREPARING|READY|COMPLETED|CANCELLED),
      payment_method (CASH|QR|null), sent_to_kitchen_at, cancel_reason, cancelled_by,
      items_json (**per line: name/unitPrice/category snapshot + `sentToKitchen` flag**,
      server-priced), total, created_at, purge_after
      *(one active order per table = the table session; the per-line snapshot + `sentToKitchen`
      flag enable delta kitchen slips and menu-edit-safe history)*
    - `menu_snapshot` — menu_json (4 categories, multilingual, askMeDaily/isAvailable),
      updated_at
    - `branding` — cafe_name, logo_url, updated_at
    - `sessions` — id, event (OPEN|CLOSE), reason, timestamp
    - `attendance` — id, device_id FK, event (CHECK_IN|CHECK_OUT), latitude, longitude,
      **forced** (bool), timestamp
    - `cafe_location` — singleton: latitude, longitude, radius_meters, updated_at
    - `aggregates` — date (PK), total_orders, total_revenue, avg_order_value,
      payment_split_json (cash/qr counts + amounts), cancelled_count, cancelled_value,
      top_items_json (per 4 categories) *(pushed by admin APK; the dashboard's only
      order-history source; content matches the end-of-day report checklist)*
    - `payment_transactions` — id, order_id FK, method (CASH|QR), amount, created_at
      *(append-only; one row per order today, table-shaped for future split payments —
      append-only payment model)*
    - `invites` — token, rotated_at *(current ordering-role invitation)*
  - RLS on all tables; Storage bucket `logos` (public read); Supabase Auth for the
    single superadmin account; `ROTATING_KEY_SECRET` as an Edge Function env secret.
  - Scheduled purge (pg_cron) of COMPLETED/CANCELLED orders past `purge_after` —
    keeps the backend transient per Decision 1 / REQ-10.
  - **Deliverable**: schema live with RLS, seeds, storage, auth, purge job.
  - *(REQ-5, REQ-6, REQ-10)*

### Phase 2 — Backend API (Edge Functions)

- [ ] 6. Identity: rotating key, first-claim admin handshake, invitations, devices
  - `GET /api/rotating-key` (superadmin) — HMAC-SHA256(secret, floor(unix/30)),
    `{ key, expiresInSeconds }`; never stored or logged.
  - `POST /api/admin/handshake` — `{ deviceId, rotatingKey }`, ±1 window (60s grace).
    **First-claim**: rejected with `409 ADMIN_EXISTS` while an admin device is
    registered. Success → hashed long-lived session token; deregistration (below)
    re-opens the claim.
  - `GET /api/invite` / `POST /api/invite/regenerate` (admin token) — current ordering
    invitation token; regeneration invalidates the old URL instantly.
  - `POST /api/register` — `{ inviteToken, deviceId, deviceModel, androidId, appVersion }`;
    validates token, inserts PENDING device (label = model), broadcasts JOIN_REQUEST on
    `admin-devices`.
  - `GET /api/devices/status?deviceId=` — poll for approval + API key.
  - `GET /api/devices` (superadmin) and `PATCH /api/devices/:id` (superadmin **or admin
    token**) — approve/reject/rename/revoke/force-sign-out. **No role changes.**
    Deregister-admin resets to pre-café state (setup screen + fresh rotating key).
  - Tests: key expiry, replay, second-admin rejection, revoked-invite join attempt.
  - **Deliverable**: both connect flows and full device lifecycle live.
  - *(REQ-2, REQ-4 Registration, REQ-5, REQ-6)*

- [ ] 7. Orders and table sessions
  - `POST /api/orders` — public (browser ID) or staff/admin (key). Validates table
    exists, **rejects if table has an active session**, re-prices server-side from
    `menu_snapshot` (client prices ignored), checks item availability, rate-limits by
    IP + browser ID, broadcasts to `admin-orders`.
  - `GET /api/tables/:tableId/session` — public: `FREE` / `OCCUPIED` / own order +
    status when the caller's browser ID owns the session.
  - `POST /api/orders/:id/kitchen` — admin or RBAC-permitted staff; marks unsent lines
    `sentToKitchen=true`, stamps `sent_to_kitchen_at`, sets status SENT_TO_KITCHEN.
    Returns the set of lines to print (all on first send, **delta = new lines only** on
    later sends) so the APK prints an "ADDED" slip without re-sending cooked items.
  - `POST /api/orders/:id/items` — admin or RBAC-permitted staff; appends lines to an
    active order (amendment), each `sentToKitchen=false`; re-priced server-side.
  - `POST /api/orders/:id/payment` — `{ method: CASH|QR }`; only after sent-to-kitchen;
    → COMPLETED, session ends, table FREE. Writes a `PaymentTransaction` row.
  - `DELETE /api/orders/:id` — admin/staff anytime (records `cancelled_by` + reason);
    customer browser ID only while status = RECEIVED.
  - `PUT /api/orders/:id/status` — admin token; broadcasts to `order:<orderId>`.
  - `GET /api/orders?since=<ts>` — admin token; **catch-up sync** for reconnects.
  - Tests: double-order on occupied table, price tampering, customer cancel after
    send-to-kitchen (rejected), payment before kitchen (rejected), **amendment after
    send-to-kitchen prints only the delta lines**, menu price change does not alter an
    existing active order (snapshot holds).
  - **Deliverable**: complete order/session lifecycle with all guards.
  - *(REQ-1, REQ-3 Orders, REQ-10, REQ-8 Security)*

- [ ] 8. Menu, branding, settings, café location
  - `GET/PUT /api/menu` — multilingual snapshot (4 categories, askMeDaily,
    availability); `{ configured: false }` until first push; 60s CDN cache.
  - `GET/PUT /api/branding` — logo base64 → Storage `logos/`, public URL; broadcast on
    `branding`.
  - `GET/PUT /api/settings` — print language, timezone, top-N, report email, **staff
    RBAC toggles**; broadcast on `settings`.
  - `PUT /api/cafe-location` (admin) / `GET /api/cafe-location` (ordering key) — GPS-lock
    coordinates + radius; authoritative attendance reference.
  - On the APK side, store on-device settings in a single `DataStore<Preferences>` with
    typed keys behind a `SettingsManager` interface→impl — but keep tokens/keys OUT of
    DataStore (those go in encrypted storage, Task 14 / REQ-12 Gap B).
  - **Deliverable**: all configuration surfaces read/write with Realtime propagation.
  - *(REQ-3 Menu/Branding/Location, REQ-9)*

- [ ] 9. Sessions, attendance, aggregates, metrics, reports
  - `POST /api/sessions` — OPEN/CLOSE (+reason). OPEN broadcasts `CAFE_OPEN`; CLOSE with
    closing broadcasts `CAFE_CLOSED` on `cafe-status`. A new OPEN implicitly closes a
    dangling session at last backend activity (crash recovery).
  - `POST /api/attendance` — CHECK_IN/CHECK_OUT with GPS + `forced` flag (admin
    override); broadcasts on `admin-attendance`.
  - `POST /api/aggregates` (admin token) — upsert daily summary.
  - `GET /api/metrics?period=` (superadmin) — computed from **aggregates + sessions**
    (never raw orders), all boundaries in the café's configured timezone.
  - `GET /api/reports/closing` and `GET /api/reports/monthly?month=` — build from
    aggregates + sessions; **HTML email via Brevo API** (attachment PDF via `pdf-lib`
    if stable in Deno — HTML body is the guaranteed path) to `settings.report_email`;
    also returned for download. **Content checklist (standard end-of-day POS report)**:
    header (café, date, open hours, closing reason); sales
    (completed orders, gross revenue, avg order value); tender breakdown (Cash vs QR
    count + amount); exceptions (cancelled count/value by who cancelled, amendment-slip
    count); Top-N per all four categories.
  - Tests: timezone boundaries (23:59 MYT order lands on the right day), dangling
    session closure, forced check-out.
  - **Deliverable**: metrics and emailed reports working from aggregates only.
  - *(REQ-6, REQ-7, REQ-4 Attendance, REQ-8 Time Zone)*

### Phase 3 — Walking Skeleton (integration checkpoint)

- [ ] 10. End-to-end skeleton and latency gate
  - Thinnest possible slice: hard-seeded menu → customer page cart → `POST /api/orders`
    → Realtime → bare admin APK screen logging the order + notification beep.
  - Measure scan-to-notification latency on real phones over 4G. **Gate: < 3s** (REQ-8).
  - Exercise Supabase pause/wake once (pause project, hit endpoint, measure wake).
  - **Deliverable**: proven pipeline + measured numbers; architecture confirmed before
    feature build-out.
  - *(REQ-8 Performance)*

### Phase 4 — Website Frontend

- [ ] 11. Customer ordering page (session-aware)
  - `/order?table=<id>`: on load call `GET /api/tables/:id/session` and branch —
    FREE → menu; OCCUPIED (other) → "Table occupied" screen; OCCUPIED (own browser ID)
    → status view.
  - Generate + persist the anonymous **browser ID** (localStorage UUID); send with orders.
  - Branding header (or "Coming soon" placeholder until configured); café-closed state
    (no ordering while `CAFE_CLOSED`).
  - i18next with **`en` as base locale** + `bm/zh/ta` as dictionary translations (REQ-9);
    default to `en` on first load; sticky language selector; **four** category tabs (Food,
    Beverages, Side Dishes, Others); item names via `name[lang]` **falling back to `en`**
    when a dictionary entry is missing; unavailable items greyed out.
  - Cart → submit → confirmation; subscribe `order:<orderId>` for live status; **Cancel
    button while status = RECEIVED only**; rescan shows the same status view.
  - Mobile-first, WCAG 2.1 AA, < 2s load on 4G mid-range (code-split, no heavy deps).
  - **Deliverable**: full customer journey — order, rescan for status, cancel
    pre-kitchen, occupied/closed states, 4 languages.
  - *(REQ-1, REQ-9, REQ-8 Accessibility)*

- [ ] 12. Superadmin website
  - Auth: register (email verification), login, forgot-password (Supabase Auth).
  - **Setup screen** (pre-café): webhook URL + rotating key with 30s countdown,
    instructions for the APK's *Connect as Admin* button; auto-transition to dashboard
    once the handshake lands.
  - **Dashboard**: numbers-only metrics grid (orders / revenue / open hours × today,
    week, month, last month, 12 months) from `GET /api/metrics`; "admin phone offline"
    banner (> 30 min without OPEN heartbeat).
  - **Devices**: list with label, role badge, last-seen, online/checked-in status.
    Actions: Rename, Force Sign Out, Deregister, **Force Check-Out**. *(No
    promote/demote — roles are fixed by connect flow.)*
  - **Orders feed**: live `admin-orders` subscription (display in print language).
  - **Settings**: Download APK (GitHub Releases link), Deregister Admin Phone (password
    re-entry → setup screen returns), closing-report toggle + recipient, monthly report
    generate/download/email, Top-N input (1–20).
  - **Deliverable**: complete superadmin surface per REQ-6.
  - *(REQ-5, REQ-6)*

- [ ] 13. Website QR sheet generator (optional convenience path)
  - Table list → in-browser QR SVGs (`qrcode` npm) → print-ready A4 portrait CSS
    (`@page`) with 4 self-contained A6 cards per sheet, hairline cut guides,
    `window.print()`.
  - **Deliverable**: browser-printable QR sheets matching the APK PDF layout.
  - *(REQ-3 QR PDF — SHOULD-level alternative)*

### Phase 5 — Admin APK: Connection, POS Core, Menu

- [ ] 14. Role selection and connection flows
  - `RoleSelectScreen`: big **Connect as Ordering Staff** button, smaller **Connect as
    Admin** button (REQ-2).
  - Admin path: webhook URL + rotating key → handshake → store session token in
    **secure storage → do the REQ-12 Gap B research spike first** (`EncryptedSharedPreferences`
    /Keystore). → AdminNavGraph. Handle `409 ADMIN_EXISTS` with a clear message.
    Auto sign-in on next start (token survives Sign Out, not reinstall).
  - Ordering path: invitation URL entry with **format validation** (rejects non-invite
    URLs) → fingerprint register → PendingApprovalScreen (10s poll) → store permanent
    API key on approval (same secure storage as above).
  - Build a self-contained runtime-permission helper (no Accompanist needed) with a
    "denied-twice ⇒ permanently-denied → open app settings" fallback.
  - **Deliverable**: both connect flows on-device, matching backend Task 6; tokens/keys in
    encrypted storage (never plain DataStore/SharedPreferences — REQ-8).
  - *(REQ-2, REQ-4 Registration, REQ-5, REQ-12 Gap B)*

- [ ] 15. Admin session lifecycle + Daily Availability popup
  - On start with valid token: post OPEN; backend broadcasts `CAFE_OPEN`.
  - **Sign Out**: CLOSE (no reason) → stop services → lock screen; token kept.
  - **Sign Out with Closing**: reason dialog → compute today's aggregate from Room →
    `POST /api/aggregates` → CLOSE (with reason) → backend emails closing report and
    broadcasts `CAFE_CLOSED` → lock screen; token kept.
  - **Daily Availability popup**: on first OPEN of the day (café timezone), if any
    `askMeDaily` item exists — top-layer modal, dimmed background; per item mark
    Available / Not available (+ optional price for market-priced items) → update Room →
    `PUT /api/menu`.
  - Guarantee CLOSE delivery with a blocking coroutine before service teardown.
  - **Deliverable**: session tracking, both sign-outs, aggregate push, daily popup.
  - *(REQ-3 Menu/ask-me-daily, REQ-7)*

- [ ] 16. Menu management (type-first, four categories, no images)
  - Room `MenuItem` (multilingual fields, `askMeDaily`, `isAvailable`,
    `lastAvailabilityConfirmedDate`) + `SystemSettings`. Room `Order`/`OrderItem` with
    **snapshot fields** (`nameSnapshot`, `unitPriceSnapshot`, `categorySnapshot`) and
    per-line `sentToKitchen` flag; single `status` enum (no scattered booleans).
  - **Type-first add flow**: pick menu type (Food / Beverages / Side Dishes / Others) →
    key in name + price **in English (the base language)**; other languages are
    dictionary-translated (REQ-9 / REQ-12 Gap A), not hand-entered — show the resolved
    BM/中文/தமிழ் with an optional **manual override** field per term and a "do not
    translate" toggle for dish proper-nouns; "Ask me daily" toggle; availability toggle.
    No image field.
  - Any change → `PUT /api/menu` full snapshot (live on website ≤ 60s). Menu edits never
    touch existing order lines (line-item snapshot pattern).
  - **Deliverable**: complete menu CRUD + snapshot-safe order schema matching REQ-3.
  - *(REQ-3 Menu/Line-Item Integrity, REQ-9)*

- [ ] 17. Table View POS + order reception + catch-up sync
  - `AdminForegroundService`: Realtime `admin-orders` subscription, persistent
    notification, **on every (re)connect call `GET /api/orders?since=<lastSeen>`** and
    reconcile — no lost orders (REQ-3 catch-up). Build the service with the standard
    pattern: a low-importance notification channel, `ServiceCompat.startForeground` with an
    appropriate foreground-service-type, `ACTION_UPDATE`/`STOP` intents, and safe stop.
  - **Table View dashboard** (primary surface): grid of tables, Free/Occupied +
    status colour. Per-table sheet: order detail, **Add items** (amendment → new lines
    `sentToKitchen=false`), **Send to Kitchen** (→ prints unsent lines only; **delta
    "ADDED" slip** on a re-send, via Task 22), status updates, **Cancel** (records who +
    reason), **Payment (Cash | QR)** enabled only after sent-to-kitchen → COMPLETED +
    session end.
  - Auto-send-to-kitchen toggle (SHOULD); live order queue list as secondary view;
    status-bar notification per new order.
  - Table registry management (add/rename/delete tables, synced to backend `tables`).
  - **Deliverable**: the POS — every order lands on the Table View and is driven to
    paid/cancelled from there.
  - *(REQ-3 Orders/Table View, REQ-10)*

- [ ] 18. Manual dine-in entry + device approvals + staff settings
  - Manual dine-in: table select → menu → submit (`source: STAFF`), identical
    downstream handling.
  - `admin-devices` subscription: join-request alert ("[Samsung Galaxy A23] is
    requesting to join…") with Approve/Reject; Devices screen (pending + connected,
    revoke, **Force Check-Out**, attendance history per device).
  - Settings: **Staff Invitation** (show/share invitation URL, Regenerate), **Staff
    Permissions** — named catalog `CREATE_ORDER`/`CANCEL_ORDER` (granted) +
    `SEND_TO_KITCHEN`/`TAKE_PAYMENT` toggles (default off); optional **manager-override**
    path (disallowed staff action → one-time admin approval prompt, no global toggle
    change). **Café Location GPS lock**
    (capture fix + accuracy warning + radius → `PUT /api/cafe-location`), Café Profile
    (name + logo ≤ 200 KB upload → branding push), Backend Connection info.
  - **Logo pipeline** (native-code-free): image picker (gallery/camera) → square (1:1)
    crop → compress-to-target loop (step JPEG quality down, then downscale dimensions)
    to hit **≤ 200 KB JPEG** → `Base64.encodeToString(bytes, NO_WRAP)` for the
    `PUT /api/branding` upload. Convert any hardware bitmap to a software bitmap before
    encoding (hardware bitmaps can't be compressed). Cache the logo locally so receipt
    printing works offline.
  - **Deliverable**: full admin management surface; logo pick→crop→compress→base64 working.
  - *(REQ-3 Devices/Branding/Location/Invitation, REQ-4 Approval)*

### Phase 6 — Ordering-Role APK

- [ ] 19. GPS attendance and café state machine
  - `OrderingForegroundService` (persistent notification, `FOREGROUND_SERVICE_TYPE_LOCATION`;
    same foreground-service pattern as Task 17, LOCATION type; permission flow via the
    Task 14 permission helper),
    battery-optimization exemption prompt, `BOOT_COMPLETED` receiver.
  - Subscribe `cafe-status`; state machine: CafeClosedScreen (Check Out only) ↔
    CheckInScreen ↔ OrderingScreen. **On reconnect, re-fetch café status + settings +
    table states** (catch-up, not broadcast-dependent).
  - Check-in/out: fresh GPS fix → distance vs `GET /api/cafe-location` radius →
    `POST /api/attendance`; friendly rejection outside radius; handle admin-forced
    check-out events.
  - **Deliverable**: staff attendance lifecycle, café open/closed compliance.
  - *(REQ-4 Check-in/out/Closed state/Background survival)*

- [ ] 20. Staff order entry + staff Table View (RBAC)
  - After check-in: **Table View** (same realtime data as admin) + order entry (table →
    menu → cart → submit with API key). Unavailable/ask-me-daily-off items blocked.
  - RBAC from settings: Cancel always; Send to Kitchen / Payment hidden unless enabled.
  - Offline queue: failed POSTs → Room `PendingOrder` → WorkManager retry; offline
    banner (SHOULD).
  - **Deliverable**: staff can run the floor within admin-set permissions.
  - *(REQ-4 Order Entry, REQ-3 Staff Permissions)*

### Phase 7 — Printing and QR PDF

- [ ] 21. Printer registry and dispatcher
  - Room `PrinterConfig` (name, MAC, 58/80 mm, RECEIPT_ONLY/KITCHEN_ONLY/BOTH, active);
    Printers screen: BT scan → add → edit → remove → test print; Android 12+ permission
    flow (`BLUETOOTH_SCAN/CONNECT`).
  - `PrinterDispatcher`: role match → fallback to BOTH → else queue + "No printer
    configured" alert. Per-printer `PrintJob` queue with reconnect retry. Reserve a
    nullable `categoryFilter` on `PrinterConfig` (null = all categories) for future
    per-category routing (virtual-printer routing headroom) — not wired to UI yet, but in
    the schema so enabling it later needs no migration.
  - Char/image widths resolved **per printer** (58 mm: 32 ch / 384 px; 80 mm: 48 ch / 576 px).
  - **Deliverable**: multi-printer setup exactly per REQ-3, informed by Spike (Task 2).
  - *(REQ-3 Printing)*

- [ ] 22. Kitchen slip and customer receipt documents
  - `KitchenSlipDocument`: big table number, items + qty, notes, timestamp — printed on
    **Send to Kitchen** (or auto-send), containing **only lines with `sentToKitchen=false`**.
    On a re-send after amendment it is a **delta slip** headed "TAMBAHAN / ADDED — Table N"
    (new lines only, never re-cooking already-sent items).
    `ReceiptDocument`: branding header (cached logo), **all** consolidated lines at
    snapshotted prices, totals, **payment method (Cash/QR)**, thank-you — printed on
    **Payment** or manual re-print. All labels via active print language (EN default / BM).
  - Re-print any past slip/receipt from order detail (SHOULD).
  - **Deliverable**: correct documents to the correct printer at the correct lifecycle
    moment.
  - *(REQ-3 Printing, REQ-9, REQ-10)*

- [ ] 23. QR PDF generator (A6 cards, 4-up A4 portrait)
  - ZXing + `PdfDocument`; A4 portrait, 2×2 grid of A6 portrait cards; per card: logo
    (≤ 40×40 mm, optional), café name, QR (60×60 mm, EC-H), table label, hairline cut
    guides; blank trailing cells; table subset selection; save via MediaStore +
    `ACTION_SEND` share.
  - **File save/share**: a per-OS saver (MediaStore on API 29+ writing to
    `DIRECTORY_DOWNLOADS`, legacy `File` below) for the PDF, and a cache-then-share helper
    (write bytes to `cacheDir` → `FileProvider.getUriForFile` → `ACTION_SEND` + chooser)
    for the share sheet. Add the FileProvider manifest entry + `file_paths.xml`.
  - **QR encoder**: `generateQrBitmap(content, widthPx, heightPx, paddingPx, fg, bg, format)`
    — wrap ZXing `MultiFormatWriter` → Android `Bitmap` on `Dispatchers.IO`, dropping
    straight into a `PdfDocument` cell. Use **`ErrorCorrectionLevel.H`** (physical cards get
    scuffed/greasy on tables — high EC matters). Add `com.google.zxing:core` to the `apk/`
    module. Keep the built-in `PdfDocument` (no third-party PDF library needed).
  - **Preview (nicety)**: a small composable to preview a table's QR on screen before
    generating the PDF.
  - The 4-up A6 tiling + cut-guide layout is bespoke to this project.
  - QR URL = `https://<project>.pages.dev/order?table=<slug>` — **domain locked from
    Task 4 onward**.
  - **Deliverable**: print-shop-ready multi-page PDF.
  - *(REQ-3 QR PDF)*

### Phase 8 — Language (English base + dictionary translation)

- [ ] 24. Dictionary-translation i18n and print-language propagation *(implements REQ-12 Gap A — do the research spike first)*
  - **Research spike (Gap A)** — decide the dictionary mechanism before coding: static
    string dictionaries (English source → BM/中文/தமிழ் key tables, bundled/offline),
    the curated café **food-term dictionary** for menu content, the English-fallback rule,
    the "do not translate" flag, the admin manual-override store, and where dictionaries
    live (bundled vs. an extendable `translations` backend table). **Zero paid translation
    APIs** — any machine translation is a one-time build-time step, never a runtime call.
  - APK resources: `res/values/strings.xml` = **English (base/default)**;
    `res/values-ms/strings.xml` = Malay (generated from the English source at build time,
    overridable — note `values-ms`, NOT `values-in`). Set `generateLocaleConfig = true`
    (AGP auto-generates the per-app language list from the `values-*` folders).
  - Website: i18next `en` base + `bm/zh/ta` dictionary bundles (built in Task 11), English
    fallback for missing keys.
  - Propagation: language setting → `PUT /api/settings` → `settings` Realtime → staff APKs
    re-locale on next sync/restart; superadmin dashboard mirrors it; confirmation dialog.
  - All print documents and report builders read the language at render time (already
    structured this way from Tasks 21–22).
  - **Deliverable**: English-default system; one toggle switches operational output to
    Malay; customer site offers all four languages via dictionaries with English fallback.
  - *(REQ-9, REQ-12 Gap A)*

### Phase 9 — Reports, Backup

- [ ] 25. On-device reports (daily/weekly)
  - Room queries: totals, revenue, avg order value, per-table, top-N per **four**
    categories, **cash-vs-QR split**, **cancelled count/value by who cancelled**;
    date-range screen; PDF (`PdfDocument`) + CSV export + share. Same content checklist
    as the closing report (Task 9).
  - **Deliverable**: admin analyses sales on the phone, in the print language.
  - *(REQ-3 Reports)*

- [ ] 26. Full database export / import
  - JSON envelope v2: `{ version, exportedAt, menuItems, orders, orderItems,
    paymentTransactions, devices, printers, settings, attendance, cafeLocation,
    aggregatesCache }` — genuinely *full* (v1 omitted attendance/location/payments).
    Export to Downloads/Drive share; import with version check + preview + confirm.
  - Use the same file saver + cache-then-share helper as Task 23 to write/share the backup;
    a `CreateDocument` picker ("Save as…") for the export location and an `OpenDocument`
    picker (filtered by MIME) for re-import.
  - Weekly backup reminder notification (mitigates single-point-of-failure phone).
  - **Deliverable**: complete restore path onto a replacement phone.
  - *(REQ-3 Database)*

### Phase 10 — Hardening and Field Test

- [ ] 27. Chaos and reconnect testing
  - Scripted tests: kill WebSocket mid-order (catch-up must recover), airplane-mode
    staff order (queue must flush), backend down during payment (must not double-pay),
    Supabase wake-from-pause during service.
  - **Deliverable**: documented recovery behaviour for each failure.
  - *(REQ-8 Reliability)*

- [ ] 28. OEM keep-alive matrix
  - Apply Spike-3 checklist: Xiaomi AutoStart, Samsung sleeping-apps whitelist, generic
    battery-exemption deep links where detectable; in-app setup guide screens.
  - **Deliverable**: both service types survive a full day on target devices.
  - *(REQ-4 Background Survival)*

- [ ] 29. Keep-alive ping + ops runbook
  - GitHub Actions scheduled workflow (daily cron) hitting `GET /api/menu` — prevents
    the 7-day Supabase free-tier pause **without UptimeRobot** (its free plan bans
    commercial use). Document in README: holidays > 7 days are covered by the cron;
    manual wake procedure; Brevo sender verification steps; APK release process.
  - **Deliverable**: the stall never finds a paused backend on Monday morning.
  - *(REQ-8 Cost)*

- [ ] 30. Full-day field rehearsal (acceptance)
  - One real service day: print QR cards, staff check-in, mixed QR/staff orders,
    kitchen + receipt printing, payments cash and QR, a cancellation, a rescan status
    check, Sign Out with Closing → email received, dashboard numbers verified against
    the paper till.
  - **Deliverable**: signed-off MVP; punch list becomes the post-MVP backlog.
  - *(all REQs — acceptance)*

---

## Task Dependency Graph

```json
{
  "waves": [
    { "wave": 0, "tasks": [1, 2, 3],
      "description": "Contract + the two killer-risk spikes. 2 and 3 run in parallel with 1." },
    { "wave": 1, "tasks": [4, 5],
      "description": "Repo/CI/hosting and Supabase schema, in parallel, once the contract is agreed." },
    { "wave": 2, "tasks": [6, 7, 8, 9],
      "description": "All backend APIs in parallel against the schema." },
    { "wave": 3, "tasks": [10],
      "description": "Walking skeleton — integration + latency gate. Nothing proceeds until it passes." },
    { "wave": 4, "tasks": [11, 12, 13, 14, 15, 16],
      "description": "Website (11–13) and admin APK core (14–16) in parallel." },
    { "wave": 5, "tasks": [17, 18],
      "description": "Table View POS and admin management — the money path complete for the admin." },
    { "wave": 6, "tasks": [19, 20, 21],
      "description": "Staff APK (19–20) and printer registry (21, after Spike 2) in parallel." },
    { "wave": 7, "tasks": [22, 23, 24],
      "description": "Print documents, QR PDF, language propagation." },
    { "wave": 8, "tasks": [25, 26],
      "description": "Reports and backup." },
    { "wave": 9, "tasks": [27, 28, 29, 30],
      "description": "Hardening, keep-alive ops, and the field-day acceptance test." }
  ]
}
```

**MVP cut line**: tasks 1–23 + 29 are required for opening day. 24 (EN language), 25–26
(reports/backup), 27–28 (hardening depth) can trail by days — but 30 (field rehearsal)
gates real service.

---

## Notes and Standing Risks

- **Never rename the Cloudflare Pages project** after QR cards are printed — the
  `*.pages.dev` URL is baked into physical cards. This is the accepted zero-cost
  trade-off for not buying a domain (gap #4 in designs.md).
- **Brevo sender verification**: verify the superadmin's own email address as sender
  (no domain needed). Reports go to the same or a configured address (REQ-6).
- **Supabase 2-active-project limit**: use one project; a second free project can serve
  as staging only temporarily.
- **Realtime message budget** (2M/month free): each order generates a handful of
  broadcasts across ≤ 30 subscribers — thousands/day at stall scale, well under budget.
- **Printing is the schedule risk**, not the backend: Task 2's spike verdict decides
  whether Task 21 is a formality or needs a library change. Do not defer the spike.
- **`ANDROID_ID` resets on factory reset** — a reset staff phone simply re-joins via the
  invitation; the admin deregisters the orphaned record (accepted, documented).
- **GPS spoofing** can defeat attendance (mock-location apps). Accepted risk at stall
  scale; noted here so it is a known limitation, not a surprise.
- **Timezone discipline**: every "today" — metrics, aggregates, the daily availability
  popup, closing reports — uses `settings.timezone` (default `Asia/Kuala_Lumpur`).
  UTC bugs here corrupt reports silently; Task 9's boundary tests are mandatory.
- **Order model design principles** (baked into the schema and tasks): line-item
  snapshotting (freeze name/price/category per line), delta kitchen slips (per-line
  `sentToKitchen` flag → re-send prints only new lines), an append-only `PaymentTransaction`
  table, a single `Order.status` enum (never scattered booleans), and a named RBAC
  permission catalog with an optional manager-override.
- **Two open design items → research before coding (REQ-12)**:
  - **Gap A — Dictionary-translation i18n**: the system is **English-default**; other
    languages are dictionary-translated over the English source (customer site
    BM/中文/தமிழ், operational BM), not hand-entered. Spike this in Task 24 before coding.
  - **Gap B — Encrypted token storage**: build `EncryptedSharedPreferences`/Keystore for
    the admin session token + ordering API key (Task 14). Never store tokens in plain
    DataStore.

## Sources (free-tier verification, July 2026)

- [Supabase pricing](https://supabase.com/pricing) · [Supabase free-project pausing](https://supabase.com/docs/guides/platform/free-project-pausing) · [Supabase free tier limits 2026](https://aiagencyplus.com/supabase-free-tier-limits/)
- [Vercel Hobby limits / commercial restriction](https://www.promptstoproduct.com/vercel-free-tier-limits) · [Cloudflare Pages vs Vercel](https://fernsidestudio.com/blog/cloudflare-pages-vs-vercel-business/)
- [Resend free tier](https://resend.com/blog/new-free-tier) · [Brevo free plan limits](https://help.brevo.com/hc/en-us/articles/208580669-FAQs-What-are-the-limits-of-the-Free-plan) · [Brevo transactional attachments](https://developers.brevo.com/reference/send-transac-email)
- [UptimeRobot pricing (commercial-use ban on free)](https://uptimerobot.com/pricing/) · [UptimeRobot free plan limits 2026](https://stillup.org/blog/uptimerobot-free-plan-limits)
- [Preventing Supabase pausing with a cron ping](https://www.georgemccarron.com/blog/preventing-supabase-pausing)
