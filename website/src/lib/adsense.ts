/**
 * Loads Google AdSense **auto ads** — deliberately not manually-placed slots with a
 * refresh-on-navigation trigger. Two real AdSense policy risks ruled that combination out:
 * standard AdSense prohibits refreshing an ad without the user explicitly requesting a refresh
 * (a menu-category tab switch doesn't count), and a sticky banner kept near the cart/checkout
 * button is exactly the "ads placed too close to navigational controls" accidental-click risk
 * Google's own ad-placement policy warns about. Auto ads let Google's own placement algorithm
 * choose where ads go on the page, and it's specifically designed to avoid overlapping
 * interactive elements — the safer default for a site whose main job is fast, frictionless
 * ordering, not ad inventory.
 *
 * Call [loadAdSense] once, only from the customer-facing ordering page (`App.tsx`) — main.tsx's
 * router renders `App` solely for `/order` and the unmatched-path fallback, so calling this only
 * from `App` is sufficient to guarantee it can never load on any `/admin/*` route; no pathname
 * checks needed here.
 */

declare global {
  interface Window {
    adsbygoogle?: unknown[]
  }
}

// Exported so InFeedAd.tsx (a specific, manually-created ad unit — not part of auto ads) can
// reuse the same configured client ID instead of re-reading import.meta.env separately.
export const ADSENSE_CLIENT_ID = import.meta.env.VITE_ADSENSE_CLIENT_ID as string | undefined

let alreadyLoaded = false

export function loadAdSense(): void {
  // No-ops cleanly when unconfigured — local dev and any deployment that hasn't set up an
  // AdSense client ID never attempts to load the script or shows a broken/blocked request.
  if (alreadyLoaded || !ADSENSE_CLIENT_ID) return
  alreadyLoaded = true

  const script = document.createElement('script')
  script.async = true
  script.crossOrigin = 'anonymous'
  script.src = `https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=${ADSENSE_CLIENT_ID}`
  script.onload = () => {
    try {
      ;(window.adsbygoogle = window.adsbygoogle || []).push({
        google_ad_client: ADSENSE_CLIENT_ID,
        enable_page_level_ads: true,
      })
    } catch (e) {
      console.error('AdSense auto-ads init failed', e)
    }
  }
  script.onerror = () => {
    // Non-fatal: an ad blocker or offline connection just means no ads this session — the
    // ordering flow itself never depends on this script loading.
  }
  document.head.appendChild(script)
}
