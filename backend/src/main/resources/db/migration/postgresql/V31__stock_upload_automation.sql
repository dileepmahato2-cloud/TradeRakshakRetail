-- V31: Stock Upload Module — automation (Current/Previous Top 10 daily cycle)
--
-- IMPORTANT: In this app Flyway is DISABLED by default (FLYWAY_ENABLED=false) and
-- the schema is managed at runtime by SymbolSchemaGuard. Everything below is also
-- created by SymbolSchemaGuard so the app works with Flyway off. This migration is
-- written defensively (ALTER TABLE IF EXISTS + existence-guarded seed) so that IF
-- Flyway is ever enabled it cannot fail — even though SymbolSchemaGuard creates the
-- referenced tables after Flyway runs.
--
-- All defaults preserve current (pre-enhancement) behavior:
--   • Current Top 10 auto-refresh ON (matches the existing 5-min scheduled refresh)
--   • Previous Top 10 auto buy/sell cycle OFF (opt-in)
--   • Execution mode PAPER (no real orders unless explicitly switched to LIVE)

-- ── 1. Trade batch cycle columns (guarded) ─────────────────────────────────
ALTER TABLE IF EXISTS top10_trade_batches       ADD COLUMN IF NOT EXISTS auto_generated INTEGER DEFAULT 0;
ALTER TABLE IF EXISTS top10_trade_batch_stocks  ADD COLUMN IF NOT EXISTS buy_date TEXT;
ALTER TABLE IF EXISTS top10_trade_batch_stocks  ADD COLUMN IF NOT EXISTS buy_time TEXT;
ALTER TABLE IF EXISTS top10_trade_batch_stocks  ADD COLUMN IF NOT EXISTS sell_date TEXT;
ALTER TABLE IF EXISTS top10_trade_batch_stocks  ADD COLUMN IF NOT EXISTS sell_time TEXT;
ALTER TABLE IF EXISTS top10_trade_batch_stocks  ADD COLUMN IF NOT EXISTS profit_loss DOUBLE PRECISION DEFAULT 0;
ALTER TABLE IF EXISTS top10_trade_batch_stocks  ADD COLUMN IF NOT EXISTS execution_source TEXT DEFAULT 'SIM';

-- ── 2. Failed-symbol log (exception isolation / observability) ──────────────
CREATE TABLE IF NOT EXISTS top10_failed_symbols (
    id             BIGSERIAL PRIMARY KEY,
    institution_id INTEGER DEFAULT 1,
    symbol         TEXT,
    phase          TEXT,          -- RANK / PRICE / BUY / SELL / REFRESH
    reason         TEXT,
    batch_id       TEXT,
    created_at     TEXT DEFAULT (CURRENT_TIMESTAMP::text)
);
CREATE INDEX IF NOT EXISTS idx_t10fs_inst   ON top10_failed_symbols(institution_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_t10fs_symbol ON top10_failed_symbols(symbol, created_at DESC);

-- ── 3. Seed automation config flags (only if application_settings exists) ────
DO $$
BEGIN
    IF to_regclass('public.application_settings') IS NOT NULL THEN
        INSERT INTO application_settings
            (category, setting_key, setting_name, setting_value, data_type, default_value, description, is_editable, is_active, display_order, config_version, created_at)
        VALUES
            ('STOCK_UPLOAD_AUTOMATION', 'top10.auto_refresh.enabled',      'Current Top 10 Auto Refresh',     'true',  'BOOLEAN', 'true',  'Auto-refresh Current Top 10 every interval', 1, 1, 1, 1, CURRENT_TIMESTAMP::text),
            ('STOCK_UPLOAD_AUTOMATION', 'top10.auto_refresh.interval_sec', 'Auto Refresh Interval (sec)',     '300',   'INTEGER', '300',   'Current Top 10 refresh cadence in seconds',  1, 1, 2, 1, CURRENT_TIMESTAMP::text),
            ('STOCK_UPLOAD_AUTOMATION', 'prevtop10.auto_cycle.enabled',    'Previous Top 10 Auto Cycle',      'false', 'BOOLEAN', 'false', 'Master switch for daily auto buy+sell cycle',1, 1, 3, 1, CURRENT_TIMESTAMP::text),
            ('STOCK_UPLOAD_AUTOMATION', 'prevtop10.auto_buy.enabled',      'Auto Buy Enabled',                'false', 'BOOLEAN', 'false', 'Auto-buy Current Top 10 at buy time',        1, 1, 4, 1, CURRENT_TIMESTAMP::text),
            ('STOCK_UPLOAD_AUTOMATION', 'prevtop10.auto_sell.enabled',     'Auto Sell Enabled',               'false', 'BOOLEAN', 'false', 'Auto-sell next trading day at sell time',    1, 1, 5, 1, CURRENT_TIMESTAMP::text),
            ('STOCK_UPLOAD_AUTOMATION', 'prevtop10.buy_time',              'Auto Buy Time (HH:mm IST)',       '15:20', 'STRING',  '15:20', 'Daily buy time',                             1, 1, 6, 1, CURRENT_TIMESTAMP::text),
            ('STOCK_UPLOAD_AUTOMATION', 'prevtop10.sell_time',             'Auto Sell Time (HH:mm IST)',      '09:30', 'STRING',  '09:30', 'Next trading day sell time',                 1, 1, 7, 1, CURRENT_TIMESTAMP::text),
            ('STOCK_UPLOAD_AUTOMATION', 'prevtop10.execution_mode',        'Execution Mode',                  'PAPER', 'STRING',  'PAPER', 'PAPER (simulated) or LIVE (real orders)',    1, 1, 8, 1, CURRENT_TIMESTAMP::text),
            ('STOCK_UPLOAD_AUTOMATION', 'prevtop10.use_real_price_if_executed', 'Use Real Executed Price',    'true',  'BOOLEAN', 'true',  'Prefer actual executed price when available', 1, 1, 9, 1, CURRENT_TIMESTAMP::text),
            ('STOCK_UPLOAD_AUTOMATION', 'market.holidays',                 'Market Holidays (yyyy-MM-dd list)', '',    'STRING',  '',      'Comma/space separated NSE holiday dates',    1, 1, 10, 1, CURRENT_TIMESTAMP::text)
        ON CONFLICT (setting_key) DO NOTHING;
    END IF;
END $$;
