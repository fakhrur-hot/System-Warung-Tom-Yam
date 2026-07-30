# Requirements Document

## Introduction

This spec covers the **Bluetooth printing & background-service reliability** work driven by the
2026-07-30 full audit of the printing subsystem, plus the café-owner request that ordering-staff
devices run **no** Bluetooth at all.

The effort splits into three groups:

1. **Landed fixes (this session)** — the two HIGH-severity concurrency/connection defects, the
   keep-alive heartbeat that confuses cheap printers, and the role-gating so only the Main Admin
   device touches Bluetooth. These are implemented and compile-verified; they are documented here as
   completed requirements so the spec is a faithful record and their on-hardware verification is
   tracked.
2. **Open reliability fixes** — a startup sweep that recovers print jobs orphaned by an app kill,
   and an in-app path to request the OS battery-optimization exemption the keep-alive depends on.
3. **Housekeeping** — deleting a dead spike file and correcting a misleading comment.

This is a **bug-fix-first** effort: correctness and reliability of existing printing, not new
printing features. On-hardware verification (a real paired thermal printer) is the gating exit
criterion and is called out explicitly because it cannot be done in a device-farm/adb-only setting.

### Out of scope (tracked elsewhere)

- The end-of-day **closing aggregate placeholder** (`AdminSessionViewModel` posts `totalOrders: 0`)
  — a reports/business-day bug in a different subsystem; track separately.
- **Demo customer surface** and **i18n** of the demo/secondary-admin strings — feature follow-ups,
  not printing bugs.

## Glossary

- **Printer_Host**: The single device permitted to own local Bluetooth printer connections and
  print slips/receipts. Defined as `SecureStorage.Role.ADMIN` (the Main Admin). Secondary-admin and
  ordering-staff devices are **not** hosts.
- **Connection_Pool**: `PrinterConnectionManager`'s in-heap map of open `BluetoothConnection`s keyed
  by printer MAC address, kept warm in Fast mode via a keep-alive heartbeat.
- **Keep_Alive**: A periodic byte write to each open connection so the OS/printer don't tear down an
  idle Bluetooth socket during a service (Fast mode only).
- **Print_Job**: A row in the local `print_jobs` Room table tracking one dispatch attempt to one
  physical printer, with status `QUEUED` / `PRINTING` / `COMPLETED` / `FAILED`.
- **Stuck_Job**: A `Print_Job` left in `PRINTING` (or unretried `QUEUED`) because the process was
  killed mid-print; nothing currently retries it on next launch.
- **OEM_Battery_Exemption**: Android's "Unrestricted"/ignore-battery-optimizations state that keeps
  background IO threads (the keep-alive) from being throttled on aggressive OEMs (Xiaomi, Vivo…).

## Requirements

### Requirement R1 — Printer locking must not block an OS thread *(landed)*

**User Story:** As a cashier during a busy service, I want a second kitchen slip to print promptly
even while the first printer is still connecting, so that orders aren't delayed by 5–10 seconds.

#### Acceptance Criteria

1. THE `PrinterConnectionManager` SHALL guard all socket access with a coroutine `Mutex`
   (`withLock`), NOT a `synchronized` block, so a waiting caller suspends its coroutine instead of
   blocking an OS (IO-dispatcher) thread during a 1–5 s Bluetooth connect + transfer.
2. `print()`, `disconnect(mac)`, and `disconnectAll()` SHALL be `suspend` functions, and every call
   site SHALL invoke them from a coroutine/suspend context.
3. THE change SHALL NOT alter the guarantee that a print, a keep-alive ping, and a disconnect can
   never touch the same connection concurrently.

### Requirement R2 — Reconnect must not scan every paired device *(landed)*

**User Story:** As an admin whose phone is paired with many Bluetooth devices (headphones, watch,
car), I want printer reconnects to be fast and predictable, so that reconnection isn't slowed by
unrelated peripherals.

#### Acceptance Criteria

1. WHEN (re)connecting to a printer, THE app SHALL resolve the device directly from its MAC address
   via `BluetoothAdapter.getRemoteDevice(mac)` and construct `BluetoothConnection(device)` — it SHALL
   NOT enumerate `BluetoothPrintersConnections().list` (all paired devices).
2. `getRemoteDevice()` SHALL be used only as a non-scanning handle lookup (it performs no I/O).
3. WHEN Bluetooth is off or the adapter is absent, THE app SHALL throw a clear, distinct error
   ("Bluetooth is off…" / "Bluetooth is unavailable…"), NOT the misleading "not paired or
   unreachable" message.
4. WHEN the stored MAC is malformed, THE app SHALL throw an "Invalid printer address" error rather
   than crash with `IllegalArgumentException`.

