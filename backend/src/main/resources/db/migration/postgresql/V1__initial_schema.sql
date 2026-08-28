-- =============================================================================
-- TradeRakshakâ„¢ v11.4 â€” Flyway V1 Migration
-- Full schema from BRD v9.1 + v11.4 patch
-- Replaces Python SCHEMA list + SCHEMA_V114 list in app.py
-- Rules: R2-NoDelete (soft-delete only), R6-Security (append-only triggers)
-- =============================================================================

-- â”€â”€ Institutions (multi-tenant white-label) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS institutions (
    id                  BIGSERIAL PRIMARY KEY,
    name                TEXT, short_code TEXT UNIQUE, logo_url TEXT, tagline TEXT,
    primary_color       TEXT DEFAULT '#0F172A',
    accent_color        TEXT DEFAULT '#FBBF24',
    theme               TEXT DEFAULT 'dark_pro', domain TEXT,
    support_email TEXT, support_mobile TEXT,
    tw_url TEXT, li_url TEXT, yt_url TEXT, tg_url TEXT,
    wa_url TEXT, ig_url TEXT, fb_url TEXT, website TEXT,
    platform_tw_url TEXT, platform_li_url TEXT, platform_yt_url TEXT, platform_tg_url TEXT,
    status              TEXT DEFAULT 'active',
    storage_type        TEXT DEFAULT 'server', storage_path TEXT,
    live_trading_enabled INTEGER DEFAULT 0,
    max_users           INTEGER DEFAULT 100,
    created_at TEXT, updated_at TEXT
);

-- â”€â”€ Users â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS users (
    id                   BIGSERIAL PRIMARY KEY,
    tr_user_id           TEXT UNIQUE,
    institution_id       INTEGER DEFAULT 1,
    name TEXT, email TEXT UNIQUE, password TEXT,
    role TEXT, level TEXT, plan TEXT,
    segments             TEXT DEFAULT 'EQ-INT,EQ-SWG',
    status               TEXT DEFAULT 'active',
    mobile TEXT, otp_enabled INTEGER DEFAULT 0,
    pan TEXT, kyc_status TEXT DEFAULT 'pending',
    consent_accepted_at  TEXT, last_login TEXT,
    login_count          INTEGER DEFAULT 0,
    failed_login_count   INTEGER DEFAULT 0,
    lockout_until        TEXT,
    plan_expiry TEXT, plan_status TEXT DEFAULT 'free',
    subscription_id TEXT,
    theme_preference     TEXT DEFAULT 'dark_pro',
    suspended_reason     TEXT,
    created_at TEXT, updated_at TEXT
);

-- â”€â”€ Subscription plans â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS plans (
    id BIGSERIAL PRIMARY KEY,
    institution_id INTEGER DEFAULT 1,
    name TEXT, price REAL, gst REAL DEFAULT 18,
    validity_days INTEGER, description TEXT, features TEXT,
    allowed_segments TEXT DEFAULT 'EQ-INT,EQ-SWG',
    max_backtests_day INTEGER DEFAULT 50,
    max_paper_portfolios INTEGER DEFAULT 1,
    max_strategies INTEGER DEFAULT 10,
    live_trading_allowed INTEGER DEFAULT 0,
    ai_access INTEGER DEFAULT 0,
    fo_access INTEGER DEFAULT 0,
    fundamental_access INTEGER DEFAULT 0,
    competition_access INTEGER DEFAULT 0,
    ppt_access INTEGER DEFAULT 0,
    status TEXT DEFAULT 'active'
);

-- â”€â”€ Payments â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS payment_history (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, plan_name TEXT, billing_cycle TEXT DEFAULT 'monthly',
    base_amount_inr REAL, gst_amount_inr REAL, total_amount_inr REAL,
    offer_code TEXT, discount_amount_inr REAL DEFAULT 0,
    payment_status TEXT DEFAULT 'pending',
    payment_method TEXT, gateway TEXT DEFAULT 'razorpay',
    gateway_payment_id TEXT, gateway_order_id TEXT,
    plan_start_date TEXT, plan_end_date TEXT,
    invoice_number TEXT UNIQUE, notes TEXT, created_at TEXT
);

-- â”€â”€ Offers / Promotions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS offers (
    id BIGSERIAL PRIMARY KEY,
    institution_id INTEGER DEFAULT 1,
    offer_name TEXT, display_title TEXT,
    offer_type TEXT, applicable_plans TEXT,
    discount_type TEXT DEFAULT 'percentage',
    discount_value REAL DEFAULT 0,
    applies_to TEXT DEFAULT 'first_month',
    valid_from TEXT, valid_to TEXT,
    max_uses INTEGER DEFAULT 0, current_uses INTEGER DEFAULT 0,
    coupon_code TEXT UNIQUE, min_amount REAL DEFAULT 0,
    stackable INTEGER DEFAULT 0, show_on_pricing INTEGER DEFAULT 1,
    status TEXT DEFAULT 'active', created_by INTEGER, created_at TEXT
);

