package com.razstudio.pos.data.promos

import android.net.Uri

/**
 * Utility for generating and validating Shopee affiliate links.
 *
 * Always uses the baked [ShopeeAuthSigner.AFFILIATE_ID] for commission attribution.
 * The [generate] function appends tracking parameters while ensuring idempotency —
 * if `sub_id` is already present in the URL, it won't be added again.
 */
object LinkGenerator {

    private const val PARAM_SUB_ID = "sub_id"
    private const val PARAM_AF_ID = "af_id"
    private const val PARAM_CAMPAIGN_ID = "campaign_id"

    /**
     * Appends affiliate tracking parameters to a Shopee URL.
     *
     * Always uses the baked AFFILIATE_ID (12352980181) for commission attribution.
     * [subId] carries the café surface identifier for analytics segmentation.
     * Format: `{cafeSlug}-{surface}` (e.g., "warung-tomyam-tableview").
     *
     * sub_id appending is idempotent — if the URL already contains a `sub_id`
     * parameter, it won't be added again.
     *
     * @param baseUrl The original Shopee product or short link URL.
     * @param subId The café surface identifier (e.g., "warung-tomyam-tableview").
     * @param campaignId Optional campaign identifier to append.
     * @return The URL string with affiliate parameters appended.
     */
    fun generate(baseUrl: String, subId: String, campaignId: String? = null): String {
        val uri = Uri.parse(baseUrl)
        val builder = uri.buildUpon()

        // Idempotency: only append sub_id if not already present
        if (uri.getQueryParameter(PARAM_SUB_ID) == null) {
            builder.appendQueryParameter(PARAM_SUB_ID, subId)
        }

        // Ensure af_id is present
        if (uri.getQueryParameter(PARAM_AF_ID) == null) {
            builder.appendQueryParameter(PARAM_AF_ID, ShopeeAuthSigner.AFFILIATE_ID)
        }

        // Append campaign_id if provided and not already present
        if (campaignId != null && uri.getQueryParameter(PARAM_CAMPAIGN_ID) == null) {
            builder.appendQueryParameter(PARAM_CAMPAIGN_ID, campaignId)
        }

        return builder.build().toString()
    }

    /**
     * Validates a Shopee affiliate link format.
     *
     * Checks:
     * - URL is non-blank
     * - URL is parseable as a URI
     * - URL uses HTTPS scheme
     * - Host is or ends with `shopee.com.my` (includes `s.shopee.com.my` short links)
     *
     * @param url The URL string to validate.
     * @return [LinkValidationResult.Valid] if all checks pass,
     *         [LinkValidationResult.Invalid] with a specific reason otherwise.
     */
    fun validate(url: String): LinkValidationResult {
        if (url.isBlank()) {
            return LinkValidationResult.Invalid("URL is blank")
        }

        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return LinkValidationResult.Invalid("URL is not a well-formed URI")
        }

        // Uri.parse doesn't throw on malformed URIs, so check for null scheme/host
        val scheme = uri.scheme
        if (scheme == null) {
            return LinkValidationResult.Invalid("URL is not a well-formed URI")
        }

        if (!scheme.equals("https", ignoreCase = true)) {
            return LinkValidationResult.Invalid("URL must use HTTPS scheme")
        }

        val host = uri.host
        if (host == null) {
            return LinkValidationResult.Invalid("URL has no host")
        }

        if (!host.equals("shopee.com.my", ignoreCase = true) &&
            !host.endsWith(".shopee.com.my", ignoreCase = true)
        ) {
            return LinkValidationResult.Invalid("URL host must be shopee.com.my or a subdomain (e.g., s.shopee.com.my)")
        }

        return LinkValidationResult.Valid
    }
}

/**
 * Result of validating a Shopee affiliate link.
 */
sealed class LinkValidationResult {
    /** The URL passed all validation checks. */
    data object Valid : LinkValidationResult()

    /** The URL failed validation. [reason] describes why. */
    data class Invalid(val reason: String) : LinkValidationResult()
}
