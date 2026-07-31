-- ─────────────────────────────────────────────────────────────────────────────
-- 0007: Payment QR on the branding row (task 16.2, Requirements 14.5, 14.6, 14.8)
--
-- The café's static payment QR is an image the admin uploads once and every device that takes
-- payment then shows on request. Two columns, alongside the existing logo:
--
--   payment_qr_url   public Storage URL of the image, or NULL when not configured
--   payment_qr_hash  SHA-256 hex of the stored bytes
--
-- Why a hash and not just the URL. The object lives at a fixed key ("payment-qr" in the logos
-- bucket), exactly as logo.jpg does, so the URL is identical before and after a replacement. A device
-- caching by URL could therefore keep serving the PREVIOUS payee indefinitely — and a stale payment
-- QR sends a customer's money to the wrong account, with no app-side transaction record to catch it
-- afterwards. The hash lets a device tell "unchanged" from "replaced" without downloading the image
-- to find out, and a byte-identical re-upload produces no churn.
--
-- The existing logo has the same problem and solves it differently, by appending
-- ?v=<updated_at> in the branding Edge Function. That works for a picture whose exact bytes do not
-- matter; it is not enough here, because the consequence of being wrong is money going astray rather
-- than a slightly old graphic.
--
-- Additive and idempotent: NULL columns on a single-row table, so this is safe to re-run and safe on
-- a database that already holds real branding.
-- ─────────────────────────────────────────────────────────────────────────────

alter table branding add column if not exists payment_qr_url  text;
alter table branding add column if not exists payment_qr_hash text;

comment on column branding.payment_qr_url is
  'Public Storage URL of the café''s static payment QR image, or NULL when not configured.';
comment on column branding.payment_qr_hash is
  'SHA-256 hex of the stored payment QR bytes. Devices cache on this, not the URL, because the URL is '
  'stable across replacements and a stale QR would pay the previous account.';
