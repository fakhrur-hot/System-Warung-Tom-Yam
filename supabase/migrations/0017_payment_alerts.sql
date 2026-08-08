-- Payment alerts forwarded between admin devices.
--
-- ## What this is for
--
-- A café's bank/e-wallet notifications land on whichever phone holds the banking app. That is not
-- always the till: it is very often the owner's own phone, which runs as a Secondary Admin. The
-- till (Main Admin) is the device that holds the printer and does the order matching, so a payment
-- captured on the Secondary Admin has to reach it somehow.
--
-- This table is that hop. The Secondary Admin POSTs a captured notification here; the Main Admin
-- drains it on the catch-up poll it already runs, then matches it to an open order exactly as if it
-- had captured the notification itself.
--
-- ## Why a table rather than a Realtime broadcast
--
-- `PaymentAlertBroadcaster` was originally written to push this over Supabase Realtime, and that
-- branch was never implemented. In this deployment Realtime delivers no broadcast frames at all —
-- every live feature here rides the poll instead. A row in a table survives a device being asleep,
-- backgrounded or out of signal at the moment of capture, which a fire-and-forget socket frame does
-- not; for something that decides whether a customer is recorded as having paid, durability is the
-- point.
--
-- ## Why not reuse an existing table
--
-- `payment_transactions` already exists here and means something else entirely (a settled payment
-- against an order). Room separately has its own `captured_payments` in SQLite. Both names are
-- taken, and `create table if not exists` against an occupied name silently no-ops and leaves the
-- new columns missing — which is the exact failure 0009 documents. Hence a distinct name.

create table if not exists payment_alerts (
  id                uuid primary key default gen_random_uuid(),
  -- The capturing device's own row id for this notification. Carried across so the receiver can
  -- recognise a payment it has already ingested: a poll cursor can be replayed (app killed before
  -- it persisted), and re-matching the same payment would credit an order twice.
  client_id         text not null,
  amount_sen        bigint not null check (amount_sen >= 0),
  wallet_app        text not null,
  sender            text,
  raw_text          text not null,
  captured_at       timestamptz not null,
  -- Which device captured it. Kept for audit: "the owner's phone saw this, not the till."
  source_device_id  uuid references devices(id) on delete set null,
  created_at        timestamptz not null default now()
);

comment on table payment_alerts is
  'Bank/e-wallet notifications captured on one admin device and forwarded to the Main Admin till.';
comment on column payment_alerts.amount_sen is
  'Amount in sen (integer). Never store money as a float.';
comment on column payment_alerts.client_id is
  'The capturing device''s local id for this capture — the receiver de-duplicates on it.';

-- One row per capture, however many times the device retries the POST. The forwarding call is
-- fire-and-forget from a notification listener, so a retry after a flaky response is expected.
create unique index if not exists payment_alerts_client_id_idx
  on payment_alerts (client_id);

-- The Main Admin drains this with `created_at > since`, newest last.
create index if not exists payment_alerts_created_at_idx
  on payment_alerts (created_at);

alter table payment_alerts enable row level security;

-- No anon or authenticated policy on purpose: every read and write goes through an Edge Function
-- using the service role. A client that could write this table could announce a payment that never
-- happened and have the till match it to a real order.