-- â”€â”€ Competitions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS competitions (
    id BIGSERIAL PRIMARY KEY, institution_id INTEGER DEFAULT 1,
    name TEXT, comp_type TEXT, description TEXT, rules_json TEXT,
    start_date TEXT, end_date TEXT,
    registration_open TEXT, registration_close TEXT,
    max_participants INTEGER DEFAULT 100,
    starting_capital REAL DEFAULT 1000000,
    allowed_segments TEXT DEFAULT 'EQ-INT,EQ-SWG',
    max_positions INTEGER DEFAULT 5, daily_trade_limit INTEGER DEFAULT 10,
    sl_mandatory INTEGER DEFAULT 1, shorting_allowed INTEGER DEFAULT 0,
    options_allowed INTEGER DEFAULT 0,
    result_basis TEXT DEFAULT 'net_pnl_pct',
    status TEXT DEFAULT 'draft', results_announced INTEGER DEFAULT 0,
    prize_json TEXT, created_by INTEGER, announced_by INTEGER,
    announced_at TEXT, created_at TEXT
);

CREATE TABLE IF NOT EXISTS competition_participants (
    id BIGSERIAL PRIMARY KEY,
    competition_id INTEGER, user_id INTEGER,
    portfolio_json TEXT, final_pnl REAL DEFAULT 0,
    final_pnl_pct REAL DEFAULT 0, win_rate REAL DEFAULT 0,
    total_trades INTEGER DEFAULT 0, rank INTEGER DEFAULT 0,
    prize_amount REAL DEFAULT 0, prize_details TEXT,
    certificate_issued INTEGER DEFAULT 0, registered_at TEXT,
    UNIQUE(competition_id, user_id)
);

-- â”€â”€ SOPs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS sops (
    id BIGSERIAL PRIMARY KEY, institution_id INTEGER DEFAULT 1,
    title TEXT, category TEXT, content TEXT,
    is_mandatory INTEGER DEFAULT 0, checklist_mode INTEGER DEFAULT 0,
    version TEXT DEFAULT '1.0', status TEXT DEFAULT 'active',
    applicable_roles TEXT DEFAULT 'all', version_number TEXT DEFAULT '1.0',
    created_by INTEGER, created_at TEXT, updated_at TEXT
);

CREATE TABLE IF NOT EXISTS sop_versions (
    id BIGSERIAL PRIMARY KEY,
    sop_id INTEGER, version TEXT, content TEXT, change_summary TEXT,
    edited_by INTEGER, created_at TEXT
);

CREATE TABLE IF NOT EXISTS sop_acknowledgements (
    id BIGSERIAL PRIMARY KEY,
    sop_id INTEGER NOT NULL, user_id INTEGER NOT NULL,
    version_acknowledged TEXT, acknowledged_at TEXT,
    UNIQUE(sop_id, user_id)
);

-- â”€â”€ Agents â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS agents (
    id BIGSERIAL PRIMARY KEY, institution_id INTEGER DEFAULT 1,
    user_id INTEGER, agent_type TEXT DEFAULT 'sub',
    master_agent_id INTEGER, agent_code TEXT UNIQUE,
    referral_link TEXT, kyc_status TEXT DEFAULT 'pending',
    commission_rate REAL DEFAULT 10,
    sub_commission_rate REAL DEFAULT 7,
    referral_count INTEGER DEFAULT 0, active_subscribers INTEGER DEFAULT 0,
    status TEXT DEFAULT 'active', created_at TEXT
);

CREATE TABLE IF NOT EXISTS agent_commissions (
    id BIGSERIAL PRIMARY KEY,
    agent_id INTEGER, user_id INTEGER,
    plan_name TEXT, plan_amount REAL,
    commission_pct REAL, commission_amount REAL,
    period TEXT, status TEXT DEFAULT 'pending',
    paid_at TEXT, created_at TEXT
);

CREATE TABLE IF NOT EXISTS agent_kyc (
    id BIGSERIAL PRIMARY KEY,
    agent_id INTEGER NOT NULL,
    pan TEXT, bank_account TEXT, bank_ifsc TEXT, bank_name TEXT,
    kyc_status TEXT DEFAULT 'pending',
    verified_by INTEGER, verified_at TEXT,
    rejection_reason TEXT, submitted_at TEXT
);

-- â”€â”€ Stock master â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS stock_master (
    id BIGSERIAL PRIMARY KEY,
    exchange TEXT, symbol TEXT, company_name TEXT, short_name TEXT,
    nse_code TEXT, bse_code TEXT, series TEXT, isin TEXT,
    sector TEXT, industry TEXT, sub_industry TEXT,
    market_cap REAL, market_cap_category TEXT,
    face_value REAL DEFAULT 10, lot_size INTEGER DEFAULT 1,
    fno_flag INTEGER DEFAULT 0, nifty50_flag INTEGER DEFAULT 0,
    nifty500_flag INTEGER DEFAULT 0,
    segment TEXT DEFAULT 'Equity',
    status TEXT DEFAULT 'active', source TEXT, as_on_date TEXT,
    UNIQUE(exchange, symbol)
);

