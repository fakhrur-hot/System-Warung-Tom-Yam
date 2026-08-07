package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.local.LocalPrefs
import com.razstudio.pos.data.local.PrintJob
import com.razstudio.pos.data.local.PrintJobDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Device-local UI preferences (persistent print status, low-stock alerts) plus the recent
 * print-job feed used by the persistent kitchen-print status view.
 */
@HiltViewModel
class DevicePrefsViewModel @Inject constructor(
    private val localPrefs: LocalPrefs,
    printJobDao: PrintJobDao,
    /** Owns the configurable new-order alert (tone + volume); shared with RealtimeService. */
    val newOrderSound: com.razstudio.pos.realtime.NewOrderSoundPlayer,
) : ViewModel() {

    fun showPrintStatus(): Boolean = localPrefs.showPrintStatus
    fun setShowPrintStatus(enabled: Boolean) { localPrefs.showPrintStatus = enabled }

    /** Hide the Android system bars. Applied to the window by the caller — see FullscreenMode. */
    fun fullscreenMode(): Boolean = localPrefs.fullscreenMode
    fun setFullscreenMode(enabled: Boolean) { localPrefs.fullscreenMode = enabled }

    fun lowStockAlerts(): Boolean = localPrefs.lowStockAlerts
    fun setLowStockAlerts(enabled: Boolean) { localPrefs.lowStockAlerts = enabled }

    /** Recent print jobs (newest first) with their QUEUED/PRINTING/COMPLETED/FAILED status. */
    val recentPrints: StateFlow<List<PrintJob>> = printJobDao.getRecentFlow(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
