-- System Warung Tom Yam — initial schema (Task 5)
-- Postgres / Supabase. The backend (Edge Functions) uses the service_role key and so
-- bypasses RLS; RLS is enabled with NO anon/authenticated policies (deny-by-default) as
-- defence in depth. Realtime uses Broadcast channels, not table-change streams, so strict
-- RLS does not block the app.

-- ── Extensions ─────────────────────────────────────────────────────────────
create extension if not exists pgcrypto;   -- gen_random_uuid()
create extension if not exists pg_cron;     -- scheduled purge (enable in Supabase dashboard)

-- ── Enums ──────────────────────────────────────────────────────────────────
create type device_role     as enum ('ADMIN', 'ORDERING');
create type device_status   as enum ('PENDING', 'APPROVED', 'REVOKED');
create type order_status     as enum ('RECEIVED','SENT_TO_KITCHEN','PREPARING','READY','COMPLETED','CANCELLED');
create type order_source     as enum ('QR', 'STAFF');
create type payment_method   as enum ('CASH', 'QR');
create type attendance_event as enum ('CHECK_IN', 'CHECK_OUT');
create type session_event    as enum ('OPEN', 'CLOSE');

-- ── Devices ────────────────────────────────────────────────────────────────
create table devices (
  id                 uuid primary key default gen_random_uuid(),
  device_identifier  text not null,                 -- client-generated UUID
  android_id         text,
  device_model       text,
  role               device_role   not null default 'ORDERING',
  status             device_status not null default 'PENDING',
  api_key_hash       text,                           -- ordering role (hashed, never plaintext)
  session_token_hash text,                           -- admin role (hashed)
  label              text,                            -- editable; defaults to device_model
  is_checked_in      boolean not null default false,
  last_seen_at       timestamptz,
  created_at         timestamptz not null default now()
);
-- At most one live admin device (first-claim). Partial unique index.
create unique index one_live_admin
  on devices ((role))
  where role = 'ADMIN' and status = 'APPROVED';
create index devices_status_idx on devices (status);

-- ── Key/value settings ─────────────────────────────────────────────────────
create table settings (
  key   text primary key,
  value text
);

-- ── Tables (the physical stall tables; validates incoming tableIds) ─────────
create table tables (
  id           text primary key,          -- slug, e.g. 'T1'
  display_name text not null,
  created_at   timestamptz not null default now()
);

-- ── Menu snapshot (single row, full multilingual JSON) ──────────────────────
create table menu_snapshot (
  id         int primary key default 1,
  menu_json  jsonb not null default '{"configured":false}'::jsonb,
  updated_at timestamptz not null default now(),
  constraint menu_singleton check (id = 1)
);

-- ── Branding (single row) ──────────────────────────────────────────────────
create table branding (
  id         int primary key default 1,
  cafe_name  text,
  logo_url   text,
  updated_at timestamptz not null default now(),
  constraint branding_singleton check (id = 1)
);

-- ── Café location (single row) ─────────────────────────────────────────────
create table cafe_location (
  id            int primary key default 1,
  latitude      double precision,
  longitude     double precision,
  radius_meters int not null default 100,
  updated_at    timestamptz not null default now(),
  constraint cafe_location_singleton check (id = 1)
);

-- ── Active orders only (transient; history lives on the admin phone) ────────
create table orders (
  id                uuid primary key default gen_random_uuid(),
  table_id          text not null references tables(id),
  source            order_source not null,
  browser_id        text,                       -- customer localStorage UUID (QR orders)
  status            order_status not null default 'RECEIVED',
  payment_method    payment_method,
  sent_to_kitchen_at timestamptz,
  cancel_reason     text,
  cancelled_by      text,                        -- 'admin' | 'staff:<label>' | 'customer'
  items_json        jsonb not null,             -- per line: snapshot + sentToKitchen flag
  total             numeric(10,2) not null default 0,
  created_at        timestamptz not null default now(),
  purge_after       timestamptz                 -- set when COMPLETED/CANCELLED
);
-- One active order per table = the table session.
create unique index one_active_order_per_table
  on orders (table_id)
  where status not in ('COMPLETED','CANCELLED');
