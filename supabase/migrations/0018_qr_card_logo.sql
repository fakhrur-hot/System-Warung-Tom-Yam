-- ─────────────────────────────────────────────────────────────────────────────
-- 0018: QR-card logo on the branding row
--
-- The Generate Table QR screen lets the admin pick a logo specifically for the printable QR
-- cards, distinct from the café's branding logo. Until now that pick was persisted ONLY on the
-- device's local storage (LogoPipeline.saveQrLogoToInternal) — an app reinstall, or picking up
-- the screen on a second device, lost it. One column, alongside the existing logo/payment QR:
--
--   qr_card_logo_url  public Storage URL of the image, or NULL when not configured
--
-- Versioned the same way the branding logo is (?v=<updated_at> in the Edge Function), not
-- hash-cached like the payment QR — a stale QR-card logo has no financial consequence, unlike a
-- stale payment QR, so the simpler cache-busting scheme already used for the branding logo is
-- enough here too.
--
-- Additive and idempotent: a NULL column on a single-row table, safe to re-run.
-- ─────────────────────────────────────────────────────────────────────────────

alter table branding add column if not exists qr_card_logo_url text;

comment on column branding.qr_card_logo_url is
  'Public Storage URL of the logo picked specifically for printable table-QR cards, or NULL when '
  'the QR-card screen falls back to the branding logo / bundled default.';
