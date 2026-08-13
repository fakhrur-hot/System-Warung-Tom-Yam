-- Invite tokens move to hash-at-rest, same as the Main Admin owner key (see 0005_owner_recovery.sql
-- and admin-recovery/index.ts).
--
-- Until now `invites.token` held the join code in plain text, readable by anyone with database
-- access for as long as the row existed. The owner key already solved this by storing only a
-- sha256 hash and showing the plaintext exactly once, at mint time; invites get the same treatment.
--
-- `token` is kept (nullable) rather than dropped so a café with rows minted before this migration
-- keeps working — `register` checks the hash first and falls back to the plaintext column, exactly
-- like `admin-recovery` already does for the owner key. Newly minted/regenerated invites clear
-- `token` and set `token_hash` only.
ALTER TABLE invites ADD COLUMN IF NOT EXISTS token_hash text;

COMMENT ON COLUMN invites.token_hash IS
  'sha256 of the invite token. Rows minted after 0020 carry only this column; token is cleared.';
COMMENT ON COLUMN invites.token IS
  'Plaintext invite token. Legacy only — rows minted after 0020 leave this NULL and use token_hash.';
