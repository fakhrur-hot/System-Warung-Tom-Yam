import { ADSENSE_STATUS_SLOT } from '../lib/adsense'
import { ADSTERRA_SMARTLINK_URL, useAdsterra } from '../lib/adsterra'
import AdsterraBanner from './AdsterraBanner'
import AdsterraNative from './AdsterraNative'
import DisplayAd from './DisplayAd'
import InFeedAd from './InFeedAd'
import SmartLinkPromo from './SmartLinkPromo'

interface AdSlotProps {
  /**
   * `feed` — between menu items, where a unit should inherit page width and blend with the list.
   * `status` — a waiting or dead-end view, where the customer has real dwell time.
   */
  placement: 'feed' | 'status'
}

/**
 * The single decision point for which ad network and format fills a slot.
 *
 * Order: Adsterra Banner → Adsterra Native → SmartLink → AdSense. Adsterra wins whenever it is
 * configured, because running it alongside AdSense on one page view is the combination worth
 * avoiding — AdSense holds publishers responsible for what appears beside its units, and a strike
 * costs more than the extra impression. Nothing is force-disabled: unset the Adsterra values and
 * the AdSense units return on their own.
 *
 * ### Why Banner is preferred over Native
 *
 * Two reasons, both learned the hard way on this site:
 *
 * 1. **Native can only appear once per page.** Its container id is fixed by Adsterra, and their
 *    loader matches that exact element, so a page can carry exactly one — every slot after the
 *    first renders nothing. A banner has no shared id (each is its own iframe), so it repeats at
 *    every slot with the same key.
 * 2. **A native unit's height is set in the Adsterra dashboard**, not here — it lays its items out
 *    in a grid and can swallow most of a phone screen. A banner is whatever size you created it
 *    at, so 320x50 stays 320x50.
 *
 * Native remains fully supported and is used whenever no banner key is set, so this is a
 * preference rather than a removal.
 *
 * Config is fetched at runtime (`app-config.json`), so this waits for that fetch before choosing.
 * Rendering nothing while it resolves avoids flashing an AdSense unit for a frame and then
 * swapping it for an Adsterra one.
 *
 * Every branch renders `null` when its own network is unconfigured, so a deployment with no ad
 * setup ships no ad markup rather than empty boxes.
 */
export default function AdSlot({ placement }: AdSlotProps) {
  const ads = useAdsterra()

  // Config still in flight. Nothing renders yet — see the note above.
  if (!ads.loaded) return null

  if (ads.bannerKey) {
    return (
      <AdsterraBanner bannerKey={ads.bannerKey} width={ads.bannerWidth} height={ads.bannerHeight} />
    )
  }

  // Only one of these can exist per page; AdsterraNative enforces that itself.
  if (ads.src && ads.container) {
    return <AdsterraNative src={ads.src} container={ads.container} />
  }

  // SmartLink last among Adsterra options: a labelled tap target rather than a rendered ad, so it
  // earns only when a customer chooses it. Useful while a zone is still being approved.
  if (ADSTERRA_SMARTLINK_URL) return <SmartLinkPromo />

  return placement === 'feed' ? <InFeedAd /> : <DisplayAd slot={ADSENSE_STATUS_SLOT} />
}
