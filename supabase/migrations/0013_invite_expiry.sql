-- Invite tokens expire.
--
-- Until now an invite token was valid forever: `invite/regenerate` minted a new one and the old
-- row was overwritten, but whatever token was current stayed current indefinitely. A QR
-- photographed over a cashier's shoulder, or a printed slip left in a drawer, remained a working
-- key to join the café for as long as nobody happened to regenerate it.
--
-- A short window is what makes an invite safe to show on a screen: it is scanned within a minute of
-- being generated in practice, so a 15-minute life costs the café nothing and closes the window on
-- everything that finds the code later.
--
-- ## Nullable on purpose
--
-- NULL means "never expires", which is what every existing row becomes. Two reasons:
--
--  1. Back-compat. Applying this to a running café must not invalidate the invite currently pinned
--     to its noticeboard, mid-service, with no warning.
--  2. It keeps the enforcement rule expressible as a single comparison in `register` — a row with
--     no expiry is simply never past it — rather than needing a separate "is this an old row?"
--     branch that would have to be maintained forever.
--
-- Rows minted from now on always carry an expiry: `invite/regenerate` sets it explicitly.
ALTER TABLE invites ADD COLUMN IF NOT EXISTS expires_at timestamptz;

COMMENT ON COLUMN invites.expires_at IS
  'When this invite token stops being accepted by register. NULL = never expires (pre-0013 rows).';
