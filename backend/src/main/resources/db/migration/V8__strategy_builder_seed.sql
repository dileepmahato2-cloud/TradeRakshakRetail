-- =============================================================================
-- V8: Strategy Builder Seed Data
-- Indicator catalog, sample strategies at all 3 levels, marketplace samples
-- =============================================================================

-- ── Sample Simple Strategy (Level 1) ─────────────────────────────────────────
INSERT OR IGNORE INTO strategies(
  user_id, institution_id, name, description, builder_level,
  segment, strategy_category, timeframe,
  conditions_json, entry_conditions_json, exit_conditions_json,
  risk_management_json, stop_loss_pct, target_pct, rr_min,
  max_positions, risk_per_trade_pct, universe,
  workflow_status, is_public, tags, status, created_at, updated_at)
VALUES(
  1, 1, 'Simple RSI Momentum — EQ Swing', 
  'Beginner-friendly swing strategy: RSI > 60 + price above EMA20 + volume breakout.',
  1, 'EQ-SWG', 'swing', 'Daily',
  '[{"group":"entry","operator":"AND","indicatorLeft":"RSI","paramsLeft":{"period":14},"comparator":">","valueRight":60},
    {"group":"entry","operator":"AND","indicatorLeft":"CLOSE","comparator":">","indicatorRight":"EMA","paramsRight":{"period":20}},
    {"group":"entry","operator":"AND","indicatorLeft":"VOLUME","comparator":">","indicatorRight":"VOLUME_SMA","paramsRight":{"period":20},"multiplier":1.5}]',
  'RSI(14) > 60 AND Close > EMA(20) AND Volume > 1.5 × SMA(Volume,20)',
  'RSI(14) < 50 OR Close < EMA(20)',
  '{"capital":100000,"riskPerTrade":2,"maxPositions":5,"dailyLossLimit":3,"monthlyDrawdown":10}',
  5, 10, 2.0, 5, 2.0, 'NSE_500',
  'draft', 1, 'rsi,ema,volume,swing,beginner', 'active', datetime('now'), datetime('now'));

-- ── Sample Advanced Strategy (Level 2) ───────────────────────────────────────
INSERT OR IGNORE INTO strategies(
  user_id, institution_id, name, description, builder_level,
  segment, strategy_category, timeframe,
  conditions_json, entry_conditions_json, exit_conditions_json,
  risk_management_json, stop_loss_pct, target_pct, rr_min,
  max_positions, risk_per_trade_pct, universe,
  workflow_status, is_public, tags, status, created_at, updated_at)
VALUES(
  1, 1, 'Advanced Trend + Momentum Combo',
  'Advanced multi-indicator strategy: RSI + EMA alignment + MACD + Delivery. OR breakout pattern.',
  2, 'EQ-SWG', 'positional', 'Weekly',
  '[{"group":"G1","operator":"AND","indicatorLeft":"RSI","paramsLeft":{"period":14,"timeframe":"Weekly"},"comparator":">","valueRight":55},
    {"group":"G1","operator":"AND","indicatorLeft":"EMA","paramsLeft":{"period":20},"comparator":">","indicatorRight":"EMA","paramsRight":{"period":50}},
    {"group":"G1","operator":"AND","indicatorLeft":"MACD_HIST","comparator":">","valueRight":0},
    {"group":"G1","operator":"AND","indicatorLeft":"DELIVERY_PCT","comparator":">","valueRight":60},
    {"group":"G2","groupOperator":"OR","indicatorLeft":"CLOSE","comparator":"CROSSABOVE","indicatorRight":"PREV_HIGH"},
    {"group":"G2","operator":"AND","indicatorLeft":"VOLUME","comparator":">","indicatorRight":"VOLUME_SMA","paramsRight":{"period":20},"multiplier":2.0}]',
  '(Weekly RSI(14) > 55 AND EMA(20) > EMA(50) AND MACD Hist > 0 AND Delivery% > 60) OR (Close CROSSABOVE Prev High AND Volume > 2× SMA20)',
  'EMA(20) < EMA(50) OR RSI(14) < 45',
  '{"capital":200000,"riskPerTrade":2,"maxPositions":8,"trailingSL":"atr","atrMultiplier":2,"dailyLossLimit":3}',
  4, 12, 2.5, 8, 2.0, 'NIFTY_500',
  'draft', 1, 'rsi,ema,macd,delivery,weekly,advanced', 'active', datetime('now'), datetime('now'));

