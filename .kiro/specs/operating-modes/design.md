# Design Document: Operating Modes (Cloud / LAN / Kiosk)

## Overview

Three topologies, one APK, selected once in the Setup Wizard. The design rests on a single
observation about the existing code: **`ApiClient` is the only component that knows the backend
exists.** Every one of the ~30 backend calls is derived from one string:

```kotlin
// data/ApiClient.kt:45-48
private fun supabaseUrl(): String = appConfig.supabaseUrl().ifBlank { BuildConfig.SUPABASE_URL }
private fun baseUrl(): String = supabaseUrl().trimEnd('/') + "/functions/v1"
```

and `ApiClient` already contains a working precedent for replacing the backend wholesale — nearly
every method opens with `if (DemoSession.active) return demoBackend.…`, routing the entire app at an
in-process fake. LAN and Kiosk Mode are that same seam, made durable and real.

Three facts about the current implementation make the offline modes far cheaper than they look:

1. **Printing is already local and already admin-only.** `printing/PrinterConnectionManager.kt:82-83`
   and `printing/PrintService.kt:59-60` gate on `Role.ADMIN`; the transport is Bluetooth SPP. No
   network is involved at any point. Requirement 9 is largely a matter of not breaking it.
2. **Reports are already computed from Room.** `ui/viewmodels/ReportsViewModel.kt:141-179` queries
   `OrderDao` directly. The `reports-closing` / `reports-monthly` Edge Functions are server-side email
   jobs the APK never calls.
3. **Tables are already phone-authoritative.** `ApiClient.kt:1389-1396` documents that the local table
   registry is the source of truth and `GET /tables` exists only to rehydrate a fresh install. The
   "device owns the data" pattern is not new here.

What is genuinely cloud-bound is order/menu/settings CRUD, device pairing, and image hosting. That is
what the Local Backend must implement.

**The risk this design must carry deliberately:** in Cloud Mode, Room is a cache and
`AppDatabase`'s `fallbackToDestructiveMigration` is survivable because Supabase holds the truth. In
LAN and Kiosk Mode there is no second copy. Data durability moves from "nice" to load-bearing, and is
treated as such below.

## Architecture

The same three layers are rewired per mode; nothing above `ApiClient` changes.

```
CLOUD (unchanged)
  Admin APK ─┐
  Staff APK ─┼─ HTTPS ─→ Supabase Edge Functions ─→ Postgres
  Customer web ─┘                                    ↑
                                              per-table qrToken

LAN
  Staff APK ── HTTP over Wireless LAN ─→ ┌──────────────────────────┐
  Staff APK ── HTTP over Wireless LAN ─→ │ Admin device (Server)    │
                                          │  LanServer (HTTP)       │
                                          │    ↓                    │
                                          │  LocalBackend           │
                                          │    ↓                    │
                                          │  Room (authoritative)   │
                                          │    ↓                    │
                                          │  Bluetooth printers     │
                                          └──────────────────────────┘
  No customer web. No qrToken. No internet.

KIOSK
  ┌──────────────────────────┐
  │ Admin device (only)      │
  │  ApiClient → LocalBackend│   (no HTTP listener at all)
  │    ↓                     │
  │  Room (authoritative)    │
  │    ↓                     │
  │  Bluetooth printers      │
  └──────────────────────────┘
  No peers. No tables. No internet.
```

Note the asymmetry that keeps risk low: **a Client Device in LAN Mode is not a new kind of client.**
It speaks the same REST dialect to a different host. Its only mode-specific behaviour is how it
discovers that host and how it reports connection failure (Requirements 4.2, 4.5).

## Components and Interfaces

### `OperatingMode` and `ModeCapabilities`

```kotlin
enum class OperatingMode { CLOUD, LAN, KIOSK }
```

Persisted in `AppConfigStore` (EncryptedSharedPreferences, file `app_config_prefs`) under a new
`operating_mode` key, defaulting to `CLOUD` when absent so existing installs are untouched
(Requirement 1.2).

Feature suppression is resolved in exactly one place rather than scattered `if (mode == …)` checks:

**Exactly eight fields — all of them, spelled out, so none is dropped in implementation:**

| Field | CLOUD | LAN | KIOSK | Gates |
|---|:--:|:--:|:--:|---|
| `customerQrOrdering` | ✅ | ❌ | ❌ | The customer web ordering flow |
| `printableQrSheets` | ✅ | ❌ | ❌ | `QrPdfScreen` / `QrPdfViewModel` |
| `tables` | ✅ | ✅ | ❌ | Table grid, table selection, table management |
| `staffDevices` | ✅ | ✅ | ❌ | Devices screen, pairing, approval |
| `secondaryAdmin` | ✅ | ❌ | ❌ | The `ADMIN_SECONDARY` role — see below |
| `websiteInvites` | ✅ | ❌ | ❌ | `https://…/join?invite=` deep-link invitations |
| `cloudImageHosting` | ✅ | ❌ | ❌ | Object-storage upload vs local file storage |
| `realtimeWebSocket` | ✅ | ❌ | ❌ | Supabase Realtime socket startup |

