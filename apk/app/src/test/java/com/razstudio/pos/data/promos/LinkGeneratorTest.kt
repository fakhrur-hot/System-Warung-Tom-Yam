package com.razstudio.pos.data.promos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies LinkGenerator URL generation and validation logic.
 * Uses Robolectric because android.net.Uri is required for URL manipulation.
 */
@RunWith(RobolectricTestRunner::class)
class LinkGeneratorTest {

    // ─── generate() ──────────────────────────────────────────────────────────────

    @Test
    fun `generate appends sub_id and af_id to a base URL`() {
        val result = LinkGenerator.generate(
            baseUrl = "https://s.shopee.com.my/product123",
            subId = "warung-tomyam-tableview",
        )
        assertTrue(result.contains("sub_id=warung-tomyam-tableview"))
        assertTrue(result.contains("af_id=12352980181"))
    }

    @Test
    fun `generate appends campaign_id when provided`() {
        val result = LinkGenerator.generate(
            baseUrl = "https://s.shopee.com.my/product123",
            subId = "warung-tomyam-tableview",
            campaignId = "xtra-july",
        )
        assertTrue(result.contains("campaign_id=xtra-july"))
    }

    @Test
    fun `generate does not append campaign_id when null`() {
        val result = LinkGenerator.generate(
            baseUrl = "https://s.shopee.com.my/product123",
            subId = "warung-tomyam-tableview",
            campaignId = null,
        )
        assertTrue(!result.contains("campaign_id"))
    }

    @Test
    fun `generate is idempotent - does not duplicate sub_id`() {
        val firstPass = LinkGenerator.generate(
            baseUrl = "https://s.shopee.com.my/product123",
            subId = "warung-tomyam-tableview",
        )
        val secondPass = LinkGenerator.generate(
            baseUrl = firstPass,
            subId = "warung-tomyam-tableview",
        )
        // Count occurrences of sub_id — should be exactly 1
        val count = Regex("sub_id=").findAll(secondPass).count()
        assertEquals(1, count)
    }

    @Test
    fun `generate does not duplicate af_id on second call`() {
        val firstPass = LinkGenerator.generate(
            baseUrl = "https://s.shopee.com.my/product123",
            subId = "warung-tomyam-tableview",
        )
        val secondPass = LinkGenerator.generate(
            baseUrl = firstPass,
            subId = "warung-tomyam-tableview",
        )
        val count = Regex("af_id=").findAll(secondPass).count()
        assertEquals(1, count)
    }

    @Test
    fun `generate preserves existing query parameters`() {
        val result = LinkGenerator.generate(
            baseUrl = "https://s.shopee.com.my/product123?existing=value",
            subId = "warung-tomyam-tableview",
        )
        assertTrue(result.contains("existing=value"))
        assertTrue(result.contains("sub_id=warung-tomyam-tableview"))
    }

    @Test
    fun `generate uses baked AFFILIATE_ID`() {
        val result = LinkGenerator.generate(
            baseUrl = "https://shopee.com.my/item/123",
            subId = "cafe-surface",
        )
        assertTrue(result.contains("af_id=${ShopeeAuthSigner.AFFILIATE_ID}"))
    }

    // ─── validate() ──────────────────────────────────────────────────────────────

    @Test
    fun `validate returns Valid for s_shopee_com_my short link`() {
        val result = LinkGenerator.validate("https://s.shopee.com.my/abc123")
        assertEquals(LinkValidationResult.Valid, result)
    }

    @Test
    fun `validate returns Valid for shopee_com_my main domain`() {
        val result = LinkGenerator.validate("https://shopee.com.my/product/12345")
        assertEquals(LinkValidationResult.Valid, result)
    }

    @Test
    fun `validate returns Invalid for blank URL`() {
        val result = LinkGenerator.validate("")
        assertTrue(result is LinkValidationResult.Invalid)
        assertEquals("URL is blank", (result as LinkValidationResult.Invalid).reason)
    }

    @Test
    fun `validate returns Invalid for whitespace-only URL`() {
        val result = LinkGenerator.validate("   ")
        assertTrue(result is LinkValidationResult.Invalid)
    }

    @Test
    fun `validate returns Invalid for HTTP scheme`() {
        val result = LinkGenerator.validate("http://s.shopee.com.my/abc123")
        assertTrue(result is LinkValidationResult.Invalid)
        assertTrue((result as LinkValidationResult.Invalid).reason.contains("HTTPS"))
    }

    @Test
    fun `validate returns Invalid for non-Shopee domain`() {
        val result = LinkGenerator.validate("https://evil.com/phishing")
        assertTrue(result is LinkValidationResult.Invalid)
        assertTrue((result as LinkValidationResult.Invalid).reason.contains("shopee.com.my"))
    }

    @Test
    fun `validate returns Invalid for URL without scheme`() {
        val result = LinkGenerator.validate("s.shopee.com.my/abc123")
        assertTrue(result is LinkValidationResult.Invalid)
    }

    @Test
    fun `validate accepts subdomains of shopee_com_my`() {
        val result = LinkGenerator.validate("https://mall.shopee.com.my/sale")
        assertEquals(LinkValidationResult.Valid, result)
    }

    @Test
    fun `validate rejects domain that merely contains shopee_com_my`() {
        // "fakeshopee.com.my" does NOT end with ".shopee.com.my" and is not "shopee.com.my"
        val result = LinkGenerator.validate("https://fakeshopee.com.my/trick")
        assertTrue(result is LinkValidationResult.Invalid)
    }
}
