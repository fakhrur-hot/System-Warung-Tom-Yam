# Requirements Document

## Introduction

Today, standing up a new café on this system requires manually applying 6 SQL migrations, deploying
27 Supabase Edge Functions, creating a Cloudflare Pages project, and configuring DNS — all by hand,
by someone comfortable with the Supabase CLI and `wrangler`. This spec covers a **Setup Wizard** that
automates that provisioning for a brand-new café owner, while keeping the APK's Setup screen
(`AppConfigStore`/`SetupScreen`, already shipped) as the low-privilege runtime-config surface it is
today — it never gains provisioning powers itself.

**The central constraint driving this design, decided 2026-07-30:** provisioning a fresh Supabase
project requires a Supabase **Personal Access Token**, which is **account-wide** (it can read, modify,
or delete every project on that Supabase account, not just the one being provisioned). That token
must never be stored on a physical POS tablet that sits on a café counter — a device far more likely
to be lost, resold, or rooted than a laptop. The owner explicitly chose the **Web Setup Wizard**
model over in-app provisioning for this reason: high-privilege tokens are entered once, in a
browser, used immediately, and never persisted; the tablet only ever receives and stores the same
low-privilege values it stores today (Supabase anon key, project URL, website URL).

## Glossary

- **Wizard**: A standalone, RAZStudio-operated web tool (not per-café) that a new café owner visits
  once, in a desktop/laptop browser, to provision their own Supabase project, Edge Functions, and
  Cloudflare Pages site + DNS. Separate from any café's own customer-facing website because it must
  run *before* that website exists.
  code and never written to a database, log, or file — see Requirement R6.
- **High-Privilege Credential**: A Supabase Personal Access Token or a Cloudflare API token scoped to
  create/modify resources. Contrast with **Low-Privilege Credential**: the Supabase anon key and
  project/website URLs, safe to store on-device because Row Level Security gates everything behind
  the anon key.
- **Skeleton**: The reusable, café-agnostic artifacts already in this repo — `supabase/migrations/*.sql`,
  `supabase/functions/*`, and the built `website/dist` — that the Wizard applies to a new owner's
  empty accounts.
- **Handoff**: The final Wizard step: after provisioning succeeds, the Wizard displays the new
  project's anon key + Supabase URL + website URL for the owner to type into the APK's existing Setup
  screen (or a QR code encoding them, to avoid transcription error).

## Requirements

### Requirement R1 — The Wizard is a separate, reusable tool

**User Story:** As RAZStudio, I want one Wizard I can point at any brand-new café's empty accounts, so
that onboarding a café doesn't require per-café custom tooling.

#### Acceptance Criteria

1. THE Wizard SHALL be a distinct deployable (its own repo folder, its own Cloudflare Pages project),
   NOT part of any café's own `website/` deployment — a new café has no website yet when the Wizard
   runs.
2. THE Wizard SHALL be reachable at a stable RAZStudio-operated URL, independent of which café is
   currently being onboarded.
3. THE Wizard SHALL require the operator to be a RAZStudio operator or the café owner acting under
   RAZStudio's guidance — it is not a public self-serve signup flow in this iteration (no auth system
   is in scope; access control is "share the URL," matching the current invite-link trust model).

### Requirement R2 — Supabase schema provisioning

**User Story:** As someone onboarding a new café, I want the Wizard to apply the database schema to
the owner's empty Supabase project, so that I never hand-run `apply-migration.mjs` again.

#### Acceptance Criteria

1. WHEN given a Supabase project ref and a Personal Access Token, THE Wizard SHALL apply
   `supabase/migrations/0001` through the latest migration, in filename order, via the Supabase
   Management API.
