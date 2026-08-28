-- =============================================================================
-- V11: Admin Feature Configuration + User Notification Channels
-- 1. feature_configs â€” admin can enable/disable any feature with schedule
-- 2. user_notification_channels â€” users configure their own delivery channels
-- =============================================================================

-- â”€â”€ 1. Feature Configuration (Admin-managed) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
-- Admin can toggle features ON/OFF, set allowed times, allowed roles, message
CREATE TABLE IF NOT EXISTS feature_configs (
    id              BIGSERIAL PRIMARY KEY,
    feature_key     TEXT UNIQUE NOT NULL,   -- e.g. backtesting, live_trading, ai_requests
    feature_name    TEXT NOT NULL,
    feature_group   TEXT DEFAULT 'trading', -- trading / lms / subscription / admin / ai
    is_enabled      INTEGER DEFAULT 1,       -- master ON/OFF switch
    -- Schedule-based restriction (e.g. block backtesting 09:00-16:00)
    schedule_restricted INTEGER DEFAULT 0,
    allowed_start_time  TEXT,               -- HH:MM 24h IST
    allowed_end_time    TEXT,               -- HH:MM 24h IST
    allowed_days        TEXT DEFAULT 'Mon,Tue,Wed,Thu,Fri,Sat,Sun',
    timezone            TEXT DEFAULT 'Asia/Kolkata',
    blocked_message     TEXT,               -- shown when feature is disabled
    -- Per-plan restriction
    min_plan        TEXT DEFAULT 'Free Trial', -- minimum plan required
    -- Per-role restriction
    allowed_roles   TEXT DEFAULT 'ALL',     -- comma-separated roles, or ALL
    -- HTTP response when blocked
    http_status     INTEGER DEFAULT 423,    -- 423=blocked by schedule, 403=forbidden
    -- Metadata
    description     TEXT,
    updated_by      INTEGER,
    updated_at      TEXT,
    created_at      TEXT
);

-- â”€â”€ 2. User Notification Channel Preferences â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
-- Each user configures their own channels; each channel has config + test status
CREATE TABLE IF NOT EXISTS user_notification_channels (
    id              BIGSERIAL PRIMARY KEY,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    -- Email channel
    email_enabled   INTEGER DEFAULT 1,
    email_address   TEXT,                    -- defaults to account email
    email_verified  INTEGER DEFAULT 0,
    -- SMS/Mobile channel
    sms_enabled     INTEGER DEFAULT 0,
    sms_mobile      TEXT,                    -- 10-digit mobile for SMS via MSG91
    sms_verified    INTEGER DEFAULT 0,
    -- Telegram channel
    telegram_enabled    INTEGER DEFAULT 0,
    telegram_chat_id    TEXT,                -- user's Telegram chat ID
    telegram_bot_name   TEXT DEFAULT '@TradeRakshakBot',
    telegram_verified   INTEGER DEFAULT 0,
    -- WhatsApp channel
    whatsapp_enabled    INTEGER DEFAULT 0,
    whatsapp_number     TEXT,                -- with country code e.g. +919876543210
    whatsapp_verified   INTEGER DEFAULT 0,
    whatsapp_opt_in     INTEGER DEFAULT 0,   -- explicit WhatsApp opt-in required
    -- In-App (always on)
    in_app_enabled  INTEGER DEFAULT 1,
    -- Notification type preferences (what to notify per channel)
    notify_alerts_email     INTEGER DEFAULT 1,
    notify_alerts_sms       INTEGER DEFAULT 0,
    notify_alerts_telegram  INTEGER DEFAULT 1,
    notify_alerts_whatsapp  INTEGER DEFAULT 0,
    notify_trades_email     INTEGER DEFAULT 1,
    notify_trades_sms       INTEGER DEFAULT 0,
    notify_trades_telegram  INTEGER DEFAULT 0,
    notify_trades_whatsapp  INTEGER DEFAULT 0,
    notify_account_email    INTEGER DEFAULT 1,
    notify_account_sms      INTEGER DEFAULT 1,
    notify_account_telegram INTEGER DEFAULT 0,
    notify_account_whatsapp INTEGER DEFAULT 0,
    notify_competition_email  INTEGER DEFAULT 1,
    notify_competition_sms    INTEGER DEFAULT 0,
    notify_lms_email          INTEGER DEFAULT 1,
    notify_lms_sms            INTEGER DEFAULT 0,
    -- Quiet hours (no notifications during these hours)
    quiet_hours_enabled INTEGER DEFAULT 0,
    quiet_start_time    TEXT DEFAULT '22:00',
    quiet_end_time      TEXT DEFAULT '07:00',
    -- Last test timestamp per channel
    email_last_test     TEXT,
    sms_last_test       TEXT,
    telegram_last_test  TEXT,
    whatsapp_last_test  TEXT,
    updated_at          TEXT,
    UNIQUE(user_id)
);

