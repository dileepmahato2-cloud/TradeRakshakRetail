-- Ranking had slowed to 45 seconds (from ~850 ms) because every daily-bar lookup was doing
-- a bitmap heap scan: the symbol index found all ~19,700 rows for a stock, then the filter
-- discarded ~19,458 of them to keep the ~260 DAILY ones. Measured on CDSL: 8.0 ms and 693
-- buffers per call, repeated for every scored stock and every candidate batch.
--
-- The predicate has to be written exactly as the queries write it. An earlier attempt used
-- "WHERE candle_mode = 'DAILY'", which PostgreSQL cannot match against the query's
-- "UPPER(COALESCE(candle_mode,'')) = 'DAILY'", so the index was built and never used.
-- Same for the leading column: the queries compare UPPER(COALESCE(symbol,'')).
--
-- After: index scan, 0.15 ms, 13 buffers, nothing discarded.
CREATE INDEX IF NOT EXISTS idx_bmc_daily_usym_date
    ON broker_market_candles (UPPER(COALESCE(symbol, '')), trade_date DESC)
    WHERE UPPER(COALESCE(candle_mode, '')) = 'DAILY';

-- The mismatched attempt described above. It can never be chosen by these queries, so it is
-- pure write overhead on a 51-million-row table.
DROP INDEX IF EXISTS idx_bmc_daily_sym_date;
