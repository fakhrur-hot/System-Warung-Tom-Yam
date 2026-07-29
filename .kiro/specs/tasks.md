# Implementation Plan:

## Overview

Redesigned plan, July 2026, aligned with the current `requirements.md` and `designs.md`
(table sessions + payment, first-claim admin, invitation-based staff join, GPS attendance,
daily availability popup, aggregates-based metrics, catch-up sync).

**Planning principles**

1. **De-risk first.** The three things most likely to sink this project are Bluetooth thermal printing on the owner's actual hardware, Android OEM background-killing, and free-tier terms-of-service violations. They are Phase 0 spikes, not Phase 5+ surprises.
2. **Walking skeleton early.** A thin end-to-end slice (QR scan → order → admin phone beep) is built and measured against the < 3s latency NFR (REQ-8) before any feature depth is added.
3. **Money path before everything else.** Order → kitchen → payment is the stall's lifeline (Phases 3–5). Attendance, reports, and i18n polish follow (Phases 6–9).
4. **Every task cites the requirements it satisfies** (REQ-x) for traceability.
5. **Zero cost, zero commitments** — every service below was re-verified against its July 2026 free-tier terms, including *commercial-use* clauses.

## Tasks

### Phase 0 — Spikes and Contract (de-risk before building)

- [x] 1. API contract and channel map
  Write `shared/api-contract.md`: every endpoint from `designs.md` with request/response JSON schemas, auth type (superadmin JWT / admin session token / ordering API key / public + browser ID), and error codes. Define the Realtime channel map (`admin-orders`, `order:<orderId>`, `admin-devices`, `admin-attendance`, `cafe-status`, `settings`, `branding`) with payload schema per event. Define shared enums: order status, categories, payment, attendance. Deliverable: contract agreed; website and APK teams can build in parallel. *(REQ-1…REQ-10)*

- [x] 2. SPIKE — Bluetooth thermal printing on real hardware
  Obtain the café's actual printer model(s) before writing feature code. Prototype with `ESCPOS-ThermalPrinter-Android`: pairing, 58mm and 80mm text + logo bitmap, Malay diacritics, reconnect-after-power-cycle behaviour. Output: a one-page compatibility note (works / quirks / fallback library needed). Deliverable: proven print path on the owner's hardware; go/no-go on the library. *(REQ-3 Printing)*

- [x] 3. SPIKE — background survival + Realtime latency
  Minimal APK with a foreground service holding a Supabase Realtime subscription; measure end-to-end broadcast latency (must support the < 3s NFR) and test survival: screen off 30 min, battery saver on, on at least one Xiaomi/Samsung budget device. **Edge Function cold-start test**: hit `POST /api/orders` (or an echo endpoint) after 15+ minutes of inactivity and measure response time — if cold-start latency alone exceeds ~2s, evaluate alternatives (direct PostgREST insert with RLS + database trigger for broadcast, or a warmup ping hitting Edge Functions specifically). Confirm the outbound-WSS-only architecture (no inbound HTTP server on the phone) survives all test scenarios. Deliverable: measured latency numbers (warm + cold), cold-start mitigation decision, + OEM keep-alive checklist feeding Task 28. *(REQ-4 Background Survival, REQ-8 Performance)*

### Phase 1 — Foundation

- [x] 4. Repository, CI, and hosting scaffold
  Monorepo: `website/` (Vite + React + Tailwind), `apk/` (Kotlin, minSdk 26, Compose, Hilt, Room, Retrofit), `shared/` (contract). Cloudflare Pages project connected to the repo (permanent `*.pages.dev` URL for printed QR cards). GitHub Actions: lint + build on PR (website and APK); signed-APK release workflow publishing to GitHub Releases. Establish the APK build environment (JDK 17, Gradle 8.9, AGP 8.7.x, Kotlin 2.0.x, compileSdk 36, `local.properties`, `keystore.properties`). Deliverable: empty shells build and deploy; APK release pipeline works. *(REQ-6, REQ-11)*

