# Sri Pantai Timur — new café setup plan

Standing up **Sri Pantai Timur** as a completely separate entity: its own Supabase project, its own
Cloudflare Pages site, its own domain, its own data. No shared anything with Tani Tom Yam.

This is a **plan**, not a runbook you should execute top-to-bottom yet. Read §1 first — there are
two decisions that change what you buy, and one of them has no free answer.

---

## 1. Read this before you buy anything

### 1a. You are at the Supabase free-tier project cap

The free tier allows **2 active projects per organisation**. Tani Tom Yam is one. Sri Pantai Timur
would be the second — which works, but it means **the next café after this one cannot be free** on
this account.

Three ways forward:

| Option | Cost | Trade-off |
|---|---|---|
| Use the second free slot | Free | Café #3 forces a decision later, under time pressure |
| New Supabase org/account for this café | Free | Clean separation; a second login to keep track of, and you must not lose it |
| Supabase Pro | ~USD 25/mo per org | Unlimited projects, daily backups, no pausing |

**Recommendation: a separate Supabase account (or org) per café.** It costs nothing, it makes
"totally new entity" literally true, and it means one café's usage can never pause or throttle
another's. Use an email you control and can hand over — `sripantaitimur@…`, not your personal one.

### 1b. Free-tier Supabase pauses after 7 days of no API requests

A project with no traffic for a week is **paused automatically**. Data is kept, but the café is
offline until someone manually resumes it from the dashboard.

For a trading café this never fires — daily orders are daily API calls. It fires in exactly two
situations, both of which apply to you:

- **Between setup and opening day.** If you provision this two weeks before the café opens, it will
  be asleep on opening morning.
- **A café that closes for a week** — Raya, renovation, a long holiday.

Neither is fatal (resume takes a minute from the dashboard) but *nobody should discover it at
7am on opening day*. Plan to open within a week of provisioning, or diarise a resume check.

### 1c. The domain is the only thing that certainly costs money

Everything else here has a real free tier. A domain does not.

- **Cloudflare Registrar sells at cost** (no markup) but supports a specific TLD list. `.com`/`.net`
  are certainly supported; **`.my` and `.com.my` are not confirmed** — I could not verify Malaysian
  TLDs are on Cloudflare's list.
- If you want `.my`, buy from a MYNIC-accredited registrar (Exabytes, Shinjiru, etc.) and then point
  the **nameservers** at Cloudflare. DNS on Cloudflare's free plan is fully featured — you do not
  need to register *through* Cloudflare to use it.
- ⚠️ **Do not register through Cloudflare if you might move DNS later** — Cloudflare-registered
  domains are locked to Cloudflare DNS, and changing that requires transferring the domain out.

**Decision needed:** which domain, and registered where. Everything in §4 assumes you have one.

### 1d. You probably do not need a Cloudflare Worker

You asked for one, so: a Worker earns its place when **one deployment serves many cafés**, choosing
config by hostname. That is not what you are building — Sri Pantai Timur gets its own Pages project
with its own environment variables and its own `app-config.json`, which is simpler, cheaper to
reason about, and has no cold-start or request-quota ceiling.

Cloudflare **Pages Functions** (already used by `provisioning/`) cover the one server-side need this
stack has. Skip the Worker unless you later decide to collapse all cafés onto one deployment.

---

## 2. What you're actually creating

```
Sri Pantai Timur
├─ Supabase project              ← database, auth, storage, ~30 Edge Functions
│   └─ owner recovery key        ← the café's identity; whoever holds it holds the café
├─ Cloudflare Pages project      ← customer ordering website  (sri-pantai-timur.pages.dev)
│   └─ custom domain             ← optional but recommended; the QR cards print this URL
├─ GitHub repo variables/secrets ← so CI deploys the right site to the right project
└─ D3 MINI terminal              ← configured by scanning the owner QR; no manual typing
```

The **owner QR is the café key**. The backend mints it as `https://<your-domain>/join?recover=<token>`
and the APK self-configures from it — Supabase URL, publishable key, website origin, café name, all
of it. This is why the domain must be settled *before* you print QR cards or hand over a device:
change the origin afterwards and every previously-issued QR stops resolving.

---

## 3. Prepare before you start

