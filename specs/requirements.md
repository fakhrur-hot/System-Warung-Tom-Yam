# System Warung Tom Yam — Requirements Document

## Introduction

This document defines the full requirements for **System Warung Tom Yam**, a small, functional, zero-commitment café/stall POS built on a QR-based table ordering model — bare-minimum hardware (at least two Android phones and one Bluetooth thermal printer) running entirely on free-tier web services. The system consists of three components that work together:

1. **A proxy website** — lightweight, free-hosted, acting as the system superadmin. It hosts the customer-facing ordering page (reached via table QR codes), manages device registration and role assignment, routes orders via webhook, and serves as the public API gateway.
2. **A single Android APK** — installed on any Android phone. On first launch the app shows two connect options: a primary **"Connect as Ordering Staff"** button and a secondary **"Connect as Admin"** button. Whoever holds superadmin website access (and can therefore see the webhook URL and the rotating API key) claims the Admin role with a one-time handshake; staff devices connect using an ordering-role invitation URL issued from the admin APK. The APK supports both roles from a single binary and switches UI based on the stored role.
3. **Table QR codes** — printable PDFs generated from the website or admin APK, laid out as A6 portrait cards tiled 4 per A4 portrait sheet (2×2 grid), each card uniquely encoding the table URL.

---

## Requirements

### REQ-1: Customer Ordering via QR Code

- MUST: Every table has a unique QR code that encodes a URL in the format `https://<site>/order?table=<tableId>`.
- MUST: Scanning the QR code opens the customer ordering page in a mobile browser with the correct table context pre-loaded.
- MUST: The ordering page displays the current menu fetched from the backend.
- MUST: Customers can add items to a cart, specify quantities, and submit an order.
- MUST: On submission, the order (table ID, items, quantities, timestamp) is sent to the backend.
- MUST: On first order, the ordering page generates an anonymous **browser ID** (UUID stored in localStorage) and submits it with the order. The browser ID locks the active order to that customer's phone.
- MUST: There is no separate status page. When the customer **rescans the same table QR** during an active table session, the page shows their placed order and its live status (`Received`, `Preparing`, `Ready`) instead of the menu, including a **Cancel** button (available only until the order is sent to the kitchen).
- MUST: Any *other* phone scanning that table's QR while a session is active sees a **"Table occupied"** screen — it cannot see the order details or place a new order for that table.
- MUST: The table session ends only when the admin (or permitted staff) cancels the order or completes **payment** from the Table View; after that, rescanning shows the fresh menu again.
- SHOULD: An order confirmation screen is shown to the customer after successful submission.

### REQ-2: Role System — Single APK, Multiple Roles

- MUST: A single APK binary supports both the Admin role and the Ordering role.
- MUST: On fresh install the app shows a role-selection screen with two options: a primary **Connect as Ordering Staff** button and a secondary **Connect as Admin** button. The device has no role until a connection succeeds.
- MUST: **Connect as Admin** requires the backend webhook URL plus the current rotating API key — both visible only on the superadmin website. Admin is **first-claim and one-time**: once an admin device exists, the backend rejects further admin handshakes until that device is deregistered from the website.
- MUST: **Connect as Ordering Staff** accepts only an invitation URL in the ordering-role format (issued from the admin APK Settings → Staff Invitation). Admin-format URLs are rejected by this flow.
- MUST: The active role is stored securely on-device and controls which screens and features are accessible. There is no role promotion or switching — changing a device's role requires deregistering and reconnecting through the other flow.
- MUST: The Admin role has full access to all features described in REQ-3.
- MUST: The Ordering role has access only to features described in REQ-4.

### REQ-3: Admin Role Features (Café Owner's Phone)

#### Menu Management
- MUST: Admin can create, edit, and delete menu items (name, description, price, category). Menu items carry no images — the customer page renders a text menu.
- MUST: Menu items are organized into exactly four fixed categories (menu types): **Food**, **Beverages**, **Side Dishes**, and **Others**. No custom categories can be created.
- MUST: Menu registration is **type-first**: the admin chooses the menu type, then keys in each item's details and price.
- MUST: An item whose availability is uncertain day-to-day (e.g., market fish such as ikan kembung or ikan siakap, where supply, size, or raw materials vary) can be flagged **"Ask me daily"** at registration or edit time.
- MUST: On the admin's first sign-in of each day (café opening), a **Daily Availability popup** appears as a top-layer modal with a slightly darkened background, listing every "Ask me daily" item. The admin marks each item Available or Not available today (and may optionally adjust today's price) before dismissing the popup.
- MUST: Items marked Not available today are shown as unavailable on the customer ordering page and staff order entry until the next daily confirmation.
- MUST: Daily availability results sync to the backend immediately with the rest of the menu.
- MUST: Menu changes sync to the website backend immediately so the customer ordering page reflects them in real time.

#### Order Reception and Management
- MUST: Admin phone receives all incoming orders from both customers (via QR) and ordering-role staff via a real-time connection.
- MUST: The admin dashboard's primary surface is a **Table View** (café POS grid) showing every table with its session state (Free / Occupied) and active order status. A live order queue with table number, items, and timestamps is also available.
- MUST: From the Table View the admin can, per table: view the order, **Send to Kitchen** (prints the kitchen slip), update status (`Received`, `Preparing`, `Ready`), **Cancel** the order, and take **Payment**.
- MUST: The **Payment** button offers two methods — **Cash** or **QR** — and is enabled only after the order has been sent to the kitchen. Completing payment marks the order `Completed` and ends the table session.
- MUST: Cancelling (by admin, by permitted staff, or by the customer before Send to Kitchen) marks the order `Cancelled` and ends the table session.
- MUST: Staff permissions are controlled by admin RBAC settings: by default ordering-role devices can **cancel orders only**; Send to Kitchen and Payment are disabled for staff unless the admin enables them in Settings → Staff Permissions.

