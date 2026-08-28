-- Restores the index V44 dropped. V44 was wrong to drop it.
--
-- V44 added a DAILY-only index on the fully normalised symbol expression and removed
-- idx_bmc_daily_usym_date as "superseded". It is not superseded: the two cover different
-- expressions and both are load-bearing.
--
--   idx_bmc_norm_daily_date   UPPER(REPLACE(...TRIM(COALESCE(symbol,''))...))
--                             used by loadLatestDailyPrices
--   idx_bmc_daily_usym_date   UPPER(COALESCE(symbol,''))
--                             used by loadOhlcvHistory, called once per candidate on every ranking
--
-- With it gone, loadOhlcvHistory fell back to scanning: ranking scoring went from 0.6 s to 16.7 s
-- and the below-price chart from 11 s to 227 s. The lesson is the one V42 already taught in the
-- other direction - a partial index serves exactly one expression, so check every caller before
-- deciding two indexes overlap.
CREATE INDEX IF NOT EXISTS idx_bmc_daily_usym_date
    ON broker_market_candles (UPPER(COALESCE(symbol,'')), trade_date DESC)
    WHERE UPPER(COALESCE(candle_mode,'')) = 'DAILY';