So: `CLOUD` = all eight true. `LAN` = **only** `tables` and `staffDevices` true. `KIOSK` = all eight
false. Navigation, menus, and settings read this one object, so adding a fourth mode later touches a
single table (Requirements 1.3, 7.5).

`secondaryAdmin` earns a flag rather than being silently unsupported: `ADMIN_SECONDARY` is a real role
today (`SecureStorage.kt:41`) with its own UI gating, and it is **out of scope for LAN and Kiosk in this
spec**. Making that a capability makes the exclusion visible and enforceable in one place instead of an
undocumented gap someone rediscovers mid-implementation. LAN Mode is one `ADMIN` server plus N
`ORDERING` clients — nothing else.

**The Payment QR is deliberately absent from `ModeCapabilities`.** It is available in all three modes
(Requirement 14.7), so making it a capability flag would invite someone to suppress it in LAN or Kiosk
alongside the genuinely cloud-shaped image features. Its visibility is driven by whether an image is
configured, never by mode.

`ADMIN_SECONDARY` is explicitly **out of scope for LAN and Kiosk** in this spec. LAN Mode supports one
`ADMIN` (the Server Device) and N `ORDERING` clients.

### `BackendGateway` — the abstraction at the existing seam

`ApiClient`'s public surface is already the app's de-facto backend interface, and `DemoBackend` already
implements a parallel one. This design extracts that into an explicit interface and provides three
implementations, chosen once at construction:

| Mode | Device | Implementation |
|---|---|---|
| `CLOUD` | any | `RemoteBackend` — today's OkHttp calls to Supabase, unchanged |
| `LAN` | Server (admin) | `LocalBackend` — direct, in-process, Room-backed |
| `LAN` | Client (staff) | `RemoteBackend` with base URL `http://<server>:<port>` |
| `KIOSK` | admin | `LocalBackend` |

Because the LAN Client reuses `RemoteBackend` verbatim, the entire Ordering-staff feature set — order
creation, add-items, send-to-kitchen, payment, cancel, settings-driven RBAC — works in LAN Mode with
no new client code.

### `LocalBackend` — Room-backed implementation of the API surface

Implements the endpoints the app actually calls. Deliberately **not** implemented, because they exist
for cloud-only purposes (Requirement 4.4): `metrics`, `reports-closing`, `reports-monthly`,
`rotating-key`, `tables-session`.

| Endpoint group | LAN | Kiosk | Notes |
|---|---|---|---|
| `orders` create / `?since=` | ✅ | ✅ | Server assigns the id — see Data Models |
| `orders-kitchen` / `-items` / `-status` / `-payment` / `-cancel` | ✅ | ✅ | Same status machine via `data/local/OrderActions.kt` |
| `menu` GET/PUT | ✅ | ✅ | Room `menu_items` becomes authoritative, not a cache |
| `menu-image` POST/DELETE | ✅ local file | ✅ local file | App-private storage, not Supabase Storage (Req 7.3) |
| `settings` GET/PUT | ✅ | ✅ | Cloud-web-only keys suppressed (Req 7.4) |
| `tables` GET/PUT | ✅ | ❌ | No tables in Kiosk (Req 3.2); no `qrToken` in either (Req 7.1) |
| `branding` GET/PUT | ✅ | ✅ | Logo from local file; also carries the Payment QR URL + hash |
| Payment QR image | ✅ | ✅ | Screen-only, all modes, never in the `settings` payload |
| `sessions` / `aggregates` | ✅ | ✅ | Open/close and daily aggregate, written locally |
| `register` / `devices-status` / `devices` / `devices/{id}` | ✅ | ❌ | Pairing and approval — see below |
| `invite` / `invite/regenerate` | ✅ pairing code | ❌ | Not a website link (Req 7.2) |
| `admin-recovery` / `admin-handshake` | ❌ | ❌ | No remote to recover from; local PIN gate instead |
| `cafe-location` / `attendance` | ✅ | ❌ | GPS attendance needs no internet |

### `LanServer` — the embedded HTTP listener

Runs only on the Server Device in LAN Mode, inside the existing admin foreground service so it shares
that service's lifetime and wake guarantees rather than introducing a second one.

**Engine choice.** Recommendation: **Ktor with the CIO engine** — coroutine-native, which matches a
codebase whose DAOs are `suspend` and whose printer access is `Mutex`-guarded, and actively
maintained. Cost is roughly 1–2 MB of APK. The alternative, NanoHTTPD, is ~50 KB but thread-per-request
and effectively unmaintained; given the APK just grew 1.4 MB for the ads SDK, the size question is
worth an explicit decision at task time rather than assuming.

