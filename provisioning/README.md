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

   > ### ⚠ It must be Pages, not Workers
   >
   > `wrangler pages deploy` serves **two** things from one project: the Vite UI out of `dist/`, and
   > the eight provisioning endpoints out of `functions/`, which Cloudflare mounts automatically by
   > file path (`functions/api/provision/run.ts` → `/api/provision/run`). There is no router to write;
   > that file tree *is* the routing.
   >
   > Deploying with plain `wrangler deploy` publishes a **Worker** on `*.workers.dev` that serves
   > `dist/` alone. Workers do not run a Pages `functions/` directory, so the UI loads perfectly and
   > every `/api/provision/*` route returns 404 — and the Wizard is then unable to provision anything,
   > including a fix for itself. Nothing in the UI hints at the cause; it looks like a healthy site.
   >
   > Symptom to check for: `curl -s -o /dev/null -w '%{http_code}' <wizard>/api/provision/run` should
   > be 405 (GET not allowed), never 404. Local equivalent is `wrangler pages dev dist`, which mounts
   > `functions/`; plain `wrangler dev` does not.

   If the Wizard is unreachable and a café needs its Edge Functions deployed *now*, there is a
   laptop-side escape hatch that performs the identical operation:
   ```
   npm run generate
   SUPA_PAT=sbp_... SUPA_REF=<project-ref> node scripts/deploy-edge-functions.mjs
   ```
4. **Nothing to configure for the template repo.** `RAZSTUDIO_GITHUB_OWNER` /
   `RAZSTUDIO_GITHUB_REPO` used to be required environment variables on the Wizard's own Pages
   project. They are now baked at build time from `template-repo.properties` at the monorepo root —
   the same file the APK compiles into `BuildConfig`, so the repo a café's website deploys from and
   the repo its menu presets come from cannot disagree.

   Setting the two environment variables still works and still wins, for a fork or a white-label
   Wizard that deploys cafés from a different repository. Leave them unset otherwise.

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
# RAZSTUDIO_GITHUB_OWNER / _REPO are optional — they default to template-repo.properties.
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

The Wizard URL is **not** a build input. It is typed on the Provision screen (and, optionally, on
Setup → Existing café, which stores it for later):

```
https://<your-wizard-domain>/api/provision/run
```

It used to be `PROVISIONER_WORKER_URL` in `apk/local.properties`, compiled into `BuildConfig`. That
put a live provisioning endpoint into every APK built from this branch — including café builds that
will never provision anything — and could not be repointed at a disposable Wizard without a rebuild,
which is exactly what rehearsing the unverified steps requires. The app remembers what was typed, on
that device only.

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
