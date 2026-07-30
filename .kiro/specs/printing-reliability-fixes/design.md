# Design Document: Printing & Bluetooth Reliability Fixes

## Architecture Overview

All changes live in the printing subsystem and its role gating. Nothing changes the ESC/POS
document layer, the backend, or the order-lifecycle routing (fixed in the `order-flow-fixes` spec).

```
┌──────────────────────────────────────────────────────────────────────┐
│  LANDED (this session)                                                 │
│  R1  print() → suspend + coroutine Mutex (was synchronized)            │
│  R2  ensureConnected → getRemoteDevice(mac) (was scan-all-paired)      │
│  R3  keep-alive heartbeat → ESC @ (was DLE EOT status-request)         │
│  R4  isPrinterHost == ADMIN + PrinterConnectionManager role short-circuit │
├──────────────────────────────────────────────────────────────────────┤
│  OPEN                                                                   │
│  R5  PrinterDispatcher init sweep: PRINTING→QUEUED, re-dispatch QUEUED  │
│  R6  KeepAliveSetupScreen: request ignore-battery-optimizations         │
│  R7  delete BluetoothPrintSpike.kt; fix categoryFilter comment          │
├──────────────────────────────────────────────────────────────────────┤
│  DEFERRED                                                              │
│  R8  on-hardware verification (real thermal printer + ordering device)  │
└──────────────────────────────────────────────────────────────────────┘
```

The single Bluetooth chokepoint is `PrinterConnectionManager` — the only class that opens a
`BluetoothConnection` or runs the keep-alive. Gating it (R4) plus the `PrintService` host check is
the belt-and-suspenders guarantee behind "ordering devices run zero Bluetooth."

---

## Landed fixes

### R1. Coroutine Mutex instead of `synchronized`

**Root cause.** `print()` held `synchronized(lock)` across `fresh.connect()` (a 1–5 s Bluetooth
handshake) and `printFormattedTextAndCut()` (transfer). Because the lock is an OS-monitor, every
other caller (keep-alive, eco-disconnect, a second print) blocked a `Dispatchers.IO` **thread** for
that whole window, serializing prints and stalling the second slip by 5–10 s.

**Design.** Replace the monitor with `kotlinx.coroutines.sync.Mutex`; `print()`, `disconnect(mac)`,
and `disconnectAll()` become `suspend` and use `mutex.withLock`. A waiter now *suspends* instead of
pinning a thread. Call sites propagate `suspend`: `PrinterDispatcher.connectAndPrint` (called only
from the already-suspend `executePrintJob`/`testPrint`) and `AdminSessionViewModel.quietBackground`
(called only inside `viewModelScope.launch`).

**Files:** `PrinterConnectionManager.kt`, `PrinterDispatcher.kt`, `AdminSessionViewModel.kt`.

### R2. Connect by MAC via `getRemoteDevice`

**Root cause.** `ensureConnected` reconnected by enumerating `BluetoothPrintersConnections().list`
(every bonded device on the phone) and matching MAC — slow and unpredictable on phones with many
peripherals. An intermediate edit tried `BluetoothConnection(mac)` with a **String**, which does not
compile — DantSu 3.3.0's only constructor is `BluetoothConnection(BluetoothDevice)` (verified via
`javap` against the AAR).

**Design.** Resolve the device with `BluetoothManager.adapter.getRemoteDevice(mac)` (a pure handle,
no I/O, no scan) and pass it to `BluetoothConnection(device)`. Add explicit guards: adapter absent
→ "Bluetooth is unavailable"; adapter disabled → "Bluetooth is off — turn it on to print to X";
malformed MAC (`IllegalArgumentException`) → "Invalid printer address". This also resolves the LOW
"misleading error when BT is off" audit item.

**Files:** `PrinterConnectionManager.kt` (`ensureConnected`).

### R3. Keep-alive heartbeat → `ESC @`

**Root cause.** The heartbeat wrote `DLE EOT 1` (`0x10 0x04 0x01`), a real-time **status request**
that expects a 1-byte reply. Many cheap 58 mm printers mishandle it (blank line, garbage, or block),
and the manager never reads the reply, so the socket can back-pressure over time.

**Design.** Use `ESC @` (`0x1B 0x40`, initialize) — a response-free no-op safe on cheap units.
`LF` (`0x0A`) was the alternative; `ESC @` is chosen because it also resets any half-parsed state.

**Files:** `PrinterConnectionManager.kt` (`KEEP_ALIVE_BYTES`).

### R4. Only the Main Admin runs Bluetooth

