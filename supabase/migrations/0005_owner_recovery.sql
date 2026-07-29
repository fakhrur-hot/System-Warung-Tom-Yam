-- 0005_owner_recovery.sql
-- Permanent owner-recovery token: lets a fresh install regain Main Admin on a new device
-- (previous phone lost/broken). Never rotates. Shown as a QR to the Main Admin — keep secret.
INSERT INTO settings (key, value)
VALUES ('owner_recovery_token', substr(md5(random()::text) || md5(clock_timestamp()::text), 1, 32))
ON CONFLICT (key) DO NOTHING;
