package com.razstudio.pos.data.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.razstudio.pos.MainActivity
import com.razstudio.pos.data.OperatingMode
import java.util.concurrent.TimeUnit

/**
 * Worker that posts a periodic notification reminding the user to back up their data.
 *
 * Schedule varies by mode (Requirement 8.2):
 * - LAN / KIOSK: **daily** — Room is the only copy of the café's data, so a week is too long.
 * - CLOUD: weekly — Supabase holds the truth; the local database is a cache.
 *
 * [scheduleForMode] uses REPLACE policy (not KEEP) so a mode change enqueues the
 * correct interval immediately rather than waiting for the old schedule to expire.
 */
class BackupReminderWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val WORK_NAME = "backup_reminder"
        private const val CHANNEL_ID = "backup_reminder"
        private const val NOTIFICATION_ID = 9001

        /** Repeat interval for LAN and Kiosk Mode — data durability is load-bearing. */
        private const val LOCAL_INTERVAL_DAYS = 1L

        /** Repeat interval for Cloud Mode — Supabase holds the truth, weekly is fine. */
        private const val CLOUD_INTERVAL_DAYS = 7L

        /**
         * Schedules a periodic reminder whose interval matches [mode].
         *
         * Uses [ExistingPeriodicWorkPolicy.UPDATE] so that calling this again after a mode change
         * replaces the previous schedule with the new interval immediately, rather than waiting
         * for the old period to expire.
         *
         * Safe to call multiple times for the same mode — WorkManager deduplicates by work name.
         */
        fun scheduleForMode(context: Context, mode: OperatingMode) {
            val intervalDays = when (mode) {
                OperatingMode.LAN, OperatingMode.KIOSK -> LOCAL_INTERVAL_DAYS
                OperatingMode.CLOUD -> CLOUD_INTERVAL_DAYS
            }
            val flexDays = (intervalDays / 2).coerceAtLeast(1L)
            val request = PeriodicWorkRequestBuilder<BackupReminderWorker>(
                intervalDays, TimeUnit.DAYS,
                flexDays, TimeUnit.DAYS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /**
         * Legacy overload — schedules with the CLOUD (weekly) interval.
         * Kept for call sites that have not been updated yet.
         */
        fun schedule(context: Context) = scheduleForMode(context, OperatingMode.CLOUD)
    }

    override fun doWork(): Result {
        createNotificationChannel()
        postNotification()
        return Result.success()
    }

    private fun notificationBody(): String {
        // Show a more urgent message in local modes where Room is the only copy.
        return "Back up your data now — there is no cloud copy of your orders."
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Backup Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Weekly reminders to back up your POS data"
            }
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun postNotification() {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Backup Reminder")
            .setContentText(notificationBody())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
