# Implementation Plan: Operating Modes (Cloud / LAN / Kiosk)

## Overview

Three topologies — `CLOUD`, `LAN`, `KIOSK` — selected once in the Setup Wizard and fixed
thereafter. The implementation follows the seam `ApiClient` already exposes via `DemoBackend`:
extract a `BackendGateway` interface, provide `RemoteBackend` (today's calls, unchanged) and a new
`LocalBackend` (Room-backed), then wire the right implementation at construction time based on the
persisted `OperatingMode`. Everything above `ApiClient` changes only where mode-specific suppression
or new UI is required.

Tasks are ordered so each step compiles and runs incrementally. The database migration guard (task 1)
goes first because every subsequent step depends on data durability.

## Tasks

- [ ] 1. Harden the database and add new Room entities
  - [ ] 1.1 Remove `fallbackToDestructiveMigration` from `AppDatabase`; bump schema version;
    write an explicit Room `Migration` for every existing migration gap so the build still compiles
    and an upgrade of an existing install does not wipe data
    - _Requirements: 8.1_
  - [ ] 1.2 Add `PairedDevice` entity and `PairedDeviceDao` to `AppDatabase` (fields: `id`,
    `name`, `model`, `role`, `status`, `credentialHash`, `lastSeenMs`); add the corresponding
    Room `@Entity` and `@Dao`
    - _Requirements: 5.3, 5.6_
  - [ ] 1.3 Add `OrderNumberSequence` entity and `OrderNumberSequenceDao` to `AppDatabase`
    (fields: `businessDay` key, `nextNumber`); provide an `@Transaction` method that atomically
    reads-and-increments so concurrent access within a device cannot duplicate a number
    - _Requirements: 3.5_
  - [ ]* 1.4 Write unit tests for `OrderNumberSequence` increment: verify monotonicity and
    uniqueness within a business day boundary, and that a new day resets the counter
    - **Property 7 (order-number half): order identity is unique — monotonic within a business day**
    - **Validates: Requirements 3.5**
  - [ ] 1.5 Write a Room migration test over a **populated** database: build the previous schema
    version, insert real orders/items/settings, run the migration from task 1.1, and assert every
    row survives with its fields intact. An empty-database migration test proves nothing about the
    property this guards — Room's own `MigrationTestHelper` supports the populated case
    - **Property 4: the café's data survives an app upgrade**
    - **Validates: Requirements 8.1, 12.6**

- [ ] 2. Persist `OperatingMode` and expose `ModeCapabilities`
  - [ ] 2.1 Add `OperatingMode` enum (`CLOUD`, `LAN`, `KIOSK`) in `data/OperatingMode.kt`; add
    `operating_mode` key to `AppConfigStore` (EncryptedSharedPreferences, file
    `app_config_prefs`), defaulting to `CLOUD` when absent so existing installs are unaffected
    - _Requirements: 1.1, 1.2_
  - [ ] 2.2 Add `ModeCapabilities` data class in `data/ModeCapabilities.kt` with boolean fields
    `customerQrOrdering`, `printableQrSheets`, `tables`, `staffDevices`, `secondaryAdmin`,
    `websiteInvites`, `cloudImageHosting`, `realtimeWebSocket` (all eight — see the capability table
    in design.md; `secondaryAdmin` is false for LAN/KIOSK, which is how ADMIN_SECONDARY stays out of
    scope); add a `fun OperatingMode.toCapabilities()` factory
    that sets all true for `CLOUD`, partially true for `LAN`, all false for `KIOSK`
    - _Requirements: 1.3, 7.5_
  - [ ] 2.3 Expose `activeMode: StateFlow<OperatingMode>` and `capabilities: StateFlow<ModeCapabilities>`
    from a new `ModeRepository` (or extend `AppConfigStore`); inject it into `PosApp` / Hilt graph
    so every ViewModel that needs it can read it without importing a side channel
    - _Requirements: 1.3_
  - [ ]* 2.4 Write property test for `ModeCapabilities`: for every `OperatingMode` value, assert
    no field in `toCapabilities()` is inconsistent with the invariants stated in the design
    (CLOUD = all true; KIOSK = none true; LAN = only `tables` and `staffDevices` true)
    - **Property 1: mode is decided in one place and read everywhere**
    - **Validates: Requirements 1.3, 7.5**

- [ ] 3. Extract `BackendGateway` interface and wire `RemoteBackend`
  - [ ] 3.1 Extract an interface `BackendGateway` in `data/BackendGateway.kt` whose methods mirror
    the public API surface of `ApiClient` (every method currently called by a ViewModel or
    Service); add it to the Hilt module so existing code compiles against the interface
    - _Requirements: 4.2_
  - [ ] 3.2 Rename today's `ApiClient` implementation to `RemoteBackend` (or make `ApiClient`
    implement `BackendGateway`); confirm the app still builds and all Cloud Mode paths are
    untouched — this step must be a pure refactor with no behaviour change
    - _Requirements: 1.2_
  - [ ] 3.3 In the Hilt `@Provides` for `BackendGateway`, read `ModeRepository.activeMode` and
    inject `RemoteBackend` for `CLOUD` and for LAN Client devices; leave `LocalBackend` as a
    stub (throws `NotImplementedError`) for now — the binding just needs to compile
    - _Requirements: 4.2_

