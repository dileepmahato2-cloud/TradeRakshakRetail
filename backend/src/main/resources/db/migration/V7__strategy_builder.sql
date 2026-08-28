-- =============================================================================
-- TradeRakshak™ v11.4 — V7: Advanced Strategy Builder Schema
-- Covers: Strategy v2 (extended), Conditions, Versions, Marketplace,
--         Live Alerts, AI Builder, Walk-Forward, Monte Carlo, Visual Builder
-- Builds on existing strategies + backtests tables
-- =============================================================================

-- ── Extend existing strategies table ────────────────────────────────────────
ALTER TABLE strategies ADD COLUMN builder_level   INTEGER DEFAULT 1;  -- 1=Simple 2=Advanced 3=Visual
ALTER TABLE strategies ADD COLUMN conditions_json TEXT;   -- structured JSON condition tree
ALTER TABLE strategies ADD COLUMN visual_json     TEXT;   -- drag-and-drop block JSON
ALTER TABLE strategies ADD COLUMN ai_generated    INTEGER DEFAULT 0;
ALTER TABLE strategies ADD COLUMN ai_prompt       TEXT;
ALTER TABLE strategies ADD COLUMN strategy_category TEXT DEFAULT 'swing'; -- swing/intraday/positional/options_buy/options_sell/futures/commodity/currency
ALTER TABLE strategies ADD COLUMN timeframe       TEXT DEFAULT 'Daily';
ALTER TABLE strategies ADD COLUMN multi_timeframe_json TEXT;  -- JSON: [{tf, conditions}]
ALTER TABLE strategies ADD COLUMN entry_conditions_json TEXT;
ALTER TABLE strategies ADD COLUMN exit_conditions_json  TEXT;
ALTER TABLE strategies ADD COLUMN risk_management_json  TEXT;
ALTER TABLE strategies ADD COLUMN position_sizing_json  TEXT;
ALTER TABLE strategies ADD COLUMN backtested      INTEGER DEFAULT 0;
ALTER TABLE strategies ADD COLUMN paper_tested    INTEGER DEFAULT 0;
ALTER TABLE strategies ADD COLUMN live_approved   INTEGER DEFAULT 0;
ALTER TABLE strategies ADD COLUMN workflow_status TEXT DEFAULT 'draft'; -- draft/testing/backtesting/paper/live_ready/production
ALTER TABLE strategies ADD COLUMN version         INTEGER DEFAULT 1;
ALTER TABLE strategies ADD COLUMN parent_id       INTEGER;   -- for versioning
ALTER TABLE strategies ADD COLUMN is_public       INTEGER DEFAULT 0;
ALTER TABLE strategies ADD COLUMN is_marketplace  INTEGER DEFAULT 0;
ALTER TABLE strategies ADD COLUMN marketplace_price REAL DEFAULT 0;
ALTER TABLE strategies ADD COLUMN tags            TEXT;
ALTER TABLE strategies ADD COLUMN win_rate        REAL;
ALTER TABLE strategies ADD COLUMN best_backtest_id INTEGER;
ALTER TABLE strategies ADD COLUMN total_copies    INTEGER DEFAULT 0;
ALTER TABLE strategies ADD COLUMN rating          REAL DEFAULT 0;
ALTER TABLE strategies ADD COLUMN rating_count    INTEGER DEFAULT 0;
ALTER TABLE strategies ADD COLUMN updated_at      TEXT;

-- ── Strategy Conditions (structured — for ALL three builder levels) ───────────
CREATE TABLE IF NOT EXISTS strategy_conditions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy_id     INTEGER NOT NULL,
    condition_group TEXT DEFAULT 'entry',   -- entry/exit/filter
    group_operator  TEXT DEFAULT 'AND',     -- AND/OR between groups
    condition_order INTEGER DEFAULT 0,
    operator        TEXT DEFAULT 'AND',     -- AND/OR/NOT before this condition
    indicator_left  TEXT NOT NULL,          -- e.g. RSI / EMA / CLOSE / VOLUME
    params_left     TEXT,                   -- JSON: {"period":14,"timeframe":"Daily"}
    comparator      TEXT NOT NULL,          -- > < >= <= = != CROSSABOVE CROSSBELOW
    indicator_right TEXT,                   -- e.g. VALUE/EMA/SMA/VOLUME_SMA
    params_right    TEXT,                   -- JSON: {"period":20} or {"value":60}
    value_right     REAL,                   -- static value (if right side is constant)
    multiplier      REAL DEFAULT 1.0,       -- e.g. 1.5 × SMA(Volume,20)
    is_group_start  INTEGER DEFAULT 0,      -- opening bracket
    is_group_end    INTEGER DEFAULT 0,      -- closing bracket
    created_at      TEXT
);

