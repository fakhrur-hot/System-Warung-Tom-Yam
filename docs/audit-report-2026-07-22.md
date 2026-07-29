# System Warung Tom Yam — Full Application Audit

**Date:** 2026-07-22
**Scope:** Entire monorepo — Supabase backend (Postgres schema + Edge Functions), Android POS app (Kotlin/Compose/Hilt/Room), customer + superadmin website (React/Vite/Tailwind), CI/CD pipelines, and project documentation.
**Method:** Manual read-through of every schema file, Edge Function, Android source package, website source tree, and GitHub Actions workflow, cross-checked against the product's own README/specs/deployment docs. No dependency installs or network scans were run; no files were modified except the one remediation noted below.

---

## 0. Immediate action taken during this audit

A file named **`supabase_k`** was found at the repo root containing **live, plaintext production credentials** for the real "Tani Tom Yam" Supabase project: the `service_role` JWT (bypasses Row Level Security entirely), the secret API key, the legacy JWT signing secret, and the **database password**. It was untracked but **not covered by `.gitignore`** — one `git add -A` away from being committed.

Action taken, per your instruction: the file was **deleted**, and `.gitignore` was updated to block the pattern from recurring:

```
supabase_k*
*.supabase-keys*
credentials.txt
secrets.txt
```

**This was never rotated.** Since the file lived locally and was never pushed, git history is clean — but if this machine's disk was ever backed up, synced, or screenshotted while the file existed, treat the credentials in it as potentially exposed. Recommend rotating the Supabase `service_role` key, the legacy JWT secret, and the database password from the Supabase dashboard as a precaution.

---

## 1. Executive summary

| Severity | Count |
|---|---|
| Critical | 4 |
| High | 6 |
| Medium | 13 |
| Low | 16 |
| Info (positive findings) | included per section |

The three most urgent problems, in order:

1. **A customer or anonymous script can cancel any table's order**, and can harvest live order data to do so at scale, because (a) `orders-cancel` never checks that the caller owns the order, and (b) the Realtime broadcast channels that carry every new order are joinable by anyone holding the public anon key — which ships in the website's JS bundle by design. These two bugs compound into a real operational-disruption risk for a live café.
2. **Anyone can sign up as a full superadmin** on the website (`/admin/register` calls `supabase.auth.signUp` with no invite/approval gate), and there is no server-side role table distinguishing "a real superadmin" from "anyone who created an account." A superadmin session can view revenue, and — critically — can generate the rotating device-pairing key that lets a rogue phone take over as the trusted Admin POS device.
3. The reporting/aggregates pipeline is **functionally broken**: the Edge Function writes to columns (`payment_split`, `top_items_per_category`) that don't exist in the schema (`payment_split_json`, `top_items_json`). Every write to `/api/aggregates` currently 500s, silently breaking dashboard metrics and monthly/closing reports.

None of these require deep expertise to exploit — #1 and #2 are reachable by anyone who can view the public website's network traffic or source, no account needed.

---

## 2. Critical findings

### C-1. `orders-cancel` has no ownership check and a bypassable privilege gate
**Files:** `supabase/functions/orders-cancel/index.ts:20-58`
The authorization check only activates when the request body says `cancelledBy: "customer"` literally. Any request that omits that exact string, or sends anything else, skips all restriction entirely and can cancel an order in *any* status (including `SENT_TO_KITCHEN`/`PREPARING`/`READY`). Even in the legitimate customer path, there's no check that `order.browser_id` matches the caller's `x-browser-id` header — so any customer who knows/guesses an `orderId` can cancel *any other table's* still-`RECEIVED` order.
**Failure scenario:** An anonymous script calls the endpoint with a harvested `orderId` (see C-2) and `cancelledBy: "staff:x"` — the order cancels regardless of its kitchen status, with no authentication at all.
**Fix:** Require `isPrivileged` (admin/staff token) for every cancellation *unless* `cancelledBy === "customer"` **and** `order.browser_id === req.headers.get("x-browser-id")`.

