CREATE TABLE IF NOT EXISTS next_day_prediction_runs (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id            INTEGER NOT NULL,
    institution_id     INTEGER DEFAULT 1,
    run_date           TEXT NOT NULL,
    run_time           TEXT DEFAULT '15:15',
    universe           TEXT DEFAULT 'NSE_500',
    index_score        INTEGER DEFAULT 0,
    index_classification TEXT,
    index_confidence   INTEGER DEFAULT 0,
    gap_up_probability INTEGER DEFAULT 0,
    gap_down_probability INTEGER DEFAULT 0,
    model_version      TEXT DEFAULT '3_15_NEXT_DAY_V1',
    input_json         TEXT,
    result_json        TEXT,
    data_quality_json  TEXT,
    status             TEXT DEFAULT 'completed',
    created_at         TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_next_day_runs_user_date
    ON next_day_prediction_runs(user_id, run_date DESC, id DESC);

CREATE TABLE IF NOT EXISTS next_day_index_scores (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id             INTEGER NOT NULL,
    index_symbol       TEXT NOT NULL,
    score              INTEGER DEFAULT 0,
    classification     TEXT,
    confidence         INTEGER DEFAULT 0,
    gap_up_probability INTEGER DEFAULT 0,
    gap_down_probability INTEGER DEFAULT 0,
    detail_json        TEXT,
    created_at         TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_next_day_index_scores_run
    ON next_day_index_scores(run_id, index_symbol);

CREATE TABLE IF NOT EXISTS next_day_stock_scores (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id             INTEGER NOT NULL,
    exchange           TEXT DEFAULT 'NSE',
    symbol             TEXT NOT NULL,
    sector             TEXT,
    score              INTEGER DEFAULT 0,
    classification     TEXT,
    confidence         INTEGER DEFAULT 0,
    detail_json        TEXT,
    missing_inputs_json TEXT,
    created_at         TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_next_day_stock_scores_run_score
    ON next_day_stock_scores(run_id, score DESC);

CREATE TABLE IF NOT EXISTS next_day_prediction_backtests (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id            INTEGER NOT NULL,
    institution_id     INTEGER DEFAULT 1,
    period_days        INTEGER NOT NULL,
    from_date          TEXT,
    to_date            TEXT,
    total_predictions  INTEGER DEFAULT 0,
    correct_predictions INTEGER DEFAULT 0,
    accuracy_pct       REAL DEFAULT 0,
    gap_accuracy_pct   REAL DEFAULT 0,
    monthly_accuracy_json TEXT,
    yearly_accuracy_json TEXT,
    sector_accuracy_json TEXT,
    stock_accuracy_json TEXT,
    status             TEXT DEFAULT 'completed',
    message            TEXT,
    created_at         TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_next_day_backtests_user
    ON next_day_prediction_backtests(user_id, period_days, created_at DESC);
