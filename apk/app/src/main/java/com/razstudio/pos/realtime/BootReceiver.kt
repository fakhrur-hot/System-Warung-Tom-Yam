package com.razstudio.pos.realtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Spike: Restarts the RealtimeService after device reboot.
 *
 * Registered in AndroidManifest.xml for BOOT_COMPLETED.
 * The service uses START_STICKY as a secondary restart mechanism,
 * but BOOT_COMPLETED ensures restart after a full device power cycle.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Device booted — restarting RealtimeService")
            RealtimeService.start(context)
        }
    }
}