### C-2. Realtime broadcast channels are unauthenticated and joinable by anyone
**Files:** `website/src/admin/pages/OrdersPage.tsx:34-44`, `website/src/App.tsx:104-117`, `supabase/migrations/0001_initial_schema.sql:3-5`, `supabase/functions/orders/index.ts:233-243`
The schema comment states Realtime "uses Broadcast channels, not table-change streams, so strict RLS does not block the app" — meaning there is genuinely no authorization layer on these channels. The Supabase URL and anon/publishable key are, by design, embedded in the shipped website JS bundle. Anyone can open a browser console anywhere in the world, import `@supabase/supabase-js`, connect with that same public key, and subscribe directly to `admin-orders` — receiving every new order broadcast in real time (table, items, notes, totals) with zero login.
**Failure scenario:** Combined with C-1, an attacker subscribes to `admin-orders` to harvest live `orderId`s, then mass-cancels in-flight orders across the café, disrupting kitchen operations during service.
**Fix:** Configure these as Supabase **private Realtime channels** with an authorization policy, or gate the data behind a signed/short-lived token issued only to authenticated device/staff sessions. Verify the current Supabase project setting (this is a dashboard-level toggle not visible in the repo).

### C-3. Superadmin registration is fully open — no invite, approval, or role table
**Files:** `website/src/admin/pages/RegisterPage.tsx:29-33`, `supabase/functions/_shared/auth.ts:31-45`, `supabase/migrations/0001_initial_schema.sql` (no owner/role table exists)
`RegisterPage` calls `supabase.auth.signUp` directly with no invite token, no admin-approval step, no domain allowlist. Server-side, `verifySuperadminJwt` only checks "is this a currently-valid Supabase Auth user" — there's no permissions/role table anywhere in the schema binding a user to a specific café or restricting what a freshly-created account can do. The only thing preventing "random visitor signs up and becomes superadmin" is whichever way the Supabase project's dashboard "allow public signups" toggle happens to be set — not enforced or even visible in this repo.
**Failure scenario:** Anyone visits `/admin/register`, creates an account, logs in, and immediately has access to revenue metrics, monthly financial reports, device management, and — most damaging — the rotating device-pairing key (`SetupPage.tsx`) that lets them pair a rogue phone as the trusted Admin POS device and take over kitchen/payment operations.
**Fix:** Gate registration behind an invite token (mirror the existing pattern in `supabase/functions/register/index.ts` for ordering devices), disable public signups at the Supabase Auth project level, and add a `superadmins`/ownership table that `verifySuperadminJwt` actually checks against.

### C-4. Aggregates/reporting pipeline writes to non-existent columns — reports are broken
**Files:** `supabase/functions/aggregates/index.ts:40-54` vs. `supabase/migrations/0001_initial_schema.sql:136-145`
The `aggregates` table defines `payment_split_json` / `top_items_json`; the function upserts `payment_split` / `top_items_per_category`. PostgREST rejects unknown columns, so every write 500s. `metrics`, `reports-closing`, and `reports-monthly` all read from this table — the entire dashboard history/reporting feature is non-functional as shipped, and no existing test caught it (tests exercise extracted pure logic, not the deployed handler).
**Fix:** Align the JS keys with the actual schema column names, and add an integration test that hits the real column names.

---

## 3. High findings

