/**
 * Runtime configuration, fetched from `/app-config.json` instead of baked in at build.
 *
 * ### Why this exists
 *
 * Vite inlines `VITE_*` at BUILD time, so every value read that way forces one build per café —
 * the thing that stops a single skeleton artefact serving many tenants. `app-config.json` is
 * already generated into `dist/` by `vite.config.ts` and is a plain static file, so a deployment
 * can swap it (by hand, from Supabase, or from a Worker keyed on hostname) and the same bundle
 * serves a different café. Ad unit ids are public — they appear in any visitor's page source — so
 * they belong here rather than in the build.
 *
 * ### Precedence, and why the env fallback stays
 *
 * Runtime config wins; a `VITE_*` value is the fallback. Keeping the fallback means deployments
 * already configured through Pages environment variables keep working untouched, so this is a
 * migration rather than a breaking change.
 *
 * ### Failure is silent on purpose
 *
 * A missing or malformed `app-config.json` resolves to `{}` rather than throwing. Ads are the
 * least important thing on a page whose job is taking a food order — a config fetch must never be
 * able to break the ordering flow.
 */

import type { ShopeeAffiliateConfig } from './shopee'

export interface RuntimeConfig {
  supabaseUrl?: string
  supabaseAnonKey?: string
  cafeName?: string
  /**
   * Full `src` of the RollerAds tag, verbatim from my.rollerads.com → your site → get code.
   *
   * Deliberately the whole URL rather than an id to assemble one from: the host and path are issued
   * by RollerAds and are not derivable from the numeric site id, so a constructed URL would load
   * nothing while looking configured. See `lib/rollerads.ts`.
   *
   * RollerAds is page-level only (push / in-page push / popunder) — it has no in-page display
   * format, so nothing here feeds `AdSlot`.
   */
  rolleradsTagSrc?: string
  /** RollerAds site id. Informational — the tag URL already embeds it. */
  rolleradsSiteId?: string
  /**
   * Where to fetch the central affiliate catalog from — see `lib/partnerCatalog.ts`.
   *
   * Here rather than hardcoded so the catalog can move (GitHub raw → Cloudflare Pages → R2) by
   * editing this file, with no code change and no café rebuild.
   */
  partnerCatalogUrl?: string
  /**
   * Shopee affiliate links for THIS café, overriding the central catalog.
   *
   * Normally absent: a café inherits its placements from the catalog on `main`, so a freshly
   * registered one needs no ad setup at all. Setting this block is the escape hatch — a café
   * curating its own list, or opting out with `products: []`. Present-but-empty still wins, so
   * opting out is possible. See `lib/shopee.ts` and `lib/partnerCatalog.ts`.
   *
   * A key present in `app-config.json` but ABSENT from this interface is silently dropped, which is
   * exactly how an earlier hand-edited `shopeeAffiliate` block sat on the live site rendering
   * nothing at all. Anything the page must read has to be declared here.
   */
  shopeeAffiliate?: ShopeeAffiliateConfig
}

let cached: RuntimeConfig | null = null
let inflight: Promise<RuntimeConfig> | null = null

/**
 * Fetches and caches `/app-config.json`. Concurrent callers share one request, so several ad
 * slots mounting together do not each trigger a fetch.
 */
export function loadRuntimeConfig(): Promise<RuntimeConfig> {
  if (cached) return Promise.resolve(cached)
  if (inflight) return inflight

  inflight = fetch('/app-config.json', { cache: 'no-cache' })
    .then((res) => (res.ok ? res.json() : {}))
    .then((json: unknown) => {
      cached = json && typeof json === 'object' ? (json as RuntimeConfig) : {}
      return cached
    })
    .catch(() => {
      // Absent, offline, or malformed — treat as "nothing configured" and carry on.
      cached = {}
      return cached
    })
    .finally(() => {
      inflight = null
    })

  return inflight
}
