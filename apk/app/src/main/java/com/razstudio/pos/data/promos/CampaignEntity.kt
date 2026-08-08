package com.razstudio.pos.data.promos

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity tracking active Shopee Affiliate XTRA campaigns.
 *
 * Campaigns offer boosted commission rates for a limited window. The repository
 * syncs these periodically so the product filter can prioritise XTRA-tagged items
 * while the campaign is still live.
 */
@Entity(tableName = "affiliate_campaigns")
data class CampaignEntity(
    /** Unique campaign identifier from Shopee. */
    @PrimaryKey val id: String,
    /** Campaign display name. */
    val name: String,
    /** Extra commission rate offered during this campaign. */
    val xtraRate: Double,
    /** ISO-8601 timestamp when the campaign becomes active. */
    val startsAt: String,
    /** ISO-8601 timestamp when the campaign expires. */
    val expiresAt: String,
    /** Number of products participating in this campaign. */
    val productCount: Int,
    /** ISO-8601 timestamp of the last sync for this campaign's data. */
    val lastSyncedAt: String,
)
