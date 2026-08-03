/**
 * RollerAds — a **page-level** ad script, loaded once from `App.tsx`.
 *
 * ### Why there are no slot components here
 *
 * RollerAds has no in-page display format. Their publisher formats are push notifications,
 * in-page push, and OnClick/popunder — none of which fill a `<div>`. So unlike the Adsterra
 * integration this replaced, there is nothing to render into `AdSlot`; the whole surface is one
 * script tag whose effects are overlays, permission prompts and new windows.
 *
 * That also means RollerAds does not compete with in-page AdSense inventory — RollerAds markets
 * push as "AdSense-friendly" — so `AdSlot` continues to serve AdSense units alongside this.
 *
 * ### What this does to the ordering page
 *
 * Recorded plainly, because it is a real cost and easy to forget once it is working:
 *
 * - Push asks for browser notification permission, which on a table-QR page interrupts a customer
 *   who scanned to order food.
 * - OnClick/popunder opens a window on interaction, on the one surface whose job is capturing an
 *   order.
 *
 * Which of those actually fire is controlled in the RollerAds dashboard for the site, not here.
 * If ordering conversion drops after enabling this, the dashboard's format toggles are the first
 * thing to check — nothing in this file can suppress an individual format.
 *
 * ### Configuration
 *
 * [ROLLERADS_TAG_SRC] must be the FULL script `src` from my.rollerads.com → your site → get code.
 * It is deliberately not assembled from [ROLLERADS_SITE_ID]: the tag's host and path are issued by
 * RollerAds and are not derivable from the numeric id, so guessing a URL would produce a tag that
 * silently loads nothing. Runtime config wins over the build-time env, so a café can be switched
 * by swapping `app-config.json` without a rebuild — same contract as the rest of this app.
 */

/** The site/publisher id from the RollerAds dashboard. Informational: the tag URL normally embeds
 *  it already, so this is not used to construct anything. */
export const ROLLERADS_SITE_ID = import.meta.env.VITE_ROLLERADS_SITE_ID as string | undefined

/** Full `src` of the RollerAds tag, verbatim from the dashboard snippet. */
export const ROLLERADS_TAG_SRC = import.meta.env.VITE_ROLLERADS_TAG_SRC as string | undefined

let loaded = false

/**
 * Injects the RollerAds tag once. No-ops when unconfigured, so a deployment without a tag ships
 * no third-party script at all rather than a broken request.
 *
 * [tagSrc] comes from the caller so runtime config can override the build-time value.
 */
export function loadRollerAds(tagSrc?: string): void {
  const src = tagSrc || ROLLERADS_TAG_SRC
  if (loaded || !src) return
  loaded = true

  const script = document.createElement('script')
  script.src = src
  script.async = true
  // Cloudflare's Rocket Loader defers third-party scripts, which breaks tags that expect to run
  // immediately; the same attribute is on Adsterra's own snippets for this reason.
  script.setAttribute('data-cfasync', 'false')
  script.onerror = () => {
    // Non-fatal. An ad blocker, a dead zone, or an offline customer just means no ads this
    // session — taking the order must never depend on this script.
  }
  document.body.appendChild(script)
}