-- â”€â”€ Market data (OHLCV + delivery) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS market_data (
    id BIGSERIAL PRIMARY KEY,
    exchange TEXT, symbol TEXT, trade_date TEXT,
    open REAL, high REAL, low REAL, close REAL,
    prev_close REAL, vwap REAL,
    volume REAL, total_volume REAL, turnover REAL,
    delivery_volume REAL, delivery_pct REAL,
    num_trades INTEGER DEFAULT 0,
    sector TEXT, segment TEXT DEFAULT 'Equity', source TEXT, series TEXT,
    UNIQUE(exchange, symbol, trade_date)
);

-- â”€â”€ Technical indicators â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS tech_indicators (
    id BIGSERIAL PRIMARY KEY,
    symbol TEXT, exchange TEXT DEFAULT 'NSE', as_of_date TEXT,
    rsi_daily REAL, rsi_weekly REAL, rsi_slope_2w REAL,
    ema9 REAL, ema20 REAL, ema50 REAL, ema200 REAL,
    macd_line REAL, macd_signal REAL, macd_hist REAL,
    bb_upper REAL, bb_lower REAL, bb_mid REAL,
    atr14 REAL, adx14 REAL,
    volume_avg20 REAL, volume_ratio REAL,
    delivery_avg20 REAL, delivery_ratio REAL,
    above_ema20 INTEGER DEFAULT 0, above_ema50 INTEGER DEFAULT 0,
    above_ema200 INTEGER DEFAULT 0,
    trend_phase TEXT DEFAULT 'NEUTRAL', updated_at TEXT,
    UNIQUE(symbol, exchange, as_of_date)
);

-- â”€â”€ Fundamentals â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS fundamentals (
    id BIGSERIAL PRIMARY KEY,
    symbol TEXT, exchange TEXT DEFAULT 'NSE',
    period_type TEXT DEFAULT 'Quarterly', period TEXT,
    revenue REAL, ebitda REAL, net_profit REAL, eps REAL,
    book_value REAL, roe REAL, roce REAL,
    debt_equity REAL, current_ratio REAL, interest_coverage REAL,
    pe REAL, pb REAL, ev_ebitda REAL, dividend_yield REAL,
    promoter_holding REAL, fii_holding REAL,
    dii_holding REAL, public_holding REAL,
    revenue_growth_yoy REAL, profit_growth_yoy REAL, eps_growth_yoy REAL,
    source TEXT, created_at TEXT,
    UNIQUE(symbol, exchange, period_type, period)
);

-- â”€â”€ Stock snapshot â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS stock_snapshot (
    id BIGSERIAL PRIMARY KEY,
    symbol TEXT UNIQUE, exchange TEXT DEFAULT 'NSE',
    pe REAL, pb REAL, roe REAL,
    week52_high REAL, week52_high_date TEXT,
    week52_low REAL, week52_low_date TEXT,
    ath REAL, ath_date TEXT, atl REAL, atl_date TEXT,
    market_cap REAL, last_updated TEXT
);

-- â”€â”€ Options chain â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS option_chain (
    id BIGSERIAL PRIMARY KEY,
    underlying TEXT, exchange TEXT DEFAULT 'NSE',
    trade_date TEXT, expiry_date TEXT,
    strike REAL, option_type TEXT,
    ltp REAL, bid REAL, ask REAL,
    open REAL, high REAL, low REAL, close REAL, prev_close REAL,
    volume INTEGER DEFAULT 0, oi INTEGER DEFAULT 0,
    change_oi INTEGER DEFAULT 0, iv REAL,
    delta REAL, gamma REAL, theta REAL, vega REAL,
    underlying_price REAL, pcr REAL, max_pain REAL, iv_percentile REAL,
    source TEXT, created_at TEXT,
    UNIQUE(underlying,exchange,trade_date,expiry_date,strike,option_type)
);

CREATE TABLE IF NOT EXISTS option_summary (
    id BIGSERIAL PRIMARY KEY,
    underlying TEXT, exchange TEXT DEFAULT 'NSE',
    trade_date TEXT, expiry_date TEXT,
    total_ce_oi INTEGER DEFAULT 0, total_pe_oi INTEGER DEFAULT 0,
    total_ce_volume INTEGER DEFAULT 0, total_pe_volume INTEGER DEFAULT 0,
    pcr_oi REAL, pcr_volume REAL, max_pain REAL, atm_strike REAL,
    underlying_price REAL, iv_percentile REAL, source TEXT, created_at TEXT,
    UNIQUE(underlying,exchange,trade_date,expiry_date)
);