-- â”€â”€ 3. Notification delivery log (per-channel) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS notification_delivery_log (
    id              BIGSERIAL PRIMARY KEY,
    user_id         INTEGER NOT NULL,
    channel         TEXT NOT NULL,          -- email/sms/telegram/whatsapp/in_app
    notification_type TEXT NOT NULL,        -- alert/trade/account/competition/lms/test
    subject         TEXT,
    message         TEXT,
    recipient       TEXT,                   -- email/mobile/chat_id
    status          TEXT DEFAULT 'sent',    -- sent/failed/bounced/queued
    error_message   TEXT,
    sent_at         TEXT,
    read_at         TEXT,
    created_at      TEXT
);

-- â”€â”€ Seed: Default feature configs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO feature_configs(
    feature_key, feature_name, feature_group,
    is_enabled, schedule_restricted,
    allowed_start_time, allowed_end_time, allowed_days,
    timezone, blocked_message, min_plan, allowed_roles,
    http_status, description, created_at) VALUES

-- Backtesting blocked during market hours (the main ask)
('backtesting', 'Backtesting Engine', 'trading',
 1, 1,
 '16:01', '08:59', 'Mon,Tue,Wed,Thu,Fri',
 'Asia/Kolkata',
 'Backtesting is not available during market hours (09:00â€“16:00 IST Monâ€“Fri). Please run backtests after 4:00 PM.',
 'Basic', 'ALL', 423,
 'Runs historical strategy simulation. Blocked during market hours to prevent data inconsistency.',
 CURRENT_TIMESTAMP::text),

-- Live Trading (Phase 2 â€” disabled by default)
('live_trading', 'Live Trading', 'trading',
 0, 0, NULL, NULL, 'Mon,Tue,Wed,Thu,Fri',
 'Asia/Kolkata',
 'Live trading is not enabled on this account. Please contact your administrator.',
 'Pro', 'paid_subscriber,level_1,level_2,level_3', 403,
 'Places real orders via broker API. Requires Pro plan and admin approval.',
 CURRENT_TIMESTAMP::text),

-- Paper Trading (always allowed)
('paper_trading', 'Paper Trading', 'trading',
 1, 0, NULL, NULL, 'All',
 'Asia/Kolkata', NULL,
 'Free Trial', 'ALL', 200,
 'Virtual trading with no real money. Always available.',
 CURRENT_TIMESTAMP::text),

-- AI Strategy Builder
('ai_strategy', 'AI Strategy Generator', 'ai',
 1, 0, NULL, NULL, 'All',
 'Asia/Kolkata',
 'AI Strategy Builder is not available on your current plan.',
 'Premium', 'ALL', 403,
 'AI-powered strategy generation from natural language prompts.',
 CURRENT_TIMESTAMP::text),

-- AI Sector Scoring
('sector_ai', 'Sector Intelligence AI', 'ai',
 1, 0, NULL, NULL, 'All',
 'Asia/Kolkata', NULL,
 'Basic', 'ALL', 200,
 'AI-based sector trend scoring using RandomForest.',
 CURRENT_TIMESTAMP::text),

