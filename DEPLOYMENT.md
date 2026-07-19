# Deployment & Secrets

How to wire a café's **Supabase** and **Cloudflare** accounts to this repo. No real values
live in git — this file uses placeholders, and each café's specifics stay in git-ignored
local files (`website/.env.local`, `website/wrangler.toml`) and repo variables/secrets.

> **Product:** System Warung Tom Yam (this repo, reusable by any café). **Per deployment:**
> each café gets **its own** Cloudflare Pages project (`<name>.pages.dev`, baked into its QR
> cards) and **its own** Supabase project. Those café-specific names/refs are intentionally
> **not committed** — set them in the local files and secrets below.

## Where each credential goes

| Your credential | Destination | Committed? |
|---|---|---|
| Supabase **publishable** key | `website/.env.local` (local) + GitHub secret `VITE_SUPABASE_PUBLISHABLE_KEY` | No (client-safe, but keep in secrets/vars) |
| Supabase **secret** key | Supabase **Edge Function secret** `SUPABASE_SECRET_KEY` + local `supabase/.env.local` | **Never** |
| Supabase **project URL** | `website/.env.local`, `supabase/.env.local`, GitHub secret `VITE_SUPABASE_URL` | No |
| Cloudflare **API token** | GitHub secret `CLOUDFLARE_API_TOKEN` | **Never** |
| Cloudflare **account ID** | GitHub secret `CLOUDFLARE_ACCOUNT_ID` | **Never** (not really secret, but keep it out of git) |
| Cloudflare **R2** keys | Unused — the design stores the logo in Supabase Storage. Keep them for later if you adopt R2. | **Never** |

## 1. Local dev

```bash
# website — publishable key is client-safe
cp website/.env.example website/.env.local     # then set VITE_SUPABASE_URL + publishable key
# (website/.env.local was pre-filled with your publishable key; just set the URL)

# supabase edge functions — SERVER-SIDE secret, never commit
cp supabase/.env.example supabase/.env.local    # then paste your SECRET key + generate ROTATING_KEY_SECRET
openssl rand -hex 32                             # value for ROTATING_KEY_SECRET
```

Find your **project URL/ref** in Supabase → Project Settings → Data API
(`https://<ref>.supabase.co`). Put it everywhere marked `REPLACE_WITH_PROJECT_REF`.

## 2. Supabase project

**a. Enable extensions** — Dashboard → Database → Extensions → enable `pg_cron` + `pgcrypto`.

**b. Apply the schema** — pick ONE (`<ref>` = your Supabase project ref):

- *Supabase CLI* (blessed path):
  ```bash
  supabase login && supabase link --project-ref <ref> && supabase db push
  ```
- *No CLI — built-in runner* (needs only your DB password):
  ```bash
  cd supabase && npm install
  # connection string (URI, with password) from Settings → Database → Connection string
  DATABASE_URL="postgresql://postgres:<PASSWORD>@db.<ref>.supabase.co:5432/postgres" npm run migrate
  ```
- *Dashboard* — paste `supabase/migrations/0001_initial_schema.sql` into the SQL editor and run.

**c. Set Edge Function secrets** (server-side only — use the secret key from *this* project):

```bash
supabase secrets set SUPABASE_SECRET_KEY=sb_secret_xxxx
supabase secrets set ROTATING_KEY_SECRET=$(openssl rand -hex 32)
supabase secrets set BREVO_API_KEY=xkeysib-xxxx
```

**d. Storage + Auth** — create the public `logos` bucket; enable Email auth with
confirmation; set Site/Redirect URL to your café's `https://<pages-project>.pages.dev`.

## 3. Cloudflare Pages (auto-deploy on push to main)

The `Deploy Website` workflow builds `website/dist` and deploys it to the Pages project
named by the repo **variable** `CLOUDFLARE_PROJECT_NAME`. Under
**GitHub → Settings → Secrets and variables → Actions** add:

**Variables** tab — one:

| Variable | Value |
|---|---|
| `CLOUDFLARE_PROJECT_NAME` | your café's Cloudflare Pages project name (→ `<name>.pages.dev`) |

**Secrets** tab — four (the two `VITE_*` are client-safe but kept here for convenience):

| Secret | Value |
|---|---|
| `CLOUDFLARE_API_TOKEN` | your Cloudflare API token (`cfat_…`) |
| `CLOUDFLARE_ACCOUNT_ID` | your Cloudflare account ID |
| `VITE_SUPABASE_URL` | `https://<ref>.supabase.co` |
| `VITE_SUPABASE_PUBLISHABLE_KEY` | the `sb_publishable_…` value in `website/.env.local` |

Create the Pages project first (`wrangler pages project create <name>` or the dashboard),
then push to `website/**` or run the workflow manually.

## 4. Secret hygiene

- The two plaintext credential files you created are **outside** this repo — keep them there
  or delete them once the values are in the destinations above. Do not copy them into the repo.
- If any secret is ever exposed, **rotate it** (Supabase → API keys; Cloudflare → API tokens / R2).
- Verify your Cloudflare token any time (read-only):
  ```bash
  curl -X GET "https://api.cloudflare.com/client/v4/accounts/<ACCOUNT_ID>/tokens/verify" \
       -H "Authorization: Bearer <CLOUDFLARE_API_TOKEN>"
  ```