#### Order Line-Item Integrity and Kitchen Amendments
- MUST: Each order line **snapshots** the item's name, unit price, and category at the moment it is added to the order. Subsequent menu edits (price change, rename, marking unavailable) MUST NOT alter existing orders or historical reports. Reports and receipts read the snapshot, never the live menu.
- MUST: The order total is the sum of snapshotted line totals; it is recomputed by the backend on submission from the current menu snapshot (client-submitted totals are ignored).
- MUST: Each order line carries a **`sentToKitchen`** flag. When Send to Kitchen is triggered, only lines not yet sent are printed, and those lines are then marked sent.
- MUST: If items are added to a table that has already been sent to the kitchen (staff-assisted amendment), Send to Kitchen prints a **delta kitchen slip** containing only the newly added lines, headed clearly as an addition (e.g., "TAMBAHAN / ADDED — Table 5"). Already-printed lines are never re-sent automatically.
- MUST: The order lifecycle status is a single enum with exactly these values: `RECEIVED`, `SENT_TO_KITCHEN`, `PREPARING`, `READY`, `COMPLETED`, `CANCELLED`. A single status enum is used deliberately, rather than several independent boolean flags, to avoid inconsistent states.
- MUST: A `CANCELLED` order records a cancel reason and who cancelled it (admin, staff device label, or customer); a `COMPLETED` order records the payment method. Both counts appear in reports.

#### App Background Survival (Admin Phone)
- MUST: The admin APK runs a foreground service while a café session is open (persistent notification "Café Open — receiving orders") so the Realtime WebSocket survives backgrounding and screen-off.
- MUST: The admin APK requests battery optimization exemption on first launch and registers a `BOOT_COMPLETED` receiver to restart after reboot.
- MUST: **Catch-up sync** — whenever the Realtime connection (re)establishes, the admin APK calls `GET /api/orders?since=<lastSeen>` to fetch active orders and events missed while disconnected. No order may be lost to a dropped WebSocket.

#### Printing — Bluetooth Thermal Printers (Multiple Supported)
- MUST: Admin APK supports registering **multiple Bluetooth thermal printers**, each saved with a name, MAC address, paper width (58mm or 80mm), and assigned print role.
- MUST: Each printer is assigned one of three print roles:
  - **Receipt Only** — prints customer receipts only.
  - **Kitchen Only** — prints kitchen order slips only.
  - **Both** — prints both receipts and kitchen slips (single-printer setup).
- MUST: When a print job is triggered, the APK dispatches it to the printer assigned to the corresponding role. If a printer role has no assigned device, the job falls back to any printer configured as **Both**.
- SHOULD: The print role is designed as the simple case of a more general **virtual-printer routing** model. A future upgrade MAY add an optional **category filter** to a printer (e.g., route only `Beverages` kitchen slips to a bar printer, `Food`/`Side Dishes` to the kitchen printer) without changing the role model — a printer with no category filter handles all categories, preserving current behaviour by default.
- MUST: Paper width (58mm or 80mm) is configured per printer. The character width and image width constants used for ESC/POS formatting are derived from the individual printer's paper width setting, not a global setting.
- MUST: Supports both 58 mm and 80 mm paper-width printers simultaneously (e.g., 58mm for kitchen, 80mm for receipts).
- MUST: Admin can add, rename, remove, and re-assign printers at any time from the Printers settings screen.
- SHOULD: An optional **auto-send-to-kitchen** toggle: when enabled, new orders are sent to the kitchen (slip printed to the Kitchen or Both printer) immediately on arrival without a manual Send to Kitchen tap.
- SHOULD: Supports re-printing of any previous receipt or kitchen slip to the appropriate assigned printer.

#### QR Code PDF Generation
- MUST: Admin APK generates a printable PDF where each table QR code is laid out as an **A6 portrait card** (105 × 148 mm). Cards are tiled onto A4 portrait sheets (210 × 297 mm) in a 2-column × 2-row grid, yielding exactly **4 cards per A4 sheet** with near-zero paper waste (0 mm horizontal, 1 mm vertical remainder). The user can choose exactly which table goes into which of the 4 containers (e.g., placing 1, 2, 3, or 4 specific tables on a single A4 sheet).
- MUST: Each A6 card is self-contained and includes, within its own boundary: the café name, the café logo (if configured), the QR code, and the table number label. There is no shared page header or footer — every card is a complete standalone piece after cutting.
- MUST: Each QR code is unique per table, encoding that table's ordering URL.
- MUST: A hairline border (cut guide) is printed on every card boundary so the print shop knows exactly where to cut.
- MUST: The PDF is saved to the device and shareable via standard Android share intent (email, WhatsApp, Google Drive, etc.) for sending to a print shop.
- MUST: If the number of tables is not a multiple of 4, the remaining cells on the last page are left blank (no filler content).
- SHOULD: The admin can select which tables to include in the PDF (all tables, or a custom subset).