| # | Finding | File(s) | Area |
|---|---|---|---|
| H-1 | `admin-handshake` rotating-key endpoint has no rate limiting/lockout — brute-forceable within a rotation window, and a successful guess = full admin device takeover | `supabase/functions/admin-handshake/index.ts:14-38`, `_shared/rotating-key.ts` | Backend |
| H-2 | `devices-status` polling endpoint is fully unauthenticated and hands back a one-time ordering API key to anyone who has/guesses the `deviceId` | `supabase/functions/devices-status/index.ts:12-75` | Backend |
| H-3 | Website/device auth models are split and inconsistent — dashboard calls only use device-token auth (`verifyAdminToken`), never the superadmin JWT the website actually authenticates with; this is an authorization design gap, not just style | `supabase/functions/orders/index.ts:59` and similarly across `menu`, `branding`, `cafe-location`, `aggregates`, `sessions`, `orders-status/items/kitchen/payment` | Backend + Website |
| H-4 | Kitchen-slip/receipt printing embeds unsanitized customer/staff free text (item notes, names) directly into the ESC/POS markup parser — attacker-controlled `[`, `<img>`, `<qrcode>` sequences are interpreted as print formatting, not literal text | `apk/.../printing/documents/KitchenSlipDocument.kt:57-59,124-127`, `ReceiptDocument.kt:78-82` | Android |
| H-5 | `.kiro/specs/*.md` (untracked, not gitignored) has materially diverged from and is more current than the tracked `specs/*.md` — missing Thai language support, an entire WAF/hardening section, all task-completion status, and a whole "Demo Mode" feature that has matching code on disk but no home in tracked specs | `.kiro/specs/*`, `specs/*` | Docs/CI |
| H-6 | *(merged into C-1)* — the website audit independently confirmed the same `orders-cancel` ownership gap from the client side | `website` cross-check | Backend + Website |

---

## 4. Medium findings

| # | Finding | File(s) |
|---|---|---|
| M-1 | `attendance` — `forced: true` (meant to be an admin-only GPS bypass per the schema comment) is actually usable by any authenticated staff device | `supabase/functions/attendance/index.ts:45-87` |
| M-2 | Nearly every Edge Function returns raw Postgres/PostgREST error messages to the client, leaking schema/internal details | `aggregates`, `branding`, `cafe-location`, `devices`, `orders*`, `sessions`, `settings`, `invite` (see full agent output for line numbers) |
| M-3 | TOCTOU race in payment/status transitions — no unique constraint on `payment_transactions.order_id`, no conditional `WHERE status = ...` guard; concurrent taps can double-record revenue | `supabase/functions/orders-payment/index.ts:62-117` |
| M-4 | Non-constant-time secret comparisons for invite tokens and rotating keys | `supabase/functions/register/index.ts:39`, `_shared/rotating-key.ts:67` |
| M-5 | `branding` logo upload has no size cap and hardcodes content-type without validating the actual image signature | `supabase/functions/branding/index.ts:65-95` |
| M-6 | `browserId` is an unsigned, client-generated bearer credential used for order-visibility/cancel decisions — readable by any script, no HMAC/integrity check | `website/src/lib/browserId.ts`, `supabase/functions/tables-session/index.ts:52-58` |
| M-7 | Wildcard CORS (`Access-Control-Allow-Origin: *`) on every Edge Function | `supabase/functions/_shared/cors.ts:6-11` |
| M-8 | No rate limiting/CAPTCHA on website registration or password-reset forms — compounds C-3 | `website/src/admin/pages/RegisterPage.tsx`, `ForgotPasswordPage.tsx` |
| M-9 | Admin "lock" screen requires no PIN/biometric re-entry — one tap fully restores admin session from a physically-accessible locked device | `apk/.../ui/screens/AdminLockScreen.kt:18-47` |
| M-10 | GPS attendance uses only cached/last-known location with no mock-location or freshness check; checkout silently posts `(0,0)` if location is unavailable | `apk/.../ui/util/GpsHelper.kt:28-64`, `OrderingViewModel.kt:161-206,246-248` |
| M-11 | Full local business-data backup export is unencrypted, one-tap shareable JSON (all orders, revenue, printer MACs) | `apk/.../data/local/DatabaseBackupManager.kt:32-162`, `BackupViewModel.kt:136-142` |
| M-12 | `androidx.security.crypto` (used for the encrypted token store) is pinned to a years-stale alpha release using the deprecated `MasterKeys` API | `apk/gradle/libs.versions.toml:13`, `data/SecureStorage.kt:47` |
| M-13 | Release APK builds ship with R8/ProGuard minification disabled — trivially decompilable | `apk/app/build.gradle.kts:56-60`, `proguard-rules.pro` |
| M-14 | Bluetooth printer library is sourced from JitPack (builds from GitHub at resolution time), not a vetted Maven Central artifact | `apk/settings.gradle.kts:19`, `build.gradle.kts:105` |
| M-15 | CI workflows (`ci.yml`, `keep-alive.yml`) declare no explicit `permissions:` block (implicit/default token scope); third-party Actions (`android-actions/setup-android`, `cloudflare/wrangler-action`, `softprops/action-gh-release`) are pinned to mutable tags, not commit SHAs; no Dependabot config exists for Actions or app dependencies | `.github/workflows/*.yml` |

