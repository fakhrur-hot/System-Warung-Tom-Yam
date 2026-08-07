package com.razstudio.pos.notification

/**
 * Structured payment data extracted from a raw notification.
 *
 * Created by [NotificationParser] when a notification from a monitored eWallet
 * app is identified as a payment-received notification.
 */
data class ParsedPayment(
    /** Parsed RM amount as a Double (e.g., 15.00). */
    val amount: Double,
    /** Amount in sen (integer), computed as (amount * 100).roundToLong(). */
    val amountSen: Long,
    /** Sender name extracted via per-app regex (nullable — not all apps include it). */
    val sender: String?,
    /** Transaction reference extracted via per-app regex (nullable). */
    val reference: String?,
    /** Raw notification title preserved verbatim for debugging. */
    val rawTitle: String,
    /** Raw notification text (bigText or text fallback) preserved verbatim. */
    val rawText: String,
    /** Android package name of the source app. */
    val packageName: String,
    /** Notification post time (epoch millis). */
    val timestamp: Long,
    /** The resolved wallet app enum value. */
    val walletApp: WalletApp,
)
