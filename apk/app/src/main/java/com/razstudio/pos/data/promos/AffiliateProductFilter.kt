package com.razstudio.pos.data.promos

/**
 * Filters, validates, and rotates affiliate product offers for display.
 *
 * This object bridges raw [ShopeeProductOffer] data from the API/Room layer
 * into display-ready [AffiliateProduct] instances. It handles:
 * - URL validation (only HTTPS Shopee links pass through)
 * - Affiliate parameter appending via [LinkGenerator]
 * - Alt text fallback ("Shopee pick" when product name is blank)
 * - Offset-based rotation windowing for fair exposure
 */
object AffiliateProductFilter {

    /** Fallback label when [ShopeeProductOffer.productName] is blank. */
    private const val DEFAULT_LABEL = "Shopee pick"

    /**
     * Validates product offer links and converts to display-ready [AffiliateProduct] list.
     *
     * For each product:
     * 1. Validates [ShopeeProductOffer.offerLink] via [LinkGenerator.validate]
     * 2. If valid, generates the final URL with sub_id via [LinkGenerator.generate]
     * 3. Uses [ShopeeProductOffer.productName] as label, falling back to [DEFAULT_LABEL] if blank
     *
     * Products with invalid links are silently excluded.
     *
     * @param products Raw product offers from API/Room.
     * @param subId Café surface identifier (e.g., "warung-tomyam-tableview").
     * @return List of display-ready products with valid HTTPS affiliate URLs.
     */
    fun validate(products: List<ShopeeProductOffer>, subId: String): List<AffiliateProduct> {
        return products.mapNotNull { offer ->
            val validationResult = LinkGenerator.validate(offer.offerLink)
            if (validationResult is LinkValidationResult.Valid) {
                val finalUrl = LinkGenerator.generate(offer.offerLink, subId)
                val label = offer.productName.ifBlank { DEFAULT_LABEL }
                AffiliateProduct(
                    id = offer.id,
                    url = finalUrl,
                    imageUrl = offer.imageUrl,
                    label = label,
                )
            } else {
                null
            }
        }
    }

    /**
     * Selects a window of products using offset-based rotation.
     *
     * The offset wraps around the product list to ensure all products get fair exposure
     * across successive calls. No duplicates appear in the returned window.
     *
     * @param products The full list of validated affiliate products.
     * @param windowSize Number of products to include in the window.
     * @param offset Starting position (will be wrapped via modulo).
     * @return A sublist of [windowSize] products starting at the wrapped offset,
     *         or an empty list if [products] is empty or [windowSize] <= 0.
     */
    fun rotate(products: List<AffiliateProduct>, windowSize: Int, offset: Int): List<AffiliateProduct> {
        if (products.isEmpty() || windowSize <= 0) return emptyList()

        val size = products.size
        val effectiveWindow = windowSize.coerceAtMost(size)
        val startIndex = offset.mod(size)

        return List(effectiveWindow) { i ->
            products[(startIndex + i) % size]
        }
    }
}
