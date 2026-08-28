-- =============================================================================
-- V10: Enterprise Seed Data — Loyalty tiers, blackout, competition rewards,
--      system settings, default branding
-- =============================================================================

-- ── Loyalty Tiers ─────────────────────────────────────────────────────────────
INSERT OR IGNORE INTO loyalty_tiers(tier_name, min_points, max_points, benefits_json, badge_icon) VALUES
('Bronze',   0,      999,  '{"discount":0,"extra_backtests":0,"priority_support":false}', '🥉'),
('Silver',   1000,   4999, '{"discount":5,"extra_backtests":5,"priority_support":false}', '🥈'),
('Gold',     5000,   14999,'{"discount":10,"extra_backtests":20,"priority_support":true}', '🥇'),
('Platinum', 15000,  49999,'{"discount":15,"extra_backtests":50,"priority_support":true,"ai_requests_bonus":50}', '💎'),
('Diamond',  50000,  99999,'{"discount":20,"extra_backtests":100,"priority_support":true,"ai_requests_bonus":100,"free_plan_upgrade":1}', '💠'),
('Elite',    100000, null, '{"discount":25,"extra_backtests":999,"priority_support":true,"dedicated_manager":true,"free_plan_upgrade":2}', '⭐');

-- ── Backtesting Blackout (09:00–16:00 IST Mon-Fri) ───────────────────────────
INSERT OR IGNORE INTO system_blackouts(blackout_type, start_time, end_time, days, timezone, message, is_active, created_at) VALUES
('backtesting', '09:00', '16:00', 'Mon,Tue,Wed,Thu,Fri', 'Asia/Kolkata',
 'Backtesting is unavailable during market hours (09:00–16:00 IST). Please run backtests after 4 PM.',
 1, datetime('now'));

-- ── Default Tenant Branding (Platform Default) ───────────────────────────────
INSERT OR IGNORE INTO tenant_branding(institution_id, primary_color, secondary_color, accent_color,
  font_family, email_from_name, sebi_disclaimer, updated_at)
VALUES(1, '#1B3A5C', '#006B6B', '#C9961A', 'Inter',
  'TradeRakshak™',
  'TradeRakshak™ is NOT a SEBI-registered Investment Adviser, Research Analyst, or Investment Manager. All content is for educational and informational purposes only. Past performance is not indicative of future results.',
  datetime('now'));

-- ── System Settings ───────────────────────────────────────────────────────────
ALTER TABLE system_settings ADD COLUMN created_at TEXT;

INSERT OR IGNORE INTO system_settings(key, value, created_at) VALUES
('platform_name',              'TradeRakshak™',                  datetime('now')),
('platform_tagline',           'Protecting Capital. Powering Decisions. Creating Wealth.', datetime('now')),
('platform_version',           '11.4',                            datetime('now')),
('sebi_disclaimer',            'TradeRakshak™ is NOT a SEBI-registered Investment Adviser. For educational use only.', datetime('now')),
('gst_rate',                   '18',                              datetime('now')),
('gstin',                      '',                                datetime('now')),
('hsn_code',                   '998314',                          datetime('now')),
('mfa_enabled',                '0',                               datetime('now')),
('max_failed_logins',          '3',                               datetime('now')),
('lockout_minutes',            '30',                              datetime('now')),
('session_timeout_minutes',    '30',                              datetime('now')),
('backtest_blackout_enabled',  '1',                               datetime('now')),
('razorpay_key_id',            '',                                datetime('now')),
('razorpay_key_secret',        '',                                datetime('now')),
('razorpay_webhook_secret',    '',                                datetime('now')),
('msg91_api_key',              '',                                datetime('now')),
('msg91_sender_id',            'TRDRK',                           datetime('now')),
('smtp_host',                  'smtp.gmail.com',                  datetime('now')),
('smtp_port',                  '587',                             datetime('now')),
('telegram_bot_token',         '',                                datetime('now')),
('telegram_chat_id',           '',                                datetime('now')),
('default_broker',             'dhan',                            datetime('now')),
('free_trial_days',            '7',                               datetime('now')),
('free_trial_stocks_limit',    '50',                              datetime('now')),
('free_trial_backtests_day',   '3',                               datetime('now')),
('free_trial_ai_requests_day', '10',                              datetime('now'));