- [ ] 4. Implement `LocalBackend` — core order and menu endpoints
  - [ ] 4.1 Create `data/local/LocalBackend.kt` implementing `BackendGateway`; implement the
    `orders` create and `?since=` poll endpoints against Room's `OrderDao`, with the Server
    Device as sole id assigner (use Room `@PrimaryKey(autoGenerate = true)` or a sequence so
    no client can mint an id)
    - _Requirements: 3.1, 4.1, 8.4_
  - [ ] 4.2 Implement `orders-kitchen`, `orders-items`, `orders-status`, `orders-payment`, and
    `orders-cancel` endpoints in `LocalBackend` via the existing `OrderActions.kt` state machine
    — no new state logic, just route the calls
    - _Requirements: 3.1, 4.1_
  - [ ] 4.3 Implement `menu` GET/PUT and `menu-image` POST/DELETE in `LocalBackend`; store menu
    images in app-private storage (not Supabase Storage); write a `LocalImageStore.kt` utility
    that manages file paths and returns local `file://` URIs
    - _Requirements: 4.4, 7.3_
  - [ ] 4.4 Implement `settings` GET/PUT in `LocalBackend`; suppress cloud-web-only keys
    (`qrOrderingEnabled`, `website_url`, etc.) from the response — read `ModeCapabilities` to
    decide which keys to omit
    - _Requirements: 4.4, 7.4_
  - [ ] 4.5 Implement `sessions` (open/close) and `aggregates` (daily aggregate) in
    `LocalBackend`, writing directly to Room; implement `tables` GET/PUT for LAN, returning an
    empty table list (no `qrToken`) for Kiosk
    - _Requirements: 3.2, 4.4_
  - [ ]* 4.6 Write unit tests for `LocalBackend` order lifecycle: create → kitchen → payment →
    closed, verifying `OrderActions` state transitions are preserved and order ids do not repeat
    - **Property 7: order ids cannot collide (single-device path)**
    - **Validates: Requirements 8.4**

- [ ] 5. Implement `LocalBackend` — pairing and device management endpoints
  - [ ] 5.1 Implement `register` in `LocalBackend`: validate the `pairingToken` (expiry +
    single-use), write a new `PairedDevice` row with `status = PENDING`, return a durable
    credential; implement `devices-status` to return the approval state so the existing
    `PendingApprovalScreen` polling loop works unmodified
    - _Requirements: 5.1, 5.3_
  - [ ] 5.2 Implement `devices` GET (list) and `devices/{id}` DELETE (revoke) in `LocalBackend`
    so the existing `DevicesScreen` / `DevicesViewModel` works against the local backend in LAN
    Mode; implement `invite` GET (current pairing code) and `invite/regenerate` POST (rotate
    token without disturbing approved devices)
    - _Requirements: 5.4, 5.6_
  - [ ] 5.3 Fix the `unitPrice`/`size` drop in `StaffOrderViewModel.kt:521-529` before the
    outbox queue is trusted: include `unitPrice` and `size` in every queued `PendingOrder` so a
    replayed item carries its correct price. Known defect, not a new requirement — design records it
    under "Known defect to fix, not inherit"; a lost price is a silently wrong bill
    - _Requirements: 4.1_

