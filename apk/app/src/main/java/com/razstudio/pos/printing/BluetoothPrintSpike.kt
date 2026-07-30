package com.razstudio.pos.printing

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections

/**
 * SPIKE — Task 2: Bluetooth thermal printing proof-of-concept.
 *
 * This file demonstrates:
 * 1. Bluetooth permission handling for Android 12+ (API 31+), covering Android 12, 13, 14, 15.
 * 2. Discovering paired Bluetooth printers via BluetoothPrintersConnections.
 * 3. Printing a formatted test receipt on both 58mm and 80mm paper widths.
 * 4. Reconnect-after-power-cycle behaviour (connect by MAC address).
 *
 * Library: com.github.DantSu:ESCPOS-ThermalPrinter-Android:3.3.0
 * License: MIT
 * Source: https://github.com/DantSu/ESCPOS-ThermalPrinter-Android
 *
 * ## Android 13/14 Compatibility Notes
 *
 * - Android 12 (API 31) introduced BLUETOOTH_CONNECT and BLUETOOTH_SCAN as runtime permissions,
 *   replacing the old BLUETOOTH and BLUETOOTH_ADMIN.
 * - Android 13 (API 33) and Android 14 (API 34) do NOT add any new Bluetooth permission
 *   requirements — BLUETOOTH_CONNECT + BLUETOOTH_SCAN remain sufficient.
 * - The `neverForLocation` flag on BLUETOOTH_SCAN avoids requiring ACCESS_FINE_LOCATION
 *   for scanning (we only need paired devices, not discovery by location).
 * - The library's BluetoothPrintersConnections.selectFirstPaired() and getList() both
 *   work on Android 13/14 as long as BLUETOOTH_CONNECT is granted at runtime.
 *
 * ## Paper Width Configuration
 *
 * | Paper  | printingWidthMM | nbrCharactersPerLine | Image max width |
 * |--------|-----------------|----------------------|-----------------|
 * | 58mm   | 48f             | 32                   | 384px           |
 * | 80mm   | 72f             | 48                   | 576px           |
 *
 * DPI for most thermal printers: 203
 */
object BluetoothPrintSpike {

    private const val REQUEST_BLUETOOTH_CONNECT = 1001
    private const val REQUEST_BLUETOOTH_SCAN = 1002

    /**
     * Check and request Bluetooth permissions appropriate for the OS version.
     * Returns true if all permissions are already granted; false if a request was triggered.
     */
    fun ensureBluetoothPermissions(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+): need BLUETOOTH_CONNECT and BLUETOOTH_SCAN
            val connectGranted = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            val scanGranted = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

            if (!connectGranted) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    REQUEST_BLUETOOTH_CONNECT
                )
                return false
            }
            if (!scanGranted) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN),
                    REQUEST_BLUETOOTH_SCAN
                )
                return false
            }
            return true
        } else {
            // Pre-Android 12: legacy BLUETOOTH and BLUETOOTH_ADMIN (auto-granted at install for targetSdk < 31,
            // but we target 36 so they are also runtime-requestable on older devices)
            val btGranted = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED

            if (!btGranted) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN),
                    REQUEST_BLUETOOTH_CONNECT
                )
                return false
            }
            return true
        }
    }

    /**
     * Get a list of all paired Bluetooth printers.
     * Requires BLUETOOTH_CONNECT permission on Android 12+.
     */
    fun getPairedPrinters(): Array<com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection>? {
        return BluetoothPrintersConnections().list
    }

    /**
     * Print a test receipt to the first paired Bluetooth printer.
     *
     * @param paperWidth58mm true for 58mm paper (32 chars), false for 80mm paper (48 chars)
     */
    fun printTestReceipt(paperWidth58mm: Boolean = true) {
        val connection = BluetoothPrintersConnections.selectFirstPaired()
            ?: throw IllegalStateException("No paired Bluetooth printer found")

        // Printer config based on paper width
        val printerDpi = 203
        val printingWidthMM = if (paperWidth58mm) 48f else 72f
        val nbrCharsPerLine = if (paperWidth58mm) 32 else 48

        val printer = EscPosPrinter(connection, printerDpi, printingWidthMM, nbrCharsPerLine)

        printer.printFormattedTextAndCut(
            "[C]<font size='big'>WARUNG TOM YAM</font>\n" +
            "[C]================================\n" +
            "[L]\n" +
            "[C]<font size='tall'>TEST PRINT</font>\n" +
            "[L]\n" +
            "[C]--------------------------------\n" +
            "[L]Paper: ${if (paperWidth58mm) "58mm" else "80mm"}\n" +
            "[L]Chars/line: $nbrCharsPerLine\n" +
            "[L]DPI: $printerDpi\n" +
            "[L]\n" +
            "[C]--------------------------------\n" +
            "[L]<b>Nasi Lemak</b>[R]RM 6.50\n" +
            "[L]<b>Teh Tarik</b>[R]RM 2.50\n" +
            "[L]<b>Tom Yam Seafood</b>[R]RM 12.00\n" +
            "[L]\n" +
            "[C]================================\n" +
            "[R]<font size='tall'>TOTAL: RM 21.00</font>\n" +
            "[L]\n" +
            "[C]Terima Kasih / Thank You\n" +
            "[L]\n" +
            "[C]<qrcode size='20'>https://tani-tom-yam.pages.dev</qrcode>\n"
        )
    }

    /**
     * Print a kitchen slip (58mm typical).
     * Demonstrates the delta-slip pattern: only prints new (unsent) items.
     */
    fun printKitchenSlip(
        tableLabel: String,
        items: List<Pair<String, Int>>, // name to quantity
        isDelta: Boolean = false,
        paperWidth58mm: Boolean = true
    ) {
        val connection = BluetoothPrintersConnections.selectFirstPaired()
            ?: throw IllegalStateException("No paired Bluetooth printer found")

        val printerDpi = 203
        val printingWidthMM = if (paperWidth58mm) 48f else 72f
        val nbrCharsPerLine = if (paperWidth58mm) 32 else 48

        val printer = EscPosPrinter(connection, printerDpi, printingWidthMM, nbrCharsPerLine)

        val header = if (isDelta) {
            "[C]<font size='big'>TAMBAHAN</font>\n" +
            "[C]<font size='big'>ADDED</font>\n"
        } else {
            "[C]<font size='big'>KITCHEN</font>\n"
        }

        val itemLines = items.joinToString("") { (name, qty) ->
            "[L]<b>$name</b>[R]x$qty\n"
        }

        printer.printFormattedTextAndCut(
            header +
            "[C]================================\n" +
            "[C]<font size='big'>$tableLabel</font>\n" +
            "[C]================================\n" +
            "[L]\n" +
            itemLines +
            "[L]\n" +
            "[C]--------------------------------\n" +
            "[L]${java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())}\n"
        )
    }
}
