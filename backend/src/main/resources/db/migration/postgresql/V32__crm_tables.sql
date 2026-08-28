-- Relocated from trep-institution-service (V3__crm_tables.sql) on 2026-08-19.
-- Migration ownership rule: trep-core owns migrations for CRM, LMS, Finance,
-- Trading, AI and Platform tables; trep-institution-service owns ERP tables only.
-- Content is unchanged and fully idempotent (every CREATE/ALTER is guarded,
-- no INSERTs), so re-running it against an existing database is a no-op.

-- V3: CRM module tables (BRD Volume 2, Section 2)
-- Marketing, Sales, Lead Management, Campaign, Call Tracking,
-- Follow-up, Admission Pipeline, Commission Management

-- 2.1 Marketing Channels
CREATE TABLE IF NOT EXISTS crm_channels (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    channel_name     VARCHAR(100) NOT NULL,
    channel_type     VARCHAR(50) NOT NULL DEFAULT 'other',
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30),
    updated_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_crm_channels_iid ON crm_channels(institution_id);

-- 2.1 Marketing Spend
CREATE TABLE IF NOT EXISTS crm_channel_spend (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    channel_id       BIGINT NOT NULL,
    campaign_id      BIGINT,
    period_start     VARCHAR(20),
    period_end       VARCHAR(20),
    amount           DOUBLE PRECISION DEFAULT 0,
    notes            TEXT,
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_crm_spend_iid ON crm_channel_spend(institution_id);

-- Ensure inst_enquiries exists (created by core's SymbolSchemaGuard; may not
-- exist yet if institution service starts before core).
CREATE TABLE IF NOT EXISTS inst_enquiries (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    enquirer_name    VARCHAR(255) NOT NULL,
    email            VARCHAR(255),
    phone            VARCHAR(20),
    course_interest  VARCHAR(255),
    source           VARCHAR(100),
    status           VARCHAR(20) DEFAULT 'new',
    follow_up_date   VARCHAR(20),
    assigned_to      BIGINT,
    notes            TEXT,
    created_at       VARCHAR(30),
    updated_at       VARCHAR(30),
    created_by       BIGINT,
    updated_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_enquiries_iid ON inst_enquiries(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_enquiries_status ON inst_enquiries(status);

-- 2.3 Lead Management (extends inst_enquiries into a proper CRM lead)
ALTER TABLE inst_enquiries ADD COLUMN IF NOT EXISTS lead_score       INTEGER DEFAULT 0;
ALTER TABLE inst_enquiries ADD COLUMN IF NOT EXISTS pipeline_stage   VARCHAR(50) DEFAULT 'new';
ALTER TABLE inst_enquiries ADD COLUMN IF NOT EXISTS expected_value   DOUBLE PRECISION DEFAULT 0;
ALTER TABLE inst_enquiries ADD COLUMN IF NOT EXISTS probability      INTEGER DEFAULT 0;
ALTER TABLE inst_enquiries ADD COLUMN IF NOT EXISTS channel_id       BIGINT;
ALTER TABLE inst_enquiries ADD COLUMN IF NOT EXISTS campaign_id      BIGINT;
ALTER TABLE inst_enquiries ADD COLUMN IF NOT EXISTS merged_into_id   BIGINT;
ALTER TABLE inst_enquiries ADD COLUMN IF NOT EXISTS converted_at     VARCHAR(30);
ALTER TABLE inst_enquiries ADD COLUMN IF NOT EXISTS converted_by     BIGINT;
ALTER TABLE inst_enquiries ADD COLUMN IF NOT EXISTS lost_reason      VARCHAR(255);
ALTER TABLE inst_enquiries ADD COLUMN IF NOT EXISTS utm_source       VARCHAR(255);
ALTER TABLE inst_enquiries ADD COLUMN IF NOT EXISTS utm_medium       VARCHAR(255);
ALTER TABLE inst_enquiries ADD COLUMN IF NOT EXISTS utm_campaign     VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_inst_enquiries_stage ON inst_enquiries(pipeline_stage);
CREATE INDEX IF NOT EXISTS idx_inst_enquiries_phone ON inst_enquiries(phone);
CREATE INDEX IF NOT EXISTS idx_inst_enquiries_email ON inst_enquiries(email);
CREATE INDEX IF NOT EXISTS idx_inst_enquiries_channel ON inst_enquiries(channel_id);
CREATE INDEX IF NOT EXISTS idx_inst_enquiries_campaign ON inst_enquiries(campaign_id);

-- Ensure inst_students exists for admission conversion
CREATE TABLE IF NOT EXISTS inst_students (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    student_name     VARCHAR(255),
    email            VARCHAR(255),
    phone            VARCHAR(20),
    enrollment_no    VARCHAR(100),
    admission_date   VARCHAR(30),
    status           VARCHAR(20) DEFAULT 'active',
    course_id        BIGINT,
    batch_id         BIGINT,
    fee_total        DOUBLE PRECISION DEFAULT 0,
    created_at       VARCHAR(30),
    updated_at       VARCHAR(30),
    created_by       BIGINT,
    updated_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_students_iid ON inst_students(institution_id);

-- Ensure inst_courses exists for commission fee lookup
CREATE TABLE IF NOT EXISTS inst_courses (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    course_name      VARCHAR(300),
    course_code      VARCHAR(50),
    category         VARCHAR(100),
    fee_amount       DOUBLE PRECISION DEFAULT 0,
    duration         VARCHAR(100),
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30),
    updated_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_courses_iid ON inst_courses(institution_id);

-- 2.2 Sales pipeline stage configuration
CREATE TABLE IF NOT EXISTS crm_pipeline_stages (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    stage_key        VARCHAR(50) NOT NULL,
    stage_label      VARCHAR(100) NOT NULL,
    sort_order       INTEGER DEFAULT 0,
    probability_pct  INTEGER DEFAULT 0,
    is_terminal      BOOLEAN DEFAULT FALSE,
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_crm_stages_iid ON crm_pipeline_stages(institution_id);

-- 2.2 Sales counsellor assignment configuration
CREATE TABLE IF NOT EXISTS crm_assignment_config (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    assignment_mode  VARCHAR(30) DEFAULT 'manual',
    max_active_leads INTEGER DEFAULT 50,
    created_at       VARCHAR(30),
    updated_at       VARCHAR(30)
);

-- 2.3 Lead Activity Timeline
CREATE TABLE IF NOT EXISTS crm_lead_activity (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    lead_id          BIGINT NOT NULL,
    activity_type    VARCHAR(50) NOT NULL,
    description      TEXT,
    old_value        TEXT,
    new_value        TEXT,
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_crm_activity_lead ON crm_lead_activity(lead_id);
CREATE INDEX IF NOT EXISTS idx_crm_activity_iid ON crm_lead_activity(institution_id);

-- 2.4 Campaign Management
CREATE TABLE IF NOT EXISTS crm_campaigns (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    campaign_name    VARCHAR(255) NOT NULL,
    channel_id       BIGINT,
    start_date       VARCHAR(20),
    end_date         VARCHAR(20),
    budget           DOUBLE PRECISION DEFAULT 0,
    goal_leads       INTEGER DEFAULT 0,
    goal_admissions  INTEGER DEFAULT 0,
    audience_filter  TEXT,
    status           VARCHAR(30) DEFAULT 'draft',
    created_at       VARCHAR(30),
    updated_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_crm_campaigns_iid ON crm_campaigns(institution_id);

-- 2.5 Call Tracking
CREATE TABLE IF NOT EXISTS crm_call_log (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    lead_id          BIGINT NOT NULL,
    counsellor_id    BIGINT NOT NULL,
    call_outcome     VARCHAR(50) NOT NULL,
    duration_seconds INTEGER DEFAULT 0,
    notes            TEXT,
    recording_ref    VARCHAR(500),
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_crm_calls_lead ON crm_call_log(lead_id);
CREATE INDEX IF NOT EXISTS idx_crm_calls_counsellor ON crm_call_log(counsellor_id);
CREATE INDEX IF NOT EXISTS idx_crm_calls_iid ON crm_call_log(institution_id);

-- 2.6 Follow-up
CREATE TABLE IF NOT EXISTS crm_follow_ups (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    lead_id          BIGINT NOT NULL,
    counsellor_id    BIGINT NOT NULL,
    follow_up_type   VARCHAR(30) NOT NULL,
    due_at           VARCHAR(30) NOT NULL,
    status           VARCHAR(30) DEFAULT 'pending',
    completed_at     VARCHAR(30),
    notes            TEXT,
    escalated        BOOLEAN DEFAULT FALSE,
    escalated_at     VARCHAR(30),
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_crm_followups_lead ON crm_follow_ups(lead_id);
CREATE INDEX IF NOT EXISTS idx_crm_followups_counsellor ON crm_follow_ups(counsellor_id, status);
CREATE INDEX IF NOT EXISTS idx_crm_followups_iid ON crm_follow_ups(institution_id);

-- 2.7 Admission Pipeline
CREATE TABLE IF NOT EXISTS crm_admission_checklist_config (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    item_key         VARCHAR(100) NOT NULL,
    item_label       VARCHAR(255) NOT NULL,
    mandatory        BOOLEAN DEFAULT TRUE,
    sort_order       INTEGER DEFAULT 0,
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_crm_checklist_cfg_iid ON crm_admission_checklist_config(institution_id);

CREATE TABLE IF NOT EXISTS crm_admission_checklist (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    lead_id          BIGINT NOT NULL,
    checklist_item_id BIGINT NOT NULL,
    completed        BOOLEAN DEFAULT FALSE,
    completed_at     VARCHAR(30),
    completed_by     BIGINT,
    notes            TEXT
);
CREATE INDEX IF NOT EXISTS idx_crm_checklist_lead ON crm_admission_checklist(lead_id);

CREATE TABLE IF NOT EXISTS crm_admissions (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    lead_id          BIGINT NOT NULL,
    student_id       BIGINT,
    batch_id         BIGINT,
    course_id        BIGINT,
    converting_counsellor_id BIGINT,
    conversion_date  VARCHAR(30),
    portal_provisioned BOOLEAN DEFAULT FALSE,
    status           VARCHAR(30) DEFAULT 'active',
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_crm_admissions_lead ON crm_admissions(lead_id);
CREATE INDEX IF NOT EXISTS idx_crm_admissions_iid ON crm_admissions(institution_id);

-- 2.8 Commission Management
CREATE TABLE IF NOT EXISTS crm_commission_structures (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    structure_name   VARCHAR(200) NOT NULL,
    structure_type   VARCHAR(30) NOT NULL DEFAULT 'flat',
    flat_amount      DOUBLE PRECISION DEFAULT 0,
    percentage       DOUBLE PRECISION DEFAULT 0,
    tier_config      TEXT,
    trigger_event    VARCHAR(50) DEFAULT 'on_admission',
    assignee_type    VARCHAR(50),
    assignee_id      BIGINT,
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30),
    updated_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_crm_comm_struct_iid ON crm_commission_structures(institution_id);

CREATE TABLE IF NOT EXISTS crm_commission_entries (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    admission_id     BIGINT NOT NULL,
    structure_id     BIGINT NOT NULL,
    counsellor_id    BIGINT NOT NULL,
    commission_amount DOUBLE PRECISION DEFAULT 0,
    fee_amount       DOUBLE PRECISION DEFAULT 0,
    payout_status    VARCHAR(30) DEFAULT 'accrued',
    approved_by      BIGINT,
    approved_at      VARCHAR(30),
    paid_at          VARCHAR(30),
    reversed         BOOLEAN DEFAULT FALSE,
    reversed_at      VARCHAR(30),
    reversal_reason  VARCHAR(255),
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_crm_comm_entries_iid ON crm_commission_entries(institution_id);
CREATE INDEX IF NOT EXISTS idx_crm_comm_entries_counsellor ON crm_commission_entries(counsellor_id);
CREATE INDEX IF NOT EXISTS idx_crm_comm_entries_admission ON crm_commission_entries(admission_id);

-- Sync sequences for new tables
SELECT setval(pg_get_serial_sequence('crm_channels', 'id'),
              COALESCE((SELECT MAX(id) FROM crm_channels), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('crm_campaigns', 'id'),
              COALESCE((SELECT MAX(id) FROM crm_campaigns), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('crm_call_log', 'id'),
              COALESCE((SELECT MAX(id) FROM crm_call_log), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('crm_follow_ups', 'id'),
              COALESCE((SELECT MAX(id) FROM crm_follow_ups), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('crm_admissions', 'id'),
              COALESCE((SELECT MAX(id) FROM crm_admissions), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('crm_commission_entries', 'id'),
              COALESCE((SELECT MAX(id) FROM crm_commission_entries), 0) + 1, false);
