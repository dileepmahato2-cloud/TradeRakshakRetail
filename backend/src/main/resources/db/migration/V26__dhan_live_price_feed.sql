ALTER TABLE tracked_stocks ADD COLUMN IF NOT EXISTS current_price_source TEXT;
ALTER TABLE tracked_stocks ADD COLUMN IF NOT EXISTS trading_symbol TEXT;
ALTER TABLE tracked_stocks ADD COLUMN IF NOT EXISTS dhan_security_id TEXT;
ALTER TABLE tracked_stocks ADD COLUMN IF NOT EXISTS exchange_segment TEXT;
ALTER TABLE tracked_stocks ADD COLUMN IF NOT EXISTS last_traded_price REAL;
ALTER TABLE tracked_stocks ADD COLUMN IF NOT EXISTS last_trade_time TEXT;
ALTER TABLE tracked_stocks ADD COLUMN IF NOT EXISTS price_updated_at TEXT;

CREATE TABLE IF NOT EXISTS live_price_quotes (
    id                    BIGSERIAL PRIMARY KEY,
    broker_name            TEXT NOT NULL DEFAULT 'dhan',
    exchange_segment       TEXT NOT NULL,
    broker_security_id     TEXT NOT NULL,
    dhan_security_id       TEXT,
    symbol                 TEXT NOT NULL,
    trading_symbol         TEXT,
    current_price          REAL,
    last_traded_price      REAL,
    bid_price              REAL,
    ask_price              REAL,
    open_price             REAL,
    high_price             REAL,
    low_price              REAL,
    close_price            REAL,
    volume                 REAL,
    last_trade_time        TEXT,
    change_value           REAL,
    change_percent         REAL,
    tick_received_at       TEXT,
    price_updated_at       TEXT,
    raw_json               TEXT,
    created_at             TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    updated_at             TEXT,
    UNIQUE(broker_name, exchange_segment, broker_security_id)
);

CREATE INDEX IF NOT EXISTS idx_lpq_symbol ON live_price_quotes(symbol, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_lpq_tick ON live_price_quotes(tick_received_at DESC);
CREATE INDEX IF NOT EXISTS idx_ts_dhan_security ON tracked_stocks(dhan_security_id, exchange_segment);

CREATE TABLE IF NOT EXISTS live_price_subscriptions (
    id                    BIGSERIAL PRIMARY KEY,
    broker_name            TEXT NOT NULL DEFAULT 'dhan',
    institution_id         INTEGER,
    symbol                 TEXT NOT NULL,
    trading_symbol         TEXT,
    underlying_symbol      TEXT,
    exchange_segment       TEXT,
    broker_security_id     TEXT,
    status                 TEXT NOT NULL,
    reason                 TEXT,
    subscribed_at          TEXT,
    last_tick_at           TEXT,
    created_at             TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    updated_at             TEXT,
    UNIQUE(broker_name, institution_id, symbol)
);

CREATE INDEX IF NOT EXISTS idx_lps_status ON live_price_subscriptions(status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_lps_security ON live_price_subscriptions(broker_name, exchange_segment, broker_security_id);

CREATE TABLE IF NOT EXISTS live_price_service_events (
    id                    BIGSERIAL PRIMARY KEY,
    broker_name            TEXT NOT NULL DEFAULT 'dhan',
    event_type             TEXT NOT NULL,
    severity               TEXT DEFAULT 'INFO',
    symbol                 TEXT,
    exchange_segment       TEXT,
    broker_security_id     TEXT,
    message                TEXT,
    details_json           TEXT,
    created_at             TEXT DEFAULT (CURRENT_TIMESTAMP::text)
);

CREATE INDEX IF NOT EXISTS idx_lpse_created ON live_price_service_events(created_at DESC);
