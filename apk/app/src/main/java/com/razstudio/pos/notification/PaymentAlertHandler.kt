package com.razstudio.pos.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles incoming payment alerts on receiving devices.
 *
 * When a PAYMENT_RECEIVED broadcast arrives (via Supabase Realtime in Cloud mode or
 * LAN WebSocket in LAN mode), this handler displays a heads-up notification, plays
 * an alert sound, and/or triggers vibration based on device-local preferences stored
 * in [ListenerPrefsStore].
 *
 * Validates: Requirements 5.1, 5.2, 5.3, 5.4
 */
@Singleton
class PaymentAlertHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val listenerPrefs: ListenerPrefsStore,
) {
    /**
     * Handle an incoming payment alert from another device (the SecondaryAdmin).
     *
     * Shows a heads-up notification, plays sound, and vibrates based on device preferences.
     *
     * @param amountSen the payment amount in Malaysian sen
     * @param sender the sender name extracted from the notification (nullable)
     * @param walletApp the wallet/bank app that received the payment
     * @param rawText the full notification description text from the SecondaryAdmin
     */
    suspend fun handlePaymentAlert(amountSen: Long, sender: String?, walletApp: String, rawText: String) {
        val showToast = listenerPrefs.toastNotificationEnabled.first()
        val playSound = listenerPrefs.soundEnabled.first()
        val vibrate = listenerPrefs.vibrationEnabled.first()

        if (showToast) {
            showPaymentNotification(rawText, playSound)
        }

        if (vibrate) {
            triggerVibration()
        }

        if (playSound && !showToast) {
            // Sound is enabled but toast is suppressed — play sound independently
            playAlertSound()
        }
    }

    /**
     * Display an Android heads-up notification with the payment description text.
     * Uses IMPORTANCE_HIGH for heads-up popup behavior.
     */
    private fun showPaymentNotification(text: String, withSound: Boolean) {
        val channelId = CHANNEL_ID
        val channel = NotificationChannel(
            channelId,
            "Payment Alerts",
            if (withSound) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alerts when payment is received on another device"
            if (!withSound) setSound(null, null)
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Payment Received")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()

        nm.notify(ALERT_NOTIFICATION_ID, notification)
    }

    /** Trigger a short vibration pulse (500ms). */
    private fun triggerVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(VIBRATION_DURATION_MS)
        }
    }

    /** Play the default notification sound without showing a system notification. */
    private fun playAlertSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play alert sound", e)
        }
    }

    companion object {
        private const val TAG = "PaymentAlertHandler"
        private const val CHANNEL_ID = "payment_alert_channel"
        private const val ALERT_NOTIFICATION_ID = 3002
        private const val VIBRATION_DURATION_MS = 500L
    }
}
