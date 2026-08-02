package com.razstudio.pos.ui.viewmodels

import com.razstudio.pos.data.local.OrderItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A group bill has to add up. These pin the arithmetic that decides what a café is paid.
 *
 * The single most important property is the last one: **the shares must sum to the bill.** Split
 * payment lifts items onto separate orders, so an error here does not show up as a crash — it shows
 * up as a café that took less money than it served, discovered at closing, with no way to tell which
 * table it happened at.
 */
class SplitPaymentPlannerTest {

    private fun item(id: String, price: Double, qty: Int) = OrderItem(
        id = id,
        orderId = "order-1",
        menuItemId = "menu-$id",
        nameSnapshot = "Item $id",
        unitPriceSnapshot = price,
        categorySnapshot = "MAIN",
        quantity = qty,
    )

    private val bill = listOf(
        item("a", 10.0, 2),   // 20.00
        item("b", 5.0, 1),    //  5.00
        item("c", 3.0, 4),    // 12.00
    )                          // total 37.00

    // ── The last share is not a slice ────────────────────────────────────────────────────────────

    @Test
    fun takingEverythingSettlesTheOriginalOrder() {
        // Slicing the final share off would leave an empty order still holding the table open, and
        // the session-end plus receipt prompt hang off paying the original.
        val plan = SplitPaymentPlanner.plan(bill, mapOf("a" to 2, "b" to 1, "c" to 4))

        assertTrue(plan is SplitPaymentPlanner.Plan.SettleWholeOrder)
        assertEquals(37.0, (plan as SplitPaymentPlanner.Plan.SettleWholeOrder).amount, 0.001)
    }

    @Test
    fun aSingleLineBillIsSettledNotSliced() {
        val one = listOf(item("solo", 8.0, 1))
        assertTrue(SplitPaymentPlanner.plan(one, mapOf("solo" to 1))
            is SplitPaymentPlanner.Plan.SettleWholeOrder)
    }

    // ── A share ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun oneCustomersShareIsPricedFromWhatTheyTook() {
        val plan = SplitPaymentPlanner.plan(bill, mapOf("a" to 1, "c" to 2))
                as SplitPaymentPlanner.Plan.SliceOff