Binds to the device's LAN address on a fixed port, serving paths under `/functions/v1/…` so the
Client's `baseUrl()` construction needs no special-casing (Requirement 4.2). Accepts and ignores the
`apikey` header, which `ApiClient` sends unconditionally on every request. Reproduces the response
semantics the client already depends on, including the **401 contract** — `ApiClient.kt:57-74` clears
the session token and emits `AuthEventBus.SessionExpired` on a 401, which is how a revoked device gets
ejected.

### Pairing and discovery

Replaces Cloud Mode's `https://<site>/join?invite=…` deep links, which cannot exist without a website.

- **Pairing code (primary).** The Server Device displays a QR encoding `{host, port, pairingToken}`.
  The Client scans it with the existing camera scanner, then calls `register` and polls
  `devices-status` — the flow `PendingApprovalScreen.kt` already implements, including approval
  waiting and role resolution. The operator approves on the Devices screen exactly as today
  (Requirements 5.1, 5.3, 5.6).
- **Manual entry (fallback).** Host and port typed by hand (Requirement 5.2).
- **`pairingToken`** is pairing-only, expiring, and rotatable without disturbing approved devices
  (Requirement 5.4). Approved devices hold a durable credential instead.
- **Address changes (Requirement 5.5).** A hotspot restart moves the Server Device. On connection
  failure the Client tries, in order: last known address; then NSD/mDNS discovery by service type via
  Android's `NsdManager`; then prompts for a re-scan. Its durable credential survives, so it
  re-attaches without re-approval.

### Payment QR — one shared button, one shared image

**Insertion point is a single component.** The Pay Cash / Pay QR row lives at
`ui/tableview/OrderDetailSheet.kt:382-403`, inside
`if (permissions.canTakePayment && OrderActions.canTakePayment(order.status))`. That sheet is the
shared tableview component used by *both* the admin and the ordering-staff screens — which is why it
takes a `permissions: StaffPermissions`. So "everywhere admin or staff see the pay buttons" is
literally one place, and the outlined **Show QR** button goes directly below that `Row`, inside the
same conditional (Requirements 13.1, 13.2).

Visibility is `configured && canTakePayment` — never mode-dependent (Requirement 13.3).

**Image handling must not follow `LogoPipeline`.** `ui/util/LogoPipeline.kt` center-crops to a square,
compresses to fit 200 KB (`MAX_JPEG_SIZE_BYTES`), and derives a monochrome bitmap for thermal
printing. Reusing it here would be a mistake on three counts: JPEG re-compression can smear a dense
QR's modules until it stops scanning, the monochrome/print path is irrelevant since the Payment QR is
screen-only (Requirement 14.10), and PNG input should stay lossless.

A separate `PaymentQrPipeline` therefore: keeps PNG as PNG, caps dimensions generously rather than
aggressively, and — the important part — **verifies scannability rather than assuming it.** ZXing is
already a dependency (`com.google.zxing:core:3.5.3`, used today for QR generation and scan decoding),
so the pipeline decodes the image on upload to confirm a QR is present (Requirement 14.3), and
re-decodes the stored result to confirm the payload is byte-identical to the original, keeping the
original if it is not (Requirement 14.4). This is the difference between shipping a feature and
shipping a picture of a feature.

**Distribution.** Ordering-staff devices must show the same code, so the image cannot be device-local.
It is addressed as a URL resolved per mode, then cached on the device so display never depends on the
network at the moment a customer is waiting (Requirements 13.9, 14.8):

| Mode | Stored | Served as |
|---|---|---|
| `CLOUD` | object storage, as `menu-image` already does | `https://…` URL returned by `branding` |
| `LAN` | file on the Server Device | `http://<server>:<port>/media/payment-qr.<ext>` |
| `KIOSK` | file in app-private storage | read directly; no URL fetch |

Carried on `branding`, **not** `settings`. `GET /settings` is polled repeatedly in normal operation —
`RealtimeService` re-fetches it on a 30 s TTL for the auto-print flag (`AUTO_PRINT_TTL_MS`) — so an
embedded image there would be re-transferred continuously (Requirement 14.9). `branding` is already
the endpoint that carries the café logo and is fetched rarely.

**Staleness is the real hazard.** A cached Payment QR that outlives its replacement sends a customer's
money to the wrong account (Requirement 14.6). Caching is therefore keyed by a content hash of the
image published alongside the URL: a device holding a different hash refetches before display, and a
removed QR clears the cache and hides the button everywhere (Requirement 14.5). Cache-by-URL alone
would be insufficient, since replacing an image at the same path would leave every staff device
confidently serving the old payee.

