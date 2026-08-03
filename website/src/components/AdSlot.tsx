import { ADSENSE_STATUS_SLOT } from '../lib/adsense'
import { ADSTERRA_BANNER_KEY, ADSTERRA_SMARTLINK_URL, useAdsterraNative } from '../lib/adsterra'
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
 * The single decision point for which ad network fills a slot.
 *
 * Order: Adsterra Native → Adsterra Banner → SmartLink → AdSense. Adsterra wins whenever it is
 * configured, because running it alongside AdSense on one page view is the combination worth
 * avoiding — AdSense holds publishers responsible for what appears beside its units, and a strike
 * costs more than the extra impression. Nothing is force-disabled: unset the Adsterra values and
 * the AdSense units return on their own.
 *
 * Native config is fetched at runtime (`app-config.json`), so this waits for that fetch before
 * choosing. Rendering nothing while it resolves avoids flashing an AdSense unit for a frame and
 * then swapping it for an Adsterra one.
 *
 * Every branch renders `null` when its own network is unconfigured, so a deployment with no ad
 * setup ships no ad markup rather than empty boxes.
 */
export default function AdSlot({ placement }: AdSlotProps) {
  const native = useAdsterraNative()

  // Config still in flight. Nothing renders yet — see the note above.
  if (!native.loaded) return null

  // Native first: it flows inline at page width, so it suits both placements better than a
  // fixed-size iframe banner. Only one can exist per page; AdsterraNative enforces that itself.
  if (native.src && native.container) {
    return <AdsterraNative src={native.src} container={native.container} />
  }
  if (ADSTERRA_BANNER_KEY) return <AdsterraBanner />

  // SmartLink last among Adsterra options: a labelled tap target rather than a rendered ad, so it
  // earns only when a customer chooses it. Useful while a Native zone is still being approved.
  if (ADSTERRA_SMARTLINK_URL) return <SmartLinkPromo />

  return placement === 'feed' ? <InFeedAd /> : <DisplayAd slot={ADSENSE_STATUS_SLOT} />
}
