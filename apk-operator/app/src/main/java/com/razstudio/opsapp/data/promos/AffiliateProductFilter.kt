package com.razstudio.opsapp.data.promos

/**
 * Filters, validates, and rotates affiliate product offers for display.
 *
 * Ported from `apk/app`'s `com.razstudio.pos.data.promos.AffiliateProductFilter` (post-bugfix
 * version — `filterByQuality` was removed there as dead code; not re-added here). Bridges raw
 * [ShopeeProductOffer] data into display-ready [AffiliateProduct] instances: URL validation,
 * affiliate parameter appending via [LinkGenerator], alt text fallback, and offset-based rotation.
 *
 * Not currently exercised by any Operator APK screen (neither `AffiliateDebugScreen` nor
 * `PromoCatalogScreen` render product tiles), but kept as a faithful, complete port of the module
 * per bugfix/design.md Requirement 8 rather than a hand-trimmed subset.
 */
object AffiliateProductFilter {

    private const val DEFAULT_LABEL = "Shopee pick"

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
     * Selects a window of products using offset-based rotation. No duplicates appear in the
     * returned window.
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
