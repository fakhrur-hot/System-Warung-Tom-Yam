Wizard must be rebuilt and redeployed before you provision:

verify_jwt was never set on deployed Edge Functions (functions.ts:57) — every freshly provisioned café would gateway-401 all API calls (the exact bug that hit Sri Pantai Timur and needed a manual --no-verify-jwt redeploy). Now baked in as verify_jwt: false.
Pages root_dir was / (pages.ts, run.ts) — the repo root has no package.json (the site lives in website/), so every Cloudflare build failed and the project sat at zero deployments serving 522. That's almost certainly what stranded Sri Pantai Timur's site on Aug 5. Now website.
What you create manually (one-time, per account)
Supabase (new account) — the APK creates the project; you only need account-level things:

Sign up at supabase.com (free plan is fine — allows 2 active projects).
Personal Access Token: supabase.com/dashboard/account/tokens → Generate new token. This is the only Supabase credential the APK asks for in "new project" mode.
Org ID: a personal org is auto-created on signup; copy its slug/ID from the dashboard URL or Organization Settings.
Pick a region string for the form: ap-southeast-1 (Singapore).
Do not create a project — /run does that (waits up to 10 min for ACTIVE, generates the DB password itself).
Cloudflare (new account):

Sign up, free plan is fine. Copy the Account ID (Workers & Pages page, right sidebar).
API token: My Profile → API Tokens → Create Custom Token → permission Account → Cloudflare Pages → Edit. (Add Zone → DNS → Edit only if you'll use a custom domain — skip for a test café, you'll get <slug>.pages.dev.)
The one step that cannot be automated: link GitHub to this new Cloudflare account. Café sites are created via git-integration from fakhrur-hot/System-Warung-Tom-Yam (template-repo.properties). In the dashboard go Workers & Pages → Create → Pages → Connect to Git, sign in as fakhrur-hot, and make sure the repo is granted to the Cloudflare Pages app. Once the repo list shows, you can cancel — the link is established; the API call the APK triggers will now work. Without this, create-cloudflare-pages fails.
Wizard (RAZStudio side — hosts the /api/provision/run endpoint the APK calls):


cd provisioning
npm install
npm run build          # regenerates migrations/functions bundles + picks up today's two fixes
npx wrangler pages deploy dist --project-name=cafe-setup-wizard
Must be wrangler pages deploy — plain wrangler deploy makes a Worker that serves the UI but 404s every API route (that's the stray sri-pantai-timur.workers.dev from before). Verify: GET https://<wizard>/api/provision/run must return 405, never 404. It can live on either Cloudflare account.

Optional: a Brevo API key (email reports) — skip for a test café.

What you type on the tablet
Setup → Connection → Provision new café tab:

Field	Value
Wizard URL	https://<wizard>.pages.dev/api/provision/run
Café name	anything
Supabase → New	PAT, org ID, region ap-southeast-1, project name
Cloudflare → New	Account ID, API token, café slug (lowercase-hyphens → becomes <slug>.pages.dev)
Everything else — Supabase project, schema, all Edge Functions, secrets (WEBSITE_ORIGIN), storage buckets, auth URLs, Pages project + first build, owner key — is automated by the run. Expect several minutes; the APK shows a per-step checklist, and a partial failure is recoverable (there's a functions-only re-deploy repair path).

At the end it shows the owner-key QR — save it immediately and properly.