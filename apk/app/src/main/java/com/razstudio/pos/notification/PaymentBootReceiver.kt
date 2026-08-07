package com.razstudio.pos.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that logs on device reboot whether the NotificationListenerService
 * will auto-rebind based on current permission state.
 *
 * The Android NotificationListenerService framework automatically rebinds the service
 * after a reboot when notification access permission is granted. This receiver simply
 * logs confirmation of that behavior for debugging purposes.
 */
class PaymentBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val permissionGranted = PaymentNotificationListener.isPermissionGranted(context)
            if (permissionGranted) {
                Log.i(TAG, "Boot completed — NotificationListenerService will auto-rebind (permission granted)")
            } else {
                Log.w(TAG, "Boot completed — NotificationListenerService will NOT rebind (permission not granted)")
            }
        }
    }

    companion object {
        private const val TAG = "PaymentBootReceiver"
    }
}
