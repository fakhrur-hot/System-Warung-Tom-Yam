import { ADSENSE_STATUS_SLOT } from '../lib/adsense'
import {
  ADSTERRA_BANNER_KEY,
  ADSTERRA_NATIVE_CONTAINER,
  ADSTERRA_NATIVE_SRC,
  ADSTERRA_SMARTLINK_URL,
} from '../lib/adsterra'
import AdsterraBanner from './AdsterraBanner'
import AdsterraNative from './AdsterraNative'
import DisplayAd from './DisplayAd'
import InFeedAd from './InFeedAd'
import SmartLinkPromo from './SmartLinkPromo'

interface AdSlotProps {
  /**
   * `feed` — between menu items, where a unit should inherit page width and blend with the list.
   * `status` — the order-status view, where the customer is waiting and has real dwell time.
   */
  placement: 'feed' | 'status'
}

/**
 * The single decision point for which ad network fills a slot.
 *
 * Adsterra wins when configured, because running it alongside AdSense on the same page view is
 * the combination worth avoiding: AdSense holds publishers responsible for what appears beside its
 * units, and a strike there costs more than the extra impression. Nothing here is force-disabled —
 * unset the Adsterra vars and the AdSense units come back on their own.
 *
 * Every branch renders `null` when its own network is unconfigured, so a deployment with no ad
 * setup at all ships no ad markup rather than empty boxes.
 */
export default function AdSlot({ placement }: AdSlotProps) {
  // Native Banner first: it flows inline at page width, so it suits both placements better than a
  // fixed-size iframe banner.
  if (ADSTERRA_NATIVE_SRC && ADSTERRA_NATIVE_CONTAINER) return <AdsterraNative />
  if (ADSTERRA_BANNER_KEY) return <AdsterraBanner />

  // SmartLink last among the Adsterra options: it is a labelled tap target, not a rendered ad, so
  // it earns only when a customer chooses it. Useful as a fallback while a Native Banner zone is
  // still being approved.
  if (ADSTERRA_SMARTLINK_URL) return <SmartLinkPromo />

  return placement === 'feed' ? <InFeedAd /> : <DisplayAd slot={ADSENSE_STATUS_SLOT} />
}