**The modal** (`ui/components/PaymentQrDialog.kt`) is deliberately inert: image only, no order total or
table alongside it (Requirement 13.8), no timer, no animation, dismissed only by explicit action
(Requirement 13.5).

While open it keeps the screen awake and suppresses the ambient/screensaver display (Requirements 13.6,
13.7) — the existing idle detector would otherwise replace the very code a customer is mid-scan on.
It does **not** touch brightness: the window's `screenBrightness` is left at
`BRIGHTNESS_OVERRIDE_NONE` so the device's own Android system setting applies. Overriding it was
considered and rejected — a POS terminal's brightness is set deliberately by the operator, an app that
yanks it to maximum is startling in a dim café, and having changed it the app then owns restoring it
correctly across rotation, backgrounding, and process death. Keeping the screen *on* is the part that
actually prevents a failed scan; changing how bright it is is not.

**What this QR is determines what the code must never do.** It is a static payee identifier encoding
no amount and no order reference: the customer's banking app shows them the payee, and they key the
amount themselves. So the app never generates, regenerates, or parameterises this code per order, and
`PaymentQrPipeline`'s payload-equality check (Requirement 14.4) is doubly load-bearing — it is the only
thing standing between a lossy re-encode and a code that silently resolves to a different payee.
Equally, there is no app-side transaction to evidence, which is why the feature carries no print path
and no audit trail (Requirements 14.10, 14.11).

### Realtime: polling, not a WebSocket

The existing `realtime/RealtimeService.kt` builds its socket URL by string-replacing the scheme:

```kotlin
// RealtimeService.kt:372  (and OrderingForegroundService.kt:159)
supabaseUrl().replace("https://", "wss://") + "/realtime/v1/websocket?apikey=…"
```

Against an `http://` LAN base URL this produces an unusable URL and would retry on backoff forever —
noisy and battery-wasteful. `ModeCapabilities.realtimeWebSocket` therefore gates socket startup off
entirely in LAN and Kiosk (Requirement 6.2).

This costs less than it appears. The service's own comments (`RealtimeService.kt:83-98`, `:290-304`)
record that the Supabase socket connects but does not deliver `NEW_ORDER` frames in the field, so
**Cloud Mode's live behaviour already runs off the 10 s catch-up poll** (`POLL_INTERVAL_MS = 10_000`,
`:87`) — new-order alerts, sound, and kitchen auto-print all originate there
(`maybeNotifyNewOrders:601`, `autoPrintFromSync:305`). LAN Mode keeps that mechanism and shortens the
interval, since a same-subnet request is far cheaper than an internet round trip; this satisfies
Requirement 6.3 by construction rather than by hope.

Kiosk Mode needs no propagation at all: the device that takes the order is the device that prints it.

De-duplication reuses the existing in-memory `printedKitchenIds` / `notifiedItemIds` sets
(`RealtimeService.kt:135-154`), which already guard against a poll re-returning a seen order
(Requirement 6.4).

### Printing and reporting: the parts that must actively *not* change

This section exists because "unchanged" is the easiest thing to leave out of a plan — there is no new
component to build, so it silently gets no task, and then nothing verifies it. Requirement 9 and
Property 5 are entirely about behaviour that must survive three topologies untouched, and Kiosk Mode's
whole reason to exist is printing slips and receipts and producing reports.

**Nothing here becomes mode-aware. That is the design.** Specifically:

- `printing/PrintService.kt`, `printing/PrinterConnectionManager.kt`, `printing/PrinterDispatcher.kt`,
  `printing/documents/*` and `BitmapTicketRenderer` are **not** modified by this spec. They already
  work with no network, over Bluetooth SPP, gated to `Role.ADMIN`.
- The `Role.ADMIN` printing gate stays exactly as it is. A LAN Client Device must **not** gain printing
  by virtue of the topology change (Requirement 9.3) — it is `ORDERING`, and `ORDERING` does not print.
- Print-time localization stays: `PrintService.localizeItemNames:47-53` re-resolves item names from the
  live menu because the backend freezes English name snapshots. In LAN and Kiosk the "live menu" is
  Room instead of a server response, but the call site does not change (Requirement 9.4).
- `ReportsViewModel.kt:141-179` already queries `OrderDao` directly. It needs **no** change, because
  the orders it reads are in the same tables whether they arrived from Supabase, from a Client Device
  over the LAN, or from the admin's own hand (Requirement 9.2).

The one genuine difference: Kiosk Mode has no tables, so anything a slip or receipt renders as a table
label uses the running order number instead (Requirement 3.5). That is a data substitution at the call
site, not a change to the rendering path.

