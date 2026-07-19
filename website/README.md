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

## i18n

English (`src/locales/en.json`) is the authored base. `bm` / `zh` / `ta` are
dictionary-translated over it with English fallback (specs REQ-9). The current files hold
static UI strings only; menu-content dictionary translation is designed in Phase 8 (Task 24).

## Status

Phase 1 skeleton: language selector + "coming soon" placeholder (backend not yet wired).
The session-aware ordering flow lands in Phase 4 (Task 11).
