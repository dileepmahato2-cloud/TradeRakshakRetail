-- =============================================================================
-- TradeRakshak™ v11.4 — V9: Enterprise Master Document Enhancements
-- Based on: 7 Master Documents + 23-Volume Phase-Wise Workflow BRD
-- Covers: Portfolio, Scanner, Alerts, Competitions, Agent, Loyalty,
--         Compliance, Reports, Institution White-Label, MFA, Razorpay FSM
-- =============================================================================

-- ── 1. Portfolio Management (Volume 8) ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS portfolios (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    name            TEXT NOT NULL,
    description     TEXT,
    portfolio_type  TEXT DEFAULT 'paper',  -- paper/live/model
    broker_id       TEXT,
    initial_capital REAL DEFAULT 100000,
    current_value   REAL DEFAULT 0,
    cash_balance    REAL DEFAULT 100000,
    unrealised_pnl  REAL DEFAULT 0,
    realised_pnl    REAL DEFAULT 0,
    total_pnl       REAL DEFAULT 0,
    total_pnl_pct   REAL DEFAULT 0,
    max_drawdown    REAL DEFAULT 0,
    sharpe_ratio    REAL DEFAULT 0,
    is_default      INTEGER DEFAULT 0,
    risk_profile    TEXT DEFAULT 'moderate',  -- conservative/moderate/aggressive
    status          TEXT DEFAULT 'active',
    created_at      TEXT,
    updated_at      TEXT
);

CREATE TABLE IF NOT EXISTS positions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    portfolio_id    INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    strategy_id     INTEGER,
    exchange        TEXT DEFAULT 'NSE',
    symbol          TEXT NOT NULL,
    company_name    TEXT,
    sector          TEXT,
    segment         TEXT DEFAULT 'EQ-SWG',
    position_type   TEXT DEFAULT 'LONG',
    qty             INTEGER DEFAULT 0,
    avg_price       REAL DEFAULT 0,
    current_price   REAL DEFAULT 0,
    invested_value  REAL DEFAULT 0,
    current_value   REAL DEFAULT 0,
    unrealised_pnl  REAL DEFAULT 0,
    unrealised_pct  REAL DEFAULT 0,
    stop_loss       REAL,
    target          REAL,
    entry_date      TEXT,
    status          TEXT DEFAULT 'open',  -- open/closed/partial
    broker_order_id TEXT,
    notes           TEXT,
    created_at      TEXT,
    updated_at      TEXT
);

CREATE TABLE IF NOT EXISTS trades (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    portfolio_id    INTEGER NOT NULL,
    position_id     INTEGER,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    trade_id        TEXT UNIQUE,
    exchange        TEXT DEFAULT 'NSE',
    symbol          TEXT NOT NULL,
    segment         TEXT DEFAULT 'EQ-SWG',
    trade_type      TEXT NOT NULL,  -- BUY/SELL/SHORT/COVER
    qty             INTEGER,
    price           REAL,
    amount          REAL,
    brokerage       REAL DEFAULT 0,
    taxes           REAL DEFAULT 0,
    net_amount      REAL,
    execution_type  TEXT DEFAULT 'manual',  -- manual/strategy/auto
    strategy_id     INTEGER,
    broker_order_id TEXT,
    status          TEXT DEFAULT 'executed',
    trade_date      TEXT,
    created_at      TEXT
);

-- ── 2. Enhanced Scanner Engine (Volume 6) ────────────────────────────────────
CREATE TABLE IF NOT EXISTS scanners (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    name            TEXT NOT NULL,
    description     TEXT,
    scanner_type    TEXT DEFAULT 'equity',  -- equity/futures/options/sector/ai
    conditions_json TEXT,                   -- structured condition tree
    logic_text      TEXT,                   -- human-readable logic
    universe        TEXT DEFAULT 'NSE_500',
    timeframe       TEXT DEFAULT 'Daily',
    schedule        TEXT DEFAULT 'manual',  -- manual/daily/hourly/realtime
    last_run        TEXT,
    last_count      INTEGER DEFAULT 0,
    total_runs      INTEGER DEFAULT 0,
    is_template     INTEGER DEFAULT 0,
    is_public       INTEGER DEFAULT 0,
    tags            TEXT,
    status          TEXT DEFAULT 'active',
    created_at      TEXT,
    updated_at      TEXT
);

