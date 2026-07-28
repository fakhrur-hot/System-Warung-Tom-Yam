-- 0003_secondary_admin.sql
-- Introduces the SECONDARY ADMIN role: a device with full admin *management* powers
-- (menu, tables, branding, settings, devices, invites, reports) but NO local printer.
-- Its orders route to the Main Admin's printer, exactly like an ordering-staff device.
-- Only role='ADMIN' devices remain printer hosts / auto-print customer orders.

-- 1) New device role value. PG15 permits ADD VALUE inside a transaction; the value just
--    can't be *used* until the transaction commits — which is fine, nothing below uses it.
ALTER TYPE device_role ADD VALUE IF NOT EXISTS 'ADMIN_SECONDARY';

-- 1b) One-time credential-delivery marker. `devices-status` mints a device's credential
--     (ordering api_key OR secondary-admin session token) on its first poll after approval
--     and stamps this so it's never re-issued. The column was referenced by devices-status
--     from the start but never actually created (a live "TODO"), so its SELECT always
--     errored → every poll 404'd. Adding it here fixes credential delivery for BOTH roles.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS key_delivered_at timestamptz;

-- 2) Invites become role-scoped. Row id=1 stays the ordering-staff invite; row id=2 is
--    the secondary-admin invite. Stored as TEXT (not the enum) so this migration never
--    references the just-added enum value in the same transaction.
--    The old singleton CHECK (id=1) has to go so a second invite row can exist.
ALTER TABLE invites DROP CONSTRAINT IF EXISTS invites_singleton;
ALTER TABLE invites ADD COLUMN IF NOT EXISTS role text NOT NULL DEFAULT 'ORDERING';

-- Seed the secondary-admin invite row with a random 32-hex token (the `invite` function
-- rotates it on demand; this just guarantees the row exists).
INSERT INTO invites (id, token, role, rotated_at)
VALUES (
  2,
  substr(md5(random()::text) || md5(clock_timestamp()::text), 1, 32),
  'ADMIN_SECONDARY',
  now()
)
ON CONFLICT (id) DO NOTHING;
