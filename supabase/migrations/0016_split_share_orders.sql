-- Split-share orders: a customer paying part of a table's bill is created as its own `orders`
-- row (Split Payment) while the original order is still active on the same table. The existing
-- `one_active_order_per_table` unique index (0001) makes that structurally impossible — a split
-- share's create always 409s with TABLE_OCCUPIED, and the DB would reject the insert even if the
-- app-level check were skipped. This carves out an explicit, narrow exception for split shares
-- only; every other order on a table is still capped at one.

alter table orders add column is_split_share boolean not null default false;

drop index one_active_order_per_table;
create unique index one_active_order_per_table
  on orders (table_id)
  where status not in ('COMPLETED', 'CANCELLED') and not is_split_share;