CREATE TABLE IF NOT EXISTS scanner_executions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    scanner_id      INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    started_at      TEXT,
    completed_at    TEXT,
    symbols_scanned INTEGER DEFAULT 0,
    matches_found   INTEGER DEFAULT 0,
    execution_ms    INTEGER DEFAULT 0,
    status          TEXT DEFAULT 'completed'
);

-- ── 3. Alert Engine (Volume 9) ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS alert_rules (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    scanner_id      INTEGER,
    strategy_id     INTEGER,
    alert_name      TEXT NOT NULL,
    alert_type      TEXT DEFAULT 'price',  -- price/volume/delivery/oi/breakout/ai/portfolio/risk
    priority        TEXT DEFAULT 'medium', -- critical/high/medium/low
    condition_json  TEXT,
    universe        TEXT DEFAULT 'watchlist',
    timeframe       TEXT DEFAULT 'Daily',
    channels_json   TEXT DEFAULT '["in_app"]',
    frequency       TEXT DEFAULT 'once',   -- once/always/daily
    cooldown_mins   INTEGER DEFAULT 60,
    is_active       INTEGER DEFAULT 1,
    triggered_count INTEGER DEFAULT 0,
    last_triggered  TEXT,
    valid_until     TEXT,
    status          TEXT DEFAULT 'active',
    created_at      TEXT,
    updated_at      TEXT
);

CREATE TABLE IF NOT EXISTS alert_notifications (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    alert_id        INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    triggered_at    TEXT,
    symbol          TEXT,
    trigger_value   REAL,
    trigger_message TEXT,
    channels_sent   TEXT,  -- JSON {"telegram":true,"email":false}
    delivery_status TEXT DEFAULT 'sent',
    read_at         TEXT
);

-- ── 4. Competition Module (Volume 11) ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS competition_registrations (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    competition_id  INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    registered_at   TEXT,
    portfolio_id    INTEGER,  -- virtual portfolio for competition
    entry_capital   REAL DEFAULT 1000000,
    current_value   REAL DEFAULT 1000000,
    rank            INTEGER,
    score           REAL DEFAULT 0,
    total_trades    INTEGER DEFAULT 0,
    win_rate        REAL DEFAULT 0,
    sharpe_ratio    REAL DEFAULT 0,
    max_drawdown    REAL DEFAULT 0,
    status          TEXT DEFAULT 'active',
    disqualified    INTEGER DEFAULT 0,
    disqualify_reason TEXT,
    UNIQUE(competition_id, user_id)
);

CREATE TABLE IF NOT EXISTS competition_rewards (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    competition_id  INTEGER NOT NULL,
    rank            INTEGER NOT NULL,
    reward_type     TEXT,  -- cash/wallet/subscription_days/badge/certificate
    reward_value    TEXT,
    distributed     INTEGER DEFAULT 0,
    distributed_at  TEXT
);

-- ── 5. Agent Commission Engine (Volume 12) ────────────────────────────────────
CREATE TABLE IF NOT EXISTS agent_leads (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_user_id   INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    lead_name       TEXT NOT NULL,
    lead_email      TEXT,
    lead_mobile     TEXT,
    source          TEXT DEFAULT 'referral',
    status          TEXT DEFAULT 'new',  -- new/contacted/demo/converted/lost
    notes           TEXT,
    converted_user_id INTEGER,
    converted_at    TEXT,
    created_at      TEXT,
    updated_at      TEXT
);

CREATE TABLE IF NOT EXISTS agent_commissions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_id        INTEGER NOT NULL,
    agent_user_id   INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    payment_id      INTEGER,
    subscriber_user_id INTEGER,
    plan_name       TEXT,
    payment_amount  REAL,
    commission_pct  REAL,
    commission_amount REAL,
    override_commission REAL DEFAULT 0,
    total_commission REAL,
    level           INTEGER DEFAULT 1,
    status          TEXT DEFAULT 'pending',  -- pending/approved/paid/rejected
    approved_by     INTEGER,
    approved_at     TEXT,
    paid_at         TEXT,
    created_at      TEXT
);

