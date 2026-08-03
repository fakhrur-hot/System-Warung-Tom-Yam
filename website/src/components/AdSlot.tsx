import { ADSENSE_STATUS_SLOT } from '../lib/adsense'
import DisplayAd from './DisplayAd'
import InFeedAd from './InFeedAd'

interface AdSlotProps {
  /**
   * `feed` — between menu items, where a unit should inherit page width and blend with the list.
   * `status` — a waiting or dead-end view, where the customer has real dwell time.
   */
  placement: 'feed' | 'status'
}

/**
 * The single decision point for what fills an in-page ad slot.
 *
 * Currently that is AdSense only. The Adsterra branches this used to carry were removed when the
 * café moved to RollerAds, which has **no in-page display format** — its formats are push,
 * in-page push and popunder, all page-level, loaded once in `App.tsx` (see `lib/rollerads.ts`).
 * So there is nothing network-specific left to choose between here.
 *
 * The indirection is kept rather than inlining `InFeedAd`/`DisplayAd` at the five call sites,
 * because it is what makes adding a second in-page network a one-file change instead of five —
 * which is exactly how the Adsterra work landed, and how it was removed again.
 *
 * Renders `null` when AdSense is unconfigured, so a deployment with no ad setup ships no ad markup
 * rather than empty boxes.
 *
 * Note RollerAds and AdSense coexist deliberately: RollerAds' push inventory does not compete for
 * the same on-page real estate, and they market it as AdSense-friendly. That is not true of every
 * network — the Adsterra integration deliberately suppressed AdSense for exactly that reason.
 */
export default function AdSlot({ placement }: AdSlotProps) {
  return placement === 'feed' ? <InFeedAd /> : <DisplayAd slot={ADSENSE_STATUS_SLOT} />
}
