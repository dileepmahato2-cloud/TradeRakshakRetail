ALTER TABLE dhan_historical_jobs ADD COLUMN IF NOT EXISTS successful_instruments INTEGER DEFAULT 0;
ALTER TABLE dhan_historical_jobs ADD COLUMN IF NOT EXISTS failed_instruments INTEGER DEFAULT 0;
ALTER TABLE dhan_historical_jobs ADD COLUMN IF NOT EXISTS skipped_instruments INTEGER DEFAULT 0;

CREATE TABLE IF NOT EXISTS dhan_historical_errors (
  id BIGSERIAL PRIMARY KEY,
  job_id INTEGER,
  symbol TEXT,
  security_id TEXT,
  exchange_segment TEXT,
  instrument TEXT,
  from_date TEXT,
  to_date TEXT,
  interval_minutes INTEGER,
  api_url TEXT,
  request_body TEXT,
  error_code TEXT,
  error_message TEXT,
  status TEXT,
  created_at TEXT DEFAULT (CURRENT_TIMESTAMP::text)
);

CREATE INDEX IF NOT EXISTS idx_dhan_hist_err_job
  ON dhan_historical_errors(job_id, status, created_at DESC);