- [ ] 6. Implement `LanServer` — embedded HTTP listener
  - [ ] 6.1 Add Ktor (`ktor-server-cio`, `ktor-server-core`, `ktor-serialization-kotlinx-json`)
    to `build.gradle.kts`; add ProGuard keep-rules for Ktor's reflection-using internals to
    `proguard-rules.pro`; confirm `./gradlew assembleRelease` succeeds
    - _Requirements: 4.2_
  - [ ] 6.2 Create `data/lan/LanServer.kt`: bind to the device's LAN IP on a fixed port; serve
    routes under `/functions/v1/…` matching the `LocalBackend` endpoints; accept and ignore the
    `apikey` header; reproduce the 401 contract so `AuthEventBus.SessionExpired` fires on a
    revoked device exactly as it does in Cloud Mode
    - _Requirements: 4.2, 5.3_
  - [ ] 6.3 Start and stop `LanServer` from the existing admin foreground service
    (`realtime/RealtimeService.kt`) so it shares that service's lifetime and wake lock; gate
    startup behind `mode == LAN && role == ADMIN`
    - _Requirements: 4.1, 4.6_
  - [ ]* 6.4 Write unit tests for `LanServer` route dispatch: verify that every path served
    delegates to the matching `LocalBackend` method and that a 401 response triggers
    `AuthEventBus.SessionExpired` on the client side
    - **Property 2: a LAN Client is byte-compatible with a Cloud client**
    - **Validates: Requirements 4.2, 5.6**

- [ ] 7. Pairing and discovery — client-side flow
  - [ ] 7.1 Create `data/lan/PairingQrPayload.kt` (serializable: `host`, `port`, `pairingToken`);
    on the Server Device, generate a QR code encoding this JSON using `QrCodeUtil` (already used
    in the timezone-onboarding spec) and display it in a new `LanPairingScreen.kt`
    - _Requirements: 5.1_
  - [ ] 7.2 On the Client Device, wire the existing `QrScannerScreen` to decode a pairing QR;
    on decode, call `LocalBackend.register` at the scanned host:port with the `pairingToken`,
    then enter the existing `PendingApprovalScreen` polling loop; add a "Enter address manually"
    fallback that prompts for host and port
    - _Requirements: 5.1, 5.2_
  - [ ] 7.3 Implement address-change recovery: on connection failure, try the last known address;
    if that fails, run `NsdManager` mDNS discovery searching for the LAN service type; if that
    fails, prompt for a re-scan; in all three branches the durable credential is preserved and
    no re-approval is required
    - _Requirements: 5.5_

- [ ] 8. Realtime polling — disable WebSocket in LAN/Kiosk, tune poll interval
  - [ ] 8.1 Gate Supabase Realtime WebSocket startup in `RealtimeService.kt` and
    `OrderingForegroundService.kt` behind `ModeCapabilities.realtimeWebSocket`; when false,
    skip the `supabaseUrl().replace(…)` construction entirely so no retrying connection is
    opened against an unusable URL
    - _Requirements: 6.2, 11.1_
  - [ ] 8.2 In the LAN poll branch, reduce `POLL_INTERVAL_MS` to a shorter same-subnet interval
    (e.g. 3 s); keep the existing `maybeNotifyNewOrders` / `autoPrintFromSync` call sites
    unchanged so new-order alerts and kitchen auto-print work via polling in LAN Mode, exactly
    as they already do in Cloud Mode
    - _Requirements: 6.1, 6.3_
  - [ ]* 8.3 Write unit tests for de-duplication: feed the same order id into `maybeNotifyNewOrders`
    twice and assert exactly one print job and one notification are produced
    - **Property 6: an order is printed once and alerted once**
    - **Validates: Requirements 6.4**

- [ ] 9. Restructure entry screen and Setup Wizard
  - [ ] 9.1 Rewrite `RoleSelectScreen.kt` to the three-action layout: **Join as Ordering mode**
    at top, **Relogin as Café Admin** in middle, then a `Spacer` (not just padding) creating a
    real layout gap, then a lower-prominence **Setup Wizard** at the bottom — gap must survive
    rotation and small screens
    - _Requirements: 2.1, 2.5_
  - [ ] 9.2 Add a mode-choice step to the Setup Wizard (`SetupScreen.kt` /
    `SetupViewModel.kt`): three options labelled **Full Online with QR ordering**, **(W)LAN AP
    without QR ordering**, **Kiosk Mode**; route `CLOUD` to the existing Supabase credential
    flow; route `LAN`/`KIOSK` to a café-name + printer-setup flow that never asks for a
    Supabase URL
    - _Requirements: 2.2, 2.3, 2.4_
  - [ ] 9.3 On completing `LAN`/`KIOSK` setup, clear any stored `supabase_url`,
    `supabase_anon_key`, and `SecureStorage` `session_token` / `api_key`; persist the selected
    `OperatingMode` via `AppConfigStore`
    - _Requirements: 1.1, 11.3_
  - [ ] 9.4 Add an active-mode badge to `AdminHomeScreen` (or its top-level scaffold) displaying
    the current mode (`CLOUD` / `LAN` / `KIOSK`) so the operator can see it without entering
    Setup
    - _Requirements: 1.4_

