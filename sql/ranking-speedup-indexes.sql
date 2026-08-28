-- =============================================================================
--  Ranking speed-up: partial indexes on the DAILY candles
-- =============================================================================
--
--  WHY
--  Refreshing the Current Top 10 was taking ~19 seconds. Almost all of it was one
--  query. getEligibleStocks() loads 30 daily bars per candidate; market_data is
--  empty, so every symbol falls through to the broker-candle fallback, which runs
--  three UNION ALL branches over broker_market_candles.
--
--  Only ~944,000 of that table's ~46,700,000 rows are DAILY candles (2%), and no
--  index covers that subset. PostgreSQL therefore chose a parallel sequential scan
--  per branch:
--
--      Parallel Seq Scan on broker_market_candles
--        Filter: ((close IS NOT NULL) AND (upper(COALESCE(candle_mode,'')) = 'DAILY'))
--        Rows Removed by Filter: 15,265,052        <- per worker, 3 workers
--      Execution Time: 15,896 ms                    <- for ONE of three branches
--
--  These three partial indexes cover exactly the rows the query wants — one per
--  UNION branch, matching each branch's expression — so the scan becomes an index
--  lookup. They index ~944k rows, not 46.7M, so they stay small.
--
--  HOW TO RUN
--  Run this when NO backfill or data-fetch job is active. CREATE INDEX CONCURRENTLY
--  does not block reads or writes, but it does add I/O, and building three indexes
--  while a fetch is hammering the same table will slow both.
--
--      psql -U postgres -d traderakshak -f sql/ranking-speedup-indexes.sql
--
--  CONCURRENTLY is deliberate and must not be removed: a plain CREATE INDEX takes an
--  ACCESS EXCLUSIVE lock on all 46.7M rows and will stall every writer, which has
--  already caused failed insert batches and lost candles on this database.
--
--  CONCURRENTLY cannot run inside a transaction block, which is why this lives in a
--  script rather than in SymbolSchemaGuard (that runs its DDL inside a transaction).
--
--  Each statement may take a few minutes. They are independent; if one is
--  interrupted, PostgreSQL leaves an INVALID index behind — drop it and re-run:
--      SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;
-- =============================================================================

\timing on

-- Branch 1: matches  UPPER(COALESCE(symbol,'')) IN (...)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bmc_daily_usym_date
    ON broker_market_candles (upper(COALESCE(symbol, '')), trade_date DESC)
    WHERE close IS NOT NULL AND upper(COALESCE(candle_mode, '')) = 'DAILY';

-- Branch 2: matches  UPPER(COALESCE(trading_symbol,'')) IN (...)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bmc_daily_utsym_date
    ON broker_market_candles (upper(COALESCE(trading_symbol, '')), trade_date DESC)
    WHERE close IS NOT NULL AND upper(COALESCE(candle_mode, '')) = 'DAILY';

-- Branch 3: matches  UPPER(REPLACE(COALESCE(trading_symbol,''),'-EQ','')) IN (...)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bmc_daily_utsymeq_date
    ON broker_market_candles (upper(replace(COALESCE(trading_symbol, ''), '-EQ', '')), trade_date DESC)
    WHERE close IS NOT NULL AND upper(COALESCE(candle_mode, '')) = 'DAILY';

-- Let the planner see the new indexes straight away.
ANALYZE broker_market_candles;

-- Confirm all three are valid (an interrupted build leaves indisvalid = false).
SELECT c.relname AS index_name,
       i.indisvalid AS is_valid,
       pg_size_pretty(pg_relation_size(c.oid)) AS size
FROM pg_index i
JOIN pg_class c ON c.oid = i.indexrelid
WHERE c.relname IN ('idx_bmc_daily_usym_date',
                    'idx_bmc_daily_utsym_date',
                    'idx_bmc_daily_utsymeq_date')
ORDER BY c.relname;
