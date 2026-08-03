-- Server-side payment-gateway credentials, one row per café. (PG-REQ-2, PG-REQ-8, task 6.2)
--
-- `GatewayCredentialStore` on the Android admin device is where an owner types these in, and where
-- the secret is masked back on re-entry (see that class's `readSecretsForUpload`, which exists to
-- ship them here once over TLS). This table is where payment-initiate and payment-query actually
-- read them from when signing a gateway request — the point of A2/F3: computing a signature with
-- the merchant secret must happen server-side, never on a café's device.
--
-- Populated by the admin settings screen (task 7.1, not yet built) via a service-role-only write
-- path. Until then this table stays empty and payment-initiate fails closed with
-- GATEWAY_NOT_CONFIGURED — the correct behaviour for a café that has not signed up with an
-- acquirer yet.
--
-- No policy grants select/insert/update to anon or authenticated. Only the service role used inside
-- Edge Functions can touch this table — a merchant secret leaking through PostgREST would be worse
-- than the gateway being briefly unconfigured.

create table if not exists gateway_config (
  id              int primary key default 1,

  -- Not secret — the evaluated aggregator puts it in the payment URL path itself (designs.md F2).
  merchant_id     text,

  -- Secret. Hashes callback signatures and requeries (designs.md F3). Never returned to any
  -- client — Edge Functions read it directly with the service role.
  verify_key      text,
  secret_key      text,

  -- Sandbox is a different HOST at the evaluated aggregator, not just different credentials
  -- (designs.md F2) — the Edge Functions branch on this when building request URLs.
  is_sandbox      boolean not null default true,

  -- PaymentMethod.code values (apk/data/local/PaymentMethod.kt) this café has enabled.
  enabled_methods text[] not null default '{}',

  updated_at      timestamptz not null default now(),

  constraint gateway_config_singleton check (id = 1)
);

comment on table gateway_config is
  'Single-row payment-gateway credentials, service-role only. Populated by the admin settings screen (task 7.1).';

alter table gateway_config enable row level security;
