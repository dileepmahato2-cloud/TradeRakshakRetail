-- =============================================================================
-- TradeRakshak™ v11.4 — V3 Migration: Complete User Hierarchy System
-- 10 User Types + 3 Agent Levels + 6 Subscription Plans
-- Future-proof: wallets, KYC, device binding, franchise, commissions
-- =============================================================================

-- ── Extended users table (new columns added safely) ──────────────────────────
ALTER TABLE users ADD COLUMN user_type         TEXT DEFAULT 'paid_subscriber';
ALTER TABLE users ADD COLUMN parent_user_id    INTEGER;
ALTER TABLE users ADD COLUMN created_by        INTEGER;
ALTER TABLE users ADD COLUMN approved_by       INTEGER;
ALTER TABLE users ADD COLUMN approved_at       TEXT;
ALTER TABLE users ADD COLUMN referral_code     TEXT;
ALTER TABLE users ADD COLUMN referred_by_code  TEXT;
ALTER TABLE users ADD COLUMN wallet_balance    REAL DEFAULT 0;
ALTER TABLE users ADD COLUMN wallet_credits    REAL DEFAULT 0;
ALTER TABLE users ADD COLUMN kyc_documents     TEXT;
ALTER TABLE users ADD COLUMN kyc_verified_at   TEXT;
ALTER TABLE users ADD COLUMN device_hash       TEXT;
ALTER TABLE users ADD COLUMN api_key           TEXT;
ALTER TABLE users ADD COLUMN api_calls_today   INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN api_calls_limit   INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN trial_ends_at     TEXT;
ALTER TABLE users ADD COLUMN auto_renew        INTEGER DEFAULT 1;
ALTER TABLE users ADD COLUMN notes             TEXT;
ALTER TABLE users ADD COLUMN tags              TEXT;

-- ── User Types (master reference) ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_types (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    type_code     TEXT UNIQUE NOT NULL,  -- e.g. 'super_admin','agent_l1'
    display_name  TEXT NOT NULL,
    hierarchy_level INTEGER NOT NULL,    -- 1=top, 10=bottom
    can_create_types TEXT,               -- JSON array of types this can create
    description   TEXT,
    is_active     INTEGER DEFAULT 1,
    created_at    TEXT
);

-- ── Role Permission Matrix ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS permissions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_type       TEXT NOT NULL,
    module          TEXT NOT NULL,
    can_view        INTEGER DEFAULT 0,
    can_create      INTEGER DEFAULT 0,
    can_edit        INTEGER DEFAULT 0,
    can_delete      INTEGER DEFAULT 0,
    can_approve     INTEGER DEFAULT 0,
    can_export      INTEGER DEFAULT 0,
    custom_flags    TEXT,               -- JSON for extra flags
    updated_by      INTEGER,
    updated_at      TEXT,
    UNIQUE(user_type, module)
);

-- ── Agent hierarchy ───────────────────────────────────────────────────────────
ALTER TABLE agents ADD COLUMN agent_level      INTEGER DEFAULT 1;
ALTER TABLE agents ADD COLUMN parent_agent_id  INTEGER;
ALTER TABLE agents ADD COLUMN total_referrals  INTEGER DEFAULT 0;
ALTER TABLE agents ADD COLUMN total_revenue    REAL DEFAULT 0;
ALTER TABLE agents ADD COLUMN wallet_balance   REAL DEFAULT 0;
ALTER TABLE agents ADD COLUMN payout_method    TEXT DEFAULT 'bank_transfer';
ALTER TABLE agents ADD COLUMN gst_number       TEXT;
ALTER TABLE agents ADD COLUMN territory        TEXT;

-- ── Commission slabs (tiered) ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS commission_slabs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_level     INTEGER NOT NULL,   -- 1,2,3
    plan_name       TEXT NOT NULL,
    commission_pct  REAL NOT NULL,
    override_pct    REAL DEFAULT 0,     -- override from parent agent
    min_revenue     REAL DEFAULT 0,
    max_revenue     REAL,
    effective_from  TEXT,
    effective_to    TEXT,
    status          TEXT DEFAULT 'active',
    created_at      TEXT
);

