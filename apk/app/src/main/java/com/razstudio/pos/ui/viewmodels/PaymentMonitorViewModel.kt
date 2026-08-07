package com.razstudio.pos.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.notification.CapturedPayment
import com.razstudio.pos.notification.CapturedPaymentDao
import com.razstudio.pos.notification.ListenerPrefsStore
import com.razstudio.pos.notification.PaymentMatcher
import com.razstudio.pos.notification.PaymentNotificationListener
import com.razstudio.pos.notification.WalletApp
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Payment Monitor screen: listener toggles, permission status,
 * battery-optimization guidance, monitored app selection, and recent payment history.
 */
@HiltViewModel
class PaymentMonitorViewModel @Inject constructor(
    private val listenerPrefs: ListenerPrefsStore,
    private val capturedPaymentDao: CapturedPaymentDao,
    private val paymentMatcher: PaymentMatcher,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val uiState: StateFlow<PaymentMonitorUiState> = combine(
        listenerPrefs.isEnabled,
        listenerPrefs.monitoredPackages,
        listenerPrefs.autoStartOnBoot,
        listenerPrefs.soundEnabled,
        listenerPrefs.vibrationEnabled,
        listenerPrefs.toastNotificationEnabled,
        capturedPaymentDao.getRecentFlow(),
        listenerPrefs.cachedBusinessDayStartHour,
        listenerPrefs.cachedBusinessDayEndHour,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val isEnabled = values[0] as Boolean
        val monitoredPackages = values[1] as Set<String>
        val autoStart = values[2] as Boolean
        val sound = values[3] as Boolean
        val vibration = values[4] as Boolean
        val toast = values[5] as Boolean
        val recentPayments = values[6] as List<CapturedPayment>
        val businessStart = values[7] as Int
        val businessEnd = values[8] as Int

        val monitoredApps = WalletApp.entries.associateWith { app ->
            app.packages.any { pkg -> monitoredPackages.contains(pkg) }
        }

        PaymentMonitorUiState(
            isListenerEnabled = isEnabled,
            isPermissionGranted = PaymentNotificationListener.isPermissionGranted(context),
            isBatteryOptimized = isBatteryOptimized(),
            isAggressiveOem = isAggressiveOem(),
            monitoredApps = monitoredApps,
            autoStartOnBoot = autoStart,
            soundEnabled = sound,
            vibrationEnabled = vibration,
            toastNotificationEnabled = toast,
            recentPayments = recentPayments,
            businessStartHour = businessStart,
            businessEndHour = businessEnd,
            isWithinBusinessHours = listenerPrefs.isWithinBusinessHours(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PaymentMonitorUiState(),
    )

    // ── Toggle functions ─────────────────────────────────────────────────────────────────────

    fun toggleListener(enabled: Boolean) {
        viewModelScope.launch {
            listenerPrefs.setEnabled(enabled)
        }
    }

    fun toggleApp(walletApp: WalletApp, enabled: Boolean) {
        viewModelScope.launch {
            walletApp.packages.forEach { pkg ->
                listenerPrefs.togglePackage(pkg, enabled)
            }
        }
    }

    fun toggleAutoStart(enabled: Boolean) {
        viewModelScope.launch {
            listenerPrefs.setAutoStartOnBoot(enabled)
        }
    }

    fun toggleSound(enabled: Boolean) {
        viewModelScope.launch {
            listenerPrefs.setSoundEnabled(enabled)
        }
    }

    fun toggleVibration(enabled: Boolean) {
        viewModelScope.launch {
            listenerPrefs.setVibrationEnabled(enabled)
        }
    }

    fun toggleToast(enabled: Boolean) {
        viewModelScope.launch {
            listenerPrefs.setToastNotificationEnabled(enabled)
        }
    }

    // ── Permission and battery actions ───────────────────────────────────────────────────────

    fun openNotificationAccessSettings() {
        PaymentNotificationListener.openNotificationAccessSettings(context)
    }

    fun requestBatteryOptimizationBypass() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ── Payment resolution ───────────────────────────────────────────────────────────────────

    fun resolveAmbiguousPayment(capturedPaymentId: String, orderId: String) {
        viewModelScope.launch {
            paymentMatcher.resolveManually(capturedPaymentId, orderId)
        }
    }

    fun dismissPayment(capturedPaymentId: String) {
        viewModelScope.launch {
            paymentMatcher.dismiss(capturedPaymentId)
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the app is subject to battery optimization (i.e., NOT on the whitelist).
     * When battery-optimized, the OS may kill background services including the listener.
     */
    private fun isBatteryOptimized(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Checks whether the device manufacturer is known for aggressive battery management
     * that may kill background services beyond standard Android doze behavior.
     */
    private fun isAggressiveOem(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer in AGGRESSIVE_OEMS
    }

    companion object {
        /** OEMs known for aggressive battery optimization beyond stock Android behavior. */
        private val AGGRESSIVE_OEMS = setOf(
            "samsung",
            "xiaomi",
            "oppo",
            "huawei",
            "vivo",
            "oneplus",
        )
    }
}

/**
 * UI state for the Payment Monitor screen, combining listener preferences,
 * permission status, and recent payment history.
 */
data class PaymentMonitorUiState(
    val isListenerEnabled: Boolean = false,
    val isPermissionGranted: Boolean = false,
    val isBatteryOptimized: Boolean = true,
    val isAggressiveOem: Boolean = false,
    val monitoredApps: Map<WalletApp, Boolean> = emptyMap(),
    val autoStartOnBoot: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val toastNotificationEnabled: Boolean = true,
    val recentPayments: List<CapturedPayment> = emptyList(),
    val businessStartHour: Int = -1,
    val businessEndHour: Int = -1,
    val isWithinBusinessHours: Boolean = true,
)