-- ── Sample AI-Generated Strategy (Level 3) ───────────────────────────────────
INSERT OR IGNORE INTO strategies(
  user_id, institution_id, name, description, builder_level,
  segment, strategy_category, timeframe, ai_generated, ai_prompt,
  conditions_json, entry_conditions_json, exit_conditions_json,
  risk_management_json, stop_loss_pct, target_pct, rr_min,
  max_positions, risk_per_trade_pct, universe,
  workflow_status, is_public, tags, status, created_at, updated_at)
VALUES(
  1, 1, 'AI: Sector Rotation Momentum',
  'AI-generated strategy: targets stocks in top-rotating sectors with RSI pullback entries.',
  3, 'EQ-SWG', 'swing', 'Daily', 1,
  'I want a swing trading strategy with 60%+ win rate for Nifty 500 stocks focusing on sector rotation',
  '[{"group":"entry","operator":"AND","indicatorLeft":"SECTOR_SCORE","comparator":">=","valueRight":65},
    {"group":"entry","operator":"AND","indicatorLeft":"RSI","paramsLeft":{"period":14},"comparator":">=","valueRight":45},
    {"group":"entry","operator":"AND","indicatorLeft":"RSI","paramsLeft":{"period":14},"comparator":"<=","valueRight":60},
    {"group":"entry","operator":"AND","indicatorLeft":"CLOSE","comparator":">","indicatorRight":"EMA","paramsRight":{"period":50}},
    {"group":"entry","operator":"AND","indicatorLeft":"ATR","paramsLeft":{"period":14},"comparator":">","valueRight":0},
    {"group":"entry","operator":"AND","indicatorLeft":"VOLUME","comparator":">","indicatorRight":"VOLUME_SMA","paramsRight":{"period":20}}]',
  'Sector Score >= 65 AND RSI(14) between 45-60 (pullback) AND Close > EMA(50) AND Volume breakout',
  'RSI(14) > 70 OR Close < EMA(20) OR Sector Score < 45',
  '{"capital":500000,"riskPerTrade":1.5,"maxPositions":10,"sectorLimit":2,"trailingSL":"atr","atrMultiplier":2.5}',
  3, 9, 3.0, 10, 1.5, 'NIFTY_500',
  'backtesting', 1, 'ai,sector,rotation,momentum,pullback', 'active', datetime('now'), datetime('now'));

-- ── Marketplace listings for demo ────────────────────────────────────────────
INSERT OR IGNORE INTO marketplace_listings(
  strategy_id, seller_user_id, institution_id,
  listing_title, listing_desc, price, is_free,
  win_rate_claimed, category, tags,
  approved, status, created_at)
VALUES
((SELECT id FROM strategies WHERE name='Simple RSI Momentum — EQ Swing' LIMIT 1),
 1, 1,
 'RSI Momentum Swing (Free Starter Strategy)',
 'Perfect for beginners! 3 simple conditions, easy to understand. Backtested on 2 years of NSE 500 data.',
 0, 1, 62.5, 'swing', 'rsi,beginners,free', 1, 'approved', datetime('now')),

((SELECT id FROM strategies WHERE name='Advanced Trend + Momentum Combo' LIMIT 1),
 1, 1,
 'Trend + Momentum Combo (Premium)',
 'Advanced multi-indicator strategy combining weekly RSI, MACD, EMA alignment, and delivery volume. Backtested with 68% win rate.',
 499, 0, 68.2, 'positional', 'advanced,macd,weekly', 1, 'approved', datetime('now')),

((SELECT id FROM strategies WHERE name='AI: Sector Rotation Momentum' LIMIT 1),
 1, 1,
 'AI Sector Rotation Strategy (Pro)',
 'AI-generated strategy targeting sector rotation. Includes full backtesting report and risk management rules.',
 999, 0, 71.4, 'swing', 'ai,sector,rotation', 0, 'pending', datetime('now'));

-- ── Alert channel defaults for super admin ───────────────────────────────────
INSERT OR IGNORE INTO alert_channels(user_id, channel_type, config_json, verified, status, created_at)
VALUES
(1, 'telegram', '{"chat_id":"","bot_token":""}', 0, 'active', datetime('now')),
(1, 'email',    '{"email":"superadmin@traderakshak.local"}', 1, 'active', datetime('now'));