**Verification, not inspection.** Because the code is unchanged, the risk is not that someone edits it
— it is that the *inputs* differ per mode and nobody notices until a café prints a blank table field.
So Property 5 is proven by generating a real slip, a real receipt, and a real report in each mode and
comparing them, not by observing that the printing package has no diff.

### Network provisioning in LAN Mode: who creates the wireless network

Requirement 4.3 allows either an existing WiFi router or a hotspot hosted by the Server Device. The
second case has a constraint that must be settled here rather than discovered during implementation:

> ⚠️ **Android does not let an app turn on normal hotspot tethering.** There is no public API. The
> `WifiManager.LocalOnlyHotspot` API that *is* public creates an AP with a system-generated SSID and
> password, no internet path, and a lifetime tied to the requesting app — it is designed for
> peer-to-peer accessory use, not for running a café.

So the design does **not** attempt to create the AP programmatically. Instead:

- **The operator enables the hotspot from Android system settings**, as they would to share internet.
  The Setup Wizard's LAN path tells them to do this and links straight to the system settings screen
  rather than pretending the app can do it.
- **The app detects rather than controls.** It reads the device's current IP on the active interface and
  shows it, so the operator can confirm the network is up and the pairing QR carries a reachable
  address. If no usable interface is found, that is reported plainly instead of producing a pairing QR
  pointing at nothing.
- **A hotspot needs no internet.** Both cases work with the mobile data off; the AP is only a local
  link. This is worth stating because "hotspot" colloquially implies sharing a mobile connection, and
  here it explicitly does not.
- **Address instability is expected, not exceptional.** A hotspot reassigns addresses when it restarts,
  which is exactly why Requirement 5.5's re-discovery ladder exists rather than being an optional
  refinement.

#### Prior art: this exact problem is already solved in-house

RAZStudio's StudioRoom project ships a Canon camera sync feature over **PTP/IP** with the same two
topologies — phone joins the camera's AP, or both sit on a home LAN. Its `NetworkBinder`
(`feature/canon-sync/.../net/NetworkBinder.kt`) is device-verified against Canon's own Camera Connect
app via `dumpsys connectivity`, and its findings are directly transferable here because the hard part
is not the protocol — PTP/IP is raw TCP, ours is HTTP — but **which interface the socket leaves by**.
Four findings change this design:

**1. Joining a no-internet AP is not enough; unbound traffic silently goes out over cellular.**
Android keeps a validated network (mobile data) as the process default even while the phone is
associated with the AP. A Client Device would appear connected and every request would still miss the
Server. The fix is `ConnectivityManager.bindProcessToNetwork(wifiNetwork)`, and for individual sockets
`network.bindSocket(...)` — for OkHttp, `OkHttpClient.Builder().socketFactory(network.socketFactory)`.
Release **must** call `bindProcessToNetwork(null)`, or the phone stays cut off from mobile data until
the process dies.

**2. Android 11+ auto-drops a Wi-Fi network with no validated internet after roughly 30–90 seconds.**
This is the field failure that looks like "it worked for a minute then stopped." Holding it requires a
`NetworkRequest` with a specific and non-obvious combination:

```
addTransportType(TRANSPORT_WIFI)
addCapability(NET_CAPABILITY_INTERNET)        // we do route IP traffic
removeCapability(NET_CAPABILITY_VALIDATED)    // but the captive-portal probe will fail
```

`removeCapability(INTERNET)` alone is *not* sufficient — the prior project measured the OS tearing the
network down anyway.

**3. Do not use `WifiNetworkSpecifier` / `requestNetwork` with an SSID matcher.** It forces a
disconnect-and-re-associate, which killed legacy Canon bodies mid-handshake, and it requires
`ACCESS_FINE_LOCATION` at runtime even on Android 13+ — a permission users routinely refuse. The
capability-only request above needs no location permission. Canon's own app does not use a specifier
either; it relies on the user having joined the AP in Settings, which is precisely the flow adopted
above.

**4. `activeNetwork` is the wrong handle.** With mobile data on, the active network is cellular even
when the phone is correctly on the AP. The prior project scans all connected networks for a
`TRANSPORT_WIFI` one instead of trusting `activeNetwork`.

**Which side needs which.** The two roles are not symmetric:

| Role | Situation | What it needs |
|---|---|---|
| Client (staff) | Joined someone else's AP | All four findings — bind, keep-alive, no specifier, scan for the Wi-Fi network |
| Server (admin) | *Is* the AP | Nothing. When the device hosts the AP it has no Wi-Fi network of its own to bind; the kernel routes `192.168.x.x` out of the AP interface automatically. Detect this case and skip binding entirely — the prior project does so via `WifiManager.isWifiApEnabled()`, hidden since API 26 and reached by reflection, failing closed to `false` |

