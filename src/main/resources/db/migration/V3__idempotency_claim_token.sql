
-- Add an ownership token for each active idempotency claim.
-- Existing records receive a token so the column can be made NOT NULL.
ALTER TABLE idempotency_records
    ADD COLUMN claim_token UUID;

-- NOTE: gen_random_uuid() is PostgreSQL-specific; H2 uses random_uuid().
-- For cross-database compatibility we skip bulk backfill here and let the
-- application generate claim tokens at claim time. The column is left
-- nullable so existing records are not invalidated by this migration.
