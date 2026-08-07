package com.razstudio.pos.notification

import android.app.Notification
import android.service.notification.StatusBarNotification
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Extracts structured payment data from raw notification content using per-app
 * regex patterns defined in [NotificationPatterns].
 *
 * Returns [ParsedPayment] if the notification is identified as a payment-received
 * notification, or null if it's a promo, debit, or unrecognized format.
 */
@Singleton
class NotificationParser @Inject constructor() {

    /**
     * Parses a [StatusBarNotification] from a monitored eWallet package into
     * a [ParsedPayment], or returns null if it's not a payment notification.
     *
     * Algorithm:
     * 1. Extract title, text, and bigText from notification extras
     * 2. Resolve the [WalletApp] from the package name
     * 3. Extract the RM amount using [NotificationPatterns.AMOUNT_REGEX]
     * 4. Verify the notification contains a receive-keyword via [NotificationPatterns.isLikelyPaymentReceived]
     * 5. Extract sender and reference using per-app patterns
     */
    fun parse(sbn: StatusBarNotification): ParsedPayment? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text

        // Use bigText if available (more detail), fall back to text
        val content = bigText.ifBlank { text }
        if (content.isBlank()) return null

        val walletApp = WalletApp.fromPackage(sbn.packageName) ?: return null

        // Extract amount — universal pattern across all Malaysian eWallet apps
        val amountMatch = NotificationPatterns.AMOUNT_REGEX.find(content)
            ?: NotificationPatterns.AMOUNT_REGEX.find(title)
            ?: return null
        val amountStr = amountMatch.groupValues[1].replace(",", "")
        val amount = amountStr.toDoubleOrNull() ?: return null
        if (amount <= 0.0) return null

        val amountSen = (amount * 100.0).roundToLong()

        // Check if this is actually a payment-received (not a promo or debit)
        if (!NotificationPatterns.isLikelyPaymentReceived(content) &&
            !NotificationPatterns.isLikelyPaymentReceived(title)
        ) return null

        // Extract sender and reference — per-app patterns
        val sender = extractSender(walletApp, title, content)
        val reference = extractReference(walletApp, title, content)

        return ParsedPayment(
            amount = amount,
            amountSen = amountSen,
            sender = sender,
            reference = reference,
            rawTitle = title,
            rawText = content,
            packageName = sbn.packageName,
            timestamp = sbn.postTime,
            walletApp = walletApp,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Per-app sender extraction
    // ═══════════════════════════════════════════════════════════════════════════

    private fun extractSender(walletApp: WalletApp, title: String, content: String): String? {
        val regex = when (walletApp) {
            WalletApp.TNG_EWALLET -> NotificationPatterns.TNG_SENDER_REGEX
            WalletApp.BOOST -> NotificationPatterns.BOOST_SENDER_REGEX
            WalletApp.GRABPAY_MERCHANT -> NotificationPatterns.GRAB_SENDER_REGEX
            WalletApp.SHOPEEPAY -> NotificationPatterns.SHOPEE_SENDER_REGEX
            WalletApp.MAYBANK_MAE -> NotificationPatterns.MAE_SENDER_REGEX
            WalletApp.DUITNOW_CIMB,
            WalletApp.DUITNOW_RHB,
            WalletApp.DUITNOW_AMBANK -> NotificationPatterns.DUITNOW_SENDER_REGEX
            WalletApp.TNG_MERCHANT -> null // TNG Merchant doesn't typically include sender
        }
        if (regex == null) return null

        val match = regex.find(content) ?: regex.find(title)
        return match?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Per-app reference extraction
    // ═══════════════════════════════════════════════════════════════════════════

    private fun extractReference(walletApp: WalletApp, title: String, content: String): String? {
        val regex = when (walletApp) {
            WalletApp.TNG_MERCHANT -> NotificationPatterns.TNG_MERCHANT_REF_REGEX
            WalletApp.GRABPAY_MERCHANT -> NotificationPatterns.GRAB_REF_REGEX
            WalletApp.DUITNOW_CIMB,
            WalletApp.DUITNOW_RHB,
            WalletApp.DUITNOW_AMBANK -> NotificationPatterns.DUITNOW_REF_REGEX
            else -> null // Other apps don't typically include a reference
        }
        if (regex == null) return null

        val match = regex.find(content) ?: regex.find(title)
        return match?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }
}