- [ ] 10. Mode-change guard and deliberate re-provisioning
  - [ ] 10.1 When Setup Wizard is entered on an already-configured device, detect that an
    `OperatingMode` is already persisted and show a confirmation dialog stating exactly what
    will be lost (orders, credentials) and what will become unavailable before writing anything;
    the user must explicitly confirm
    - _Requirements: 10.1, 10.2_
  - [ ] 10.2 Ensure the confirmation dialog (10.1) uses `ModeCapabilities` to enumerate what
    becomes unavailable for the new mode, rather than hardcoding strings — so a future fourth
    mode gets correct messaging without a code change
    - _Requirements: 10.3_

- [ ] 11. Suppress Cloud-Only Features in LAN and Kiosk Mode
  - [ ] 11.1 Gate customer QR ordering entry points and `QrPdfScreen` / `QrPdfViewModel` behind
    `ModeCapabilities.customerQrOrdering` and `ModeCapabilities.printableQrSheets`; gate
    website-based invite links in `DevicesScreen` behind `ModeCapabilities.websiteInvites`
    - _Requirements: 7.1, 7.2_
  - [ ] 11.2 Gate table selection, the table grid, and `TableManagementScreen` behind
    `ModeCapabilities.tables`; in Kiosk Mode replace table-based order identification with the
    locally generated order number from `OrderNumberSequenceDao`
    - _Requirements: 3.2, 3.5, 7.1_
  - [ ] 11.3 Gate Supabase Storage image upload in `MenuViewModel` and `AdminSettingsViewModel`
    behind `ModeCapabilities.cloudImageHosting`; route image storage through `LocalImageStore`
    instead in LAN/Kiosk Mode
    - _Requirements: 7.3_
  - [ ] 11.4 Hide cloud-web-only settings fields in `AdminSettingsScreen` when
    `ModeCapabilities` marks them unavailable; verify that the hidden settings are not reachable
    by rotation, back-stack restore, or deep link
    - _Requirements: 7.4, 7.5_
  - [ ]* 11.5 Write property test for capability-gated navigation: for each capability field set
    to false, assert that the corresponding route is unreachable from any navigation entry point
    without mutating `ModeCapabilities`
    - **Property 1: mode is decided in one place and read everywhere**
    - **Validates: Requirements 1.3, 7.5**

- [ ] 12. Checkpoint — LAN/Kiosk core paths work end-to-end
  - Ensure all unit tests pass. Manually verify on a single device: Kiosk setup, order creation
    with running order number on slip, printing, and report. Ask the user if questions arise.

- [ ] 13. Data durability: backup surface and data-loss warnings
  - [ ] 13.1 In LAN and Kiosk Mode, surface `BackupScreen` / `BackupViewModel` more prominently
    (e.g. a persistent banner on `AdminHomeScreen`) and trigger `BackupReminderWorker` on a
    shorter schedule, making the need to back up evident rather than discoverable
    - _Requirements: 8.2_
  - [ ] 13.2 Add a warning dialog before any operation that would clear local data
    (`clearOrders`, session close, menu reset) in LAN/Kiosk Mode, naming what will be lost;
    check all call sites of `OrderDao.deleteAll()` or equivalent and guard each one
    - _Requirements: 8.3_

- [ ] 14. Error messaging — LAN connection failures
  - [ ] 14.1 Intercept `IOException` / timeout from `RemoteBackend` when `mode == LAN` and the
    host is a local IP; map it to a new `ErrorType.LAN_SERVER_UNREACHABLE` and display a
    message naming the local connection ("Cannot reach the admin device") rather than the
    existing Supabase-flavoured strings
    - _Requirements: 4.5_
  - [ ] 14.2 When the Server Device fails to bind on its port (port-in-use), report the specific
    port number to the operator via a visible error rather than silently failing
    - _Requirements: 4.5_

