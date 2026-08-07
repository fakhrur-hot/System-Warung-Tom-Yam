package com.razstudio.pos.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPatternsTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // AMOUNT_REGEX
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun amountRegex_matchesRmWithSpace() {
        val match = NotificationPatterns.AMOUNT_REGEX.find("You received RM 15.00 from Ali")
        assertNotNull(match)
        assertEquals("15.00", match!!.groupValues[1])
    }

    @Test
    fun amountRegex_matchesRmWithoutSpace() {
        val match = NotificationPatterns.AMOUNT_REGEX.find("RM15.00 received via DuitNow")
        assertNotNull(match)
        assertEquals("15.00", match!!.groupValues[1])
    }

    @Test
    fun amountRegex_matchesAmountWithThousandsSeparator() {
        val match = NotificationPatterns.AMOUNT_REGEX.find("Payment of RM 1,500.00 received")
        assertNotNull(match)
        assertEquals("1,500.00", match!!.groupValues[1])
    }

    @Test
    fun amountRegex_doesNotMatchWithoutDecimal() {
        val match = NotificationPatterns.AMOUNT_REGEX.find("RM 15 credited")
        assertNull(match)
    }

    @Test
    fun amountRegex_doesNotMatchNonRmCurrency() {
        val match = NotificationPatterns.AMOUNT_REGEX.find("USD 15.00 received")
        assertNull(match)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TNG eWallet patterns
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun tngSenderRegex_extractsSenderFromNotification() {
        val match = NotificationPatterns.TNG_SENDER_REGEX.find("You have received RM 15.00 from Ali bin Abu")
        assertNotNull(match)
        assertEquals("Ali bin Abu", match!!.groupValues[1])
    }

    @Test
    fun tngSenderRegex_extractsSenderWithDari() {
        val match = NotificationPatterns.TNG_SENDER_REGEX.find("Anda telah terima RM 15.00 dari Ahmad")
        assertNotNull(match)
        assertEquals("Ahmad", match!!.groupValues[1])
    }

    @Test
    fun tngReceivedKeywords_containsExpectedKeywords() {
        assertTrue(NotificationPatterns.TNG_RECEIVED_KEYWORDS.contains("received"))
        assertTrue(NotificationPatterns.TNG_RECEIVED_KEYWORDS.contains("terima"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TNG Merchant patterns
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun tngMerchantRefRegex_extractsReference() {
        val match = NotificationPatterns.TNG_MERCHANT_REF_REGEX.find("Payment of RM 15.00 received. Ref: TNG1234567890")
        assertNotNull(match)
        assertEquals("TNG1234567890", match!!.groupValues[1])
    }

    @Test
    fun tngMerchantRefRegex_extractsReferenceWithFullWord() {
        val match = NotificationPatterns.TNG_MERCHANT_REF_REGEX.find("Reference: ABC123")
        assertNotNull(match)
        assertEquals("ABC123", match!!.groupValues[1])
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Boost patterns
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun boostSenderRegex_extractsSenderName() {
        val match = NotificationPatterns.BOOST_SENDER_REGEX.find("You received RM 15.00 from Ali")
        assertNotNull(match)
        assertEquals("Ali", match!!.groupValues[1])
    }

    @Test
    fun boostSenderRegex_stopsAtVia() {
        val match = NotificationPatterns.BOOST_SENDER_REGEX.find("You received RM 15.00 from Ali via QR")
        assertNotNull(match)
        assertEquals("Ali", match!!.groupValues[1])
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GrabPay Merchant patterns
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun grabSenderRegex_extractsSender() {
        val match = NotificationPatterns.GRAB_SENDER_REGEX.find("Payment received: RM 15.00 from Ali")
        assertNotNull(match)
        assertEquals("Ali", match!!.groupValues[1])
    }

    @Test
    fun grabRefRegex_extractsTransactionId() {
        val match = NotificationPatterns.GRAB_REF_REGEX.find("Payment received: RM 15.00. Transaction ID: GP123456")
        assertNotNull(match)
        assertEquals("GP123456", match!!.groupValues[1])
    }

    @Test
    fun grabRefRegex_extractsMalayTransactionId() {
        val match = NotificationPatterns.GRAB_REF_REGEX.find("Bayaran diterima. ID Transaksi: GP789012")
        assertNotNull(match)
        assertEquals("GP789012", match!!.groupValues[1])
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ShopeePay patterns
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun shopeeSenderRegex_extractsSender() {
        val match = NotificationPatterns.SHOPEE_SENDER_REGEX.find("You've received RM 15.00 from buyer_name")
        assertNotNull(match)
        assertEquals("buyer_name", match!!.groupValues[1])
    }

    @Test
    fun shopeeSenderRegex_stopsAtParenthesis() {
        val match = NotificationPatterns.SHOPEE_SENDER_REGEX.find("Received RM 10.00 from seller123 (Order #456)")
        assertNotNull(match)
        assertEquals("seller123", match!!.groupValues[1])
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Maybank MAE patterns
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun maeSenderRegex_extractsSenderStrippingPhoneNumber() {
        val match = NotificationPatterns.MAE_SENDER_REGEX.find("RM15.00 received via DuitNow from Ali 0123456789")
        assertNotNull(match)
        assertEquals("Ali", match!!.groupValues[1])
    }

    @Test
    fun maeSenderRegex_extractsSenderWithoutPhone() {
        val match = NotificationPatterns.MAE_SENDER_REGEX.find("RM15.00 received from Ahmad")
        assertNotNull(match)
        assertEquals("Ahmad", match!!.groupValues[1])
    }

    @Test
    fun maeKeywords_containsExpectedKeywords() {
        assertTrue(NotificationPatterns.MAE_KEYWORDS.contains("received"))
        assertTrue(NotificationPatterns.MAE_KEYWORDS.contains("terima"))
        assertTrue(NotificationPatterns.MAE_KEYWORDS.contains("DuitNow"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DuitNow bank app patterns
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun duitnowSenderRegex_extractsSender() {
        val match = NotificationPatterns.DUITNOW_SENDER_REGEX.find("DuitNow: RM 15.00 received from Ali")
        assertNotNull(match)
        assertEquals("Ali", match!!.groupValues[1])
    }

    @Test
    fun duitnowSenderRegex_stopsAtRef() {
        val match = NotificationPatterns.DUITNOW_SENDER_REGEX.find("Received from Ahmad Ref: DN123")
        assertNotNull(match)
        assertEquals("Ahmad", match!!.groupValues[1])
    }

    @Test
    fun duitnowRefRegex_extractsReference() {
        val match = NotificationPatterns.DUITNOW_REF_REGEX.find("Payment received. Ref: DN123456789")
        assertNotNull(match)
        assertEquals("DN123456789", match!!.groupValues[1])
    }

    @Test
    fun duitnowRefRegex_extractsMalayReference() {
        val match = NotificationPatterns.DUITNOW_REF_REGEX.find("Bayaran diterima. Rujukan: RHB999888")
        assertNotNull(match)
        assertEquals("RHB999888", match!!.groupValues[1])
    }

    @Test
    fun duitnowKeywords_containsExpectedValues() {
        assertTrue(NotificationPatterns.DUITNOW_KEYWORDS.contains("DuitNow"))
        assertTrue(NotificationPatterns.DUITNOW_KEYWORDS.contains("received"))
        assertTrue(NotificationPatterns.DUITNOW_KEYWORDS.contains("menerima"))
        assertTrue(NotificationPatterns.DUITNOW_KEYWORDS.contains("credit"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RECEIVE_KEYWORDS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun receiveKeywords_containsEnglishKeywords() {
        val keywords = NotificationPatterns.RECEIVE_KEYWORDS
        assertTrue(keywords.contains("received"))
        assertTrue(keywords.contains("credit"))
        assertTrue(keywords.contains("credited"))
        assertTrue(keywords.contains("payment received"))
    }

    @Test
    fun receiveKeywords_containsMalayKeywords() {
        val keywords = NotificationPatterns.RECEIVE_KEYWORDS
        assertTrue(keywords.contains("terima"))
        assertTrue(keywords.contains("menerima"))
        assertTrue(keywords.contains("bayaran diterima"))
        assertTrue(keywords.contains("wang masuk"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // isLikelyPaymentReceived
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun isLikelyPaymentReceived_trueForAmountPlusKeyword() {
        assertTrue(NotificationPatterns.isLikelyPaymentReceived("You have received RM 15.00 from Ali"))
    }

    @Test
    fun isLikelyPaymentReceived_trueForMalayNotification() {
        assertTrue(NotificationPatterns.isLikelyPaymentReceived("Anda telah menerima RM15.00 via DuitNow"))
    }

    @Test
    fun isLikelyPaymentReceived_trueForCreditKeyword() {
        assertTrue(NotificationPatterns.isLikelyPaymentReceived("RM 50.00 credited to your account"))
    }

    @Test
    fun isLikelyPaymentReceived_falseForAmountWithoutKeyword() {
        assertFalse(NotificationPatterns.isLikelyPaymentReceived("You paid RM 15.00 to Grab"))
    }

    @Test
    fun isLikelyPaymentReceived_falseForKeywordWithoutAmount() {
        assertFalse(NotificationPatterns.isLikelyPaymentReceived("Payment received successfully"))
    }

    @Test
    fun isLikelyPaymentReceived_falseForPromoNotification() {
        assertFalse(NotificationPatterns.isLikelyPaymentReceived("Get RM5 cashback! Tap to claim your reward"))
    }

    @Test
    fun isLikelyPaymentReceived_falseForEmptyString() {
        assertFalse(NotificationPatterns.isLikelyPaymentReceived(""))
    }

    @Test
    fun isLikelyPaymentReceived_caseInsensitiveKeywordMatch() {
        assertTrue(NotificationPatterns.isLikelyPaymentReceived("RM 25.00 RECEIVED from sender"))
    }
}
