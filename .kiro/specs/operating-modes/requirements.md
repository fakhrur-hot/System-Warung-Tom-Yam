# Requirements Document

## Introduction

The product today has exactly one topology: every device talks to Supabase over the internet, and
customers order from a Cloudflare-hosted web page by scanning a per-table QR code. That topology is
baked in at a low level — `ApiClient` derives every call from a single Supabase URL, and the customer
web app, the per-table `qrToken`, and 26 Edge Functions all assume a reachable cloud.

This spec adds two internet-free topologies alongside it, selected once during setup:

1. **Cloud Mode** — the current behaviour, unchanged. Supabase backend, customer QR web ordering.
2. **LAN Mode** — no internet. One Main Admin device is the server; Ordering-staff devices reach it
   over a shared Wireless LAN, which may be an ordinary WiFi router or a hotspot the admin device
   itself hosts. No customer web app, no QR table ordering.
3. **Kiosk Mode** — no internet and no peers. A single admin device takes orders, prints kitchen
   slips and customer receipts, and produces reports. No customer tables at all.

**The observation that makes this tractable, and that shapes the whole design:** the two capabilities
Kiosk Mode most needs are already local. Printing is Bluetooth-only and already gated to
`Role.ADMIN`; reports are already computed from Room by `ReportsViewModel`, not fetched from the
server. Neither touches the network today. What is cloud-bound is order/menu/settings CRUD — and
`ApiClient` already demonstrates a full backend swap in-process via `DemoSession`/`DemoBackend`.

**The consequence that shapes the risk:** in Cloud Mode, Room is a discardable cache — Supabase holds
the truth, so `AppDatabase`'s `fallbackToDestructiveMigration` is survivable. In LAN and Kiosk Mode,
Room becomes the café's *only* copy of its orders and takings. Data durability therefore stops being
a nicety and becomes a hard requirement of this spec (Requirement 8).

## Glossary

- **Mode / Operating Mode**: One of `CLOUD`, `LAN`, `KIOSK`. Chosen in the Setup Wizard, persisted on
  the device, and fixed for normal operation (see Requirement 10 for deliberate changes).
- **Server Device**: In LAN Mode, the Main Admin device. It owns the authoritative database and
  serves the other devices. There is no Server Device in Cloud Mode (Supabase is the server) or in
  Kiosk Mode (there are no peers to serve).
- **Client Device**: In LAN Mode, an Ordering-staff device that reads and writes through the Server
  Device instead of Supabase.
- **Local Backend**: The in-process, Room-backed implementation of the API surface that `ApiClient`
  currently reaches over HTTPS. Used directly by the admin device in LAN and Kiosk Mode.
- **LAN Server**: The embedded HTTP listener on the Server Device that exposes the Local Backend to
  Client Devices over the Wireless LAN, using the same request/response shapes as the Edge Functions.
- **Pairing**: How a Client Device learns the Server Device's address and obtains credentials, in
  place of Cloud Mode's website deep-link invites.
- **Cloud-Only Feature**: Anything that cannot exist without the internet — the customer ordering
  web app, per-table `qrToken`s and their printed QR sheets, Supabase Storage image hosting, and the
  server-side email report jobs.
- **Payment QR**: A single static image of the café's own payment QR code (e.g. a DuitNow QR),
  uploaded once by the admin and shown on screen for a customer to scan when paying. It is a photo,
  not a generated code, and is unrelated to the per-table `qrToken` used for customer web ordering —
  the two must not be conflated. Available in all three modes (Requirements 13, 14).
- **Static payee QR (what this QR is, and is not)**: The code identifies the café's bank account only.
  It encodes **no amount and no order reference**. Scanning it opens the customer's own banking app,
  which shows them the payee name for confirmation; the customer then keys in the amount themselves and
  authorises the transfer inside their bank's app. Consequences that shape this spec: the app never
  generates or mutates this code, never needs a per-order variant, and has no transaction record to
  reconcile against — which is why no printed copy or audit trail is required (Requirement 14.10).

## Requirements

