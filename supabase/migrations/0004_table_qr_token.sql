-- 0004_table_qr_token.sql
-- Gives every table an opaque, static QR token so the customer QR URL is
-- ?table=<20-hex-token> instead of the guessable ?table=T0006. Not rotated.
-- The token is resolved back to the real table id server-side (tables-session, orders).

ALTER TABLE tables ADD COLUMN IF NOT EXISTS qr_token text;

-- Backfill existing tables with a random token.
UPDATE tables
SET qr_token = substr(md5(random()::text || id || clock_timestamp()::text), 1, 20)
WHERE qr_token IS NULL;

-- New tables (created via the tables PUT upsert) get a token automatically — the PUT
-- payload never includes it, so a volatile per-row DEFAULT fills it in on insert.
ALTER TABLE tables
  ALTER COLUMN qr_token SET DEFAULT substr(md5(random()::text || clock_timestamp()::text), 1, 20);

CREATE UNIQUE INDEX IF NOT EXISTS tables_qr_token_key ON tables(qr_token);
