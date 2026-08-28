-- TradeRakshak v11.5 - normalized broker API data warehouse
-- Common storage for Dhan, Angel One, Zerodha, Upstox, Fyers, Shoonya,
-- Kotak, ICICI, and later broker adapters.

ALTER TABLE broker_data_snapshots ADD COLUMN IF NOT EXISTS broker_credential_id INTEGER;
ALTER TABLE broker_data_snapshots ADD COLUMN IF NOT EXISTS request_json TEXT;
ALTER TABLE broker_data_snapshots ADD COLUMN IF NOT EXISTS normalized_table TEXT;
ALTER TABLE broker_data_snapshots ADD COLUMN IF NOT EXISTS rows_imported INTEGER DEFAULT 0;
ALTER TABLE broker_data_snapshots ADD COLUMN IF NOT EXISTS external_request_id TEXT;

CREATE TABLE IF NOT EXISTS broker_api_capabilities (
    id                  BIGSERIAL PRIMARY KEY,
    broker_name          TEXT NOT NULL,
    broker_label         TEXT NOT NULL,
    data_type            TEXT NOT NULL,
    api_family           TEXT,
    api_endpoint_hint    TEXT,
    target_table         TEXT NOT NULL,
    requires_instrument  INTEGER DEFAULT 0,
    requires_expiry      INTEGER DEFAULT 0,
    requires_interval    INTEGER DEFAULT 0,
    max_history_note     TEXT,
    status               TEXT DEFAULT 'planned',
    updated_at           TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    UNIQUE(broker_name, data_type)
);

CREATE TABLE IF NOT EXISTS broker_api_fetch_jobs (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              INTEGER NOT NULL,
    institution_id       INTEGER DEFAULT 1,
    broker_credential_id INTEGER,
    broker_name          TEXT NOT NULL,
    broker_label         TEXT,
    data_type            TEXT NOT NULL,
    source               TEXT DEFAULT 'BROKER',
    from_date            TEXT,
    to_date              TEXT,
    interval_minutes     INTEGER,
    exchange             TEXT,
    segment              TEXT,
    instrument_symbol    TEXT,
    broker_security_id   TEXT,
    expiry_date          TEXT,
    request_json         TEXT,
    status               TEXT DEFAULT 'queued',
    message              TEXT,
    target_table         TEXT,
    rows_requested       INTEGER DEFAULT 0,
    rows_imported        INTEGER DEFAULT 0,
    rows_failed          INTEGER DEFAULT 0,
    started_at           TEXT,
    completed_at         TEXT,
    created_at           TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    updated_at           TEXT
);

