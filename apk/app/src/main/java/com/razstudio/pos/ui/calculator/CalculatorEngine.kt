package com.razstudio.pos.ui.calculator

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The arithmetic behind the counter calculator, with no Android in it.
 *
 * Separated from the UI because this is the part that can be *wrong* in ways nobody notices: a
 * cashier reads the number off the screen and takes that much money. Every rule below is therefore
 * exercised directly by `CalculatorEngineTest` rather than inferred from tapping buttons.
 *
 * ## Why BigDecimal and not Double
 *
 * `0.1 + 0.2` is `0.30000000000000004` in binary floating point. On a till that is not a curiosity —
 * it is a receipt that disagrees with the coins in the drawer, and a cashier who cannot explain why.
 * Money is decimal, so the arithmetic is decimal.
 *
 * ## The percent keys are the café-specific part
 *
 * A scientific `%` divides by a hundred and is useless at a counter. What a café actually asks is
 * "what is this plus six percent" (service charge, SST) or "minus ten" (staff discount), so those
 * are single keys operating on the running value rather than something to be assembled out of
 * multiplications each time.
 */
object CalculatorEngine {

    /** Digits after the decimal point that a result is rounded to. Ringgit and sen. */
    private const val SCALE = 2

    /** How many characters of entry to accept, so a lean on the keypad cannot overflow the display. */
    private const val MAX_INPUT_LENGTH = 12

    enum class Op { ADD, SUBTRACT, MULTIPLY, DIVIDE }

    /**
     * The whole calculator, as one immutable value.
     *
     * @param entry what the cashier is typing right now — a *string*, not a number, because "5.",
     *   "0.10" and "" are all states a number cannot represent and all states the display must show
     *   back exactly as typed.
     * @param accumulator the value carried into a pending operation.
     * @param pending the operation waiting for its right-hand side.
     * @param history the upper display line: the expression so far, or the last one evaluated.
     * @param justEvaluated true immediately after `=`, so the next digit starts a fresh sum instead
     *   of appending to the answer.
     */
    data class State(
        val entry: String = "",
        val accumulator: BigDecimal? = null,
        val pending: Op? = null,
        val history: String = "",
        val justEvaluated: Boolean = false,
        val memories: Map<Int, BigDecimal> = emptyMap(),
    ) {
        /** The lower display line — what a cashier reads a number off. Never blank. */
        val display: String
            get() = when {
                entry.isNotEmpty() -> entry
                accumulator != null -> accumulator.strip()
                else -> "0"
            }

        /** The value the next operation applies to. */
        val current: BigDecimal
            get() = entry.toBigDecimalOrNull() ?: accumulator ?: BigDecimal.ZERO
    }

    fun digit(state: State, d: Char): State {
        val base = if (state.justEvaluated) State(memories = state.memories) else state
        // A leading zero is replaced rather than appended, so "0" then "5" reads 5 and not 05.
        val next = when {
            base.entry == "0" && d != '.' -> d.toString()
            else -> base.entry + d
        }
        if (next.length > MAX_INPUT_LENGTH) return base
        return base.copy(entry = next, justEvaluated = false)
    }

    /** A decimal point, at most one, and never as a bare leading dot. */
    fun decimalPoint(state: State): State {
        val base = if (state.justEvaluated) State(memories = state.memories) else state
        if (base.entry.contains('.')) return base
        return base.copy(entry = if (base.entry.isEmpty()) "0." else base.entry + ".", justEvaluated = false)
    }

    /**
     * Chain an operation.
     *
     * Pressing `+` with a sum already pending evaluates it first, which is what makes
     * `2 + 3 + 4` read 9 rather than losing the middle term.
     */
    fun operation(state: State, op: Op): State {
        val folded = if (state.pending != null && state.entry.isNotEmpty()) evaluate(state) else state
        val value = folded.current
        return folded.copy(
            entry = "",
            accumulator = value,
            pending = op,
            history = "${value.strip()} ${op.symbol}",
            justEvaluated = false,
        )
    }