- [ ] 15. Payment QR — `PaymentQrPipeline` and storage
  - [ ] 15.1 Create `ui/util/PaymentQrPipeline.kt`: accept JPEG and PNG; keep PNG lossless; cap
    dimensions generously; decode the candidate image with ZXing (`com.google.zxing:core:3.5.3`
    already in deps) to confirm a QR is present before storing; if a re-encode is needed,
    decode the stored result and compare payload byte-for-byte with the original — keep the
    original if they differ
    - _Requirements: 14.2, 14.3, 14.4_
  - [ ] 15.2 Store the processed image as a file in app-private storage; persist its content hash and
    resolved URL in `AppConfigStore` (device-scoped cache state, alongside the café name that arrives
    on the same payload), and add `paymentQrHash` / `paymentQrUrl` fields to the `branding` GET/PUT
    payload. NOTE: `branding` is an API endpoint, not a Room table — there is no `Branding` entity and
    no `BrandingDao`, so this task adds **no** Room migration
    - _Requirements: 14.8, 14.9_
  - [ ] 15.3 Implement `branding` GET/PUT in `LocalBackend` for LAN and Kiosk: serve the
    Payment QR at `http://<server>:<port>/media/payment-qr.<ext>` in LAN Mode; read directly
    from file in Kiosk Mode; verify ZXing can decode the served image (not just the stored file)
    - _Requirements: 14.8_
  - [ ]* 15.4 Write property tests for `PaymentQrPipeline`:
    - **Property 8a: a stored QR always decodes** — for any accepted input, `ZXing.decode(store(input))` returns a result
    - **Property 8b: payload is preserved** — `decode(store(input)).text == decode(input).text`
    - **Property 8c: pipeline rejects non-QR images** — a random JPEG with no QR code is rejected
    - **Validates: Requirements 14.3, 14.4**

- [ ] 16. Payment QR — admin upload UI and cache invalidation
  - [ ] 16.1 Add a **Payment QR** section to `AdminSettingsScreen`: upload button (image picker,
    JPEG/PNG), current image thumbnail, replace and remove actions; wire through
    `AdminSettingsViewModel` and `PaymentQrPipeline`; show a clear error if the image contains
    no QR code
    - _Requirements: 14.1, 14.3_
  - [ ] 16.2 On upload or removal, write the new hash (or null) to the `branding` record and
    invalidate the per-device cache; in Cloud Mode push to Supabase Storage as `menu-image`
    already does; in LAN Mode the file is on the Server Device and the hash propagates via
    `branding` GET
    - _Requirements: 14.5, 14.6_
  - [ ] 16.3 Implement the per-device Payment QR cache (keyed by content hash): on `branding`
    fetch, if `payment_qr_hash` differs from the cached hash (or is null), refetch or clear the
    local file; ensure the cache is populated before the **Show QR** button becomes visible
    - _Requirements: 13.9, 14.5, 14.6, 14.8_
  - [ ]* 16.4 Write unit tests for cache invalidation: upload QR A, assert hash A is cached;
    upload QR B, assert cache switches to B and never returns A; remove QR, assert cache is
    empty and `payment_qr_hash` is null
    - **Property 8 (staleness half): caching is keyed by content hash**
    - **Validates: Requirements 14.5, 14.6**

- [ ] 17. Payment QR — `PaymentQrDialog` and `Show QR` button
  - [ ] 17.1 Create `ui/components/PaymentQrDialog.kt`: full-image modal over a dark scrim,
    sized to fill as much of the dialog as the image's aspect ratio allows; no order total, no
    table, no timer, no animation; dismissed only by an explicit close action
    - _Requirements: 13.4, 13.5, 13.8_
  - [ ] 17.2 While `PaymentQrDialog` is open, hold a `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`
    wake lock so the screen cannot sleep mid-scan; do NOT set `screenBrightness` —
    leave it at `BRIGHTNESS_OVERRIDE_NONE`; suppress ambient/screensaver display via
    `AmbientSettingsStore` or the existing idle-detector mechanism
    - _Requirements: 13.6, 13.7_
  - [ ] 17.3 Add a **Show QR** action (outlined button, visually subordinate) directly below the
    Pay Cash / Pay QR row in `ui/tableview/OrderDetailSheet.kt` inside the same
    `if (permissions.canTakePayment && OrderActions.canTakePayment(order.status))` guard;
    visibility is `paymentQrHash != null` — never mode-dependent; show nothing when no image is
    configured (do not show a disabled button)
    - _Requirements: 13.1, 13.2, 13.3_
  - [ ]* 17.4 Write unit tests for `PaymentQrDialog` visibility rules: when `paymentQrHash` is
    null the button is absent; when non-null and `canTakePayment` is true the button is present
    regardless of `OperatingMode`
    - **Property 9: Payment QR is mode-independent**
    - **Validates: Requirements 13.3, 13.9, 14.7**

