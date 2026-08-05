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

Nothing about the APK's Setup screen changes because of this tool. The Wizard's final "Done" screen
displays the Supabase URL, anon key, website URL, and café name for the owner to type into the
existing three-dots → Setup screen on the tablet — exactly the same low-privilege values that
screen has always stored.