**5. Re-discovery is nearly free in the hotspot case, which simplifies Requirement 5.5.** When a staff
device joins the admin's hotspot, `WifiManager.getDhcpInfo().gateway` **is the admin device** — that is
what "the AP" means. So after a hotspot restart the Client can recover its address by reading the
gateway, with no mDNS round trip and no re-scan. The discovery ladder becomes: last known address →
**DHCP gateway** → NSD/mDNS (needed only for the home-router case, where the Server is an ordinary peer
rather than the gateway) → prompt for re-scan. The prior project also keeps an informed subnet sweep,
bounded by the DHCP netmask and skipping its own IP and the gateway, as a final fallback.

**A welcome side effect.** Process-binding the Client to the LAN network enforces Requirement 11 at the
OS level: while bound, the app *cannot* reach the internet, because every socket in the process is
routed out of a link that has no upstream. That is a stronger guarantee than an application-level
interceptor, though the interceptor still earns its place for Kiosk Mode, where there is no network
binding at all.

### Setup Wizard and the restructured entry screen

The entry screen is rebuilt to the specified layout (Requirement 2.1): a prominent **Join as Ordering
mode** at the top, **Relogin as Café Admin** in the middle, then a deliberate layout gap — a spacer
that survives rotation and small screens, not merely extra styling — and a lower-prominence **Setup
Wizard** at the bottom.

The wizard opens on a three-way mode choice (Requirement 2.2). Choosing `CLOUD` collects Supabase URL,
anon key, and café name as today. Choosing `LAN` or `KIOSK` collects café name, printer setup, and
menu source, and never asks for a Supabase URL (Requirement 2.4).

## Data Models

**`AppConfigStore`** gains `operating_mode`. On applying `LAN` or `KIOSK`, any stored
`supabase_url` / `supabase_anon_key` is cleared, and `SecureStorage`'s `session_token` / `api_key` are
cleared, so no cloud credential lingers (Requirement 11.3).

**Order identity (Requirement 8.4).** Cloud Mode has the server assign order ids; this design keeps
that invariant rather than introducing client-generated ids. In LAN Mode the Server Device assigns,
so two devices cannot mint the same id — collision is structurally impossible rather than made
unlikely by a UUID.

**Kiosk order numbers (Requirement 3.5).** A dedicated Room sequence table, keyed by business day as
derived from the existing `businessDayStartHour` setting, rather than a `COUNT(*)`-derived number which
would reuse a number after a cancellation. The number is carried onto both the kitchen slip and the
receipt.

**New Room entities.** `PairedDevice` (LAN Server: device id, name, model, role, status, credential
hash, last seen) and `OrderNumberSequence` (business day, next number).

**Payment QR state — and an explicit warning about where it does *not* live.**

> ⚠️ **`branding` is an API endpoint, not a database table. There is no `Branding` Room entity and no
> `BrandingDao`, and this design does not add one.** `AppDatabase` registers exactly eight entities:
> `MenuItem`, `Order`, `OrderItem`, `SystemSettings`, `Table`, `PendingOrder`, `PrinterConfig`,
> `PrintJob`. `Branding` exists only as `BrandingResponse`, a network DTO inside `ApiClient`. Anyone
> reading "the hash is carried on `branding`" as "add a column to the branding table" will go looking
> for a table that does not exist. It travels **on the branding API payload**; where each device
> *persists* it is a separate question, answered next.

Three distinct places hold Payment QR state, and conflating them is the easy mistake:

| What | Where it lives | Why there |
|---|---|---|
| The image bytes | A file in app-private storage; additionally object storage in Cloud Mode | Images do not belong in Room as blobs, and never as base64 columns |
| The content hash + resolved URL, **in transit** | Fields on the `branding` GET/PUT payload | `branding` is fetched rarely, unlike the 30 s-polled `settings` |
| The content hash + resolved URL, **at rest on a device** | `AppConfigStore` | It is device-scoped cache state, and `AppConfigStore` already holds the café name that arrives on the same payload |

Because none of that is a Room table, **the Payment QR feature requires no Room migration.**

Absence of a hash *is* the "not configured" state that hides the **Show QR** button, so there is no
separate enabled flag that could fall out of sync with whether the image actually exists.

**Room becomes authoritative.** `menu_items`, `settings`, and `branding` are written by admin edits
directly in LAN and Kiosk rather than being refreshed from a server response.

**Migration policy (Requirement 8.1).** `AppDatabase` is currently version 10 with
`fallbackToDestructiveMigration`. That must go — not gated per mode, since the database is a single
global object and a mode-conditional migration policy would be a trap. Real migrations are written
from here on, and a missing migration fails loudly instead of silently wiping a café's takings.

**Backup (Requirement 8.2).** `data/local/DatabaseBackupManager.kt` and `BackupReminderWorker.kt`
already implement full export/import. In LAN and Kiosk they stop being optional hygiene and become the
only recovery path, so the reminder is surfaced rather than left to discovery.