**Accounts**
- [ ] Supabase account for this café (see §1a) — email + password recorded somewhere durable
- [ ] Cloudflare account (free plan is enough)
- [ ] Domain registrar account, if not Cloudflare (§1c)
- [ ] Google account for the café — used for the Drive café-bundle backup
- [ ] Brevo account, only if you want the closing/monthly report emails

**Assets**
- [ ] Café name exactly as it should print: `Sri Pantai Timur`
- [ ] Logo — colour, square, ≥512px, PNG
- [ ] Payment QR — a photo/screenshot of the café's own DuitNow or bank merchant QR
- [ ] Floor plan — how many tables, plus how many take-away (`Tapaw`) slots
- [ ] Menu — categories, item names, prices. Bring it as a list before you start typing into the app
- [ ] GPS location of the premises (captured on-site from the device)

**Tools on your machine**
- [ ] `supabase` CLI (`npm i -g supabase`) — or use the repo's own runner, see §4.2
- [ ] `wrangler` CLI (`npm i -g wrangler`)
- [ ] Node 20+, `git`, `adb`

**Costs to expect**

| Item | Cost |
|---|---|
| Supabase free tier | RM 0 — 500 MB DB, 1 GB storage, 5 GB egress, 500k function calls/mo |
| Cloudflare Pages + DNS free plan | RM 0 |
| Domain | ~RM 50–120/yr depending on TLD and registrar |
| Brevo (optional, report emails) | Free tier available |
| **Total** | **Domain only** |

---

## 4. Setup order

The order matters: the website origin is baked into the owner QR, so the domain has to exist before
the device is provisioned.

### 4.1 — Supabase project

1. New project, name `sri-pantai-timur`, region **Singapore** (closest to Malaysia — every order
   round-trips through it, so this is the single biggest latency decision you will make).
2. Save the database password immediately; it is shown once.
3. **Database → Extensions**: enable `pg_cron` and `pgcrypto`.
4. Collect from **Settings → Data API**: project URL (`https://<ref>.supabase.co`), the
   **publishable** key (client-safe), and the **secret** key (server-side only, never in git).

### 4.2 — Schema

Twelve migrations live in `supabase/migrations/` (`0001_initial_schema` … `0012_gateway_providers`).

```bash
supabase login
supabase link --project-ref <ref>
supabase db push
```

No CLI? The repo ships its own runner:

```bash
cd supabase && npm install
DATABASE_URL="postgresql://postgres:<PASSWORD>@db.<ref>.supabase.co:5432/postgres" npm run migrate
```

> Apply **all twelve**, in order, even the payment-gateway ones (0009–0012). The gateway feature is
> switched off in the app, but the schema is expected by code paths that still compile and run.

### 4.3 — Edge Functions

About 30 functions in `supabase/functions/`. Deploy them all:

```bash
supabase functions deploy --project-ref <ref>
```

Then set the secrets:

```bash
supabase secrets set SUPABASE_SECRET_KEY=sb_secret_xxxx
supabase secrets set ROTATING_KEY_SECRET=$(openssl rand -hex 32)
supabase secrets set BREVO_API_KEY=xkeysib-xxxx     # only if you want report emails
```

### 4.4 — Storage and Auth

- **Storage**: create a **public** bucket named `logos`.
- **Auth**: enable Email with confirmation. Set Site URL and Redirect URL to the café's final
  origin — the custom domain if you have one, otherwise `https://sri-pantai-timur.pages.dev`.

### 4.5 — Cloudflare Pages

```bash
wrangler pages project create sri-pantai-timur
```

Then in the Pages project's settings:

- **Environment variables**: `VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`,
  `VITE_CAFE_NAME=Sri Pantai Timur`
- ⚠️ **Production branch** — set it deliberately and write down what you chose. This bit us on Tani
  Tom Yam: the live site was building from a branch that was *not* `main`, and pushes to `main`
  silently changed nothing while appearing to succeed.

`website/public/app-config.json` is a runtime override fetched by the browser, so the same built
bundle can serve a different café. Either set the `VITE_*` build variables above or ship a per-café
`app-config.json` — but pick one and be consistent, or you will spend an afternoon working out which
value actually won.

### 4.6 — Domain

1. Add the domain to Cloudflare as a zone (free plan).
2. Point the registrar's nameservers at the two Cloudflare gives you. Propagation is usually minutes,
   allow up to 24h.
