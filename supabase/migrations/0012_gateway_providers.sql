-- Multi-provider gateway credentials. (PG-REQ-2, PG-REQ-8)
--
-- Supersedes `gateway_config` (0010), which held ONE merchant_id/verify_key/secret_key and so
-- assumed a single aggregator covering every channel. That holds for an aggregator like Fiuu, but
-- not for the integration path this café is actually pursuing: Touch 'n Go direct and DuitNow via
-- an acquiring bank are two separate merchant relationships, with different credential shapes
-- (TNG: merchant id + verify/secret key; a bank's DuitNow rail: OAuth client id + secret) and a
-- separate callback URL each. One row of three fixed columns cannot represent that.
--
-- `gateway_config` is deliberately LEFT IN PLACE and not dropped: the currently-installed APK
-- still reads it through the `gateway-config` endpoint, and dropping it would turn a working
-- settings page back into an error. It becomes dead once the app moves to `gateway-providers`.
--
-- ### Why credentials are jsonb rather than columns
--
-- Each provider names its own fields, and the real field names are only known after merchant
-- onboarding — that is the whole reason this table exists. Fixed columns would mean a migration
-- every time a provider is added, which is precisely the coupling that made 0010 wrong. The
-- adapter declares its own field spec (see `_shared/gateway-registry.ts`) and validates against it.
--
-- Secrets sit in this jsonb. That is no weaker than 0010's columns were: RLS below denies every
-- role, and only Edge Functions using the service role (which bypasses RLS) can read it. Nothing
-- ever returns a credential VALUE to a client — `gateway-providers` reports only which keys are
-- set, exactly as the old endpoint reported hasVerifyKey/hasSecretKey.

create table if not exists gateway_providers (
  -- Adapter key: 'fiuu' | 'touchngo' | 'duitnow' | … Matches GatewayAdapter.provider.
  provider          text primary key,

  -- Provider-specific credentials, e.g. {"merchantId": "...", "secretKey": "..."}. Shape is
  -- declared and validated by that provider's adapter, never by this schema.
  credentials       jsonb not null default '{}'::jsonb,

  -- Channel identifiers in the PROVIDER's own vocabulary. Not validated here: an earlier version
  -- of gateway-config checked these against a hardcoded Fiuu channel map, which would reject a
  -- channel a café had genuinely been onboarded for under a different provider.
  enabled_methods   text[] not null default '{}',

  -- Sandbox is per provider: a café can be live on one rail while still testing another.
  is_sandbox        boolean not null default true,

  -- Off by default. A provider with credentials saved but not yet switched on must not appear at
  -- the counter — onboarding is usually approved before the café is ready to take live payments.
  is_enabled        boolean not null default false,

  updated_at        timestamptz not null default now()
);

comment on table gateway_providers is
  'One row per payment provider the café is onboarded with. Credentials are service-role only and never returned to a client.';
comment on column gateway_providers.credentials is
  'Provider-specific credential fields as jsonb. Field names come from the adapter spec, known only after merchant onboarding.';
comment on column gateway_providers.enabled_methods is
  'Channel identifiers in the provider''s own vocabulary. Deliberately not constrained — each provider names channels differently.';

alter table gateway_providers enable row level security;

-- No policy for anon or authenticated, matching gateway_config and payment/gateway_transactions:
-- a client that could read this table would hold the café's merchant secrets, and one that could
-- write it could point payments at an attacker's merchant account.
