package com.razstudio.pos.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.realtime.PosNotificationStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Android [NotificationListenerService] that passively captures payment-received notifications
 * from Malaysian eWallet and banking apps, stores them in Room, and auto-matches to pending orders.
 *
 * Lifecycle:
 * - [onListenerConnected]: starts foreground service to prevent OS kills
 * - [onNotificationPosted]: filters by monitored package, parses, stores, matches
 * - [onListenerDisconnected]: logs warning (permission may have been revoked)
 * - [onDestroy]: cancels coroutine scope
 *
 * Requires Notification Access permission granted by the user in system settings.
 */
@AndroidEntryPoint
class PaymentNotificationListener : NotificationListenerService() {

    @Inject lateinit var capturedPaymentDao: CapturedPaymentDao
    @Inject lateinit var notificationParser: NotificationParser
    @Inject lateinit var paymentMatcher: PaymentMatcher
    @Inject lateinit var listenerPrefs: ListenerPrefsStore
    @Inject lateinit var paymentAlertBroadcaster: PaymentAlertBroadcaster
    @Inject lateinit var backendGateway: BackendGateway
    @Inject lateinit var posNotificationStatus: PosNotificationStatus

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Duplicate detection: track processed notification keys to prevent duplicate inserts. */
    private val processedKeys = mutableSetOf<String>()

    // ── Lifecycle ────────────────────────────────────────────────────────────────────────────

    override fun onListenerConnected() {
        super.onListenerConnected()
        startForegroundNotification()

        // Keep business-hours snapshot in sync with DataStore
        serviceScope.launch {
            listenerPrefs.refreshBusinessHoursSnapshot()
        }

        // Sync business hours from backend on connect (and refresh every hour)
        serviceScope.launch {
            syncBusinessHours()
        }

        Log.i(TAG, "PaymentNotificationListener connected — monitoring started")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        posNotificationStatus.updatePaymentMonitorStatus(active = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.w(TAG, "NotificationListenerService disconnected — permission may have been revoked")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    // ── Notification handling ────────────────────────────────────────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        // Step 1: Filter — only process monitored eWallet packages
        if (!isMonitoredPackage(packageName)) return

        // Step 2: Duplicate detection — skip if already processed
        if (!processedKeys.add(sbn.key)) return

        // Bound the processedKeys set to prevent unbounded memory growth
        if (processedKeys.size > MAX_PROCESSED_KEYS) {
            val iterator = processedKeys.iterator()
            repeat(processedKeys.size - MAX_PROCESSED_KEYS) {
                iterator.next()
                iterator.remove()
            }
        }

        // Step 2.5: Business-hour gate — skip processing outside trading hours
        if (!listenerPrefs.isWithinBusinessHours()) {
            Log.d(TAG, "Notification from $packageName skipped — outside business hours")
            return
        }

        // Step 3: Parse
        val parsed = notificationParser.parse(sbn) ?: return

        // Step 4: Store and match (off main thread)
        serviceScope.launch {
            val capturedPayment = CapturedPayment(
                id = UUID.randomUUID().toString(),
                amountSen = parsed.amountSen,
                walletApp = parsed.walletApp.name,
                packageName = packageName,
                sender = parsed.sender,
                reference = parsed.reference,
                rawTitle = parsed.rawTitle,
                rawText = parsed.rawText,
                matchStatus = MatchStatus.UNMATCHED.name,
                matchedOrderId = null,
                capturedAt = Instant.now().toString(),
                matchedAt = null,
            )
            capturedPaymentDao.insert(capturedPayment)

            // Step 4b: Broadcast payment alert to other connected devices
            paymentAlertBroadcaster.broadcastPaymentReceived(capturedPayment)

            // Step 5: Match
            val result = paymentMatcher.matchPayment(capturedPayment)
            when (result) {
                is MatchResult.SingleMatch -> Log.i(TAG, "Auto-matched payment to order ${result.orderId}")
                is MatchResult.MultipleMatches -> Log.i(TAG, "Ambiguous: ${result.orders.size} candidates")
                is MatchResult.NoMatch -> Log.d(TAG, "No matching order for ${parsed.amountSen} sen")
                is MatchResult.Error -> {
                    Log.e(TAG, "Match error: ${result.message}")
                    showErrorNotification(
                        "Payment Match Failed",
                        "Captured ${parsed.amountSen / 100.0} RM but failed to process: ${result.message}"
                    )
                }
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────────────────────

    /**
     * Fetches business hours from the backend and caches them in ListenerPrefsStore.
     * Called on each onListenerConnected and refreshes once per hour while the service is running.
     */
    private suspend fun syncBusinessHours() {
        while (true) {
            try {
                when (val result = backendGateway.getSettings()) {
                    is ApiResult.Success -> {
                        listenerPrefs.setCachedBusinessDayStartHour(result.data.businessDayStartHour)
                        listenerPrefs.setCachedBusinessDayEndHour(result.data.businessDayEndHour)
                        Log.i(TAG, "Business hours synced: ${result.data.businessDayStartHour}-${result.data.businessDayEndHour}")
                    }
                    is ApiResult.Error -> {
                        Log.w(TAG, "Failed to sync business hours: ${result.message}")
                    }
                    is ApiResult.NetworkError -> {
                        Log.w(TAG, "Network error syncing business hours: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception syncing business hours", e)
            }
            // Refresh every hour
            delay(60 * 60 * 1000L)
        }
    }

    /**
     * Checks whether the given package is in the monitored superset.
     * Uses [WalletApp.allPackages] for a fast synchronous check on the main thread.
     */
    private fun isMonitoredPackage(packageName: String): Boolean {
        return WalletApp.allPackages().contains(packageName)
    }

    /**
     * Shows an error notification to the admin when processPayment fails for an auto-matched order.
     * Uses IMPORTANCE_HIGH to ensure visibility of payment processing failures.
     */
    private fun showErrorNotification(title: String, message: String) {
        val channelId = "payment_error_channel"
        val channel = NotificationChannel(
            channelId,
            "Payment Errors",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Errors during automatic payment matching"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(ERROR_NOTIFICATION_ID, notification)
    }

    /**
     * Starts a foreground notification using the shared [PosNotificationStatus] so both
     * RealtimeService and this listener appear as a single notification row.
     */
    private fun startForegroundNotification() {
        // Update the shared notification status (adds "Payment monitor: active" line)
        val sleeping = !listenerPrefs.isWithinBusinessHours()
        posNotificationStatus.updatePaymentMonitorStatus(active = true, sleeping = sleeping)

        // Use the shared notification for this service's foreground requirement
        val notification = posNotificationStatus.buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                PosNotificationStatus.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(PosNotificationStatus.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "PaymentNotifListener"
        private const val ERROR_NOTIFICATION_ID = 3003

        /** Maximum number of notification keys to track for duplicate detection. */
        private const val MAX_PROCESSED_KEYS = 1000

        /**
         * Checks whether Notification Access permission is granted for this service.
         */
        fun isPermissionGranted(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            val component = ComponentName(context, PaymentNotificationListener::class.java)
            return enabledListeners?.contains(component.flattenToString()) == true
        }

        /**
         * Opens the Android system settings screen for Notification Access,
         * where the user can grant or revoke permission for this listener.
         */
        fun openNotificationAccessSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
