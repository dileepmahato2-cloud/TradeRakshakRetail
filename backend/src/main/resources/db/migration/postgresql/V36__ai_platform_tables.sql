-- Relocated from trep-institution-service (V9__ai_platform_tables.sql) on 2026-08-19.
-- Migration ownership rule: trep-core owns migrations for CRM, LMS, Finance,
-- Trading, AI and Platform tables; trep-institution-service owns ERP tables only.
-- Content is unchanged and fully idempotent (every CREATE/ALTER is guarded,
-- no INSERTs), so re-running it against an existing database is a no-op.

-- V9: AI Platform tables (BRD Vol 3, Section 4)
-- All AI modules route through AiService.invoke().
-- BR-4.5.1 / BR-4.6.1: mandatory Faculty approval before student-facing use.
-- BR-4.7.1 / BR-2.8.2: no forward-looking instrument-specific recommendation.

-- ── AI Conversations (Chatbot 4.2, Teacher 4.3, Student Assistant 4.4) ───────
CREATE TABLE IF NOT EXISTS ai_conversations (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    user_id          BIGINT NOT NULL,
    module           VARCHAR(30) NOT NULL CHECK (module IN ('chatbot','teacher','student_assistant','mentor','lead_scoring')),
    context_type     VARCHAR(30),
    context_id       BIGINT,
    status           VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active','escalated','closed')),
    escalated_to     VARCHAR(30),
    exchange_count   INTEGER NOT NULL DEFAULT 0,
    max_exchanges    INTEGER NOT NULL DEFAULT 5,
    created_at       VARCHAR(30) NOT NULL,
    updated_at       VARCHAR(30) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_aic_inst ON ai_conversations(institution_id);
CREATE INDEX IF NOT EXISTS idx_aic_user ON ai_conversations(user_id, module);

-- ── AI Messages ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ai_messages (
    id               BIGSERIAL PRIMARY KEY,
    conversation_id  BIGINT NOT NULL REFERENCES ai_conversations(id),
    role             VARCHAR(10) NOT NULL CHECK (role IN ('user','assistant','system')),
    content          TEXT NOT NULL,
    model_used       VARCHAR(60),
    is_stub          BOOLEAN NOT NULL DEFAULT FALSE,
    flagged          BOOLEAN NOT NULL DEFAULT FALSE,
    flagged_by       BIGINT,
    flagged_reason   TEXT,
    created_at       VARCHAR(30) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_aim_conv ON ai_messages(conversation_id, created_at);

-- ── AI Question Generator Drafts (BR-4.5.1: mandatory Faculty approval) ─────
CREATE TABLE IF NOT EXISTS ai_question_drafts (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    course_id        BIGINT,
    topic            VARCHAR(200),
    difficulty       VARCHAR(20) DEFAULT 'medium' CHECK (difficulty IN ('easy','medium','hard')),
    question_type    VARCHAR(20) NOT NULL CHECK (question_type IN ('mcq','subjective','true_false')),
    question_text    TEXT NOT NULL,
    options_json     TEXT,
    correct_answer   TEXT,
    explanation      TEXT,
    ai_warnings      TEXT,
    approval_status  VARCHAR(20) NOT NULL DEFAULT 'pending' CHECK (approval_status IN ('pending','approved','rejected','edited')),
    reviewed_by      BIGINT,
    reviewed_at      VARCHAR(30),
    batch_ref        VARCHAR(60),
    requested_by     BIGINT NOT NULL,
    created_at       VARCHAR(30) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_aiqd_inst ON ai_question_drafts(institution_id, approval_status);
CREATE INDEX IF NOT EXISTS idx_aiqd_batch ON ai_question_drafts(batch_ref);

-- ── AI Assignment Generator Drafts (BR-4.6.1: mandatory Faculty approval) ───
CREATE TABLE IF NOT EXISTS ai_assignment_drafts (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    course_id        BIGINT,
    topic            VARCHAR(200),
    learning_objective TEXT,
    assignment_brief TEXT NOT NULL,
    rubric_json      TEXT,
    expected_deliverable TEXT,
    approval_status  VARCHAR(20) NOT NULL DEFAULT 'pending' CHECK (approval_status IN ('pending','approved','rejected','edited')),
    reviewed_by      BIGINT,
    reviewed_at      VARCHAR(30),
    requested_by     BIGINT NOT NULL,
    created_at       VARCHAR(30) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_aiad_inst ON ai_assignment_drafts(institution_id, approval_status);

-- ── AI Trading Mentor Insights (BR-4.7.1: strictly historical reflection) ────
CREATE TABLE IF NOT EXISTS ai_mentor_insights (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    user_id          BIGINT NOT NULL,
    insight_type     VARCHAR(30) NOT NULL,
    pattern_summary  TEXT NOT NULL,
    supporting_data  TEXT,
    trade_count      INTEGER,
    period_from      VARCHAR(30),
    period_to        VARCHAR(30),
    include_journal  BOOLEAN NOT NULL DEFAULT FALSE,
    dismissed        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       VARCHAR(30) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ami_user ON ai_mentor_insights(user_id, created_at);

-- ── AI Lead Scoring (BR-4.8.1: sorting aid, never auto-reassigns) ───────────
CREATE TABLE IF NOT EXISTS ai_lead_scores (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    lead_id          BIGINT NOT NULL,
    ai_score         NUMERIC(5,2) NOT NULL,
    confidence       NUMERIC(5,2),
    scoring_factors  TEXT,
    model_version    VARCHAR(60),
    created_at       VARCHAR(30) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ails_inst ON ai_lead_scores(institution_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ails_lead ON ai_lead_scores(lead_id);

-- ── AI Analytics Alerts (BR-4.9.1: confidence range required) ────────────────
CREATE TABLE IF NOT EXISTS ai_analytics_alerts (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    alert_type       VARCHAR(30) NOT NULL CHECK (alert_type IN ('anomaly','forecast','trend')),
    metric_name      VARCHAR(60) NOT NULL,
    current_value    NUMERIC(18,4),
    expected_value   NUMERIC(18,4),
    deviation_pct    NUMERIC(8,2),
    confidence_low   NUMERIC(18,4),
    confidence_high  NUMERIC(18,4),
    historical_window VARCHAR(30),
    message          TEXT NOT NULL,
    severity         VARCHAR(10) NOT NULL DEFAULT 'info' CHECK (severity IN ('info','warning','critical')),
    acknowledged     BOOLEAN NOT NULL DEFAULT FALSE,
    acknowledged_by  BIGINT,
    created_at       VARCHAR(30) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_aiaa_inst ON ai_analytics_alerts(institution_id, created_at);

-- ── AI Audit Log (BRD 7.2: every AI output logged for audit) ─────────────────
CREATE TABLE IF NOT EXISTS ai_audit_log (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    user_id          BIGINT NOT NULL,
    module           VARCHAR(30) NOT NULL,
    action           VARCHAR(30) NOT NULL,
    prompt_summary   TEXT,
    response_summary TEXT,
    model_used       VARCHAR(60),
    is_stub          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       VARCHAR(30) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_aial_inst ON ai_audit_log(institution_id, created_at);
CREATE INDEX IF NOT EXISTS idx_aial_module ON ai_audit_log(module, created_at);

-- Sync sequences
SELECT setval(pg_get_serial_sequence('ai_conversations', 'id'),
              COALESCE((SELECT MAX(id) FROM ai_conversations), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ai_messages', 'id'),
              COALESCE((SELECT MAX(id) FROM ai_messages), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ai_question_drafts', 'id'),
              COALESCE((SELECT MAX(id) FROM ai_question_drafts), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ai_assignment_drafts', 'id'),
              COALESCE((SELECT MAX(id) FROM ai_assignment_drafts), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ai_mentor_insights', 'id'),
              COALESCE((SELECT MAX(id) FROM ai_mentor_insights), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ai_lead_scores', 'id'),
              COALESCE((SELECT MAX(id) FROM ai_lead_scores), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ai_analytics_alerts', 'id'),
              COALESCE((SELECT MAX(id) FROM ai_analytics_alerts), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('ai_audit_log', 'id'),
              COALESCE((SELECT MAX(id) FROM ai_audit_log), 0) + 1, false);