        assertEquals(10.0 + 6.0, plan.amount, 0.001)
        assertEquals(2, plan.sliceItems.size)
    }

    @Test
    fun theOriginalKeepsExactlyWhatWasNotTaken() {
        val plan = SplitPaymentPlanner.plan(bill, mapOf("a" to 1, "c" to 2))
                as SplitPaymentPlanner.Plan.SliceOff
        val keep = plan.keepLines.associate { it.itemId to it.keepQuantity }

        assertEquals(1, keep["a"])
        assertEquals(2, keep["c"])
    }

    @Test
    fun untouchedLinesAreStillSentAsKeepAll() {
        // The void endpoint takes the quantity to KEEP. Omitting an untouched line would read as
        // "keep none of it" and silently wipe it off the bill — the café would serve it free.
        val plan = SplitPaymentPlanner.plan(bill, mapOf("a" to 1))
                as SplitPaymentPlanner.Plan.SliceOff
        val keep = plan.keepLines.associate { it.itemId to it.keepQuantity }

        assertEquals("every line must be listed", 3, plan.keepLines.size)
        assertEquals("b was untouched and must survive whole", 1, keep["b"])
        assertEquals(4, keep["c"])
    }

    @Test
    fun theSlicePricesAtWhatTheCustomerWasQuoted() {
        // Not today's menu price. A variable-price item repriced mid-service must not change what
        // this table is charged after the food has arrived.
        val plan = SplitPaymentPlanner.plan(bill, mapOf("a" to 1))
                as SplitPaymentPlanner.Plan.SliceOff

        assertEquals(10.0, plan.sliceItems.first().unitPrice!!, 0.001)
    }

    // ── The property the money depends on ────────────────────────────────────────────────────────

    @Test
    fun successiveSharesSumToTheWholeBill() {
        // Three customers settling one table, in sequence. What the café banks must equal the bill.
        var remaining = bill
        var banked = 0.0

        // Customer 1 takes one "a".
        val p1 = SplitPaymentPlanner.plan(remaining, mapOf("a" to 1)) as SplitPaymentPlanner.Plan.SliceOff
        banked += p1.amount
        remaining = applyKeep(remaining, p1.keepLines.associate { it.itemId to it.keepQuantity })

        // Customer 2 takes "b" and two "c".
        val p2 = SplitPaymentPlanner.plan(remaining, mapOf("b" to 1, "c" to 2)) as SplitPaymentPlanner.Plan.SliceOff
        banked += p2.amount
        remaining = applyKeep(remaining, p2.keepLines.associate { it.itemId to it.keepQuantity })

        // Customer 3 takes the rest, which settles the order.
        val p3 = SplitPaymentPlanner.plan(remaining, remaining.associate { it.id to it.quantity })
                as SplitPaymentPlanner.Plan.SettleWholeOrder
        banked += p3.amount

        assertEquals("the café must bank exactly the bill", 37.0, banked, 0.001)
    }

    @Test
    fun theRemainderShrinksByWhatWasTaken() {
        assertEquals(37.0, SplitPaymentPlanner.remainderAfter(bill, emptyMap()), 0.001)
        assertEquals(27.0, SplitPaymentPlanner.remainderAfter(bill, mapOf("a" to 1)), 0.001)
        assertEquals(0.0, SplitPaymentPlanner.remainderAfter(bill, mapOf("a" to 2, "b" to 1, "c" to 4)), 0.001)
    }

    // ── Nothing selected, and nonsense selections ────────────────────────────────────────────────

    @Test
    fun nothingSelectedIsItsOwnAnswer() {
        assertTrue(SplitPaymentPlanner.plan(bill, emptyMap()) is SplitPaymentPlanner.Plan.NothingSelected)
        assertTrue(SplitPaymentPlanner.plan(bill, mapOf("a" to 0)) is SplitPaymentPlanner.Plan.NothingSelected)
    }

    @Test
    fun takingMoreThanIsOnTheBillIsClamped() {
        // A stepper bug, or a stale list after another device paid. Charging for six of something
        // the table has two of is worse than the bug that produced it.
        val plan = SplitPaymentPlanner.plan(bill, mapOf("a" to 6, "b" to 1, "c" to 4))

        assertTrue(plan is SplitPaymentPlanner.Plan.SettleWholeOrder)
        assertEquals(37.0, (plan as SplitPaymentPlanner.Plan.SettleWholeOrder).amount, 0.001)
    }

    @Test
    fun anUnknownItemIdIsIgnored() {
        assertTrue(SplitPaymentPlanner.plan(bill, mapOf("ghost" to 3))
            is SplitPaymentPlanner.Plan.NothingSelected)
    }

    // ── The "it never arrived" edit ──────────────────────────────────────────────────────────────

    @Test
    fun reducingALineOnlyEverGoesDown() {
        // Raising a quantity here would be taking a new order at the payment screen, after the
        // kitchen has cooked and with no slip printed.
        val lines = SplitPaymentPlanner.reduceTo(bill, mapOf("a" to 5, "c" to 1))
            .associate { it.itemId to it.keepQuantity }

        assertEquals("cannot exceed what was ordered", 2, lines["a"])
        assertEquals(1, lines["c"])
    }

    @Test
    fun reducingToZeroClearsTheLine() {
        val lines = SplitPaymentPlanner.reduceTo(bill, mapOf("b" to 0))
            .associate { it.itemId to it.keepQuantity }

        assertEquals(0, lines["b"])
        assertEquals("other lines are untouched", 2, lines["a"])
    }

    /** Mirrors what the backend does with keep-lines, so the sequence test stays honest. */
    private fun applyKeep(items: List<OrderItem>, keep: Map<String, Int>): List<OrderItem> =
        items.mapNotNull { item ->
            val q = keep[item.id] ?: item.quantity
            if (q <= 0) null else item.copy(quantity = q)
        }
}
