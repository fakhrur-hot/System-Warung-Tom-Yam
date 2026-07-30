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

### Phase 1 — Wizard scaffold + Cloudflare Pages/DNS (lowest risk, best-documented) — DONE

- [x] 1.1 Created the Wizard as its own deployable: `provisioning/` (Vite + React + TS, matching
  `website/`'s conventions), its own `wrangler.toml` (project name `cafe-setup-wizard`) — separate
  from `website/` per Requirement R1.1.
- [x] 1.2 Built the one-page frontend (`src/App.tsx`): masked-input fields for every field in the
  agreed layout (Supabase ref/anon key/connection string/PAT, Cloudflare account id/token/zone id,
  café slug/name) plus a per-step checklist (not one "Provision" button) per Requirement R6.2, each
  step showing its own `StepResult[]`.
- [x] 1.3 **Revised from "direct upload" to git-integration** (see design.md's git-integration
  pivot). `functions/api/provision/pages.ts` calls the confirmed
  `POST /accounts/{account_id}/pages/projects` with a GitHub `source` pointing at a RAZStudio-owned
  repo + per-project `deployment_configs.env_vars` for that café's Supabase URL/anon key —
  Cloudflare's own build servers run `npm run build`, so this endpoint never touches a build
  artifact itself (no `website/dist` build step needed here after all).
- [x] 1.4 `functions/api/provision/dns.ts`: optional custom-domain DNS record via the Cloudflare API.
- [x] 1.5 Reviewed: every `/api/provision/*` Function only ever uses a credential inside the one
  `fetch()`/`pg` call that needs it; nothing is logged or written anywhere (Requirement R5.1/R5.2).
  The frontend's `WizardState` lives in `useState` only — no `localStorage`/`sessionStorage` call
  exists anywhere in `src/`.
- [x] 1.6 (not originally planned, done because it was cheap alongside 1.1–1.5): full local build
  verification — `npm install && npm run build` succeeds (tsc + Vite) against the real repo.

### Phase 2 — Schema provisioning — code written, verification gate still blocked

- [x] 2.1 `functions/api/provision/schema.ts`: direct Postgres connection via the official `pg`
  package (Cloudflare's own tutorial pattern — `Client({ connectionString })`, confirmed via their
  docs, not guessed), running the bundled `supabase/migrations/*.sql` in order, one `StepResult` per
  file. `wrangler.toml` updated with the required `compatibility_flags = ["nodejs_compat"]`.
- [ ] 2.2 **Verification gate (blocked on a real test project) — NOT YET DONE.** The code compiles
  and follows Cloudflare's official example, but has not been run against a real deployed Function.
  Confirm the TCP connection actually holds long enough to run the migration batch before trusting
  this. `src/App.tsx`'s `STEPS[0].verified = false` and the UI shows an "Unverified" badge until this
  passes — see `provisioning/README.md`'s verification section for the exact steps to run.

### Phase 3 — Edge Function deployment (highest uncertainty) — code written, verification gate still blocked

- [x] 3.1 Wrote the `_shared`-import inliner (`scripts/inline-functions.mjs`): resolves each
  function's transitive `_shared` dependency closure (not just direct imports — `_shared` files
  import from EACH OTHER, e.g. `auth.ts` → `supabase.ts`), inlines in dependency order, and
  self-checks for duplicate top-level declarations before writing output.
  **Ran it against the real repo and it caught a genuine bug**: `auth.ts` and `supabase.ts` both
  import `createClient` from the same esm.sh URL, which the first pass left duplicated in the merged
  file (a Deno `SyntaxError` waiting to happen) — fixed with an explicit external-import
  deduplication pass, re-verified the fix by inspecting the regenerated output. All 26 real
  functions (of 27 — `tests/` holds Deno test files, not a deployable function, correctly skipped)
  inline cleanly with no duplicate-declaration errors.
- [ ] 3.2 **Verification gate (blocked on a real test project) — NOT YET DONE.** The inliner is
  verified against every real function's SOURCE (see 3.1), but no inlined function has been
  live-deployed and invoked yet. Deploy ONE first (see `provisioning/README.md`), confirm it responds
  correctly, before trusting the loop across all 26.
- [x] 3.3 `functions/api/provision/functions.ts`: loops `EDGE_FUNCTIONS` through
  `POST /v1/projects/{ref}/functions/deploy?slug=<name>` (confirmed shape — multipart `metadata` +
  single `file` part, no `Content-Type` override so `fetch` sets its own boundary), one `StepResult`
  per function (Requirement R3.2), safe to re-run (R3.3 — redeploying a slug updates it).

### Phase 4 — Handoff

- [x] 4.1 `src/App.tsx`'s handoff section displays the Supabase URL (derived from project ref) +
  anon key + website URL + café name once the Pages step succeeds.
- [ ] 4.2 QR code rendering of the handoff payload — NOT built; still an optional enhancement, not
  required for the wizard to be usable. `AppConfigStore`/`SetupScreen` storage itself is unchanged
  either way (Requirement R5.3).

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

Prose: 1 and 2.1/3.1 could be built in parallel (and were — the Wizard scaffold, the schema-runner
code, and the inliner were all written and locally verified in the same pass); 2.2/3.2 are the real
gates, both blocked on the same external prerequisite (a disposable Supabase project); 3.3 only
needed 3.1 to exist, not 3.2 to pass, so it was written ahead of its verification gate per explicit
direction; 4 needed 1.3 (Pages creation) to have something to hand off.

```json
{
  "waves": [
    { "wave": 1, "name": "Scoping", "tasks": ["0.1", "0.2"], "status": "done" },
    {
      "wave": 2,
      "name": "Scaffold + low-risk endpoints + unverified endpoint code",
      "tasks": ["1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "2.1", "3.1", "3.3"],
      "status": "done"
    },
    {
      "wave": 3,
      "name": "Live verification gates (external prerequisite: disposable Supabase project + Cloudflare account/zone)",
      "tasks": ["2.2", "3.2"],
      "status": "blocked"
    },
    { "wave": 4, "name": "Handoff", "tasks": ["4.1"], "status": "done" },
    { "wave": 5, "name": "Handoff enhancement (optional)", "tasks": ["4.2"], "status": "not_started" }
  ]
}
```
