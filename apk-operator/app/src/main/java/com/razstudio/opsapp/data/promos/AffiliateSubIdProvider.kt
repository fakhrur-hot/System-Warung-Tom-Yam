package com.razstudio.opsapp.data.promos

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the `sub_id` used to tag affiliate links synced by this device.
 *
 * Ported from `apk/app`'s `com.razstudio.pos.data.promos.AffiliateSubIdProvider`. In the POS app
 * this is per-café; in the Operator APK there is no café identity to derive from (this device
 * manages the shared central catalog, not any one café's own copy), so [forSurface] falls back to
 * a fixed "operator" slug rather than a package-name-derived one.
 */
@Singleton
class AffiliateSubIdProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun forSurface(surface: String): String = "operator-$surface"
}
