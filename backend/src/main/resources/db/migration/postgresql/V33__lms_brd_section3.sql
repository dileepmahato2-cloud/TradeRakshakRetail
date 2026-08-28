-- Relocated from trep-institution-service (V4__lms_brd_section3.sql) on 2026-08-19.
-- Migration ownership rule: trep-core owns migrations for CRM, LMS, Finance,
-- Trading, AI and Platform tables; trep-institution-service owns ERP tables only.
-- Content is unchanged and fully idempotent (every CREATE/ALTER is guarded,
-- no INSERTs), so re-running it against an existing database is a no-op.

-- V4: LMS BRD Section 3 enhancements
-- Course versioning, batch waitlist, exams, timetable conflicts,
-- forum moderation, AI doubt solver, study material versioning,
-- certificate verification

-- ── 3.1 Course Versioning ───────────────────────────────────────────────────
ALTER TABLE courses ADD COLUMN IF NOT EXISTS version         INTEGER DEFAULT 1;
ALTER TABLE courses ADD COLUMN IF NOT EXISTS parent_course_id BIGINT;
ALTER TABLE courses ADD COLUMN IF NOT EXISTS is_latest_version BOOLEAN DEFAULT TRUE;

-- ── 3.2 Batch Waitlist ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS lms_batch_waitlist (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    batch_id         BIGINT NOT NULL,
    user_id          BIGINT NOT NULL,
    course_id        BIGINT NOT NULL,
    waitlisted_at    VARCHAR(30) NOT NULL,
    status           VARCHAR(20) DEFAULT 'waiting',
    offered_at       VARCHAR(30),
    offer_expires_at VARCHAR(30),
    promoted_at      VARCHAR(30),
    cancelled_at     VARCHAR(30),
    position         INTEGER DEFAULT 0,
    UNIQUE(batch_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_waitlist_batch ON lms_batch_waitlist(batch_id, status);
CREATE INDEX IF NOT EXISTS idx_waitlist_user  ON lms_batch_waitlist(user_id);

-- ── 3.4 Study Material Versioning ───────────────────────────────────────────
ALTER TABLE resource_library ADD COLUMN IF NOT EXISTS version       INTEGER DEFAULT 1;
ALTER TABLE resource_library ADD COLUMN IF NOT EXISTS parent_id     BIGINT;
ALTER TABLE resource_library ADD COLUMN IF NOT EXISTS downloadable  BOOLEAN DEFAULT TRUE;
ALTER TABLE resource_library ADD COLUMN IF NOT EXISTS topic_id      BIGINT;
ALTER TABLE resource_library ADD COLUMN IF NOT EXISTS batch_id      BIGINT;

-- ── 3.5 Assignment Late Policy Enforcement ──────────────────────────────────
ALTER TABLE assignment_submissions ADD COLUMN IF NOT EXISTS penalty_pct   INTEGER DEFAULT 0;
ALTER TABLE assignment_submissions ADD COLUMN IF NOT EXISTS final_marks   REAL;
ALTER TABLE assignment_submissions ADD COLUMN IF NOT EXISTS locked        BOOLEAN DEFAULT FALSE;
ALTER TABLE assignment_submissions ADD COLUMN IF NOT EXISTS reopen_reason TEXT;
ALTER TABLE assignment_submissions ADD COLUMN IF NOT EXISTS reopened_by   BIGINT;

-- ── 3.6 Exams (distinct from quizzes) ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS lms_exams (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    course_id        BIGINT,
    batch_id         BIGINT,
    created_by       BIGINT NOT NULL,
    title            VARCHAR(300) NOT NULL,
    description      TEXT,
    exam_type        VARCHAR(30) DEFAULT 'mixed',
    duration_minutes INTEGER DEFAULT 60,
    passing_marks    INTEGER DEFAULT 40,
    total_marks      INTEGER DEFAULT 100,
    proctoring_mode  VARCHAR(20) DEFAULT 'none',
    scheduled_start  VARCHAR(30),
    scheduled_end    VARCHAR(30),
    auto_save_interval_secs INTEGER DEFAULT 30,
    instructions     TEXT,
    results_published BOOLEAN DEFAULT FALSE,
    published_by     BIGINT,
    published_at     VARCHAR(30),
    status           VARCHAR(20) DEFAULT 'draft',
    created_at       VARCHAR(30),
    updated_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_lms_exams_inst ON lms_exams(institution_id);
CREATE INDEX IF NOT EXISTS idx_lms_exams_batch ON lms_exams(batch_id);

CREATE TABLE IF NOT EXISTS lms_exam_questions (
    id               BIGSERIAL PRIMARY KEY,
    exam_id          BIGINT NOT NULL,
    institution_id   BIGINT NOT NULL,
    question_text    TEXT NOT NULL,
    question_type    VARCHAR(30) DEFAULT 'mcq',
    options_json     TEXT,
    correct_answer   TEXT,
    explanation      TEXT,
    marks            INTEGER DEFAULT 1,
    is_objective     BOOLEAN DEFAULT TRUE,
    difficulty       VARCHAR(20) DEFAULT 'medium',
    display_order    INTEGER DEFAULT 0,
    image_url        VARCHAR(500),
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_exam_q_exam ON lms_exam_questions(exam_id);

CREATE TABLE IF NOT EXISTS lms_exam_attempts (
    id               BIGSERIAL PRIMARY KEY,
    exam_id          BIGINT NOT NULL,
    user_id          BIGINT NOT NULL,
    institution_id   BIGINT NOT NULL,
    started_at       VARCHAR(30),
    last_auto_save   VARCHAR(30),
    submitted_at     VARCHAR(30),
    auto_submitted   BOOLEAN DEFAULT FALSE,
    answers_json     TEXT,
    objective_score  REAL,
    subjective_score REAL,
    total_score      REAL,
    max_score        INTEGER,
    passed           BOOLEAN,
    graded_by        BIGINT,
    graded_at        VARCHAR(30),
    status           VARCHAR(30) DEFAULT 'in_progress',
    UNIQUE(exam_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_exam_att_user ON lms_exam_attempts(user_id);

-- 3.6 Proctoring event log (BR-3.6.1)
CREATE TABLE IF NOT EXISTS lms_proctoring_events (
    id               BIGSERIAL PRIMARY KEY,
    attempt_id       BIGINT NOT NULL,
    institution_id   BIGINT NOT NULL,
    event_type       VARCHAR(50) NOT NULL,
    event_detail     TEXT,
    flagged          BOOLEAN DEFAULT TRUE,
    reviewed_by      BIGINT,
    reviewed_at      VARCHAR(30),
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_proctor_attempt ON lms_proctoring_events(attempt_id);

-- ── 3.7 Certificate Verification ────────────────────────────────────────────
ALTER TABLE certificates ADD COLUMN IF NOT EXISTS verification_token VARCHAR(64);
ALTER TABLE certificates ADD COLUMN IF NOT EXISTS revoked_by        BIGINT;
ALTER TABLE certificates ADD COLUMN IF NOT EXISTS revoked_at        VARCHAR(30);
ALTER TABLE certificates ADD COLUMN IF NOT EXISTS min_attendance_met BOOLEAN DEFAULT TRUE;
ALTER TABLE certificates ADD COLUMN IF NOT EXISTS min_score_met     BOOLEAN DEFAULT TRUE;

-- ── 3.8 Forum Moderation & Replies ──────────────────────────────────────────
ALTER TABLE forum_threads ADD COLUMN IF NOT EXISTS category     VARCHAR(100);
ALTER TABLE forum_threads ADD COLUMN IF NOT EXISTS upvotes      INTEGER DEFAULT 0;
ALTER TABLE forum_threads ADD COLUMN IF NOT EXISTS flagged      BOOLEAN DEFAULT FALSE;
ALTER TABLE forum_threads ADD COLUMN IF NOT EXISTS flagged_by   BIGINT;
ALTER TABLE forum_threads ADD COLUMN IF NOT EXISTS flagged_at   VARCHAR(30);
ALTER TABLE forum_threads ADD COLUMN IF NOT EXISTS moderated_by BIGINT;
ALTER TABLE forum_threads ADD COLUMN IF NOT EXISTS moderated_at VARCHAR(30);

ALTER TABLE forum_replies ADD COLUMN IF NOT EXISTS status       VARCHAR(20) DEFAULT 'active';
ALTER TABLE forum_replies ADD COLUMN IF NOT EXISTS flagged      BOOLEAN DEFAULT FALSE;
ALTER TABLE forum_replies ADD COLUMN IF NOT EXISTS flagged_by   BIGINT;
ALTER TABLE forum_replies ADD COLUMN IF NOT EXISTS flagged_at   VARCHAR(30);
ALTER TABLE forum_replies ADD COLUMN IF NOT EXISTS moderated_by BIGINT;
ALTER TABLE forum_replies ADD COLUMN IF NOT EXISTS moderated_at VARCHAR(30);

-- ── 3.9 AI Doubt Solver ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS lms_ai_responses (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    thread_id        BIGINT NOT NULL,
    ai_response_text TEXT NOT NULL,
    ai_model         VARCHAR(100),
    ai_status        VARCHAR(30) DEFAULT 'draft',
    escalated        BOOLEAN DEFAULT FALSE,
    escalated_at     VARCHAR(30),
    confirmed_by     BIGINT,
    confirmed_at     VARCHAR(30),
    corrected_text   TEXT,
    corrected_by     BIGINT,
    corrected_at     VARCHAR(30),
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_ai_resp_thread ON lms_ai_responses(thread_id);

-- ── Timetable (Vol 1 Section 6.7) ──────────────────────────────────────────
ALTER TABLE batch_schedule ADD COLUMN IF NOT EXISTS room_id    BIGINT;
ALTER TABLE batch_schedule ADD COLUMN IF NOT EXISTS branch_id  BIGINT;
ALTER TABLE batch_schedule ADD COLUMN IF NOT EXISTS slot_date  VARCHAR(20);
ALTER TABLE batch_schedule ADD COLUMN IF NOT EXISTS is_recurring BOOLEAN DEFAULT TRUE;
ALTER TABLE batch_schedule ADD COLUMN IF NOT EXISTS status     VARCHAR(20) DEFAULT 'active';
ALTER TABLE batch_schedule ADD COLUMN IF NOT EXISTS created_at VARCHAR(30);

CREATE INDEX IF NOT EXISTS idx_schedule_room    ON batch_schedule(room_id, day_of_week);
CREATE INDEX IF NOT EXISTS idx_schedule_trainer ON batch_schedule(trainer_id, day_of_week);

-- Sync sequences
SELECT setval(pg_get_serial_sequence('lms_batch_waitlist', 'id'),
              COALESCE((SELECT MAX(id) FROM lms_batch_waitlist), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('lms_exams', 'id'),
              COALESCE((SELECT MAX(id) FROM lms_exams), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('lms_exam_questions', 'id'),
              COALESCE((SELECT MAX(id) FROM lms_exam_questions), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('lms_exam_attempts', 'id'),
              COALESCE((SELECT MAX(id) FROM lms_exam_attempts), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('lms_proctoring_events', 'id'),
              COALESCE((SELECT MAX(id) FROM lms_proctoring_events), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('lms_ai_responses', 'id'),
              COALESCE((SELECT MAX(id) FROM lms_ai_responses), 0) + 1, false);
