# Deployment & Secrets — Tani Tom Yam (first café)

How to wire the newly-created **Supabase** and **Cloudflare** accounts to this repo.
No real secret values live in git — this file uses placeholders. Paste the actual values
(from your local credential files) into the destinations below.

> Product: **System Warung Tom Yam** · This deployment / first café: **Tani Tom Yam**
> Public URL (baked into QR cards): `https://tani-tom-yam.pages.dev`

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

Follow `supabase/README.md`, then set the Edge Function secrets (server-side only):

```bash
supabase secrets set SUPABASE_SECRET_KEY=sb_secret_xxxx
supabase secrets set ROTATING_KEY_SECRET=<openssl rand -hex 32>
supabase secrets set BREVO_API_KEY=xkeysib-xxxx
```

## 3. Cloudflare Pages (auto-deploy on push to main)

The `Deploy Website` workflow builds and deploys `website/dist` to the Pages project
**`tani-tom-yam`**. Add these **GitHub → Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `CLOUDFLARE_API_TOKEN` | your Cloudflare API token (`cfat_…`) |
| `CLOUDFLARE_ACCOUNT_ID` | your Cloudflare account ID |
| `VITE_SUPABASE_URL` | `https://<ref>.supabase.co` |
| `VITE_SUPABASE_PUBLISHABLE_KEY` | your `sb_publishable_…` key |

First deploy also happens on the next push touching `website/**`, or run the workflow
manually (Actions → Deploy Website → Run workflow). Create the Pages project named
`tani-tom-yam` first (dashboard or `wrangler pages project create tani-tom-yam`).

## 4. Secret hygiene

- The two plaintext credential files you created are **outside** this repo — keep them there
  or delete them once the values are in the destinations above. Do not copy them into the repo.
- If any secret is ever exposed, **rotate it** (Supabase → API keys; Cloudflare → API tokens / R2).
- Verify your Cloudflare token any time (read-only):
  ```bash
  curl -X GET "https://api.cloudflare.com/client/v4/accounts/<ACCOUNT_ID>/tokens/verify" \
       -H "Authorization: Bearer <CLOUDFLARE_API_TOKEN>"
  ```