*(M-2 and M-7 were each independently flagged by both the backend and website audits — consolidated here.)*

---

## 5. Low / informational findings

- **L-1.** `menu`/`aggregates` PUT endpoints accept loosely-typed JSON with minimal shape validation (fails safe by coercing bad values to `0`, but should reject up front). `supabase/functions/menu/index.ts:76-77`, `aggregates/index.ts`.
- **L-2.** `settings.reportEmail` has no email-format validation. `supabase/functions/settings/index.ts:185-188`.
- **L-3.** Device revoke/approve state transitions aren't guarded against re-approving an already-revoked device. `supabase/functions/devices/index.ts`.
- **L-4.** README still lists "Gap B — encrypted token storage" as an open item; it appears to already be implemented in `SecureStorage.kt` — update the README so it doesn't mislead future reviewers.
- **L-5.** `RealtimeService`/`OrderingForegroundService`/`OrderingViewModel` bypass Hilt via a hand-rolled service locator, inconsistent with the rest of the app's DI.
- **L-6.** `PrinterDispatcher.connectAndPrint()` is a stub that always throws — the "production" print path can't currently succeed on-device; only the explicitly-marked spike code has a working implementation. Confirm this is tracked as an open task, not assumed done.
- **L-7.** No certificate pinning on Supabase HTTPS/WebSocket traffic (defense-in-depth only, not a defect — cleartext is already correctly blocked).
- **L-8.** `FileProvider` cache-path maps the entire app cache directory rather than a scoped subdirectory.
- **L-9.** Several Android dependencies (AGP 8.7.2, Compose BOM 2024.10, Room 2.6.1, OkHttp 4.12.0) are 1.5+ years stale relative to the compileSdk 36 target.
- **L-10.** Viewport meta tag disables pinch-zoom (`maximum-scale=1.0`) — an accessibility regression (WCAG 1.4.4). `website/index.html:5`.
- **L-11.** No top-level React error boundary — an uncaught render error produces a blank screen instead of a fallback UI.
- **L-12.** Admin phone-number/settings "confirm password" reauth silently refreshes the session as a side effect and conflates wrong-password with network errors. `website/src/admin/pages/SettingsPage.tsx:98-113`.
- **L-13.** `dangerouslySetInnerHTML` used once, for QR-code SVG rendering — not exploitable today (geometry, not text echo) but worth keeping an eye on if the input model changes. `website/src/admin/pages/QrSheetsPage.tsx:171`.
- **L-14.** Vite pinned to `^5.3.4`, predating some dev-server-only security fixes — low impact since production is a static Cloudflare Pages build, but worth bumping.
- **L-15.** Release-signing GitHub Secrets (`KEYSTORE_BASE64`, etc.) required by `release-apk.yml` are never documented in `DEPLOYMENT.md`/`ops-runbook.md`.
- **L-16.** Minor doc drift: `supabase/README.md` refers to `keepalive.yml`; the actual file is `keep-alive.yml`. `keep-alive.yml` also only hard-fails when *both* health-check endpoints fail, so a single broken Edge Function could go unnoticed.

