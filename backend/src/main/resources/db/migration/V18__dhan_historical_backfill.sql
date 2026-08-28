CREATE TABLE IF NOT EXISTS dhan_instruments (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    security_id        TEXT NOT NULL,
    exchange           TEXT,
    segment_code       TEXT,
    exchange_segment   TEXT,
    instrument         TEXT,
    instrument_type    TEXT,
    symbol_name        TEXT,
    trading_symbol     TEXT,
    display_name       TEXT,
    isin               TEXT,
    underlying_security_id TEXT,
    underlying_symbol  TEXT,
    expiry_date        TEXT,
    strike_price       REAL,
    option_type        TEXT,
    lot_size           REAL,
    raw_json           TEXT,
    updated_at         TEXT,
    UNIQUE(security_id, exchange_segment)
);

CREATE INDEX IF NOT EXISTS idx_dhan_inst_scope ON dhan_instruments(exchange, exchange_segment, instrument);
CREATE INDEX IF NOT EXISTS idx_dhan_inst_symbol ON dhan_instruments(symbol_name, trading_symbol, display_name);

CREATE TABLE IF NOT EXISTS dhan_historical_jobs (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id            INTEGER NOT NULL,
    institution_id     INTEGER DEFAULT 1,
    status             TEXT DEFAULT 'queued',
    mode               TEXT DEFAULT 'DAILY',
    interval_minutes   INTEGER,
    from_date          TEXT,
    to_date            TEXT,
    requested_from_date TEXT,
    requested_to_date  TEXT,
    exchanges          TEXT,
    segments           TEXT,
    instrument_filter  TEXT,
    total_instruments  INTEGER DEFAULT 0,
    processed_instruments INTEGER DEFAULT 0,
    total_chunks       INTEGER DEFAULT 0,
    processed_chunks   INTEGER DEFAULT 0,
    imported_candles   INTEGER DEFAULT 0,
    failed_chunks      INTEGER DEFAULT 0,
    current_symbol     TEXT,
    message            TEXT,
    started_at         TEXT,
    completed_at       TEXT,
    stop_requested     INTEGER DEFAULT 0,
    created_at         TEXT DEFAULT (datetime('now')),
    updated_at         TEXT
);

CREATE INDEX IF NOT EXISTS idx_dhan_jobs_user ON dhan_historical_jobs(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dhan_jobs_status ON dhan_historical_jobs(status);

CREATE TABLE IF NOT EXISTS dhan_historical_candles (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id             INTEGER,
    security_id        TEXT NOT NULL,
    exchange           TEXT,
    exchange_segment   TEXT,
    instrument         TEXT,
    symbol_name        TEXT,
    trading_symbol     TEXT,
    candle_mode        TEXT NOT NULL,
    interval_minutes   INTEGER,
    candle_time        TEXT NOT NULL,
    trade_date         TEXT,
    open               REAL,
    high               REAL,
    low                REAL,
    close              REAL,
    volume             REAL,
    open_interest      REAL,
    source             TEXT DEFAULT 'DHAN',
    created_at         TEXT DEFAULT (datetime('now')),
    UNIQUE(security_id, exchange_segment, candle_mode, interval_minutes, candle_time)
);

CREATE INDEX IF NOT EXISTS idx_dhan_candles_symbol_time ON dhan_historical_candles(symbol_name, candle_time);
CREATE INDEX IF NOT EXISTS idx_dhan_candles_segment_time ON dhan_historical_candles(exchange_segment, candle_time);
