CREATE TABLE IF NOT EXISTS broker_data_snapshots (
    id              BIGSERIAL PRIMARY KEY,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    broker_name     TEXT NOT NULL,
    data_type       TEXT,
    source          TEXT DEFAULT 'BROKER',
    from_date       TEXT,
    to_date         TEXT,
    payload_json    TEXT,
    status          TEXT DEFAULT 'completed',
    message         TEXT,
    created_at      TEXT DEFAULT (CURRENT_TIMESTAMP::text)
);

CREATE INDEX IF NOT EXISTS idx_bds_user_date ON broker_data_snapshots(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_bds_broker_type ON broker_data_snapshots(broker_name, data_type);

