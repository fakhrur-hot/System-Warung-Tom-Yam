-- Fixes a bug introduced by 0020_invite_token_hash.sql: that migration added `token_hash` and
-- had invite/index.ts start writing `token: null` for every newly hash-only row, but never
-- dropped the original `NOT NULL` constraint on `token` (from 0001_initial_schema.sql). Every
-- fresh insert (a café's first-ever Ordering-staff invite, seeded lazily on first GET) and every
-- `regenerate` on an existing row (which also writes `token: null`) violated that constraint and
-- 500'd instead of succeeding.
ALTER TABLE invites ALTER COLUMN token DROP NOT NULL;
