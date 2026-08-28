-- =============================================================================
-- TradeRakshakâ„¢ v11.5 â€” V14: Stock Ranking Engine Schema
--
-- Tables:
--   1. stock_ranking_runs     â€” audit trail of each ranking run
--   2. stock_rankings         â€” individual ranked stock rows per run
--   3. ranking_config         â€” admin-configurable parameter weights
--
-- Enterprise Policy:
--   â€¢ IF NOT EXISTS on all tables â€” safe to re-run
--   â€¢ No existing tables modified â€” fully backward compatible
--   â€¢ Supports multi-institution weight configuration
--   â€¢ Seeds default feature flag for Super Admin governance
--   â€¢ Data migration: previous versions seamlessly upgrade (no data loss)
-- =============================================================================

-- â”€â”€ 1. Ranking Run Audit Trail â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS stock_ranking_runs (
    id              BIGSERIAL PRIMARY KEY,
    run_id          TEXT UNIQUE NOT NULL,           -- e.g. RNK-1717234567890
    strategy_id     INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    ranked_count    INTEGER DEFAULT 0,
    scanned_count   INTEGER DEFAULT 0,
    top_symbol      TEXT,
    top_score       REAL,
    avg_score       REAL,
    high_priority_count   INTEGER DEFAULT 0,
    medium_priority_count INTEGER DEFAULT 0,
    low_priority_count    INTEGER DEFAULT 0,
    filters_json    TEXT,                           -- JSON: sector, tradeType, etc.
    scan_date       TEXT NOT NULL,
    scan_time_ms    INTEGER DEFAULT 0,
    status          TEXT DEFAULT 'completed',       -- completed / failed / running
    error_message   TEXT,
    created_at      TEXT DEFAULT (CURRENT_TIMESTAMP::text)
);

CREATE INDEX IF NOT EXISTS idx_sruns_strategy    ON stock_ranking_runs(strategy_id, scan_date DESC);
CREATE INDEX IF NOT EXISTS idx_sruns_user        ON stock_ranking_runs(user_id, scan_date DESC);
CREATE INDEX IF NOT EXISTS idx_sruns_institution ON stock_ranking_runs(institution_id, scan_date DESC);

-- â”€â”€ 2. Individual Ranked Stock Rows â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS stock_rankings (
    id              BIGSERIAL PRIMARY KEY,
    run_id          TEXT NOT NULL,
    strategy_id     INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    rank_position   INTEGER NOT NULL,
    symbol          TEXT NOT NULL,
    company_name    TEXT,
    sector          TEXT,
    exchange        TEXT DEFAULT 'NSE',
    cmp             REAL,
    trade_type      TEXT DEFAULT 'swing',           -- swing / intraday / positional / options
    signal          TEXT,                           -- STRONG BUY / BUY / WATCH / AVOID
    entry_price     REAL,
    stop_loss       REAL,
    target          REAL,
    rr_ratio        REAL,
    composite_score REAL NOT NULL,                  -- 0-100
    priority        TEXT DEFAULT 'LOW',             -- HIGH / MEDIUM / LOW
    backtest_win_rate REAL DEFAULT 0,
    passes_logic    INTEGER DEFAULT 0,              -- 1 if passed strategy conditions
    -- 11 individual parameter scores (stored for transparency)
    score_price_action       REAL,
    score_volume_strength    REAL,
    score_delivery_pct       REAL,
    score_rsi                REAL,
    score_ema_trend          REAL,
    score_breakout           REAL,
    score_sr_proximity       REAL,
    score_risk_reward        REAL,
    score_backtest_wr        REAL,
    score_sector_strength    REAL,
    score_market_trend       REAL,
    -- JSON payload for full details
    reason_json         TEXT,                       -- JSON array of reason strings
    score_breakdown_json TEXT,                      -- JSON: full per-parameter breakdown
    scan_date           TEXT NOT NULL,
    created_at          TEXT DEFAULT (CURRENT_TIMESTAMP::text)
);

CREATE INDEX IF NOT EXISTS idx_sranks_run       ON stock_rankings(run_id, rank_position);
CREATE INDEX IF NOT EXISTS idx_sranks_symbol    ON stock_rankings(symbol, scan_date DESC);
CREATE INDEX IF NOT EXISTS idx_sranks_priority  ON stock_rankings(institution_id, priority, scan_date DESC);
CREATE INDEX IF NOT EXISTS idx_sranks_score     ON stock_rankings(composite_score DESC, scan_date DESC);

