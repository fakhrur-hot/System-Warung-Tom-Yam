package com.razstudio.pos.data.promos

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the per-café, per-surface `sub_id` used to tag affiliate links, so Shopee's dashboard
 * (and any future commission-per-café accounting) can tell one café's traffic apart from another's.
 *
 * Before this existed, every call site read [ShopeeAuthSigner.AFFILIATE_ID] — the Shopee *account*
 * id, the same value on every café's install — into the `subId` slot, which made every café's taps
 * indistinguishable from every other café's.
 */
@Singleton
class AffiliateSubIdProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * @param surface The display surface generating this sub_id (e.g. "tableview", "ambient").
     * @return `{cafeSlug}-{surface}`, where `cafeSlug` is derived from the café's own package name
     *   (already unique per café — see `APPLICATION_ID` in `build.gradle.kts`). Falls back to
     *   `"unknown-cafe"` rather than silently reusing the affiliate account id, so a missing café
     *   identity stays visible in Shopee's dashboard instead of being masked again.
     */
    fun forSurface(surface: String): String {
        val cafeSlug = context.packageName
            ?.substringAfterLast('.')
            ?.ifBlank { null }
            ?: "unknown-cafe"
        return "$cafeSlug-$surface"
    }
}
