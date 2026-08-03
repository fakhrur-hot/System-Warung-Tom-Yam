-- Gateway payment attempts. (PG-REQ-5, PG-REQ-7, task 5.4)
--
-- Named `gateway_transactions`, NOT `payment_transactions`. 0001_initial_schema.sql already creates
-- a table called `payment_transactions` — a much simpler one-row-per-completed-order CASH/QR audit
-- log, written by orders-payment, with a `method payment_method` enum column that only accepts
-- 'CASH'/'QR'. A `create table if not exists payment_transactions (...)` here would silently no-op
-- against that existing table on every café's database: no error, no new columns, and this ledger's
-- `status`, `idempotency_key` and `gateway_transaction_id` would simply never exist. Found while
-- building task 6.2's Edge Functions, before it shipped to a real café database.
--
-- The Android Room entity keeps the name `payment_transactions` — it lives in a separate SQLite
-- database that has no such collision, so only the Postgres side needed the rename.
--
-- Cash and static-QR settlements do not appear here — they have no gateway leg and orders.payment_method
-- already records them. This table exists for payments that can be pending, fail, time out, or need
-- reconciling against an acquirer's statement.
--
-- This row is the source of truth once the day is over, not the gateway. The evaluated aggregator's
-- status requery is documented as returning "no result available for transactions more than 1 day
-- or 24 hours", so anything that re-derives payment state by asking the gateway starts returning
-- nothing the next morning. The callback writes here the moment it lands and every later reader
-- uses this table.

create table if not exists gateway_transactions (
  id                      uuid primary key default gen_random_uuid(),
  order_id                uuid not null references orders(id) on delete cascade,

  payment_method          text not null,

  -- Integer sen, never a float. 19.99 is not representable in binary floating point, and a gateway
  -- that receives 19.989999999999998 either rejects it or settles a different amount than the
  -- receipt shows. The APK converts once, at PaymentTransaction.fromRinggit.
  amount_sen              bigint not null check (amount_sen >= 0),

  status                  text not null
                            check (status in ('PENDING','SUCCESS','FAILED','CANCELLED','TIMEOUT','REFUNDED')),

  gateway_transaction_id  text,

  -- Kept verbatim for disputes. Never parsed to derive state — `status` is the state.
  gateway_response_json   jsonb,

  -- Derived from (order_id, amount_sen), NOT from a per-attempt id: a key minted fresh each retry
  -- is the opposite of idempotent. The unique index is what makes a double-charge a database error
  -- rather than a silent second payment, so the guarantee does not depend on client discipline.
  idempotency_key         text not null,

  is_sandbox              boolean not null default false,

  created_at              timestamptz not null default now(),
  settled_at              timestamptz
);

comment on table gateway_transactions is
  'Gateway payment attempts. Authoritative after the acquirer''s 24-hour requery window closes.';
comment on column gateway_transactions.amount_sen is
  'Amount in sen (integer). Never store money as a float.';
comment on column gateway_transactions.idempotency_key is
  'Stable per (order, amount) so a retry replays the same key at the gateway instead of charging twice.';

create unique index if not exists gateway_transactions_idempotency_key_idx
  on gateway_transactions (idempotency_key);

create index if not exists gateway_transactions_order_id_idx
  on gateway_transactions (order_id);

-- Reporting reads one day at a time and only ever cares about money that actually arrived.
create index if not exists gateway_transactions_created_at_idx
  on gateway_transactions (created_at desc)
  where status = 'SUCCESS' and is_sandbox = false;

alter table gateway_transactions enable row level security;

-- Writes come from Edge Functions using the service role, which bypasses RLS. No anon or
-- authenticated policy grants insert or update here: a client that could write this table could
-- mark its own order paid, which is the one thing this table must make impossible.
--
-- Reads are not granted to anon either. A customer's web status page needs to know an order is
-- paid, and it learns that from orders.status — it has no business seeing gateway transaction ids
-- or raw acquirer payloads.