#### Café Branding — Upload from Admin APK
- MUST: Admin can set the café name and upload a café logo from within the Admin APK (Settings → Café Profile).
- MUST: On save, the café name and logo are pushed to the backend (`PUT /api/branding`) so the website customer ordering page uses them immediately.
- MUST: The website is a **skeleton application on first deploy** — it has no café name, no logo, and no menu until the admin APK connects and pushes this data for the first time.
- MUST: Until branding data is pushed, the customer ordering page displays a generic placeholder (e.g., "Café" and a default icon). The page is still accessible but shows no real menu items.
- MUST: Logo is stored on the backend (Supabase Storage or as a base64-encoded field) so it is served to customers without requiring the admin phone to be online.
- SHOULD: Admin can preview how the café name and logo appear on the website ordering page and on the QR PDF cards before saving.

#### Device Management
- MUST: Admin phone receives a real-time alert (via Supabase Realtime `admin-devices` channel) when a new ordering-role device requests to connect, showing the device model name.
- MUST: Admin can approve or reject the join request directly from the alert or from the Devices screen.
- MUST: Admin can view all approved ordering devices, their check-in status, and last-seen timestamps.
- MUST: Admin can revoke any device's access (deregister) from within the app.
- MUST: Admin APK collects and stores all ordering-role staff **attendance records** (check-in and check-out events with GPS coordinates and timestamps) received from the backend in the local Room database.
- MUST: Admin can view attendance history per ordering device from the Devices screen (date, check-in time, check-out time, duration).

#### Café Location (GPS Lock)
- MUST: Settings → Café Location lets the admin register the café's coordinates via **GPS lock** — the APK captures the admin phone's current GPS fix (accuracy shown) while standing at the café. No manual coordinate entry is needed.
- MUST: On save, the location `{ latitude, longitude, radiusMeters }` is pushed to the backend (`PUT /api/cafe-location`) and stored in the website database — the authoritative copy used to validate ordering-staff daily attendance (GPS check-in and check-out).
- MUST: The check-in radius (default 100m) is configurable on the same screen.
- SHOULD: The admin can re-lock (update) the location at any time, e.g. after relocating the stall.

#### Staff Invitation and Permissions
- MUST: The admin APK Settings shows a **Staff Invitation** — an ordering-role invitation URL (with embedded invite token) that staff enter via the Connect as Ordering Staff button. The admin can regenerate the invitation at any time, immediately invalidating the previous URL.
- MUST: Staff permissions use a **named permission catalog**: `CREATE_ORDER`, `CANCEL_ORDER`, `SEND_TO_KITCHEN`, `TAKE_PAYMENT`. Ordering-role devices are granted `CREATE_ORDER` + `CANCEL_ORDER` by default; `SEND_TO_KITCHEN` and `TAKE_PAYMENT` are off by default and toggled per system in Settings → Staff Permissions (they apply to all ordering devices, not per-device).
- SHOULD: **Manager-override pattern** — instead of granting `SEND_TO_KITCHEN`/`TAKE_PAYMENT` globally, the admin MAY leave them off and approve an individual action on request: when a staff device attempts a disallowed action, a one-time approval prompt is pushed to the admin phone (via `admin-devices`/a dedicated channel); the admin approves and the single action proceeds without permanently changing the toggle.

#### Database and Reporting
- MUST: All business data (orders, menu, devices, transactions) is stored in a local SQLite database on the admin phone.
- MUST: Admin can export the full database (JSON or CSV format) to device storage or cloud (Google Drive / Dropbox).
- MUST: Admin can import a previously exported database to restore data.
- MUST: Admin can generate daily and weekly reports (total orders, total revenue, item popularity, average order value, cash-vs-QR tender split, cancelled-order count and value).
- SHOULD: Reports are exportable as PDF or CSV.

#### Dine-In Ordering (Staff-Assisted)
- MUST: Admin can manually place dine-in orders directly on the admin phone (for walk-up or phone orders not originating from a QR scan).
- MUST: Manually placed orders are treated identically to QR-submitted orders for printing and reporting purposes.

### REQ-4: Ordering Role Features (Staff Phones)

#### Registration and Connection
- MUST: To connect a new ordering-role phone, the staff member taps **Connect as Ordering Staff** and enters the **invitation URL** shown in the admin APK's Settings → Staff Invitation. No rotating key is required for ordering-role devices; the flow rejects URLs that are not in the ordering-role invitation format.
- MUST: On connection attempt, the APK automatically collects and sends the device's fingerprint: `{ deviceModel: Build.MODEL, androidId: Settings.Secure.ANDROID_ID, appVersion }` along with the webhook URL to the backend.
- MUST: The backend records the device fingerprint, creates a pending device record, and immediately broadcasts a join-request notification to the admin APK via the `admin-devices` Realtime channel: `"[Samsung Galaxy A23] is requesting to join as Ordering role."`.
- MUST: The admin APK displays a real-time alert with the device name and model. The admin taps **Approve** or **Reject**.
- MUST: On approval, the backend issues a permanent API key to the ordering device. This registration is **one-time and permanent** — the device never needs to re-register or re-enter the webhook URL unless it is manually deregistered.
- MUST: The ordering-role device uses its permanent API key for all subsequent communication. The key survives admin sign-out, café closing, and app restarts.

