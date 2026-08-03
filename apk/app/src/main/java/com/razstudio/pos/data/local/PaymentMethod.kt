package com.razstudio.pos.data.local

/**
 * How a bill was settled. (PG-REQ-1, task 5.2)
 *
 * **`Order.paymentMethod` stays a `String`.** Every existing row holds `"CASH"` or `"QR"` written
 * as free text, and reports, receipts, the web status page and the split-payment flow all compare
 * against those literals. Converting the column to an enum type would need a migration that could
 * not represent a value it did not recognise — so this is a *catalog over* the column, not a
 * replacement for it. [code] is the stored value and is a storage key: renaming one silently
 * orphans every historical bill paid that way. (A4, A11)
 *
 * Codes here are **aggregator-neutral**. The mapping to a gateway's own channel strings (Fiuu's
 * `RPP_DuitNowQR`, `TNG-EWALLET`, …) belongs with the gateway client, not here — a café that
 * changes acquirer must not have its order history rewritten. (designs.md F1)
 */
enum class PaymentMethod(
    val code: String,
    val category: PaymentCategory,
    /** False for methods that need a live gateway — hidden off-cloud. (A1, PG-REQ-3) */
    val worksOffline: Boolean,
) {
    /** Notes and coins. Written to records today but absent from the original catalog. (A11) */
    CASH("CASH", PaymentCategory.CASH, worksOffline = true),

    /**
     * The café's own static merchant QR, printed and stuck to the counter. The customer types the
     * amount themselves. Not a gateway payment and needs no internet — which is exactly why it
     * stays available in LAN and Kiosk Mode.
     */
    STATIC_QR("QR", PaymentCategory.QR_PAYNET, worksOffline = true),

    /**
     * A per-transaction DuitNow QR carrying the amount.
     *
     * The interoperable rail: MAE, TNG, Boost, GrabPay and the banking apps can all scan it, which
     * is why one tile here covers most of the wallet list and why the evaluated aggregator has no
     * separate MAE channel at all. Enable the per-wallet methods below only where their rates
     * justify a second tile. (designs.md F1)
     */
    DUITNOW_QR("DUITNOW_QR", PaymentCategory.QR_PAYNET, worksOffline = false),

    TNG("TNG", PaymentCategory.E_WALLET, worksOffline = false),
    GRABPAY("GRABPAY", PaymentCategory.E_WALLET, worksOffline = false),
    BOOST("BOOST", PaymentCategory.E_WALLET, worksOffline = false),
    SHOPEEPAY("SHOPEEPAY", PaymentCategory.E_WALLET, worksOffline = false),

    FPX("FPX", PaymentCategory.ONLINE_BANKING, worksOffline = false),

    /**
     * Credit or debit card, taken by **hosted checkout or a certified external reader only**.
     * Driving the card hardware in-process would put PIN entry and card data inside this app and
     * the deployment inside PCI-DSS scope. (PG-REQ-4d, designs.md H7/D7)
     */
    CARD("CARD", PaymentCategory.CARD, worksOffline = false);

    companion object {
        /**
         * Resolve a stored value, or null if nothing matches.
         *
         * Returns null rather than throwing or defaulting: a bill paid by a method this build does
         * not know about — an older code, or a newer one after a downgrade — must still render and
         * still be searchable. Callers show the raw string in that case, which is more honest than
         * relabelling someone's history as "Cash".
         */
        fun fromCode(code: String?): PaymentMethod? =
            code?.let { raw -> entries.firstOrNull { it.code.equals(raw, ignoreCase = true) } }

        /** Methods usable with no internet — the full catalog in LAN and Kiosk Mode. (A1) */
        fun offlineMethods(): List<PaymentMethod> = entries.filter { it.worksOffline }
    }

    /**
     * Human-readable name for this payment method, used on printed receipts (task 9.1).
     * Kept here so receipt printing and the UI grid resolve the same label.
     */
    fun displayName(): String = when (this) {
        CASH -> "Cash"
        STATIC_QR -> "QR"
        DUITNOW_QR -> "DuitNow QR"
        TNG -> "TNG eWallet"
        GRABPAY -> "GrabPay"
        BOOST -> "Boost"
        SHOPEEPAY -> "ShopeePay"
        FPX -> "FPX"
        CARD -> "Card"
    }
}

/** Grouping for the checkout grid. (PG-REQ-1) */
enum class PaymentCategory {
    CASH,
    QR_PAYNET,
    E_WALLET,
    ONLINE_BANKING,
    CARD,
}
