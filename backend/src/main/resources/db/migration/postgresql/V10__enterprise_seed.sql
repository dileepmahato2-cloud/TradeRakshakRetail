-- =============================================================================
-- V10: Enterprise Seed Data â€” Loyalty tiers, blackout, competition rewards,
--      system settings, default branding
-- =============================================================================

-- â”€â”€ Loyalty Tiers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO loyalty_tiers(tier_name, min_points, max_points, benefits_json, badge_icon) VALUES
('Bronze',   0,      999,  '{"discount":0,"extra_backtests":0,"priority_support":false}', 'ðŸ¥‰'),
('Silver',   1000,   4999, '{"discount":5,"extra_backtests":5,"priority_support":false}', 'ðŸ¥ˆ'),
('Gold',     5000,   14999,'{"discount":10,"extra_backtests":20,"priority_support":true}', 'ðŸ¥‡'),
('Platinum', 15000,  49999,'{"discount":15,"extra_backtests":50,"priority_support":true,"ai_requests_bonus":50}', 'ðŸ’Ž'),
('Diamond',  50000,  99999,'{"discount":20,"extra_backtests":100,"priority_support":true,"ai_requests_bonus":100,"free_plan_upgrade":1}', 'ðŸ’ '),
('Elite',    100000, null, '{"discount":25,"extra_backtests":999,"priority_support":true,"dedicated_manager":true,"free_plan_upgrade":2}', 'â­') ON CONFLICT DO NOTHING;

