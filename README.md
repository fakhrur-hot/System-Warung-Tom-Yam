# RAZ POS — Café / Stall Point-of-Sale Template

> A small, functional, **zero-commitment** café/stall POS. Bare-minimum hardware —
> at least **two Android phones** and **one Bluetooth thermal printer** — running entirely
> on **free-tier** web services. No monthly fees, no yearly commitments, no paid domain.

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3ddc84)
![Backend](https://img.shields.io/badge/backend-Supabase%20(free)-3ecf8e)
![Hosting](https://img.shields.io/badge/web-Cloudflare%20Pages%20(free)-f38020)
![Cost](https://img.shields.io/badge/ongoing%20cost-RM%200-brightgreen)
![Status](https://img.shields.io/badge/status-in%20production%20use-success)

---

## This is the template — Bring-Your-Own-Infrastructure

This `main` branch is the **generic, café-agnostic product**: neutral branding, no baked-in
café identity, and an in-app **Setup screen** (three-dots menu on the login page) where an
operator points a build at their own backend at runtime.

Each real café is a separate **deployment**, built from this same source with its own
identity — package name, branding, and its own Supabase + Cloudflare accounts. This repo
carries one such deployment as a sibling `git worktree`: **`tani-tom-yam/`** is the
production build for the Tani Tom Yam café, checked out on the `tani-tom-yam` branch,
sharing this repo's full history so fixes merge cleanly in both directions. See that
folder's own `README.md` for its specifics.

**Two ways to stand up a new café:**
1. **Guided (BYOI, no scripting)** — the standalone [Setup Wizard](provisioning/) walks a new
   owner through provisioning their own Supabase project + Cloudflare Pages site (schema,
   Edge Functions, hosting, optional custom domain), then hands the resulting low-privilege
   values to the tablet's Setup screen. High-privilege tokens never touch the APK — see
   `provisioning/README.md` and `.kiro/specs/cafe-provisioning-wizard/`.
2. **Manual** — apply `supabase/migrations/*.sql`, deploy `supabase/functions/*`, build and
   deploy `website/` to Cloudflare Pages yourself, then fill in the Setup screen. See
   [`DEPLOYMENT.md`](DEPLOYMENT.md).

---

## What is it?

A QR-based table ordering and point-of-sale system for a small warung / hawker stall / café.
Customers scan a QR on their table, order from their own phone, and re-scan to track status.
The owner runs the whole POS from a single Android phone; staff take orders from additional
phones. It prints kitchen slips and receipts to Bluetooth thermal printers and emails
end-of-day reports — all without a PC, a server, or a paid subscription.

**Design goals:** zero ongoing cost, minimal on-site technical skill, and works on cheap
Android hardware. Scale target: ≤ 30 tables, ≤ 10 devices.

## What you need (the bare minimum)

| Item | Minimum | Notes |
|---|---|---|
| 📱 Android phones | **2** (Android 8.0+) | One is the **Main Admin** (owner, printer host); one or more are **Ordering staff**. A **Secondary Admin** device can manage everything but has no local printer. Customers use **their own** phones — no app install, just a browser + camera. |
| 🖨️ Bluetooth thermal printer | **1** (58 mm or 80 mm) | Connects only to the Main Admin device — every other role runs zero Bluetooth activity. |
| ☁️ Free web services | — | Supabase + Cloudflare Pages + Brevo, all free tier, all commercial-use-allowed. |
| 🖨️ A print shop (once) | — | To print the QR table cards from the generated PDF. |

That's it. No cash register, no PC, no POS terminal.

## How it works

Three components, talking over HTTPS/WebSocket:

```
        ┌──────────────────────── CLOUDFLARE PAGES (free) ───────────────────────┐
        │   React customer ordering site  +  Superadmin dashboard                 │
        │                    │  Supabase Edge Functions + Realtime + Postgres      │
        └────────────────────┼───────────────────────────────────────────────────┘
                             │ HTTPS / WSS
        ┌────────────────────┼───────────────────────────────┐
        │                    │                                │
  ┌─────▼──────┐      ┌──────▼───────┐                 ┌──────▼───────┐
  │  Admin APK │      │ Ordering APK │                 │  Customer    │
  │  (POS,     │      │ (staff order │                 │  phone       │
  │  SQLite =  │      │  entry, GPS  │                 │  (browser,   │
  │  source of │      │  attendance) │                 │  QR scan)    │
  │  truth)    │      └──────────────┘                 └──────────────┘
  │  + Bluetooth thermal printer(s)
  └────────────┘
```

1. **Customer** scans the table QR → opens the ordering page → picks a language → orders.
   Re-scanning the same QR shows their live order status; other phones see "table occupied".
2. **Main Admin phone** is the POS: a **Table View** grid where the owner sends orders to
   the kitchen, updates status, cancels, and takes payment (cash or QR). All business data
   lives in **local SQLite** on this phone — the cloud only holds active orders and routing
   state. It's the only device that touches Bluetooth.
3. **Staff / Secondary Admin phones** join by invitation (QR scan), check in by **GPS** at
   the stall (staff only), and take orders during the shift.

A single APK contains **every** role — the device claims Main Admin, Secondary Admin, or
Ordering Staff during connect, based on which credential it scans/enters.

## Features

- **QR table ordering** — 5 customer languages (English default, Malay, 中文, Tamil, ไทย), rescan-for-status, table-occupied guard, cancel-before-kitchen.
- **Table View POS** — Free/Occupied grid, Send to Kitchen, status, Cancel, **Payment (Cash / QR)**; staff permissions gated by admin RBAC.
- **Role-based access** — Main Admin (full management + sole printer host), Secondary Admin (full management, no local printer — prints route to Main Admin), Ordering Staff (order entry + GPS attendance).
- **Bluetooth thermal printing** — coroutine-safe connection pooling, connect-by-MAC (no scanning every paired device), multiple printers, role-based dispatch (Receipt / Kitchen / Both), 58 mm + 80 mm, multilingual bitmap fallback for CJK/Tamil/Thai, **delta kitchen slips** for items added after send.
- **Ambient display mode** — keeps the terminal screen on and, once idle, shows a dimmed live table board instead of sleeping; configurable idle delay and a guest-safe mode that hides order values.
- **Configurable order-alert sound** — pick the notification tone (Android's own picker) and volume for new-order alerts.
- **Demo Mode** — a fully offline walkthrough on the real admin/staff screens against a local seeded dataset, for trying the product with zero backend.
- **GPS attendance** — staff check-in/out within a configurable radius of the GPS-locked café location; admin force-checkout override.
- **Daily availability popup** — market items ("Ask me daily", e.g. ikan kembung) prompt the owner at first sign-in each day.
- **Reports** — emailed closing report on "Sign Out with Closing", monthly report, on-device daily/weekly (revenue, cash-vs-QR split, cancels, top-N per category).
- **QR card PDF** — A6 portrait cards, 4-up on A4, hairline cut guides, share to a print shop.
- **Zero-cost i18n** — English is the authored source; other languages via bundled **dictionary translation** with English fallback (BM, 中文, Tamil, ไทย for customer site; 5 languages for operational output).
- **Local-first** — SQLite on the admin phone is the source of truth; full JSON backup export/import.
- **Runtime Setup screen** — one template APK, any café's backend: point it at your own Supabase/Cloudflare/GitHub via an in-app config screen, no rebuild needed.

## Free-tier stack (verified 2026)

| Service | Role | Why this one |
|---|---|---|
| **Supabase** | Postgres, Edge Functions, Realtime, Auth, Storage | Generous free tier; fits ≤ 30 tables / ≤ 10 devices. Kept awake by a daily GitHub Actions ping (7-day pause otherwise). |
| **Cloudflare Pages** | Website hosting | Free tier **allows commercial use** (Vercel Hobby does not); unlimited bandwidth; stable `*.pages.dev` URL for the printed QR cards. |
| **Brevo** | Report emails | Verifies a plain **sender email** (no paid domain needed, unlike Resend); 300 emails/day. |
| **GitHub** | Repo, CI, APK releases, keep-alive cron | Actions cron keeps Supabase awake; Releases host the signed APK. Also backs the [Setup Wizard's](provisioning/) git-integration Cloudflare Pages deploys — a café owner never needs their own GitHub account. |

> ⚠️ **Never rename the Cloudflare Pages project** once QR cards are printed — the
> `*.pages.dev` URL is baked into the physical cards. This is the deliberate trade-off for
> not buying a domain.

## Repository layout

```
/
├── website/       React + Vite + Tailwind SPA (customer page + superadmin dashboard)
├── apk/           Android — Kotlin, Jetpack Compose, Hilt, Room (single multi-role binary)
├── provisioning/  Standalone BYOI Setup Wizard — provisions a new café's own Supabase +
│                  Cloudflare accounts (see provisioning/README.md)
├── supabase/      Postgres schema migrations + Edge Functions + provisioning notes
├── shared/        API contract (endpoints, Realtime channels, enums)
├── spikes/        Phase 0 de-risk harnesses (Bluetooth printing, background/latency)
├── docs/          Audit reports and operational notes
├── specs/         Original product spec (requirements.md · designs.md · tasks.md)
├── .kiro/specs/   Working specs for individual features/fixes (EARS requirements, design,
│                  phased tasks) — the current source of truth for in-flight work
├── tani-tom-yam/  Git worktree: the Tani café's own deployment (separate branch, shared history)
└── .github/       CI (website + APK) and signed-release workflows
```

## Package / application identity

The Kotlin source package is the constant vendor base `com.razstudio.pos` on **every**
branch — this is what keeps `main` and each café's branch mergeable without file-move or
import conflicts. The Android **applicationId** (what actually identifies the installed app,
and what its signing key binds to) is independently configurable per build via
`local.properties`'s `APPLICATION_ID` — the template defaults to `com.razstudio.pos`, while
a café build sets its own (e.g. `com.warungtomyam.pos` for Tani) to keep its installed
identity and update path.

## Project status

**In production use** on at least one deployment (Tani Tom Yam). Actively maintained:

- ✅ Full order lifecycle (place → kitchen → payment), Bluetooth printing, GPS attendance
- ✅ RBAC (Main Admin / Secondary Admin / Ordering Staff), Demo Mode, ambient display mode
- ✅ Template/café branch split with a shared, mergeable source tree
- ✅ CI + signed-release GitHub Actions
- ⏳ [Café Provisioning Wizard](provisioning/) — Phase 1 (scaffold, Cloudflare Pages/DNS) is
  built and locally verified; the Postgres-schema and Edge-Function-deploy endpoints are
  code-complete but await a live test account before being trusted for a real café — see
  `.kiro/specs/cafe-provisioning-wizard/tasks.md` for the exact checkpoint.

## Getting started (build the APK)

Pinned, stable toolchain versions:

| Tool | Version |
|---|---|
| JDK (runs Gradle) | 17 (Temurin) |
| Gradle (wrapper) | 8.9 |
| Android Gradle Plugin | 8.7.x |
| Kotlin | 2.0.x |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |

```bash
# APK
cd apk
# one-time: create local.properties pointing at your Android SDK
#   sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
# one-time (only if building a signed release): create keystore.properties (git-ignored)
./gradlew assembleDebug      # debug build — unconfigured template; use the in-app Setup
                             # screen, or set SUPABASE_URL/SUPABASE_ANON_KEY/WEBSITE_URL
                             # in local.properties for a pre-configured build
./gradlew assembleRelease    # signed release (needs keystore.properties)

# Website
cd website
npm install
npm run dev                  # local dev
npm run build                # production build → deploy to Cloudflare Pages

# BYOI Setup Wizard (optional — for provisioning a NEW café's backend)
cd provisioning
npm install
npm run build                # regenerates bundled migrations/functions, then builds
```

## Documentation

- 📋 [Original requirements](requirements.md) / [design](designs.md) / [tasks](tasks.md) —
  the initial product spec (historical; most recent feature work lives in `.kiro/specs/`).
- 🧭 [Deployment guide](DEPLOYMENT.md) — manually standing up a new café's accounts.
- 🧙 [Setup Wizard](provisioning/README.md) — the guided BYOI provisioning path.
- 🗂️ `.kiro/specs/` — per-feature specs (requirements/design/tasks) for everything built
  after the initial MVP: printing reliability, RBAC, demo mode, ambient display, the
  provisioning wizard, and more.

## License

To be decided before public release.

---

*RAZ POS — order at the table, run the stall from your pocket, pay nothing to keep it online.*
