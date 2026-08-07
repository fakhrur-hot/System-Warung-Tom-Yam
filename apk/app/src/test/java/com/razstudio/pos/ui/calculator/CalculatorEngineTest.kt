package com.razstudio.pos.ui.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The counter calculator's arithmetic.
 *
 * Worth testing directly because the failure mode is silent: a cashier reads the number off the
 * screen and takes that much money. A wrong answer here does not crash, does not log, and is not
 * noticed until the drawer is counted.
 */
class CalculatorEngineTest {

    private fun type(digits: String, from: CalculatorEngine.State = CalculatorEngine.State()) =
        digits.fold(from) { acc, c ->
            if (c == '.') CalculatorEngine.decimalPoint(acc) else CalculatorEngine.digit(acc, c)
        }

    @Test
    fun `decimal addition does not drift the way binary floats do`() {
        // 0.1 + 0.2 is 0.30000000000000004 in Double. On a receipt that is a number that does not
        // reconcile with the coins, and a cashier who cannot explain it.
        var s = type("0.1")
        s = CalculatorEngine.operation(s, CalculatorEngine.Op.ADD)
        s = type("0.2", s)
        s = CalculatorEngine.evaluate(s)
        assertEquals("0.3", s.display)
    }

    @Test
    fun `chaining an operator folds the sum so far`() {
        var s = type("2")
        s = CalculatorEngine.operation(s, CalculatorEngine.Op.ADD)
        s = type("3", s)
        s = CalculatorEngine.operation(s, CalculatorEngine.Op.ADD)   // folds 2+3 before continuing
        s = type("4", s)
        s = CalculatorEngine.evaluate(s)
        assertEquals("9", s.display)
    }

    @Test
    fun `adding a percentage is the service-charge case`() {
        val s = CalculatorEngine.addPercent(type("50"), BigDecimal(6))
        assertEquals("53", s.display)
    }

    @Test
    fun `subtracting a percentage is the staff-discount case`() {
        val s = CalculatorEngine.subtractPercent(type("50"), BigDecimal(10))
        assertEquals("45", s.display)
    }

    @Test
    fun `plain percent divides by a hundred`() {
        assertEquals("0.06", CalculatorEngine.percent(type("6")).display)
    }

    @Test
    fun `dividing by zero leaves the state untouched rather than showing infinity`() {
        var s = type("8")
        s = CalculatorEngine.operation(s, CalculatorEngine.Op.DIVIDE)
        s = type("0", s)
        val after = CalculatorEngine.evaluate(s)
        assertEquals("a refused keystroke is easier to recover from than an error state", s, after)
    }

    @Test
    fun `a leading zero is replaced, not appended`() {
        assertEquals("5", type("05").display)
    }

    @Test
    fun `only one decimal point is accepted`() {
        assertEquals("1.5", type("1.5.2").display.take(3))
    }

    @Test
    fun `clearing all keeps every memory`() {
        // The whole point of the memories: they are standing figures a café relies on, and no key
        // anywhere in this feature may discard them.
        var s = CalculatorEngine.memoryStore(type("42"), 2)
        s = CalculatorEngine.clearAll(s)
        assertEquals("0", s.display)
        assertEquals(BigDecimal("42.00"), s.memories[2])
    }

    @Test
    fun `there is no way to clear a memory, only to overwrite it`() {
        var s = CalculatorEngine.memoryStore(type("10"), 1)
        s = CalculatorEngine.memoryStore(type("20", CalculatorEngine.clearAll(s)), 1)
        assertEquals(BigDecimal("20.00"), s.memories[1])
        assertTrue("a slot once written stays written", s.memories.containsKey(1))
    }

    @Test
    fun `recalling an unset memory does nothing`() {
        val s = type("7")
        assertEquals(s, CalculatorEngine.memoryRecall(s, 3))
    }

    @Test
    fun `a digit after equals starts a new sum instead of appending to the answer`() {
        var s = type("2")
        s = CalculatorEngine.operation(s, CalculatorEngine.Op.ADD)
        s = type("3", s)
        s = CalculatorEngine.evaluate(s)
        assertEquals("5", s.display)
        s = CalculatorEngine.digit(s, '7')
        assertEquals("7", s.display)
    }

    @Test
    fun `the history line shows the evaluated expression`() {
        var s = type("6")
        s = CalculatorEngine.operation(s, CalculatorEngine.Op.MULTIPLY)
        s = type("7", s)
        s = CalculatorEngine.evaluate(s)
        assertEquals("6 × 7 =", s.history)
        assertEquals("42", s.display)
    }

    @Test
    fun `entry is capped so leaning on the keypad cannot overflow the display`() {
        val s = type("123456789012345678")
        assertTrue("entry stayed bounded", s.display.length <= 12)
    }
}
