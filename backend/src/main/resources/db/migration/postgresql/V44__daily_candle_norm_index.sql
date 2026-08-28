-- A DAILY-only index on the SAME normalised symbol expression the queries actually use.
--
-- The problem: broker_market_candles holds 51.3M INTRADAY rows against 711k DAILY rows, a 72:1
-- ratio. The "latest daily bar per symbol" lateral filters on candle_mode but could only use
-- idx_bmc_norm_date, which covers every row. So for each symbol it walked backwards through
-- intraday bars, discarding them one by one, until it reached a daily one - about 22 ms per
-- symbol, 17.6 seconds across 800 symbols, and the dominant cost of every ranking refresh.
--
-- V42 already added a partial DAILY index, but on upper(COALESCE(symbol,'')) while the query
-- normalises much further (stripping .NS, .BO, -EQ, -BE, -BZ, -SM, -ST and spaces). A partial
-- index is only usable when BOTH its expression and its predicate match the query, so that one
-- could never be chosen. This repeats the same mistake's fix: match the query exactly.
--
-- Every row has a candle_mode, so the predicate below covers the data completely.
CREATE INDEX IF NOT EXISTS idx_bmc_norm_daily_date
    ON broker_market_candles (
        UPPER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
            TRIM(COALESCE(symbol,'')),'.NS',''),'.BO',''),'-EQ',''),'-BE',''),'-BZ',''),'-SM',''),'-ST',''),' ','')),
        trade_date DESC)
    WHERE UPPER(COALESCE(candle_mode,'')) = 'DAILY';

-- Superseded: same intent, but an expression no query uses.
DROP INDEX IF EXISTS idx_bmc_daily_usym_date;
