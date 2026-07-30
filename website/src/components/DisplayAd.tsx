import { useEffect, useRef } from 'react'
import { ADSENSE_CLIENT_ID } from '../lib/adsense'

interface DisplayAdProps {
  /** AdSense ad unit slot ID (digits only, from Ads → By ad unit → Display ads). */
  slot?: string
}

/**
 * A responsive AdSense **display** unit — distinct from InFeedAd, which is a `fluid` unit with a
 * layout key built for insertion between items in a content list. This one is a standalone block,
 * used at the bottom of the order-status view where a customer waiting on their food has real
 * dwell time and there is no purchase flow left to interrupt.
 *
 * Deliberately rendered in normal document flow rather than pinned to fill the empty space below
 * the buttons: on a small order that gap is ~225px, but a long multi-session order with the cancel
 * window still open fills it, and a pinned ad would then overlap `+ Add more items` — the same
 * accidental-click problem that ruled out a sticky banner near checkout (see lib/adsense.ts).
 * In flow, it simply pushes below the fold instead.
 *
 * Renders nothing unless BOTH the client ID and a slot are configured, so an unconfigured
 * deployment ships with no markup at all rather than an empty `<ins>` that never fills.
 */
export default function DisplayAd({ slot }: DisplayAdProps) {
  const pushed = useRef(false)

  useEffect(() => {
    // One push({}) per mounted <ins>; the ref guards against React StrictMode's dev double-invoke.
    if (pushed.current || !ADSENSE_CLIENT_ID || !slot) return
    pushed.current = true
    try {
      ;(window.adsbygoogle = window.adsbygoogle || []).push({})
    } catch (e) {
      console.error('AdSense display push failed', e)
    }
  }, [slot])

  if (!ADSENSE_CLIENT_ID || !slot) return null

  return (
    <ins
      className="adsbygoogle"
      style={{ display: 'block' }}
      data-ad-client={ADSENSE_CLIENT_ID}
      data-ad-slot={slot}
      data-ad-format="auto"
      data-full-width-responsive="true"
    />
  )
}
