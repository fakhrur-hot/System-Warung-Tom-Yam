# Café Setup Wizard

A standalone, RAZStudio-operated Web Setup Wizard that walks a new café owner through provisioning
their **own** Supabase project and Cloudflare account (Bring-Your-Own-Infrastructure) — no browser
automation, no scraping, every step is a plain REST call via the Supabase / Cloudflare Management
APIs. See the internal design record for the full requirements/design/task history, including
the research that shaped this (Supabase's gated migrations endpoint, Cloudflare's unofficial Direct
Upload protocol).

This is **not** part of any café's own `website/` — it's one tool RAZStudio hosts, reused for every
café onboarded.

## What's verified vs. what isn't

| Step | Endpoint | Status |
|---|---|---|
| Create the ordering website (Cloudflare Pages, git-integration) | `/api/provision/pages` | ✅ Built on Cloudflare's officially-documented API — safe to trust |
| Point a custom domain | `/api/provision/dns` | ✅ Built on Cloudflare's officially-documented API — safe to trust |
| Apply database schema | `/api/provision/schema` | ✅ Verified live via the Supabase Management API `database/query` endpoint |
| Deploy Edge Functions | `/api/provision/functions` | ✅ Verified live against a disposable Supabase project (all 33 functions) |
| Set Edge Function secrets | `/api/provision/secrets` | ✅ Verified live via the Supabase Management API |
| Create public Storage buckets | `/api/provision/storage` | ✅ Verified live via the Supabase Storage Admin API |
| Configure Auth URLs | `/api/provision/auth` | ✅ Verified live via the Supabase Management API |
| **Full orchestrated run (APK installer)** | `/api/provision/run` | ⚠️ Smoke-tested (request parsing + structured error reporting); still needs live verification against a disposable Supabase + Cloudflare setup |

The `/api/provision/run` endpoint is what the APK installer calls. It runs the steps above in one request, plus creating a new Supabase project (if requested) and minting the first owner key. It returns the final tablet config values so the APK can save them automatically.

The UI marks the unverified two with a visible "Unverified" badge. Do not remove that badge until
the live checks below have actually been run.

## One-time setup (RAZStudio-side, done once, ever — never per café)

1. **Install the Cloudflare Pages GitHub App** on the RAZStudio GitHub org (or the specific repo
   used as the café-website template), authorizing Cloudflare to deploy from it. This is a human
   clicking through GitHub's install/authorize flow once — it is not exposed as an API call, and
   it is **not** a per-café step; a single authorization (or "all repositories") covers every future
   onboarding.
2. **Create the template repo** (or reuse this monorepo's `website/` folder as the source, if you'd
   rather not stand up a second repo) — whatever RAZStudio's GitHub owner/repo will be, note it for
   step 4.
3. **Deploy this Wizard itself** as its own Cloudflare Pages project:
   ```
   cd provisioning
   npm install
   npm run build
   npx wrangler pages deploy dist --project-name=cafe-setup-wizard
   ```
   (Or wire it into a GitHub Actions workflow, mirroring `.github/workflows/deploy-website.yml`.)
4. **Set two environment variables** on the Wizard's own Cloudflare Pages project (Settings →
   Environment variables — NOT in `wrangler.toml`, since these are operational details, not
   secrets):
   - `RAZSTUDIO_GITHUB_OWNER` — e.g. `razstudio-org`
   - `RAZSTUDIO_GITHUB_REPO` — e.g. `cafe-website-template`

## Verifying the provisioner steps

All Supabase-side steps are now verified live. The `scripts/live-verify.mjs` harness imports the
actual endpoint modules and runs them against a real Supabase project. To run it yourself:

```bash
cd provisioning
export SUPABASE_PROJECT_REF=<ref>
export SUPABASE_PAT=<account.supabase.com/account/tokens>
export SUPABASE_SERVICE_ROLE_KEY=<project service_role key>
# Optional:
export BREVO_API_KEY=<brevo key>
export WEBSITE_URL=<https://...>
export SINGLE_FUNCTION=1   # deploy only one function first, then remove for the full loop
npx tsx scripts/live-verify.mjs
```

The `SINGLE_FUNCTION=1` flag makes `functions.ts` deploy only the first Edge Function so you can
verify one deploy before running the full 33-function loop.

### Full orchestrated run (`/api/provision/run`)

`scripts/live-verify-run.mjs` runs the new orchestrator endpoint against real Supabase + Cloudflare
credentials. It can exercise a new Supabase project, a new Cloudflare Pages project, or both in
existing mode:

```bash
cd provisioning
export SUPABASE_PAT=<account.supabase.com/account/tokens>

# New Supabase project + new Cloudflare Pages project
export SUPABASE_ORG_ID=<...>
export SUPABASE_REGION=ap-southeast-1
export SUPABASE_PROJECT_NAME=live-verify-run-1
export CLOUDFLARE_ACCOUNT_ID=<...>
export CLOUDFLARE_API_TOKEN=<...>
export CLOUDFLARE_CAFE_SLUG=live-verify-run-1
export RAZSTUDIO_GITHUB_OWNER=<...>
export RAZSTUDIO_GITHUB_REPO=<...>
export CAFE_NAME="Test Café"
# Optional:
# export BREVO_API_KEY=...
# export CLOUDFLARE_ZONE_ID=...
# export CLOUDFLARE_CUSTOM_DOMAIN=...

npx tsx scripts/live-verify-run.mjs
```

For existing projects, supply `SUPABASE_PROJECT_REF`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`,
`CLOUDFLARE_PROJECT_NAME` instead of the new-project fields.

### Smoke test (no real credentials)

`scripts/smoke-test-run.mjs` exercises the orchestrator with fake credentials and verifies that the
request is parsed correctly and that failures are reported as structured `results` entries. This is a
safe pre-check before running the live verifier:

```bash
cd provisioning
npm run generate
npx tsx scripts/smoke-test-run.mjs
```

## APK installer flow

The APK now has an in-app **Provision new café** screen, reached from the role-select screen. It
collects the same credentials the Wizard would collect, then hands them to a WorkManager worker that
POSTs to `/api/provision/run` and returns the resolved Supabase URL, anon key, website URL, and
owner-key URL. The tablet can then save those values directly.

The screen supports four combinations:
- New Supabase project + new Cloudflare Pages project
- Existing Supabase project + new Cloudflare Pages project
- New Supabase project + existing Cloudflare Pages project
- Existing Supabase project + existing Cloudflare Pages project

High-privilege credentials (PATs, API tokens, service-role keys) are only held in the ViewModel state
and in the WorkManager input data for the single provisioning request; they are not persisted on
the tablet.

The build needs the worker URL in `apk/local.properties`:

```
PROVISIONER_WORKER_URL=https://<your-wizard-domain>/api/provision/run
```

## Local development

```
cd provisioning
npm install
npm run dev          # regenerates migrations.ts/edge-functions.ts, then starts Vite
```

`npm run generate` (also run automatically by `npm run dev`/`npm run build`) reads
`../supabase/migrations/*.sql` and `../supabase/functions/*/index.ts` and writes
`functions/_generated/*.ts` — these are build artifacts, git-ignored, regenerated every build so
they can never drift from the actual migrations/functions in this repo.

## What the tablet still needs

The APK's role-select screen now offers a **Provision new café** path that automates the Wizard
steps from the tablet. Once provisioning succeeds, the resolved Supabase URL, anon key, website URL,
and café name are saved straight into the existing app config store, and the device can sign in with
the minted owner key.

For cafés that were set up outside the APK, the existing three-dots → Setup screen still accepts the
same low-privilege values manually.
