-- 0015_operator_role.sql
-- Introduces the OPERATOR role: a device that can manage menu, tables, branding,
-- and cafe-location for multiple cafés — but has no access to orders, devices,
-- settings, reports, or attendance.

-- 1) New device role value.
ALTER TYPE device_role ADD VALUE IF NOT EXISTS 'OPERATOR';

-- 2) Seed the operator invite row (id=3) with a random 32-hex token.
INSERT INTO invites (id, token, role, rotated_at)
VALUES (
  3,
  substr(md5(random()::text) || md5(clock_timestamp()::text), 1, 32),
  'OPERATOR',
  now()
)
ON CONFLICT (id) DO NOTHING;
