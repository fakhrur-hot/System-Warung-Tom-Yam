# Spike 01 — Bluetooth Thermal Printing

## Library

**ESCPOS-ThermalPrinter-Android** v3.3.0  
Source: https://github.com/DantSu/ESCPOS-ThermalPrinter-Android  
License: MIT  
JitPack: `com.github.DantSu:ESCPOS-ThermalPrinter-Android:3.3.0`

## Status: ✅ Compiles and integrates

The library has been added to the APK project and builds successfully with:
- AGP 8.7.2, Kotlin 2.0.21, compileSdk 36, minSdk 26
- JitPack repository in `settings.gradle.kts`

## Android Version Compatibility

| Android Version | API Level | Bluetooth Permissions Required | Status |
|---|---|---|---|
| 8.0–11 (Oreo–R) | 26–30 | `BLUETOOTH` + `BLUETOOTH_ADMIN` (install-time) | ✅ Supported |
| 12 (S) | 31 | `BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN` (runtime) | ✅ Supported |
| 13 (Tiramisu) | 33 | Same as Android 12 — no new BT permissions | ✅ Supported |
| 14 (Upside Down Cake) | 34 | Same as Android 12 — no new BT permissions | ✅ Supported |
| 15 | 35–36 | Same as Android 12 — no new BT permissions | ✅ Supported |

### Key Points for Android 13/14

1. **No new Bluetooth permissions** were introduced in Android 13 or 14. The `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` runtime permissions from Android 12 remain the complete set.
2. The `neverForLocation` flag on `BLUETOOTH_SCAN` avoids requiring `ACCESS_FINE_LOCATION` for scanning — we only interact with already-paired devices.
3. The library's `BluetoothPrintersConnections.selectFirstPaired()` and `getList()` both work on Android 13/14 as long as `BLUETOOTH_CONNECT` is granted at runtime.
4. The library handles the BluetoothSocket connection internally — no additional APIs changed in 13/14.

## Manifest Permissions

```xml
<!-- Legacy (pre-Android 12) -->
<uses-permission android:name="android.permission.BLUETOOTH"
    android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
    android:maxSdkVersion="30" />

<!-- Android 12+ (covers 12, 13, 14, 15) -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
```

## Paper Width Configuration

| Paper | printingWidthMM | nbrCharactersPerLine | Image max width | Typical use |
|---|---|---|---|---|
| 58mm | 48f | 32 | 384px | Kitchen slips |
| 80mm | 72f | 48 | 576px | Customer receipts |

Standard DPI for most thermal printers: **203**

## Key Classes

| Class | Purpose |
|---|---|
| `BluetoothPrintersConnections` | Discover/list paired BT printers |
| `BluetoothPrintersConnections.selectFirstPaired()` | Quick access to first paired printer |
| `BluetoothConnection` | Individual printer connection (holds MAC address) |
| `EscPosPrinter` | Main printer API — format + print |
| `PrinterTextParserImg` | Convert Bitmap/Drawable to printer hex image |
| `EscPosCharsetEncoding` | Set charset (windows-1252, UTF-8, etc.) |

## Formatted Text Features

- Alignment: `[L]` left, `[C]` center, `[R]` right — multi-column on same line
- Font sizes: `normal`, `wide`, `tall`, `big`, `big-2` through `big-6`
- Bold: `<b>text</b>`, Underline: `<u>text</u>`
- Images: `<img>` hex data `</img>` (for logo printing)
- Barcodes: `<barcode type='ean13'>code</barcode>`
- QR codes: `<qrcode size='20'>data</qrcode>`

## Charset / Malay Diacritics

For Malay text (diacritics like ā, ē, etc. are rare in standard Malay), standard ASCII/Latin covers the common case. For edge cases:

```kotlin
val printer = EscPosPrinter(connection, 203, 48f, 32,
    EscPosCharsetEncoding("windows-1252", 16))
```

The `escPosCharsetId` varies by printer model — `16` (windows-1252) works for most budget printers. UTF-8 is not universally supported by thermal printers.

## Reconnect-After-Power-Cycle

The library connects via Bluetooth MAC address. After a printer power cycle:
1. The Android OS retains the pairing (no re-pair needed).
2. `BluetoothPrintersConnections.selectFirstPaired()` finds it again.
3. For specific printer targeting, store the MAC address and create a `BluetoothConnection` directly.

## Spike Implementation

See: `apk/app/src/main/java/com/warungtomyam/pos/printing/BluetoothPrintSpike.kt`

Contains:
- `ensureBluetoothPermissions()` — OS-version-aware permission handler
- `getPairedPrinters()` — list available printers
- `printTestReceipt()` — formatted receipt with Malay text, 58mm/80mm
- `printKitchenSlip()` — delta-slip pattern demonstration

## Verdict: GO ✅

The library integrates cleanly, compiles with our toolchain, supports all target Android versions (8.0 through 14+), and provides the formatted text API needed for kitchen slips and receipts. No fallback library needed.

## Remaining (needs real hardware)

- [ ] Test on the café's actual printer model (pairing, print quality, reconnect)
- [ ] Verify logo bitmap printing (café branding on receipts)
- [ ] Confirm Malay characters render correctly on the specific printer
- [ ] Measure print latency (acceptable for kitchen workflow?)
- [ ] Test simultaneous connection to two printers (kitchen + receipt)
