# Spike 01 — Bluetooth thermal printing on real hardware

**Status:** ⏳ needs the café's actual printer to run — highest project risk (Task 2).
**Goal:** prove ESC/POS printing works on the owner's printer(s) before feature code, and
decide the library (go/no-go).

## Why this is a spike

Thermal-printer behaviour varies wildly by model (ESC/POS dialect, paper width, image mode,
diacritics, reconnect). We must not discover a broken printer in Phase 7. Get the model
**before** starting, and run this on it.

## Procedure

1. Get the exact printer model(s) the café owns (58 mm and/or 80 mm).
2. Add the ESC/POS library to a throwaway module (candidate: `ESCPOS-ThermalPrinter-Android`).
3. Run the checklist below on each physical printer. Record results in the table.

## Harness (Kotlin sketch — adapt to the chosen library)

```kotlin
// Throwaway Activity/Composable. Requires BLUETOOTH_CONNECT (API 31+) granted.
suspend fun runPrinterSpike(mac: String, width: PaperWidth) {
    val printer = connect(mac)                 // pair + open socket
    // 1. plain text + a divider line
    printer.printText("[ WARUNG TOM YAM ]\n--------------------------------\n")
    // 2. Malay diacritics / ringgit
    printer.printText("Ayam Masak Merah  RM 8.50\nTeh Tarik ais  RM 2.00\n")
    // 3. character width probe (32 for 58mm, 48 for 80mm)
    printer.printText("0123456789".repeat(5) + "\n")
    // 4. logo bitmap (small monochrome)
    printer.printImage(loadSampleLogo())
    // 5. QR (if the printer supports native QR) or as image
    printer.printQrOrImage("https://example.pages.dev/order?table=T1")
    printer.cut()
    printer.close()
    // 6. power-cycle the printer, then call runPrinterSpike again → does it reconnect?
}
```

## Checklist / results template

| Check | 58 mm model: `____` | 80 mm model: `____` |
|---|---|---|
| Pairs & connects over Bluetooth | ☐ | ☐ |
| Plain text + divider legible | ☐ | ☐ |
| Malay diacritics render (or acceptable ASCII fallback) | ☐ | ☐ |
| Char width correct (32 / 48) | ☐ | ☐ |
| Logo bitmap prints at 384 px / 576 px | ☐ | ☐ |
| Reconnect after power-cycle works | ☐ | ☐ |
| Auto-cut works (or manual tear acceptable) | ☐ | ☐ |

## Deliverable

A one-paragraph verdict: **works as-is** / **works with quirks (list them)** / **needs a
different library**. This decides whether Task 21 (printer registry) is a formality or needs
rework. Paste the verdict at the bottom of this file when done.

### Verdict (fill in)
> _pending — run on hardware._