**Known defect to fix, not inherit.** The existing offline outbox drops `unitPrice` and `size` when
queueing (`StaffOrderViewModel.kt:521-529`), so a replayed sized or variable-price item loses its
price. Any reliance on that queue in LAN Mode must fix this first; a lost price is a silently wrong
bill.

## Correctness Properties

### Property 1: Mode is decided in one place and read everywhere
Every topology-dependent branch resolves through `OperatingMode` and `ModeCapabilities`; no feature
infers its topology from whether a Supabase URL is non-blank, and no suppressed feature is reachable
by restored state, a deep link, or rotation.
**Validates: Requirements 1.3, 7.5**

### Property 2: A LAN Client is byte-compatible with a Cloud client
The LAN Server's paths, request bodies, response shapes, and status-code semantics — including the 401
that triggers `AuthEventBus.SessionExpired` — match the Edge Functions. Consequence: staff-side
behaviour is mode-independent, and the staff feature set needs no per-mode implementation.
**Validates: Requirements 4.2, 5.6**

### Property 3: No internet traffic originates in LAN or Kiosk Mode
Demonstrated by observation of the running app's network activity, not by code reading alone, and no
Supabase credential remains stored after the mode is applied.

> ⚠️ **A guard placed inside the backend gateway is not sufficient and will produce a false pass.**
> `RemoteBackend` is not the app's only HTTP client, and in Kiosk Mode it is not used at all. Every one
> of these can independently reach the network and must be covered:
> - `ApiClient`'s OkHttp instance (`RemoteBackend`) — absent entirely in Kiosk
> - `RealtimeService`'s own OkHttp **WebSocket** client
> - `OrderingForegroundService`'s own OkHttp **WebSocket** client (a separate instance)
> - **Coil** (`io.coil-kt:coil-compose`), which fetches menu-item and logo images by URL and would
>   happily load a leftover `https://` image URL in an offline mode
>
> The check therefore belongs at a level all of them share — a shared OkHttp `Dns` or `Interceptor`
> applied app-wide — not bolted onto one call site.

**Validates: Requirements 11.1, 11.2, 11.3**

### Property 4: The café's data survives an app upgrade
With Room authoritative, no schema change may discard rows. A missing migration is a loud failure, not
a silent wipe, and a restore path exists.
**Validates: Requirements 8.1, 8.2, 8.3**

### Property 5: Printing and reporting are mode-invariant
Slips, receipts, and reports are produced by one code path in all three modes, printing stays
admin-only, and print-time localization of item names is preserved.
**Validates: Requirements 9.1, 9.2, 9.3, 9.4**

### Property 6: An order is printed once and alerted once
Retried submissions and repeated polls cannot produce a duplicate kitchen slip or a duplicate alert,
in any mode.
**Validates: Requirements 6.4**

### Property 7: Order identity is unique — both the id and the Kiosk order number
Two distinct identifiers, one property, because both are "an order must be unambiguously identifiable":

- **Order id (LAN).** The Server Device is the sole assigner, making cross-device collision structurally
  impossible rather than merely improbable.
- **Kiosk order number (Requirement 3.5).** Drawn from a dedicated sequence inside a single Room
  transaction, so it is unique and monotonic within a business day and resets at the next one. A
  `COUNT(*)`-derived number would reuse a number after a cancellation and put two different orders on
  two slips bearing the same number — which is why the sequence table exists.

Note this is *not* Property 6. Property 6 is about printing and alerting an order exactly once; this is
about an order having exactly one identity. They are easy to conflate and are verified separately.
**Validates: Requirements 3.5, 8.4**

### Property 8: A displayed Payment QR is scannable, current, and unaltered
Scannable: an image is only ever stored after ZXing confirms it decodes. Unaltered: a re-encoded copy is
kept only if its decoded payload matches the original byte-for-byte, and nothing in the app encodes an
amount or order reference into the code. Current: caching is keyed by content hash, so no device can
display a superseded QR after the admin replaces or removes it. Together these close the three ways
this feature could send a customer's money astray — an unreadable code, an altered payload, and a stale
payee. This property carries unusual weight precisely because there is no audit trail (Requirement
14.10) to catch a failure after the fact.
**Validates: Requirements 14.3, 14.4, 14.5, 14.6, 14.11**

### Property 9: The Payment QR is mode-independent
Its availability is a function of whether an image is configured, never of the operating mode, and it
is exempt from Requirement 7's Cloud-Only suppression despite being an image.
**Validates: Requirements 13.9, 14.7, 14.8**

## Error Handling

- **Server Device unreachable (LAN).** The Client names the local connection as the fault — not the
  internet, not Supabase — and offers retry and re-discovery. Requirement 4.5 exists because the
  current error strings assume a cloud backend and would actively mislead here.