- [x] 5. Supabase project and schema
  Create all tables: `devices`, `settings`, `tables`, `orders` (active only), `menu_snapshot`, `branding`, `sessions`, `attendance`, `cafe_location`, `aggregates`, `payment_transactions`, `invites`. RLS on all tables (deny-by-default); Storage bucket `logos` (public read); Supabase Auth for superadmin; `ROTATING_KEY_SECRET` as Edge Function env secret. Scheduled purge (pg_cron) of COMPLETED/CANCELLED orders past `purge_after` — **set `purge_after` to minimum 24 hours** (not immediate) so that catch-up sync (`GET /api/orders?since=<ts>`) can still find recently-completed/cancelled orders for devices that reconnect after being offline. The outbound-WSS reconnect + catch-up pattern depends on these rows existing during the same service day. Seeds for settings. Deliverable: schema live with RLS, seeds, storage, auth, purge job (24h+ rolling buffer). *(REQ-5, REQ-6, REQ-10)*

### Phase 2 — Backend API (Edge Functions)

- [x] 6. Identity — rotating key, first-claim admin handshake, invitations, devices
  `GET /api/rotating-key` (superadmin) — HMAC-SHA256 derived 30s key. `POST /api/admin/handshake` — first-claim with ±1 window (60s grace), rejected with 409 if admin exists. `GET /api/invite` / `POST /api/invite/regenerate` (admin token). `POST /api/register` — ordering device with invite token validation, broadcasts JOIN_REQUEST. `GET /api/devices/status?deviceId=` — poll for approval + API key. `GET /api/devices` (superadmin) and `PATCH /api/devices/:id` — approve/reject/rename/revoke/force-sign-out. Tests: key expiry, replay, second-admin rejection, revoked-invite join attempt. Deliverable: both connect flows and full device lifecycle live. *(REQ-2, REQ-4, REQ-5, REQ-6)*

- [x] 7. Orders and table sessions
  `POST /api/orders` — validates table, rejects if occupied, re-prices server-side, rate-limits, broadcasts to `admin-orders`. `GET /api/tables/:tableId/session` — FREE/OCCUPIED/own order. `POST /api/orders/:id/kitchen` — marks unsent lines sent, returns delta for printing. `POST /api/orders/:id/items` — append amendment lines. `POST /api/orders/:id/payment` — CASH/QR, only after sent-to-kitchen, ends session. `DELETE /api/orders/:id` — cancel with reason/who. `PUT /api/orders/:id/status` — broadcasts to `order:<orderId>`. `GET /api/orders?since=<ts>` — catch-up sync; **must return orders in all terminal states (COMPLETED, CANCELLED) that ended after `<ts>`**, not just active orders — this is the safety net for the outbound-WSS reconnect pattern (devices that dropped the WebSocket still learn final outcomes via this endpoint). Tests: double-order, price tampering, cancel after kitchen (rejected), payment before kitchen (rejected), delta amendment, snapshot integrity, **catch-up after purge boundary** (verify no data loss). Deliverable: complete order/session lifecycle with all guards. *(REQ-1, REQ-3, REQ-8, REQ-10)*

- [x] 8. Menu, branding, settings, café location
  `GET/PUT /api/menu` — multilingual snapshot (4 categories, askMeDaily, availability); `{ configured: false }` until first push; 60s CDN cache. `GET/PUT /api/branding` — logo base64 → Storage, public URL; broadcast on `branding`. `GET/PUT /api/settings` — print language, timezone, top-N, report email, staff RBAC toggles; broadcast on `settings`. `PUT /api/cafe-location` (admin) / `GET /api/cafe-location` (ordering key) — GPS-lock coordinates + radius. Deliverable: all configuration surfaces read/write with Realtime propagation. *(REQ-3, REQ-9)*

- [x] 9. Sessions, attendance, aggregates, metrics, reports
  `POST /api/sessions` — OPEN/CLOSE; broadcasts CAFE_OPEN/CAFE_CLOSED on `cafe-status`. `POST /api/attendance` — CHECK_IN/CHECK_OUT with GPS + forced flag; broadcasts on `admin-attendance`. `POST /api/aggregates` — upsert daily summary. `GET /api/metrics?period=` — computed from aggregates + sessions in café timezone. `GET /api/reports/closing` and `GET /api/reports/monthly?month=` — HTML email via Brevo API to report_email. Tests: timezone boundaries, dangling session closure, forced check-out. Deliverable: metrics and emailed reports working from aggregates only. *(REQ-4, REQ-6, REQ-7, REQ-8)*

### Phase 3 — Walking Skeleton (integration checkpoint)

