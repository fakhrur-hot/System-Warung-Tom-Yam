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

```kotlin
data class ModeCapabilities(
    val customerQrOrdering: Boolean,  val printableQrSheets: Boolean,
    val tables: Boolean,             val staffDevices: Boolean,
    val secondaryAdmin: Boolean,     val websiteInvites: Boolean,
    val cloudImageHosting: Boolean,  val realtimeWebSocket: Boolean,
)
```

`CLOUD` = all true. `LAN` = tables/staffDevices true, everything cloud-shaped false. `KIOSK` = all
false. Navigation, menus, and settings read this object, so adding a fourth mode later touches one
table (Requirement 1.3, 7.5).

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

**Payment QR state.** The image itself is a file, never a database blob or a base64 column: app-private
storage on the device, plus object storage in Cloud Mode. What is stored as data is small — the content
hash and the resolved URL, carried on `branding` and cached per device so a staff device can tell
"unchanged" from "replaced" without downloading the image to find out. Absence of a hash *is* the
"not configured" state that hides the **Show QR** button, so there is no separate enabled flag to fall
out of sync with the image's actual presence.

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

### Property 7: Order ids cannot collide
The Server Device is the sole assigner of order identity in LAN Mode, making cross-device collision
structurally impossible.
**Validates: Requirements 8.4**

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

- **Two-device LAN, on a hotspot hosted by the Server Device.** Pair, approve, place an order from the
  Client, confirm it appears and auto-prints on the Server. Then restart the hotspot to change the
  address and confirm the Client re-attaches without re-approval.
- **Kiosk with networking fully disabled** (airplane mode, WiFi and mobile data off). Order entry,
  kitchen slip, receipt, and report — proving no path silently depends on a reachable host.
- **Cloud regression.** Re-verify Cloud Mode after the work, since this spec edits `ApiClient`,
  `AppDatabase`, and the realtime services, all of which Cloud Mode also uses.
- **Release build, not just debug** (Requirement 12.4). This is not theoretical: adding the AdMob SDK
  produced a `VerifyError` that crashed only the R8 release build and was invisible in debug. An
  embedded HTTP server plus reflection-using serialization is exactly the shape of code a minifier
  breaks, so keep-rules must be verified against an installed release APK.
- **Network observation** for Property 3, using logcat or a proxy against the running app rather than
  a code audit.
- **Migration tests** over a populated database, since Property 4 is about not losing data that is
  already there.
- **Payment QR scanned by a real banking app on a second phone**, in all three modes (Requirement 12.5).
  A rendered-looking QR and a scannable QR are not the same thing, and no amount of unit testing on the
  decode path substitutes for one phone reading another phone's screen at arm's length in room light.
- **Payment QR replacement propagation**: upload QR A, confirm a staff device shows A, replace with B on
  the admin device, confirm the staff device shows B and never A again. This is Property 8's staleness
  half and the highest-consequence path in the feature.
- **Payment QR with the network cut** after caching, confirming display still works (Requirement 13.9),
  and with no cache present, confirming the button is absent rather than broken.
