package com.razstudio.pos.printing.sunmi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.razstudio.pos.printing.DriverStatus

/**
 * BroadcastReceiver that subscribes to Sunmi printer-status broadcasts and maps them to
 * [DriverStatus] values surfaced via [onStatusChanged].
 *
 * Action strings are copied **verbatim** from Sunmi's SDK — including the vendor's
 * misspellings (`ACITON` instead of `ACTION`). A filter that corrects the spelling
 * silently never fires. (HW-REQ-5)
 *
 * Registered with [Context.RECEIVER_NOT_EXPORTED] so no third-party app can spoof the
 * broadcast and trigger a false "paper empty" or "cover open" alert.
 */
class SunmiStatusReceiver(
    private val onStatusChanged: (DriverStatus) -> Unit
) : BroadcastReceiver() {

    companion object {
        // ── Vendor action strings — DO NOT "fix" the misspellings ───────────────
        // These are the exact strings Sunmi's firmware broadcasts. Correcting the spelling
        // means the filter silently never matches the real broadcast.
        private const val ACTION_OVERHEATING    = "woyou.aidlservice.jiuiv5.action.OVER_HEATING_ACITON"
        private const val ACTION_FW_UPDATING    = "woyou.aidlservice.jiuiv5.action.FIRMWARE_UPDATING_ACITON"
        private const val ACTION_NON_EXISTENT   = "woyou.aidlservice.jiuiv5.action.PRINTER_NON_EXISTENT_ACITON"

        // Out-of-paper and cover-open are standard Sunmi broadcasts (correctly spelled)
        private const val ACTION_OUT_OF_PAPER   = "woyou.aidlservice.jiuiv5.action.OUT_OF_PAPER_ACTION"
        private const val ACTION_COVER_OPEN     = "woyou.aidlservice.jiuiv5.action.COVER_OPEN_ACTION"
        private const val ACTION_COVER_CLOSE    = "woyou.aidlservice.jiuiv5.action.COVER_CLOSE_ACTION"
        private const val ACTION_PRINT_NORMAL   = "woyou.aidlservice.jiuiv5.action.PRINT_NORMAL_ACTION"

        private const val TAG = "SunmiStatusReceiver"

        /**
         * Build the IntentFilter containing all Sunmi status action strings.
         * Call this once and pass the result to [registerReceiver].
         */
        fun intentFilter(): IntentFilter = IntentFilter().apply {
            addAction(ACTION_OVERHEATING)
            addAction(ACTION_FW_UPDATING)
            addAction(ACTION_NON_EXISTENT)
            addAction(ACTION_OUT_OF_PAPER)
            addAction(ACTION_COVER_OPEN)
            addAction(ACTION_COVER_CLOSE)
            addAction(ACTION_PRINT_NORMAL)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received broadcast: $action")
        val status = when (action) {
            ACTION_OVERHEATING  -> DriverStatus.OVERHEATING
            ACTION_FW_UPDATING  -> DriverStatus.OFFLINE      // offline during update
            ACTION_NON_EXISTENT -> DriverStatus.OFFLINE
            ACTION_OUT_OF_PAPER -> DriverStatus.OUT_OF_PAPER
            ACTION_COVER_OPEN   -> DriverStatus.COVER_OPEN
            ACTION_COVER_CLOSE  -> DriverStatus.ONLINE
            ACTION_PRINT_NORMAL -> DriverStatus.ONLINE
            else                -> return
        }
        onStatusChanged(status)
    }

    /**
     * Register this receiver with [RECEIVER_NOT_EXPORTED] (Android 13+) so broadcasts from
     * other apps cannot trigger the status callback.
     */
    fun register(context: Context) {
        val filter = intentFilter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // On older API levels the flag didn't exist; the receiver is still safe because
            // the Sunmi action strings are not guessable / worth spoofing by a typical app.
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(this, filter)
        }
        Log.d(TAG, "SunmiStatusReceiver registered")
    }

    /**
     * Unregister this receiver. Call when the driver disconnects / is destroyed.
     */
    fun unregister(context: Context) {
        try {
            context.unregisterReceiver(this)
            Log.d(TAG, "SunmiStatusReceiver unregistered")
        } catch (e: IllegalArgumentException) {
            // Already unregistered — safe to ignore
        }
    }
}