- [x] 10. End-to-end skeleton and latency gate
  Thinnest possible slice: hard-seeded menu → customer page cart → `POST /api/orders` → Realtime → bare admin APK screen logging the order + notification beep. Measure scan-to-notification latency on real phones over 4G. Gate: < 3s (REQ-8). Exercise Supabase pause/wake once (pause project, hit endpoint, measure wake). Deliverable: proven pipeline + measured numbers; architecture confirmed before feature build-out. *(REQ-8 Performance)*

### Phase 4 — Website Frontend

- [x] 11. Customer ordering page (session-aware)
  `/order?table=<id>`: on load call `GET /api/tables/:id/session` and branch — FREE → menu; OCCUPIED (other) → "Table occupied" screen; OCCUPIED (own browser ID) → status view. Generate + persist browser ID (localStorage UUID) **with cookie fallback** — also store the browser ID in a `SameSite=Lax; Secure` cookie so that iOS Safari ITP purges (7-day localStorage wipe) and in-app browsers (WeChat, WhatsApp, Facebook camera scanner) do not lock the customer out of their active session mid-meal. On load: read cookie first, fall back to localStorage, generate fresh only if both are empty. Branding header or placeholder until configured. i18next with `en` base + `bm/zh/ta/th` dictionary translations; five category tabs; item names via `name[lang]` falling back to `en`; unavailable items greyed out. Cart → submit → confirmation; subscribe `order:<orderId>` for live status (outbound WSS — no server on the customer phone); Cancel button while RECEIVED only; rescan shows status view. Mobile-first, WCAG 2.1 AA, < 2s load on 4G. Deliverable: full customer journey, resilient to browser storage purges. *(REQ-1, REQ-8, REQ-9)*

- [x] 12. Superadmin website
  Auth: register (email verification), login, forgot-password (Supabase Auth). Setup screen (pre-café): rotating key with 30s countdown. Dashboard: numbers-only metrics grid (orders / revenue / open hours × today, week, month, last month, 12 months) from `GET /api/metrics`; "admin phone offline" banner. Devices: list with label, role badge, actions (Rename, Force Sign Out, Deregister, Force Check-Out). Orders feed: live `admin-orders` subscription. Settings: Download APK link, Deregister Admin Phone, closing-report toggle, monthly report, Top-N input. Deliverable: complete superadmin surface per REQ-6. *(REQ-5, REQ-6)*

- [x] 13. Website QR sheet generator (optional convenience path)
  Table list → in-browser QR SVGs (`qrcode` npm) → print-ready A4 portrait CSS (`@page`) with 4 self-contained A6 cards per sheet. Users can choose exactly which table goes into which of the 4 containers (1, 2, 3, or 4 tables per sheet). Hairline cut guides,
    `window.print()`. Deliverable: browser-printable QR sheets matching the APK PDF layout. *(REQ-3 QR PDF — SHOULD-level alternative)*

### Phase 5 — Admin APK: Connection, POS Core, Menu

- [x] 14. Role selection and connection flows
  `RoleSelectScreen`: big Connect as Ordering Staff button, smaller Connect as Admin button. Admin path: webhook URL + rotating key → handshake → store session token in EncryptedSharedPreferences (REQ-12 Gap B research spike first) → AdminNavGraph. Handle 409 ADMIN_EXISTS. **EncryptedSharedPreferences KeyStore fallback**: wrap all read/write calls in `try-catch` handling `KeyStoreException` / `GeneralSecurityException` — on budget OEMs (Xiaomi MIUI, older Samsung Android 10–12) the Android Keystore key can get corrupted during OS updates or reboots. If decryption fails, clear the corrupted preferences gracefully and route the user back to the role-selection/re-auth screen instead of crash-looping. Ordering path: invitation URL entry with format validation → fingerprint register → PendingApprovalScreen (10s poll) → store permanent API key on approval. Build self-contained runtime-permission helper (denied-twice → open app settings fallback). Deliverable: both connect flows on-device; tokens/keys in encrypted storage with OEM-resilient fallback. *(REQ-2, REQ-4, REQ-5, REQ-12 Gap B)*