#### Check-In (GPS-Verified Attendance)
- MUST: After the app starts and the device is approved, staff must **Check In** before they can access the ordering screen.
- MUST: Check-in requires the device's GPS location to be within a configurable radius (default 100m) of the café's registered GPS coordinates. If the device is outside the radius, check-in is rejected with a message: "You must be at the café to check in."
- MUST: The café GPS coordinates are registered by the admin via GPS lock (Settings → Café Location, see REQ-3) and stored in the website database; ordering devices fetch them from the backend for every attendance validation.
- MUST: On successful check-in: the APK posts `{ event: "CHECK_IN", deviceId, timestamp, latitude, longitude }` to the backend `attendance` table.
- MUST: The admin APK receives a real-time notification when any ordering-role staff checks in, showing their device label and check-in time.
- MUST: After a successful check-in, the ordering role dashboard becomes accessible: a real-time **Table View** (same table/session states as the admin's) plus order entry (table selection + menu).

#### Check-Out (GPS-Verified Attendance)
- MUST: Staff must **Check Out** at the end of their shift.
- MUST: Check-out also requires GPS to confirm the staff member is within the configured radius of the café at the time of check-out.
- MUST: On check-out: the APK posts `{ event: "CHECK_OUT", deviceId, timestamp, latitude, longitude }` to the backend `attendance` table.
- MUST: The admin APK receives a real-time notification when any ordering-role staff checks out.
- MUST: After check-out, the ordering screen is hidden and only a **Check In** button is shown until the staff member checks in again.
- MUST: Admin override — the admin can **Force Check-Out** a staff device from the Devices screen (no GPS required); the attendance record is stored with a `forced` flag. This covers staff who left the café without checking out (GPS would otherwise block them from ever checking out).

#### Café Closed State (Admin Signed Out with Closing)
- MUST: When the admin performs **Sign Out with Closing**, the backend broadcasts a `{ event: "CAFE_CLOSED" }` event to all connected ordering-role devices via the `cafe-status` Realtime channel.
- MUST: On receiving `CAFE_CLOSED`, all ordering-role APKs immediately hide the ordering screen and display a **"Café is closed — Check Out"** screen with only a Check Out button visible.
- MUST: In the café-closed state, ordering-role staff cannot see the menu or submit orders.
- MUST: Staff must still check out via GPS before the app returns to the idle (not checked in) state.
- MUST: When the admin signs in again (APK starts with valid session token), the backend broadcasts `{ event: "CAFE_OPEN" }` to all ordering-role devices, restoring access to the Check In screen.

#### Order Entry (During Active Shift)
- MUST: Ordering role displays a staff-facing order entry screen (table selection, menu browsing, item selection) — only accessible after a successful GPS check-in during an open café session.
- MUST: Submitted orders are sent to the backend which forwards them to the admin phone.
- MUST: Ordering role phones cannot access menu management, database, device management, or reports. Within the Table View, staff may cancel orders by default; Send to Kitchen and Payment are hidden unless enabled by the admin (see REQ-3 Staff Invitation and Permissions).
- MUST: If an ordering-role device loses connectivity, pending orders are cached locally and retried when the connection is restored.
- MUST: On reconnect, the ordering APK re-fetches the current café status, settings, and table/session states (catch-up sync) rather than relying on missed Realtime broadcasts.
- SHOULD: The app shows a visual indicator (banner or badge) when operating in offline/cached mode.

#### App Background Survival
- MUST: The ordering-role APK runs a persistent foreground service (`OrderingForegroundService`) at all times once approved, showing a permanent status bar notification ("Café Staff — Active" or "Café Staff — Checked Out").
- MUST: The app requests Android battery optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) on first launch.
- MUST: The app registers a `BOOT_COMPLETED` broadcast receiver so it auto-restarts after device reboot.
- MUST: The foreground service uses `ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION` to retain GPS access in the background.
- SHOULD: The app implements manufacturer-specific keep-alive workarounds (Xiaomi AutoStart, Samsung Adaptive Battery whitelist prompt) where detectable.

### REQ-5: Device Registration, Connectivity, and Rotating API Key

#### Initial Admin Phone Handshake — Rotating 30-Second Key
- MUST: The superadmin website displays a **time-limited, rotating API key** (30-second expiry) on the Connection page, visible only to a logged-in superadmin.
- MUST: The key auto-rotates every 30 seconds. A countdown timer is shown alongside the key so the admin knows how long it remains valid.
- MUST: The admin taps **Connect as Admin** on the APK's first-launch screen and manually enters the backend webhook URL and the currently displayed rotating key.
- MUST: The backend validates the entered key against the current valid window. If valid, the admin APK is issued a durable **long-lived session token** (separate from the rotating key) stored in `EncryptedSharedPreferences`. This token is used for all subsequent communication.
- MUST: After the initial handshake, the rotating key is no longer required. The admin APK uses its long-lived token for all API calls.
- MUST: Admin is **first-claim**: while an admin device is registered, the backend rejects any further admin handshake attempts. Deregistering the admin phone (website Settings) re-opens the claim window with a fresh rotating key.
- MUST: If the admin phone is lost or replaced, the superadmin logs into the website, generates a new rotating key session, and completes the handshake on the replacement phone. The old token is automatically invalidated when a new one is issued for the admin role.
- MUST: The rotating key is never stored in the backend logs or exposed in any API response body — it is only shown in the superadmin dashboard UI and transmitted once during the handshake.

#### Website Liveness — Dependent on Admin APK Data
- MUST: The customer ordering page is **only functional after the admin APK has connected and pushed** café branding and at least one menu item. Before this happens, the website renders a placeholder state ("Coming soon" or equivalent) and accepts no orders.
- MUST: The superadmin dashboard is always accessible (independent of the admin APK) for device management and key display.
- MUST: If the admin APK disconnects (e.g., phone lost), existing menu and branding data remain on the backend and the customer ordering page continues to function — customers can still browse the menu and submit orders. Orders accumulate on the backend.
- SHOULD: The superadmin dashboard shows a "Admin phone offline" warning when no admin APK has been connected for more than 30 minutes.

