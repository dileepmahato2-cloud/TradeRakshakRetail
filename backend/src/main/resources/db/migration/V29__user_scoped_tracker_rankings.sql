ALTER TABLE tracker_rankings ADD COLUMN IF NOT EXISTS user_id INTEGER;
ALTER TABLE tracker_ranking_items ADD COLUMN IF NOT EXISTS user_id INTEGER;

CREATE INDEX IF NOT EXISTS idx_trankings_user_date ON tracker_rankings(user_id, run_date DESC);
CREATE INDEX IF NOT EXISTS idx_tri_user_date ON tracker_ranking_items(user_id, run_date DESC);

CREATE INDEX IF NOT EXISTS idx_tracked_stocks_user_symbol ON tracked_stocks(user_id, symbol);
CREATE INDEX IF NOT EXISTS idx_tracked_stocks_user_date ON tracked_stocks(user_id, upload_date DESC);
CREATE INDEX IF NOT EXISTS idx_stock_uploads_user_date ON stock_uploads(user_id, created_at DESC);
