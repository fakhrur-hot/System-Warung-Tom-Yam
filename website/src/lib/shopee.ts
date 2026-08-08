/**
 * Shopee affiliate placements for the customer-facing ordering page.
 *
 * ### What this is, and what it is not
 *
 * Unlike RollerAds or AdSense there is no ad server here and no script to load — an affiliate
 * placement is just the café's own referral link, rendered by us. That has two consequences worth
 * stating: nothing here can be blocked by an ad blocker's script filters, and nothing here reports
 * a fill rate. A tile shows because the café configured it, full stop.
 *
 * ### Where the links come from
 *
 * `app-config.json` at runtime (see [loadRuntimeConfig]), never `VITE_*` inlined at build. Same
 * reason as the RollerAds tag: a link set is per-café, and baking it in would force one build per
 * café. Unlike a tag id, this one is a *list*, so the build generator reads it from a single JSON
 * env var (`VITE_SHOPEE_PRODUCTS`) rather than a field per product.
 *
 * ### Sub-ids
 *
 * Shopee's affiliate reporting splits earnings by `sub_id`, which is how a café tells "someone
 * scanned a table QR and bought something" apart from its other channels. [withSubId] appends it
 * only when the configured link does not already carry one — a link pasted straight out of the
 * affiliate dashboard may already have its own, and overwriting that would silently retarget
 * someone else's reporting.
 *
 * ### Failure is silent, like every other ad path here
 *
 * A malformed link is dropped rather than rendered. The page's job is taking a food order; an
 * affiliate tile is the least important thing on it and must never be able to break the flow.
 */

import { useEffect, useState } from 'react'
import { catalogEntryFor, loadPartnerCatalog } from './partnerCatalog'
import { loadRuntimeConfig } from './runtimeConfig'

/** One affiliate placement. [img] is optional — see [ShopeeBanner] for what renders without it. */
export interface ShopeeProduct {
  /** Absolute https affiliate URL (a Shopee short link, or a network's tracking link). */
  href: string
  /** Product image URL. Empty or absent renders the text form instead of a thumbnail row. */
  img?: string
  /** Human label. Used as the card text and as the image's alt text. */
  alt?: string
}

export interface ShopeeAffiliateConfig {
  /** Shopee affiliate sub-id for attributing these taps, e.g. `"tani-menu"`. */
  subId?: string
  products?: ShopeeProduct[]
}

/**
 * Append `sub_id` unless the link already has one.
 *
 * Returns [href] untouched when it is not a parseable absolute URL — the caller has already
 * dropped those, so this is belt-and-braces rather than the real guard.
 */
export function withSubId(href: string, subId?: string): string {
  if (!subId) return href
  try {
    const url = new URL(href)
    if (url.searchParams.has('sub_id')) return href
    url.searchParams.set('sub_id', subId)
    return url.toString()
  } catch {
    return href
  }
}

const ROTATION_STORAGE_KEY = 'shopee_rotation_cursor'

/** Set once the first slot on a page reads it, so every later slot on the same page agrees. */
let cachedRotationOffset: number | null = null

/**
 * This page load's rotation offset — round-robin, not random.
 *
 * A random offset per mount (the previous design) can show the same product several visits in a
 * row by chance, and never guarantees the catalog's tail ever gets shown at all. This instead
 * reads a cursor from `localStorage`, hands it out, and advances it by one, so the NEXT page load
 * on this browser picks up exactly where this one left off — every product gets its turn before
 * any repeats. Mirrors the Android app's `AffiliateAdsViewModel.rotationOffset`, which is the same
 * wrap-around cursor kept in memory across a table grid's own redraws.
 *
 * Cached per module load rather than re-read per call: two slots on one page must agree on the
 * base offset (their own `slotIndex` is what keeps them from showing the same item), not each
 * advance the cursor independently and skip products.
 *
 * Falls back to a one-off random value when storage throws (private browsing, or a test
 * environment with no `window`) — still varies the product, just without persisting the cycle.
 */
