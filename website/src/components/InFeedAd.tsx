import { useEffect, useRef } from 'react'
import { ADSENSE_CLIENT_ID } from '../lib/adsense'

/**
 * One Google AdSense in-feed native ad unit (fluid format) — a specific, manually-created ad unit
 * from the AdSense dashboard, not part of the page-level auto ads in lib/adsense.ts. In-feed
 * (fluid + layout-key) is Google's own sanctioned format for exactly this use case: inserted
 * between items in a content list, styled to blend in rather than a banner. Rendered once per
 * insertion point (see MenuView) with no refresh trigger, so it doesn't touch the
 * refresh-without-user-request policy risk a GPT-style forced-refresh-on-navigation approach
 * would have.
 */
export default function InFeedAd() {
  const pushed = useRef(false)

  useEffect(() => {
    // Each <ins class="adsbygoogle"> on the page needs its own push({}) once the slot mounts —
    // guard so React StrictMode's double-invoke in dev doesn't push twice for the same element.
    if (pushed.current || !ADSENSE_CLIENT_ID) return
    pushed.current = true
    try {
      ;(window.adsbygoogle = window.adsbygoogle || []).push({})
    } catch (e) {
      console.error('AdSense in-feed push failed', e)
    }
  }, [])

  if (!ADSENSE_CLIENT_ID) return null

  return (
    <ins
      className="adsbygoogle"
      style={{ display: 'block' }}
      data-ad-format="fluid"
      data-ad-layout-key="-fx+3r+60-9v-y"
      data-ad-client={ADSENSE_CLIENT_ID}
      data-ad-slot="8913887972"
    />
  )
}
