# Design Document: Café Provisioning Wizard

## Overview

This is a **Bring-Your-Own-Infrastructure (BYOI)** provisioning tool: a café owner buys the POS app,
then a standalone Web Setup Wizard walks them through initializing their *own* Supabase and
Cloudflare accounts (free or paid) to host their data and ordering site. The Wizard collects the
owner's high-privilege tokens once in a browser, uses them immediately against official APIs/direct
Postgres, and hands the tablet only the low-privilege values it already stores today. No browser
automation, scraping, or headless-browser driving is used anywhere — every step is a plain REST call
or a direct database connection, both because scraping breaks the moment a vendor's dashboard UI
changes and because app-store review treats automated web-driving code as a red flag.

Two of this design's three integrations do **not** work the way a first read of "there's an API for
that" would suggest, and both were changed as a result of checking their actual documented behavior
rather than assuming a clean contract exists:

1. **Supabase schema provisioning** cannot use the obvious `POST /v1/projects/{ref}/database/migrations`
   endpoint — it is access-gated to "select customers" per Supabase's own docs. The design instead
   reuses this repo's own already-proven direct-Postgres approach (`supabase/apply-migration.mjs`),
   which needs no special account tier.
2. **Cloudflare site deployment** cannot safely use "Direct Upload" as first imagined — its only
   concrete protocol comes from a third party's reverse-engineering of Wrangler's network traffic,
   not from Cloudflare's own REST docs. The owner chose Cloudflare's officially-documented
   **git-integration** instead, backed by a repo RAZStudio owns (the café owner never touches GitHub).

The third integration, Supabase Edge Function deployment, IS on a documented endpoint, but that
endpoint's only worked example is single-file, while 26 of this repo's 27 functions import a second
shared file — handled via a build-time import-inliner (see Components and Interfaces).

