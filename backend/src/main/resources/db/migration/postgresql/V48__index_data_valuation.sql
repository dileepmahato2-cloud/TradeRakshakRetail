-- NSE's daily all-index file carries valuation alongside the prices, and it is the only place
-- these come from - the broker candle feed has no P/E, P/B or dividend yield at any level.
-- Dropping them on import would mean re-downloading the whole history to get them back.
--
-- lock_timeout is raised for this migration only. Both statements below need ACCESS EXCLUSIVE on
-- index_data, and the first attempt failed with "canceling statement due to lock timeout" because
-- the previous application instance still held pooled connections while the new one migrated.
-- The work itself is instant on an empty table; it is purely the wait for the old pool to drain.
SET LOCAL lock_timeout = '60s';

ALTER TABLE index_data ADD COLUMN IF NOT EXISTS pe        NUMERIC(12,2);
ALTER TABLE index_data ADD COLUMN IF NOT EXISTS pb        NUMERIC(12,2);
ALTER TABLE index_data ADD COLUMN IF NOT EXISTS div_yield NUMERIC(12,2);
ALTER TABLE index_data ADD COLUMN IF NOT EXISTS turnover  NUMERIC(18,2);

-- idx_index_data_sym_date and idx_index_data_symbol_date are the same index under two names
-- (symbol, trade_date DESC). Keeping both only ever cost write time and disk.
DROP INDEX IF EXISTS idx_index_data_sym_date;
