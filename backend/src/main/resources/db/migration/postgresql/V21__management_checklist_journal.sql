CREATE TABLE IF NOT EXISTS user_management_audit (
    id BIGSERIAL PRIMARY KEY,
    actor_user_id BIGINT,
    target_user_id BIGINT,
    institution_id BIGINT DEFAULT 1,
    activity_type VARCHAR(80) NOT NULL,
    details_json TEXT,
    created_at VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS user_trading_permissions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    institution_id BIGINT DEFAULT 1,
    paper_trading_enabled INTEGER DEFAULT 1,
    real_trading_enabled INTEGER DEFAULT 0,
    max_order_value NUMERIC(18,2) DEFAULT 0,
    max_daily_order_value NUMERIC(18,2) DEFAULT 0,
    allowed_segments TEXT,
    broker_ids TEXT,
    updated_by BIGINT,
    updated_at VARCHAR(40),
    UNIQUE(user_id)
);

CREATE TABLE IF NOT EXISTS institution_branches (
    id BIGSERIAL PRIMARY KEY,
    institution_id BIGINT NOT NULL,
    branch_name VARCHAR(160) NOT NULL,
    city VARCHAR(80),
    manager_name VARCHAR(120),
    contact_mobile VARCHAR(40),
    status VARCHAR(40) DEFAULT 'active',
    created_at VARCHAR(40),
    updated_at VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS institution_departments (
    id BIGSERIAL PRIMARY KEY,
    institution_id BIGINT NOT NULL,
    department_name VARCHAR(160) NOT NULL,
    head_name VARCHAR(120),
    status VARCHAR(40) DEFAULT 'active',
    created_at VARCHAR(40),
    updated_at VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS institution_employees (
    id BIGSERIAL PRIMARY KEY,
    institution_id BIGINT NOT NULL,
    branch_id BIGINT,
    department_id BIGINT,
    employee_name VARCHAR(160) NOT NULL,
    email VARCHAR(160),
    mobile VARCHAR(40),
    role_name VARCHAR(100),
    status VARCHAR(40) DEFAULT 'active',
    created_at VARCHAR(40),
    updated_at VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS institution_billing (
    id BIGSERIAL PRIMARY KEY,
    institution_id BIGINT NOT NULL,
    plan_name VARCHAR(100),
    billing_amount NUMERIC(18,2) DEFAULT 0,
    renewal_date VARCHAR(40),
    payment_status VARCHAR(40) DEFAULT 'pending',
    notes TEXT,
    created_at VARCHAR(40),
    updated_at VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS institution_documents (
    id BIGSERIAL PRIMARY KEY,
    institution_id BIGINT NOT NULL,
    document_name VARCHAR(180) NOT NULL,
    document_type VARCHAR(80),
    file_reference TEXT,
    status VARCHAR(40) DEFAULT 'active',
    uploaded_by BIGINT,
    uploaded_at VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS institution_approvals (
    id BIGSERIAL PRIMARY KEY,
    institution_id BIGINT NOT NULL,
    workflow_name VARCHAR(160) NOT NULL,
    request_type VARCHAR(100),
    requested_by BIGINT,
    approver_user_id BIGINT,
    status VARCHAR(40) DEFAULT 'pending',
    remarks TEXT,
    created_at VARCHAR(40),
    updated_at VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS trading_checklist_templates (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    institution_id BIGINT DEFAULT 1,
    template_name VARCHAR(180) NOT NULL,
    strategy_key VARCHAR(120),
    strategy_name VARCHAR(180),
    trade_type VARCHAR(40),
    applies_to VARCHAR(80) DEFAULT 'ALL',
    enabled INTEGER DEFAULT 1,
    override_allowed INTEGER DEFAULT 0,
    created_at VARCHAR(40),
    updated_at VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS trading_checklist_items (
    id BIGSERIAL PRIMARY KEY,
    template_id BIGINT NOT NULL REFERENCES trading_checklist_templates(id) ON DELETE CASCADE,
    item_text TEXT NOT NULL,
    mandatory INTEGER DEFAULT 1,
    display_order INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS trading_checklist_overrides (
    id BIGSERIAL PRIMARY KEY,
    template_id BIGINT,
    user_id BIGINT,
    institution_id BIGINT DEFAULT 1,
    strategy_key VARCHAR(120),
    trade_type VARCHAR(40),
    symbol VARCHAR(80),
    override_reason TEXT,
    trade_details_json TEXT,
    created_at VARCHAR(40)
);

CREATE TABLE IF NOT EXISTS trading_journal (
    id BIGSERIAL PRIMARY KEY,
    trade_ref VARCHAR(120),
    order_id VARCHAR(120),
    user_id BIGINT,
    institution_id BIGINT DEFAULT 1,
    user_name VARCHAR(180),
    strategy_key VARCHAR(120),
    strategy_name VARCHAR(180),
    trading_mode VARCHAR(40),
    trade_type VARCHAR(60),
    stock_name VARCHAR(120),
    symbol VARCHAR(80),
    sector VARCHAR(120),
    quantity INTEGER DEFAULT 0,
    buy_price NUMERIC(18,4) DEFAULT 0,
    sell_price NUMERIC(18,4) DEFAULT 0,
    stop_loss NUMERIC(18,4) DEFAULT 0,
    trailing_stop_loss NUMERIC(18,4) DEFAULT 0,
    target_price NUMERIC(18,4) DEFAULT 0,
    capital_utilized NUMERIC(18,2) DEFAULT 0,
    margin_utilized NUMERIC(18,2) DEFAULT 0,
    brokerage_charges NUMERIC(18,2) DEFAULT 0,
    net_profit_loss NUMERIC(18,2) DEFAULT 0,
    exit_reason VARCHAR(120),
    outcome VARCHAR(80),
    system_remarks TEXT,
    user_remarks TEXT,
    broker_name VARCHAR(120),
    broker_response TEXT,
    created_at VARCHAR(40),
    updated_at VARCHAR(40)
);

CREATE INDEX IF NOT EXISTS idx_trading_journal_user_created ON trading_journal(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_trading_journal_symbol ON trading_journal(symbol);
CREATE INDEX IF NOT EXISTS idx_checklist_strategy ON trading_checklist_templates(institution_id, strategy_key, trade_type);
