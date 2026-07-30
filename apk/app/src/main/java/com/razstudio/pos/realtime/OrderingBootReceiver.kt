package com.razstudio.pos.realtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.razstudio.pos.data.SecureStorage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Restarts the OrderingForegroundService after device reboot,
 * but only if the device has a stored API key (ordering role).
 *
 * Uses Hilt field injection so the same [SecureStorage] singleton
 * used across the rest of the app is accessed here, rather than
 * constructing a separate instance that bypasses the DI graph.
 */
@AndroidEntryPoint
class OrderingBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OrderingBootReceiver"
    }

    @Inject lateinit var secureStorage: SecureStorage

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val apiKey = secureStorage.getApiKey()
            val role = secureStorage.getRole()

            if (apiKey != null && role == SecureStorage.Role.ORDERING) {
                Log.i(TAG, "Device booted with ordering role — starting OrderingForegroundService")
                OrderingForegroundService.start(context)
            } else {
                Log.d(TAG, "Device booted but no ordering API key — skipping service start")
            }
        }
    }
}