- [ ] 18. No internet contact verification in LAN and Kiosk Mode
  - [ ] 18.1 Add a shared, app-wide OkHttp `Dns`/`Interceptor` guard that throws when `mode != CLOUD`
    and the resolved host is not a LAN address. It MUST be applied to every HTTP client, not just the
    gateway: `RemoteBackend`'s client (absent entirely in Kiosk), `RealtimeService`'s WebSocket client,
    `OrderingForegroundService`'s separate WebSocket client, and **Coil**'s image loader — a leftover
    `https://` menu-image URL would otherwise leak past a gateway-only guard. Wire it so instrumented
    tests can enable it to assert Property 3
    - _Requirements: 11.1, 11.2, 11.2.1_
  - [ ]* 18.2 Write an instrumented test (Android test, not unit test) that sets `mode = KIOSK`,
    enables the network-intercept guard, performs a full order lifecycle, and asserts zero
    internet-routable requests were made
    - **Property 3: no internet traffic originates in LAN or Kiosk Mode**
    - **Validates: Requirements 11.1, 11.2, 11.3**

- [ ] 19. Checkpoint — full mode matrix passes all automated tests
  - Run `./gradlew test` and `./gradlew assembleRelease`; confirm no regressions in Cloud Mode
    paths, all new unit and property tests pass, and the release APK installs without
    `VerifyError`. Ask the user if questions arise.

- [ ] 20. Printing and reporting stay mode-invariant
  - [ ] 20.1 Verify by inspection that no file under `printing/` branches on `OperatingMode` or
    `ModeCapabilities`, and add none. The subsystem is already offline-only (Bluetooth SPP) and
    already `Role.ADMIN`-gated; this task is about *not* making it mode-aware
    - _Requirements: 9.1, 9.5_
  - [ ] 20.2 Confirm the `Role.ADMIN` printing gate in `PrintService.kt:59-60` and
    `PrinterConnectionManager.kt:82-83` is unchanged, so a LAN Client Device cannot acquire printing
    by virtue of the topology change
    - _Requirements: 9.3_
  - [ ] 20.3 In Kiosk Mode, substitute the running order number wherever a slip or receipt renders a
    table label — a data substitution at the call site, not a change to the rendering path; confirm
    `PrintService.localizeItemNames:47-53` still re-localizes item names from the Room-backed menu
    - _Requirements: 3.5, 9.4_
  - [ ] 20.4 Confirm `ReportsViewModel.kt:141-179` needs no change and produces the same figures in
    all three modes, since it already reads `OrderDao` directly regardless of how orders arrived
    - _Requirements: 3.3, 9.2, 9.6_