    /**
     * Apply the pending operation.
     *
     * Division by zero returns the state untouched rather than an error or infinity: there is
     * nothing sensible to show a cashier, and a calculator that refuses the keystroke is easier to
     * recover from than one that has to be cleared.
     */
    fun evaluate(state: State): State {
        val op = state.pending ?: return state.copy(
            history = state.current.strip(),
            justEvaluated = true,
        )
        val left = state.accumulator ?: BigDecimal.ZERO
        val right = state.current
        if (op == Op.DIVIDE && right.compareTo(BigDecimal.ZERO) == 0) return state

        val result = when (op) {
            Op.ADD -> left.add(right)
            Op.SUBTRACT -> left.subtract(right)
            Op.MULTIPLY -> left.multiply(right)
            Op.DIVIDE -> left.divide(right, SCALE, RoundingMode.HALF_UP)
        }.round()

        return State(
            entry = "",
            accumulator = result,
            pending = null,
            history = "${left.strip()} ${op.symbol} ${right.strip()} =",
            justEvaluated = true,
            memories = state.memories,
        )
    }

    /**
     * Plain percent: turn the number on the display into its percentage of itself — i.e. divide by
     * a hundred. `6 %` is `0.06`, for a cashier who wants the raw fraction.
     */
    fun percent(state: State): State = withValue(state) { it.divide(BigDecimal(100), SCALE, RoundingMode.HALF_UP) }

    /** Add a percentage to the running value: 50, then `+6%`, is 53.00. */
    fun addPercent(state: State, percent: BigDecimal): State =
        withValue(state) { it.add(it.multiply(percent).divide(BigDecimal(100), SCALE, RoundingMode.HALF_UP)) }

    /** Take a percentage off the running value: 50, then `-10%`, is 45.00. */
    fun subtractPercent(state: State, percent: BigDecimal): State =
        withValue(state) { it.subtract(it.multiply(percent).divide(BigDecimal(100), SCALE, RoundingMode.HALF_UP)) }

    /**
     * Store the display into one of the three memories.
     *
     * There is no memory-clear key anywhere in this class, deliberately. A café uses these as
     * standing figures — the service-charge rate, a regular's usual total, the float in the drawer —
     * and an adjacent "clear" is one mis-tap away from destroying a number nobody wrote down.
     * Overwriting requires deciding what to put there instead, which is a different kind of mistake
     * and a much rarer one.
     */
    fun memoryStore(state: State, slot: Int): State =
        state.copy(memories = state.memories + (slot to state.current.round()))

    /** Recall a memory onto the entry line. A slot never written reads as zero. */
    fun memoryRecall(state: State, slot: Int): State {
        val value = state.memories[slot] ?: return state
        return state.copy(entry = value.strip(), justEvaluated = false)
    }

    /** Clear the entry only — the pending sum and the memories both survive. */
    fun clearEntry(state: State): State = state.copy(entry = "", justEvaluated = false)

    /** Clear the sum. Memories are untouched; see [memoryStore]. */
    fun clearAll(state: State): State = State(memories = state.memories)

    /** Backspace one character of the entry. */
    fun backspace(state: State): State {
        if (state.justEvaluated || state.entry.isEmpty()) return state
        return state.copy(entry = state.entry.dropLast(1))
    }

    /** Flip the sign of what is being entered. */
    fun negate(state: State): State {
        if (state.entry.isEmpty()) return state
        return state.copy(entry = if (state.entry.startsWith("-")) state.entry.drop(1) else "-" + state.entry)
    }

    private inline fun withValue(state: State, transform: (BigDecimal) -> BigDecimal): State {
        val result = transform(state.current).round()
        return state.copy(entry = result.strip(), pending = state.pending, justEvaluated = false)
    }

    private val Op.symbol: String
        get() = when (this) {
            Op.ADD -> "+"
            Op.SUBTRACT -> "−"
            Op.MULTIPLY -> "×"
            Op.DIVIDE -> "÷"
        }

    private fun BigDecimal.round(): BigDecimal = setScale(SCALE, RoundingMode.HALF_UP)

    /** Drop trailing zeros so 5.00 reads "5", but keep 5.50 as "5.5" rather than "5.5000". */
    private fun BigDecimal.strip(): String = stripTrailingZeros().toPlainString()
}