-- â”€â”€ 3. Ranking Config â€” Admin-configurable parameter weights â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
--    One row per parameter per institution â€” updated by Admin without coding
CREATE TABLE IF NOT EXISTS ranking_config (
    id              BIGSERIAL PRIMARY KEY,
    institution_id  INTEGER DEFAULT 1,
    param_key       TEXT NOT NULL,
    weight          REAL NOT NULL DEFAULT 10.0,     -- contribution weight (total should be ~100)
    is_enabled      INTEGER DEFAULT 1,
    updated_by      INTEGER,                        -- user_id of admin who last changed this
    updated_at      TEXT,
    created_at      TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    UNIQUE(institution_id, param_key)
);

CREATE INDEX IF NOT EXISTS idx_rankconf_inst ON ranking_config(institution_id);

-- Seed default weights for institution 1 (idempotent)
INSERT INTO ranking_config (institution_id, param_key, weight, updated_at) VALUES
    (1, 'price_action',          12.0, CURRENT_TIMESTAMP::text),
    (1, 'volume_strength',       10.0, CURRENT_TIMESTAMP::text),
    (1, 'delivery_pct',           8.0, CURRENT_TIMESTAMP::text),
    (1, 'rsi',                   10.0, CURRENT_TIMESTAMP::text),
    (1, 'ema_trend',             12.0, CURRENT_TIMESTAMP::text),
    (1, 'breakout',              10.0, CURRENT_TIMESTAMP::text),
    (1, 'sr_proximity',           8.0, CURRENT_TIMESTAMP::text),
    (1, 'risk_reward',           10.0, CURRENT_TIMESTAMP::text),
    (1, 'backtest_win_rate',     12.0, CURRENT_TIMESTAMP::text),
    (1, 'sector_strength',        8.0, CURRENT_TIMESTAMP::text),
    (1, 'market_trend',          10.0, CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;
-- Total = 110 (normalised to 100 in service layer; admin can rebalance freely)

-- â”€â”€ 4. Ranking Alert Config â€” notify when high-priority stocks appear â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS ranking_alerts (
    id              BIGSERIAL PRIMARY KEY,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    strategy_id     INTEGER,
    alert_name      TEXT,
    min_score       REAL DEFAULT 72.0,              -- alert when stock scores >= this
    priority_filter TEXT DEFAULT 'HIGH',            -- HIGH / HIGH,MEDIUM
    sector_filter   TEXT,                           -- NULL = all sectors
    channels_json   TEXT DEFAULT '["in_app"]',      -- notification channels
    is_enabled      INTEGER DEFAULT 1,
    last_fired_at   TEXT,
    fire_count      INTEGER DEFAULT 0,
    created_at      TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    updated_at      TEXT
);

-- â”€â”€ 5. Feature flags â€” ranking feature access control â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO feature_configs
    (feature_key, feature_name, feature_group, is_enabled, min_plan, allowed_roles, description, created_at)
VALUES
    ('stock_ranking',
     'Stock Ranking Engine',
     'trading', 1, 'Free Trial', 'ALL',
     'Multi-parameter stock ranking with price action, RSI, EMA trend, volume, delivery%, S&R, R:R, sector strength',
     CURRENT_TIMESTAMP::text),
    ('ranking_weight_config',
     'Ranking Weight Configuration (Admin)',
     'admin', 1, 'Free Trial', 'ADMIN,SUPER_ADMIN',
     'Admin can modify 11 parameter weights for stock ranking without coding',
     CURRENT_TIMESTAMP::text),
    ('ranking_export',
     'Ranking Export (CSV/PDF)',
     'trading', 1, 'Basic', 'ALL',
     'Export ranked stock list as CSV or PDF',
     CURRENT_TIMESTAMP::text),
    ('ranking_alerts',
     'Ranking Proximity Alerts',
     'trading', 1, 'Pro', 'ALL',
     'Alert user when high-priority ranked stocks appear matching their criteria',
     CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- =============================================================================
-- END V14
-- =============================================================================

