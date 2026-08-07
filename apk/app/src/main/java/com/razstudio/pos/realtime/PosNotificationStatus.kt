package com.razstudio.pos.realtime

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.razstudio.pos.MainActivity
import com.razstudio.pos.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the persistent foreground notification shown by the POS app.
 *
 * Both [RealtimeService] and [com.razstudio.pos.notification.PaymentNotificationListener] update
 * their respective status fields, and this helper rebuilds the shared notification. The result is
 * a single notification row in the shade instead of two separate ones.
 *
 * The notification uses [CHANNEL_ID] ("realtime_channel") and [NOTIFICATION_ID] (1001) — the same
 * slot the realtime service already owns.
 */
@Singleton
class PosNotificationStatus @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Current realtime connection status text (e.g., "Connected — listening for orders"). */
    @Volatile
    var realtimeStatusText: String = ""
        private set

    /** Whether the payment monitor listener is currently active (connected). */
    @Volatile
    var paymentMonitorActive: Boolean = false
        private set

    /** Whether the payment monitor is sleeping due to being outside business hours. */
    @Volatile
    var businessHoursSleeping: Boolean = false
        private set

    /**
     * Called by [RealtimeService] whenever its connection status changes.
     */
    fun updateRealtimeStatus(statusText: String) {
        realtimeStatusText = statusText
        rebuildNotification()
    }

    /**
     * Called by [com.razstudio.pos.notification.PaymentNotificationListener] when it
     * connects/disconnects.
     */
    fun updatePaymentMonitorStatus(active: Boolean, sleeping: Boolean = false) {
        paymentMonitorActive = active
        businessHoursSleeping = sleeping
        rebuildNotification()
    }

    /**
     * Builds and posts the single persistent notification combining all status lines.
     * Uses InboxStyle when multiple lines are present.
     */
    fun rebuildNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val lines = buildList {
            if (realtimeStatusText.isNotBlank()) add(realtimeStatusText)
            if (paymentMonitorActive) {
                if (businessHoursSleeping) {
                    add("Payment monitor: sleeping (outside business hours)")
                } else {
                    add("Payment monitor: active")
                }
            }
        }

        // Use first line as content text, show all via InboxStyle if >1 line
        val contentText = lines.firstOrNull() ?: "RAZ POS"

        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)

        if (lines.size > 1) {
            val inboxStyle = NotificationCompat.InboxStyle()
            lines.forEach { inboxStyle.addLine(it) }
            builder.setStyle(inboxStyle)
        }

        nm.notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * Builds a [Notification] for use with [android.app.Service.startForeground].
     * Services should call this instead of building their own notification.
     */
    fun buildForegroundNotification(): Notification {
        val lines = buildList {
            if (realtimeStatusText.isNotBlank()) add(realtimeStatusText)
            if (paymentMonitorActive) {
                if (businessHoursSleeping) {
                    add("Payment monitor: sleeping (outside business hours)")
                } else {
                    add("Payment monitor: active")
                }
            }
        }

        val contentText = lines.firstOrNull() ?: "RAZ POS"

        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)

        if (lines.size > 1) {
            val inboxStyle = NotificationCompat.InboxStyle()
            lines.forEach { inboxStyle.addLine(it) }
            builder.setStyle(inboxStyle)
        }

        return builder.build()
    }

    companion object {
        /** Shared channel ID — same as RealtimeService's existing channel. */
        const val CHANNEL_ID = "realtime_channel"

        /** Shared notification ID — same as RealtimeService's existing notification slot. */
        const val NOTIFICATION_ID = 1001
    }
}
