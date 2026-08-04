import { useMemo, useState } from 'react'
import { pickProduct, type ShopeeProduct } from '../lib/shopee'

interface ShopeeBannerProps {
  /** Already validated and sub-id-tagged by [useShopeeAffiliate]. Never empty. */
  products: ShopeeProduct[]
  /** Distinguishes slots on one page so two never show the same product. */
  slotIndex: number
}

/**
 * One hand-picked Shopee product, shown as an affiliate banner between menu items.
 *
 * Marked visibly as an ad. That is not decoration: an unlabelled product tile inside a restaurant
 * menu reads as something the café is selling, and a customer tapping it expects to add food to
 * their order, not to leave for Shopee. The label plus `rel="sponsored"` keeps that honest — and
 * `sponsored` is the correct rel for a paid link. Opens in a new tab so an order in progress is
 * never navigated away from.
 *
 * ### Image optional, on purpose
 *
 * A café configures a link long before it has a product image, and the earlier version of this
 * required `img` — which is why a seeded product with `"img": ""` was filtered out and the whole
 * feature rendered nothing for days while looking correctly configured. So the image is now the
 * enhancement, not the entry fee: with one, this is a thumbnail row; without one, the same row
 * carries a "Shop on Shopee" call to action instead. It never renders nothing.
 *
 * A broken image URL degrades to that same text form at runtime, rather than dropping the unit:
 * Shopee's CDN can refuse hotlinked requests, and losing the placement to someone else's
 * Referer policy is worse than showing the label we already have.
 */
export default function ShopeeBanner({ products, slotIndex }: ShopeeBannerProps) {
  // Chosen once per mount — see pickProduct for why this is not on a timer.
  const product = useMemo(() => pickProduct(products, slotIndex), [products, slotIndex])
  const [imageBroken, setImageBroken] = useState(false)

  const label = product.alt?.trim() || 'Shopee pick'
  const showImage = Boolean(product.img?.trim()) && !imageBroken

  return (
    <a
      href={product.href}
      target="_blank"
      rel="sponsored noopener noreferrer"
      className="mx-auto block w-full max-w-sm overflow-hidden rounded-lg border border-emerald-100 bg-white py-2"
    >
      <div className="flex items-center gap-3 px-3">
        {showImage && (
          <img
            src={product.img}
            alt={label}
            loading="lazy"
            // Some CDNs gate hotlinking on the Referer header; sending none is more likely to load
            // than sending a foreign origin.
            referrerPolicy="no-referrer"
            onError={() => setImageBroken(true)}
            className="h-16 w-16 flex-shrink-0 rounded object-cover"
          />
        )}
        <div className="min-w-0 flex-1">
          <span className="mb-1 inline-block rounded bg-emerald-100 px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-emerald-800">
            Ad
          </span>
          <p className="truncate text-sm text-emerald-900">{label}</p>
          {/* Without a thumbnail the row needs something that reads as tappable. */}
          {!showImage && (
            <p className="text-xs font-medium text-emerald-600">Shop on Shopee →</p>
          )}
        </div>
      </div>
    </a>
  )
}