- [x] 15. Admin session lifecycle + Daily Availability popup
  On start with valid token: post OPEN; backend broadcasts CAFE_OPEN. Sign Out: CLOSE → stop services → lock screen; token kept. Sign Out with Closing: reason dialog → compute today's aggregate from Room → `POST /api/aggregates` → CLOSE → backend emails closing report and broadcasts CAFE_CLOSED → lock screen. Daily Availability popup: on first OPEN of the day, if any `askMeDaily` item exists — top-layer modal per item mark Available/Not available (+ optional price) → update Room → `PUT /api/menu`. Guarantee CLOSE delivery with blocking coroutine before service teardown. Deliverable: session tracking, both sign-outs, aggregate push, daily popup. *(REQ-3, REQ-7)*

- [x] 16. Menu management (type-first, four categories, no images)
  Room `MenuItem` (multilingual, askMeDaily, isAvailable) + `SystemSettings`. Room `Order`/`OrderItem` with snapshot fields (nameSnapshot, unitPriceSnapshot, categorySnapshot) and per-line `sentToKitchen` flag; single status enum. Type-first add flow: pick menu type → key in name + price in English (base language); other languages dictionary-translated with manual override field + "do not translate" toggle. Any change → `PUT /api/menu` full snapshot. Menu edits never touch existing order lines (line-item snapshot pattern). Deliverable: complete menu CRUD + snapshot-safe order schema. *(REQ-3, REQ-9)*

- [x] 17. Table View POS + order reception + catch-up sync
  `AdminForegroundService`: Realtime `admin-orders` subscription, persistent notification, on every (re)connect call `GET /api/orders?since=<lastSeen>` and reconcile. Table View dashboard (primary surface): grid of tables, Free/Occupied + status colour. Per-table sheet: order detail, Add items (amendment), Send to Kitchen (prints unsent lines only; delta "ADDED" slip on re-send), status updates, Cancel (records who + reason), Payment (Cash/QR) enabled only after sent-to-kitchen → COMPLETED + session end. Auto-send-to-kitchen toggle (SHOULD); live order queue; status-bar notification per new order. Table registry management (add/rename/delete). Deliverable: the POS — every order lands on Table View and is driven to paid/cancelled. *(REQ-3, REQ-10)*

- [x] 18. Manual dine-in entry + device approvals + staff settings
  Manual dine-in: table select → menu → submit (source: STAFF). `admin-devices` subscription: join-request alert with Approve/Reject; Devices screen (pending + connected, revoke, Force Check-Out, attendance history). Settings: Staff Invitation (show/share URL, Regenerate), Staff Permissions (SEND_TO_KITCHEN/TAKE_PAYMENT toggles, optional manager-override), Café Location GPS lock (capture fix + radius → `PUT /api/cafe-location`), Café Profile (name + logo ≤ 200 KB upload → branding push). Logo pipeline: image picker → square crop → compress-to-target loop → ≤ 200 KB JPEG → **then downscale to max 384px width and convert to 1-bit monochrome (Floyd–Steinberg dither or threshold) for the print-ready bitmap** — store both the full JPEG (for website/branding display) and the mono raster (for ESC/POS receipt header). This prevents low-end thermal printer buffer overflows and Bluetooth SPP stalls caused by oversized bitmaps. Base64 of the full JPEG for `PUT /api/branding`. Deliverable: full admin management surface with print-safe logo pipeline. *(REQ-3, REQ-4)*

### Phase 6 — Ordering-Role APK

- [x] 19. GPS attendance and café state machine
  `OrderingForegroundService` (persistent notification, FOREGROUND_SERVICE_TYPE_LOCATION), battery-optimization exemption prompt, BOOT_COMPLETED receiver. Subscribe `cafe-status`; state machine: CafeClosedScreen ↔ CheckInScreen ↔ OrderingScreen. On reconnect, re-fetch café status + settings + table states (catch-up). Check-in/out: fresh GPS fix → distance vs `GET /api/cafe-location` radius → `POST /api/attendance`; friendly rejection outside radius; handle admin-forced check-out events. Deliverable: staff attendance lifecycle, café open/closed compliance. *(REQ-4)*

- [x] 20. Staff order entry + staff Table View (RBAC)
  After check-in: Table View (same realtime data as admin) + order entry (table → menu → cart → submit with API key). Unavailable/ask-me-daily-off items blocked. RBAC from settings: Cancel always; Send to Kitchen / Payment hidden unless enabled. Offline queue: failed POSTs → Room `PendingOrder` → WorkManager retry; offline banner (SHOULD). Deliverable: staff can run the floor within admin-set permissions. *(REQ-3, REQ-4)*

