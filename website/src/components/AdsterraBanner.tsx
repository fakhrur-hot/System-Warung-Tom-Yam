import { useMemo } from 'react'
import {
  ADSTERRA_BANNER_HEIGHT,
  ADSTERRA_BANNER_KEY,
  ADSTERRA_BANNER_WIDTH,
  bannerFrameHtml,
} from '../lib/adsterra'

/**
 * One Adsterra **banner** unit, sandboxed in an iframe.
 *
 * The iframe is not cosmetic. Adsterra's banner `invoke.js` writes its markup with
 * `document.write`; called after load on the main document that wipes the entire SPA. Giving it
 * its own document is the standard carrier for these units in React, and as a side effect the ad
 * script cannot touch the ordering DOM, read the cart, or reach app state.
 *
 * Renders nothing when unconfigured, so an unset key ships no markup rather than an empty box.
 */
export default function AdsterraBanner() {
  const html = useMemo(
    () =>
      ADSTERRA_BANNER_KEY
        ? bannerFrameHtml(ADSTERRA_BANNER_KEY, ADSTERRA_BANNER_WIDTH, ADSTERRA_BANNER_HEIGHT)
        : null,
    [],
  )

  if (!html) return null

  return (
    <div className="flex justify-center py-2" aria-hidden="true">
      <iframe
        title="advertisement"
        srcDoc={html}
        width={ADSTERRA_BANNER_WIDTH}
        height={ADSTERRA_BANNER_HEIGHT}
        style={{ border: 0, overflow: 'hidden', maxWidth: '100%' }}
        scrolling="no"
        // Allows the ad's own scripts and click-throughs, but withholds same-origin — so the frame
        // cannot reach this document, its storage, or the customer's order.
        sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"
        loading="lazy"
      />
    </div>
  )
}