#### Ongoing Device Registration — Ordering Devices
- MUST: Ordering devices connect by entering the café's ordering-role **invitation URL** (issued and regenerable from the admin APK) in the APK. No rotating key is required; the backend validates the embedded invite token.
- MUST: The APK automatically sends device fingerprint data (`deviceModel`, `androidId`, `appVersion`) to the backend on first connection.
- MUST: The backend notifies the admin APK in real time; the admin approves or rejects from the alert.
- MUST: On approval, the backend issues a permanent API key to the ordering device tied to that specific device fingerprint.
- MUST: All ordering APK-to-backend communication is authenticated with this permanent API key.
- MUST: Revoking a device's API key on the website or admin APK immediately prevents that device from submitting orders or checking in.

### REQ-6: Website Superadmin — Onboarding, Dashboard, and Settings

#### Account Creation and Authentication
- MUST: On first visit to the website, if no superadmin account exists, the site presents a **registration form** (email address, password, confirm password).
- MUST: On registration, a **verification email** is sent to the provided address. The superadmin must click the verification link before the account is activated.
- MUST: Password recovery is available via a "Forgot password" link that sends a recovery email to the registered address.
- MUST: The superadmin dashboard is inaccessible without a verified, authenticated session.

#### Pre-Café State (No Admin Phone Connected)
- MUST: After login, if no admin phone has ever successfully completed the handshake, the dashboard shows a **Setup screen** containing:
  - The current **rotating API key** with a 30-second countdown timer.
  - Instructional text: "Open the Admin APK → Settings → Backend Connection and enter this key."
  - A "Key copied — store it safely" confirmation button the superadmin clicks after the handshake succeeds.
- MUST: Once the admin phone handshake is confirmed, the dashboard transitions permanently to the **Café Dashboard** view. The setup screen is no longer shown.
- MUST: The rotating key shown pre-handshake is the same HMAC-derived 30s key used for the APK pairing. It is shown until the first successful handshake.

#### Café Dashboard (Post-Connection)
- MUST: The dashboard home shows the following metrics as **numbers only** (no graphs):

  | Metric | Periods shown |
  |--------|---------------|
  | Total orders | Today / This week / This month / Last month / Per month up to 12 months ago |
  | Total profit (revenue) | Today / This week / This month / Last month / Per month up to 12 months ago |
  | Café open hours | Today / This week / This month / Last month / Per month up to 12 months ago |

- MUST: "Café open hours" is derived from admin APK session data — time between "Sign In" and "Sign Out (any type)" events recorded on the backend.
- MUST: Order and revenue metrics are read from **daily aggregate rows** (`{ date, totalOrders, totalRevenue, avgOrderValue, paymentSplit: { cash, qr }, cancelledCount, cancelledValue, topItemsPerCategory }`) pushed by the admin APK (at Sign Out with Closing, and on demand) — raw order history stays on the admin phone. Open-hours metrics come from the `sessions` table updated by the admin APK on sign-in and sign-out events.

#### Settings Page
- MUST: A settings icon on the dashboard navigates to the **Settings page**, which contains:
  - **Download APK** — a direct download link to the latest signed APK file (hosted on GitHub Release or a static URL).
  - **Deregister Admin Phone** — revokes the current admin phone's session token and clears the admin device record. Used when the phone is broken or lost. Requires superadmin password confirmation. After deregistration, the setup screen with the rotating key is shown again so a new phone can complete the handshake.
  - **Daily Closing Report settings** — toggle auto-send on/off; configure recipient email address.
  - **Monthly Report** — button to manually generate and download/email the current month's report.
  - **Top Items settings** — configure N (default 5, minimum 1, maximum 20) for the "Top N" ordering report. Applies to all four categories: Food, Beverages, Side Dishes, Others.
  - **Connected Ordering Devices** — list of all ordering-role phones with: device label (auto-set to phone model on registration, editable), last-seen timestamp, online/offline status. Actions per device: **Rename**, **Force Sign Out** (revokes their session for this service period without permanently deregistering), **Deregister** (permanently removes the device — they will need to re-register).

#### Reports — Closing Report and Monthly Report
- MUST: The **closing report** is generated automatically when the admin APK performs "Sign Out with Closing." It is sent to the email configured in Settings. Format: structured PDF (Jasper-report style layout) containing, at minimum:
  - **Header**: café name, report date, open hours (from sign-in/sign-out), closing reason.
  - **Sales**: total completed orders, gross revenue, average order value.
  - **Tender breakdown**: count and amount split by payment method — **Cash** vs **QR**.
  - **Exceptions**: cancelled-order count and value, broken down by who cancelled (admin / staff / customer); count of delta/amendment kitchen slips.
  - **Top N items per category** (Food / Beverages / Side Dishes / Others), N from Top-Items setting.
- MUST: The **monthly report** includes everything in the closing report aggregated across the month, plus: total café open hours for the month, number of operating days, and month-over-month totals where available.
- MUST: Both reports are sent to the superadmin email configured in Settings.
- SHOULD: Reports are also downloadable directly from the Settings page.

### REQ-7: Admin APK — Sign-Out Model

#### Two Sign-Out Actions
- MUST: The Admin APK provides two distinct sign-out options, accessible from the main menu or dashboard:
  1. **Sign Out** — ends the current session on the device (stops order reception, disconnects Realtime). The device **remains registered** and **does not need to re-do the rotating-key handshake**. On next app open, the admin is signed back in automatically using the stored session token.
  2. **Sign Out with Closing** — in addition to ending the session, this action:
     - Prompts for a **closing reason** (free text, e.g., "End of day", "Break", "Public holiday"). Reason is included in the closing report.
     - Generates and sends the **daily closing report** to the configured email.
     - Posts a `{ event: "CLOSE", reason, timestamp }` record to the backend `sessions` table.
     - Then signs out as above (remains registered, no re-handshake required).