3. Pages → Custom domains → add it. Cloudflare issues the certificate.
4. Go back and update the Supabase **Auth Site/Redirect URL** to the custom domain. Missing this
   makes email links land on the wrong origin.

### 4.7 — GitHub CI (optional)

Only if you want pushes to auto-deploy. Repo → Settings → Secrets and variables → Actions:

- Variable `CLOUDFLARE_PROJECT_NAME` = `sri-pantai-timur`
- Secrets `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `VITE_SUPABASE_URL`,
  `VITE_SUPABASE_PUBLISHABLE_KEY`

Note these are **repo-wide** — one repo cannot auto-deploy two cafés from the same variables. If both
Tani and Sri Pantai Timur are to auto-deploy, you need per-branch/per-environment values or a second
repo. Deploying this one by hand (`wrangler pages deploy`) is the honest short-term answer.

### 4.8 — The D3 MINI

1. Factory-reset or uninstall the existing app — the terminal currently holds **Tani Tom Yam's**
   session, device registration and cached data. Do not try to convert it in place.
2. Install the template APK.
3. First run → Setup → Owner QR tab.
4. Get the owner QR from the new Supabase project (`admin-recovery` mints
   `https://<domain>/join?recover=<token>`). Scan it. The device configures itself.
5. In the app: Café Management → Café Profile → name, logo, GPS lock, Payment QR.
6. Tables Management → the floor plan. Menu Management → the menu (or load a preset).
7. Devices & Hardware → printer, cash drawer, auto-cut.
8. Settings → Security → **change the drawer PIN from the default `666666`**.
9. Café Profile → link the café's Google account, then save the bundle to Drive. This is what makes
   a broken terminal a 10-minute recovery instead of a re-setup.

---

## 5. Verify before opening

- [ ] Customer scans a table QR → menu loads → order arrives on the terminal
- [ ] Order prints to the kitchen printer
- [ ] Cash payment → receipt prints → drawer opens
- [ ] Split payment across two customers
- [ ] Payment QR shows at checkout and scans with a real banking app
- [ ] Calculator → drawer PIN → `=` → drawer opens
- [ ] Closing report runs, and emails if Brevo is configured
- [ ] Google Drive bundle saved and **restored onto a second device** — an untested backup is not a
      backup
- [ ] Website loads on the custom domain over HTTPS
- [ ] The printed QR cards point at the custom domain, not `.pages.dev`

---

## 6. Known risks

| Risk | Impact | Mitigation |
|---|---|---|
| Supabase pauses after 7 idle days | Café offline on opening morning | Provision close to opening; check the day before |
| Free tier is 500 MB DB / 5 GB egress | Growth stops the café, not gradually | Watch the dashboard monthly; Pro is USD 25/mo |
| No backups on free tier | Data loss is permanent | The Drive café bundle is your only backup — test the restore |
| Owner QR leaks | Whoever holds it holds the café | Treat it as a key; do not put it in a group chat |
| Wrong Pages production branch | Deploys silently do nothing | §4.5 — verify after the first deploy |
| `.my` may not be on Cloudflare Registrar | Blocks a same-day domain purchase | Confirm before committing to a TLD |
| Supabase free cap is 2 projects | Café #3 blocked | Separate account per café (§1a) |

---

## 7. There is a wizard, and it is partly unverified

`provisioning/` is a setup wizard built for exactly this job — it provisions Supabase and Cloudflare
through their APIs. Its own README is honest about the state of it:

- ✅ Create Pages project — built on documented API, safe
- ✅ Point custom domain — built on documented API, safe
- ⚠️ Apply schema — **never run against a real deployed Function**
- ⚠️ Deploy Edge Functions — inliner verified, **the live deploy call is not**

For the *first* real café through it, do §4 by hand. Then, if you want café #3 to be faster, use Sri
Pantai Timur as the chance to verify the two unverified steps against a disposable project — and only
then remove the "Unverified" badges in `provisioning/src/App.tsx`.

---

## 8. Open decisions

1. **Supabase**: second free slot, or a separate account for this café? (Recommend: separate account)
2. **Domain**: which name, which TLD, registered where?
3. **CI**: auto-deploy this café, or deploy by hand until the repo handles two cafés properly?
4. **Timing**: when does Sri Pantai Timur actually open? That sets when to provision (§1b).
