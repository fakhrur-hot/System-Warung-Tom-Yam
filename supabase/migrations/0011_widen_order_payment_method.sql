-- ─────────────────────────────────────────────────────────────────────────────
-- 0011: orders.payment_method must accept gateway method codes (task 6/7/8 audit)
--
-- designs.md A4 assumed `orders.payment_method` was already a free-text column whose "value
-- domain widens" to gateway codes like "DUITNOW_QR" or "GRABPAY" with no schema change needed.
-- It is not: 0001_initial_schema.sql typed it as the `payment_method` enum, created with exactly
-- two values, `'CASH'` and `'QR'`. Every gateway completion — `orders-payment` setting
-- `payment_method = 'DUITNOW_QR'` after a successful checkout — would be rejected at the database
-- level with "invalid input value for enum payment_method", not something orders-payment's own
-- validation could catch or explain.
--
-- Widened to `text`. The catalog of valid values now lives entirely in the app
-- (`PaymentMethod.kt`) and in `orders-payment`'s own request validation — not a database
-- constraint — because a café's acquirer-enabled channel list can change without a migration.
--
-- The `payment_method` enum type itself is left in place: `gateway_transactions.payment_method`
-- (renamed from a same-named collision — see 0009's header) was already `text` from the start and
-- is unaffected; nothing else references this enum type after this column changes.
--
-- ### The backup table, and why it is here rather than a pg_dump
--
-- This is the only migration in this project that alters a column on a LIVE trading table, so it
-- should not run unbacked. A full `supabase db dump` needs Docker, which was unavailable on the
-- machine doing the deploy — and waiting on that blocked this migration once already.
--
-- A targeted in-database snapshot removes that dependency: the statement below copies exactly the
-- data this migration touches and nothing else, over the same connection that runs the migration.
-- No Docker, no database password, no external file to misplace.
--
-- The cast itself is lossless — every enum label converts to its own text form — so this guards
-- against operator error and unforeseen interactions, not against the cast. To restore:
--
--   update orders o
--      set payment_method = b.payment_method
--     from _backup_orders_payment_method_0011 b
--    where o.id = b.id;
--
-- It is deliberately NOT dropped automatically: an empty-handed rollback is the exact failure this
-- exists to prevent. Drop it once a café has traded a full day past this migration.
--
-- `orders` holds only ACTIVE rows (settled ones are purged by `purge_settled_orders()`), so both
-- the copy and the ALTER touch a small table and hold their lock momentarily.
-- ─────────────────────────────────────────────────────────────────────────────

create table if not exists _backup_orders_payment_method_0011 as
  select id, payment_method::text as payment_method from orders;

comment on table _backup_orders_payment_method_0011 is
  'Pre-migration snapshot of orders.payment_method (migration 0011). Safe to drop once 0011 has been in production for a full trading day.';

alter table orders
  alter column payment_method type text using payment_method::text;