-- â”€â”€ Index / Sector data â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS index_data (
    id BIGSERIAL PRIMARY KEY,
    index_name TEXT, symbol TEXT, trade_date TEXT,
    open REAL, high REAL, low REAL, close REAL,
    prev_close REAL, change_pct REAL, volume REAL,
    source TEXT, created_at TEXT, UNIQUE(symbol, trade_date)
);

CREATE TABLE IF NOT EXISTS sector_data (
    id BIGSERIAL PRIMARY KEY,
    sector TEXT, trade_date TEXT,
    weekly_rsi REAL, change_pct REAL,
    above_ema20 INTEGER DEFAULT 0, above_ema50 INTEGER DEFAULT 0,
    volume_ratio REAL DEFAULT 1.0, delivery_pct REAL,
    advance_count INTEGER DEFAULT 0, decline_count INTEGER DEFAULT 0,
    advance_decline_ratio REAL,
    fii_net REAL, dii_net REAL, pcr REAL,
    trend_score INTEGER DEFAULT 50, trend_phase TEXT DEFAULT 'NEUTRAL',
    signal TEXT DEFAULT 'HOLD',
    source TEXT DEFAULT 'computed', created_at TEXT,
    UNIQUE(sector, trade_date)
);

-- â”€â”€ ML Engine â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS ml_models (
    id BIGSERIAL PRIMARY KEY, institution_id INTEGER DEFAULT 1,
    model_name TEXT, model_type TEXT, target TEXT, scope TEXT DEFAULT 'equity',
    features_used TEXT, training_from TEXT, training_to TEXT,
    accuracy REAL, precision_val REAL, recall REAL, f1_score REAL,
    win_rate REAL, profit_factor REAL, max_drawdown REAL,
    feature_importance TEXT,
    buy_logic TEXT, sell_logic TEXT, hold_logic TEXT, risk_logic TEXT,
    model_version TEXT, status TEXT DEFAULT 'active',
    auto_retrain INTEGER DEFAULT 1, retrain_frequency TEXT DEFAULT 'weekly',
    last_trained TEXT, next_train TEXT, notes TEXT,
    created_at TEXT, updated_at TEXT
);

CREATE TABLE IF NOT EXISTS ml_training_log (
    id BIGSERIAL PRIMARY KEY,
    model_id INTEGER, run_date TEXT, data_rows INTEGER,
    training_accuracy REAL, test_accuracy REAL,
    precision_val REAL, recall REAL, f1 REAL,
    win_rate REAL, profit_factor REAL,
    duration_seconds INTEGER, status TEXT,
    error_message TEXT, created_at TEXT
);

-- â”€â”€ Strategies â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS strategies (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, institution_id INTEGER DEFAULT 1,
    name TEXT, description TEXT, mode TEXT,
    segment TEXT DEFAULT 'EQ-SWG',
    strategy_type TEXT DEFAULT 'technical',
    logic TEXT, entry_logic TEXT, exit_logic TEXT,
    fundamental_conditions TEXT, technical_conditions TEXT,
    stop_loss_pct REAL DEFAULT 5, target_pct REAL DEFAULT 10,
    trailing_sl_ph1 REAL DEFAULT 5, trailing_sl_ph2 REAL DEFAULT 15,
    holding_days INTEGER DEFAULT 22,
    trade_window_start TEXT DEFAULT '15:10',
    trade_window_end TEXT DEFAULT '15:20',
    universe TEXT DEFAULT 'ALL_CASH',
    rr_min REAL DEFAULT 2.0, max_positions INTEGER DEFAULT 5,
    max_per_sector INTEGER DEFAULT 2, risk_per_trade_pct REAL DEFAULT 2.0,
    use_ai_filter INTEGER DEFAULT 0, ai_min_score REAL DEFAULT 60,
    status TEXT DEFAULT 'active', created_at TEXT, disabled_reason TEXT
);

-- â”€â”€ Screeners â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS screeners (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, institution_id INTEGER DEFAULT 1,
    name TEXT, description TEXT, logic_text TEXT,
    timeframe TEXT DEFAULT 'Daily', universe TEXT DEFAULT 'NSE_500',
    segment TEXT DEFAULT 'EQ-SWG', min_market_cap REAL DEFAULT 0,
    last_run TEXT, last_count INTEGER DEFAULT 0,
    status TEXT DEFAULT 'active', created_at TEXT
);