create index orders_status_idx on orders (status);
create index orders_created_idx on orders (created_at);

-- ── Payment transactions (append-only; one row per order today) ─────────────
create table payment_transactions (
  id         uuid primary key default gen_random_uuid(),
  order_id   uuid not null references orders(id) on delete cascade,
  method     payment_method not null,
  amount     numeric(10,2) not null,
  created_at timestamptz not null default now()
);

-- ── Admin session open/close events ─────────────────────────────────────────
create table sessions (
  id         uuid primary key default gen_random_uuid(),
  event      session_event not null,
  reason     text,
  closing    boolean not null default false,
  timestamp  timestamptz not null default now()
);
create index sessions_ts_idx on sessions (timestamp);

-- ── GPS attendance ──────────────────────────────────────────────────────────
create table attendance (
  id         uuid primary key default gen_random_uuid(),
  device_id  uuid not null references devices(id) on delete cascade,
  event      attendance_event not null,
  latitude   double precision,
  longitude  double precision,
  forced     boolean not null default false,   -- admin override, no GPS
  timestamp  timestamptz not null default now()
);
create index attendance_device_idx on attendance (device_id, timestamp);

-- ── Daily aggregates (dashboard's only order-history source) ────────────────
create table aggregates (
  date               date primary key,
  total_orders       int not null default 0,
  total_revenue      numeric(12,2) not null default 0,
  avg_order_value    numeric(10,2) not null default 0,
  payment_split_json jsonb not null default '{}'::jsonb,   -- { cash:{count,amount}, qr:{...} }
  cancelled_count    int not null default 0,
  cancelled_value    numeric(12,2) not null default 0,
  top_items_json     jsonb not null default '{}'::jsonb    -- per category top-N
);

-- ── Ordering-role invitation (single active token) ─────────────────────────
create table invites (
  id         int primary key default 1,
  token      text not null,
  rotated_at timestamptz not null default now(),
  constraint invites_singleton check (id = 1)
);

-- ── Seeds ────────────────────────────────────────────────────────────────────
insert into settings (key, value) values
  ('print_language',        'EN'),
  ('timezone',              'Asia/Kuala_Lumpur'),
  ('top_n_items',           '5'),
  ('report_email',          ''),
  ('closing_report_auto',   'true'),
  ('staff_can_send_kitchen','false'),
  ('staff_can_take_payment','false');

insert into menu_snapshot (id) values (1);
insert into branding (id) values (1);
insert into cafe_location (id) values (1);

-- ── Purge job: drop settled orders past their TTL (keeps the backend transient) ──
create or replace function purge_settled_orders() returns void
language sql as $$
  delete from orders
   where status in ('COMPLETED','CANCELLED')
     and purge_after is not null
     and purge_after < now();
$$;

-- Runs every 15 minutes. (pg_cron must be enabled for the project.)
select cron.schedule('purge-settled-orders', '*/15 * * * *', $$select purge_settled_orders()$$);

-- ── Enable RLS everywhere (deny-by-default; service_role bypasses) ──────────
alter table devices              enable row level security;
alter table settings             enable row level security;
alter table tables               enable row level security;
alter table menu_snapshot        enable row level security;
alter table branding             enable row level security;
alter table cafe_location        enable row level security;
alter table orders               enable row level security;
alter table payment_transactions enable row level security;
alter table sessions             enable row level security;
alter table attendance           enable row level security;
alter table aggregates           enable row level security;
alter table invites              enable row level security;
-- No anon/authenticated policies are defined on purpose: all reads/writes go through
-- Edge Functions using the service_role key. See supabase/README.md.
