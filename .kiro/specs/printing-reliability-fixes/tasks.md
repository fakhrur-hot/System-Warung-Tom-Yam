# Implementation Plan: Printing & Bluetooth Reliability Fixes

## Overview

Bug-fix-first work on the printing subsystem from the 2026-07-30 audit, plus the owner's
"ordering devices run zero Bluetooth" requirement. Group 1 landed this session (documented here with
verification notes); Group 2 is open; Group 3 is deferred to on-hardware verification.

Requirement references map to `requirements.md` (R1–R8).

## Tasks

### Group 1 — Landed (this session, compile + smoke verified)

- [x] 1. Coroutine-safe printer locking — R1
  - [x] 1.1 Replace `synchronized(lock)` with `kotlinx.coroutines.sync.Mutex`; make `print()`,
    `disconnect(mac)`, `disconnectAll()` `suspend` using `mutex.withLock` in
    `PrinterConnectionManager`.
  - [x] 1.2 Propagate `suspend` to call sites: `PrinterDispatcher.connectAndPrint` (callers
    `executePrintJob`/`testPrint` already suspend) and `AdminSessionViewModel.quietBackground`
    (called only inside `viewModelScope.launch`).
  - [x] 1.3 Verified: `:app:assembleDebug` green; app installs and starts clean on device
    `143332557X105990`.

- [x] 2. Connect by MAC without scanning all paired devices — R2
  - [x] 2.1 `ensureConnected` resolves the device via
    `BluetoothManager.adapter.getRemoteDevice(mac)` → `BluetoothConnection(device)`; removed the
    `BluetoothPrintersConnections().list` enumeration and its now-unused import.
  - [x] 2.2 Fixed the compile bug an intermediate edit introduced (`BluetoothConnection(mac)` with a
    String — DantSu 3.3.0 only accepts `BluetoothConnection(BluetoothDevice)`, confirmed via `javap`).
  - [x] 2.3 Added distinct errors for adapter-absent, Bluetooth-off, and malformed-MAC (also clears
    the LOW "misleading error when BT off" audit item).

- [x] 3. Keep-alive heartbeat is a response-free no-op — R3
  - [x] 3.1 `KEEP_ALIVE_BYTES` changed from `DLE EOT 1` (`0x10 0x04 0x01`) to `ESC @`
    (`0x1B 0x40`); still Fast-mode-only, still per open connection.

- [x] 4. Only the Main Admin device runs Bluetooth — R4
  - [x] 4.1 `PrintService.isPrinterHost()` → `getRole() == SecureStorage.Role.ADMIN` (was
    `!= ADMIN_SECONDARY`, which let ORDERING through).
  - [x] 4.2 `PrinterConnectionManager` injects `SecureStorage`; `print()` and `startKeepAlive()`
    short-circuit to no-op for non-hosts. Confirmed no Hilt cycle (build green — `SecureStorage` is a
    leaf singleton).
  - [x] 4.3 Static confirmation: enumerated every Bluetooth entry point — socket/keep-alive gated in
    `PrinterConnectionManager`, print entry gated in `PrintService`, scan gated behind admin nav
    (`PrintersScreen`, unreachable by ordering).

### Group 2 — Open

- [ ] 5. Startup sweep for orphaned print jobs — R5
  - [ ] 5.1 Add `PrintJobDao.requeueStuckPrinting()`:
    `@Query("UPDATE print_jobs SET status='QUEUED' WHERE status='PRINTING'")`.
  - [ ] 5.2 In `PrinterDispatcher`, add `recoverStuckJobs()` launched once on init on `scope`; gate
    it on Printer_Host (R4). Call `requeueStuckPrinting()`, then read `getAllQueued()` and re-run each
    job via the existing `executePrintJob` path, resolving the printer from `job.printerId` via
    `PrinterConfigDao`; respect the existing `retryCount`/`markForRetry` ceiling.
  - [ ] 5.3 Guard: no-op on empty queue; no spurious alert; never runs on a non-host.
  - [ ] 5.4 Build green; unit-verify the requeue query flips `PRINTING`→`QUEUED` and leaves
    `COMPLETED`/`FAILED` untouched.

- [ ] 6. Request the battery-optimization exemption in-app — R6
  - [ ] 6.1 In `KeepAliveSetupScreen`, add a primary button (shown when `!isExempt`) that launches
    `Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))`
    via `OemKeepAliveHelper.launchSafely`.
  - [ ] 6.2 On return/resume, re-read `isIgnoringBatteryOptimizations()` and flip `isExempt`; hide
    the button and show the granted state once exempt.
  - [ ] 6.3 Keep the per-OEM `WhitelistStepCard`s as fallback. No new manifest permission (already
    declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

- [ ] 7. Delete dead code, fix stale comment — R7
  - [ ] 7.1 Confirm zero references, then delete `printing/BluetoothPrintSpike.kt`.
  - [ ] 7.2 Correct `PrinterConfig.categoryFilter` KDoc (it holds the active FOOD/BEVERAGE routing
    bucket, not "reserved for future use").
  - [ ] 7.3 Build green; no runtime behavior change.

### Group 3 — Deferred (on-hardware, gating the release)

- [ ] 8. On-hardware verification — R8 (owner-run)
  - [ ] 8.1 Real 58/80 mm printer: place an order → kitchen slip prints → `Print_Job` reaches
    `COMPLETED`.
  - [ ] 8.2 Fast mode keeps a warm connection across consecutive prints with the ESC @ heartbeat and
    no blank-line/garbage artifacts.
  - [ ] 8.3 Multilingual (Chinese/Tamil/Thai) bitmap slips + receipt logo + ESC* image mode render
    correctly.
  - [ ] 8.4 On a logged-in ordering-staff device: confirm zero Bluetooth activity (no socket, no
    scan, no keep-alive).
  - [ ] 8.5 On confirming 8.1–8.4, rebuild the signed FOSS release (version bump) with this work.

## Notes

- Group 1 is implemented but **not yet committed** (part of a 26-file uncommitted set that also
  includes the demo-mode rebuild and the secondary-admin QR feature). Commit Group 1 as its own
  focused commit(s) before starting Group 2.
- Do not re-open the `synchronized`→`Mutex` decision; it is settled and verified.
- Related specs: `order-flow-fixes` (order-lifecycle routing + the original Bluetooth stub replaced
  here), `apk-refactor`.

## Task Dependency Graph

```
Group 1 (done) ──┐
                 ├─► 5 (dispatcher sweep) ─┐
                 ├─► 6 (battery button)    ├─► 8 (hardware verify) ─► 8.5 (release)
                 └─► 7 (cleanup) ──────────┘
```
5, 6, 7 are independent of each other and can be done in any order.