ALTER TABLE agent_commissions ADD COLUMN agent_user_id   INTEGER;
ALTER TABLE agent_commissions ADD COLUMN institution_id  INTEGER DEFAULT 1;
ALTER TABLE agent_commissions ADD COLUMN payment_id      INTEGER;
ALTER TABLE agent_commissions ADD COLUMN subscriber_user_id INTEGER;
ALTER TABLE agent_commissions ADD COLUMN payment_amount  REAL;
ALTER TABLE agent_commissions ADD COLUMN override_commission REAL DEFAULT 0;
ALTER TABLE agent_commissions ADD COLUMN total_commission REAL;
ALTER TABLE agent_commissions ADD COLUMN level           INTEGER DEFAULT 1;
ALTER TABLE agent_commissions ADD COLUMN approved_by     INTEGER;
ALTER TABLE agent_commissions ADD COLUMN approved_at     TEXT;

CREATE TABLE IF NOT EXISTS payout_requests (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_user_id   INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    amount          REAL NOT NULL,
    bank_name       TEXT,
    account_number  TEXT,
    ifsc_code       TEXT,
    account_holder  TEXT,
    status          TEXT DEFAULT 'pending',
    approved_by     INTEGER,
    approved_at     TEXT,
    paid_at         TEXT,
    utr_number      TEXT,
    notes           TEXT,
    created_at      TEXT
);

-- ── 6. Loyalty & Gamification (Volume 11) ─────────────────────────────────────
CREATE TABLE IF NOT EXISTS loyalty_points (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    action          TEXT NOT NULL,  -- login/trade/backtest/quiz/referral/competition
    points          INTEGER DEFAULT 0,
    balance_after   INTEGER DEFAULT 0,
    reference_id    TEXT,
    created_at      TEXT
);

CREATE TABLE IF NOT EXISTS loyalty_tiers (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    tier_name       TEXT UNIQUE NOT NULL,  -- Bronze/Silver/Gold/Platinum/Diamond/Elite
    min_points      INTEGER NOT NULL,
    max_points      INTEGER,
    benefits_json   TEXT,
    badge_icon      TEXT,
    created_at      TEXT
);

CREATE TABLE IF NOT EXISTS user_badges (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    badge_type      TEXT NOT NULL,
    badge_name      TEXT NOT NULL,
    badge_desc      TEXT,
    earned_at       TEXT,
    UNIQUE(user_id, badge_type)
);

-- ── 7. Compliance & KYC (Volume 19) ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kyc_reviews (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    reviewer_id     INTEGER,
    document_type   TEXT,
    document_number TEXT,
    verification_status TEXT DEFAULT 'pending',
    rejection_reason TEXT,
    aml_flag        INTEGER DEFAULT 0,
    aml_reason      TEXT,
    reviewed_at     TEXT,
    created_at      TEXT
);

CREATE TABLE IF NOT EXISTS login_history (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    login_at        TEXT NOT NULL,
    ip_address      TEXT,
    device_info     TEXT,
    browser         TEXT,
    os_info         TEXT,
    location        TEXT,
    login_method    TEXT DEFAULT 'password',
    mfa_used        INTEGER DEFAULT 0,
    success         INTEGER DEFAULT 1,
    failure_reason  TEXT
);

-- ── 8. Reports Engine (Volume 18) ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS report_configs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    institution_id  INTEGER DEFAULT 1,
    created_by      INTEGER NOT NULL,
    report_name     TEXT NOT NULL,
    report_type     TEXT NOT NULL,  -- user/subscription/trading/market/custom
    filters_json    TEXT,
    columns_json    TEXT,
    schedule        TEXT DEFAULT 'manual',
    format          TEXT DEFAULT 'excel',  -- excel/csv/pdf
    email_recipients TEXT,
    last_run        TEXT,
    status          TEXT DEFAULT 'active',
    created_at      TEXT
);