-- ── Referral tracking ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS referrals (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    referrer_code   TEXT NOT NULL,
    referrer_user_id INTEGER NOT NULL,
    referred_user_id INTEGER NOT NULL,
    referred_at     TEXT,
    first_payment_at TEXT,
    commission_paid INTEGER DEFAULT 0,
    commission_amount REAL DEFAULT 0,
    status          TEXT DEFAULT 'pending'  -- pending/converted/paid
);

-- ── Subscription lifecycle ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS subscriptions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    plan_id         INTEGER NOT NULL,
    plan_name       TEXT,
    billing_cycle   TEXT DEFAULT 'monthly',
    status          TEXT DEFAULT 'active', -- active/paused/cancelled/expired
    start_date      TEXT NOT NULL,
    end_date        TEXT NOT NULL,
    next_billing    TEXT,
    auto_renew      INTEGER DEFAULT 1,
    trial_used      INTEGER DEFAULT 0,
    upgrade_from    TEXT,
    downgrade_to    TEXT,
    cancel_reason   TEXT,
    cancelled_at    TEXT,
    created_at      TEXT,
    updated_at      TEXT
);

-- ── Subscription upgrade/downgrade log ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS subscription_changes (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    from_plan       TEXT,
    to_plan         TEXT,
    change_type     TEXT,   -- upgrade/downgrade/cancel/renew/trial_convert
    reason          TEXT,
    prorated_credit REAL DEFAULT 0,
    initiated_by    INTEGER,
    created_at      TEXT
);

-- ── Coupons & promo codes ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS coupons (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    institution_id  INTEGER DEFAULT 1,
    code            TEXT UNIQUE NOT NULL,
    coupon_type     TEXT DEFAULT 'percentage',  -- percentage/fixed/free_days
    discount_value  REAL NOT NULL,
    free_days       INTEGER DEFAULT 0,
    applicable_plans TEXT DEFAULT 'ALL',
    min_order_amount REAL DEFAULT 0,
    max_uses        INTEGER DEFAULT 0,
    uses_per_user   INTEGER DEFAULT 1,
    current_uses    INTEGER DEFAULT 0,
    valid_from      TEXT,
    valid_to        TEXT,
    created_by      INTEGER,
    status          TEXT DEFAULT 'active',
    created_at      TEXT
);

CREATE TABLE IF NOT EXISTS coupon_uses (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    coupon_id       INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    payment_id      INTEGER,
    discount_applied REAL,
    used_at         TEXT
);

-- ── User wallet / credits ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS wallet_transactions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    transaction_type TEXT NOT NULL,  -- credit/debit/refund/commission/referral_bonus
    amount          REAL NOT NULL,
    balance_after   REAL,
    description     TEXT,
    reference_id    TEXT,  -- payment_id or commission_id
    created_by      INTEGER,
    created_at      TEXT
);

-- ── KYC documents ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kyc_documents (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    document_type   TEXT NOT NULL,  -- pan_card/aadhaar/passport/bank_statement
    document_number TEXT,
    file_path       TEXT,
    verified        INTEGER DEFAULT 0,
    verified_by     INTEGER,
    verified_at     TEXT,
    rejection_reason TEXT,
    uploaded_at     TEXT
);

-- ── Device binding / login security ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_devices (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    device_hash     TEXT NOT NULL,
    device_name     TEXT,
    device_type     TEXT,  -- mobile/desktop/tablet
    os              TEXT,
    browser         TEXT,
    ip_address      TEXT,
    is_trusted      INTEGER DEFAULT 0,
    last_seen       TEXT,
    created_at      TEXT,
    UNIQUE(user_id, device_hash)
);