- MUST: Neither sign-out action deregisters the device. The session token is preserved and reused on next sign-in.
- MUST: "Sign Out" (without closing) does **not** generate or send a closing report.

#### Sign-In Tracking
- MUST: When the admin APK starts (with a valid session token), it posts a `{ event: "OPEN", timestamp }` to the backend `sessions` table.
- MUST: This data is used by the website dashboard to calculate "Café open hours" metrics.
- MUST: If a new OPEN event arrives while a previous session has no CLOSE (app crash, battery death), the backend implicitly closes the dangling session at the last recorded backend activity timestamp so open-hours metrics stay accurate.

### REQ-8: Non-Functional Requirements

#### Performance
- MUST: Order submission to admin phone notification latency must be under 3 seconds under normal Wi-Fi or mobile data conditions.
- SHOULD: The customer ordering page loads in under 2 seconds on a mid-range smartphone on a 4G connection.

#### Cost
- MUST: All hosting costs must be zero (free tier) for the expected scale of ≤ 30 tables and ≤ 10 devices.
- Recommended hosting: Supabase (free tier — 500 MB DB, 50,000 monthly active users, Realtime up to 200 concurrent connections, Edge Functions).
- Capacity planning: 30 tables × ~2 concurrent browser sessions + 10 devices = ~70 peak connections, consuming only ~35% of the 200-connection Supabase free quota (70% safety buffer). The 2M/month Realtime message allowance is well within budget at this scale.

#### Time Zone
- MUST: All daily/weekly/monthly boundaries (metrics, reports, first-sign-in-of-the-day detection for the Daily Availability popup) are computed in the café's local time zone (default `Asia/Kuala_Lumpur`, configurable in settings) — never raw UTC.

#### Security
- MUST: All traffic between components is over HTTPS / WSS.
- MUST: The public order endpoint validates the table ID against registered tables, enforces one active session per table, rate-limits by IP/browser ID, and re-prices orders server-side from the current menu snapshot (client-submitted prices are ignored).
- MUST: API keys are never logged or exposed in client-side code.
- MUST: The superadmin dashboard is not publicly accessible without credentials.
- SHOULD: API keys are rotatable without requiring app reinstallation.

#### Scalability
- The system is designed for small-scale use: ≤ 30 tables, ≤ 10 connected devices, ~60–70 peak simultaneous connections (well within the 200-connection Supabase free-tier limit at ~35% utilisation).

#### Reliability
- MUST: Ordering-role phones cache unsubmitted orders locally during connectivity loss.
- SHOULD: The admin APK queues print jobs locally if the Bluetooth printer is temporarily unavailable.

#### Accessibility
- SHOULD: The customer ordering page meets WCAG 2.1 AA contrast and touch target size guidelines.
- SHOULD: Font sizes and button sizes on the APK are appropriate for use in a busy kitchen/counter environment.

### REQ-9: Language and Internationalisation

**English is the base (source) language of the whole system.** All authored content —
static UI strings, menu item names/descriptions, report labels — originates in English.
Every other language is produced by **direct dictionary translation** layered over the
English source (see "Dictionary-Translation Model" below), not by requiring the admin to
hand-enter each translation. This replaces the earlier Malay-primary, manually-translated
model.

#### Customer-Facing Website — 4 Language Options
- MUST: When a customer scans a table QR and the ordering page loads, a language selector is presented with four choices: **English (EN, default)**, **Malay (BM)**, **Simplified Mandarin (中文)**, and **Tamil (தமிழ்)**.
- MUST: The selected language applies to all visible text on the customer ordering page — category names, item names, item descriptions, UI labels, button text, status messages, and the order confirmation screen.
- MUST: The language selection is persisted in the browser (localStorage) so returning customers on the same device do not need to re-select. On first load the page defaults to **English**.
- MUST: Non-English text is produced by dictionary translation over the English source. Where the dictionary has no entry for a term, the system **falls back to the English source** (never a blank).
- SHOULD: The language selector is visible at all times during ordering (e.g., a sticky flag/icon in the page header) so customers can switch at any point.

#### Printed Output and APK Processing — 2 Language Options
- MUST: All printed documents (customer receipt, kitchen order slip, audit reports) are produced in one of two languages only: **English** or **Malay**.
- MUST: The active print language is a global system setting controlled exclusively by the Admin role in the APK under Settings → Language.
- MUST: The print language setting applies uniformly to all printed output across all connected devices — kitchen slips, receipts, and report PDFs.
- MUST: The website's superadmin dashboard language mirrors the print language setting (English or Malay only).
- MUST: The ordering-role APK UI (table selection, menu browsing, cart, confirmation) also renders in the global print language (English or Malay only).
- MUST: Changing the global language setting on the admin APK propagates to the backend via `PUT /api/settings` and all connected ordering-role APKs pick it up on the next sync or app start.
- SHOULD: A language change confirmation dialog warns the admin that all connected devices and printed output will be affected.

#### Default Language Behaviour
- MUST: The system default language is **English** on fresh setup.
- MUST: The admin can change the system default language (e.g. to Malay) at any time via Settings. No app reinstall is required.
- MUST: The admin enters menu item names and descriptions **in English only** (the source). When the admin changes the default language, or a customer selects another language, the other-language text is produced by **direct dictionary translation** of the English source — the admin is not required to key in per-language translations.
- SHOULD: The admin may **override** any auto-translated term with a manual translation from the item edit screen; a manual override always wins over the dictionary for that term.

