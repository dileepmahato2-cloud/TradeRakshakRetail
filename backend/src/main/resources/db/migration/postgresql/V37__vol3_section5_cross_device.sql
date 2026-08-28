-- Relocated from trep-institution-service (V10__vol3_section5_cross_device.sql) on 2026-08-19.
-- Migration ownership rule: trep-core owns migrations for CRM, LMS, Finance,
-- Trading, AI and Platform tables; trep-institution-service owns ERP tables only.
-- Content is unchanged and fully idempotent (every CREATE/ALTER is guarded,
-- no INSERTs), so re-running it against an existing database is a no-op.

-- ═══════════════════════════════════════════════════════════════════════════
-- BRD Volume 3, Section 5 — Mobile Apps, Tablet & Desktop
--
-- 5.2  Cross-platform state sync (drafts) across a user's devices
-- US-5.3.1  Assignment draft auto-saves, retrievable from any device
-- BR-5.4.1  In-progress timed Exam attempts are EXCLUDED from cross-device
--           sync/resume — locked to the device + app/browser session that
--           started them, per exam-integrity proctoring requirements.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── BR-5.4.1: device/session binding for exam attempts ─────────────────────
ALTER TABLE lms_exam_attempts ADD COLUMN IF NOT EXISTS device_id           VARCHAR(128);
ALTER TABLE lms_exam_attempts ADD COLUMN IF NOT EXISTS session_fingerprint VARCHAR(128);
ALTER TABLE lms_exam_attempts ADD COLUMN IF NOT EXISTS device_label        VARCHAR(200);
ALTER TABLE lms_exam_attempts ADD COLUMN IF NOT EXISTS device_locked_at    VARCHAR(30);

CREATE INDEX IF NOT EXISTS idx_exam_att_device
    ON lms_exam_attempts(id, session_fingerprint);

-- ── US-5.3.1: device-agnostic assignment draft store ───────────────────────
-- Deliberately NOT stored in assignment_submissions: a draft must never be
-- counted as a submission by the at-risk, reporting, or pending-grade queries
-- that read that table.
CREATE TABLE IF NOT EXISTS lms_assignment_drafts (
    id              BIGSERIAL PRIMARY KEY,
    assignment_id   BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    institution_id  BIGINT NOT NULL,
    draft_text      TEXT,
    draft_url       TEXT,
    revision        BIGINT NOT NULL DEFAULT 1,
    last_device_id  VARCHAR(128),
    created_at      VARCHAR(30),
    updated_at      VARCHAR(30),
    UNIQUE(assignment_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_asg_draft_user_updated
    ON lms_assignment_drafts(user_id, updated_at);