CREATE TABLE IF NOT EXISTS screener_results (
    id BIGSERIAL PRIMARY KEY,
    screener_id INTEGER, strategy_id INTEGER,
    exchange TEXT, symbol TEXT, company_name TEXT, sector TEXT,
    cmp REAL, change_pct REAL, volume REAL, total_volume REAL,
    delivery_volume REAL, delivery_pct REAL,
    weekly_rsi REAL, daily_rsi REAL, rsi_slope REAL,
    above_ema20 INTEGER DEFAULT 0, above_ema50 INTEGER DEFAULT 0,
    macd_signal TEXT DEFAULT 'neutral',
    trend_phase TEXT DEFAULT 'NEUTRAL', trend_score INTEGER DEFAULT 50,
    ai_score REAL DEFAULT 50, trade_score REAL DEFAULT 50,
    buy_probability REAL DEFAULT 50, risk_level TEXT DEFAULT 'Medium',
    stop_loss_price REAL, stop_loss_pct REAL,
    target_price REAL, target_pct REAL, rr_ratio REAL,
    week52_high REAL, week52_low REAL, ath REAL, atl REAL,
    dist_from_ath_pct REAL, pe REAL, roe REAL, debt_equity REAL,
    oi_change_pct REAL, iv_percentile REAL,
    selection_reason TEXT, sector_ai_signal TEXT,
    trailing_sl_level REAL, trailing_sl_phase INTEGER DEFAULT 0,
    status TEXT, scan_date TEXT
);

CREATE TABLE IF NOT EXISTS scanner_results (
    id BIGSERIAL PRIMARY KEY,
    strategy_id INTEGER, exchange TEXT, symbol TEXT,
    company_name TEXT, sector TEXT, segment TEXT DEFAULT 'EQ-SWG',
    cmp REAL, change_pct REAL, volume REAL, total_volume REAL,
    delivery_volume REAL, delivery_pct REAL, trade_score REAL DEFAULT 50,
    stop_loss_price REAL, stop_loss_pct REAL,
    target_price REAL, target_pct REAL, rr_ratio REAL,
    week52_high REAL, week52_low REAL, ath REAL, atl REAL,
    dist_from_ath_pct REAL, pe REAL, roe REAL, sector_ai_signal TEXT,
    trailing_sl_level REAL, trailing_sl_phase INTEGER DEFAULT 0,
    trade_open_period TEXT, status TEXT, scan_date TEXT
);

-- â”€â”€ Backtests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS backtests (
    id BIGSERIAL PRIMARY KEY,
    strategy_id INTEGER, institution_id INTEGER DEFAULT 1,
    exchange_scope TEXT, segment TEXT,
    from_date TEXT, to_date TEXT, initial_capital REAL,
    total_trades INTEGER, wins INTEGER, losses INTEGER,
    win_rate REAL, net_pnl REAL, gross_profit REAL, gross_loss REAL,
    profit_factor REAL, max_drawdown REAL, cagr REAL,
    sharpe_ratio REAL, sortino_ratio REAL, expectancy REAL,
    avg_profit_per_trade REAL, avg_loss_per_trade REAL,
    best_trade REAL, worst_trade REAL, avg_holding_days REAL,
    sector_wise_pnl TEXT, notes TEXT, created_at TEXT
);

-- â”€â”€ Paper trades â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS paper_trades (
    id BIGSERIAL PRIMARY KEY,
    trade_id TEXT UNIQUE,
    strategy_id INTEGER, user_id INTEGER, institution_id INTEGER DEFAULT 1,
    competition_id INTEGER, trade_type TEXT,
    exchange TEXT, symbol TEXT, sector TEXT, segment TEXT DEFAULT 'EQ-SWG',
    qty INTEGER, entry_price REAL, entry_date TEXT,
    stop_loss REAL, stop_loss_pct REAL, target REAL, target_pct REAL, rr_ratio REAL,
    trailing_sl_level REAL, trailing_sl_phase INTEGER DEFAULT 0,
    trailing_sl_active INTEGER DEFAULT 0,
    portfolio_name TEXT DEFAULT 'Default', broker_used TEXT DEFAULT 'Dhan',
    status TEXT, exit_price REAL, exit_date TEXT, exit_type TEXT,
    pnl REAL, pnl_pct REAL, holding_days INTEGER DEFAULT 0, source TEXT
);

-- â”€â”€ Trade journal â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS trade_journal (
    id BIGSERIAL PRIMARY KEY,
    trade_id TEXT, user_id INTEGER, institution_id INTEGER DEFAULT 1,
    trade_source TEXT, exchange TEXT, symbol TEXT,
    sector TEXT, segment TEXT DEFAULT 'EQ-SWG',
    trade_date TEXT, purchase_price REAL, qty_buy INTEGER,
    total_invested REAL, sale_date TEXT, sell_price REAL,
    qty_sell INTEGER, total_received REAL, brokerage_est REAL DEFAULT 0,
    gross_pnl REAL, net_pnl REAL, return_pct REAL,
    holding_days INTEGER DEFAULT 0, result_status TEXT,
    entry_remark TEXT NOT NULL DEFAULT '',   -- MANDATORY (BRD Â§10)
    closing_remark TEXT, checklist_complete INTEGER DEFAULT 0,
    ai_score_at_entry REAL, sector_signal_at_entry TEXT,
    trade_score_at_entry REAL, created_at TEXT
);

-- â”€â”€ Checklist â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS checklist_items (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, institution_id INTEGER DEFAULT 1,
    item_text TEXT, category TEXT,
    is_mandatory INTEGER DEFAULT 1, display_order INTEGER DEFAULT 0,
    status TEXT DEFAULT 'active', created_at TEXT
);