**Root cause.** `PrintService.isPrinterHost()` was `getRole() != ADMIN_SECONDARY`, which returns
**true for ORDERING** — an ordering device was treated as a printer host and could enter the print
path. The owner requires ordering devices to run no Bluetooth at all.

**Design.** Two independent layers:

1. `PrintService.isPrinterHost()` → `getRole() == SecureStorage.Role.ADMIN`. Only the Main Admin is
   a host; ORDERING, ADMIN_SECONDARY, and null role are non-hosts, so every print entry point
   (`printKitchenSlip`, `printReceipt`) no-ops for them.
2. `PrinterConnectionManager` injects `SecureStorage` and short-circuits `print()` and
   `startKeepAlive()` (`return` when not host) — so even a stray call opens no socket and starts no
   heartbeat. Scan/discovery (`BluetoothHelper`/`PrintersScreen`) is admin-nav-only and unreachable
   by ordering.

**Files:** `PrintService.kt`, `PrinterConnectionManager.kt`.

---

## Open fixes

### R5. Startup sweep for orphaned print jobs

**Root cause.** If the process is killed mid-print, jobs stay `PRINTING` (or unretried `QUEUED`) in
`print_jobs` forever; nothing re-dispatches them on next launch, and the admin gets no feedback.

**Design.**
- Add `PrintJobDao.requeueStuckPrinting()`:
  `@Query("UPDATE print_jobs SET status='QUEUED' WHERE status='PRINTING'")`.
- On `PrinterDispatcher` init (or first `dispatch`), on a Printer_Host device only (R4), launch a
  one-shot recovery on `scope`: call `requeueStuckPrinting()`, then read `getAllQueued()` and
  re-run each job through the existing dispatch/`executePrintJob` path, resolving the printer by the
  job's `printerId` via `PrinterConfigDao`.
- Guard: no-op when the queue is empty; never emit a spurious alert; never run on a non-host.

**Files:** `PrintJobDao.kt`, `PrinterDispatcher.kt` (init + a `recoverStuckJobs()` helper).

**Open question.** Whether to cap retries on recovery (a permanently-unreachable printer shouldn't
loop) — reuse the existing `retryCount`/`markForRetry` ceiling rather than dispatching blindly.

### R6. Request the battery-optimization exemption in-app

**Root cause.** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is in the manifest and
`OemKeepAliveHelper.isIgnoringBatteryOptimizations()` is used only as a *check*; the system dialog is
never fired, so users must dig through per-OEM steps. On aggressive OEMs the keep-alive IO threads
get throttled when the screen is off.

**Design.** `KeepAliveSetupScreen` already tracks `isExempt` and has `OemKeepAliveHelper.launchSafely`
for launching intents. Add a primary button, shown when `!isExempt`, that launches
`Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))`
via `launchSafely`; on resume, re-read `isIgnoringBatteryOptimizations()` to flip the state. Keep the
existing per-OEM `WhitelistStepCard`s as the fallback. No new permission.

**Files:** `KeepAliveSetupScreen.kt` (optionally a helper on `OemKeepAliveHelper`).

### R7. Delete dead code, fix stale comment

**Design.** Delete `printing/BluetoothPrintSpike.kt` (unmaintained, "first paired", no
`@AndroidEntryPoint`, never referenced). Correct the `PrinterConfig.categoryFilter` KDoc to state it
holds the FOOD/BEVERAGE routing bucket in use today, not "reserved for future." Confirm no
references before deleting; verify a green build after.

**Files:** delete `BluetoothPrintSpike.kt`; edit `PrinterConfig.kt`.

---

## R8. On-hardware verification (deferred, gating)

Cannot be done adb-only. Requires the owner to: pair a real 58/80 mm printer, place an order and see
a kitchen slip reach `COMPLETED`; confirm Fast-mode warm reconnect + ESC @ heartbeat produce no
artifacts; confirm multilingual bitmap slips + receipt logo + ESC* render; and confirm a logged-in
ordering device does zero Bluetooth. This gates the FOSS release rebuild.

## Testing Strategy

- **Build-green** after each change (`:app:compileDebugKotlin`, then `:app:assembleDebug`).
- **DI validation** — R4's `PrinterConnectionManager → SecureStorage` injection must not introduce a
  Hilt cycle (confirmed: `SecureStorage` is a leaf singleton).
- **Static confirmation** for R4 — grep every Bluetooth entry point and confirm each is either
  role-gated or admin-nav-only.
- **On-device smoke** — install debug, confirm clean startup and no regression to admin printing.
- **R8 hardware pass** — owner-run, per the acceptance criteria; the release gate.