-- ── Strategy Versions ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS strategy_versions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy_id     INTEGER NOT NULL,
    version         INTEGER NOT NULL,
    conditions_json TEXT,
    risk_json       TEXT,
    change_summary  TEXT,
    win_rate        REAL,
    net_pnl         REAL,
    created_by      INTEGER,
    created_at      TEXT
);

-- ── Extend backtests table ────────────────────────────────────────────────────
ALTER TABLE backtests ADD COLUMN user_id          INTEGER;
ALTER TABLE backtests ADD COLUMN backtest_name    TEXT;
ALTER TABLE backtests ADD COLUMN slippage_pct     REAL DEFAULT 0.05;
ALTER TABLE backtests ADD COLUMN brokerage_pct    REAL DEFAULT 0.03;
ALTER TABLE backtests ADD COLUMN trade_details    TEXT;   -- JSON array of trades
ALTER TABLE backtests ADD COLUMN monthly_pnl_json TEXT;   -- JSON: {YYYY-MM: pnl}
ALTER TABLE backtests ADD COLUMN equity_curve_json TEXT;  -- JSON array of {date, equity}
ALTER TABLE backtests ADD COLUMN xirr             REAL;
ALTER TABLE backtests ADD COLUMN recovery_factor  REAL;
ALTER TABLE backtests ADD COLUMN calmar_ratio     REAL;
ALTER TABLE backtests ADD COLUMN total_days       INTEGER;
ALTER TABLE backtests ADD COLUMN status           TEXT DEFAULT 'completed';
ALTER TABLE backtests ADD COLUMN error_message    TEXT;
ALTER TABLE backtests ADD COLUMN updated_at       TEXT;

-- ── Walk-Forward Optimization ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS walk_forward_results (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy_id     INTEGER NOT NULL,
    run_date        TEXT,
    window_type     TEXT DEFAULT 'rolling',  -- rolling/anchored
    in_sample_months INTEGER DEFAULT 12,
    out_sample_months INTEGER DEFAULT 3,
    windows_json    TEXT,  -- JSON: [{period, in_win_rate, out_win_rate, params}]
    avg_in_sample_wr   REAL,
    avg_out_sample_wr  REAL,
    robustness_score   REAL,
    recommended_params TEXT,
    status          TEXT DEFAULT 'completed',
    created_at      TEXT
);

-- ── Monte Carlo Analysis ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS monte_carlo_results (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy_id     INTEGER NOT NULL,
    backtest_id     INTEGER,
    simulations     INTEGER DEFAULT 1000,
    percentile_5    REAL,
    percentile_25   REAL,
    percentile_50   REAL,
    percentile_75   REAL,
    percentile_95   REAL,
    worst_drawdown  REAL,
    prob_positive   REAL,   -- probability of positive return
    results_json    TEXT,   -- JSON array of simulation outcomes
    created_at      TEXT
);

-- ── Live Alerts ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS live_alerts (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy_id     INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    alert_name      TEXT,
    universe        TEXT DEFAULT 'NSE_500',
    segment         TEXT DEFAULT 'EQ-SWG',
    timeframe       TEXT DEFAULT 'Daily',
    frequency       TEXT DEFAULT 'eod',   -- eod/intraday_15m/intraday_1h/realtime
    channels_json   TEXT DEFAULT '["in_app"]',  -- ["in_app","telegram","email","whatsapp"]
    last_triggered  TEXT,
    last_count      INTEGER DEFAULT 0,
    total_triggered INTEGER DEFAULT 0,
    status          TEXT DEFAULT 'active',  -- active/paused/expired
    valid_until     TEXT,
    created_at      TEXT,
    updated_at      TEXT
);