CREATE TABLE IF NOT EXISTS report_history (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    report_config_id INTEGER,
    institution_id  INTEGER DEFAULT 1,
    generated_by    INTEGER NOT NULL,
    report_name     TEXT,
    report_type     TEXT,
    format          TEXT,
    file_path       TEXT,
    file_size_kb    REAL,
    rows_exported   INTEGER DEFAULT 0,
    generated_at    TEXT,
    download_count  INTEGER DEFAULT 0
);

-- ── 9. Subscription FSM (7 states per Doc 2.3) ───────────────────────────────
ALTER TABLE subscriptions ADD COLUMN state TEXT DEFAULT 'PENDING';
ALTER TABLE subscriptions ADD COLUMN grace_until TEXT;
ALTER TABLE subscriptions ADD COLUMN razorpay_subscription_id TEXT;
ALTER TABLE subscriptions ADD COLUMN razorpay_order_id TEXT;
ALTER TABLE subscriptions ADD COLUMN refund_amount REAL DEFAULT 0;
ALTER TABLE subscriptions ADD COLUMN refund_reason TEXT;
ALTER TABLE subscriptions ADD COLUMN refunded_at TEXT;

-- ── 10. Blackout scheduling for backtesting ───────────────────────────────────
CREATE TABLE IF NOT EXISTS system_blackouts (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    blackout_type   TEXT DEFAULT 'backtesting',
    start_time      TEXT DEFAULT '09:00',
    end_time        TEXT DEFAULT '16:00',
    days            TEXT DEFAULT 'Mon,Tue,Wed,Thu,Fri',
    timezone        TEXT DEFAULT 'Asia/Kolkata',
    message         TEXT DEFAULT 'Backtesting is unavailable during market hours (09:00–16:00 IST)',
    is_active       INTEGER DEFAULT 1,
    created_at      TEXT
);

-- ── 11. MFA / OTP ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS mfa_settings (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER UNIQUE NOT NULL,
    mfa_type        TEXT DEFAULT 'totp',  -- totp/sms/email
    mfa_secret      TEXT,
    is_enabled      INTEGER DEFAULT 0,
    backup_codes    TEXT,  -- JSON array of one-time backup codes
    setup_at        TEXT,
    last_used       TEXT
);

CREATE TABLE IF NOT EXISTS otp_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER,
    mobile          TEXT,
    email           TEXT,
    otp_type        TEXT DEFAULT 'login',  -- login/password_reset/mfa_setup
    otp_hash        TEXT,  -- bcrypt of OTP
    expires_at      TEXT,
    used            INTEGER DEFAULT 0,
    used_at         TEXT,
    ip_address      TEXT,
    created_at      TEXT
);

-- ── 12. GST & Invoice (Volume 2) ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS invoices (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    invoice_number  TEXT UNIQUE NOT NULL,
    invoice_date    TEXT NOT NULL,
    due_date        TEXT,
    billing_name    TEXT,
    billing_address TEXT,
    billing_city    TEXT,
    billing_state   TEXT,
    billing_pincode TEXT,
    buyer_gstin     TEXT,
    seller_gstin    TEXT DEFAULT 'GSTIN_PLACEHOLDER',
    hsn_sac_code    TEXT DEFAULT '998314',
    description     TEXT,
    base_amount     REAL,
    discount_amount REAL DEFAULT 0,
    taxable_amount  REAL,
    cgst_rate       REAL DEFAULT 9,
    cgst_amount     REAL DEFAULT 0,
    sgst_rate       REAL DEFAULT 9,
    sgst_amount     REAL DEFAULT 0,
    igst_rate       REAL DEFAULT 0,
    igst_amount     REAL DEFAULT 0,
    total_tax       REAL,
    total_amount    REAL,
    payment_id      INTEGER,
    razorpay_payment_id TEXT,
    status          TEXT DEFAULT 'generated',
    pdf_path        TEXT,
    sebi_disclaimer TEXT DEFAULT 'TradeRakshak™ is NOT a SEBI-registered Investment Advisor. For educational purposes only.',
    created_at      TEXT
);

