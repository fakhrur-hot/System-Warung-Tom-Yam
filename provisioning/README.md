# Café Setup Wizard

A standalone, RAZStudio-operated Web Setup Wizard that walks a new café owner through provisioning
their **own** Supabase project and Cloudflare account (Bring-Your-Own-Infrastructure) — no browser
automation, no scraping, every step is a plain REST call or a direct Postgres connection. See
`.kiro/specs/cafe-provisioning-wizard/` for the full requirements/design/task history, including
the research that shaped this (Supabase's gated migrations endpoint, Cloudflare's unofficial Direct
Upload protocol).

This is **not** part of any café's own `website/` — it's one tool RAZStudio hosts, reused for every
café onboarded.

## What's verified vs. what isn't

| Step | Endpoint | Status |
|---|---|---|
| Create the ordering website (Cloudflare Pages, git-integration) | `/api/provision/pages` | ✅ Built on Cloudflare's officially-documented API — safe to trust |
| Point a custom domain | `/api/provision/dns` | ✅ Built on Cloudflare's officially-documented API — safe to trust |
| Apply database schema | `/api/provision/schema` | ⚠️ Code compiles and follows Cloudflare's own official Postgres-over-Workers tutorial, but has **not been run against a real deployed Function** — verify before trusting on a real café's project |
| Deploy Edge Functions | `/api/provision/functions` | ⚠️ Code compiles and the inliner is verified against every real function in this repo (caught and fixed a real duplicate-import bug — see `scripts/inline-functions.mjs`'s comments), but the **live deploy call itself** hasn't been exercised against Supabase — verify one function first |

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

## Verifying the two unverified steps (Requirement R8)

You'll need one disposable Supabase project and one Cloudflare account/zone — see
`.kiro/specs/cafe-provisioning-wizard/design.md`'s Testing Strategy.

1. **Schema**: run the Wizard against that disposable project's real connection string. Confirm all
   6 migrations report `ok`, then check the tables actually exist in the Supabase dashboard.
2. **Functions**: deploy just ONE function first (comment out the loop in `functions.ts` to test a
   single entry from `EDGE_FUNCTIONS`, or temporarily filter it), confirm it appears in the Supabase
   dashboard's Edge Functions list and actually responds when invoked, THEN trust the full loop.
3. Only after both pass, remove the "Unverified" badges in `src/App.tsx`'s `STEPS` array
   (`verified: false` → `true`).

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
