/**
 * Adsterra ad configuration for the customer-facing ordering page.
 *
 * Every value is read from `VITE_*` env vars and every unit renders nothing when its own vars are
 * unset — the same fail-quiet contract as `lib/adsense.ts`, so an unconfigured deployment ships
 * with no ad markup rather than empty placeholders.
 *
 * ### Why the keys are not hardcoded
 *
 * Adsterra issues a per-publisher key (banner) or a per-zone `invoke.js` URL (native, social bar).
 * They are account-specific, so they live in the Cloudflare Pages environment. Vite inlines
 * `VITE_*` at BUILD time, so setting one in Pages only takes effect on the next deploy.
 *
 * ### Running Adsterra alongside AdSense
 *
 * Technically possible, but the two are configured independently here and it is generally unwise
 * to serve both on the same page view: AdSense holds publishers responsible for everything shown
 * alongside its units, and a policy strike there is far more expensive than the incremental
 * revenue. [adsterraEnabled] is what the ad slots branch on — when Adsterra is configured it takes
 * the slot, and AdSense's own components stay dark unless separately configured.
 */

import { useEffect, useState } from 'react'
import { loadRuntimeConfig } from './runtimeConfig'

/** Banner: the publisher key from Adsterra → Websites → the unit's code snippet (`'key'`). */
export const ADSTERRA_BANNER_KEY = import.meta.env.VITE_ADSTERRA_BANNER_KEY as string | undefined

/** Banner size. Must match the size the unit was created at, or Adsterra returns no fill. */
export const ADSTERRA_BANNER_WIDTH = Number(import.meta.env.VITE_ADSTERRA_BANNER_WIDTH ?? 300)
export const ADSTERRA_BANNER_HEIGHT = Number(import.meta.env.VITE_ADSTERRA_BANNER_HEIGHT ?? 250)

/**
 * Native Banner: the full `invoke.js` URL from the unit's snippet, e.g.
 * `//pl12345678.effectiveratecpm.com/abc.../invoke.js`.
 */
export const ADSTERRA_NATIVE_SRC = import.meta.env.VITE_ADSTERRA_NATIVE_SRC as string | undefined

/** Native Banner: the `id` of the `<div>` in the same snippet, e.g. `container-abc123...`. */
export const ADSTERRA_NATIVE_CONTAINER = import.meta.env.VITE_ADSTERRA_NATIVE_CONTAINER as
  | string
  | undefined

/**
 * Social Bar / Popunder: a page-level script URL. Loaded once from `App.tsx`.
 *
 * Deliberately opt-in and separate from the in-flow units. These formats can open or overlay
 * during checkout, which on a table-QR ordering page competes with the customer actually placing
 * their order — leave it unset to run in-flow banners only.
 */
export const ADSTERRA_SOCIAL_BAR_SRC = import.meta.env.VITE_ADSTERRA_SOCIAL_BAR_SRC as
  | string
  | undefined

/**
 * SmartLink / Direct Link — the FULL destination URL, not an ID.
 *
 * Adsterra's own description: a Direct Link "has no visual format, just a URL". It renders no
 * banner and cannot fill [AdsterraNative] or [AdsterraBanner], which need a Native Banner or
 * Banner unit and their `invoke.js` snippet. It is a place to *send* a visitor.
 *
 * That makes it a poor fit for the middle of an ordering flow: auto-redirecting or popping it
 * open takes a customer away from the order they came to place, which costs the café the sale.
 * [SmartLinkPromo] therefore renders it only as an explicit, labelled link the customer chooses to
 * tap, opened in a new tab so the order is never navigated away from.
 */
export const ADSTERRA_SMARTLINK_URL = import.meta.env.VITE_ADSTERRA_SMARTLINK_URL as
  | string
  | undefined

/** True when any in-flow Adsterra unit is configured — the ad slots branch on this. */
export const adsterraEnabled = Boolean(
  ADSTERRA_BANNER_KEY || (ADSTERRA_NATIVE_SRC && ADSTERRA_NATIVE_CONTAINER),
)