- [ ] 21. LAN network provisioning — the operator creates the hotspot, the app detects it
  - [ ] 21.1 In the Setup Wizard's LAN path, instruct the operator to enable the hotspot from Android
    settings and provide an action that opens that settings screen. Do NOT attempt programmatic
    tethering: there is no public API, and `WifiManager.LocalOnlyHotspot` gives a system-named AP with
    no internet path and an app-scoped lifetime — unsuitable for running a café
    - _Requirements: 4.3, 4.3.1_
  - [ ] 21.2 Detect and display the Server Device's current IP on the active interface so the operator
    can confirm the network is up before pairing; verify both paths work with mobile data off
    - _Requirements: 4.3.2, 4.3.4_
  - [ ] 21.3 When no usable interface is found, report it plainly and refuse to generate a pairing QR
    carrying an unreachable address
    - _Requirements: 4.3.3_
  - [ ] 21.4 Port `NetworkBinder` from StudioRoom's canon-sync module
    (`feature/canon-sync/.../net/NetworkBinder.kt`) — device-verified there against Canon Camera
    Connect via `dumpsys connectivity`. On a **Client** Device: locate the `TRANSPORT_WIFI` network by
    scanning all connected networks rather than trusting `activeNetwork` (which is cellular whenever
    mobile data is on), then `bindProcessToNetwork` it and apply `network.socketFactory` to the OkHttp
    client. Adapt from raw PTP/IP sockets (`network.bindSocket`) to OkHttp
    - _Requirements: 4.3.5_
  - [ ] 21.5 Hold the no-internet network against Android 11+'s auto-drop with a `NetworkRequest` of
    exactly `addTransportType(WIFI) + addCapability(INTERNET) + removeCapability(VALIDATED)`.
    `removeCapability(INTERNET)` alone is **not** sufficient — the prior project measured the OS
    tearing the network down anyway after ~30–90 s. This is the failure that presents as "worked for a
    minute, then stopped"
    - _Requirements: 4.3.6_
  - [ ] 21.6 On teardown, call `bindProcessToNetwork(null)` and unregister the callback, so the
    operator's phone is not left without mobile data until the process dies
    - _Requirements: 4.3.7_
  - [ ] 21.7 Do NOT use `WifiNetworkSpecifier` / `requestNetwork` with an SSID matcher: it forces a
    disconnect-and-re-associate and demands `ACCESS_FINE_LOCATION` at runtime even on Android 13+.
    Verify no location permission is added to the manifest for LAN Mode
    - _Requirements: 4.3.8_
  - [ ] 21.8 On the **Server** Device, detect the phone-is-the-AP case via `WifiManager.isWifiApEnabled()`
    (hidden since API 26 — reflection, failing closed to `false`) and skip binding entirely; the kernel
    already routes `192.168.x.x` out of the AP interface
    - _Requirements: 4.3.9_
  - [ ] 21.9 Implement the re-discovery ladder in the order the prior project's findings imply: last
    known address → `WifiManager.getDhcpInfo().gateway` (in hotspot mode the gateway **is** the Server
    Device, so this alone recovers from a hotspot restart) → NSD/mDNS for the shared-router case →
    prompt to re-scan. This supersedes the mDNS-first ladder in task 7.3
    - _Requirements: 5.5, 5.5.1, 5.5.2_

- [ ] 22. Physical-device verification gate (Requirement 12 — hardware in hand, not a test suite)
  - This group exists so the verification work is scheduled rather than assumed. None of it is
    satisfied by a passing test suite or a green build; each item needs a person, two phones, a
    hotspot, and a printer
    - _Requirements: 12.8_
  - [ ] 22.1 **V1** Two physical devices on a Server-hosted hotspot: pair, approve, place an order from
    the Client, confirm it appears and auto-prints on the Server; then restart the hotspot so the
    address changes and confirm the Client re-attaches without re-approval
    - _Requirements: 12.1_
  - [ ] 22.2 **V2** Kiosk with airplane mode on and WiFi/mobile data off: order entry, kitchen slip,
    receipt, report — proving no path silently depends on a reachable host
    - _Requirements: 12.2, 3.1, 3.4_
  - [ ] 22.1b **V1b — the soak test, with mobile data ON.** Two devices on the Server's hotspot, staff
    device left idle on the order screen for **at least 10 minutes**, then place an order. This is the
    single most important LAN test: with mobile data on, an unbound socket goes out over cellular and
    silently never reaches the Server, and Android 11+ drops a no-internet Wi-Fi after ~30–90 s. Both
    failures pass a 30-second smoke test and fail in a real café. Confirm also that mobile data still
    works on the staff device after leaving LAN Mode
    - _Requirements: 4.3.5, 4.3.6, 4.3.7_
  - [ ] 22.3 **V3** Cloud Mode regression, since this spec edits `ApiClient`, `AppDatabase`, and both
    realtime services — all of which Cloud Mode also uses
    - _Requirements: 12.3, 1.2_
  - [ ] 22.4 **V4** Install and exercise the **release** (R8) APK on a device, not just debug. An
    embedded HTTP server plus reflection-based serialization is exactly what a minifier breaks;
    precedent is the AdMob `VerifyError` that crashed only the release build
    - _Requirements: 12.4_
  - [ ] 22.5 **V5** Scan the on-screen Payment QR with a real banking app on a second phone, in all
    three modes. **V6** Replacement propagation: upload A, confirm a staff device shows A, replace with
    B, confirm it shows B and never A again. **V7** With the network cut after caching the QR still
    displays; with no cache the button is absent rather than broken
    - _Requirements: 12.5, 13.9, 14.5, 14.6_
  - [ ] 22.6 **V9** Generate a real kitchen slip, receipt, and report in each of the three modes and
    compare them. The printing code is unchanged, so what this catches is a difference in the inputs
    each mode feeds it
    - **Property 5: printing and reporting are mode-invariant**
    - **Validates: Requirements 9.1, 9.2, 9.3, 9.4, 9.7, 12.7**
  - [ ] 22.7 Confirm the Payment QR is never printed on a slip or receipt and that nothing encodes an
    amount or order reference into it — the code is a static payee identifier and there is no audit
    trail to catch a mistake after the fact
    - _Requirements: 14.10, 14.11_

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP; all unmarked tasks
  are required.
