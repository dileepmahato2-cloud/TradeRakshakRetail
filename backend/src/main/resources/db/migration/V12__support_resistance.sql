-- =============================================================================
-- TradeRakshak™ v11.4 — V12: Support & Resistance Cache
-- Stores pre-computed S&R levels for major indices
-- Sahi App Logic: Pivot + Fibonacci + Fractal + Volume Profile + Psychological
-- =============================================================================

-- ── 1. S&R Level Cache Table ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sr_levels_cache (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    index_key       TEXT NOT NULL,          -- NIFTY50 | BANKNIFTY | FINNIFTY | NIFTY500
    index_label     TEXT NOT NULL,          -- Display name
    trade_date      TEXT NOT NULL,          -- Date of computation
    current_price   REAL,                   -- CMP at time of computation
    zone_type       TEXT NOT NULL,          -- support | resistance
    price           REAL NOT NULL,          -- Level price
    name            TEXT,                   -- e.g. "Pivot R1 + Weekly R1"
    source          TEXT,                   -- Methods that identified this level
    grade           TEXT,                   -- VERY STRONG | STRONG | MODERATE | WEAK
    final_score     REAL DEFAULT 0,
    confluence      INTEGER DEFAULT 1,
    is_confluence   INTEGER DEFAULT 0,
    touch_count     INTEGER DEFAULT 0,
    dist_pct        REAL,                   -- % distance from CMP
    dist_abs        REAL,                   -- Absolute distance from CMP
    proximity       TEXT,                   -- AT LEVEL | VERY NEAR | NEAR | MODERATE | FAR
    is_active       INTEGER DEFAULT 0,      -- within 2% of CMP
    trend_bias      TEXT,                   -- BULLISH | BEARISH | NEUTRAL
    atr_14          REAL,
    computed_at     TEXT,
    created_at      TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_sr_cache_index_date ON sr_levels_cache(index_key, trade_date);
CREATE INDEX IF NOT EXISTS idx_sr_cache_active     ON sr_levels_cache(index_key, is_active);

-- ── 2. index_data — stores index OHLCV (fallback if not in market_data) ───────
CREATE TABLE IF NOT EXISTS index_data (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol      TEXT NOT NULL,              -- NIFTY 50, NIFTY BANK, etc.
    trade_date  TEXT NOT NULL,
    open        REAL,
    high        REAL,
    low         REAL,
    close       REAL,
    volume      REAL DEFAULT 0,
    prev_close  REAL,
    source      TEXT DEFAULT 'NSE',
    created_at  TEXT DEFAULT (datetime('now')),
    UNIQUE(symbol, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_index_data_sym_date ON index_data(symbol, trade_date DESC);

-- ── 3. Seed: Add NIFTY indices to stock_master if not present ─────────────────
INSERT OR IGNORE INTO stock_master (symbol, company_name, exchange, segment, sector)
VALUES
    ('NIFTY 50',          'Nifty 50 Index',             'NSE', 'INDEX', 'Broad Market'),
    ('NIFTY BANK',        'Nifty Bank Index',            'NSE', 'INDEX', 'Banking'),
    ('NIFTY FIN SERVICE', 'Nifty Financial Services',    'NSE', 'INDEX', 'Financial Services'),
    ('NIFTY 500',         'Nifty 500 Index',             'NSE', 'INDEX', 'Broad Market');

-- ── 4. Feature config for S&R widget ─────────────────────────────────────────
INSERT OR IGNORE INTO feature_configs
    (feature_key, feature_name, feature_group, is_enabled, description, created_at)
VALUES
    ('sr_widget',        'Support & Resistance Widget',        'trading', 1, 'Dashboard S&R levels for Nifty 50, Bank Nifty, Fin Nifty, Nifty 500', datetime('now')),
    ('sr_alert_active',  'S&R Proximity Alerts (Active Zone)', 'trading', 1, 'Alert when index price is within 0.5% of S&R level', datetime('now'));