2. THE Wizard SHALL detect and report a migration that fails (e.g., the project already has some
   tables) rather than silently continuing, since these migrations are first-run-only (`create
   table`/`create type` error on a second run — see `supabase/apply-migration.mjs`'s existing note).
3. THE Wizard SHALL show which migrations succeeded and which failed/were skipped, so a partial
   failure is diagnosable without re-running blind.

### Requirement R3 — Supabase Edge Function deployment

**User Story:** As someone onboarding a new café, I want the Wizard to deploy all 27 Edge Functions to
the owner's project, so that the backend is immediately callable by the APK and website.

#### Acceptance Criteria

1. THE Wizard SHALL deploy every function under `supabase/functions/` (excluding `_shared`, which is
   an import, not a function) to the target project via the Supabase Management API.
2. THE Wizard SHALL report per-function success/failure, since one broken function must not be
   allowed to silently block the other 26 from deploying.
3. THE Wizard SHALL be re-runnable against a project that already has some functions deployed
   (update semantics), so a partial-failure retry does not require a full teardown.

### Requirement R4 — Cloudflare Pages + DNS provisioning

**User Story:** As someone onboarding a new café, I want the Wizard to stand up the customer-ordering
website and point a domain at it, so the café's QR cards have somewhere to link to.

#### Acceptance Criteria

1. THE Wizard SHALL create a Cloudflare Pages project in the owner's account and upload the built
   `website/dist` output (built with that café's `VITE_SUPABASE_URL`/`VITE_SUPABASE_PUBLISHABLE_KEY`,
   matching today's GitHub Actions build step) via the Cloudflare API.
2. WHEN the owner supplies a custom domain, THE Wizard SHALL create the corresponding DNS record via
   the Cloudflare API; WHEN they don't, the default `<project>.pages.dev` SHALL be used (matching
   today's manual flow).
3. THE Wizard SHALL surface the resulting live URL for use as the APK's `WEBSITE_URL` value.

### Requirement R5 — Credential handling (the security-critical requirement)

**User Story:** As the café owner, I want my Supabase and Cloudflare tokens to never end up stored on
my POS tablet or in any RAZStudio-operated database, so a lost or rooted tablet can't leak
account-wide access to my cloud accounts.

#### Acceptance Criteria

1. High-Privilege Credentials (Supabase PAT, Cloudflare API token) SHALL exist only in browser memory
   on the Wizard page and in the request body of the provisioning calls the Wizard's backend makes on
   the operator's behalf — THE Wizard's backend SHALL NOT write them to a database, log, or file at
   any point.
2. THE Wizard's backend SHALL hold each credential only for the duration of the HTTP request that
   uses it, and SHALL NOT cache or persist it across requests.
3. THE APK's `SetupScreen`/`AppConfigStore` SHALL remain unchanged by this spec — it SHALL continue to
   store only Low-Privilege Credentials (Supabase anon key, Supabase URL, website URL, café name) and
   SHALL NOT gain any field, code path, or dependency that touches a Supabase PAT or Cloudflare API
   token.
4. THE Wizard's transport SHALL be HTTPS-only; the token fields in its UI SHALL be masked
   (password-style inputs), matching the existing `SetupScreen`'s treatment of secret fields.

### Requirement R6 — Idempotency and partial-failure recovery

**User Story:** As the person running the Wizard, I want to re-run it after a failure without
worrying it will double-apply anything, so a flaky network call mid-provisioning isn't a disaster.

#### Acceptance Criteria

1. WHEN re-run against a project that has already had SOME migrations/functions/DNS records applied,
   THE Wizard SHALL skip or update already-provisioned resources rather than erroring the whole run
   (scoped per Requirement R2.2/R3.3's individual reporting).
2. THE Wizard SHALL present one overall run as a checklist of discrete steps (each migration file,
   each function, the Pages project, the DNS record), each independently retryable, rather than one
   opaque "Provision" action that succeeds or fails as a monolith.

### Requirement R7 — Handoff to the existing mobile Setup screen

**User Story:** As the café owner, I want the Wizard to hand me exactly what I need to type into the
tablet, so provisioning and device setup are one smooth flow.

#### Acceptance Criteria

1. ON successful provisioning, THE Wizard SHALL display the Supabase URL, Supabase anon key, and
   website URL for the owner to enter into the APK's existing `SetupScreen`.
2. THE Wizard SHOULD offer a QR code encoding these values so the tablet can scan rather than
   hand-type them — reusing the same `AppConfigStore` fields the Setup screen already persists; this
   is an enhancement to the existing Setup screen's entry methods, not a new storage location.

### Requirement R8 — Verification is a hard prerequisite, not an afterthought

**User Story:** As the maintainer, I want this spec's implementation actually exercised against a
real (test) Supabase project and Cloudflare account before it's trusted for a real café, because a
bug here can leave a paying customer's project half-migrated or their DNS misconfigured.

#### Acceptance Criteria

1. Implementation of R2–R4 SHALL be verified against a real, disposable Supabase project and
   Cloudflare account/zone — NOT assumed correct from reading API documentation alone, since the
   exact request/response shapes of the Supabase Management API's SQL-execution and function-deploy
   endpoints must be confirmed against their current live behavior before this is relied upon for a
   real onboarding.
2. Until such a test account is available, implementation SHALL proceed in this order: (a) confirm
   the exact Management API contracts via their current docs, (b) build against a disposable test
   project, (c) only then treat the Wizard as usable for a real café.