### Requirement 1: Three explicit, first-class operating modes

**User Story:** As a café owner, I want to choose the topology that matches my venue and my internet
situation, so I am not forced to pay for and depend on cloud infrastructure a small kiosk does not
need.

#### Acceptance Criteria

1. THE app SHALL support exactly three operating modes — `CLOUD`, `LAN`, `KIOSK` — and SHALL persist
   the selected mode on the device alongside the existing configuration in `AppConfigStore`.
2. THE app SHALL treat `CLOUD` as the behaviour-preserving default: a device already configured
   before this spec ships SHALL continue to operate exactly as it does today, with no
   re-provisioning and no change to its stored Supabase settings.
3. EVERY mode-dependent decision SHALL be derived from the single persisted mode value, so that no
   feature infers its topology from a side effect such as whether a Supabase URL happens to be set.
4. THE app SHALL display the active mode somewhere the operator can see it without entering Setup, so
   a support conversation can establish the topology quickly.

### Requirement 2: Mode is chosen in the Setup Wizard, reached from a restructured entry screen

**User Story:** As someone setting up a device, I want the first screen to make the common action
obvious and keep first-time provisioning distinct from day-to-day joining, so staff do not wander
into the wizard by accident.

#### Acceptance Criteria

1. THE entry screen (today's role-select screen) SHALL present exactly three actions, in this order:
   a prominent **Join as Ordering mode** action at the top; a **Relogin as Café Admin** action in the
   middle; and, after a deliberate larger gap, a less prominent **Setup Wizard** action at the bottom.
2. THE **Setup Wizard** action SHALL lead to a mode choice offering exactly three options, labelled
   to state what each includes: **Full Online with QR ordering**, **(W)LAN AP without QR ordering**,
   and **Kiosk Mode**.
3. WHEN `CLOUD` is chosen, THE wizard SHALL collect the Supabase URL, anon key, and café name exactly
   as the current Setup screen does.
4. WHEN `LAN` or `KIOSK` is chosen, THE wizard SHALL NOT ask for a Supabase URL, anon key, or website
   URL, since no such backend exists in those modes.
5. THE larger gap in 2.1 SHALL be a real layout separation rather than only a visual style change, so
   that the Setup Wizard action is not adjacent to the two routine actions and cannot be mis-tapped.

### Requirement 3: Kiosk Mode — one device, no network, no tables

**User Story:** As a small kiosk owner with one tablet and no staff, I want to take an order, print
the kitchen slip and the customer receipt, and see my daily numbers, without any internet connection
or any table management.

#### Acceptance Criteria

1. IN Kiosk Mode, THE app SHALL allow the admin to compose and place an order, print a kitchen slip,
   print a customer receipt, and take payment, with no network of any kind available.
2. IN Kiosk Mode, THE app SHALL NOT present table selection, a table grid, or table management, and
   SHALL identify each order by a locally generated running order number instead of a table.
3. IN Kiosk Mode, THE app SHALL produce the same reports it produces today, computed from the local
   database.
4. IN Kiosk Mode, THE app SHALL NOT present device pairing, staff invitations, or any Ordering-staff
   role, since no second device participates.
5. THE order number in 3.2 SHALL be unique and monotonic within a business day as already defined by
   the existing `businessDayStartHour` setting, and SHALL appear on both the kitchen slip and the
   customer receipt so counter staff and kitchen refer to the same identifier.

### Requirement 4: LAN Mode — the admin device is the server

**User Story:** As a café owner without reliable internet, I want my staff's order-taking devices to
work against my own admin tablet over a local network, so service continues with no connectivity and
no monthly cloud cost.

#### Acceptance Criteria

1. IN LAN Mode, THE Main Admin device SHALL hold the authoritative database and SHALL serve
   Client Devices over the Wireless LAN.
2. THE LAN Server SHALL expose the same request paths, request bodies, and response shapes that the
   corresponding Supabase Edge Functions expose today, so that a Client Device's behaviour does not
   depend on which mode it is running in.
3. IN LAN Mode, THE app SHALL support the Wireless LAN being either an existing WiFi network both
   devices join, or a hotspot hosted by the Server Device itself.
   1. THE app SHALL NOT attempt to switch on hotspot tethering programmatically, because Android
      provides no public API to do so; it SHALL instead instruct the operator to enable the hotspot in
      Android's own settings and SHALL offer to open that settings screen for them.
   2. THE app SHALL detect and display the Server Device's current address on the active network
      interface, so the operator can confirm the network is up before pairing.
   3. WHEN no usable network interface is present, THE app SHALL say so plainly and SHALL NOT produce a
      pairing code containing an unreachable address.
   4. THE app SHALL work with mobile data switched off in both cases, since the wireless network here
      carries only local traffic and never needs an internet path.
   5. WHEN a Client Device is joined to a wireless network that has no internet, THE app SHALL route
      its traffic over that network explicitly rather than relying on the operating system's default
      route — with mobile data enabled, an unbound request travels over cellular and never reaches the
      Server Device, while the app still appears connected.
   6. THE app SHALL hold a no-internet wireless network against the operating system's tendency to
      disconnect it once its connectivity check fails, so a Client Device does not lose the Server
      partway through service.
   7. WHEN the app releases such a network, IT SHALL restore the device's normal routing, so the
      operator's phone is not left without mobile data afterwards.
   8. THE app SHALL NOT request a specific wireless network by name in a way that forces the device to
      disconnect and re-associate, and SHALL NOT require location permission in order to connect to the
      Server Device.
   9. WHEN the Server Device is itself hosting the wireless network, THE app SHALL detect this and skip
      the client-side routing steps above, which do not apply to the device that *is* the network.
4. THE app SHALL implement, on the Local Backend, every endpoint the Ordering-staff and admin flows
   actually call, and SHALL NOT be required to implement endpoints that exist server-side only for
   cloud-specific purposes (the email report jobs, the metrics endpoint, the rotating-key endpoint,
   and the customer table-session endpoint).
5. WHEN the Server Device is unreachable, THE Client Device SHALL surface a clear, mode-appropriate
   message naming the local connection as the problem, and SHALL NOT report it as an internet or
   Supabase failure.
6. IN LAN Mode, THE Server Device SHALL remain fully usable for order entry and printing while no
   Client Device is connected.

### Requirement 5: Pairing and discovery replace website invite links

**User Story:** As an admin setting up a staff device on my own network, I want to pair it in seconds
without typing an IP address, because the website deep-link invitations do not exist in this mode.

#### Acceptance Criteria

1. THE app SHALL let a Client Device pair by scanning a code displayed on the Server Device that
   carries the Server Device's address, its port, and a pairing credential.
2. THE app SHALL also accept the Server Device's address entered by hand, so pairing still works when
   a camera is unavailable or unusable.
3. THE Server Device SHALL require explicit operator approval of a newly pairing Client Device before
   that device can place orders, preserving the approval step the current invite flow provides.
4. THE pairing credential SHALL be usable only for pairing, SHALL expire, and SHALL be replaceable by
   the operator without re-pairing already-approved devices.
5. WHEN the Server Device's address changes — as it will when a hotspot or router restarts — THE
   Client Device SHALL be able to re-establish contact without being fully re-paired and re-approved.
   1. WHERE the Server Device is hosting the wireless network, THE Client Device SHALL resolve it from
      the network's own gateway address, since the device hosting an access point *is* the gateway —
      no search or re-scan is required in this case.
   2. WHERE the Server Device is an ordinary peer on a shared router, THE Client Device SHALL fall back
      to local network service discovery, and only then to asking the operator to re-scan.
6. THE Server Device SHALL let the operator see paired Client Devices and revoke any of them, as the
   Devices screen does today.

### Requirement 6: Live order propagation without the cloud realtime service

**User Story:** As kitchen and counter staff, I want an order placed on a staff device to appear on
the admin device promptly and to print automatically, exactly as it does in Cloud Mode.

#### Acceptance Criteria

1. IN LAN Mode, an order placed on a Client Device SHALL become visible on the Server Device, and
   SHALL trigger the same new-order alert and automatic kitchen printing that Cloud Mode triggers.
2. THE app SHALL NOT require a Supabase Realtime WebSocket in LAN or Kiosk Mode, and SHALL NOT leave
   a connection attempt retrying against an address that cannot serve it.
3. THE propagation delay in 6.1 SHALL be no worse than the delay Cloud Mode currently achieves in
   practice via its periodic catch-up sync.
4. THE app SHALL NOT print the same kitchen slip twice, nor alert twice for the same order, when a
   Client Device retries a submission or a poll returns an order already seen.

### Requirement 7: Cloud-Only Features are absent, not merely hidden

**User Story:** As a café owner in LAN or Kiosk Mode, I do not want to see, be offered, or be able to
reach features that cannot possibly work without the cloud.

#### Acceptance Criteria

1. IN LAN and Kiosk Mode, THE app SHALL NOT offer customer QR ordering, SHALL NOT generate per-table
   QR tokens, and SHALL NOT offer the printable QR sheets whose only purpose is customer web ordering.
2. IN LAN and Kiosk Mode, THE app SHALL NOT offer website-based staff invitation links, since there
   is no website to host them.
3. IN LAN and Kiosk Mode, menu item images SHALL be stored and served locally rather than uploaded to
   cloud object storage, and the menu screens SHALL continue to display them.
4. IN LAN and Kiosk Mode, THE app SHALL NOT present settings whose only effect is on the customer web
   app or on server-side email reports.
5. Suppression under this requirement SHALL remove the capability, not merely hide a button — a
   suppressed feature SHALL NOT be reachable by restoring saved state, following a deep link, or
   rotating the device.

### Requirement 8: Data durability, because the device is now the only copy

**User Story:** As a café owner in LAN or Kiosk Mode, I need to know that my orders and takings cannot
be silently destroyed, because there is no cloud copy to restore from.

#### Acceptance Criteria

1. IN LAN and Kiosk Mode, THE local database SHALL NOT be subject to destructive migration: an app
   upgrade that changes the schema SHALL either migrate the existing data or fail loudly, and SHALL
   NOT discard the café's orders in order to proceed.
2. THE app SHALL provide the operator a way to back up and restore the local database in LAN and
   Kiosk Mode, and SHALL make the need to do so evident rather than leaving it undiscovered.
3. THE app SHALL warn before any operation that would clear local data in LAN or Kiosk Mode, naming
   what will be lost.
4. Order identifiers generated locally SHALL NOT collide across devices in LAN Mode, so that an order
   created on a Client Device cannot overwrite one created on the Server Device.

### Requirement 9: Printing and reporting behave identically in all three modes

**User Story:** As an operator, I want slips, receipts, and reports to look and behave the same
regardless of topology, so switching modes does not retrain my staff or change my paperwork.

#### Acceptance Criteria

1. Kitchen slips and customer receipts SHALL be produced by the same rendering path in all three
   modes, including the multilingual bitmap rendering, the logo header, and the configured paper
   width.
2. Reports SHALL present the same figures and breakdowns in all three modes.
3. Printing SHALL remain restricted to the Main Admin device in LAN Mode, as it is today; a Client
   Device SHALL NOT acquire printing capability by virtue of the mode change.
4. Item names on printed output SHALL continue to be localized from the live menu at print time in
   all three modes.
5. THE printing subsystem SHALL NOT become mode-aware: no component under `printing/` SHALL branch on
   the operating mode. Mode differences SHALL be confined to the data handed to it — in Kiosk Mode, the
   running order number where a table label would otherwise appear.
6. THE reporting subsystem SHALL NOT become mode-aware, and SHALL continue to read from the local
   database as it does today, regardless of how the orders in that database arrived.
7. Requirements 9.1–9.6 SHALL be verified by comparing real printed and reported output across the
   three modes (Requirement 12.7), not by observing that the printing and reporting code is unchanged —
   unchanged code fed different inputs is exactly how this requirement fails in practice.

### Requirement 10: Changing mode is deliberate and never silently destructive

**User Story:** As a café owner whose circumstances change, I want to move between modes knowingly,
with a clear statement of what happens to my existing data.

#### Acceptance Criteria

1. THE app SHALL treat a mode change on a configured device as an explicit operation initiated from
   the Setup Wizard, not as a side effect of editing a setting.
2. BEFORE applying a mode change, THE app SHALL state plainly what will happen to the data already on
   the device and what will become unavailable, and SHALL require confirmation.
3. THE app SHALL NOT attempt to migrate data between a cloud backend and a local one in either
   direction as part of this spec — the modes are independent, and any such transfer is out of scope
   and SHALL be described as unavailable rather than partially attempted.

### Requirement 11: No cloud contact in LAN and Kiosk Mode

**User Story:** As a café owner who chose an offline mode, I want confidence the app is not quietly
reaching the internet, because I may be on a metered hotspot or have no connection at all.

#### Acceptance Criteria

1. IN LAN and Kiosk Mode, THE app SHALL NOT issue requests to Supabase or to any other internet host
   as part of its normal operation.
2. THE absence in 11.1 SHALL be demonstrable by inspection of the running app's network activity, not
   asserted only in code review.
   1. THE check SHALL cover every HTTP client in the app, not only the backend gateway — which in Kiosk
      Mode is not even in use. At minimum this includes the gateway's own client, the two realtime
      services' separate WebSocket clients, and the image loader used for menu and logo images. A guard
      applied to one call site would report success while another quietly reached the internet.
3. IN LAN and Kiosk Mode, THE app SHALL NOT hold or require a Supabase anon key or session token, and
   SHALL NOT retain a previously configured one after the mode is applied.

### Requirement 12: Verification on real devices over a real wireless LAN

**User Story:** As the maintainer, I want this implementation proven on actual hardware over an actual
hotspot before a café depends on it, because the failure modes that matter here — a hotspot changing
its address, a device sleeping, a socket dying mid-service — do not appear in a simulator or on a
single device.

#### Acceptance Criteria

1. LAN Mode SHALL be verified with at least two physical devices on a hotspot hosted by the Server
   Device, covering: pairing, approval, order placement from the Client Device, the order appearing
   and auto-printing on the Server Device, and recovery after the Server Device's address changes.
2. Kiosk Mode SHALL be verified on a physical device with networking disabled entirely, covering
   order entry, kitchen slip printing, receipt printing, and report generation.
3. Cloud Mode SHALL be re-verified after this work to confirm it is unchanged, since this spec
   modifies the shared paths that Cloud Mode also uses.
4. THE release build SHALL be verified, not only the debug build, because the app ships minified and
   this spec adds code that a minifier can break in ways a debug build does not reveal.
5. THE Payment QR feature SHALL be verified by scanning the on-screen image with a real banking app on
   a separate physical phone, in each of the three modes, because an image that renders correctly but
   does not scan is indistinguishable from a working one by inspection alone.
6. THE database migration SHALL be verified over a **populated** database — install the prior version,
   create real orders, upgrade, and confirm every order survives. A migration exercised only against an
   empty database demonstrates nothing about the property it is meant to protect.
7. Printing and reporting SHALL be verified by producing a real kitchen slip, a real customer receipt,
   and a real report in each of the three modes and comparing them. Because the printing code is
   deliberately unmodified, the risk is not a bad edit but a difference in the inputs each mode feeds
   it, which only comparing real output will reveal.
8. THE verification items in this requirement SHALL be planned as explicit, scheduled work with
   hardware, and SHALL NOT be treated as satisfied by a passing automated test suite or a successful
   build.

### Requirement 13: "Show QR" — displaying the café's static payment QR

**User Story:** As an admin or ordering staff member taking payment, when a customer asks to pay by QR
I want to show them our payment QR on screen immediately, so I do not have to fetch a printed standee
or hand over my own device's banking app.

#### Acceptance Criteria

1. WHEREVER the **Pay Cash** and **Pay QR** actions are shown, THE app SHALL additionally show a
   **Show QR** action directly below them, rendered as a small hollow (outlined) button visually
   subordinate to the two payment buttons.
2. THE **Show QR** action SHALL appear under exactly the same conditions as the two payment buttons,
   for both the admin and the ordering-staff role, so that a device permitted to take payment can
   always present the Payment QR.
3. WHEN no Payment QR image has been uploaded, THE **Show QR** action SHALL NOT be displayed at all —
   not shown-and-disabled, and not shown-then-failing when tapped.
4. WHEN **Show QR** is activated, THE app SHALL display the Payment QR image in a modal dialog over a
   darkened background, sized so the code occupies as much of the dialog as the image's aspect ratio
   allows.
5. THE modal SHALL be static: it SHALL NOT auto-dismiss on a timer, SHALL NOT animate or transform the
   image, and SHALL be dismissed only by an explicit action from the operator.
6. WHILE the modal is open, THE app SHALL keep the screen awake so it cannot sleep mid-scan, and SHALL
   leave screen brightness at the device's own Android system setting — it SHALL NOT raise, lower, or
   otherwise override brightness, and SHALL NOT need to restore a brightness it never changed.
7. WHILE the modal is open, THE ambient/screensaver display SHALL NOT activate, regardless of how long
   the modal remains open.
8. THE modal SHALL display the image only. It SHALL NOT display an order total, table, or any other
   order detail alongside the code, so that nothing can be misread as part of the payment instruction.
9. THE Payment QR SHALL be displayable from a device with no working network connection at the moment
   of display, in every mode.

### Requirement 14: Uploading, validating, and distributing the Payment QR

**User Story:** As a café admin, I want to upload a photo of our payment QR once in Settings and have
every device that takes payment show that exact code, so customers always pay the right account.

#### Acceptance Criteria

1. THE Admin Settings screen SHALL provide a dedicated section for the Payment QR, allowing the admin
   to upload an image, replace an existing one, and remove it entirely.
2. THE app SHALL accept JPEG and PNG images for the Payment QR.
3. BEFORE accepting an uploaded image, THE app SHALL verify that the image actually contains a
   machine-readable QR code, and SHALL reject it with a clear explanation if it does not.
4. WHERE the app resizes or re-encodes the uploaded image for storage or transport, IT SHALL verify
   that the stored result still decodes as a QR code carrying the same payload as the original, and
   SHALL preserve the original instead of storing a degraded copy that no longer scans.
5. WHEN the admin removes the Payment QR, THE **Show QR** action SHALL disappear on every device that
   takes payment, not only on the admin device.
6. WHEN the admin replaces the Payment QR, EVERY device that takes payment SHALL show the new image
   and SHALL NOT continue showing the previous one — a stale Payment QR sends a customer's money to
   the wrong account and is the most serious failure this feature can have.
7. THE Payment QR SHALL be available in all three operating modes, and SHALL NOT be treated as a
   Cloud-Only Feature under Requirement 7 despite being an image, since it is a payment aid rather
   than a customer-web feature.
8. THE Payment QR SHALL be distributed to ordering-staff devices by the mode's own backend — Supabase
   in Cloud Mode, the LAN Server in LAN Mode — and SHALL be cached on each device so that Requirement
   13.9 holds.
9. THE Payment QR image SHALL NOT be carried in the frequently-polled settings payload, since that
   payload is fetched repeatedly during normal operation and an embedded image would be re-transferred
   on every poll.
10. THE app SHALL NOT print the Payment QR on kitchen slips or customer receipts, and SHALL NOT record
    any per-display audit entry. The code is a static payee identifier: the customer's own banking app
    confirms the payee to them and they key the amount themselves, so there is no app-side transaction
    to evidence and nothing a printed copy would prove.
11. THE app SHALL NOT attempt to encode an amount, an order reference, or any other per-order data into
    the Payment QR, and SHALL NOT modify the uploaded image in any way that alters what it encodes.
