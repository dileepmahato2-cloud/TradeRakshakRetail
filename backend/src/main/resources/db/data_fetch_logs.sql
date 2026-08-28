CREATE TABLE IF NOT EXISTS data_fetch_logs (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT,
    data_type       VARCHAR(100),
    source          VARCHAR(50),
    from_date       VARCHAR(20),
    to_date         VARCHAR(20),
    status          VARCHAR(30),
    error_code      VARCHAR(10),
    rows_imported   INTEGER DEFAULT 0,
    files_fetched   INTEGER DEFAULT 0,
    skipped_count   INTEGER DEFAULT 0,
    duration_ms     BIGINT DEFAULT 0,
    message         TEXT,
    details_json    JSONB DEFAULT '{}'::jsonb,
    created_at      VARCHAR(30)
);

CREATE INDEX IF NOT EXISTS idx_fetch_logs_user ON data_fetch_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_fetch_logs_date ON data_fetch_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_fetch_logs_status ON data_fetch_logs(status);