-- â”€â”€ Backtesting Blackout (09:00â€“16:00 IST Mon-Fri) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO system_blackouts(blackout_type, start_time, end_time, days, timezone, message, is_active, created_at) VALUES
('backtesting', '09:00', '16:00', 'Mon,Tue,Wed,Thu,Fri', 'Asia/Kolkata',
 'Backtesting is unavailable during market hours (09:00â€“16:00 IST). Please run backtests after 4 PM.',
 1, CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Default Tenant Branding (Platform Default) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO tenant_branding(institution_id, primary_color, secondary_color, accent_color,
  font_family, email_from_name, sebi_disclaimer, updated_at)
VALUES(1, '#1B3A5C', '#006B6B', '#C9961A', 'Inter',
  'TradeRakshakâ„¢',
  'TradeRakshakâ„¢ is NOT a SEBI-registered Investment Adviser, Research Analyst, or Investment Manager. All content is for educational and informational purposes only. Past performance is not indicative of future results.',
  CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ System Settings â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
ALTER TABLE system_settings ADD COLUMN created_at TEXT;

INSERT INTO system_settings(key, value, created_at) VALUES
('platform_name',              'TradeRakshakâ„¢',                  CURRENT_TIMESTAMP::text),
('platform_tagline',           'Protecting Capital. Powering Decisions. Creating Wealth.', CURRENT_TIMESTAMP::text),
('platform_version',           '11.4',                            CURRENT_TIMESTAMP::text),
('sebi_disclaimer',            'TradeRakshakâ„¢ is NOT a SEBI-registered Investment Adviser. For educational use only.', CURRENT_TIMESTAMP::text),
('gst_rate',                   '18',                              CURRENT_TIMESTAMP::text),
('gstin',                      '',                                CURRENT_TIMESTAMP::text),
('hsn_code',                   '998314',                          CURRENT_TIMESTAMP::text),
('mfa_enabled',                '0',                               CURRENT_TIMESTAMP::text),
('max_failed_logins',          '3',                               CURRENT_TIMESTAMP::text),
('lockout_minutes',            '30',                              CURRENT_TIMESTAMP::text),
('session_timeout_minutes',    '30',                              CURRENT_TIMESTAMP::text),
('backtest_blackout_enabled',  '1',                               CURRENT_TIMESTAMP::text),
('razorpay_key_id',            '',                                CURRENT_TIMESTAMP::text),
('razorpay_key_secret',        '',                                CURRENT_TIMESTAMP::text),
('razorpay_webhook_secret',    '',                                CURRENT_TIMESTAMP::text),
('msg91_api_key',              '',                                CURRENT_TIMESTAMP::text),
('msg91_sender_id',            'TRDRK',                           CURRENT_TIMESTAMP::text),
('smtp_host',                  'smtp.gmail.com',                  CURRENT_TIMESTAMP::text),
('smtp_port',                  '587',                             CURRENT_TIMESTAMP::text),
('telegram_bot_token',         '',                                CURRENT_TIMESTAMP::text),
('telegram_chat_id',           '',                                CURRENT_TIMESTAMP::text),
('default_broker',             'dhan',                            CURRENT_TIMESTAMP::text),
('free_trial_days',            '7',                               CURRENT_TIMESTAMP::text),
('free_trial_stocks_limit',    '50',                              CURRENT_TIMESTAMP::text),
('free_trial_backtests_day',   '3',                               CURRENT_TIMESTAMP::text),
('free_trial_ai_requests_day', '10',                              CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Default Competition (Demo) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
ALTER TABLE competitions ADD COLUMN competition_type TEXT;
ALTER TABLE competitions ADD COLUMN scoring_engine  TEXT;
ALTER TABLE competitions ADD COLUMN entry_capital   REAL DEFAULT 1000000;
ALTER TABLE competitions ADD COLUMN prize_pool      REAL DEFAULT 0;

INSERT INTO competitions(institution_id, name, description, competition_type, scoring_engine,
  start_date, end_date, entry_capital, max_participants, prize_pool, status, created_by, created_at)
VALUES(1, 'TradeRakshakâ„¢ Paper Trading League â€” June 2026',
  'Monthly paper trading competition. Virtual capital â‚¹10L. Best P&L + Sharpe wins.',
  'paper_trading', 'pnl_sharpe',
  CURRENT_DATE, (CURRENT_DATE + INTERVAL '30 days'),
  1000000, 500, 50000, 'active', 1, CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Competition Rewards â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO competition_rewards(competition_id, rank, reward_type, reward_value)
VALUES
((SELECT id FROM competitions WHERE name LIKE '%Paper Trading League%' LIMIT 1), 1, 'cash', 'â‚¹20,000'),
((SELECT id FROM competitions WHERE name LIKE '%Paper Trading League%' LIMIT 1), 2, 'wallet', 'â‚¹10,000'),
((SELECT id FROM competitions WHERE name LIKE '%Paper Trading League%' LIMIT 1), 3, 'subscription_days', '90 days Pro'),
((SELECT id FROM competitions WHERE name LIKE '%Paper Trading League%' LIMIT 1), 4, 'certificate', 'Certificate of Excellence'),
((SELECT id FROM competitions WHERE name LIKE '%Paper Trading League%' LIMIT 1), 5, 'badge', 'Top Trader Badge') ON CONFLICT DO NOTHING;

-- â”€â”€ Default Portfolios for demo users â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO portfolios(user_id, institution_id, name, description,
  portfolio_type, initial_capital, current_value, cash_balance, is_default, status, created_at)
VALUES
(1, 1, 'Default Portfolio', 'My default paper trading portfolio',
 'paper', 100000, 100000, 100000, 1, 'active', CURRENT_TIMESTAMP::text),
((SELECT id FROM users WHERE email='subscriber@demo.local' LIMIT 1), 1,
 'Subscriber Portfolio', 'Paper trading portfolio',
 'paper', 100000, 100000, 100000, 1, 'active', CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Default Alert Rules â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO alert_rules(user_id, institution_id, alert_name, alert_type,
  priority, channels_json, frequency, status, created_at)
VALUES
(1, 1, 'Volume Spike Alert', 'volume', 'high',
 '["in_app","telegram"]', 'always', 'active', CURRENT_TIMESTAMP::text),
(1, 1, 'RSI Overbought Alert', 'price', 'medium',
 '["in_app"]', 'daily', 'active', CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

-- â”€â”€ Market Breadth seed (today's mock data) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO market_breadth(trade_date, exchange, advance_count, decline_count,
  unchanged_count, total_stocks, advance_decline_ratio, new_52w_high, new_52w_low,
  above_200dma, market_score, market_phase, created_at)
VALUES(CURRENT_DATE, 'NSE', 1145, 672, 83, 1900, 1.70,
  42, 18, 1280, 65, 'UPTREND', CURRENT_TIMESTAMP::text) ON CONFLICT DO NOTHING;