CREATE TABLE IF NOT EXISTS alert_history (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    alert_id        INTEGER NOT NULL,
    strategy_id     INTEGER,
    user_id         INTEGER,
    triggered_at    TEXT,
    symbols_json    TEXT,   -- JSON array of triggered symbols with data
    channels_sent   TEXT,   -- JSON: {"telegram":true,"email":false}
    message_preview TEXT
);

-- ── Strategy Marketplace ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS marketplace_listings (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy_id     INTEGER NOT NULL UNIQUE,
    seller_user_id  INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    listing_title   TEXT,
    listing_desc    TEXT,
    price           REAL DEFAULT 0,
    is_free         INTEGER DEFAULT 1,
    demo_backtest_id INTEGER,
    win_rate_claimed REAL,
    category        TEXT,
    tags            TEXT,
    rating          REAL DEFAULT 0,
    rating_count    INTEGER DEFAULT 0,
    total_sales     INTEGER DEFAULT 0,
    total_copies    INTEGER DEFAULT 0,
    approved        INTEGER DEFAULT 0,
    approved_by     INTEGER,
    approved_at     TEXT,
    status          TEXT DEFAULT 'pending',   -- pending/approved/rejected/suspended
    created_at      TEXT,
    updated_at      TEXT
);

CREATE TABLE IF NOT EXISTS marketplace_reviews (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    listing_id      INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    rating          INTEGER NOT NULL,
    review_text     TEXT,
    verified_buyer  INTEGER DEFAULT 0,
    created_at      TEXT,
    UNIQUE(listing_id, user_id)
);

CREATE TABLE IF NOT EXISTS marketplace_purchases (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    listing_id      INTEGER NOT NULL,
    strategy_id     INTEGER NOT NULL,
    buyer_user_id   INTEGER NOT NULL,
    copied_strategy_id INTEGER,
    amount_paid     REAL DEFAULT 0,
    payment_ref     TEXT,
    purchased_at    TEXT
);

-- ── AI Strategy Templates ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ai_strategy_requests (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    prompt          TEXT NOT NULL,
    segment         TEXT,
    category        TEXT,
    target_win_rate REAL,
    universe        TEXT,
    generated_strategy_id INTEGER,
    status          TEXT DEFAULT 'pending',  -- pending/processing/completed/failed
    created_at      TEXT,
    completed_at    TEXT
);

-- ── Alert delivery channels ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS alert_channels (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    channel_type    TEXT NOT NULL,   -- telegram/whatsapp/email/webhook
    config_json     TEXT,            -- {"chat_id":"xxx"} or {"phone":"xxx"} etc
    verified        INTEGER DEFAULT 0,
    status          TEXT DEFAULT 'active',
    created_at      TEXT,
    UNIQUE(user_id, channel_type)
);

-- ── Strategy Performance Monitoring ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS strategy_performance (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy_id     INTEGER NOT NULL,
    period          TEXT NOT NULL,   -- YYYY-MM
    signals_given   INTEGER DEFAULT 0,
    trades_taken    INTEGER DEFAULT 0,
    wins            INTEGER DEFAULT 0,
    losses          INTEGER DEFAULT 0,
    win_rate        REAL DEFAULT 0,
    avg_return      REAL DEFAULT 0,
    net_pnl         REAL DEFAULT 0,
    health_score    INTEGER DEFAULT 100,  -- 0-100 auto-computed
    health_status   TEXT DEFAULT 'healthy', -- healthy/degrading/critical
    notes           TEXT,
    computed_at     TEXT,
    UNIQUE(strategy_id, period)
);

-- ── Indexes ───────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_strategy_conditions ON strategy_conditions(strategy_id, condition_group);
CREATE INDEX IF NOT EXISTS idx_live_alerts         ON live_alerts(user_id, status);
CREATE INDEX IF NOT EXISTS idx_alert_history       ON alert_history(alert_id, triggered_at);
CREATE INDEX IF NOT EXISTS idx_marketplace         ON marketplace_listings(status, category);
CREATE INDEX IF NOT EXISTS idx_strategy_versions   ON strategy_versions(strategy_id, version);
CREATE INDEX IF NOT EXISTS idx_strategy_perf       ON strategy_performance(strategy_id, period);
