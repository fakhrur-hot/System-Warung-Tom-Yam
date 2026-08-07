package com.razstudio.pos.notification

/**
 * Centralized regex patterns and keyword lists for parsing Malaysian eWallet
 * and banking payment notifications.
 *
 * Each app section documents the expected notification format and provides
 * patterns for extracting amount, sender, and reference information.
 *
 * This is a pure data/utility object with no external dependencies.
 */
object NotificationPatterns {

    // ═══════════════════════════════════════════════════════════════════════════
    // Universal Amount Pattern
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Universal RM amount extraction pattern.
     * Matches formats: "RM 15.00", "RM15.00", "RM 1,500.00"
     * Group 1 captures the numeric value (e.g., "15.00" or "1,500.00").
     */
    val AMOUNT_REGEX = Regex("""RM\s*([\d,]+\.\d{2})""")

    // ═══════════════════════════════════════════════════════════════════════════
    // Touch 'n Go eWallet (my.com.tngdigital.ewallet)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Keywords indicating a received payment in TNG eWallet notifications.
     * Notification title: "Payment Received" or "Money Received"
     * Example: "You have received RM 15.00 from Ali bin Abu"
     */
    val TNG_RECEIVED_KEYWORDS = listOf("received", "terima")

    /**
     * Extracts sender name from TNG eWallet notifications.
     * Matches "from <name>" or "dari <name>" up to end of line or punctuation.
     * Example: "You have received RM 15.00 from Ali bin Abu" → "Ali bin Abu"
     */
    val TNG_SENDER_REGEX = Regex("""(?:from|dari)\s+(.+?)(?:\s*$|\s*[.\-])""", RegexOption.IGNORE_CASE)

    // ═══════════════════════════════════════════════════════════════════════════
    // TNG Merchant (com.tng.merchant)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Extracts transaction reference from TNG Merchant notifications.
     * Example: "Payment of RM 15.00 received. Ref: TNG1234567890" → "TNG1234567890"
     */
    val TNG_MERCHANT_REF_REGEX = Regex("""(?:Reference|Ref)[:\s]*([A-Z0-9]+)""", RegexOption.IGNORE_CASE)

    // ═══════════════════════════════════════════════════════════════════════════
    // Boost (my.com.axiata.boostapp)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Extracts sender name from Boost notifications.
     * Matches "from <name>" up to end of line or "via".
     * Example: "You received RM 15.00 from Ali" → "Ali"
     * Also handles: "RM 15.00 has been transferred to your wallet"
     */
    val BOOST_SENDER_REGEX = Regex("""(?:from|dari)\s+(.+?)(?:\s*$|\s*via)""", RegexOption.IGNORE_CASE)

    // ═══════════════════════════════════════════════════════════════════════════
    // GrabPay Merchant (com.grab.merchant)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Extracts sender name from GrabPay Merchant notifications.
     * Example: "Payment received: RM 15.00 from Ali" → "Ali"
     */
    val GRAB_SENDER_REGEX = Regex("""(?:from|dari)\s+(.+?)(?:\s*$|\s*[.\-])""", RegexOption.IGNORE_CASE)

    /**
     * Extracts transaction ID from GrabPay Merchant notifications.
     * Example: "Payment received: RM 15.00. Transaction ID: GP123456" → "GP123456"
     */
    val GRAB_REF_REGEX = Regex("""(?:Transaction ID|ID Transaksi)[:\s]*([A-Z0-9]+)""", RegexOption.IGNORE_CASE)

    // ═══════════════════════════════════════════════════════════════════════════
    // ShopeePay (com.shopee.my)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Extracts sender name from ShopeePay notifications.
     * Matches "from <name>" up to end of line or opening parenthesis.
     * Example: "You've received RM 15.00 from buyer_name" → "buyer_name"
     */
    val SHOPEE_SENDER_REGEX = Regex("""(?:from|dari)\s+(.+?)(?:\s*$|\s*\()""", RegexOption.IGNORE_CASE)

    // ═══════════════════════════════════════════════════════════════════════════
    // Maybank MAE (com.maybank2u.life)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Extracts sender name from Maybank MAE notifications.
     * Strips trailing phone numbers (10+ digits).
     * Example: "RM15.00 received via DuitNow from Ali 0123456789" → "Ali"
     * Also: "You have received RM 15.00 via DuitNow QR"
     */
    val MAE_SENDER_REGEX = Regex("""(?:from|dari)\s+(.+?)(?:\s*\d{10,}|\s*$)""", RegexOption.IGNORE_CASE)

    /**
     * Keywords indicating a received payment in Maybank MAE notifications.
     */
    val MAE_KEYWORDS = listOf("received", "terima", "DuitNow")

    // ═══════════════════════════════════════════════════════════════════════════
    // DuitNow bank apps (CIMB, RHB, AmBank)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Keywords indicating a received DuitNow payment from various bank apps.
     * Generic pattern: "DuitNow: RM 15.00 received" or "Anda telah menerima RM15.00"
     */
    val DUITNOW_KEYWORDS = listOf("DuitNow", "received", "menerima", "credit")

    /**
     * Extracts sender name from DuitNow bank app notifications.
     * Matches "from/dari/sender: <name>" up to end of line or "Ref".
     * Example: "DuitNow: RM 15.00 received from Ali" → "Ali"
     */
    val DUITNOW_SENDER_REGEX = Regex("""(?:from|dari|sender)[:\s]*(.+?)(?:\s*$|\s*Ref)""", RegexOption.IGNORE_CASE)

    /**
     * Extracts transaction reference from DuitNow bank app notifications.
     * Supports "Ref", "Reference", and Malay "Rujukan".
     * Example: "... Ref: DN123456789" → "DN123456789"
     */
    val DUITNOW_REF_REGEX = Regex("""(?:Reference|Rujukan|Ref)[:\s]*([A-Z0-9]+)""", RegexOption.IGNORE_CASE)

    // ═══════════════════════════════════════════════════════════════════════════
    // Payment-Received Filter
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Keywords that indicate a notification is about receiving a payment (credit)
     * rather than a debit, promotional message, or system update.
     * Includes both English and Malay (Bahasa Malaysia) variants.
     */
    val RECEIVE_KEYWORDS = listOf(
        "received", "terima", "menerima", "credit", "credited",
        "payment received", "bayaran diterima", "wang masuk"
    )

    /**
     * Determines whether the given notification text is likely a "payment received"
     * notification rather than a promotional message, debit notification, or system update.
     *
     * A notification is considered a likely payment-received if it:
     * 1. Contains a recognizable RM amount pattern (e.g., "RM 15.00")
     * 2. Contains at least one receive-keyword from [RECEIVE_KEYWORDS]
     *
     * @param text The notification body text to evaluate.
     * @return `true` if the text likely represents an incoming payment notification.
     */
    fun isLikelyPaymentReceived(text: String): Boolean =
        AMOUNT_REGEX.containsMatchIn(text) &&
            RECEIVE_KEYWORDS.any { text.contains(it, ignoreCase = true) }
}