## Architecture

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
│  Backend: Cloudflare Pages Functions — serverless, stateless, each   │
│  request independent, nothing persisted between calls.              │
│    /api/provision/schema     → runs migrations (direct pg)          │
│    /api/provision/functions  → deploys Edge Functions (Mgmt API)     │
│    /api/provision/pages      → creates a Pages project via git       │
│                                 integration (RAZStudio-owned repo)   │
│    /api/provision/dns        → creates the DNS record (optional)    │
├─────────────────────────────────────────────────────────────────────┤
│  Handoff: Wizard displays anon key + Supabase URL + website URL,     │
│  optionally as a QR code, for the EXISTING APK SetupScreen to        │
│  consume exactly as it does today. SetupScreen/AppConfigStore are    │
│  UNCHANGED by this spec.                                             │
└─────────────────────────────────────────────────────────────────────┘
```

Each backend step is an independent, retryable HTTP call from the frontend — the UI is a checklist,
not one "Provision everything" button, so a failure in step 3 doesn't force re-running steps 1–2 (see
Correctness Properties).

## Components and Interfaces

### Wizard frontend (single onboarding page)
A masked-input form (Supabase connection string + PAT; Cloudflare account id / zone id / API token)
plus a per-step checklist UI. Each checklist row calls exactly one backend endpoint and shows that
step's own success/failure independently — this is the interface contract that makes Requirement
6.2 (independently retryable steps) concrete rather than aspirational.

### `/api/provision/schema` — direct-Postgres migration runner
**Input:** `{ connectionString: string }`. **Behavior:** connects with a `pg`-compatible client,
runs each `supabase/migrations/*.sql` file (bundled into the Wizard's own build, since it's built from
this same monorepo) in filename order. **Output:** `{ results: [{ file, status: "ok"|"error", error? }] }`
— one entry per migration file, never a single pass/fail for the whole batch.

**Open verification item:** whether a Cloudflare Pages Function can hold a raw TCP connection to
Postgres port 5432 for the ~1–2 seconds a migration batch takes — Workers' networking model differs
from Node's. If it can't, this endpoint moves to a small persistent-runtime service instead of an
edge Function; the interface contract above is unaffected either way.

### Function-inliner (build-time tool, not a request-time endpoint)
Reads each `supabase/functions/<name>/index.ts`, resolves its `import ... from "../_shared/X.ts"`
lines, and inlines the referenced file's contents in place — producing one self-contained `.ts` file
per function with no relative imports left. Runs when the Wizard itself is deployed (once per Wizard
release), not per café, and its output is what `/api/provision/functions` uploads. This sidesteps
relying on the Supabase Management API's undocumented multi-file upload behavior entirely — every
upload uses the one shape its docs actually demonstrate.

**Open verification item:** must be checked against one real function with a live deploy (confirm it
actually invokes correctly afterward) before trusting it across all 26 — a name collision between two
functions' shared imports would otherwise ship silently broken functions.

### `/api/provision/functions` — Edge Function deployer
**Input:** `{ personalAccessToken: string, projectRef: string }`. **Behavior:** loops the inliner's
26 output files through `POST /v1/projects/{ref}/functions/deploy?slug=<name>` (confirmed endpoint;
`metadata={entrypoint_path: "index.ts", name: "<name>"}` + the single inlined file as the multipart
`file` part). Redeploying an existing slug updates it in place, so this call is safe to repeat.
**Output:** `{ results: [{ function, status, error? }] }` — one entry per function.

### `/api/provision/pages` — Cloudflare Pages project creation (git-integration)
**Input:** `{ cafeSlug: string, cloudflareAccountId: string, cloudflareApiToken: string,
supabaseUrl: string, supabaseAnonKey: string }`. **Behavior:** a single confirmed, officially
documented call —
```
POST https://api.cloudflare.com/client/v4/accounts/{account_id}/pages/projects
```
with a GitHub `source` pointing at a repo RAZStudio owns, `build_config` (`npm run build` / `dist`),
and that café's `VITE_SUPABASE_URL`/`VITE_SUPABASE_PUBLISHABLE_KEY` set via
`deployment_configs.production.env_vars` — see Data Models for the exact body. Cloudflare's own build
servers then clone the repo and build per project; this endpoint never touches a build artifact
itself.

**Prerequisite (one-time, RAZStudio-side only, done once ever — not per café):** the Cloudflare Pages
GitHub App must be installed and authorized on RAZStudio's GitHub org before the Wizard's first use.
Confirmed this authorization is account/org-level, so a single one-time authorization (or "all
repositories") covers every future café — it never becomes a per-onboarding step, and no café owner
ever needs a GitHub account.

**Open item, not yet verified either way:** since a Pages project merely *references* a git source,
multiple projects may be able to point at the exact SAME repo+branch, differentiated only by each
project's own env vars — meaning zero per-café branch/repo management, just N independent
project-creation calls against one shared template repo. If a Cloudflare quirk requires distinct
branches per project instead, the fallback is a branch-per-café created via GitHub's (fully
scriptable) API. Confirm against the disposable test account before committing to either path.

### `/api/provision/dns` — optional custom domain
**Input:** `{ zoneId, apiToken, recordName, target }`. A single, well-documented
`POST /zones/{zone_id}/dns_records` call, independent of the Pages work above.

## Data Models

**Wizard in-memory credential set** (component state only; never sent anywhere but the four
`/api/provision/*` endpoints; never written to `localStorage`/cookies/a database):
```
{ supabaseConnectionString, supabasePersonalAccessToken, supabaseProjectRef,
  cloudflareAccountId, cloudflareZoneId?, cloudflareApiToken, cafeSlug, customDomain? }
```

**Cloudflare Pages project-creation body** (the confirmed shape `/api/provision/pages` sends):
```json
{
  "name": "<cafe-slug>",
  "production_branch": "main",
  "source": {
    "type": "github",
    "config": {
      "owner": "razstudio-org",
      "repo_name": "cafe-website-template",
      "production_branch": "main",
      "deployments_enabled": true,
      "production_deployments_enabled": true,
      "preview_deployment_setting": "none"
    }
  },
  "build_config": { "build_command": "npm run build", "destination_dir": "dist", "root_dir": "/" },
  "deployment_configs": {
    "production": {
      "env_vars": {
        "VITE_SUPABASE_URL": { "type": "plain_text", "value": "<that café's URL>" },
        "VITE_SUPABASE_PUBLISHABLE_KEY": { "type": "plain_text", "value": "<that café's anon key>" }
      }
    }
  }
}
```

**Per-step result shape**, shared across every `/api/provision/*` endpoint so the frontend checklist
can render them uniformly: `{ step: string, status: "ok" | "error" | "skipped", detail?: string }[]`.

**Handoff payload** (what the APK's existing `SetupScreen` receives — unchanged shape, confirmed by
re-reading `AppConfigStore.save()`'s current fields): `{ supabaseUrl, supabaseAnonKey, websiteUrl,
cafeName }`. No Cloudflare or Supabase high-privilege field is ever part of this payload.

## Correctness Properties

### Property 1: No high-privilege credential is ever persisted
Not to a database, log, or file, at any point in the Wizard's backend — request bodies construct one
outbound call and then go out of scope. This holds for every current and future endpoint under
`/api/provision/*`, not just the ones designed today.
**Validates: Requirements 5.1, 5.2**

### Property 2: Every provisioning step is independently retryable and independently reported
No endpoint returns a single pass/fail for a batch of underlying operations (migrations, functions) —
each item in the batch gets its own result, and re-running the batch must not fail merely because
some items were already applied (idempotent where the underlying API allows it — e.g. redeploying an
existing function slug updates it rather than erroring).
**Validates: Requirements 2.3, 3.2, 3.3, 6.1, 6.2**

### Property 3: The APK's SetupScreen/AppConfigStore never gain a high-privilege field or code path
This spec's entire credential-custody argument depends on that boundary holding; any change to
`AppConfigStore` that adds a Supabase PAT or Cloudflare API token field would silently break the
security model this design exists to provide.
**Validates: Requirement 5.3**

### Property 4: Nothing in this design drives a browser or scrapes a vendor's UI
Every step is a plain REST call or a direct Postgres connection — the explicit reason being that
scraping breaks on the vendor's next UI change, gets blocked by Turnstile/CAPTCHA, and risks
app-store rejection for automated web-driving code.
**Validates: Requirements 1.1, 8.1**

## Error Handling

- **Per-item, not per-batch.** `/api/provision/schema` and `/api/provision/functions` report one
  result per migration file / per function, so a single bad migration or one broken function doesn't
  hide the status of the other 5 or 26.
- **The Wizard UI is a checklist, not a single "Provision" action** — a failed step is retried on its
  own without re-running steps that already succeeded (Correctness Property 2).
- **A migration failure is expected to be diagnosable, not silent**: since these migrations are
  first-run-only (`create table`/`create type` error if the object exists — the same caveat
  `apply-migration.mjs` already documents), a failure on re-run against an already-partially-applied
  project is reported per-file so the operator can see exactly which statement failed, rather than the
  Wizard assuming a clean run.
- **Verification failures block promotion, not just implementation.** Per the Testing Strategy below,
  an unverified endpoint (schema-runner networking, the function-inliner, the Cloudflare git-source
  behavior) is not to be treated as production-ready until checked against a real disposable account —
  this is an explicit gate, not an assumption that "it should work."

## Testing Strategy

This cannot be verified by reading documentation alone. Before any phase is considered done:
1. Create one disposable Supabase project + one Cloudflare account/zone for testing.
2. Verify the direct-Postgres migration runner against that disposable project.
3. Verify the function-inliner + deploy against ONE real function first (e.g. `settings`), confirm it
   actually invokes correctly post-deploy, before trusting the loop over all 26.
4. Verify Cloudflare Pages project creation via git-integration against the same disposable account,
   including the open "one shared repo, many projects" question above.
5. Verify DNS record creation, if a custom domain is exercised.
6. Only after 2–5 pass does the Wizard get pointed at a real café's accounts.

None of steps 2–5 can happen inside this environment, which has no live Supabase/Cloudflare
credentials of its own — this is why Requirement 8 blocks calling this spec "done."
