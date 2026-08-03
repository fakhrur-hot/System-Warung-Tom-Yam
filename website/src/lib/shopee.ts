import { useEffect, useState } from 'react'
import { loadRuntimeConfig } from './runtimeConfig'

/**
 * Shopee affiliate banners — hand-picked products shown between menu items.
 *
 * By far the best-fitting ad format for this page. Unlike a network, the café chooses every
 * product, so nothing unexpected appears next to their food; Shopee is where Malaysian customers
 * already shop; and it pays per sale rather than per impression, so it does not need volume or
 * intrusive formats to be worth anything.
 *
 * ### Links must come from the affiliate portal
 *
 * Use the short link Shopee generates (`https://s.shopee.com.my/XXXX`) — it already carries your
 * attribution. Do NOT hand-build `shopee.com.my/product/{id}?affiliate_id=...`: that is not a
 * documented tracking form, and a link that looks affiliated but is not earns nothing while
 * appearing to work. Shopee's own documented alternative is the `an_redir?origin_link=` form.
 *
 * [subId] is optional and genuinely useful: Shopee reports it back, so tagging placements lets you
 * see which menu position actually converts rather than guessing.
 */
export interface ShopeeProduct {
  /** The short link from the Shopee affiliate portal, verbatim. */
  href: string
  /** Product image URL. */
  img: string
  /** Alt text — also what a customer sees if the image fails to load. */
  alt: string
}

export interface ShopeeAffiliateConfig {
  products: ShopeeProduct[]
  /** Appended as `sub_id` for per-placement reporting. Optional. */
  subId?: string
}

/**
 * Resolves the product list from runtime config, so each café curates its own without a rebuild —
 * the same contract as the rest of this app's configuration.
 */
export function useShopeeProducts(): { loaded: boolean; config?: ShopeeAffiliateConfig } {
  const [state, setState] = useState<{ loaded: boolean; config?: ShopeeAffiliateConfig }>({
    loaded: false,
  })

  useEffect(() => {
    let alive = true
    loadRuntimeConfig().then((cfg) => {
      if (!alive) return
      const raw = cfg.shopeeAffiliate
      const products = (raw?.products ?? []).filter(
        (p): p is ShopeeProduct => Boolean(p && p.href && p.img),
      )
      setState({
        loaded: true,
        config: products.length ? { products, subId: raw?.subId } : undefined,
      })
    })
    return () => {
      alive = false
    }
  }, [])

  return state
}

/**
 * Adds `sub_id` for reporting, leaving everything else untouched.
 *
 * Deliberately does NOT add `affiliate_id`: a portal-generated short link already encodes the
 * affiliate, and bolting a second attribution parameter onto it risks conflicting with what Shopee
 * already resolves server-side. Nothing is appended when there is no sub id.
 */
export function withSubId(href: string, subId?: string): string {
  if (!subId) return href
  const sep = href.includes('?') ? '&' : '?'
  return `${href}${sep}sub_id=${encodeURIComponent(subId)}`
}

/**
 * Which product a given slot shows.
 *
 * Rotation is **per mount and per slot index**, not on a timer. A timer was the obvious design and
 * is the wrong one here: the banner is a tap target inside a menu the customer is actively
 * scrolling, and swapping it under their finger produces exactly the accidental clicks that ad
 * placement policies exist to prevent — and an accidental tap here navigates a customer away
 * mid-order. Per-mount rotation still varies the product every page load, and the index offset
 * means two slots on one page never show the same item.
 */
export function pickProduct(products: ShopeeProduct[], slotIndex: number): ShopeeProduct {
  // A per-session offset so a returning customer does not always meet the same first product.
  const offset = Math.floor(Math.random() * products.length)
  return products[(offset + slotIndex) % products.length]
}
