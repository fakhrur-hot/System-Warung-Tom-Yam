package com.razstudio.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-typed "+ Customized" charge. Everything here guards money the cashier typed under time
 * pressure at a counter: the price field is the only thing standing between a mis-key and a wrong
 * bill, and a rejected parse is what keeps the Add button disabled rather than billing a nonsense
 * amount.
 */
class CustomChargeTest {

    @Test
    fun `plain and decimal amounts parse`() {
        assertEquals(12.0, parseCustomChargePrice("12")!!, 0.001)
        assertEquals(12.5, parseCustomChargePrice("12.5")!!, 0.001)
        assertEquals(1234.56, parseCustomChargePrice("1234.56")!!, 0.001)
    }

    @Test
    fun `comma decimal separator and an RM prefix are accepted`() {
        // Some Malaysian keyboard layouts offer a comma on the decimal pad, and a cashier who types
        // "RM 5.00" out of habit means five ringgit, not a validation error.
        assertEquals(12.5, parseCustomChargePrice("12,50")!!, 0.001)
        assertEquals(5.0, parseCustomChargePrice("RM 5.00")!!, 0.001)
        assertEquals(5.0, parseCustomChargePrice("  5 ")!!, 0.001)
    }

    @Test
    fun `sub-sen precision rounds to money, not thirds of a sen`() {
        assertEquals(1.01, parseCustomChargePrice("1.005")!!, 0.001)
        assertEquals(1.0, parseCustomChargePrice("1.004")!!, 0.001)
    }

    @Test
    fun `zero, negative, unparseable and absurd amounts are refused`() {
        // Each of these must leave Add disabled: a free line and a negative line are both a hole in
        // the day's takings, and the cap catches a stuck keypress before it reaches a receipt.
        assertNull(parseCustomChargePrice(""))
        assertNull(parseCustomChargePrice("0"))
        assertNull(parseCustomChargePrice("0.00"))
        assertNull(parseCustomChargePrice("-5"))
        assertNull(parseCustomChargePrice("abc"))
        assertNull(parseCustomChargePrice("1.2.3"))
        assertNull(parseCustomChargePrice("999999999"))
    }

    @Test
    fun `a custom charge item is tagged, priced, and named verbatim`() {
        val item = customChargeMenuItem("Corkage fee", 5.5)
        assertTrue(item.isCustomCharge)
        assertTrue(item.id.startsWith(CUSTOM_CHARGE_ID_PREFIX))
        assertEquals("Corkage fee", item.nameEn)
        assertEquals(5.5, item.price, 0.001)
        // doNotTranslate: the cashier typed this in whatever language they were thinking in, and
        // there is no translation of it to look up.
        assertTrue(item.doNotTranslate)
    }

    @Test
    fun `two charges with the same name stay two separate lines`() {
        // The cart dedupes by menu item id, so a shared id would silently merge a RM 5 corkage and
        // a RM 20 one into a single line at whichever price was typed first.
        val a = customChargeMenuItem("Extra", 5.0)
        val b = customChargeMenuItem("Extra", 20.0)
        assertTrue(a.id != b.id)
    }

    @Test
    fun `an over-long name is truncated to the cap the servers enforce`() {
        val item = customChargeMenuItem("x".repeat(200), 1.0)
        assertEquals(CUSTOM_CHARGE_NAME_MAX, item.nameEn.length)
    }

    @Test
    fun `toNewOrderItem carries the typed name and price onto the wire`() {
        val wire = customChargeMenuItem("Replacement plate", 8.0).toNewOrderItem(quantity = 2)
        assertEquals("Replacement plate", wire.customName)
        assertEquals(8.0, wire.unitPrice!!, 0.001)
        assertEquals(2, wire.quantity)
        assertTrue(wire.menuItemId.startsWith(CUSTOM_CHARGE_ID_PREFIX))
    }

    @Test
    fun `a real menu item is untouched by the custom-charge path`() {
        val real = com.razstudio.pos.data.local.MenuItem(
            id = "m1",
            category = "FOOD",
            price = 7.0,
            available = true,
            askMeDaily = false,
            nameEn = "Nasi Goreng",
        )
        val wire = real.toNewOrderItem(quantity = 1, size = "L", unitPrice = 9.0)
        assertNull(wire.customName)
        // unitPrice stays exactly what the caller passed — the S/M/L price the server validates
        // against the item's presets, not a fallback to the base price.
        assertEquals(9.0, wire.unitPrice!!, 0.001)

        val plain = real.toNewOrderItem(quantity = 1)
        assertNull(plain.customName)
        assertNull(plain.unitPrice)
    }
}
