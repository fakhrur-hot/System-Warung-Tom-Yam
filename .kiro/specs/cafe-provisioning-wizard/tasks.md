# Implementation Plan: Café Provisioning Wizard

## Overview

Automates café onboarding (Supabase schema + functions, Cloudflare Pages + DNS) via a standalone Web
Setup Wizard, keeping high-privilege tokens out of the APK entirely (see `requirements.md`/`design.md`
for the security rationale). Scaffolding/logic can be written and unit-tested now; **end-to-end
verification against real accounts is blocked** (Requirement R8) until a disposable Supabase project
+ Cloudflare account/zone are available — this environment has none of its own.

## Tasks

### Phase 0 — Scoping (this session)

- [x] 0.1 Research the actual Supabase Management API contracts rather than assuming a clean REST
  surface — found `/database/migrations` is access-gated and the function-deploy endpoint's only
  documented example is single-file. Both are designed around in `design.md`.
- [x] 0.2 Write `requirements.md` (EARS) and `design.md`, including the credential-custody decision
  (Web Wizard, not in-app) the owner explicitly chose.

### Phase 1 — Wizard scaffold + Cloudflare Pages/DNS (lowest risk, best-documented)

- [ ] 1.1 Create the Wizard as its own deployable: a new top-level folder (e.g. `provisioning/`),
  its own `package.json`/Vite (or plain static) frontend, its own `wrangler.toml`, its own Cloudflare
  Pages project — separate from `website/` per Requirement R1.1.
- [ ] 1.2 Build the one-page frontend: masked-input fields for Supabase connection string + PAT,
  Cloudflare account id/zone id/API token, and a per-step checklist UI (not one "Provision" button)
  per Requirement R6.2.
- [ ] 1.3 `/api/provision/pages`: build `website/dist` parameterized by the target café's
  `VITE_SUPABASE_URL`/`VITE_SUPABASE_PUBLISHABLE_KEY` (mirrors `.github/workflows/deploy-website.yml`),
  create the Cloudflare Pages project via direct upload.
- [ ] 1.4 `/api/provision/dns`: optional custom-domain DNS record via the Cloudflare API.
- [ ] 1.5 Confirm no credential is written to any log/response body beyond the single request that
  needs it (Requirement R5.1/R5.2 — a code-review checklist item, not just a design intent).

### Phase 2 — Schema provisioning

- [ ] 2.1 `/api/provision/schema`: direct Postgres connection (same approach as
  `supabase/apply-migration.mjs`), running `supabase/migrations/*.sql` in order, one result per file.
- [ ] 2.2 **Verification gate (blocked on a real test project):** confirm a Cloudflare Pages Function
  can hold a raw Postgres TCP connection long enough to run the migration batch. If it can't (Workers'
  networking model is a real open question here), fall back to the Function only proxying through to
  a small persistent-runtime service, or to the Wizard displaying a ready-to-run `apply-migration.mjs`
  command as a manual step for this phase only.

### Phase 3 — Edge Function deployment (highest uncertainty)

- [ ] 3.1 Write the `_shared`-import inliner: reads each `supabase/functions/<name>/index.ts`,
  resolves its `../_shared/*.ts` imports, and emits one self-contained file per function.
- [ ] 3.2 **Verification gate (blocked on a real test project):** deploy ONE inlined function live,
  confirm it actually runs correctly post-deploy, before trusting the inliner across all 27 — per
  `design.md`'s note that a subtle inlining bug (e.g. a name collision between two functions' shared
  imports) would ship silently broken functions.
- [ ] 3.3 `/api/provision/functions`: loop the inliner's output through
  `POST /v1/projects/{ref}/functions/deploy?slug=<name>`, one result per function
  (Requirement R3.2), safe to re-run (R3.3).

### Phase 4 — Handoff

- [ ] 4.1 On success, display the target project's Supabase URL + anon key + website URL.
- [ ] 4.2 Optionally render these as a QR code the APK's existing scanner-adjacent flows could read —
  enhancement only; `AppConfigStore`/`SetupScreen` storage itself does not change (Requirement R5.3).

## Notes

- **Do not build Phase 3 before Phase 2's verification gate clears** — if the direct-Postgres
  approach turns out not to work from a Cloudflare Worker, that changes where `/api/provision/schema`
  actually runs (e.g., a tiny separate Node-based runner instead), and Phase 3's Functions deployment
  is independent of that decision either way, so sequencing schema first surfaces the bigger unknown
  earlier.
- **Nothing in this spec touches `apk/`.** The APK's `SetupScreen`/`AppConfigStore` remain exactly as
  shipped; this is intentionally true throughout, not just at handoff (Requirement R5.3).
- GitHub provisioning is explicitly NOT in scope — confirmed earlier this session there is no
  GitHub-side target to provision; those Setup-screen fields remain reference-only.

## Task Dependency Graph

```
0 (done) ─► 1 (Wizard scaffold + Cloudflare) ─┐
                                               ├─► 4 (Handoff)
            2 (schema) ─► 2.2 gate ───────────┤
                                               │
            3.1 (inliner) ─► 3.2 gate ─► 3.3 ──┘
```
1 and 2/3 can be built in parallel; 4 needs all of them to have SOMETHING to hand off, but each
provisioning step is independently checkable in the UI per R6.2 even before every step is wired.