-- ── Franchise / partner management ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS franchises (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL,
    owner_user_id   INTEGER NOT NULL,
    institution_id  INTEGER,
    territory       TEXT,
    franchise_code  TEXT UNIQUE,
    revenue_share_pct REAL DEFAULT 30,
    status          TEXT DEFAULT 'active',
    contract_start  TEXT,
    contract_end    TEXT,
    created_at      TEXT
);

-- ── API usage tracking ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS api_usage_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    api_key         TEXT,
    endpoint        TEXT,
    method          TEXT,
    response_code   INTEGER,
    response_ms     INTEGER,
    created_at      TEXT
);

-- ── User performance / activity scoring ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_activity_scores (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL UNIQUE,
    login_score     INTEGER DEFAULT 0,
    trade_score     INTEGER DEFAULT 0,
    journal_score   INTEGER DEFAULT 0,
    checklist_score INTEGER DEFAULT 0,
    total_score     INTEGER DEFAULT 0,
    rank            INTEGER,
    computed_at     TEXT
);

-- ── Support tickets (extended) ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS support_tickets (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    ticket_id       TEXT UNIQUE NOT NULL,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER DEFAULT 1,
    assigned_to     INTEGER,  -- support_executive user_id
    category        TEXT,
    subject         TEXT NOT NULL,
    description     TEXT NOT NULL,
    priority        TEXT DEFAULT 'Medium',
    status          TEXT DEFAULT 'Open',
    resolution      TEXT,
    resolved_at     TEXT,
    first_response_at TEXT,
    satisfaction_rating INTEGER,
    created_at      TEXT,
    updated_at      TEXT
);

CREATE TABLE IF NOT EXISTS ticket_messages (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    ticket_id       INTEGER NOT NULL,
    sender_id       INTEGER NOT NULL,
    message         TEXT NOT NULL,
    is_internal     INTEGER DEFAULT 0,
    attachments     TEXT,
    created_at      TEXT
);

-- ── Training / LMS ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS training_content (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    institution_id  INTEGER DEFAULT 1,
    title           TEXT NOT NULL,
    content_type    TEXT DEFAULT 'article',  -- article/video/quiz/webinar
    category        TEXT,
    content_url     TEXT,
    description     TEXT,
    is_mandatory    INTEGER DEFAULT 0,
    required_plan   TEXT DEFAULT 'Free Trial',
    view_count      INTEGER DEFAULT 0,
    created_by      INTEGER,
    status          TEXT DEFAULT 'active',
    created_at      TEXT
);

CREATE TABLE IF NOT EXISTS training_progress (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    content_id      INTEGER NOT NULL,
    completed       INTEGER DEFAULT 0,
    progress_pct    INTEGER DEFAULT 0,
    completed_at    TEXT,
    UNIQUE(user_id, content_id)
);

-- ── GST invoices (extended) ───────────────────────────────────────────────────
ALTER TABLE payment_history ADD COLUMN gst_number     TEXT;
ALTER TABLE payment_history ADD COLUMN gst_state_code TEXT;
ALTER TABLE payment_history ADD COLUMN hsn_code       TEXT DEFAULT '998314';
ALTER TABLE payment_history ADD COLUMN igst_amount    REAL DEFAULT 0;
ALTER TABLE payment_history ADD COLUMN cgst_amount    REAL DEFAULT 0;
ALTER TABLE payment_history ADD COLUMN sgst_amount    REAL DEFAULT 0;
ALTER TABLE payment_history ADD COLUMN billing_name   TEXT;
ALTER TABLE payment_history ADD COLUMN billing_address TEXT;

-- ── Indexes for performance ───────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_users_type     ON users(user_type);
CREATE INDEX IF NOT EXISTS idx_users_parent   ON users(parent_user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_referral ON users(referral_code);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_api_key ON users(api_key);
CREATE INDEX IF NOT EXISTS idx_referrals_code ON referrals(referrer_code);
CREATE INDEX IF NOT EXISTS idx_subscriptions_user ON subscriptions(user_id, status);
CREATE INDEX IF NOT EXISTS idx_wallet_user    ON wallet_transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_api_usage_user ON api_usage_log(user_id, created_at);
