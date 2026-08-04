/**
 * The central affiliate catalog — one file, on `main`, that every café's ordering page reads.
 *
 * ### The problem this solves
 *
 * Affiliate links change often (daily, in practice). Putting them in each café's own
 * `app-config.json` means one copy per café to update, and a newly registered café starts with an
 * empty list until someone remembers to fill it in. So the links live in ONE place — `promos/
 * partners.json` on `main`, deployed by its own Cloudflare Pages project — and every café fetches
 * it at runtime. One edit, every café, no rebuild.
 *
 * Café branches are cut from `main`, so they may carry the file in git; they never serve or read
 * their copy. The URL below is always the central one.
 *
 * ### Precedence
 *
 * A café's own `shopeeAffiliate` block in its `app-config.json` WINS over the catalog. That is the
 * escape hatch: a café that wants its own curated list, or none at all, sets it locally. The normal
 * case — and what a freshly registered café ships with — is no local block, so the catalog fills in.
 *
 * ### Why the URL is configurable
 *
 * `partnerCatalogUrl` is read from runtime config first, so the catalog can be moved (Pages → R2 →
 * a Worker) by editing each café's `app-config.json`, with no code change and no café rebuild. The
 * `VITE_PARTNER_CATALOG_URL` build-time value is the fallback.
 *
 * ### Why not a file named `ads.json`
 *
 * EasyList blocks requests by filename and path — `ads.json`, `/ads/`, and paths containing
 * `affiliate` are all matched, so Brave and uBlock would drop the request and every café would
 * silently render nothing. The neutral name is load-bearing, not cosmetic.
 *
 * ### Failure is silent, always
 *
 * No catalog, a 404, a CORS refusal, malformed JSON, an ad blocker, an offline phone — all resolve
 * to "nothing configured". The page's job is taking a food order; an affiliate tile must never be
 * able to break that, and a café whose catalog project does not exist yet simply shows no ads.
 */

import type { ShopeeAffiliateConfig } from './shopee'

/** Build-time fallback for the catalog URL. Runtime config wins — see the note above. */
const ENV_CATALOG_URL = import.meta.env.VITE_PARTNER_CATALOG_URL as string | undefined

export interface PartnerCatalog {
  /** Used by any café without its own `byCafe` entry. */
  default?: ShopeeAffiliateConfig
  /** Per-café overrides, keyed on the café's `cafeName`. Matched case-insensitively. */
  byCafe?: Record<string, ShopeeAffiliateConfig>
}

let cached: PartnerCatalog | null = null
let inflight: Promise<PartnerCatalog> | null = null

/**
 * Fetches and caches the catalog. Concurrent callers share one request, so the several ad slots on
 * a menu page cost one fetch between them rather than one each.
 *
 * Returns `{}` — not a rejection — for every failure mode. See the note above.
 */
export function loadPartnerCatalog(url: string | undefined): Promise<PartnerCatalog> {
  if (cached) return Promise.resolve(cached)
  if (inflight) return inflight

  const target = url || ENV_CATALOG_URL
  if (!target) {
    cached = {}
    return Promise.resolve(cached)
  }

  inflight = fetch(target, { cache: 'no-cache' })
    .then((res) => (res.ok ? res.json() : {}))
    .then((json: unknown) => {
      cached = json && typeof json === 'object' ? (json as PartnerCatalog) : {}
      return cached
    })
    .catch(() => {
      cached = {}
      return cached
    })
    .finally(() => {
      inflight = null
    })

  return inflight
}

/**
 * The catalog entry for [cafeName], falling back to `default`.
 *
 * Matching is case-insensitive and trimmed because the key is a human-typed café name that has to
 * agree with another human-typed café name in a different file — an exact-match rule would fail on
 * "Tani Tom Yam" vs "tani tom yam" and give no clue why.
 */
export function catalogEntryFor(
  catalog: PartnerCatalog,
  cafeName: string | undefined,
): ShopeeAffiliateConfig | undefined {
  const byCafe = catalog.byCafe
  if (byCafe && cafeName) {
    const wanted = cafeName.trim().toLowerCase()
    for (const [key, value] of Object.entries(byCafe)) {
      if (key.trim().toLowerCase() === wanted) return value
    }
  }
  return catalog.default
}