### Phase 7 — Printing and QR PDF

- [x] 21. Printer registry and dispatcher
  Room `PrinterConfig` (name, MAC, 58/80mm, RECEIPT_ONLY/KITCHEN_ONLY/BOTH, active); Printers screen: BT scan → add → edit → remove → test print; Android 12+ permission flow (BLUETOOTH_SCAN/CONNECT). `PrinterDispatcher`: role match → fallback to BOTH → else queue + "No printer configured" alert. Per-printer `PrintJob` queue with reconnect retry. Reserve nullable `categoryFilter` on PrinterConfig for future per-category routing. Char/image widths resolved per printer (58mm: 32ch/384px; 80mm: 48ch/576px). Deliverable: multi-printer setup per REQ-3, informed by Spike (Task 2). *(REQ-3 Printing)*

- [x] 22. Kitchen slip and customer receipt documents
  `KitchenSlipDocument`: big table number, items + qty, notes, timestamp — only lines with `sentToKitchen=false`. Delta slip headed "TAMBAHAN / ADDED — Table N" on re-send. `ReceiptDocument`: branding header (**use the pre-rendered 1-bit monochrome logo bitmap from Task 18's pipeline**, max 384px wide for 58mm / 576px for 80mm — never decode the full JPEG at print time), all consolidated lines at snapshotted prices, totals, payment method (Cash/QR), thank-you — printed on Payment or manual re-print. All labels via active print language (EN default / BM). Re-print any past slip/receipt from order detail (SHOULD). Deliverable: correct documents to the correct printer at the correct lifecycle moment, with print-safe image sizing. *(REQ-3, REQ-9, REQ-10)*

- [x] 23. QR PDF generator (A6 cards, 4-up A4 portrait)
  ZXing + `PdfDocument`; A4 portrait, 2×2 grid of A6 portrait cards; per card: logo (≤ 40×40mm, optional), café name, QR (60×60mm, EC-H), table label, hairline cut guides; blank trailing cells; table subset selection. File save via MediaStore (API 29+) + FileProvider fallback; share via ACTION_SEND + chooser. QR URL = `https://<project>.pages.dev/order?table=<slug>`. Deliverable: print-shop-ready multi-page PDF. *(REQ-3 QR PDF)*

### Phase 8 — Language (English base + dictionary translation)

- [x] 24. Dictionary-translation i18n and print-language propagation
  Research spike (Gap A): decide the dictionary mechanism — static string dictionaries (English source → BM/中文/தமிழ்/ไทย), curated café food-term dictionary, English-fallback rule, "do not translate" flag, admin manual-override store, where dictionaries live (bundled vs. backend table). Zero paid translation APIs. APK resources: `res/values/strings.xml` = English (base); `res/values-ms/strings.xml` = Malay (generated from English, overridable). Set `generateLocaleConfig = true`. Website: i18next `en` base + `bm/zh/ta/th` dictionary bundles with English fallback. Propagation: language setting → `PUT /api/settings` → `settings` Realtime → staff APKs re-locale on next sync/restart. Deliverable: English-default system; one toggle switches operational output to Malay; customer site offers all five languages. *(REQ-9, REQ-12 Gap A)*

### Phase 9 — Reports, Backup

- [x] 25. On-device reports (daily/weekly)
  Room queries: totals, revenue, avg order value, per-table, top-N per four categories, cash-vs-QR split, cancelled count/value by who cancelled; date-range screen; PDF (`PdfDocument`) + CSV export + share. Same content checklist as the closing report (Task 9). Deliverable: admin analyses sales on the phone, in the print language. *(REQ-3 Reports)*

- [x] 26. Full database export / import
  JSON envelope v2: `{ version, exportedAt, menuItems, orders, orderItems, paymentTransactions, devices, printers, settings, attendance, cafeLocation, aggregatesCache }`. Export to Downloads/Drive share; import with version check + preview + confirm. Use same file saver + cache-then-share helper as Task 23. `CreateDocument` picker for export, `OpenDocument` picker for re-import. Weekly backup reminder notification. Deliverable: complete restore path onto a replacement phone. *(REQ-3 Database)*

### Phase 10 — Hardening and Field Test

- [x] 27. Chaos and reconnect testing + Cloudflare WAF/rate limiting
  **Chaos & reconnect tests**: kill WebSocket mid-order (catch-up must recover), airplane-mode staff order (queue must flush), backend down during payment (must not double-pay), Supabase wake-from-pause during service. **Pre-flight test matrix** (each must pass before field rehearsal):

  | Operational Zone | Risk Area | Pre-Flight Test |
  |---|---|---|
  | Android APK | Background Killers | Turn off screen, leave phone idle for 45 min, send test order via Supabase dashboard. Does it beep within 3 seconds? |
  | Printers | Concurrency Lock | Trigger 3 consecutive manual receipts simultaneously while a kitchen slip is printing. Does the queue resolve cleanly? |
  | Network | Flight Mode / Off-Grid | Turn on Airplane mode on the staff phone, place 2 orders, re-enable network. Does the catch-up sync (`/api/orders?since=`) reconcile without duplicates? |
  | Edge Functions | Cold Start Latency | Leave system idle for 2 hours. Submit an order from 4G cellular. Measure exact elapsed time to APK broadcast. |

  **Cloudflare WAF & Rate Limiting** (free-tier protection layer):
  - Enable Cloudflare WAF on the Pages domain (`*.pages.dev`) — blocks common attack vectors (SQL injection, XSS, malformed payloads) and filters suspicious IP ranges.
  - Configure rate limiting rules on `/api/*` routes: cap at 10 requests/second per IP to prevent bot floods or runaway client refresh loops from draining Supabase Edge Function quotas (500k/month free).
  - Bot management: identify automated crawlers and throttle before they reach Supabase.
  - Start with **monitor mode** to observe traffic patterns before enforcing hard blocks.
  - Document thresholds (max requests per IP per minute) so they can be tuned as usage grows.
  - Impact: customer PWA loads via CDN cache (unaffected); authenticated admin/staff traffic passes through; Supabase free tier protected from accidental or malicious request floods.
  - Implementation: Cloudflare dashboard → Security → WAF → create rules; or via `wrangler` CLI if using Workers routing.

  Deliverable: documented recovery behaviour for each failure + all pre-flight tests green + WAF rules active in monitor mode with documented thresholds. *(REQ-8 Reliability, REQ-8 Cost)*

- [x] 28. OEM keep-alive matrix
  Apply Spike-3 checklist: Xiaomi AutoStart, Samsung sleeping-apps whitelist, generic battery-exemption deep links where detectable; in-app setup guide screens. Deliverable: both service types survive a full day on target devices. *(REQ-4 Background Survival)*

- [x] 29. Keep-alive ping + ops runbook
  GitHub Actions scheduled workflow (daily cron) hitting **both `GET /api/menu` and at least one Edge Function endpoint** (e.g., `GET /api/settings`) — prevents the 7-day Supabase free-tier pause AND keeps Edge Functions warm to avoid cold-start latency spikes on the first real order of the day. Document in README: holidays > 7 days covered by cron; manual wake procedure; Brevo sender verification steps; APK release process. Deliverable: the stall never finds a paused backend or cold Edge Functions on Monday morning. *(REQ-8 Cost)*

- [x] 30. Full-day field rehearsal (acceptance)
  One real service day: print QR cards, staff check-in, mixed QR/staff orders, kitchen + receipt printing, payments cash and QR, a cancellation, a rescan status check, Sign Out with Closing → email received, dashboard numbers verified against the paper till. Deliverable: signed-off MVP; punch list becomes the post-MVP backlog. *(all REQs — acceptance)*

## Task Dependency Graph

```json
{
  "waves": [
    { "wave": 0, "tasks": [1, 2, 3], "description": "Contract + the two killer-risk spikes. 2 and 3 run in parallel with 1." },
    { "wave": 1, "tasks": [4, 5], "description": "Repo/CI/hosting and Supabase schema, in parallel, once the contract is agreed." },
    { "wave": 2, "tasks": [6, 7, 8, 9], "description": "All backend APIs in parallel against the schema." },
    { "wave": 3, "tasks": [10], "description": "Walking skeleton — integration + latency gate. Nothing proceeds until it passes." },
    { "wave": 4, "tasks": [11, 12, 13, 14, 15, 16], "description": "Website (11–13) and admin APK core (14–16) in parallel." },
    { "wave": 5, "tasks": [17, 18], "description": "Table View POS and admin management — the money path complete for the admin." },
    { "wave": 6, "tasks": [19, 20, 21], "description": "Staff APK (19–20) and printer registry (21, after Spike 2) in parallel." },
    { "wave": 7, "tasks": [22, 23, 24], "description": "Print documents, QR PDF, language propagation." },
    { "wave": 8, "tasks": [25, 26], "description": "Reports and backup." },
    { "wave": 9, "tasks": [27, 28, 29, 30], "description": "Hardening, keep-alive ops, and the field-day acceptance test." }
  ]
}
```

**MVP cut line**: tasks 1–23 + 29 are required for opening day. Tasks 24–28 can trail by days but Task 30 (field rehearsal) gates real service.

## Notes

- **Never rename the Cloudflare Pages project** after QR cards are printed — the `*.pages.dev` URL is baked into physical cards.
- **Brevo sender verification**: verify the superadmin's own email address as sender (no domain needed).
- **Supabase 2-active-project limit**: use one project; a second free project can serve as staging only temporarily.
- **Realtime message budget** (2M/month free): each order generates a handful of broadcasts across ≤ 70 subscribers (30 tables × 2 + 10 devices) — well under budget at stall scale (~2.5% monthly utilisation).
- **Printing is the schedule risk**, not the backend: Task 2's spike verdict decides whether Task 21 is a formality or needs a library change. Do not defer the spike.
- **`ANDROID_ID` resets on factory reset** — a reset staff phone simply re-joins via the invitation; the admin deregisters the orphaned record.
- **GPS spoofing** can defeat attendance (mock-location apps). Accepted risk at stall scale.
- **Architecture: outbound-WSS only, no inbound HTTP server on phone.** The APK acts as a real-time event consumer via Supabase Realtime broadcast channels (not `postgres_changes`). This avoids IP flapping, NAT/firewall issues, and battery drain from hosting a local HTTP server. Broadcast channels give full payload control and bypass WAL replication lag. The catch-up sync endpoint (`GET /api/orders?since=<ts>`) closes the gap for any WebSocket drops — together they guarantee zero lost orders.
- **Edge Function cold starts are the latency risk** — Task 3 spike must measure cold-start + warm response separately. If cold starts break the 3s NFR, fall back to direct PostgREST inserts with RLS + a database trigger for Realtime broadcast, or add a warmup ping to the cron job (Task 29).
- **iOS Safari ITP** purges localStorage after 7 days of inactivity (or immediately in some in-app browsers). The browser ID cookie fallback (Task 11) ensures customers are never locked out of their active table session mid-meal.
- **EncryptedSharedPreferences OEM bugs** — Xiaomi MIUI and older Samsung devices can corrupt the Android Keystore during OS updates. Task 14's fallback ensures the app degrades to re-auth instead of crash-looping.
- **Timezone discipline**: every "today" — metrics, aggregates, the daily availability popup, closing reports — uses `settings.timezone` (default `Asia/Kuala_Lumpur`). UTC bugs corrupt reports silently; Task 9's boundary tests are mandatory.
- **Order model design principles**: line-item snapshotting, delta kitchen slips (per-line `sentToKitchen` flag), append-only `PaymentTransaction` table, single `Order.status` enum, named RBAC permission catalog with optional manager-override.
- **Two open design items (REQ-12)**: Gap A — Dictionary-translation i18n (English-default, spike in Task 24). Gap B — Encrypted token storage (EncryptedSharedPreferences/Keystore, spike in Task 14).
- **Free-tier stack verified July 2026**: Supabase, Cloudflare Pages, Brevo, GitHub. Explicitly rejected: Vercel Hobby, UptimeRobot free, Resend free, Firebase FCM, any paid domain.
- **Cloudflare WAF/rate limiting** (future hardening, Task 27): Cloudflare's free WAF rules protect `/api/*` routes from bot traffic and abuse that could drain Supabase's 500k/month Edge Function invocation quota. Rate limiting (10 req/s per IP) prevents runaway client refresh loops. Start in monitor mode; enforce once traffic patterns are baselined. This is lightweight to enable and extends free-tier quota longevity significantly.
- **Build environment**: JDK 17 (Temurin), Gradle 8.9, AGP 8.7.x, Kotlin 2.0.x, KSP matching, compileSdk/targetSdk 36, minSdk 26. `local.properties` and `keystore.properties` are git-ignored per-project files.
