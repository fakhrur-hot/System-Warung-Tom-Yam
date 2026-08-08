package com.razstudio.opsapp.data.promos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ported from `apk/app`'s `com.razstudio.pos.data.promos.AffiliateProductFilterTest` (post-bugfix
 * version — `filterByQuality` tests removed there along with the dead code). Uses Robolectric
 * because `LinkGenerator` relies on `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
class AffiliateProductFilterTest {

    private fun offer(
        id: String = "row-1",
        productName: String = "Test Product",
        offerLink: String = "https://s.shopee.com.my/product1",
        imageUrl: String = "https://img.shopee.com.my/img1.jpg",
        price: Long = 8000L,
        originalPrice: Long = 10000L,
        isOfficialShop: Boolean = false,
    ) = ShopeeProductOffer(
        id = id,
        itemId = 1L,
        productName = productName,
        offerLink = offerLink,
        imageUrl = imageUrl,
        price = price,
        originalPrice = originalPrice,
        commissionRate = 0.08,
        commissionXtra = null,
        shopName = "Test Shop",
        isOfficialShop = isOfficialShop,
        salesCount = 100L,
        rating = 4.5,
    )

    private fun affiliateProduct(
        url: String = "https://s.shopee.com.my/product1",
        imageUrl: String = "https://img.shopee.com.my/img1.jpg",
        label: String = "Test Product",
    ) = AffiliateProduct(url = url, imageUrl = imageUrl, label = label)

    // ─── validate() ──────────────────────────────────────────────────────────────

    @Test
    fun `validate returns products with valid HTTPS links`() {
        val products = listOf(
            offer(productName = "Valid Item", offerLink = "https://s.shopee.com.my/abc"),
        )
        val result = AffiliateProductFilter.validate(products, "operator-catalog")

        assertEquals(1, result.size)
        assertTrue(result[0].url.contains("sub_id=operator-catalog"))
        assertEquals("Valid Item", result[0].label)
    }

    @Test
    fun `validate excludes products with invalid links`() {
        val products = listOf(
            offer(offerLink = "http://s.shopee.com.my/no-https"),
            offer(offerLink = "https://evil.com/phishing"),
            offer(offerLink = ""),
        )
        val result = AffiliateProductFilter.validate(products, "operator-catalog")

        assertEquals(0, result.size)
    }

    @Test
    fun `validate uses Shopee pick fallback when productName is blank`() {
        val products = listOf(
            offer(productName = "", offerLink = "https://s.shopee.com.my/item1"),
            offer(productName = "   ", offerLink = "https://shopee.com.my/item2"),
        )
        val result = AffiliateProductFilter.validate(products, "operator-catalog")

        assertEquals(2, result.size)
        assertEquals("Shopee pick", result[0].label)
        assertEquals("Shopee pick", result[1].label)
    }

    @Test
    fun `validate appends sub_id via LinkGenerator`() {
        val products = listOf(offer(offerLink = "https://s.shopee.com.my/prod"))
        val result = AffiliateProductFilter.validate(products, "operator-ambient")

        assertTrue(result[0].url.contains("sub_id=operator-ambient"))
        assertTrue(result[0].url.contains("af_id=${ShopeeAuthSigner.AFFILIATE_ID}"))
    }

    @Test
    fun `validate returns empty list for empty input`() {
        val result = AffiliateProductFilter.validate(emptyList(), "sub")
        assertEquals(0, result.size)
    }

    @Test
    fun `validate preserves imageUrl from offer`() {
        val products = listOf(
            offer(imageUrl = "https://img.shopee.com.my/photo.png"),
        )
        val result = AffiliateProductFilter.validate(products, "cafe")

        assertEquals("https://img.shopee.com.my/photo.png", result[0].imageUrl)
    }

    @Test
    fun `validate carries the offer id through for impression and click tracking`() {
        val products = listOf(offer(id = "shopee-42"))
        val result = AffiliateProductFilter.validate(products, "cafe")

        assertEquals("shopee-42", result[0].id)
    }

    // ─── rotate() ────────────────────────────────────────────────────────────────

    @Test
    fun `rotate returns window starting at offset`() {
        val products = (0..4).map { affiliateProduct(label = "Item $it") }
        val result = AffiliateProductFilter.rotate(products, windowSize = 3, offset = 1)

        assertEquals(3, result.size)
        assertEquals("Item 1", result[0].label)
        assertEquals("Item 2", result[1].label)
        assertEquals("Item 3", result[2].label)
    }

    @Test
    fun `rotate wraps around when offset plus window exceeds size`() {
        val products = (0..4).map { affiliateProduct(label = "Item $it") }
        val result = AffiliateProductFilter.rotate(products, windowSize = 3, offset = 4)

        assertEquals(3, result.size)
        assertEquals("Item 4", result[0].label)
        assertEquals("Item 0", result[1].label)
        assertEquals("Item 1", result[2].label)
    }

    @Test
    fun `rotate returns empty list for empty products`() {
        val result = AffiliateProductFilter.rotate(emptyList(), windowSize = 3, offset = 0)
        assertEquals(0, result.size)
    }

    @Test
    fun `rotate returns empty list when windowSize is zero`() {
        val products = listOf(affiliateProduct())
        val result = AffiliateProductFilter.rotate(products, windowSize = 0, offset = 0)
        assertEquals(0, result.size)
    }

    @Test
    fun `rotate returns empty list when windowSize is negative`() {
        val products = listOf(affiliateProduct())
        val result = AffiliateProductFilter.rotate(products, windowSize = -1, offset = 0)
        assertEquals(0, result.size)
    }

    @Test
    fun `rotate caps window to list size when windowSize exceeds products`() {
        val products = (0..2).map { affiliateProduct(label = "Item $it") }
        val result = AffiliateProductFilter.rotate(products, windowSize = 10, offset = 0)

        assertEquals(3, result.size)
    }

    @Test
    fun `rotate produces no duplicates`() {
        val products = (0..4).map { affiliateProduct(label = "Item $it") }
        val result = AffiliateProductFilter.rotate(products, windowSize = 5, offset = 3)

        assertEquals(5, result.size)
        assertEquals(5, result.map { it.label }.toSet().size)
    }

    @Test
    fun `rotate handles large offset via modulo`() {
        val products = (0..2).map { affiliateProduct(label = "Item $it") }
        val result = AffiliateProductFilter.rotate(products, windowSize = 2, offset = 100)

        assertEquals("Item 1", result[0].label)
        assertEquals("Item 2", result[1].label)
    }
}
