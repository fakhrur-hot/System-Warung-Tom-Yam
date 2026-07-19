# 🍜 System Warung Tom Yam

> A small, functional, **zero-commitment** café/stall POS. Bare-minimum hardware —
> at least **two Android phones** and **one Bluetooth thermal printer** — running entirely
> on **free-tier** web services. No monthly fees, no yearly commitments, no paid domain.

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3ddc84)
![Backend](https://img.shields.io/badge/backend-Supabase%20(free)-3ecf8e)
![Hosting](https://img.shields.io/badge/web-Cloudflare%20Pages%20(free)-f38020)
![Cost](https://img.shields.io/badge/ongoing%20cost-RM%200-brightgreen)
![Status](https://img.shields.io/badge/status-spec%20%2F%20pre--build-blue)

---

## What is it?

A QR-based table ordering and point-of-sale system for a small warung / hawker stall / café
(built with a Malaysian tom yam stall in mind, but generic). Customers scan a QR on their
table, order from their own phone, and re-scan to track status. The owner runs the whole POS
from a single Android phone; staff take orders from additional phones. It prints kitchen
slips and receipts to Bluetooth thermal printers and emails end-of-day reports — all without
a PC, a server, or a paid subscription.

**Design goals:** zero ongoing cost, minimal on-site technical skill, and works on cheap
Android hardware. Scale target: ≤ 20 tables, ≤ 10 devices.

## What you need (the bare minimum)

| Item | Minimum | Notes |
|---|---|---|
| 📱 Android phones | **2** (Android 8.0+) | One is the **Admin POS** (owner); one or more are **Ordering staff**. Customers use **their own** phones — no app install, just a browser + camera. |
| 🖨️ Bluetooth thermal printer | **1** (58 mm or 80 mm) | One printer set to "Both" covers receipts + kitchen. Add a second later (e.g. 58 mm kitchen + 80 mm counter). |
| ☁️ Free web services | — | Supabase + Cloudflare Pages + Brevo + GitHub. All free tier, all commercial-use-allowed. |
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
2. **Admin phone** is the POS: a **Table View** grid where the owner sends orders to the
   kitchen, updates status, cancels, and takes payment (cash or QR). All business data lives
   in **local SQLite** on this phone — the cloud only holds active orders and routing state.
3. **Staff phones** join by invitation, check in by **GPS** at the stall, and take orders
   during the shift.

A single APK contains **both** roles — the first device to claim admin (via a rotating key
on the website) becomes the POS; the rest join as ordering staff.

## Features

- **QR table ordering** — 4 customer languages (English default, Malay, 中文, Tamil), rescan-for-status, table-occupied guard, cancel-before-kitchen.
- **Table View POS** — Free/Occupied grid, Send to Kitchen, status, Cancel, **Payment (Cash / QR)**; staff permissions gated by admin RBAC.
- **Bluetooth thermal printing** — multiple printers, role-based dispatch (Receipt / Kitchen / Both) with fallback, 58 mm + 80 mm, **delta kitchen slips** for items added after send.
- **GPS attendance** — staff check-in/out within a configurable radius of the GPS-locked café location; admin force-checkout override.
- **Daily availability popup** — market items ("Ask me daily", e.g. ikan kembung) prompt the owner at first sign-in each day.
- **Reports** — emailed closing report on "Sign Out with Closing", monthly report, on-device daily/weekly (revenue, cash-vs-QR split, cancels, top-N per category).
- **QR card PDF** — A6 portrait cards, 4-up on A4, hairline cut guides, share to a print shop.
- **Zero-cost i18n** — English is the authored source; other languages via bundled **dictionary translation** with English fallback.
- **Local-first** — SQLite on the admin phone is the source of truth; full JSON backup export/import.

## Free-tier stack (verified July 2026)

| Service | Role | Why this one |
|---|---|---|
| **Supabase** | Postgres, Edge Functions, Realtime, Auth, Storage | Generous free tier; fits ≤ 20 tables / ≤ 10 devices. Kept awake by a daily GitHub Actions ping (7-day pause otherwise). |
| **Cloudflare Pages** | Website hosting | Free tier **allows commercial use** (Vercel Hobby does not); unlimited bandwidth; stable `*.pages.dev` URL for the printed QR cards. |
| **Brevo** | Report emails | Verifies a plain **sender email** (no paid domain needed, unlike Resend); 300 emails/day. |
| **GitHub** | Repo, CI, APK releases, keep-alive cron | Actions cron keeps Supabase awake; Releases host the signed APK. |

> ⚠️ **Never rename the Cloudflare Pages project** once QR cards are printed — the
> `*.pages.dev` URL is baked into the physical cards. This is the deliberate trade-off for
> not buying a domain.

## Repository layout (planned monorepo)

```
/
├── website/     React + Vite + Tailwind (customer page + superadmin dashboard)
├── apk/         Android — Kotlin, Jetpack Compose, Hilt, Room (single dual-role binary)
├── shared/      API contract (endpoints, Realtime channels, enums)
└── specs/       requirements.md · designs.md · tasks.md · this README
```

*(This README currently lives in `specs/` alongside the design docs; it moves to the
monorepo root when the project is scaffolded — see Implementation Plan, Task 4.)*

## Getting started (build the APK)

The Android toolchain is already proven on the target machine. Pinned, stable versions:

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
# one-time: create keystore.properties (git-ignored) for release signing
./gradlew assembleDebug      # debug build
./gradlew assembleRelease    # signed release (needs keystore.properties)

# Website
cd website
npm install
npm run dev                  # local dev
npm run build                # production build → deploy to Cloudflare Pages
```

Full environment notes (SDK components, signing keystore, JDK gotchas) are in the
Implementation Plan under **Build Environment — APK toolchain** (Task 4).

## Roadmap

Phased build (see [tasks.md](tasks.md) for the full 30-task plan):

- **Phase 0** — Contract + de-risk spikes (Bluetooth printing on real hardware, background survival).
- **Phase 1–2** — Repo/CI/hosting scaffold, Supabase schema, backend APIs.
- **Phase 3** — Walking skeleton + < 3s latency gate.
- **Phase 4–5** — Website (customer + dashboard) and Admin APK POS core.
- **Phase 6–7** — Ordering-role APK (GPS/attendance), printing, QR PDF.
- **Phase 8–9** — Language (English base + dictionary translation), reports, backup.
- **Phase 10** — Hardening, keep-alive ops, and a full-day field rehearsal (acceptance).

**MVP cut line:** the money path (order → kitchen → payment) plus QR cards and keep-alive.

## Two open design items

Tracked as **REQ-12** — a design spike is needed before implementing each:

- **Gap A — Dictionary-translation i18n**: English base → Malay / 中文 / Tamil by bundled
  dictionary lookup (offline, zero paid API), with English fallback and admin override.
- **Gap B — Encrypted token storage**: `EncryptedSharedPreferences` / Keystore for the admin
  session token and ordering API keys.

## Documentation

- 📋 [Requirements](requirements.md) — what the system must do (REQ-1 … REQ-12).
- 🏗️ [Design](designs.md) — architecture, data model, data flows, decisions.
- ✅ [Implementation Plan](tasks.md) — 30 tasks across 10 phases, dependency waves, build environment.

## License

To be decided before public release.

---

*System Warung Tom Yam — order at the table, run the stall from your pocket, pay nothing to keep it online.*