CREATE TABLE IF NOT EXISTS checklist_submissions (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, journal_id INTEGER, submission_date TEXT,
    items_json TEXT, all_passed INTEGER DEFAULT 0, created_at TEXT
);

-- â”€â”€ Watchlists / Alerts â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS watchlists (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, institution_id INTEGER DEFAULT 1,
    strategy_id INTEGER, name TEXT, created_at TEXT
);

CREATE TABLE IF NOT EXISTS watchlist_items (
    id BIGSERIAL PRIMARY KEY,
    watchlist_id INTEGER, exchange TEXT, symbol TEXT,
    company_name TEXT, sector TEXT, cmp REAL, added_at TEXT
);

CREATE TABLE IF NOT EXISTS price_alerts (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, institution_id INTEGER DEFAULT 1,
    exchange TEXT, symbol TEXT, alert_type TEXT, trigger_price REAL,
    note TEXT, status TEXT DEFAULT 'active',
    notification_channels TEXT DEFAULT 'in_app', triggered_price REAL,
    triggered_at TEXT, created_at TEXT
);

CREATE TABLE IF NOT EXISTS price_alert_history (
    id BIGSERIAL PRIMARY KEY,
    alert_id INTEGER, user_id INTEGER,
    exchange TEXT, symbol TEXT, alert_type TEXT,
    trigger_price REAL, triggered_price REAL,
    triggered_at TEXT, notification_sent INTEGER DEFAULT 0
);

-- â”€â”€ Broker connections â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS broker_connections (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, institution_id INTEGER DEFAULT 1,
    broker TEXT, user_code TEXT, client_id TEXT,
    read_only INTEGER DEFAULT 1, otp_required INTEGER DEFAULT 1,
    connection_status TEXT, last_checked TEXT, available_data TEXT,
    is_default INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS broker_data_status (
    id BIGSERIAL PRIMARY KEY,
    broker TEXT, data_type TEXT, exchange TEXT,
    last_fetch TEXT, status TEXT DEFAULT 'never',
    rows_fetched INTEGER DEFAULT 0, message TEXT, updated_at TEXT
);

CREATE TABLE IF NOT EXISTS broker_api_log (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, broker TEXT, operation TEXT,
    status TEXT, response_code INTEGER, message TEXT, created_at TEXT
);

-- â”€â”€ Market data pipeline â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS exchange_fetch_logs (
    id BIGSERIAL PRIMARY KEY,
    exchange TEXT, report_type TEXT, report_date TEXT,
    source_url TEXT, status TEXT, rows_imported INTEGER DEFAULT 0,
    message TEXT, created_at TEXT
);

CREATE TABLE IF NOT EXISTS exchange_fetch_jobs (
    id BIGSERIAL PRIMARY KEY,
    exchange TEXT, segment TEXT, report_type TEXT,
    from_date TEXT, to_date TEXT, status TEXT,
    stop_requested INTEGER DEFAULT 0, total_dates INTEGER DEFAULT 0,
    success_count INTEGER DEFAULT 0, failed_count INTEGER DEFAULT 0,
    rows_imported INTEGER DEFAULT 0, message TEXT, created_at TEXT, updated_at TEXT
);

CREATE TABLE IF NOT EXISTS upload_history (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, institution_id INTEGER DEFAULT 1,
    upload_type TEXT, filename TEXT, file_path TEXT,
    rows_received INTEGER DEFAULT 0, rows_imported INTEGER DEFAULT 0,
    rows_failed INTEGER DEFAULT 0, error_report TEXT,
    status TEXT DEFAULT 'pending', created_at TEXT
);

-- â”€â”€ FII/DII â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS fii_dii_data (
    id BIGSERIAL PRIMARY KEY,
    trade_date TEXT UNIQUE,
    fii_buy REAL, fii_sell REAL, fii_net REAL,
    dii_buy REAL, dii_sell REAL, dii_net REAL,
    source TEXT, created_at TEXT
);

CREATE TABLE IF NOT EXISTS fii_dii_fetch_log (
    id BIGSERIAL PRIMARY KEY,
    fetch_date TEXT, source TEXT DEFAULT 'manual',
    rows_imported INTEGER DEFAULT 0, status TEXT DEFAULT 'success',
    error TEXT, created_at TEXT
);

-- â”€â”€ Events â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS events (
    id BIGSERIAL PRIMARY KEY,
    event_type TEXT, symbol TEXT, event_name TEXT,
    event_date TEXT, event_time TEXT, importance TEXT, source TEXT
);

-- â”€â”€ Observation gate â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS observation_gate (
    id BIGSERIAL PRIMARY KEY,
    gate_date TEXT UNIQUE, nifty_direction TEXT, fii_dii_status TEXT,
    global_mood TEXT, sector_status TEXT, market_breadth TEXT,
    event_risk TEXT, score INTEGER, gate_status TEXT,
    recommendation TEXT, updated_at TEXT
);

CREATE TABLE IF NOT EXISTS observation_gate_v2 (
    id BIGSERIAL PRIMARY KEY,
    gate_date TEXT UNIQUE,
    nifty_pts INTEGER DEFAULT 0, breadth_pts INTEGER DEFAULT 0,
    fii_dii_pts INTEGER DEFAULT 0, global_pts INTEGER DEFAULT 0,
    event_pts INTEGER DEFAULT 0, total_score INTEGER DEFAULT 0,
    gate_status TEXT DEFAULT 'AMBER', gate_details_json TEXT,
    recommendation TEXT, override_by INTEGER, override_reason TEXT, updated_at TEXT
);

CREATE TABLE IF NOT EXISTS daily_observation_checks (
    id BIGSERIAL PRIMARY KEY,
    check_date TEXT, check_window TEXT,
    nifty TEXT, gift_nifty TEXT, usd_inr TEXT, oil_price TEXT,
    us_market TEXT, gold TEXT, national_market TEXT, fii_dii TEXT,
    india_vix TEXT, market_breadth TEXT, event_risk TEXT, checked_at TEXT
);

-- â”€â”€ System / Audit (append-only tables) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS issue_reports (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, institution_id INTEGER DEFAULT 1,
    ticket_id TEXT UNIQUE, module TEXT, issue_type TEXT,
    description TEXT, severity TEXT, priority TEXT DEFAULT 'Medium',
    status TEXT DEFAULT 'Open', auto_detected INTEGER DEFAULT 0,
    stack_trace TEXT, error_hash TEXT,
    suggested_fix TEXT, rca TEXT, resolved_at TEXT, created_at TEXT
);

CREATE TABLE IF NOT EXISTS bug_detection_log (
    id BIGSERIAL PRIMARY KEY,
    detected_at TEXT, module TEXT, component TEXT,
    error_type TEXT, error_message TEXT, stack_trace TEXT,
    error_hash TEXT, severity TEXT DEFAULT 'Low',
    auto_healed INTEGER DEFAULT 0, heal_action TEXT,
    user_id INTEGER, created_at TEXT
);

CREATE TABLE IF NOT EXISTS health_checks (
    id BIGSERIAL PRIMARY KEY,
    check_time TEXT, db_status TEXT DEFAULT 'ok',
    api_status TEXT DEFAULT 'ok', data_status TEXT DEFAULT 'ok',
    ml_status TEXT DEFAULT 'ok', backup_status TEXT DEFAULT 'ok',
    health_score REAL DEFAULT 100, issues_found TEXT, created_at TEXT
);

-- R6: Append-only audit tables â€” protect with DB-level triggers
CREATE TABLE IF NOT EXISTS activity_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, institution_id INTEGER DEFAULT 1,
    module TEXT, action TEXT, result TEXT, detail TEXT,
    ip TEXT, user_agent TEXT, created_at TEXT
);

