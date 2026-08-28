CREATE TABLE IF NOT EXISTS trading_auto_settings (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL,
    institution_id INTEGER DEFAULT 1,
    strategy_key TEXT NOT NULL,
    strategy_name TEXT,
    mode TEXT DEFAULT 'PAPER',
    auto_buy INTEGER DEFAULT 0,
    auto_sell INTEGER DEFAULT 0,
    automated_execution INTEGER DEFAULT 0,
    buy_time TEXT,
    sell_time TEXT,
    investment_amount REAL DEFAULT 0,
    max_capital_allocation REAL DEFAULT 0,
    max_daily_trades INTEGER DEFAULT 0,
    risk_per_trade_pct REAL DEFAULT 0,
    stop_loss_pct REAL DEFAULT 0,
    trailing_stop_loss_pct REAL DEFAULT 0,
    target_pct REAL DEFAULT 0,
    max_daily_loss_limit REAL DEFAULT 0,
    max_open_positions INTEGER DEFAULT 0,
    max_stock_allocation REAL DEFAULT 0,
    max_sector_allocation REAL DEFAULT 0,
    circuit_filter_protection INTEGER DEFAULT 1,
    volume_validation INTEGER DEFAULT 1,
    liquidity_validation INTEGER DEFAULT 1,
    status TEXT DEFAULT 'inactive',
    created_at TEXT,
    updated_at TEXT,
    UNIQUE(user_id, strategy_key)
);

CREATE TABLE IF NOT EXISTS trading_audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER,
    institution_id INTEGER DEFAULT 1,
    strategy_key TEXT,
    strategy_name TEXT,
    mode TEXT,
    event_type TEXT,
    symbol TEXT,
    side TEXT,
    status TEXT,
    message TEXT,
    broker_name TEXT,
    broker_response TEXT,
    details_json TEXT,
    created_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_trading_auto_user ON trading_auto_settings(user_id, strategy_key);
CREATE INDEX IF NOT EXISTS idx_trading_audit_user ON trading_audit_log(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_trading_audit_strategy ON trading_audit_log(strategy_key, event_type, created_at DESC);
