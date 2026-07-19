# Supabase backend — provisioning

The backend is Supabase (Postgres + Edge Functions + Realtime + Auth + Storage), free tier.
This folder holds the schema migration and (later) the Edge Functions. Provisioning needs
**your** Supabase account — it cannot be done headless.

## One-time setup

1. Create a free project at https://supabase.com (region closest to the café).
2. Install the CLI: `npm i -g supabase` (or use the SQL editor for step 4).
3. In the dashboard → **Database → Extensions**, enable **`pg_cron`** and **`pgcrypto`**.
4. Apply the schema (pick one):
   - CLI: `supabase link --project-ref <ref>` then `supabase db push`
   - No CLI: `npm install` here, then
     `DATABASE_URL="postgresql://postgres:<PASSWORD>@db.<ref>.supabase.co:5432/postgres" npm run migrate`
     (runs `apply-migration.mjs`)
   - or paste `migrations/0001_initial_schema.sql` into the SQL editor and run it.
5. **Auth**: enable Email provider; turn on "Confirm email". Set Site URL + Redirect URL to
   your Cloudflare Pages domain (`https://<project>.pages.dev`).
6. **Storage**: create a public bucket named `logos`.
7. **Secrets** (Edge Functions → Secrets), never commit:
   - `ROTATING_KEY_SECRET` — random 32+ byte hex, for the HMAC pairing key.
   - `BREVO_API_KEY` — for report emails.
8. Deploy Edge Functions (added in Phase 2): `supabase functions deploy`.

## Keep-alive

Free projects pause after 7 days without DB activity. A GitHub Actions cron
(`.github/workflows/keepalive.yml`, added in Phase 10) hits `GET /api/menu` daily to keep
it awake. Do **not** use UptimeRobot's free plan (commercial use is banned there).

## Security model

- All app traffic goes through Edge Functions using the **service_role** key (server-side
  only), which bypasses RLS. Row Level Security is enabled on every table with **no**
  anon/authenticated policies — a deny-by-default backstop if a client key ever reaches a
  table directly.
- Realtime uses **Broadcast channels** (`admin-orders`, `order:<id>`, …), not table-change
  streams, so strict RLS does not interfere.
- Tokens/keys are stored **hashed** (`api_key_hash`, `session_token_hash`) and never logged.

## Notes

- `orders` holds **active** rows only; `purge_settled_orders()` (pg_cron, every 15 min)
  deletes settled orders past `purge_after`. Long-term history lives on the admin phone and
  reaches the dashboard as daily rows in `aggregates`.
- There can be at most one live admin device (`one_live_admin` partial unique index) —
  the first-claim rule. Deregister it to re-open the claim.