CREATE TABLE IF NOT EXISTS consent_log (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, action TEXT, consent_text TEXT,
    accepted INTEGER DEFAULT 0, ip TEXT, created_at TEXT
);

CREATE TABLE IF NOT EXISTS security_log (
    id BIGSERIAL PRIMARY KEY,
    event_type TEXT NOT NULL, user_id INTEGER,
    ip_address TEXT, user_agent TEXT, detail TEXT,
    severity TEXT DEFAULT 'Low', timestamp TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS system_settings (key TEXT PRIMARY KEY, value TEXT);

CREATE TABLE IF NOT EXISTS feature_flags (
    id BIGSERIAL PRIMARY KEY,
    feature_name TEXT UNIQUE, enabled INTEGER DEFAULT 1,
    disabled_reason TEXT, disabled_by INTEGER,
    disabled_at TEXT, brd_reference TEXT
);

CREATE TABLE IF NOT EXISTS duplicate_removal_log (
    id BIGSERIAL PRIMARY KEY,
    table_name TEXT, removed_count INTEGER, run_by INTEGER, run_at TEXT
);

CREATE TABLE IF NOT EXISTS feedback (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, section TEXT, sub_section TEXT,
    message TEXT, priority TEXT, status TEXT,
    rating INTEGER DEFAULT 0, screenshot_path TEXT, created_at TEXT
);

CREATE TABLE IF NOT EXISTS backup_registry (
    id BIGSERIAL PRIMARY KEY,
    backup_type TEXT, scope TEXT, institution_id INTEGER,
    filename TEXT, file_path TEXT, file_size_kb REAL,
    backup_format TEXT DEFAULT 'zip', integrity_hash TEXT,
    triggered_by INTEGER, status TEXT DEFAULT 'available', created_at TEXT
);

CREATE TABLE IF NOT EXISTS market_data_archive (
    id BIGSERIAL PRIMARY KEY,
    exchange TEXT, symbol TEXT, trade_date TEXT,
    open REAL, high REAL, low REAL, close REAL, prev_close REAL,
    volume REAL, total_volume REAL, delivery_volume REAL, delivery_pct REAL,
    vwap REAL, turnover REAL, num_trades INTEGER,
    sector TEXT, series TEXT, archive_month TEXT, archived_at TEXT,
    UNIQUE(exchange, symbol, trade_date)
);

CREATE TABLE IF NOT EXISTS unusual_activity_alerts (
    id BIGSERIAL PRIMARY KEY,
    alert_type TEXT, exchange TEXT, symbol TEXT,
    instrument_type TEXT DEFAULT 'EQ', alert_message TEXT,
    price_change_pct REAL, volume_spike_x REAL,
    cmp REAL, prev_close REAL, volume REAL,
    severity TEXT DEFAULT 'Medium',
    sent_telegram INTEGER DEFAULT 0, sent_email INTEGER DEFAULT 0,
    acknowledged INTEGER DEFAULT 0, acknowledged_by INTEGER, acknowledged_at TEXT,
    resolved INTEGER DEFAULT 0, resolved_at TEXT, resolved_by INTEGER,
    created_at TEXT
);

CREATE TABLE IF NOT EXISTS communications (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER, institution_id INTEGER DEFAULT 1,
    channel TEXT, to_address TEXT, subject TEXT, body TEXT,
    sent_at TEXT, status TEXT DEFAULT 'pending',
    error TEXT, created_at TEXT
);

CREATE TABLE IF NOT EXISTS documented_queries (
    id BIGSERIAL PRIMARY KEY,
    query_name TEXT UNIQUE, description TEXT, sql_text TEXT,
    parameters TEXT, category TEXT, created_at TEXT
);

-- â”€â”€ BRD Â§17 â€” v11.4 Security tables â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS role_permissions (
    id BIGSERIAL PRIMARY KEY,
    role TEXT NOT NULL, module TEXT NOT NULL,
    permission_level TEXT DEFAULT 'NO_ACCESS',
    updated_by INTEGER, updated_at TEXT,
    UNIQUE(role, module)
);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL, token TEXT UNIQUE NOT NULL,
    expires_at TEXT NOT NULL, used INTEGER DEFAULT 0, created_at TEXT
);