- **Server Device address changed.** Handled by the discovery ladder above without re-approval; the
  Client must not silently appear healthy while unable to submit orders.
- **Port already in use on the Server Device.** Reported to the operator with the port named, since a
  silent bind failure would present as every Client being unable to pair.
- **Client submits while the Server is briefly away.** Either queued in a fixed outbox or refused
  cleanly with the order preserved for retry — never accepted-looking-but-lost. Note the
  `unitPrice`/`size` defect above must be fixed before the queue is trusted.
- **Migration failure.** Fails loudly and leaves the existing database intact, rather than proceeding
  destructively.
- **No printer configured.** Unchanged behaviour: the job queues and a `PrintAlert` is raised via
  `printing/PrinterDispatcher.kt`.
- **Uploaded Payment QR does not decode.** Rejected at upload with a reason that distinguishes "no QR
  found in this image" from "file could not be read", so the admin knows whether to retake the photo or
  pick a different file. Nothing is stored, and any previously working QR is left intact.
- **Re-encoding would degrade the Payment QR.** The original is kept rather than the re-encoded copy;
  the feature never silently trades scannability for file size.
- **Payment QR cannot be fetched on a staff device.** If a cached copy exists it is shown, since the
  cache is what makes Requirement 13.9 hold. If none exists, the **Show QR** button is absent rather
  than present-and-broken — a button that fails in front of a waiting customer is worse than no button.
- **Mode change requested on a configured device.** Blocked behind an explicit statement of
  consequences and a confirmation; no partial cloud↔local data transfer is attempted, since
  Requirement 10.3 puts that out of scope entirely.

## Testing Strategy

Requirement 12 is the binding constraint: this feature's real failure modes are physical, so device
testing is a prerequisite rather than a follow-up.

> ⚠️ **Read this section as a task list, not as advice.** Every numbered item below is work that has to
> be scheduled and done by a person with hardware in their hands. None of it is satisfied by unit
> tests, and none of it can be inferred from a green build. An implementation plan that omits these has
> omitted the part of the spec most likely to catch a real defect.

**V1. Two physical devices on a Server-hosted hotspot** *(Requirement 12.1)* — pair, approve, place an
order from the Client, confirm it appears and auto-prints on the Server; then restart the hotspot so the
address changes and confirm the Client re-attaches without re-approval.
**V2. Kiosk with networking fully off** *(Requirement 12.2)* — airplane mode, WiFi and mobile data
disabled: order entry, kitchen slip, receipt, report.
**V3. Cloud Mode regression** *(Requirement 12.3)* — this spec edits `ApiClient`, `AppDatabase`, and
both realtime services, all of which Cloud Mode uses.
**V4. Release build on a device** *(Requirement 12.4)* — not debug. Precedent: adding the AdMob SDK
produced a `VerifyError` that crashed only the R8 release build and was invisible in debug. An embedded
HTTP server plus reflection-based serialization is exactly the shape of code a minifier breaks, so
keep-rules must be proven against an installed release APK.
**V5. Payment QR scanned by a real banking app on a second phone** *(Requirement 12.5)* — in all three
modes. A rendered QR and a scannable QR are not the same thing, and no amount of decode-path unit
testing substitutes for one phone reading another's screen in room light.
**V6. Payment QR replacement propagation** — upload A, confirm a staff device shows A, replace with B,
confirm the staff device shows B and never A again. Property 8's staleness half, and the
highest-consequence path in that feature.
**V7. Payment QR with the network cut** after caching (display still works, Requirement 13.9), and with
no cache present (button absent, not broken).
**V8. Migration over a populated database** *(Property 4)* — install the previous version, create real
orders, upgrade, confirm every order survives. Property 4 is about not losing data that is *already
there*, so a migration test on an empty database proves nothing.
**V9. Printing and reporting compared across modes** *(Property 5, Requirement 9)* — generate a kitchen
slip, a receipt, and a report in each of the three modes and compare them. The printing code is
unchanged, so the risk is not a bad edit; it is that the inputs differ per mode and nobody notices.

Alongside V1–V9, the ordinary automated layer — unit and property tests for the sequence counter, the
capability table, the `LocalBackend` order lifecycle, `LanServer` route dispatch, poll de-duplication,
`PaymentQrPipeline` decode/payload-equality, and hash-keyed cache invalidation; plus an instrumented
test for Property 3's network guard, which needs a real Android runtime to observe real sockets.

**Where the automated layer stops.** It can prove a QR decodes; it cannot prove a phone camera reads it
off a screen. It can prove a poll de-duplicates; it cannot prove two devices on a flaky hotspot
converge. Everything in V1–V9 exists precisely because it lives past that boundary.
