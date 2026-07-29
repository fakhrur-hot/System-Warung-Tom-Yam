# Operations Runbook

Practical day-to-day operations guide for System Warung Tom Yam deployments.

---

## Supabase Keep-Alive

### The 7-day pause rule

Supabase free-tier projects are **paused after 7 days of inactivity** (no API requests).
A paused project stops responding to all API calls, Edge Functions, and Realtime connections
— the stall is effectively offline until manually resumed.

### Automatic prevention (daily cron)

The `.github/workflows/keep-alive.yml` workflow runs **daily at 00:00 UTC (8:00 AM MYT)**
and hits two endpoints:

1. **REST API** — `GET /rest/v1/settings?select=id&limit=1` (PostgREST, lightweight query)
2. **Edge Function** — `GET /functions/v1/api/menu` (warms the function runtime)

This single daily hit is enough to reset the 7-day inactivity counter.

### Holidays longer than 7 days

**Covered automatically.** GitHub Actions scheduled workflows run regardless of whether
anyone pushes code or opens the repo. The daily cron fires even during CNY, Hari Raya,
or a two-week break — no manual intervention needed.

### Manual wake procedure

If the project does get paused (e.g., GitHub Actions quota exhausted, or secrets rotated
incorrectly):

1. Go to [Supabase Dashboard](https://supabase.com/dashboard)
2. Select the project
3. Click the **"Resume project"** button (top banner or project settings)
4. Wait 1–2 minutes for the project to come back online
5. Verify by running:
   ```bash
   curl -s "$SUPABASE_URL/rest/v1/settings?select=id" \
     -H "apikey: $ANON_KEY"
   ```
   A 200 response with JSON data confirms the project is awake.

### Required GitHub secrets

| Secret | Value |
|--------|-------|
| `SUPABASE_URL` | `https://<ref>.supabase.co` |
| `SUPABASE_ANON_KEY` | The `anon` / publishable key from Supabase → Settings → API |

---

## Edge Function Cold Starts

### The problem

Edge Functions on the free tier spin down after ~5 minutes of inactivity. The first
invocation after idle incurs a **cold-start penalty of 2–5 seconds** — this can push the
first real order of the day past the 3-second latency NFR (REQ-8).

### Mitigation

- The daily keep-alive cron warms the Edge Function every morning at 8:00 AM MYT, before
  the stall typically opens.
- Once the first request hits, subsequent invocations stay warm (sub-200ms) for the rest of
  the service day.

### If cold start exceeds 3s repeatedly

Consider the **PostgREST fallback pattern**: instead of routing order creation through an
Edge Function, use a direct PostgREST insert (RLS-protected) combined with a database
trigger that fires the Realtime broadcast. This bypasses the Edge Function cold start
entirely for the critical `POST /api/orders` path. See the Phase 0 spike notes (Task 3)
for measured latency data.

---

## Brevo Sender Verification

Brevo (formerly Sendinblue) sends the closing report and monthly report emails. It requires
a **verified sender** — but no paid domain is needed (a plain personal email works).

### Steps to verify

1. Log in to [Brevo](https://app.brevo.com)
2. Go to **Settings → Senders, Domains & Dedicated IPs**
3. Click **"Add a Sender"**
4. Enter the superadmin's personal email address (e.g., `owner@gmail.com`)
5. Brevo sends a verification email — click the link in it
6. Done. The sender is now authorized.

### Test delivery

1. From the Brevo dashboard, go to **Campaigns → Email → Create**
2. Send a test email to yourself using the newly verified sender
3. Confirm it arrives in your inbox (check spam if needed)

### Notes

- The 300 emails/day free-tier limit is more than sufficient (closing + monthly = 2/day max)
- If the owner changes their email, repeat the verification steps above
- The `BREVO_API_KEY` secret is stored in Supabase Edge Function secrets (see DEPLOYMENT.md)

---

## APK Release Process

### Versioning

Update these values in `apk/app/build.gradle.kts` before releasing:

```kotlin
versionCode = 2          // Integer, must increment every release
versionName = "1.1.0"    // Human-readable (semver)
```

### Automated release (recommended)

1. Commit the version bump to `main`
2. Tag the commit: `git tag v1.1.0 && git push origin v1.1.0`
3. The `release-apk.yml` workflow automatically:
   - Builds a signed release APK
   - Publishes it to **GitHub Releases** with the tag name
4. Download the APK from the Releases page
5. Install on devices via USB cable or file sharing (WhatsApp, Google Drive, etc.)

### Manual local build

If you need to build locally (e.g., no internet, testing signing):

```bash
cd apk
# Ensure keystore.properties exists (git-ignored) with:
#   storeFile=path/to/warung-release.jks
#   storePassword=***
#   keyAlias=***
#   keyPassword=***
./gradlew assembleRelease
# APK at: app/build/outputs/apk/release/app-release.apk
```

### Installing on devices

- **USB**: Connect the phone, enable USB debugging, run `adb install app-release.apk`
- **File share**: Send via WhatsApp/Telegram/Google Drive → open on the phone → install
- **Direct download**: Open the GitHub Release URL on the phone's browser → download → install
- Ensure "Install from unknown sources" is enabled for the installer app

---

## Cloudflare Pages

### Critical: never rename the project

The `*.pages.dev` URL is **printed on physical QR table cards**. Renaming the Cloudflare
Pages project changes the URL and breaks every printed card. This is the deliberate
trade-off for not purchasing a domain name.

### Custom domain

Not needed. The free tier includes a stable `*.pages.dev` URL with unlimited bandwidth and
commercial use. No paid domain commitment required.

### Deployment

Push to `main` with changes in the `website/` directory → the `deploy-website.yml` workflow
auto-deploys to Cloudflare Pages. Or trigger the workflow manually via `workflow_dispatch`.

### Troubleshooting

- **Build fails**: Check the workflow logs; most likely a missing env var (`VITE_SUPABASE_URL`
  or `VITE_SUPABASE_PUBLISHABLE_KEY` secrets)
- **404 after deploy**: Verify the `CLOUDFLARE_PROJECT_NAME` variable matches your actual
  Pages project name
- **Stale content**: Cloudflare Pages has no CDN cache to purge — deploys are instant

---

## Monitoring (Manual)

There is no paid monitoring service. Periodic manual checks:

### Weekly checks

- [ ] **GitHub Actions**: Look for failed cron runs (yellow/red badge on the Actions tab).
      A failed keep-alive run means the ping didn't work — check secrets are still valid.
- [ ] **Supabase dashboard**: Confirm project status is "Active" (not "Paused").

### Monthly checks

- [ ] **Edge Function invocations**: Supabase dashboard → Edge Functions → check invocation
      count isn't approaching **500,000/month** (free-tier limit). At ~100 orders/day this
      stays well under the limit.
- [ ] **Database size**: Free tier allows 500 MB. Check Dashboard → Database → Usage. The
      24h purge job keeps active orders table small; aggregates grow slowly.
- [ ] **Brevo sends**: Verify the monthly report email was received. Check Brevo dashboard
      → Transactional → Logs for delivery status.

### If something looks wrong

1. Check GitHub Actions logs for the keep-alive workflow
2. Try the manual verification curl (see "Manual wake procedure" above)
3. Check Supabase status page: https://status.supabase.com
4. If the project is paused, resume it from the dashboard
