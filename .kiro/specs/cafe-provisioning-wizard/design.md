# Design Document: Café Provisioning Wizard

## Research findings that shape this design (2026-07-30)

Before designing against it, I checked the Supabase Management API's actual documented behavior
rather than assuming a clean REST contract exists. Two findings changed the design:

1. **`POST /v1/projects/{ref}/database/migrations` is gated** — Supabase's own docs note "only select
   customers have access to the database migrations endpoint." A wizard built on this endpoint would
   silently fail to work for most Supabase accounts. **Avoided entirely** — see Schema Provisioning
   below.
2. **The Edge Function deploy endpoint's only documented example is single-file**:
   ```
   POST https://api.supabase.com/v1/projects/{ref}/functions/deploy?slug=my-func
   Authorization: Bearer sbp_TOKEN
   content-type: multipart/form-data
   --form 'metadata={ "entrypoint_path": "index.ts", "name": "My test" }'
   --form file=@file
   ```
   26 of this repo's 27 functions `import { handleCors } from "../_shared/cors.ts"` — a relative
   import to a second file. Whether the multipart body supports multiple file parts is **not
   documented** anywhere I found. Guessing here risks a Wizard that appears to work in testing on one
   function and silently mis-deploys the other 26. See Function Deployment below for how this is
   de-risked instead of assumed.

These are exactly the kind of unverified specifics Requirement R8 exists to catch — this document
treats them as open items to close with a live test account, not as solved.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│  Wizard (new, standalone deployable — NOT part of any café's        │
│  website/) — its own Cloudflare Pages project + Pages Functions     │
├─────────────────────────────────────────────────────────────────────┤
│  Frontend: a single onboarding page. Owner pastes:                  │
│    - Supabase project ref + Postgres connection string (has the     │
│      password) + Personal Access Token (for function deploy only)   │
│    - Cloudflare account id + zone id (optional) + API token         │
│  → held in page memory only, submitted directly to the backend      │
│    Functions below, never written to any RAZStudio-operated store.  │
├─────────────────────────────────────────────────────────────────────┤
│  Backend: Cloudflare Pages Functions (website/functions-style,      │
│  in the Wizard's OWN repo folder) — serverless, stateless, each      │
│  request is independent, nothing persisted between calls.           │
│    /api/provision/schema     → runs migrations (direct pg)          │
│    /api/provision/functions  → deploys Edge Functions (Mgmt API)     │
│    /api/provision/pages      → creates Pages project + uploads dist  │
│    /api/provision/dns        → creates the DNS record                │
├─────────────────────────────────────────────────────────────────────┤
│  Handoff: Wizard displays anon key + Supabase URL + website URL,     │
│  optionally as a QR code, for the EXISTING APK SetupScreen to        │
│  consume exactly as it does today. SetupScreen/AppConfigStore are    │
│  UNCHANGED by this spec.                                             │
└─────────────────────────────────────────────────────────────────────┘
```

Each backend step is an independent, retryable HTTP call from the frontend (Requirement R6.2) — the
UI is a checklist, not one "Provision everything" button, so a failure in step 3 doesn't force
re-running steps 1–2.

---

## Schema Provisioning (R2)

**Design: reuse the repo's own already-proven approach, orchestrated remotely.**
`supabase/apply-migration.mjs` already applies these exact migrations via a direct Postgres
connection string (`postgresql://postgres:<password>@<host>:5432/postgres` — every Supabase project
exposes this in Project Settings → Database, with no special access tier required, unlike the gated
Management API migrations endpoint).

The `/api/provision/schema` Pages Function does the same thing server-side: takes the owner's
connection string, connects with a `pg`-compatible client, and runs each `supabase/migrations/*.sql`
file in order (bundled into the Wizard's own deployment as it's built from this same monorepo).
Because these migrations are first-run-only (`create table` errors if the table exists — same
caveat `apply-migration.mjs` already documents), a failed run is reported per-file
(Requirement R2.3) rather than assumed complete.