#### Dictionary-Translation Model *(NEW — project research item, see REQ-12 Gap A)*
- MUST: The system maintains **translation dictionaries** keyed off the English source: one set for **static strings** (UI labels, buttons, status messages, category names, report labels) covering BM/中文/தமிழ் for the website and BM for operational output, and one for **menu content** (food/beverage terms).
- MUST: Static-string dictionaries are curated and bundled (developer-maintained), so UI text in every language ships complete and offline.
- MUST: Menu-content translation is **best-effort**: a curated food-term dictionary (a small, café-domain vocabulary — e.g. *Coconut Rice → Nasi Lemak*) with English fallback for unknown terms and an admin manual override for anything wrong. Proper nouns and dish names that should not be translated are marked "do not translate" and passed through verbatim.
- Rationale: cuts the admin's data-entry burden to one language (aligns with REQ-11's minimal-on-site-expertise constraint) and sidesteps maintaining full hand-written per-language resource files. Feasibility, dictionary sourcing, and the offline vs. lookup mechanism are a **research item** (REQ-12 Gap A).

### REQ-10: Table Sessions and Payment

- MUST: A **table session** starts when an order is placed for a table (QR or staff) — the table becomes `Occupied` — and ends only when the order is cancelled or paid from the Table View. There is no automatic expiry; an abandoned session must be cleared by an admin/staff cancel.
- MUST: The backend holds only **active** sessions and orders. On session end (payment or cancel), the admin APK persists the final record to its local database and the backend record is purged after a short TTL.
- MUST: Payment is a record-keeping action (method `CASH` or `QR`, e.g. a DuitNow QR displayed at the counter) — no online payment gateway is integrated (zero-cost constraint).
- MUST: The payment method is stored per order and included in reports (cash vs QR totals in the daily closing and monthly reports).
- MUST: The customer's Cancel button is available only while the order status is `Received` (not yet sent to kitchen). After Send to Kitchen, only the admin or permitted staff can cancel.

### REQ-11: Constraints

- The solution must run on Android (minimum API level 26 / Android 8.0).
- The website must be deployable to free-tier hosting with no paid infrastructure.
- Budget for ongoing costs is zero (free tiers only).
- Technical expertise on-site is minimal — setup and daily operation must not require developer knowledge.
- The APK must be distributable as a side-loaded APK file (no Play Store requirement).

### REQ-12: Open Design / Research Items

Two areas need a design spike before implementation. Both are original research for this
project.

#### Gap A — Dictionary-Translation i18n (English base → other languages)
- MUST: Research and specify the **direct dictionary-translation** mechanism that backs REQ-9: English is the single authored source; BM/中文/தமிழ் (website) and BM (operational) are derived by dictionary lookup.
- Research questions: (1) static-string dictionaries — bundled key→translation tables per language, keyed off the English string; (2) menu-content translation — source of a café food-term dictionary (curated list vs. a bundled offline dictionary), how "do not translate" proper nouns are flagged, and the admin manual-override store; (3) fallback rule (English source when no entry); (4) where dictionaries live (bundled in APK/site vs. a `translations` backend table the admin can extend); (5) zero-cost constraint — no paid translation API; any machine translation must be a one-time offline/build-time step, not a runtime paid call.
- MUST: The chosen mechanism must work fully offline for the customer site's static UI and for operational (APK/print) output.

#### Gap B — Secure On-Device Token Storage
- MUST: Research and specify secure storage for the admin **long-lived session token** and ordering-role **permanent API key** (REQ-5 requires `EncryptedSharedPreferences`).
- Research questions: (1) `androidx.security-crypto` `EncryptedSharedPreferences` + `MasterKey` (Android Keystore-backed) vs. the newer DataStore-with-Keystore approaches, given deprecation status at build time; (2) key rotation and what happens on token revocation/`ADMIN_EXISTS` reclaim; (3) behaviour across app reinstall and device migration (token must survive app restart but not a reinstall — matches REQ-5); (4) minimum-API-26 compatibility.
- MUST: Tokens/keys MUST never be stored in plain DataStore/SharedPreferences or logged (REQ-8 Security).

---

## Glossary

