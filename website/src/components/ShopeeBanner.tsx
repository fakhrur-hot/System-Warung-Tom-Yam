import { useMemo, useState } from 'react'
import { pickProduct, withSubId, type ShopeeAffiliateConfig } from '../lib/shopee'

interface ShopeeBannerProps {
  config: ShopeeAffiliateConfig
  /** Distinguishes slots on one page so they never show the same product. */
  slotIndex: number
}

/**
 * One hand-picked Shopee product, shown as an affiliate banner.
 *
 * Marked visibly as an ad. That is not decoration: an unlabelled product tile inside a restaurant
 * menu reads as something the café is selling, and a customer tapping it expects to add food to
 * their order, not to leave for Shopee. The label plus `rel="sponsored"` keeps that honest — and
 * `sponsored` is the correct rel for a paid link.
 *
 * Opens in a new tab so the order in progress is never navigated away from.
 */
export default function ShopeeBanner({ config, slotIndex }: ShopeeBannerProps) {
  // Chosen once per mount — see pickProduct for why this is not on a timer.
  const product = useMemo(
    () => pickProduct(config.products, slotIndex),
    [config.products, slotIndex],
  )
  const [imageBroken, setImageBroken] = useState(false)

  // Shopee's CDN can refuse hotlinked requests, and a dead <img> leaves a blank gap mid-menu that
  // looks like a broken café site rather than a missing ad. Drop the whole unit instead.
  if (imageBroken) return null

  const href = withSubId(product.href, config.subId)

  return (
    <a
      href={href}
      target="_blank"
      rel="sponsored noopener noreferrer"
      className="mx-auto block w-full max-w-sm overflow-hidden rounded-lg border border-emerald-100 bg-white py-2"
    >
      <div className="flex items-center gap-3 px-3">
        <img
          src={product.img}
          alt={product.alt}
          loading="lazy"
          // Some CDNs gate hotlinking on the Referer header; sending none is more likely to load
          // than sending a foreign origin.
          referrerPolicy="no-referrer"
          onError={() => setImageBroken(true)}
          className="h-16 w-16 flex-shrink-0 rounded object-cover"
        />
        <div className="min-w-0 flex-1">
          <span className="mb-1 inline-block rounded bg-emerald-100 px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-emerald-800">
            Ad
          </span>
          <p className="truncate text-sm text-emerald-900">{product.alt}</p>
        </div>
      </div>
    </a>
  )
}
