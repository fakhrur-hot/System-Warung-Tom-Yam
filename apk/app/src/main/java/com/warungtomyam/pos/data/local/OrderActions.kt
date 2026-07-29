package com.warungtomyam.pos.data.local

/**
 * The single authority on which actions an order permits in a given [OrderStatus].
 *
 * Both the admin and staff order-detail sheets derive their button visibility from
 * here, instead of comparing raw status strings — so the transition rules live in
 * one place and can't drift between roles or be broken by a string typo.
 */
object OrderActions {

    /** Send-to-kitchen is only valid on a freshly received order. */
    fun canSendToKitchen(status: OrderStatus): Boolean =
        status == OrderStatus.RECEIVED

    /** Payment is valid once an order has been sent to the kitchen and before it is terminal. */
    fun canTakePayment(status: OrderStatus): Boolean =
        status == OrderStatus.SENT_TO_KITCHEN ||
            status == OrderStatus.PREPARING ||
            status == OrderStatus.READY

    /** Any non-terminal order can be cancelled. */
    fun canCancel(status: OrderStatus): Boolean = !status.isTerminal
}