-- ── Default Competition (Demo) ────────────────────────────────────────────────
ALTER TABLE competitions ADD COLUMN competition_type TEXT;
ALTER TABLE competitions ADD COLUMN scoring_engine  TEXT;
ALTER TABLE competitions ADD COLUMN entry_capital   REAL DEFAULT 1000000;
ALTER TABLE competitions ADD COLUMN prize_pool      REAL DEFAULT 0;

INSERT OR IGNORE INTO competitions(institution_id, name, description, competition_type, scoring_engine,
  start_date, end_date, entry_capital, max_participants, prize_pool, status, created_by, created_at)
VALUES(1, 'TradeRakshak™ Paper Trading League — June 2026',
  'Monthly paper trading competition. Virtual capital ₹10L. Best P&L + Sharpe wins.',
  'paper_trading', 'pnl_sharpe',
  date('now'), date('now', '+30 days'),
  1000000, 500, 50000, 'active', 1, datetime('now'));

-- ── Competition Rewards ───────────────────────────────────────────────────────
INSERT OR IGNORE INTO competition_rewards(competition_id, rank, reward_type, reward_value)
VALUES
((SELECT id FROM competitions WHERE name LIKE '%Paper Trading League%' LIMIT 1), 1, 'cash', '₹20,000'),
((SELECT id FROM competitions WHERE name LIKE '%Paper Trading League%' LIMIT 1), 2, 'wallet', '₹10,000'),
((SELECT id FROM competitions WHERE name LIKE '%Paper Trading League%' LIMIT 1), 3, 'subscription_days', '90 days Pro'),
((SELECT id FROM competitions WHERE name LIKE '%Paper Trading League%' LIMIT 1), 4, 'certificate', 'Certificate of Excellence'),
((SELECT id FROM competitions WHERE name LIKE '%Paper Trading League%' LIMIT 1), 5, 'badge', 'Top Trader Badge');

-- ── Default Portfolios for demo users ────────────────────────────────────────
INSERT OR IGNORE INTO portfolios(user_id, institution_id, name, description,
  portfolio_type, initial_capital, current_value, cash_balance, is_default, status, created_at)
VALUES
(1, 1, 'Default Portfolio', 'My default paper trading portfolio',
 'paper', 100000, 100000, 100000, 1, 'active', datetime('now')),
((SELECT id FROM users WHERE email='subscriber@demo.local' LIMIT 1), 1,
 'Subscriber Portfolio', 'Paper trading portfolio',
 'paper', 100000, 100000, 100000, 1, 'active', datetime('now'));

-- ── Default Alert Rules ───────────────────────────────────────────────────────
INSERT OR IGNORE INTO alert_rules(user_id, institution_id, alert_name, alert_type,
  priority, channels_json, frequency, status, created_at)
VALUES
(1, 1, 'Volume Spike Alert', 'volume', 'high',
 '["in_app","telegram"]', 'always', 'active', datetime('now')),
(1, 1, 'RSI Overbought Alert', 'price', 'medium',
 '["in_app"]', 'daily', 'active', datetime('now'));

-- ── Market Breadth seed (today's mock data) ───────────────────────────────────
INSERT OR IGNORE INTO market_breadth(trade_date, exchange, advance_count, decline_count,
  unchanged_count, total_stocks, advance_decline_ratio, new_52w_high, new_52w_low,
  above_200dma, market_score, market_phase, created_at)
VALUES(date('now'), 'NSE', 1145, 672, 83, 1900, 1.70,
  42, 18, 1280, 65, 'UPTREND', datetime('now'));
