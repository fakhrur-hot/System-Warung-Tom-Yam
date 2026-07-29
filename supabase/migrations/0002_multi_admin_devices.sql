-- Allow up to MAX_ADMIN_DEVICES (enforced in admin-handshake Edge Function, not here)
-- approved admin devices instead of exactly one. Previously a partial unique index
-- forced a hard cap of 1, which meant a stale/replaced device row had to be manually
-- deleted from the database before any other device (or a reinstalled app) could ever
-- claim the admin role again. Multiple simultaneous admin devices is now an accepted
-- dev/testing convenience — each runs its own RealtimeService/printer independently,
-- so a real multi-device deployment could double-print the same kitchen slip; that
-- tradeoff is accepted for now rather than building a printer-owner/viewer distinction.
drop index if exists one_live_admin;
