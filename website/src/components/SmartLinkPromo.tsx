import { ADSTERRA_SMARTLINK_URL } from '../lib/adsterra'

/**
 * An Adsterra SmartLink (Direct Link) rendered as an explicit, labelled link.
 *
 * A SmartLink has no visual format — it is just a destination URL. So it cannot be "displayed"
 * the way a banner is; the only honest ways to use one are a link the visitor chooses to tap, or
 * a redirect. On a table-QR ordering page a redirect is the wrong choice at any price: it takes a
 * customer mid-order away from the café's own checkout, and the café loses the order that the
 * whole site exists to capture.
 *
 * So this is a tap target, not an interception:
 * - `target="_blank"` so the order state is never navigated away from,
 * - `rel="sponsored noopener noreferrer"` — `sponsored` is the correct rel for paid links and
 *   keeps the site honest with search engines; `noopener` stops the opened page reaching
 *   `window.opener`,
 * - visibly marked as an ad, so a customer is never tricked into thinking it is part of the menu.
 *
 * Renders nothing when unconfigured.
 */
export default function SmartLinkPromo() {
  if (!ADSTERRA_SMARTLINK_URL) return null

  return (
    <a
      href={ADSTERRA_SMARTLINK_URL}
      target="_blank"
      rel="sponsored noopener noreferrer"
      className="block rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-center text-sm text-emerald-700 hover:bg-emerald-100"
    >
      <span className="mr-1 rounded bg-emerald-200 px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-emerald-800">
        Ad
      </span>
      Sponsored offers
    </a>
  )
}