**Open verification item:** confirm a Cloudflare Pages Function can hold a raw TCP connection to
Postgres port 5432 for the ~1–2 seconds a migration batch takes (Workers' networking model differs
from Node's; this needs confirming against a real project before trusting it in production per R8).

## Function Deployment (R3)

**Design: pre-inline `_shared` imports at Wizard build time, so every upload matches the one
confirmed-working single-file shape — no reliance on undocumented multi-file support.**

A small build step (run when the Wizard itself is built/deployed, not per-café) reads each
`supabase/functions/<name>/index.ts`, finds its `import ... from "../_shared/X.ts"` lines, and
inlines the referenced file's contents in place of the import — producing one self-contained
`.ts` file per function with no relative imports left. This is a textual transform, not a Deno
bundler invocation, so it doesn't add a new toolchain dependency.

`/api/provision/functions` then loops over the 27 pre-inlined files and calls the documented
`POST /v1/projects/{ref}/functions/deploy?slug=<name>` with `metadata={entrypoint_path: "index.ts",
name: "<name>"}` and the single inlined file — matching the ONE shape actually confirmed to work.
Per-function results are collected and shown individually (Requirement R3.2), and the same call is
safe to repeat (Requirement R3.3 — the docs confirm redeploying an existing slug updates it).

**Open verification item:** the inlining step must be tested against at least one real function with
a live deploy before trusting it for all 27 — a subtle bug in the inliner (e.g. two functions
importing the same shared symbol under different names) would silently ship broken functions.

## Cloudflare Pages + DNS (R4)

This is the best-documented, lowest-risk piece: Cloudflare's REST API (`api.cloudflare.com/client/v4`)
supports both creating a Pages project and a "direct upload" deployment (as opposed to a git
integration) with a standard API token, and a DNS record is a single well-documented `POST
/zones/{zone_id}/dns_records` call. `/api/provision/pages` builds `website/dist` (same
`VITE_SUPABASE_URL`/`VITE_SUPABASE_PUBLISHABLE_KEY`-parameterized build the existing GitHub Actions
workflow already does — see `.github/workflows/deploy-website.yml`) using that café's own values, then
uploads it. `/api/provision/dns` is optional per R4.2.

## Credential Handling (R5) — how "never persisted" is actually enforced

- The Wizard frontend keeps credentials in component state only; nothing is written to
  `localStorage`/`sessionStorage`/cookies.
- Cloudflare Pages Functions are stateless per-invocation — there is no database or file write
  anywhere in this design for a credential to land in. Request bodies are used to construct the
  outbound `fetch()` to Supabase/Cloudflare and then go out of scope when the function returns.
- No logging statement in any Function SHALL include a credential value (this is a code-review
  gate for implementation, not just a design note).
- `SetupScreen`/`AppConfigStore` in the APK are untouched — confirmed by re-reading their current
  source: `AppConfigStore.save()` takes only the fields it already takes (Supabase URL/anon key,
  website URL, café name, and the already-separately-stored Cloudflare/GitHub *reference* fields from
  the earlier Setup-screen work, which were always described as "stored for reference, not used by
  the app" — this spec does not change that).

## Testing Strategy

Per Requirement R8, this cannot be verified by reading documentation alone. Before any phase is
considered done:
1. Create one disposable Supabase project + one Cloudflare account/zone for testing.
2. Verify the direct-Postgres migration runner against that disposable project.
3. Verify the function-inliner + deploy against ONE real function first (e.g. `settings`, one of the
   simpler ones), confirm it actually invokes correctly post-deploy, before trusting the loop over
   all 27.
4. Verify Cloudflare Pages direct-upload + DNS against the same disposable account.
5. Only after 2–4 pass does the Wizard get pointed at a real café's accounts.

This is also why Requirement R8 blocks calling this spec "done" — none of steps 2–4 can happen inside
this environment, which has no live Supabase/Cloudflare credentials of its own.
