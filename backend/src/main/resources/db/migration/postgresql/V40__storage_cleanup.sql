-- Storage cleanup on broker_market_candles and the dead candle table.
--
-- Context: on 2026-08-23 the candle table was 5,948 MB of which 2,722 MB was
-- indexes - 46% of the table, across ten of them. This removes the two that are
-- provably unnecessary. It deliberately does NOT touch the seven others: their
-- usage counters had only a few hours of backfill-dominated traffic behind them,
-- which is not enough evidence to drop an index that a monthly report might need.

-- 1. dhan_historical_candles: zero rows and zero references anywhere in the Java
--    source. Superseded by broker_market_candles, which every service reads.
DROP TABLE IF EXISTS dhan_historical_candles;

-- 2. idx_bmc_symbol_std is on (symbol). idx_bmc_symbol_date is on
--    (symbol, trade_date DESC), and a B-tree can serve a leading-column lookup
--    from a composite index. So every query the first can answer, the second can
--    answer too - it is redundant structurally, not merely unused, which is why
--    it is safe to drop without waiting for representative usage statistics.
DROP INDEX IF EXISTS idx_bmc_symbol_std;
