package com.razstudio.opsapp.data.promos

import android.net.Uri

/**
 * Utility for generating and validating Shopee affiliate links.
 *
 * Ported from `apk/app`'s `com.razstudio.pos.data.promos.LinkGenerator`. Always uses the baked
 * [ShopeeAuthSigner.AFFILIATE_ID] for commission attribution. The [generate] function appends
 * tracking parameters while ensuring idempotency — if `sub_id` is already present in the URL, it
 * won't be added again.
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
     * sub_id appending is idempotent — if the URL already contains a `sub_id`
     * parameter, it won't be added again.
     */
    fun generate(baseUrl: String, subId: String, campaignId: String? = null): String {
        val uri = Uri.parse(baseUrl)
        val builder = uri.buildUpon()

        if (uri.getQueryParameter(PARAM_SUB_ID) == null) {
            builder.appendQueryParameter(PARAM_SUB_ID, subId)
        }

        if (uri.getQueryParameter(PARAM_AF_ID) == null) {
            builder.appendQueryParameter(PARAM_AF_ID, ShopeeAuthSigner.AFFILIATE_ID)
        }

        if (campaignId != null && uri.getQueryParameter(PARAM_CAMPAIGN_ID) == null) {
            builder.appendQueryParameter(PARAM_CAMPAIGN_ID, campaignId)
        }

        return builder.build().toString()
    }

    /**
     * Validates a Shopee affiliate link format.
     *
     * Checks: non-blank, well-formed URI, HTTPS scheme, host is or ends with `shopee.com.my`
     * (includes `s.shopee.com.my` short links).
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

/** Result of validating a Shopee affiliate link. */
sealed class LinkValidationResult {
    data object Valid : LinkValidationResult()
    data class Invalid(val reason: String) : LinkValidationResult()
}
