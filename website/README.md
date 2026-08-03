# Website — Warung Tom Yam

React + Vite + TypeScript + Tailwind. Hosts the customer ordering page and (Phase 4) the
superadmin dashboard. Deploys to **Cloudflare Pages** (free tier, commercial use allowed).

## Develop

```bash
npm install
npm run dev        # http://localhost:5173  (try /?table=T1)
npm run build      # type-check + production build → dist/
npm run preview    # serve the built dist/
```

## Deploy (Cloudflare Pages)

- Framework preset: **Vite**
- Build command: `npm run build`
- Build output directory: `dist`
- `public/_redirects` provides SPA fallback (`/* /index.html 200`) so deep links like
  `/order?table=T3` work.

> **Do not rename the Pages project** once QR cards are printed — the `*.pages.dev` URL is
> baked into the physical cards.

### Environment variables — set them in Pages, and redeploy

Vite **inlines `VITE_*` at build time**, so these must exist in the Cloudflare Pages project's
environment variables *before* the build runs. Setting one after a deploy changes nothing until
the next build — the value is compiled into the JS bundle, not read at runtime. `.env.local` is
git-ignored and only affects local dev; it has no bearing on production.

**Two ad networks are wired, and they are mutually exclusive at runtime.** `AdSlot.tsx` is the
single decision point: if any in-flow Adsterra unit is configured it takes every ad slot and
AdSense is skipped, including its page-level auto-ads. Unset the Adsterra vars and AdSense returns.
They are kept apart on purpose — AdSense holds publishers responsible for what appears alongside
its units, so a strike there costs more than the extra impression.

Adsterra (`VITE_ADSTERRA_*`, see `.env.example`): set **Native Banner** (`_NATIVE_SRC` +
`_NATIVE_CONTAINER`, both required) for a unit that flows at page width, or `_BANNER_KEY` for a
fixed-size unit. `_SOCIAL_BAR_SRC` is separate and opt-in, since popunder/social formats can open
over a customer mid-order.

The AdSense vars, which apply only when no Adsterra unit is set:

| Variable | Effect when unset |
|---|---|
| `VITE_ADSENSE_CLIENT_ID` | `loadAdSense()` no-ops and **every** ad component renders `null` — no auto ads, no in-feed, no display unit. |
| `VITE_ADSENSE_STATUS_SLOT` | `DisplayAd` on the order-status view renders `null`. No empty `<ins>` — no markup at all. Create the unit under AdSense → Ads → By ad unit → **Display ads → Responsive**, then paste the digits-only slot ID here. |

Two things that are easy to misdiagnose as "ads are broken":

- **In-feed ads are per category.** `MenuView` renders one every `IN_FEED_AD_INTERVAL` items of
  the *currently visible* list, plus one at the end of any category shorter than that interval
  (down to `IN_FEED_AD_MIN_ITEMS`). A 2-item category shows none by design.
- **`*.pages.dev` is eligible for AdSense.** It is on the
  [Public Suffix List](https://publicsuffix.org/list/), and AdSense explicitly permits
  "subdomains on platforms that are already part of the public suffix list". A custom domain is
  nice for branding but is *not* required for approval. Newly-added sites still serve blank until
  approved, which looks identical to a misconfiguration.

## i18n

English (`src/locales/en.json`) is the authored base. `bm` / `zh` / `ta` are
dictionary-translated over it with English fallback (specs REQ-9). The current files hold
static UI strings only; menu-content dictionary translation is designed in Phase 8 (Task 24).

## Status

Phase 1 skeleton: language selector + "coming soon" placeholder (backend not yet wired).
The session-aware ordering flow lands in Phase 4 (Task 11).