---

## 6. What's done well

Worth preserving through any remediation work:

- **RLS is enabled on every table** with a deny-by-default posture — a real backstop if any client ever hit PostgREST directly.
- **No plaintext secrets in the database** — session/API tokens are SHA-256 hashes of high-entropy random values.
- **Server-side re-pricing everywhere** — client-submitted prices are never trusted; totals are computed from the authoritative menu snapshot, and this is covered by tests.
- **Solid DB-level race protection** — partial unique indexes (`one_active_order_per_table`, `one_live_admin`) correctly handle concurrency that application logic alone would miss.
- **`SecureStorage.kt` (Android)** is a genuinely careful implementation — encrypted at rest, with defensive handling of known OEM Keystore-corruption bugs (Xiaomi/Samsung) that gracefully falls back to re-auth instead of crashing.
- **Manifest hygiene is strong** — minimal, justified permissions, `allowBackup=false`, all non-launcher components correctly `exported=false`, no cleartext traffic allowed.
- **No `service_role` key anywhere in client code** (Android or website) — only the anon/publishable key ships client-side, which is the correct pattern.
- **Enumeration-safe password reset copy**, consistent accessibility basics (aria-labels, touch targets, live regions) across the website, and complete i18n key parity across all five locale files.
- **Signed-release CI hygiene** — the keystore is decoded at build time and explicitly deleted afterward with `if: always()`; secrets are never echoed to logs.
- **Rate limiting already exists** on order creation (IP+browserId sliding window) — the missing piece is extending the same pattern to registration/cancel/rotating-key endpoints (see H-1, C-3, M-8).

---

## 7. Recommended remediation order

1. **Rotate the Supabase secrets** that were sitting in `supabase_k` (service_role key, legacy JWT secret, DB password) — precautionary, since the file's exposure history outside this repo can't be verified.
2. **Fix C-1 / C-4 immediately** — both are small, surgical code changes (an ownership check; a column-name rename) with outsized real-world impact (order cancellation abuse; broken reporting).
3. **Address C-2 and C-3** — these need a decision (Realtime private-channel config, invite-gated registration + role table) rather than a one-line fix; scope as a short design task before implementing.
4. **H-1/H-2** (rate limiting + unauthenticated key-fetch) and **H-4** (print-markup sanitization) — bounded, well-understood fixes.
5. **H-5** — reconcile `.kiro/specs/` against `specs/`, decide which is canonical, and gitignore or merge the other before it's accidentally committed.
6. Work through the Medium list opportunistically — several (M-2, M-7) are one shared helper-function fix applied consistently across all Edge Functions.
7. Low-severity/code-quality items as ordinary backlog.

---

## Appendix — files reviewed

- **Backend:** `supabase/migrations/0001_initial_schema.sql`; every function under `supabase/functions/*/index.ts` and `supabase/functions/_shared/*.ts`; `supabase/apply-migration.mjs`; `supabase/functions/tests/*`; `supabase/.env.example`, `supabase/README.md`.
- **Android:** `apk/app/src/main/AndroidManifest.xml`, `build.gradle.kts`, `proguard-rules.pro`, `libs.versions.toml`, and the full `data/`, `di/`, `printing/`, `realtime/`, `ui/` source trees.
- **Website:** `website/src/**` (customer SPA, `admin/`, `lib/`), `package.json`, `vite.config.ts`, `wrangler.toml(.example)`, `.env(.example/.local)`, `tsconfig.json`, `index.html`.
- **CI/CD & docs:** `.github/workflows/{ci,deploy-website,keep-alive,release-apk}.yml`; `apk/{keystore.properties.example,local.properties.example,gradle.properties}`; `DEPLOYMENT.md`; `docs/{ops-runbook,chaos-testing,field-rehearsal}.md`; `{supabase,website,apk}/README.md`; `.kiro/specs/**` vs. `specs/**`; `.gitignore`.