CREATE TABLE IF NOT EXISTS ip_blacklist (
    id BIGSERIAL PRIMARY KEY,
    ip_address TEXT UNIQUE NOT NULL, reason TEXT,
    events_count INTEGER DEFAULT 0, blacklisted_at TEXT, expires_at TEXT
);

CREATE TABLE IF NOT EXISTS payment_invoices (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL, invoice_number TEXT UNIQUE,
    razorpay_order_id TEXT, razorpay_payment_id TEXT,
    plan_name TEXT, period_from TEXT, period_to TEXT,
    base_amount REAL, gst_amount REAL, total_amount REAL,
    currency TEXT DEFAULT 'INR', status TEXT DEFAULT 'paid',
    pdf_path TEXT, created_at TEXT
);

-- =============================================================================
-- R6: Append-only DB triggers (BRD Â§17 L4 â€” CRITICAL SECURITY)
-- Prevents deletion from audit and consent tables
-- =============================================================================







-- PostgreSQL append-only protection for audit and consent tables
CREATE OR REPLACE FUNCTION tr_deny_delete()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'This table is append-only.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS prevent_actlog_delete ON activity_logs;
CREATE TRIGGER prevent_actlog_delete
BEFORE DELETE ON activity_logs
FOR EACH ROW EXECUTE FUNCTION tr_deny_delete();

DROP TRIGGER IF EXISTS prevent_consentlog_delete ON consent_log;
CREATE TRIGGER prevent_consentlog_delete
BEFORE DELETE ON consent_log
FOR EACH ROW EXECUTE FUNCTION tr_deny_delete();

DROP TRIGGER IF EXISTS prevent_seclog_delete ON security_log;
CREATE TRIGGER prevent_seclog_delete
BEFORE DELETE ON security_log
FOR EACH ROW EXECUTE FUNCTION tr_deny_delete();
-- =============================================================================
-- Performance indexes
-- =============================================================================
CREATE INDEX IF NOT EXISTS idx_market_data_date ON market_data(trade_date);
CREATE INDEX IF NOT EXISTS idx_market_data_symbol ON market_data(symbol, exchange);
CREATE INDEX IF NOT EXISTS idx_trade_journal_user ON trade_journal(user_id);
CREATE INDEX IF NOT EXISTS idx_paper_trades_status ON paper_trades(status, portfolio_name);
CREATE INDEX IF NOT EXISTS idx_scanner_strategy ON scanner_results(strategy_id, trade_score);
CREATE INDEX IF NOT EXISTS idx_activity_logs_user ON activity_logs(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_option_chain_key ON option_chain(underlying, trade_date, expiry_date);

