-- =============================================================================
-- TradeRakshak™ v11.6 — V15: Stock Upload & Performance Tracker
--
-- Tables:
--   1.  stock_uploads          — uploaded stock batches (audit)
--   2.  tracked_stocks         — active tracked stock master (one row per symbol)
--   3.  tracked_stock_prices   — daily price snapshots (full history)
--   4.  tracker_rankings       — daily Top-10 ranking snapshots
--   5.  tracker_ranking_items  — individual stocks in each Top-10 run
--   6.  tracker_ranking_config — admin-configurable scoring factor weights
--   7.  tracker_price_refresh_log — audit of every price refresh
--   8.  tracker_audit_log      — complete audit trail
--
-- Enterprise Policy:
--   • IF NOT EXISTS on all — safe re-run / rollback
--   • No existing tables modified — fully backward compatible
--   • Admin controls weights via tracker_ranking_config (no code change)
--   • Retention configurable per institution (default 90 days → 5 years)
-- =============================================================================

-- ── 1. Upload Batches ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stock_uploads (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    upload_id       TEXT UNIQUE NOT NULL,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    file_name       TEXT,
    file_type       TEXT DEFAULT 'xlsx',           -- xlsx / csv
    total_rows      INTEGER DEFAULT 0,
    new_records     INTEGER DEFAULT 0,
    duplicate_records INTEGER DEFAULT 0,
    failed_records  INTEGER DEFAULT 0,
    status          TEXT DEFAULT 'processing',     -- processing/completed/failed
    error_message   TEXT,
    upload_date     TEXT NOT NULL,
    created_at      TEXT DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_uploads_user ON stock_uploads(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_uploads_inst ON stock_uploads(institution_id, created_at DESC);

-- ── 2. Tracked Stocks Master (one active row per symbol) ──────────────────────
CREATE TABLE IF NOT EXISTS tracked_stocks (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    institution_id  INTEGER DEFAULT 1,
    user_id         INTEGER NOT NULL,
    upload_id       TEXT,
    symbol          TEXT NOT NULL,
    company_name    TEXT,
    market_cap_name TEXT,                          -- Large Cap / Mid Cap / Small Cap
    sector          TEXT,
    exchange        TEXT DEFAULT 'NSE',
    upload_date     TEXT NOT NULL,                 -- the date from the uploaded row
    base_price      REAL,                          -- closing price on upload_date
    base_price_source TEXT DEFAULT 'api',          -- api / manual / estimated
    current_price   REAL,
    current_date    TEXT,
    return_pct      REAL DEFAULT 0,                -- ((current-base)/base)*100
    is_active       INTEGER DEFAULT 1,
    last_refreshed  TEXT,
    created_at      TEXT DEFAULT (datetime('now')),
    updated_at      TEXT,
    UNIQUE(institution_id, symbol)                 -- one active record per symbol per institution
);
CREATE INDEX IF NOT EXISTS idx_ts_inst_sym  ON tracked_stocks(institution_id, symbol);
CREATE INDEX IF NOT EXISTS idx_ts_inst_sec  ON tracked_stocks(institution_id, sector, return_pct);
CREATE INDEX IF NOT EXISTS idx_ts_return    ON tracked_stocks(institution_id, return_pct);
CREATE INDEX IF NOT EXISTS idx_ts_active    ON tracked_stocks(institution_id, is_active, return_pct);

-- ── 3. Daily Price Snapshots (full history, never overwritten) ────────────────
CREATE TABLE IF NOT EXISTS tracked_stock_prices (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    institution_id  INTEGER DEFAULT 1,
    symbol          TEXT NOT NULL,
    price_date      TEXT NOT NULL,
    open_price      REAL,
    high_price      REAL,
    low_price       REAL,
    close_price     REAL NOT NULL,
    volume          REAL,
    return_pct      REAL,                          -- vs base_price on that date
    base_price      REAL,
    source          TEXT DEFAULT 'api',
    created_at      TEXT DEFAULT (datetime('now')),
    UNIQUE(institution_id, symbol, price_date)
);
CREATE INDEX IF NOT EXISTS idx_tsp_sym_date ON tracked_stock_prices(institution_id, symbol, price_date DESC);
CREATE INDEX IF NOT EXISTS idx_tsp_date     ON tracked_stock_prices(institution_id, price_date DESC);

-- ── 4. Daily Top-10 Ranking Snapshots (history) ───────────────────────────────
CREATE TABLE IF NOT EXISTS tracker_rankings (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id                  TEXT UNIQUE NOT NULL,
    institution_id          INTEGER DEFAULT 1,
    run_date                TEXT NOT NULL,
    run_type                TEXT DEFAULT 'scheduled',   -- scheduled/manual/eod
    eligible_count          INTEGER DEFAULT 0,
    ranked_count            INTEGER DEFAULT 0,
    -- Current Top-10 metrics
    curr_avg_return         REAL DEFAULT 0,
    curr_win_ratio          REAL DEFAULT 0,
    curr_positive_count     INTEGER DEFAULT 0,
    curr_negative_count     INTEGER DEFAULT 0,
    curr_avg_score          REAL DEFAULT 0,
    -- Previous Top-10 metrics (stored at time of run)
    prev_avg_return         REAL DEFAULT 0,
    prev_win_ratio          REAL DEFAULT 0,
    prev_positive_count     INTEGER DEFAULT 0,
    prev_negative_count     INTEGER DEFAULT 0,
    prev_avg_score          REAL DEFAULT 0,
    -- Sector distribution (JSON)
    sector_distribution_json TEXT,
    created_at              TEXT DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_trankings_inst_date ON tracker_rankings(institution_id, run_date DESC);

-- ── 5. Top-10 Ranking Items ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tracker_ranking_items (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id          TEXT NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    rank_position   INTEGER NOT NULL,
    symbol          TEXT NOT NULL,
    sector          TEXT,
    market_cap_name TEXT,
    composite_score REAL NOT NULL,
    upload_date     TEXT,
    base_price      REAL,
    current_price   REAL,
    return_pct      REAL DEFAULT 0,
    days_since_upload INTEGER DEFAULT 0,
    -- Individual factor scores (JSON)
    factor_scores_json TEXT,
    is_current_top10   INTEGER DEFAULT 1,         -- 1=current, 0=previous
    run_date           TEXT,
    created_at         TEXT DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_tri_run    ON tracker_ranking_items(run_id, rank_position);
CREATE INDEX IF NOT EXISTS idx_tri_inst   ON tracker_ranking_items(institution_id, run_date DESC);
CREATE INDEX IF NOT EXISTS idx_tri_symbol ON tracker_ranking_items(symbol, run_date DESC);

-- ── 6. Admin-Configurable Scoring Factor Weights ─────────────────────────────
CREATE TABLE IF NOT EXISTS tracker_ranking_config (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    institution_id  INTEGER DEFAULT 1,
    factor_key      TEXT NOT NULL,
    factor_label    TEXT NOT NULL,
    factor_description TEXT,
    weight          REAL NOT NULL DEFAULT 10.0,
    is_enabled      INTEGER DEFAULT 1,
    factor_order    INTEGER DEFAULT 0,
    updated_by      INTEGER,
    updated_at      TEXT,
    created_at      TEXT DEFAULT (datetime('now')),
    UNIQUE(institution_id, factor_key)
);
CREATE INDEX IF NOT EXISTS idx_trc_inst ON tracker_ranking_config(institution_id, is_enabled);

-- Seed default 13 factors for institution 1
INSERT OR IGNORE INTO tracker_ranking_config
    (institution_id, factor_key, factor_label, factor_description, weight, is_enabled, factor_order)
VALUES
    (1,'relative_strength',    'Relative Strength',       'RS vs Nifty 50 over 6 months',              15, 1, 1),
    (1,'volume_growth',        'Volume Growth',            'Current vol vs 20-day avg vol',             10, 1, 2),
    (1,'delivery_pct',         'Delivery Percentage',      'Delivery % indicating institutional buying', 10, 1, 3),
    (1,'sector_strength',      'Sector Strength',          'Sector AI trend score',                     10, 1, 4),
    (1,'trend_strength',       'Trend Strength',           'Price above EMA20/50/200 alignment score',  10, 1, 5),
    (1,'rsi_strength',         'RSI Strength',             'RSI(14) score in ideal zone 45-65',          8, 1, 6),
    (1,'ma_position',          'Moving Average Position',  'Price vs EMA20/50/200 score',                8, 1, 7),
    (1,'price_momentum',       'Price Momentum',           '10-day ROC and short-term momentum',         8, 1, 8),
    (1,'week52_position',      '52-Week Position',         '% above 52-week low, proximity to 52w high', 7, 1, 9),
    (1,'breakout_strength',    'Breakout Strength',        '20-day and 52-week breakout confirmation',   7, 1, 10),
    (1,'liquidity',            'Liquidity Score',          'Average daily turnover quality',              5, 1, 11),
    (1,'market_cap_quality',   'Market Cap Quality',       'Large/Mid/Small cap quality weight',          5, 1, 12),
    (1,'institutional_interest','Institutional Interest',  'Delivery % trend + FII/DII proxy',            7, 1, 13);
-- Total = 110 (normalised to 100 in service layer)

-- ── 7. Price Refresh Audit Log ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tracker_price_refresh_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    institution_id  INTEGER DEFAULT 1,
    refresh_type    TEXT DEFAULT 'auto',           -- auto/manual/eod
    triggered_by    INTEGER,
    symbols_updated INTEGER DEFAULT 0,
    symbols_failed  INTEGER DEFAULT 0,
    api_source      TEXT DEFAULT 'dhan',
    started_at      TEXT,
    completed_at    TEXT,
    duration_ms     INTEGER DEFAULT 0,
    status          TEXT DEFAULT 'running',
    error_message   TEXT,
    created_at      TEXT DEFAULT (datetime('now'))
);

-- ── 8. Full Audit Trail ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tracker_audit_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    institution_id  INTEGER DEFAULT 1,
    user_id         INTEGER,
    action          TEXT NOT NULL,
    entity_type     TEXT,
    entity_id       TEXT,
    details_json    TEXT,
    ip_address      TEXT,
    created_at      TEXT DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_tal_inst_action ON tracker_audit_log(institution_id, action, created_at DESC);

-- ── 9. Feature Flags ──────────────────────────────────────────────────────────
INSERT OR IGNORE INTO feature_configs
    (feature_key, feature_name, feature_group, is_enabled, min_plan, allowed_roles, description, created_at)
VALUES
    ('stock_tracker',           'Stock Upload & Performance Tracker',  'analytics', 1, 'Free Trial', 'ALL',
     'Upload stocks, track prices, rank using configurable scoring, view Top-10 analysis',      datetime('now')),
    ('stock_tracker_upload',    'Stock Upload (Excel/CSV)',            'analytics', 1, 'Free Trial', 'ALL',
     'Upload stock master data in Excel or CSV format',                                          datetime('now')),
    ('stock_tracker_ranking',   'Sahi-Style Ranking Engine',          'analytics', 1, 'Free Trial', 'ALL',
     'Configurable 13-factor scoring engine for Top-10 stock selection',                        datetime('now')),
    ('stock_tracker_export',    'Tracker Export (Excel/CSV/PDF)',      'analytics', 1, 'Basic',      'ALL',
     'Export eligible stocks, rankings, and performance reports',                               datetime('now')),
    ('tracker_admin_config',    'Tracker Admin Configuration',         'admin',     1, 'Free Trial', 'ADMIN,SUPER_ADMIN',
     'Admin can modify ranking factors, weights, sector limits without coding',                 datetime('now'));

-- =============================================================================
-- END V15
-- =============================================================================
