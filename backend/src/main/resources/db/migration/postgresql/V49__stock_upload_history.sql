-- =============================================================================
-- V49 — Stock Upload History (append-only signal log)
--
-- Why this table exists
--   tracked_stocks holds ONE row per (institution, symbol) and is upserted on
--   every upload, so a symbol flagged on 02-01-2026, 10-04-2026 and 01-05-2026
--   left only the last-written row behind. Two screens need two different
--   answers from that data:
--     * "current state" screens  -> the symbol's LATEST entry_date  (Mode A)
--     * "historical / backtest"  -> the row as it stood on a given date (Mode B)
--   Neither is answerable once the earlier rows are gone, so every uploaded row
--   is now appended here and tracked_stocks becomes the Mode-A projection of it.
--
-- Duplicate policy (deliberate, and it is two rules, not one)
--   * NO unique constraint on (entry_date, symbol): the same symbol MUST be
--     storable many times across different dates - that is the whole point.
--   * An exactly identical row (same tenant, user, symbol, date, market cap and
--     sector) carries no new information, so re-uploading the same file does not
--     grow the table. ux_suh_row_identity enforces that, and the upload handler
--     inserts with ON CONFLICT DO NOTHING.
--
-- base_price is stored per row because it belongs to THAT entry_date. A backtest
-- of 10-04-2026 must use the close on 10-04-2026, not today's base price.
-- =============================================================================

CREATE TABLE IF NOT EXISTS stock_upload_history (
    id                BIGSERIAL PRIMARY KEY,
    upload_batch_id   UUID        NOT NULL,          -- one id per file upload (traceability)
    file_name         TEXT,
    institution_id    BIGINT      NOT NULL DEFAULT 1,
    user_id           BIGINT      NOT NULL DEFAULT 0,
    entry_date        DATE        NOT NULL,          -- "Date" column from the file = signal date
    symbol            TEXT        NOT NULL,          -- stored normalized (UPPER/TRIM)
    marketcap_name    TEXT        NOT NULL DEFAULT '',
    sector            TEXT        NOT NULL DEFAULT '',
    base_price        DOUBLE PRECISION,              -- close on entry_date (or last close before it)
    base_price_date   DATE,                          -- the trade_date that base_price came from
    base_price_source TEXT,
    uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Exact-duplicate suppression. NOT a (entry_date, symbol) key: the date is part
-- of the identity, so the same symbol on a different date is a separate row.
-- marketcap_name/sector are NOT NULL DEFAULT '' precisely so NULLs cannot slip
-- past this index (in Postgres NULLs are distinct from each other).
CREATE UNIQUE INDEX IF NOT EXISTS ux_suh_row_identity
    ON stock_upload_history (institution_id, user_id, symbol, entry_date, marketcap_name, sector);

-- Mode A (DISTINCT ON (symbol) ... ORDER BY entry_date DESC) and Mode B as-of.
CREATE INDEX IF NOT EXISTS idx_suh_symbol_date
    ON stock_upload_history (symbol, entry_date DESC);
-- Mode B exact-date lookups.
CREATE INDEX IF NOT EXISTS idx_suh_entry_date
    ON stock_upload_history (entry_date);
-- Every application query is tenant-scoped first; this is the one the planner uses.
CREATE INDEX IF NOT EXISTS idx_suh_scope_symbol_date
    ON stock_upload_history (institution_id, user_id, symbol, entry_date DESC);
-- Base-price backfill scans "rows still missing a base price".
CREATE INDEX IF NOT EXISTS idx_suh_missing_base
    ON stock_upload_history (institution_id, entry_date)
    WHERE base_price IS NULL OR base_price <= 0;

-- ── Seed from what already exists ────────────────────────────────────────────
-- tracked_stocks is all the history that survived, so it becomes the first
-- generation of rows. Anything with an unparseable upload_date is left out
-- rather than guessed at: a wrong entry_date would silently become a symbol's
-- "latest" and poison Mode A. gen_random_uuid() is avoided so this runs on
-- PostgreSQL versions without pgcrypto.
INSERT INTO stock_upload_history (
    upload_batch_id, file_name, institution_id, user_id, entry_date, symbol,
    marketcap_name, sector, base_price, base_price_source, uploaded_at)
SELECT
    md5(random()::text || clock_timestamp()::text)::uuid,
    'seed:tracked_stocks',
    COALESCE(ts.institution_id, 1),
    COALESCE(ts.user_id, 0),
    to_date(substring(ts.upload_date FROM 1 FOR 10), 'YYYY-MM-DD'),
    UPPER(TRIM(ts.symbol)),
    COALESCE(ts.market_cap_name, ''),
    COALESCE(ts.sector, ''),
    CASE WHEN ts.base_price > 0 THEN ts.base_price END,
    CASE WHEN ts.base_price > 0 THEN 'seed:tracked_stocks' END,
    now()
FROM tracked_stocks ts
WHERE ts.symbol IS NOT NULL
  AND TRIM(ts.symbol) <> ''
  AND ts.upload_date ~ '^\d{4}-\d{2}-\d{2}'
ON CONFLICT DO NOTHING;
