-- Voided order lines — "pay for what you actually got".
--
-- The café hits this several times a service: a customer is told an item never arrived, they are
-- leaving now, and they want to settle only for what reached the table. Before this, the cashier's
-- options were to charge for food the customer never received, or cancel the entire order and lose
-- the whole ticket including everything that WAS served.
--
-- Voided lines are moved out of items_json rather than flagged in place. Every existing consumer of
-- items_json — the kitchen slip, the customer receipt, the web status page, the Kotlin order mapper,
-- the reports' popular-items rollup — sums or lists that array, so a `voided: true` flag would have
-- silently over-counted in every one of them until each was taught the flag. Removing the line keeps
-- all of them correct with no change, and orders.total (which is what the reports actually sum)
-- stays equal to the sum of items_json exactly as orders-items already assumes.
--
-- The removed line is not discarded, because a line that vanishes with no trace is indistinguishable
-- from one that was never ordered: the kitchen cooked it, or was supposed to. Each entry keeps the
-- original line verbatim plus who voided it, when, and why, so the difference between "we billed
-- RM 38" and "the table ordered RM 52" is answerable after the fact.
alter table orders
  add column if not exists voided_items_json jsonb not null default '[]'::jsonb;

comment on column orders.voided_items_json is
  'Audit trail of order lines removed before payment (item never served). Each entry is the original items_json line plus voidedAt, voidedBy and voidReason. Excluded from total.';
