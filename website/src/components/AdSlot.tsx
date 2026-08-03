import { ADSENSE_STATUS_SLOT } from '../lib/adsense'
import { useShopeeProducts } from '../lib/shopee'
import DisplayAd from './DisplayAd'
import InFeedAd from './InFeedAd'
import ShopeeBanner from './ShopeeBanner'

interface AdSlotProps {
  /**
   * `feed` — between menu items, where a unit should inherit page width and blend with the list.
   * `status` — a waiting or dead-end view, where the customer has real dwell time.
   */
  placement: 'feed' | 'status'
  /** Distinguishes slots on one page so rotating units never repeat a product. */
  slotIndex?: number
}

/**
 * The single decision point for what fills an in-page ad slot.
 *
 * Order: Shopee affiliate → AdSense. Shopee wins when the café has curated products, because on a
 * restaurant's own ordering page a hand-picked product is strictly better inventory than a network
 * unit: the café controls exactly what appears beside its food, it is locally relevant, and it pays
 * per sale rather than needing volume.
 *
 * RollerAds does not appear here at all — it has no in-page display format. It is a page-level
 * script loaded once in `App.tsx` (see `lib/rollerads.ts`) and does not compete for this space.
 *
 * Renders `null` when nothing is configured, so a deployment with no ad setup ships no ad markup
 * rather than empty boxes. Waits for the runtime config fetch before choosing, so a slot never
 * flashes an AdSense unit and then swaps it for a Shopee banner.
 */
export default function AdSlot({ placement, slotIndex = 0 }: AdSlotProps) {
  const shopee = useShopeeProducts()

  if (!shopee.loaded) return null

  if (shopee.config) {
    return <ShopeeBanner config={shopee.config} slotIndex={slotIndex} />
  }

  return placement === 'feed' ? <InFeedAd /> : <DisplayAd slot={ADSENSE_STATUS_SLOT} />
}
