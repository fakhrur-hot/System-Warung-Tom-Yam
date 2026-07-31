package com.razstudio.pos.data.local

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

    /**
     * Whether the "Show QR" button appears on the order-detail sheet
     * (Requirements 13.3, 13.9, 14.7 — Property 9: the Payment QR is mode-independent).
     *
     * Note what this function does **not** take: an `OperatingMode`. That absence is the property,
     * not an omission. The Payment QR is a static payee image held on the device, so it works
     * identically in Cloud, LAN and Kiosk Mode; a mode parameter here would be the first step
     * towards a café whose QR silently stops appearing after a topology change.
     *
     * [hasStoredImage] is required alongside [paymentQrHash] because the two can disagree: a hash
     * survives in [com.razstudio.pos.data.AppConfigStore] while the file behind it does not (a
     * partial wipe, or a download that failed after the hash was recorded). Showing the button in
     * that state would put a control in front of a waiting customer that opens an empty dialog,
     * which Requirement 13.3 rules out — absent beats present-then-broken.
     */
    fun canShowPaymentQr(
        hasPaymentPermission: Boolean,
        status: OrderStatus,
        paymentQrHash: String?,
        hasStoredImage: Boolean,
    ): Boolean =
        hasPaymentPermission &&
            canTakePayment(status) &&
            paymentQrHash != null &&
            hasStoredImage
}
