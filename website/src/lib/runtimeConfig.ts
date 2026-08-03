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

export interface RuntimeConfig {
  supabaseUrl?: string
  supabaseAnonKey?: string
  cafeName?: string
  /** Adsterra Native Banner `invoke.js` URL, verbatim from the unit's snippet. */
  adsterraSrc?: string
  /** Adsterra Native Banner container id, verbatim — their loader matches it exactly. */
  adsterraContainer?: string
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
