-- Daily market status, kept.
--
-- The Market Trend Identification Engine computed a score, served it from an hourly in-memory
-- cache, and then forgot it. Every reading the platform has ever produced was lost on restart,
-- so there was no way to ask "what was the market doing last Tuesday" or to chart the score
-- against what the strategies actually did. Breadth is a daily measure; a daily measure nobody
-- keeps is a measure that can only ever describe the present.
--
-- One row per session. The session is the key, not the computation time: the engine recomputes
-- hourly through the day and each recompute replaces that day's row rather than appending, so
-- the table holds the latest reading for each session instead of a dozen near-identical ones.

CREATE TABLE IF NOT EXISTS market_health_history (
    trade_date              TEXT PRIMARY KEY,
    market_status           TEXT,
    recommended_action      TEXT,
    market_health_score     DOUBLE PRECISION,
    pct_above_20dma         DOUBLE PRECISION,
    pct_above_50dma         DOUBLE PRECISION,
    pct_above_100dma        DOUBLE PRECISION,
    pct_above_200dma        DOUBLE PRECISION,
    advancing               INTEGER,
    declining               INTEGER,
    advance_decline_ratio   DOUBLE PRECISION,
    new_highs               INTEGER,
    new_lows                INTEGER,
    nh_nl_ratio             DOUBLE PRECISION,
    volume_participation_pct DOUBLE PRECISION,
    stocks_above_avg_volume INTEGER,
    composite_rsi           DOUBLE PRECISION,
    composite_rsi_state     TEXT,
    composite_trend         TEXT,
    total_stocks            INTEGER,
    universe                TEXT,
    computed_at             TEXT
);

-- The history panel and the export both read newest-first.
CREATE INDEX IF NOT EXISTS idx_market_health_history_date
    ON market_health_history (trade_date DESC);

COMMENT ON TABLE market_health_history IS
  'One row per trading session: the latest market health reading computed for that session. Replaced in place by each hourly recompute, so it is the day''s settled reading rather than an audit of every recomputation.';
COMMENT ON COLUMN market_health_history.trade_date IS
  'The session the reading describes - taken from the newest daily candle the engine read, not the wall clock, so a reading computed today from Monday''s closes is filed under Monday.';
