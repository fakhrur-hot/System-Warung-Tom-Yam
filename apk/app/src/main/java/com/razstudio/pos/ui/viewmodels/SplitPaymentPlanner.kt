package com.razstudio.pos.ui.viewmodels

import com.razstudio.pos.data.NewOrderItem
import com.razstudio.pos.data.VoidLine
import com.razstudio.pos.data.local.OrderItem

/**
 * Works out what one customer in a group is paying for, and what that leaves behind.
 *
 * ## Why a group bill is not one payment
 *
 * Four friends at one table order together and pay separately. Until now the till could only settle
 * the whole order with a single method, so the cashier either made one person pay for everyone or
 * did the arithmetic on paper and lied to the till about who paid how.
 *
 * ## The model, and the one thing to understand about it
 *
 * There is no partial-payment concept anywhere in this app: `orders-payment` sets an order to
 * COMPLETED, records one payment method, and ends the table session. Rather than invent one — a new
 * table, a new endpoint, a Room migration and three modes of `LocalBackend` to match — each
 * customer's share becomes **its own paid order**:
 *
 *  1. the selected lines are lifted into a new order, which is paid with that customer's method;
 *  2. the original is shrunk by exactly what was lifted.
 *
 * Revenue stays exactly right, and Cash-versus-QR is recorded truthfully per customer. The known
 * cost, accepted deliberately: the day's *order count* counts each share, so four friends read as
 * four orders and average order value falls.
 *
 * **The last share is different and must stay that way.** When a selection covers everything that is
 * left, nothing is lifted — the original order is simply paid. That is what ends the table session,
 * fires the receipt prompt and closes the table, all through the path that already exists. Slicing
 * the final share off instead would leave an empty, unpayable order holding the table open.
 *
 * This class is pure arithmetic so it can be tested without a backend; the ViewModel performs the
 * calls it describes.
 */
object SplitPaymentPlanner {

    /** One line of the bill, and how many of it the current customer is taking. */
    data class Selection(
        val item: OrderItem,
        val takeQuantity: Int,
    ) {
        val lineTotal: Double get() = item.unitPriceSnapshot * takeQuantity
    }

    sealed class Plan {
        /**
         * The customer is paying for everything left. Pay the original order and let the existing
         * flow end the session and offer the receipt.
         */
        data class SettleWholeOrder(val amount: Double) : Plan()

        /**
         * A share. Create [sliceItems] as their own order, pay it, then shrink the original to
         * [keepLines].
         */
        data class SliceOff(
            val sliceItems: List<NewOrderItem>,
            val keepLines: List<VoidLine>,
            val amount: Double,
        ) : Plan()

        /** Nothing selected. The pay buttons stay disabled rather than doing nothing on tap. */
        data object NothingSelected : Plan()
    }

    /**
     * @param items what is still unpaid on the order
     * @param taken item id → quantity this customer is paying for
     */
    fun plan(items: List<OrderItem>, taken: Map<String, Int>): Plan {
        // Clamp defensively: a selection above what remains would create a slice the order cannot
        // cover, and the original would go negative rather than to zero.
        val selections = items.mapNotNull { item ->
            val q = (taken[item.id] ?: 0).coerceIn(0, item.quantity)
            if (q > 0) Selection(item, q) else null
        }
        if (selections.isEmpty()) return Plan.NothingSelected

        val amount = selections.sumOf { it.lineTotal }

        val takesEverything = items.all { item ->
            (taken[item.id] ?: 0).coerceIn(0, item.quantity) == item.quantity
        }
        if (takesEverything) return Plan.SettleWholeOrder(amount)

        return Plan.SliceOff(
            sliceItems = selections.map {
                NewOrderItem(
                    menuItemId = it.item.menuItemId,
                    quantity = it.takeQuantity,
                    note = it.item.note,
                    // The price the customer was quoted, not today's menu price. A variable-price
                    // item repriced mid-service must not change what this table is charged.
                    unitPrice = it.item.unitPriceSnapshot,
                )
            },
            // Every line is sent, including untouched ones: the void endpoint takes the quantity to
            // KEEP, so omitting a line would read as "keep none of it" and wipe it from the bill.
            keepLines = items.map { item ->
                val q = (taken[item.id] ?: 0).coerceIn(0, item.quantity)
                VoidLine(itemId = item.id, keepQuantity = item.quantity - q)
            },
            amount = amount,
        )
    }

    /**
     * The "this never reached the table" edit: reduce a line, never raise it.
     *
     * Raising a quantity here would be taking a new order at the payment screen, after the kitchen
     * has cooked and without a slip — so the stepper only goes down, and down to zero clears the
     * line. Returns the keep-lines for [com.razstudio.pos.data.BackendGateway.voidOrderItems].
     */
    fun reduceTo(items: List<OrderItem>, newQuantities: Map<String, Int>): List<VoidLine> =
        items.map { item ->
            val q = (newQuantities[item.id] ?: item.quantity).coerceIn(0, item.quantity)
            VoidLine(itemId = item.id, keepQuantity = q)
        }

    /** What is still owed once [taken] is settled — shown as the running remainder. */
    fun remainderAfter(items: List<OrderItem>, taken: Map<String, Int>): Double =
        items.sumOf { item ->
            val q = (taken[item.id] ?: 0).coerceIn(0, item.quantity)
            item.unitPriceSnapshot * (item.quantity - q)
        }
}