-- ── 13. Unusual Activity Detection (Volume 20) ───────────────────────────────
CREATE TABLE IF NOT EXISTS unusual_activity (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    institution_id  INTEGER DEFAULT 1,
    exchange        TEXT DEFAULT 'NSE',
    symbol          TEXT NOT NULL,
    detection_type  TEXT NOT NULL,  -- volume_spike/delivery_spike/price_spike/oi_spike/circuit/promoter/block_deal/bulk_deal/insider
    severity        TEXT DEFAULT 'medium',
    detected_value  REAL,
    baseline_value  REAL,
    spike_ratio     REAL,
    message         TEXT,
    ai_confidence   REAL DEFAULT 0,
    is_confirmed    INTEGER DEFAULT 0,
    confirmed_by    INTEGER,
    alert_sent      INTEGER DEFAULT 0,
    trade_date      TEXT,
    detected_at     TEXT
);

-- ── 14. Market Intelligence (Volume 20) ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS market_breadth (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    trade_date      TEXT NOT NULL,
    exchange        TEXT DEFAULT 'NSE',
    advance_count   INTEGER DEFAULT 0,
    decline_count   INTEGER DEFAULT 0,
    unchanged_count INTEGER DEFAULT 0,
    total_stocks    INTEGER DEFAULT 0,
    advance_decline_ratio REAL DEFAULT 0,
    new_52w_high    INTEGER DEFAULT 0,
    new_52w_low     INTEGER DEFAULT 0,
    above_200dma    INTEGER DEFAULT 0,
    below_200dma    INTEGER DEFAULT 0,
    market_score    INTEGER DEFAULT 50,  -- 0-100 market health
    market_phase    TEXT DEFAULT 'NEUTRAL',
    fii_net_flow    REAL DEFAULT 0,
    dii_net_flow    REAL DEFAULT 0,
    created_at      TEXT,
    UNIQUE(trade_date, exchange)
);

-- ── 15. White-label Tenant Branding (Volume 3) ───────────────────────────────
CREATE TABLE IF NOT EXISTS tenant_branding (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    institution_id  INTEGER UNIQUE NOT NULL,
    primary_color   TEXT DEFAULT '#1B3A5C',
    secondary_color TEXT DEFAULT '#006B6B',
    accent_color    TEXT DEFAULT '#C9961A',
    font_family     TEXT DEFAULT 'Inter',
    logo_url        TEXT,
    favicon_url     TEXT,
    custom_domain   TEXT UNIQUE,
    ssl_enabled     INTEGER DEFAULT 0,
    email_from_name TEXT,
    email_from_addr TEXT,
    email_header_logo TEXT,
    footer_text     TEXT,
    sebi_disclaimer TEXT,
    is_active       INTEGER DEFAULT 1,
    updated_by      INTEGER,
    updated_at      TEXT
);

-- ── 16. SEBI Disclaimer tracking ─────────────────────────────────────────────
ALTER TABLE consent_log ADD COLUMN disclaimer_version TEXT DEFAULT '1.0';
ALTER TABLE consent_log ADD COLUMN platform_version TEXT DEFAULT '11.4';

-- ── Indexes ───────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_portfolios_user     ON portfolios(user_id, status);
CREATE INDEX IF NOT EXISTS idx_positions_portfolio ON positions(portfolio_id, status);
CREATE INDEX IF NOT EXISTS idx_trades_portfolio    ON trades(portfolio_id, trade_date);
CREATE INDEX IF NOT EXISTS idx_alert_rules_user    ON alert_rules(user_id, status);
CREATE INDEX IF NOT EXISTS idx_alert_notif_user    ON alert_notifications(user_id, triggered_at);
CREATE INDEX IF NOT EXISTS idx_comp_reg            ON competition_registrations(competition_id, rank);
CREATE INDEX IF NOT EXISTS idx_agent_commissions   ON agent_commissions(agent_user_id, status);
CREATE INDEX IF NOT EXISTS idx_loyalty_user        ON loyalty_points(user_id);
CREATE INDEX IF NOT EXISTS idx_login_history       ON login_history(user_id, login_at);
CREATE INDEX IF NOT EXISTS idx_unusual_activity    ON unusual_activity(trade_date, severity);
CREATE INDEX IF NOT EXISTS idx_invoices_user       ON invoices(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_scanners_user       ON scanners(user_id, status);
