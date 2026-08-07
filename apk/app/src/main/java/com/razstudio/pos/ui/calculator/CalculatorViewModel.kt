package com.razstudio.pos.ui.calculator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.data.local.CalculatorStore
import com.razstudio.pos.printing.PrinterDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/**
 * The counter calculator, and the hidden way it opens the cash drawer.
 *
 * ## Two jobs, one of which must never be visible
 *
 * A cashier sometimes needs the drawer open with no sale attached — breaking a note for a customer,
 * dropping in a float, taking petty cash out. Every till has to allow it and no till should make it
 * obvious, because a visible "open drawer" button is an invitation to whoever is standing on the
 * other side of the counter, and an audit hole for whoever is standing on this side.
 *
 * So the drawer is opened by typing a PIN into what is, in every other respect, an ordinary
 * calculator, and pressing `=`. Someone watching sees arithmetic. This is the same convention
 * calculator-vault apps have used for years, and it works for the same reason: the cover
 * application is genuinely the application, so there is nothing to notice.
 *
 * ## The rule that makes it safe to type numbers all day
 *
 * The drawer only opens when the entry is **exactly** the PIN and nothing is pending — no operator,
 * no accumulator. `2 + 1234 =` is arithmetic and stays arithmetic even when the PIN is 1234, because
 * the cashier is plainly in the middle of a sum. Only a bare `1234 =` is the signal. Without that
 * rule a café whose PIN collided with a common price would pop the drawer during ordinary use, which
 * is worse than not having the feature.
 *
 * With no PIN set, this is only ever a calculator.
 */
@HiltViewModel
class CalculatorViewModel @Inject constructor(
    private val store: CalculatorStore,
    private val secureStorage: SecureStorage,
    private val printerDispatcher: PrinterDispatcher,
) : ViewModel() {

    /** What just happened, for a one-shot acknowledgement in the UI. */
    enum class Event {
        DRAWER_OPENED,

        /**
         * The drawer opened, but the PIN that opened it is still the factory default. Rendered as
         * a warning rather than a plain acknowledgement — every successful open on the default PIN
         * nags until the café sets its own, because "everyone who read the manual can open your
         * till" is a state worth being noisy about.
         */
        DRAWER_OPENED_DEFAULT_PIN,
    }

    private val _state = MutableStateFlow(
        CalculatorEngine.State(
            entry = store.entry,
            history = store.history,
            memories = store.readMemories(),
        )
    )
    val state: StateFlow<CalculatorEngine.State> = _state.asStateFlow()

    private val _event = MutableStateFlow<Event?>(null)
    val event: StateFlow<Event?> = _event.asStateFlow()

    fun consumeEvent() { _event.value = null }

    /** True when a drawer PIN has been set, so the UI can describe the feature honestly. */
    fun hasDrawerPin(): Boolean = secureStorage.getDrawerPin() != null

    // ── Keypad ───────────────────────────────────────────────────────────────────

    fun digit(d: Char) = update(CalculatorEngine.digit(_state.value, d))
    fun decimalPoint() = update(CalculatorEngine.decimalPoint(_state.value))
    fun operation(op: CalculatorEngine.Op) = update(CalculatorEngine.operation(_state.value, op))
    fun percent() = update(CalculatorEngine.percent(_state.value))
    fun addPercent(percent: BigDecimal) = update(CalculatorEngine.addPercent(_state.value, percent))
    fun subtractPercent(percent: BigDecimal) = update(CalculatorEngine.subtractPercent(_state.value, percent))
    fun clearEntry() = update(CalculatorEngine.clearEntry(_state.value))
    fun clearAll() = update(CalculatorEngine.clearAll(_state.value))
    fun backspace() = update(CalculatorEngine.backspace(_state.value))
    fun negate() = update(CalculatorEngine.negate(_state.value))

    fun memoryStore(slot: Int) {
        val next = CalculatorEngine.memoryStore(_state.value, slot)
        next.memories[slot]?.let { store.writeMemory(slot, it) }
        update(next)
    }

    fun memoryRecall(slot: Int) = update(CalculatorEngine.memoryRecall(_state.value, slot))

    /**
     * `=` — evaluate, unless this keystroke is the drawer signal.
     *
     * Checked *before* evaluating so the PIN never lands in the history line. Evaluating first and
     * then testing would leave "1234 =" on the upper display for the next person to read, which
     * would give away in one glance what the whole mechanism exists to hide.
     */
    fun equals() {
        val current = _state.value
        val pin = secureStorage.getDrawerPin()
        val isDrawerSignal = pin != null &&
            current.pending == null &&
            current.accumulator == null &&
            current.entry == pin

        if (isDrawerSignal) {
            // The entry is wiped rather than evaluated: the display must not be left holding the
            // PIN, and a cleared screen is what a cashier expects after the drawer springs open.
            update(CalculatorEngine.clearAll(current))
            viewModelScope.launch {
                printerDispatcher.kickCashDrawer()
                _event.value = if (secureStorage.hasCustomDrawerPin()) {
                    Event.DRAWER_OPENED
                } else {
                    Event.DRAWER_OPENED_DEFAULT_PIN
                }
            }
            return
        }
        update(CalculatorEngine.evaluate(current))
    }

    /**
     * Persist on every keystroke rather than on dismiss.
     *
     * The calculator is closed by walking away from it as often as by tapping a button — the app is
     * backgrounded, the screen sleeps, a customer arrives. Writing through means the number is still
     * there afterwards no matter which of those happened. These are two short strings in
     * SharedPreferences; the cost is not worth an optimisation that could lose a cashier's tally.
     */
    private fun update(next: CalculatorEngine.State) {
        _state.value = next
        store.entry = next.entry
        store.history = next.history
    }
}