- Task 1 (database hardening) is a prerequisite for everything else — do not skip it.
- Task 3 (extract `BackendGateway`) is a pure refactor: if it changes behaviour, something
  went wrong.
- Task 5.3 (fix the `unitPrice`/`size` outbox bug) must land before any LAN ordering is tested
  on a real device, since a lost price is a silently wrong bill.
- Tasks 6, 7, 8 (LAN Server + pairing + polling) can proceed in parallel once task 4 is done.
- Tasks 15–17 (Payment QR) are independent of the LAN/Kiosk topology work and can be scheduled
  alongside tasks 9–11.
- Property tests (tasks 1.4, 2.4, 4.6, 6.4, 8.3, 11.5, 15.4, 16.4, 17.4, 18.2) each
  reference a numbered design property and the requirements it validates; do not implement
  tasks postfixed with `*`.
- Tasks 15–17 (Payment QR) are pulled to waves 0–5 deliberately: in Cloud Mode the feature needs
  nothing from the LAN/Kiosk work — an upload, a hash, a cache and a dialog — so it can ship to a live
  café long before any topology change exists. Only 15.3 (serving it from `LocalBackend`) waits on
  task 4.
- Task 20 is mostly *confirming code stays unchanged*. That is deliberate: Requirement 9 failed to get
  any task in the first draft precisely because "don't change this" produces no obvious deliverable.
- Task 22 is the Requirement 12 hardware gate and runs last, after checkpoint 19. It cannot be
  satisfied by a green build; each item needs a person with two phones, a hotspot, and a printer.
- Checkpoints 12 and 19 now appear in the waves (14 and 16) so they actually gate the work either side
  of them — previously they existed in the checklist but were absent from the graph, which meant
  nothing enforced them.
- 7.2 (client pairs against the server) now sits after 6.3 (server actually starts). Previously the
  client was scheduled to pair one wave before there was anything listening.
- Each task references specific requirements for traceability.
- Checkpoints (tasks 12, 19) ensure incremental validation; task 22 is the final hardware gate.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0,  "tasks": ["1.1", "1.2", "1.3", "15.1"] },
    { "id": 1,  "tasks": ["1.4", "1.5", "2.1", "15.2"] },
    { "id": 2,  "tasks": ["2.2", "2.3", "16.1"] },
    { "id": 3,  "tasks": ["2.4", "3.1", "16.2", "17.1"] },
    { "id": 4,  "tasks": ["3.2", "16.3", "17.2"] },
    { "id": 5,  "tasks": ["3.3", "15.4", "16.4", "17.3"] },
    { "id": 6,  "tasks": ["4.1", "5.3", "9.1", "17.4", "20.1", "20.2"] },
    { "id": 7,  "tasks": ["4.2", "4.3", "4.4", "4.5", "9.2", "20.3", "20.4"] },
    { "id": 8,  "tasks": ["4.6", "5.1", "5.2", "9.3", "9.4", "21.1"] },
    { "id": 9,  "tasks": ["6.1", "8.1", "10.1", "11.1", "11.2", "11.3", "11.4", "21.2", "21.3", "21.4", "21.7", "21.8"] },
    { "id": 10, "tasks": ["6.2", "8.2", "10.2", "11.5", "13.1", "13.2", "14.1", "14.2", "15.3"] },
    { "id": 11, "tasks": ["6.3", "7.1", "8.3", "21.5", "21.6"] },
    { "id": 12, "tasks": ["6.4", "7.2"] },
    { "id": 13, "tasks": ["7.3", "18.1", "21.9"] },
    { "id": 14, "tasks": ["12"] },
    { "id": 15, "tasks": ["18.2"] },
    { "id": 16, "tasks": ["19"] },
    { "id": 17, "tasks": ["22.1", "22.1b", "22.2", "22.3", "22.4", "22.5", "22.6", "22.7"] }
  ]
}
```