function rotationOffsetForThisLoad(): number {
  if (cachedRotationOffset !== null) return cachedRotationOffset
  try {
    const stored = Number(window.localStorage.getItem(ROTATION_STORAGE_KEY))
    const current = Number.isFinite(stored) ? stored : 0
    window.localStorage.setItem(ROTATION_STORAGE_KEY, String(current + 1))
    cachedRotationOffset = current
  } catch {
    cachedRotationOffset = Math.floor(Math.random() * 1000)
  }
  return cachedRotationOffset
}

/**
 * Which product a given slot shows.
 *
 * Not on a timer. A timer was the obvious design and is the wrong one here: the banner is a tap
 * target inside a menu the customer is actively scrolling, and swapping it under their finger
 * produces exactly the accidental clicks that ad placement policies exist to prevent — and an
 * accidental tap here navigates a customer away mid-order. Rotation instead advances once per page
 * load (see [rotationOffsetForThisLoad]), and the slot index means two slots on one page never
 * show the same item.
 */
export function pickProduct(products: ShopeeProduct[], slotIndex: number): ShopeeProduct {
  const offset = rotationOffsetForThisLoad()
  return products[(offset + slotIndex) % products.length]
}

/**
 * Keep only placements we are willing to send a paying customer to.
 *
 * `https` is required: the ordering page is served over https, and a mixed-content link is both a
 * browser warning and a downgrade of someone's shopping session. Anything without a usable href is
 * dropped silently rather than rendered as a dead tile.
 *
 * The host is deliberately NOT restricted to shopee.com.my — Malaysian affiliate links are
 * routinely issued through networks (Involve Asia's `invol.co`, for one), and rejecting those
 * would break the common case in the name of a check that a typo'd host would pass anyway.
 *
 * An empty `img` is explicitly NOT a reason to drop a placement. Requiring one is what kept this
 * feature invisible while its config looked complete; [ShopeeBanner] renders a text form instead.
 */
export function validProducts(products: ShopeeProduct[] | undefined): ShopeeProduct[] {
  if (!Array.isArray(products)) return []
  return products.filter((p) => {
    if (!p || typeof p.href !== 'string') return false
    try {
      return new URL(p.href).protocol === 'https:'
    } catch {
      return false
    }
  })
}

/**
 * The Shopee placements for THIS deployment, with sub-ids already applied.
 *
 * ### Two sources, in order
 *
 * 1. This café's own `shopeeAffiliate` block, if it has one — the escape hatch for a café that
 *    curates its own list or wants none.
 * 2. Otherwise the central catalog on `main` (see [loadPartnerCatalog]), which is what a freshly
 *    registered café gets with no setup at all.
 *
 * A café with a LOCAL block never falls through to the catalog, even if its own list is empty after
 * validation. "I set this myself" has to mean something, or opting out would be impossible.
 *
 * Returns `loaded: false` until both fetches settle, so a slot renders nothing rather than flashing
 * the AdSense unit behind it and then replacing it. Both fetches are cached module-side, so all
 * slots on a page share one of each.
 */
export function useShopeeAffiliate(): { loaded: boolean; products: ShopeeProduct[] } {
  const [state, setState] = useState<{ loaded: boolean; products: ShopeeProduct[] }>({
    loaded: false,
    products: [],
  })

  useEffect(() => {
    let alive = true

    loadRuntimeConfig()
      .then(async (cfg) => {
        const local = cfg.shopeeAffiliate
        // A local block present at all — even with an empty product list — is deliberate, and ends
        // the lookup here.
        if (local) return local
        const catalog = await loadPartnerCatalog(cfg.partnerCatalogUrl)
        return catalogEntryFor(catalog, cfg.cafeName)
      })
      .then((affiliate) => {
        if (!alive) return
        const products = validProducts(affiliate?.products).map((p) => ({
          ...p,
          href: withSubId(p.href, affiliate?.subId),
        }))
        setState({ loaded: true, products })
      })
      .catch(() => {
        // Belt-and-braces: both loaders already swallow their own failures.
        if (alive) setState({ loaded: true, products: [] })
      })

    return () => {
      alive = false
    }
  }, [])

  return state
}
