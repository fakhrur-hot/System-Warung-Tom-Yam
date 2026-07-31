package com.razstudio.pos.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * Height reserved for a banner. [AdSize.BANNER] is 320x50dp; callers that need to keep content
 * clear of the banner should reserve exactly this much plus their own spacing.
 */
val AD_BANNER_HEIGHT = 50.dp

private const val AD_UNIT_BANNER = "ca-app-pub-8323843054100465/6611158260"

/**
 * The app's only ad surface. Every banner in the app goes through here so the placement rules
 * below are enforced in one place rather than re-decided per screen.
 *
 * ### Where this may be used
 *
 * AdMob forbids or discourages several placements that a POS app walks straight into, so the set of
 * screens this may appear on is deliberately small. From
 * [Discouraged banner implementations](https://support.google.com/admob/answer/6275345):
 *
 *  - a banner must **not** float or hover over app content, and must **not** sit over scrolling
 *    content the user taps — "users may try to click on the menu but end up clicking on the ad";
 *  - a banner must **not** be immediately next to interactive elements or navigational buttons;
 *  - a banner must **not** be on a screen the user is continuously interacting with.
 *
 * That rules out every screen involved in taking an order or moving money: the table views, all
 * three steps of new-order entry, the order detail sheet (whose bottom-most controls are Pay Cash /
 * Pay QR / Cancel Order), and every form, device list and printer list whose rows carry
 * approve/revoke/delete controls at the bottom edge. An earlier revision did overlay a banner on
 * the new-order table grid; it was removed rather than restacked, because on that screen the
 * adjacency is the problem, not merely the overlay.
 *
 * What is left is the read-only and idle surfaces: Reports, Backup, Background Setup, the
 * pending-approval wait, the admin lock screen, and the café-closed state. Those are the only call
 * sites, and new ones should be added only after re-reading the rules above.
 *
 * ### How it must be laid out
 *
 * This composable renders as **its own row in normal layout flow** — never inside a
 * `Box` with `align(Alignment.Bottom*)` over other content. Give it a parent `Column` where the
 * content above takes `weight(1f)`, so the banner occupies space of its own that nothing scrolls
 * beneath. [AdBannerFooter] does that framing (divider + breathing room) and is what call sites
 * should normally use.
 *
 * ### Refresh
 *
 * [AdView.loadAd] is called exactly once per composition, so a banner parked on an idle screen —
 * the lock screen or the café-closed state, which can sit untouched overnight — renders one
 * impression rather than accumulating unseen ones. The view is also paused with the host lifecycle,
 * so it stops when the screen turns off. Automatic refresh is a per-ad-unit setting in the AdMob
 * console and must be left **disabled** for [AD_UNIT_BANNER]; enabling it there would defeat this
 * and start billing impressions to an empty room, which is how accounts get flagged for invalid
 * traffic.
 */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val adView = remember {
        AdView(context).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = AD_UNIT_BANNER
        }
    }

    // Once per composition — deliberately not keyed on anything that recomposes.
    LaunchedEffect(adView) {
        adView.loadAd(AdRequest.Builder().build())
    }

    // Pause with the host so a banner on a screen the café leaves running overnight stops as soon
    // as the display sleeps, and resumes only when someone actually comes back to the device.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, adView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> adView.pause()
                Lifecycle.Event.ON_RESUME -> adView.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            adView.destroy()
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(AD_BANNER_HEIGHT),
        factory = { adView },
    )
}

/**
 * [AdBanner] with the separation that keeps it off the app's own controls: a divider and a gap
 * above, so the last button of the content above can never end up flush against the ad.
 *
 * Place it as the final child of a `Column` whose content above uses `Modifier.weight(1f)`.
 */
@Composable
fun AdBannerFooter(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        AdBanner(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    }
}
