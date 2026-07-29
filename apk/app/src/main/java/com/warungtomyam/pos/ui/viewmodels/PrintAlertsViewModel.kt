package com.warungtomyam.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.warungtomyam.pos.printing.PrintAlert
import com.warungtomyam.pos.printing.PrinterDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject

/**
 * Thin ViewModel that exposes [PrinterDispatcher.alerts] to the UI layer.
 *
 * Kept deliberately minimal — no UI state, no transformation.
 * Its only purpose is to bridge the @Singleton [PrinterDispatcher] into
 * a Composable via the standard hiltViewModel() pattern so that the
 * SharedFlow is collected once at app-wide scope (AdminHomeScreen) and
 * survives screen navigation.
 */
@HiltViewModel
class PrintAlertsViewModel @Inject constructor(
    private val printerDispatcher: PrinterDispatcher
) : ViewModel() {

    val alerts: SharedFlow<PrintAlert> = printerDispatcher.alerts

    /** Localized, user-facing message for a [PrintAlert] (formatted by the dispatcher). */
    fun message(alert: PrintAlert): String = printerDispatcher.toMessage(alert)
}