| Term | Definition |
|------|------------|
| QR Code | Machine-readable code printed at each table, encoding the table's ordering URL. |
| Superadmin | The website operator role. Manages all devices and roles. |
| Admin Role | The café owner role on the APK. Has full feature access including language and branding settings. |
| Ordering Role | The staff role on the APK, connected via the invitation URL. Limited to order entry and permitted Table View actions. |
| Rotating API Key | A 30-second time-limited key displayed on the superadmin website, used only for the one-time admin APK handshake. |
| Long-Lived Session Token | A durable token issued to the admin APK after a successful rotating-key handshake. Used for all ongoing admin API calls. |
| API Key | A standard secret token issued to ordering-role devices, used for authentication. |
| ESC/POS | A printer command protocol supported by most Bluetooth thermal printers. |
| 58mm / 80mm | Standard paper roll widths for thermal receipt printers. |
| Receipt Printer | A printer assigned to print customer receipts only. |
| Kitchen Printer | A printer assigned to print kitchen order slips only. |
| Both Printer | A printer assigned to print both receipts and kitchen slips (single-printer setup). |
| Webhook | Legacy term for the backend base URL used at connection time; real-time order delivery uses Supabase Realtime channels, not HTTP callbacks. |
| Kitchen Slip | A printed slip sent to the kitchen listing the items and table for a new order. |
| Receipt | A printed document given to the customer showing their order total. |
| Realtime Channel | A Supabase Realtime WebSocket channel used to push orders to the admin phone. |
| RBAC | Role-Based Access Control — the pattern used to limit features by device role. |
| Print Language | The global language setting (English or Malay) controlling all printed output and APK UI. English is the default. |
| Base Language | English — the single authored source for all UI strings and menu content; every other language is derived from it. |
| Dictionary Translation | Producing non-English text by key→translation lookup over the English source (bundled for static strings, curated food-term dictionary for menu content), with English fallback and admin manual override. |
| i18n | Internationalisation — the system supporting multiple languages on the customer website. |
| Food | One of four fixed menu categories. Contains main dishes. |
| Beverages | One of four fixed menu categories. Contains drinks. |
| Side Dishes | One of four fixed menu categories. Contains accompaniments and extras. |
| Others | One of four fixed menu categories. Anything not covered by the other three. |
| Ask Me Daily | A per-item flag for uncertain day-to-day availability (e.g., market fish like ikan kembung / ikan siakap); triggers the Daily Availability popup. |
| Daily Availability Popup | Top-layer modal with dimmed background, shown on the admin's first sign-in each day, confirming availability (and optionally today's price) of every Ask-me-daily item. |
| Force Check-Out | Admin action from the Devices screen that checks out a staff device without GPS; recorded with a `forced` flag. |
| Catch-up Sync | Fetching missed orders/events/state via REST whenever a device's Realtime connection re-establishes. |
| Line-Item Snapshot | A copy of an item's name, price, and category frozen into an order line at add time, so later menu edits never change past orders or reports. |
| Delta Kitchen Slip | A kitchen slip printed for items added to an order after it was already sent to the kitchen, listing only the new lines (tracked per line by a `sentToKitchen` flag). |
| Manager Override | Optional pattern where a disallowed staff action triggers a one-time admin approval prompt instead of requiring the permission to be enabled globally. |
| Virtual-Printer Routing | The general model behind print roles: a printer can optionally filter by menu category, enabling e.g. a separate bar printer, without changing the role model. |
| Cut Guide | The visible cell borders in the QR PDF that indicate where the print shop should cut. |
| Table Card | An individual cut QR cell, designed to sit on a café table as a standalone card. |
| Café Branding | The café name and logo uploaded from the Admin APK and pushed to the website backend. |
| Skeleton State | The initial state of the website before the admin APK has connected and pushed branding/menu data. |
| Pre-Café State | The website state after superadmin registration but before the first admin phone handshake. Shows rotating key for APK pairing. |
| Closing Report | A PDF report auto-generated and emailed when the admin performs "Sign Out with Closing." Contains orders, revenue, open hours, top items. |
| Monthly Report | A PDF report covering a full calendar month's orders, revenue, and top items. Generated on demand or scheduled. |
| Café Open Hours | Time tracked from admin APK sign-in to sign-out events, stored in the `sessions` table on the backend. |
| Sign Out with Closing | An APK sign-out action that triggers the closing report before ending the session. Does not deregister the device. |
| Sign Out | An APK sign-out action that ends the session only. No report generated. Device remains registered. |
| Sessions Table | Backend table recording admin APK OPEN/CLOSE events with timestamps and closing reasons. |
| Top-N Setting | The configurable number (default 5) of top-ordered items per category shown in reports. Configurable in superadmin settings. |
| Webhook URL | The backend base URL. The admin enters it (with the rotating key) during the admin handshake; staff use the ordering-role Invitation URL instead. |
| Device Fingerprint | The combination of `Build.MODEL` and `Settings.Secure.ANDROID_ID` used to uniquely identify an ordering-role device. |
| Check In | GPS-verified attendance action that grants ordering-role staff access to the ordering screen. |
| Check Out | GPS-verified attendance action that ends a staff shift and hides the ordering screen. |
| Attendance Record | A backend record of a staff device's check-in or check-out event including timestamp and GPS coordinates. |
| Café Closed State | The ordering-role APK state when the admin has signed out with closing. Only shows the Check Out button. |
| Café Open State | The normal operating state when the admin is signed in and ordering devices can check in and take orders. |
| GPS Radius | The configurable maximum distance (default 100m) from the café within which check-in and check-out are permitted. |
| GPS Lock | Registering the café location by capturing the admin phone's current GPS fix on-site (Settings → Café Location); stored in the website database as the authoritative attendance reference. |
| OrderingForegroundService | The persistent Android foreground service that keeps the ordering-role APK alive in the background. |
| Table Session | The active period between an order being placed for a table and the admin/staff ending it via payment or cancel. |
| Table View | The table-grid POS surface in the APK dashboard showing each table's session state and order status. |
| Browser ID | An anonymous UUID stored in the customer browser's localStorage, locking an active order to that phone. |
| Invitation URL | The ordering-role connect URL (with embedded invite token) shown in admin APK Settings, regenerable at any time. |
| Send to Kitchen | The Table View action that dispatches the kitchen slip; prerequisite for Payment. |
| Payment (Cash / QR) | The Table View action recording how an order was settled; marks it Completed and ends the table session. |
| Staff Permissions | Admin RBAC toggles controlling whether ordering-role devices may Send to Kitchen or take Payment (Cancel is always allowed). |
| Occupied | The table state shown to any non-owning phone that scans a table QR during an active session. |
