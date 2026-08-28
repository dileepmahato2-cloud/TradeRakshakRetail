-- V30: Stock Master Preview Enhancement
-- Adds tracking columns to stock_master for action_type, timestamps, and remarks
-- Creates upload_logs table for manual upload tracking

ALTER TABLE stock_master ADD COLUMN IF NOT EXISTS action_type TEXT DEFAULT 'fetched';
ALTER TABLE stock_master ADD COLUMN IF NOT EXISTS remarks TEXT;
ALTER TABLE stock_master ADD COLUMN IF NOT EXISTS created_at TEXT;
ALTER TABLE stock_master ADD COLUMN IF NOT EXISTS updated_at TEXT;
ALTER TABLE stock_master ADD COLUMN IF NOT EXISTS uploaded_at TEXT;
ALTER TABLE stock_master ADD COLUMN IF NOT EXISTS fetched_at TEXT;

CREATE INDEX IF NOT EXISTS idx_stock_master_action_type ON stock_master(action_type);
CREATE INDEX IF NOT EXISTS idx_stock_master_source ON stock_master(source);
CREATE INDEX IF NOT EXISTS idx_stock_master_status ON stock_master(status);
CREATE INDEX IF NOT EXISTS idx_stock_master_created_at ON stock_master(created_at);
CREATE INDEX IF NOT EXISTS idx_stock_master_exchange ON stock_master(exchange);

-- Backfill created_at for existing records
UPDATE stock_master SET created_at = COALESCE(as_on_date, '2024-01-01') WHERE created_at IS NULL;
UPDATE stock_master SET action_type = 'fetched' WHERE action_type IS NULL;

-- Upload logs table
CREATE TABLE IF NOT EXISTS upload_logs (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT,
    file_name       TEXT,
    data_type       VARCHAR(100),
    source          VARCHAR(50) DEFAULT 'manual',
    status          VARCHAR(30) DEFAULT 'completed',
    total_records   INTEGER DEFAULT 0,
    imported_records INTEGER DEFAULT 0,
    failed_records  INTEGER DEFAULT 0,
    duplicate_records INTEGER DEFAULT 0,
    skipped_records INTEGER DEFAULT 0,
    error_message   TEXT,
    uploaded_at     TEXT,
    created_at      TEXT
);

CREATE INDEX IF NOT EXISTS idx_upload_logs_user ON upload_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_upload_logs_data_type ON upload_logs(data_type);
CREATE INDEX IF NOT EXISTS idx_upload_logs_created_at ON upload_logs(created_at);
