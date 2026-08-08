package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.data.local.CashDrawerEvent
import com.razstudio.pos.data.local.CashDrawerEventDao
import com.razstudio.pos.data.local.CashDrawerLedger
import com.razstudio.pos.printing.PrinterDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Drawer page: expected cash balance, the opening-float editor, PIN-gated
 * cash-out, and the audit trail.
 */
@HiltViewModel
class CashDrawerViewModel @Inject constructor(
    private val ledger: CashDrawerLedger,
    dao: CashDrawerEventDao,
    private val secureStorage: SecureStorage,
    private val printerDispatcher: PrinterDispatcher,
    private val printSettingsStore: com.razstudio.pos.data.local.PrintSettingsStore,
) : ViewModel() {

    /**
     * The Enable Cash Drawer master toggle (cash-drawer-settings R1/R7). Read fresh on each call —
     * the home menu recomposes every time it opens, and the setting can only change on another
     * screen, so a live flow would be machinery for a staleness that cannot occur.
     */
    fun isDrawerFeatureEnabled(): Boolean = printSettingsStore.isCashDrawerEnabled()

    /** Expected drawer content, in sen. What a manager counting the drawer should find. */
    val balanceSen: StateFlow<Long> = dao.getLatestFlow()
        .map { it?.balanceAfterSen ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Audit trail, newest first. */
    val events: StateFlow<List<CashDrawerEvent>> = dao.getRecentFlow(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One-shot messages for the screen (cash-out result, default-PIN warning). */
    data class Notice(val text: String, val isWarning: Boolean = false)

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()
    fun clearNotice() { _notice.value = null }

    // ── Opening float: committed only on an explicit Save ────────────────────────────────
    //
    // This used to autosave on a 1.2s typing pause, with no button. That turned every pause
    // mid-entry into a committed figure: keying RM 300 slowly wrote the float as RM 3, then RM 30,
    // and a manager who was interrupted halfway left the drawer's expected balance set to a number
    // they never meant to enter. The expected balance is what the end-of-day count is judged
    // against, so a half-typed value is a real discrepancy, not a cosmetic one.
    //
    // Keying is now free — nothing is written until Save is pressed, which is also what makes the
    // FLOAT_SET audit row correspond to a decision rather than to a pause in typing.

    /** Commit the keyed opening float. Called from the Save button, never from a keystroke. */
    fun saveOpeningFloat(floatSen: Long) {
        viewModelScope.launch {
            if (ledger.balanceSen() == floatSen) {
                _notice.value = Notice("Opening float unchanged: RM %.2f".format(floatSen / 100.0))
                return@launch
            }
            ledger.setOpeningFloat(floatSen)
            _notice.value = Notice("Opening float saved: RM %.2f".format(floatSen / 100.0))
        }
    }

    // ── Cash sale: fed by the tender pad ─────────────────────────────────────────────────

    /**
     * Append a settled cash payment to the ledger and kick the drawer open — the cashier owes
     * change *now*, receipt or not (the receipt path's own kick only fires when one is printed).
     * Demo sessions are theatre and must not move the real ledger.
     */
    fun recordCashSale(orderId: String, totalSen: Long, tenderedSen: Long) {
        if (com.razstudio.pos.data.demo.DemoSession.active) return
        viewModelScope.launch {
            ledger.recordCashSale(orderId, totalSen, tenderedSen)
            printerDispatcher.kickCashDrawer()
        }
    }

    // ── Cash out: PIN-gated, audited, opens the physical drawer ─────────────────────────

    /**
     * Take money out. Returns false when the PIN is wrong (the dialog stays up); on success the
     * physical drawer is kicked so the money can actually be taken, and the event is recorded —
     * flagged when the factory-default PIN authorised it, with a warning shown every time until
     * the café sets its own.
     */
    fun cashOut(amountSen: Long, pin: String): Boolean {
        if (pin != secureStorage.getDrawerPin()) return false
        val usedDefaultPin = !secureStorage.hasCustomDrawerPin()
        viewModelScope.launch {
            ledger.recordCashOut(amountSen, usedDefaultPin)
            printerDispatcher.kickCashDrawer()
            _notice.value = if (usedDefaultPin) {
                Notice(
                    "Cash out recorded — but the drawer PIN is still the factory default " +
                        "(${SecureStorage.DEFAULT_DRAWER_PIN}). Anyone who knows it can open the " +
                        "drawer. Change it in Settings.",
                    isWarning = true,
                )
            } else {
                Notice("Cash out recorded: RM %.2f".format(amountSen / 100.0))
            }
        }
        return true
    }
}
