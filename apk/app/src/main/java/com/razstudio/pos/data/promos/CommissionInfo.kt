package com.razstudio.pos.data.promos

/**
 * Commission breakdown for a Shopee affiliate product.
 *
 * Contains the base and optional XTRA commission rates, along with the campaign
 * details when an XTRA promotion is active. Used to prioritize higher-commission
 * products in the rotation and to track campaign expiry.
 */
data class CommissionInfo(
    /** Shopee item ID this commission info belongs to. */
    val itemId: Long,
    /** Base commission rate as a decimal (e.g. 0.05 = 5%). */
    val baseRate: Double,
    /** XTRA campaign bonus rate, if active. Null when no XTRA applies. */
    val xtraRate: Double?,
    /** Name of the active XTRA campaign, if any. */
    val campaignName: String?,
    /** ISO-8601 expiry timestamp of the XTRA campaign. Null if no campaign is active. */
    val expiresAt: String?,
)
