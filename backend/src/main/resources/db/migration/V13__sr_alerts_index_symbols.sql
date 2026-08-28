-- =============================================================================
-- TradeRakshak™ v11.4 → v11.5 — V13 Migration
-- Support & Resistance Engine: Index Data + SR Cache Tables
--
-- Enterprise Policy:
--   • Backward-compatible: no existing tables modified
--   • IF NOT EXISTS guards ensure safe re-runs
--   • Rollback-safe: all new tables, no column drops
--   • Data migration: seeds index symbol map for all 4 supported indices
-- =============================================================================

-- ── 1. INDEX_DATA — stores OHLCV for NSE indices (primary S&R data source) ───
--    Used when market_data table lacks index-level records.
--    Symbols: NIFTY 50, NIFTY 500, NIFTY FIN SERVICE, NIFTY BANK
CREATE TABLE IF NOT EXISTS index_data (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol      TEXT NOT NULL,
    exchange    TEXT DEFAULT 'NSE',
    trade_date  TEXT NOT NULL,
    open        REAL,
    high        REAL,
    low         REAL,
    close       REAL NOT NULL,
    volume      REAL DEFAULT 0,
    prev_close  REAL,
    change_pct  REAL DEFAULT 0,
    created_at  TEXT DEFAULT (datetime('now')),
    UNIQUE(symbol, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_index_data_symbol_date ON index_data(symbol, trade_date DESC);
CREATE INDEX IF NOT EXISTS idx_index_data_date        ON index_data(trade_date DESC);

-- ── 2. INDEX_SYMBOLS — canonical symbol master for all tracked indices ────────
CREATE TABLE IF NOT EXISTS index_symbols (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    index_key       TEXT UNIQUE NOT NULL,   -- NIFTY50, BANKNIFTY, FINNIFTY, NIFTY500
    symbol          TEXT NOT NULL,           -- as stored in market_data / index_data
    display_label   TEXT NOT NULL,           -- shown on UI: "Nifty 50"
    major_step      REAL DEFAULT 500,        -- psychological level interval
    minor_step      REAL DEFAULT 100,        -- minor psychological level interval
    lot_size        INTEGER DEFAULT 50,      -- F&O lot size (informational)
    is_active       INTEGER DEFAULT 1,
    sort_order      INTEGER DEFAULT 0,
    created_at      TEXT DEFAULT (datetime('now'))
);

-- Seed canonical index symbol map (idempotent)
INSERT OR IGNORE INTO index_symbols (index_key, symbol, display_label, major_step, minor_step, lot_size, sort_order) VALUES
    ('NIFTY50',   'NIFTY 50',           'Nifty 50',    500,  100,  50,  1),
    ('BANKNIFTY', 'NIFTY BANK',         'Bank Nifty',  1000, 500,  15,  2),
    ('FINNIFTY',  'NIFTY FIN SERVICE',  'Fin Nifty',   500,  100,  40,  3),
    ('NIFTY500',  'NIFTY 500',          'Nifty 500',   100,  50,   0,   4);

-- ── 3. SR_CACHE — computed S&R levels cache (performance optimization) ────────
--    Populated by SupportResistanceService; refreshed every 5 minutes in market hours
--    Allows instant dashboard load without re-computing all levels on each request
CREATE TABLE IF NOT EXISTS sr_cache (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    index_key       TEXT NOT NULL,
    as_of_date      TEXT NOT NULL,
    computed_at     TEXT NOT NULL,
    current_price   REAL,
    trend_bias      TEXT DEFAULT 'NEUTRAL',
    atr_14          REAL DEFAULT 0,
    total_levels    INTEGER DEFAULT 0,
    supports_json   TEXT,           -- JSON array of support level objects
    resistances_json TEXT,          -- JSON array of resistance level objects
    nearest_support_json  TEXT,     -- JSON object of nearest support
    nearest_resistance_json TEXT,   -- JSON object of nearest resistance
    data_source     TEXT DEFAULT 'market_data',  -- market_data / index_data / demo
    is_valid        INTEGER DEFAULT 1,
    created_at      TEXT DEFAULT (datetime('now')),
    UNIQUE(index_key, as_of_date)
);

CREATE INDEX IF NOT EXISTS idx_sr_cache_key_date ON sr_cache(index_key, as_of_date DESC);

-- ── 4. SR_ALERTS — tracks when price enters S&R proximity zones ──────────────
--    Used by Notification Engine to fire proximity alerts
CREATE TABLE IF NOT EXISTS sr_alerts (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER,                    -- NULL = platform-wide alert
    institution_id  INTEGER DEFAULT 1,
    index_key       TEXT NOT NULL,
    level_price     REAL NOT NULL,
    level_type      TEXT NOT NULL,             -- support / resistance
    level_name      TEXT,
    level_grade     TEXT,                      -- VERY STRONG / STRONG / MODERATE / WEAK
    alert_type      TEXT DEFAULT 'proximity',  -- proximity / breach / bounce
    proximity_pct   REAL DEFAULT 1.0,          -- fire when within X% of level
    is_enabled      INTEGER DEFAULT 1,
    last_fired_at   TEXT,
    fire_count      INTEGER DEFAULT 0,
    created_at      TEXT DEFAULT (datetime('now')),
    updated_at      TEXT
);

CREATE INDEX IF NOT EXISTS idx_sr_alerts_user    ON sr_alerts(user_id, is_enabled);
CREATE INDEX IF NOT EXISTS idx_sr_alerts_index   ON sr_alerts(index_key, is_enabled);

-- ── 5. Feature flag seed — enable SR widget for all plans ────────────────────
INSERT OR IGNORE INTO feature_configs
    (feature_key, feature_name, feature_group, is_enabled, min_plan, allowed_roles, description, created_at)
VALUES
    ('sr_widget',       'Support & Resistance Widget',      'trading', 1, 'Free Trial', 'ALL',
     'Dashboard S&R widget showing Pivot, Fib, Fractal, Volume profile levels for 4 major indices',
     datetime('now')),
    ('sr_alerts',       'S&R Proximity Alerts',             'trading', 1, 'Basic',      'ALL',
     'Email/Telegram/Push notifications when index approaches strong S&R levels',
     datetime('now')),
    ('sr_export',       'S&R Level Export (CSV/PDF)',       'trading', 1, 'Pro',        'ALL',
     'Export current S&R levels for all indices as CSV or PDF report',
     datetime('now'));

-- =============================================================================
-- END V12
-- =============================================================================