CREATE INDEX IF NOT EXISTS idx_bafj_user_date ON broker_api_fetch_jobs(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_bafj_broker_type ON broker_api_fetch_jobs(broker_name, data_type, status);

CREATE TABLE IF NOT EXISTS broker_instruments (
    id                     BIGSERIAL PRIMARY KEY,
    broker_name             TEXT NOT NULL,
    broker_credential_id    INTEGER,
    broker_security_id      TEXT NOT NULL,
    exchange                TEXT,
    exchange_segment        TEXT,
    segment                 TEXT,
    instrument_type         TEXT,
    symbol                  TEXT,
    trading_symbol          TEXT,
    display_name            TEXT,
    isin                    TEXT,
    underlying_security_id  TEXT,
    underlying_symbol       TEXT,
    expiry_date             TEXT,
    strike_price            REAL,
    option_type             TEXT,
    lot_size                REAL,
    tick_size               REAL,
    raw_json                TEXT,
    updated_at              TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    UNIQUE(broker_name, broker_security_id, exchange_segment)
);

CREATE INDEX IF NOT EXISTS idx_bi_symbol ON broker_instruments(broker_name, symbol, trading_symbol);
CREATE INDEX IF NOT EXISTS idx_bi_scope ON broker_instruments(broker_name, exchange, exchange_segment, instrument_type);

CREATE TABLE IF NOT EXISTS broker_market_candles (
    id                   BIGSERIAL PRIMARY KEY,
    source_job_type       TEXT,
    source_job_id         INTEGER,
    broker_name           TEXT NOT NULL,
    broker_credential_id  INTEGER,
    data_type             TEXT DEFAULT 'Daily Bhavcopy (OHLCV)',
    exchange              TEXT,
    exchange_segment      TEXT,
    segment               TEXT,
    instrument_type       TEXT,
    broker_security_id    TEXT NOT NULL,
    symbol                TEXT,
    trading_symbol        TEXT,
    candle_mode           TEXT NOT NULL,
    interval_minutes      INTEGER,
    interval_key          INTEGER NOT NULL DEFAULT 0,
    candle_time           TEXT NOT NULL,
    trade_date            TEXT,
    open                  REAL,
    high                  REAL,
    low                   REAL,
    close                 REAL,
    prev_close            REAL,
    vwap                  REAL,
    volume                REAL,
    total_volume          REAL,
    turnover              REAL,
    delivery_volume       REAL,
    delivery_pct          REAL,
    open_interest         REAL,
    source                TEXT DEFAULT 'BROKER',
    raw_json              TEXT,
    created_at            TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    updated_at            TEXT,
    UNIQUE(broker_name, broker_security_id, exchange_segment, candle_mode, interval_key, candle_time)
);

CREATE INDEX IF NOT EXISTS idx_bmc_symbol_date ON broker_market_candles(symbol, trade_date DESC);
CREATE INDEX IF NOT EXISTS idx_bmc_broker_date ON broker_market_candles(broker_name, data_type, trade_date DESC);

CREATE TABLE IF NOT EXISTS broker_option_chain_snapshots (
    id                   BIGSERIAL PRIMARY KEY,
    source_job_id         INTEGER,
    broker_name           TEXT NOT NULL,
    broker_credential_id  INTEGER,
    exchange              TEXT DEFAULT 'NSE',
    underlying            TEXT NOT NULL,
    broker_security_id    TEXT,
    trade_date            TEXT,
    expiry_date           TEXT,
    strike                REAL,
    option_type           TEXT,
    ltp                   REAL,
    bid                   REAL,
    ask                   REAL,
    open                  REAL,
    high                  REAL,
    low                   REAL,
    close                 REAL,
    prev_close            REAL,
    volume                REAL,
    open_interest         REAL,
    change_oi             REAL,
    implied_volatility    REAL,
    delta                 REAL,
    gamma                 REAL,
    theta                 REAL,
    vega                  REAL,
    underlying_price      REAL,
    raw_json              TEXT,
    created_at            TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    UNIQUE(broker_name, underlying, trade_date, expiry_date, strike, option_type)
);

CREATE TABLE IF NOT EXISTS broker_fii_dii_flows (
    id                   BIGSERIAL PRIMARY KEY,
    source_job_id         INTEGER,
    broker_name           TEXT NOT NULL,
    broker_credential_id  INTEGER,
    trade_date            TEXT NOT NULL,
    market_segment        TEXT DEFAULT 'EQUITY',
    fii_buy               REAL,
    fii_sell              REAL,
    fii_net               REAL,
    dii_buy               REAL,
    dii_sell              REAL,
    dii_net               REAL,
    raw_json              TEXT,
    created_at            TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    UNIQUE(broker_name, trade_date, market_segment)
);

CREATE TABLE IF NOT EXISTS broker_corporate_actions (
    id                   BIGSERIAL PRIMARY KEY,
    source_job_id         INTEGER,
    broker_name           TEXT NOT NULL,
    broker_credential_id  INTEGER,
    exchange              TEXT,
    symbol                TEXT NOT NULL,
    action_type           TEXT NOT NULL,
    ex_date               TEXT,
    record_date           TEXT,
    ratio_text            TEXT,
    amount                REAL,
    description           TEXT,
    raw_json              TEXT,
    created_at            TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    UNIQUE(broker_name, symbol, action_type, ex_date, record_date)
);

CREATE TABLE IF NOT EXISTS broker_fundamentals (
    id                   BIGSERIAL PRIMARY KEY,
    source_job_id         INTEGER,
    broker_name           TEXT NOT NULL,
    broker_credential_id  INTEGER,
    exchange              TEXT,
    symbol                TEXT NOT NULL,
    period_type           TEXT DEFAULT 'Quarterly',
    period                TEXT,
    revenue               REAL,
    ebitda                REAL,
    net_profit            REAL,
    eps                   REAL,
    book_value            REAL,
    roe                   REAL,
    roce                  REAL,
    debt_equity           REAL,
    pe                    REAL,
    pb                    REAL,
    promoter_holding      REAL,
    fii_holding           REAL,
    dii_holding           REAL,
    public_holding        REAL,
    raw_json              TEXT,
    created_at            TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    UNIQUE(broker_name, exchange, symbol, period_type, period)
);

CREATE TABLE IF NOT EXISTS broker_delivery_volumes (
    id                   BIGSERIAL PRIMARY KEY,
    source_job_id         INTEGER,
    broker_name           TEXT NOT NULL,
    broker_credential_id  INTEGER,
    exchange              TEXT,
    symbol                TEXT NOT NULL,
    trade_date            TEXT NOT NULL,
    traded_quantity       REAL,
    delivery_quantity     REAL,
    delivery_pct          REAL,
    raw_json              TEXT,
    created_at            TEXT DEFAULT (CURRENT_TIMESTAMP::text),
    UNIQUE(broker_name, exchange, symbol, trade_date)
);

CREATE TABLE IF NOT EXISTS broker_portfolio_snapshots (
    id                   BIGSERIAL PRIMARY KEY,
    source_job_id         INTEGER,
    user_id               INTEGER NOT NULL,
    institution_id        INTEGER DEFAULT 1,
    broker_name           TEXT NOT NULL,
    broker_credential_id  INTEGER,
    snapshot_type         TEXT NOT NULL,
    symbol                TEXT,
    exchange              TEXT,
    product_type          TEXT,
    quantity              REAL,
    average_price         REAL,
    last_price            REAL,
    pnl                   REAL,
    payload_json          TEXT,
    created_at            TEXT DEFAULT (CURRENT_TIMESTAMP::text)
);

CREATE INDEX IF NOT EXISTS idx_bps_user_broker ON broker_portfolio_snapshots(user_id, broker_name, snapshot_type, created_at DESC);

INSERT INTO broker_api_capabilities
    (broker_name, broker_label, data_type, api_family, api_endpoint_hint, target_table, requires_instrument, requires_expiry, requires_interval, max_history_note, status)
VALUES
    ('dhan', 'Dhan', 'Daily Bhavcopy (OHLCV)', 'charts', 'https://api.dhan.co/v2/charts/historical', 'broker_market_candles', 1, 0, 0, 'Daily history by security id.', 'enabled'),
    ('dhan', 'Dhan', 'Intraday Candles', 'charts', 'https://api.dhan.co/v2/charts/intraday', 'broker_market_candles', 1, 0, 1, 'Intraday candle history is limited by Dhan API rules.', 'enabled'),
    ('dhan', 'Dhan', 'Option Chain', 'option-chain', 'broker option-chain endpoint', 'broker_option_chain_snapshots', 1, 1, 0, 'Requires underlying security id and expiry.', 'planned'),
    ('dhan', 'Dhan', 'Holdings / Positions', 'portfolio', 'https://api.dhan.co/v2/holdings, https://api.dhan.co/v2/positions', 'broker_portfolio_snapshots', 0, 0, 0, 'User account snapshot.', 'enabled'),
    ('angel', 'Angel One SmartAPI', 'Daily Bhavcopy (OHLCV)', 'market-data', 'SmartAPI candle data', 'broker_market_candles', 1, 0, 1, 'Requires Angel token/instrument mapping.', 'planned'),
    ('angel', 'Angel One SmartAPI', 'Option Chain', 'market-data', 'SmartAPI option-chain/quote data', 'broker_option_chain_snapshots', 1, 1, 0, 'Requires expiry and symbol token.', 'planned'),
    ('zerodha', 'Zerodha Kite Connect', 'Daily Bhavcopy (OHLCV)', 'historical', '/instruments/historical', 'broker_market_candles', 1, 0, 1, 'Requires instrument token.', 'planned'),
    ('upstox', 'Upstox API v2', 'Daily Bhavcopy (OHLCV)', 'historical-candle', '/v2/historical-candle', 'broker_market_candles', 1, 0, 1, 'Requires instrument key.', 'planned'),
    ('fyers', 'Fyers API v3', 'Daily Bhavcopy (OHLCV)', 'history', '/data/history', 'broker_market_candles', 1, 0, 1, 'Requires symbol and resolution.', 'planned')
ON CONFLICT (broker_name, data_type) DO UPDATE SET
    broker_label=EXCLUDED.broker_label,
    api_family=EXCLUDED.api_family,
    api_endpoint_hint=EXCLUDED.api_endpoint_hint,
    target_table=EXCLUDED.target_table,
    requires_instrument=EXCLUDED.requires_instrument,
    requires_expiry=EXCLUDED.requires_expiry,
    requires_interval=EXCLUDED.requires_interval,
    max_history_note=EXCLUDED.max_history_note,
    status=EXCLUDED.status,
    updated_at=CURRENT_TIMESTAMP::text;
