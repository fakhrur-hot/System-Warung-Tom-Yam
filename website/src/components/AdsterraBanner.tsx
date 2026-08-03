import { useMemo } from 'react'
import { bannerFrameHtml } from '../lib/adsterra'

interface AdsterraBannerProps {
  /** Publisher key from the unit's snippet. */
  bannerKey: string
  /** Must match the size the unit was created at, or the zone returns no fill. */
  width: number
  height: number
}

/**
 * One Adsterra **banner** unit, sandboxed in an iframe.
 *
 * The iframe is not cosmetic. Adsterra's banner `invoke.js` writes its markup with
 * `document.write`; called after load on the main document that wipes the entire SPA. Giving it
 * its own document is the standard carrier for these units in React, and as a side effect the ad
 * script cannot touch the ordering DOM, read the cart, or reach app state.
 *
 * Unlike the Native Banner there is no shared container id, so this format CAN repeat at every
 * slot on a page — each iframe is independent and the same key works in all of them. That, plus a
 * predictable fixed height, is why it is preferred over native in `AdSlot`.
 *
 * Config arrives as props because it now comes from runtime `app-config.json`, which is what lets
 * one build serve many cafés.
 */
export default function AdsterraBanner({ bannerKey, width, height }: AdsterraBannerProps) {
  const html = useMemo(
    () => (bannerKey ? bannerFrameHtml(bannerKey, width, height) : null),
    [bannerKey, width, height],
  )

  if (!html) return null

  return (
    <div className="flex justify-center py-2" aria-hidden="true">
      <iframe
        title="advertisement"
        srcDoc={html}
        width={width}
        height={height}
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