### Requirement R3 — Keep-alive heartbeat must be a harmless no-op *(landed)*

**User Story:** As an owner using a cheap 58 mm printer, I want the warm-connection heartbeat to not
produce blank lines, garbage, or a blocked socket, so that Fast mode is safe on my hardware.

#### Acceptance Criteria

1. THE Keep_Alive heartbeat SHALL write bytes that expect **no** response from the printer.
   Specifically it SHALL use `ESC @` (`0x1B 0x40`, initialize) and SHALL NOT use the previous
   `DLE EOT 1` (`0x10 0x04 0x01`) real-time status request.
2. THE heartbeat SHALL continue to run only in Fast mode and only for currently-open connections.

### Requirement R4 — Only the Main Admin device runs Bluetooth *(landed)*

**User Story:** As the café owner, I want ordering-staff devices to never run any Bluetooth activity
or module at all, so that staff tablets do no printer chatter, hold no sockets, and burn no battery
on background Bluetooth.

#### Acceptance Criteria

1. `isPrinterHost()` SHALL be defined as `getRole() == SecureStorage.Role.ADMIN` at every print
   entry point (`PrintService`), so ORDERING and ADMIN_SECONDARY (and a null role) are non-hosts.
2. `PrinterConnectionManager` SHALL short-circuit `print()` and `startKeepAlive()` to a no-op when
   the device is not the Printer_Host — so a non-host device NEVER opens a Bluetooth socket, runs
   the keep-alive loop, or scans.
3. Bluetooth scan/discovery (`BluetoothHelper` / `PrintersScreen`) SHALL remain reachable only from
   the admin navigation and SHALL have no ordering-staff entry point.
4. THE Main Admin (role `ADMIN`) SHALL retain its existing printing behavior with no regression.

### Requirement R5 — Orphaned print jobs are recovered on startup *(open)*

**User Story:** As a cashier, I want slips that were mid-print when the app was killed to be retried
automatically, so that an app crash/kill during service doesn't silently drop a kitchen slip.

#### Acceptance Criteria

1. WHEN `PrinterDispatcher` initializes (app launch), on a Printer_Host device THE app SHALL reset
   any `Print_Job` left in `PRINTING` back to `QUEUED`.
2. THEN THE app SHALL re-dispatch all `QUEUED` jobs through the normal dispatch/retry path.
3. THE sweep SHALL run ONLY on a Printer_Host device (non-hosts have no printers and must not touch
   Bluetooth per R4).
4. THE sweep SHALL be safe to run when there are zero stuck jobs (no error, no spurious alert).

### Requirement R6 — Battery-optimization exemption can be requested in-app *(open)*

**User Story:** As an owner on an aggressive-OEM phone, I want a one-tap way to grant the app the
"Unrestricted" battery setting, so that the keep-alive isn't throttled when the screen is off during
a busy service.

#### Acceptance Criteria

1. THE `KeepAliveSetupScreen` SHALL show a button that fires
   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` so Android presents its own exemption dialog.
2. WHEN the app already holds the exemption (`isIgnoringBatteryOptimizations()` is true), THE UI
   SHALL reflect the granted state and SHALL NOT re-prompt.
3. THE existing manual per-OEM instructions SHALL remain as a fallback.
4. THE manifest already declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; no new permission is added.

### Requirement R7 — Dead code and misleading comments removed *(open)*

**User Story:** As a maintainer, I want the printing package free of unmaintained spike code and
stale comments, so that future changes aren't led astray.

#### Acceptance Criteria

1. THE unmaintained `BluetoothPrintSpike.kt` (uses "first paired" selection, no DI, never called)
   SHALL be deleted.
2. THE `PrinterConfig.categoryFilter` comment SHALL be corrected: it is actively used to store the
   FOOD/BEVERAGE kitchen-routing bucket, not "reserved for future use."
3. Deleting/relabeling SHALL NOT change any runtime behavior (verified by a green build).

### Requirement R8 — On-hardware verification *(deferred, gating)*

**User Story:** As the owner, I want the printing changes confirmed on a real thermal printer before
release, so that a reliability refactor doesn't ship an untested print path.

#### Acceptance Criteria

1. On a real paired 58 mm and/or 80 mm printer, a placed order SHALL print a kitchen slip, and the
   `Print_Job` SHALL reach `COMPLETED`.
2. Fast mode SHALL keep a warm connection across consecutive prints with the ESC @ heartbeat and no
   blank-line/garbage artifacts.
3. Multilingual slips (Chinese/Tamil/Thai) SHALL print via the bitmap fallback, and the receipt logo
   + ESC* image mode SHALL render correctly.
4. On a logged-in ordering-staff device, NO Bluetooth activity SHALL occur (no socket, no scan, no
   keep-alive).