-- Strategy Marketplace
('marketplace', 'Strategy Marketplace', 'trading',
 1, 0, NULL, NULL, 'All',
 'Asia/Kolkata', NULL,
 'Premium', 'ALL', 200,
 'Buy, sell, and copy trading strategies from the community.',
 CURRENT_TIMESTAMP::text),

-- F&O Scanner
('fo_scanner', 'F&O Scanner', 'trading',
 1, 0, NULL, NULL, 'Mon,Tue,Wed,Thu,Fri',
 'Asia/Kolkata',
 'F&O Scanner requires Premium plan or higher.',
 'Premium', 'ALL', 403,
 'Futures and Options scanner with OI, PCR, and Greeks.',
 CURRENT_TIMESTAMP::text),

-- Competition Module
('competitions', 'Trading Competitions', 'trading',
 1, 0, NULL, NULL, 'All',
 'Asia/Kolkata',
 'Active subscription required to enter competitions.',
 'Basic', 'ALL', 403,
 'Paper and strategy competitions with prizes.',
 CURRENT_TIMESTAMP::text),

-- PPT Reports
('ppt_reports', 'PPT Report Generator', 'trading',
 1, 0, NULL, NULL, 'All',
 'Asia/Kolkata',
 'PPT Reports require Pro plan or higher.',
 'Pro', 'ALL', 403,
 'Generate PowerPoint presentation reports.',
 CURRENT_TIMESTAMP::text),

-- API Access
('api_access', 'API Access', 'admin',
 1, 0, NULL, NULL, 'All',
 'Asia/Kolkata',
 'API Access requires Pro plan. Contact admin to enable.',
 'Pro', 'ALL', 403,
 'External REST API access for integrations.',
 CURRENT_TIMESTAMP::text),

-- Bulk User Operations
('bulk_user_ops', 'Bulk User Operations', 'admin',
 1, 0, NULL, NULL, 'All',
 'Asia/Kolkata', NULL,
 'Institutional', 'super_admin,platform_admin,admin,institution_admin', 403,
 'Upload CSV to create/modify multiple users at once.',
 CURRENT_TIMESTAMP::text),

-- White-label branding
('white_label', 'White-Label Branding', 'admin',
 1, 0, NULL, NULL, 'All',
 'Asia/Kolkata',
 'White-label requires Institutional or Enterprise plan.',
 'Institutional', 'super_admin,platform_admin,institution_admin', 403,
 'Custom branding, logo, colors, and domain for institution.',
 CURRENT_TIMESTAMP::text),

-- LMS / Teaching
('lms_teaching', 'LMS Teaching Platform', 'lms',
 1, 0, NULL, NULL, 'All',
 'Asia/Kolkata', NULL,
 'Starter', 'ALL', 200,
 'Learning Management System â€” courses, quizzes, certificates.',
 CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Seed: Default notification channels for existing users â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO user_notification_channels(
    user_id, institution_id,
    email_enabled, sms_enabled, telegram_enabled, whatsapp_enabled,
    in_app_enabled,
    notify_alerts_email, notify_alerts_telegram,
    notify_account_email, notify_account_sms,
    updated_at)
SELECT id, institution_id,
    1, 0, 0, 0, 1,
    1, 0,
    1, 1,
    CURRENT_TIMESTAMP::text
FROM users
WHERE NOT EXISTS (
    SELECT 1 FROM user_notification_channels unc WHERE unc.user_id=users.id
) ON CONFLICT DO NOTHING;

-- â”€â”€ Indexes â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE INDEX IF NOT EXISTS idx_feature_configs_key ON feature_configs(feature_key);
CREATE INDEX IF NOT EXISTS idx_notif_channels_user ON user_notification_channels(user_id);
CREATE INDEX IF NOT EXISTS idx_notif_delivery_user ON notification_delivery_log(user_id, channel, created_at);

