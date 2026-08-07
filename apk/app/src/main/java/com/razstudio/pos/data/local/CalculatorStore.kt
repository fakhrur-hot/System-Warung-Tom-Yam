package com.razstudio.pos.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the counter calculator remembers between one use and the next.
 *
 * ## Why this outlives the app, and even the session
 *
 * A café uses the three memories as standing figures — the service-charge rate, the float that went
 * into the drawer this morning, a running tally somebody is building up across the shift. Those are
 * notes on a physical counter, not application state, and a note does not disappear because the
 * till was restarted or a shift ended. So this is deliberately **not** cleared on sign-out, which is
 * the one place a "tidy up the user's data" reflex would otherwise wipe it.
 *
 * The two display lines persist for the same reason: a cashier interrupted mid-sum by a customer
 * comes back to the number still on the screen, exactly as they would with the calculator that used
 * to sit beside the till.
 *
 * ## Why it is not encrypted
 *
 * Nothing here is secret — it is arithmetic. The drawer PIN, which *is* secret, lives in
 * `SecureStorage` and never touches this file.
 */
@Singleton
class CalculatorStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("calculator_prefs", Context.MODE_PRIVATE)

    /** The lower display line, as last shown. */
    var entry: String
        get() = prefs.getString(KEY_ENTRY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_ENTRY, value).apply() }

    /** The upper display line — the expression being built, or the last one evaluated. */
    var history: String
        get() = prefs.getString(KEY_HISTORY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HISTORY, value).apply() }

    /**
     * The three memory slots.
     *
     * Stored as plain strings via [BigDecimal.toPlainString] so the value that comes back is the
     * value that went in — a float round-trip through SharedPreferences would quietly turn 0.1 into
     * something that is nearly 0.1, which is the whole reason the engine avoids binary floats.
     *
     * A slot is only ever written, never removed: there is no memory-clear anywhere in this
     * feature. See `CalculatorEngine.memoryStore`.
     */
    fun readMemories(): Map<Int, BigDecimal> =
        SLOTS.mapNotNull { slot ->
            prefs.getString(memoryKey(slot), null)
                ?.let { raw -> runCatching { BigDecimal(raw) }.getOrNull() }
                ?.let { slot to it }
        }.toMap()

    fun writeMemory(slot: Int, value: BigDecimal) {
        prefs.edit().putString(memoryKey(slot), value.toPlainString()).apply()
    }

    private fun memoryKey(slot: Int) = "memory_$slot"

    companion object {
        /** M1, M2, M3 — three is what fits a counter keypad without becoming a filing system. */
        val SLOTS = listOf(1, 2, 3)

        private const val KEY_ENTRY = "entry"
        private const val KEY_HISTORY = "history"
    }
}
