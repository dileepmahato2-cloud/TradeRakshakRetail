-- Relocated from trep-institution-service (V6__finance_module.sql) on 2026-08-19.
-- Migration ownership rule: trep-core owns migrations for CRM, LMS, Finance,
-- Trading, AI and Platform tables; trep-institution-service owns ERP tables only.
-- Content is unchanged and fully idempotent (every CREATE/ALTER is guarded,
-- no INSERTs), so re-running it against an existing database is a no-op.

-- V6: Finance module (BRD Volume 2, Section 8)
-- Fees, Installments, Scholarships, Refunds, GST, Accounting Integration, Ledger

-- ── 8.1 Fee Structures ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS fin_fee_structures (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    course_id        BIGINT,
    batch_id         BIGINT,
    structure_name   VARCHAR(200) NOT NULL,
    status           VARCHAR(20) DEFAULT 'active',
    created_by       BIGINT,
    created_at       VARCHAR(30),
    updated_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_ffs_inst ON fin_fee_structures(institution_id);

CREATE TABLE IF NOT EXISTS fin_fee_components (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    structure_id     BIGINT NOT NULL,
    component_type   VARCHAR(50) NOT NULL DEFAULT 'tuition',
    component_name   VARCHAR(200) NOT NULL,
    amount           DOUBLE PRECISION NOT NULL,
    hsn_sac_code     VARCHAR(20),
    gst_rate_pct     DOUBLE PRECISION DEFAULT 0,
    display_order    INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ffc_struct ON fin_fee_components(structure_id);

-- ── 8.1 Fee Accounts (per student) ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS fin_fee_accounts (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    student_user_id  BIGINT NOT NULL,
    admission_id     BIGINT,
    structure_id     BIGINT,
    total_due        DOUBLE PRECISION DEFAULT 0,
    total_paid       DOUBLE PRECISION DEFAULT 0,
    total_waived     DOUBLE PRECISION DEFAULT 0,
    balance          DOUBLE PRECISION DEFAULT 0,
    fee_status       VARCHAR(20) DEFAULT 'clear',
    created_at       VARCHAR(30),
    updated_at       VARCHAR(30),
    UNIQUE(institution_id, student_user_id)
);
CREATE INDEX IF NOT EXISTS idx_ffa_inst ON fin_fee_accounts(institution_id);
CREATE INDEX IF NOT EXISTS idx_ffa_student ON fin_fee_accounts(student_user_id);

-- ── 8.1 Payments + Receipts ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS fin_payments (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    fee_account_id   BIGINT NOT NULL,
    student_user_id  BIGINT NOT NULL,
    amount           DOUBLE PRECISION NOT NULL,
    payment_method   VARCHAR(30) NOT NULL DEFAULT 'online',
    payment_ref      VARCHAR(255),
    receipt_number   VARCHAR(50),
    component_type   VARCHAR(50),
    installment_id   BIGINT,
    gst_amount       DOUBLE PRECISION DEFAULT 0,
    net_amount       DOUBLE PRECISION DEFAULT 0,
    notes            TEXT,
    recorded_by      BIGINT NOT NULL,
    status           VARCHAR(20) DEFAULT 'completed',
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_fp_account ON fin_payments(fee_account_id);
CREATE INDEX IF NOT EXISTS idx_fp_student ON fin_payments(student_user_id);
CREATE INDEX IF NOT EXISTS idx_fp_inst ON fin_payments(institution_id);
CREATE INDEX IF NOT EXISTS idx_fp_receipt ON fin_payments(receipt_number);

-- ── 8.2 Installment Plans ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS fin_installment_plans (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    fee_account_id   BIGINT NOT NULL,
    total_installments INTEGER NOT NULL,
    plan_type        VARCHAR(30) DEFAULT 'equal',
    late_fee_amount  DOUBLE PRECISION DEFAULT 0,
    grace_days       INTEGER DEFAULT 7,
    status           VARCHAR(20) DEFAULT 'active',
    created_by       BIGINT,
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_fip_account ON fin_installment_plans(fee_account_id);

CREATE TABLE IF NOT EXISTS fin_installments (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    plan_id          BIGINT NOT NULL,
    fee_account_id   BIGINT NOT NULL,
    installment_no   INTEGER NOT NULL,
    amount_due       DOUBLE PRECISION NOT NULL,
    amount_paid      DOUBLE PRECISION DEFAULT 0,
    late_fee_applied DOUBLE PRECISION DEFAULT 0,
    due_date         VARCHAR(20) NOT NULL,
    paid_date        VARCHAR(30),
    status           VARCHAR(20) DEFAULT 'pending'
);
CREATE INDEX IF NOT EXISTS idx_fi_plan ON fin_installments(plan_id);
CREATE INDEX IF NOT EXISTS idx_fi_account ON fin_installments(fee_account_id);
CREATE INDEX IF NOT EXISTS idx_fi_due ON fin_installments(due_date, status);

-- ── 8.3 Scholarships ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS fin_scholarships (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    scholarship_name VARCHAR(200) NOT NULL,
    scholarship_type VARCHAR(30) NOT NULL DEFAULT 'merit',
    discount_type    VARCHAR(20) NOT NULL DEFAULT 'percentage',
    discount_value   DOUBLE PRECISION NOT NULL,
    applicable_component VARCHAR(50),
    budget_amount    DOUBLE PRECISION DEFAULT 0,
    budget_used      DOUBLE PRECISION DEFAULT 0,
    status           VARCHAR(20) DEFAULT 'active',
    created_by       BIGINT,
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_fs_inst ON fin_scholarships(institution_id);

CREATE TABLE IF NOT EXISTS fin_scholarship_applications (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    scholarship_id   BIGINT NOT NULL,
    fee_account_id   BIGINT NOT NULL,
    student_user_id  BIGINT NOT NULL,
    requested_by     BIGINT NOT NULL,
    discount_amount  DOUBLE PRECISION NOT NULL,
    approval_status  VARCHAR(20) DEFAULT 'requested',
    approved_by      BIGINT,
    approved_at      VARCHAR(30),
    rejected_reason  TEXT,
    credit_note_id   BIGINT,
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_fsa_account ON fin_scholarship_applications(fee_account_id);
CREATE INDEX IF NOT EXISTS idx_fsa_inst ON fin_scholarship_applications(institution_id);

-- ── 8.4 Refunds ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS fin_refunds (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    fee_account_id   BIGINT NOT NULL,
    student_user_id  BIGINT NOT NULL,
    original_payment_id BIGINT NOT NULL,
    admission_id     BIGINT,
    refund_reason    VARCHAR(50) NOT NULL DEFAULT 'withdrawal',
    refund_policy    VARCHAR(30) DEFAULT 'full',
    requested_amount DOUBLE PRECISION NOT NULL,
    approved_amount  DOUBLE PRECISION,
    refund_status    VARCHAR(20) DEFAULT 'requested',
    auto_approved    BOOLEAN DEFAULT FALSE,
    approved_by      BIGINT,
    approved_at      VARCHAR(30),
    processed_at     VARCHAR(30),
    commission_reversed BOOLEAN DEFAULT FALSE,
    reversal_note    TEXT,
    requested_by     BIGINT NOT NULL,
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_fr_account ON fin_refunds(fee_account_id);
CREATE INDEX IF NOT EXISTS idx_fr_inst ON fin_refunds(institution_id);
CREATE INDEX IF NOT EXISTS idx_fr_payment ON fin_refunds(original_payment_id);

-- ── 8.5 GST Configuration ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS fin_gst_config (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    gstin            VARCHAR(20),
    default_gst_rate DOUBLE PRECISION DEFAULT 18.0,
    effective_from   VARCHAR(20) NOT NULL,
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_fgc_inst ON fin_gst_config(institution_id);

-- ── 8.5 GST Invoices ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS fin_gst_invoices (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    payment_id       BIGINT NOT NULL,
    invoice_number   VARCHAR(50) NOT NULL,
    invoice_date     VARCHAR(30) NOT NULL,
    taxable_amount   DOUBLE PRECISION NOT NULL,
    cgst_amount      DOUBLE PRECISION DEFAULT 0,
    sgst_amount      DOUBLE PRECISION DEFAULT 0,
    igst_amount      DOUBLE PRECISION DEFAULT 0,
    total_tax        DOUBLE PRECISION DEFAULT 0,
    total_amount     DOUBLE PRECISION NOT NULL,
    hsn_sac_code     VARCHAR(20),
    gst_rate_pct     DOUBLE PRECISION DEFAULT 18.0,
    buyer_gstin      VARCHAR(20),
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_fgi_inst ON fin_gst_invoices(institution_id);
CREATE INDEX IF NOT EXISTS idx_fgi_payment ON fin_gst_invoices(payment_id);
CREATE INDEX IF NOT EXISTS idx_fgi_invoice ON fin_gst_invoices(invoice_number);

-- ── 8.6 Accounting Sync Queue ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS fin_accounting_sync (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    transaction_type VARCHAR(30) NOT NULL,
    transaction_id   BIGINT NOT NULL,
    sync_status      VARCHAR(20) DEFAULT 'pending',
    external_ref     VARCHAR(255),
    failure_reason   TEXT,
    retry_count      INTEGER DEFAULT 0,
    last_attempt_at  VARCHAR(30),
    resolved_at      VARCHAR(30),
    resolved_by      BIGINT,
    resolution_note  TEXT,
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_fas_inst ON fin_accounting_sync(institution_id);
CREATE INDEX IF NOT EXISTS idx_fas_status ON fin_accounting_sync(sync_status);

-- ── Auditable Finance Ledger (BR-9.3.1: single source of truth) ────────────
CREATE TABLE IF NOT EXISTS fin_ledger (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    entry_type       VARCHAR(30) NOT NULL,
    reference_type   VARCHAR(30) NOT NULL,
    reference_id     BIGINT NOT NULL,
    debit            DOUBLE PRECISION DEFAULT 0,
    credit           DOUBLE PRECISION DEFAULT 0,
    balance_after    DOUBLE PRECISION DEFAULT 0,
    description      TEXT,
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_fl_inst ON fin_ledger(institution_id);
CREATE INDEX IF NOT EXISTS idx_fl_ref ON fin_ledger(reference_type, reference_id);
CREATE INDEX IF NOT EXISTS idx_fl_type ON fin_ledger(entry_type);

-- Sync sequences
SELECT setval(pg_get_serial_sequence('fin_fee_structures', 'id'),
              COALESCE((SELECT MAX(id) FROM fin_fee_structures), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('fin_fee_components', 'id'),
              COALESCE((SELECT MAX(id) FROM fin_fee_components), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('fin_fee_accounts', 'id'),
              COALESCE((SELECT MAX(id) FROM fin_fee_accounts), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('fin_payments', 'id'),
              COALESCE((SELECT MAX(id) FROM fin_payments), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('fin_installment_plans', 'id'),
              COALESCE((SELECT MAX(id) FROM fin_installment_plans), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('fin_installments', 'id'),
              COALESCE((SELECT MAX(id) FROM fin_installments), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('fin_scholarships', 'id'),
              COALESCE((SELECT MAX(id) FROM fin_scholarships), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('fin_scholarship_applications', 'id'),
              COALESCE((SELECT MAX(id) FROM fin_scholarship_applications), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('fin_refunds', 'id'),
              COALESCE((SELECT MAX(id) FROM fin_refunds), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('fin_gst_config', 'id'),
              COALESCE((SELECT MAX(id) FROM fin_gst_config), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('fin_gst_invoices', 'id'),
              COALESCE((SELECT MAX(id) FROM fin_gst_invoices), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('fin_accounting_sync', 'id'),
              COALESCE((SELECT MAX(id) FROM fin_accounting_sync), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('fin_ledger', 'id'),
              COALESCE((SELECT MAX(id) FROM fin_ledger), 0) + 1, false);