/**
 * The Native Banner unit for THIS deployment, preferring runtime config over the build-time env.
 *
 * Runtime config is what lets one build serve many cafés: swap `app-config.json` and the same
 * bundle points at a different café's unit. The `VITE_*` values remain as a fallback so
 * deployments already configured through Pages environment variables keep working.
 *
 * Returns `loaded: false` until the fetch settles, so a slot renders nothing rather than briefly
 * flashing an AdSense unit and then replacing it.
 */
export interface ResolvedAdsterra {
  loaded: boolean
  /** Native Banner. */
  src?: string
  container?: string
  /** Banner — takes precedence over native when set. See [useAdsterra]. */
  bannerKey?: string
  bannerWidth: number
  bannerHeight: number
}

/**
 * The Adsterra units for THIS deployment, preferring runtime config over the build-time env.
 *
 * Runtime config is what lets one build serve many cafés: swap `app-config.json` and the same
 * bundle points at a different café's units. The `VITE_*` values remain as a fallback so
 * deployments already configured through Pages environment variables keep working.
 *
 * Returns `loaded: false` until the fetch settles, so a slot renders nothing rather than briefly
 * flashing an AdSense unit and then replacing it.
 */
export function useAdsterra(): ResolvedAdsterra {
  const [state, setState] = useState<ResolvedAdsterra>({
    loaded: false,
    bannerWidth: ADSTERRA_BANNER_WIDTH,
    bannerHeight: ADSTERRA_BANNER_HEIGHT,
  })

  useEffect(() => {
    let alive = true
    loadRuntimeConfig().then((cfg) => {
      if (!alive) return
      setState({
        loaded: true,
        src: cfg.adsterraSrc || ADSTERRA_NATIVE_SRC,
        container: cfg.adsterraContainer || ADSTERRA_NATIVE_CONTAINER,
        bannerKey: cfg.adsterraBannerKey || ADSTERRA_BANNER_KEY,
        bannerWidth: cfg.adsterraBannerWidth || ADSTERRA_BANNER_WIDTH,
        bannerHeight: cfg.adsterraBannerHeight || ADSTERRA_BANNER_HEIGHT,
      })
    })
    return () => {
      alive = false
    }
  }, [])

  return state
}

let socialBarLoaded = false

/** Loads the Social Bar / Popunder script once. No-ops when unconfigured. */
export function loadAdsterraSocialBar(): void {
  if (socialBarLoaded || !ADSTERRA_SOCIAL_BAR_SRC) return
  socialBarLoaded = true

  const script = document.createElement('script')
  script.type = 'text/javascript'
  script.src = ADSTERRA_SOCIAL_BAR_SRC
  script.async = true
  // Adsterra's own snippet sets this; their loader checks for it.
  script.setAttribute('data-cfasync', 'false')
  script.onerror = () => {
    // Non-fatal. An ad blocker or a dead zone just means no ads this session — ordering itself
    // never depends on this script.
  }
  document.body.appendChild(script)
}

/**
 * The self-contained HTML for one Adsterra **banner** unit.
 *
 * Rendered inside an iframe `srcDoc` rather than injected into the page. Adsterra's banner
 * `invoke.js` emits its markup with `document.write`, which after initial page load would blow
 * away the whole SPA document — React included. An iframe gives it its own document to write
 * into, which is the standard way to carry these units in a single-page app, and it also stops a
 * third-party script from reaching into the ordering DOM.
 */
export function bannerFrameHtml(key: string, width: number, height: number): string {
  // JSON.stringify escapes the key safely for the JS literal, so a malformed value from the
  // environment cannot break out of the string and inject script into the frame.
  return `<!DOCTYPE html><html><head><meta charset="utf-8">
<style>html,body{margin:0;padding:0;overflow:hidden;background:transparent}</style>
</head><body>
<script type="text/javascript">
  atOptions = {
    'key' : ${JSON.stringify(key)},
    'format' : 'iframe',
    'height' : ${height},
    'width' : ${width},
    'params' : {}
  };
<\/script>
<script type="text/javascript" src="//www.highperformanceformat.com/${encodeURIComponent(key)}/invoke.js"><\/script>
</body></html>`
}
