-- =============================================================================
-- TradeRakshakâ„¢ v11.4 â€” V5 Migration: Institution Teaching / LMS Module
-- Full teaching platform scoped to institutions
-- Covers: Courses â†’ Topics â†’ Lessons, Batches, Enrollments, Live Classes,
--         Quizzes, Assignments, Attendance, Progress, Leaderboard,
--         Certificates, Announcements, Discussion Forum
-- =============================================================================

-- â”€â”€ Extended institutions table â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
ALTER TABLE institutions ADD COLUMN inst_type         TEXT DEFAULT 'trading_academy';
ALTER TABLE institutions ADD COLUMN teaching_enabled  INTEGER DEFAULT 1;
ALTER TABLE institutions ADD COLUMN max_courses       INTEGER DEFAULT 50;
ALTER TABLE institutions ADD COLUMN max_students      INTEGER DEFAULT 500;
ALTER TABLE institutions ADD COLUMN allow_live_class  INTEGER DEFAULT 1;
ALTER TABLE institutions ADD COLUMN allow_cert_issue  INTEGER DEFAULT 1;
ALTER TABLE institutions ADD COLUMN city              TEXT;
ALTER TABLE institutions ADD COLUMN state             TEXT;
ALTER TABLE institutions ADD COLUMN pincode           TEXT;
ALTER TABLE institutions ADD COLUMN gst_number        TEXT;
ALTER TABLE institutions ADD COLUMN established_year  INTEGER;
ALTER TABLE institutions ADD COLUMN about             TEXT;
ALTER TABLE institutions ADD COLUMN cert_template_url TEXT;
ALTER TABLE institutions ADD COLUMN subscription_plan TEXT DEFAULT 'Institutional';
ALTER TABLE institutions ADD COLUMN plan_expiry       TEXT;

-- â”€â”€ Courses â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS courses (
    id                  BIGSERIAL PRIMARY KEY,
    institution_id      INTEGER NOT NULL,
    created_by          INTEGER NOT NULL,   -- trainer/institution_admin user_id
    title               TEXT NOT NULL,
    slug                TEXT UNIQUE,        -- URL-friendly e.g. options-trading-basics
    description         TEXT,
    short_description   TEXT,
    thumbnail_url       TEXT,
    category            TEXT,              -- equity_basics, options, futures, technical_analysis, fundamental
    sub_category        TEXT,
    language            TEXT DEFAULT 'English',
    level               TEXT DEFAULT 'Beginner',  -- Beginner/Intermediate/Advanced
    tags                TEXT,              -- comma-separated
    duration_hours      REAL DEFAULT 0,   -- total estimated hours
    num_topics          INTEGER DEFAULT 0,
    num_lessons         INTEGER DEFAULT 0,
    num_quizzes         INTEGER DEFAULT 0,
    num_assignments     INTEGER DEFAULT 0,
    course_type         TEXT DEFAULT 'self_paced',  -- self_paced / batch / live_only
    is_paid             INTEGER DEFAULT 0,
    price               REAL DEFAULT 0,
    required_plan       TEXT DEFAULT 'Starter',  -- minimum subscription plan to access
    prerequisites       TEXT,             -- comma-separated course slugs
    certificate_enabled INTEGER DEFAULT 1,
    min_pass_score      INTEGER DEFAULT 70, -- % for certificate
    status              TEXT DEFAULT 'draft', -- draft/published/archived
    total_enrolled      INTEGER DEFAULT 0,
    avg_rating          REAL DEFAULT 0,
    rating_count        INTEGER DEFAULT 0,
    published_at        TEXT,
    created_at          TEXT,
    updated_at          TEXT
);

-- â”€â”€ Course Topics (Chapters) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS course_topics (
    id              BIGSERIAL PRIMARY KEY,
    course_id       INTEGER NOT NULL,
    institution_id  INTEGER NOT NULL,
    title           TEXT NOT NULL,
    description     TEXT,
    display_order   INTEGER DEFAULT 0,
    is_free_preview INTEGER DEFAULT 0,
    status          TEXT DEFAULT 'active',
    created_at      TEXT
);

-- â”€â”€ Lessons â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS lessons (
    id              BIGSERIAL PRIMARY KEY,
    topic_id        INTEGER NOT NULL,
    course_id       INTEGER NOT NULL,
    institution_id  INTEGER NOT NULL,
    title           TEXT NOT NULL,
    description     TEXT,
    content_type    TEXT DEFAULT 'video',  -- video/article/pdf/slides/interactive
    content_url     TEXT,                  -- video URL or file path
    content_text    TEXT,                  -- for article type lessons
    duration_minutes INTEGER DEFAULT 0,
    is_free_preview INTEGER DEFAULT 0,
    display_order   INTEGER DEFAULT 0,
    resources_json  TEXT,                  -- JSON: [{name, url, type}]
    status          TEXT DEFAULT 'active',
    view_count      INTEGER DEFAULT 0,
    created_at      TEXT,
    updated_at      TEXT
);

-- â”€â”€ Batches (cohort-based learning) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS batches (
    id              BIGSERIAL PRIMARY KEY,
    institution_id  INTEGER NOT NULL,
    course_id       INTEGER NOT NULL,
    trainer_id      INTEGER NOT NULL,      -- user_id of trainer
    batch_code      TEXT UNIQUE,
    name            TEXT NOT NULL,
    description     TEXT,
    start_date      TEXT NOT NULL,
    end_date        TEXT NOT NULL,
    schedule_json   TEXT,                  -- JSON: [{day, time, duration_min}]
    mode            TEXT DEFAULT 'online', -- online/offline/hybrid
    venue           TEXT,
    max_seats       INTEGER DEFAULT 30,
    current_seats   INTEGER DEFAULT 0,
    timezone        TEXT DEFAULT 'Asia/Kolkata',
    status          TEXT DEFAULT 'upcoming', -- upcoming/ongoing/completed/cancelled
    fee             REAL DEFAULT 0,
    gst_pct         REAL DEFAULT 18,
    meeting_link    TEXT,                  -- Zoom/Google Meet/Teams link
    recording_url   TEXT,
    created_at      TEXT,
    updated_at      TEXT
);

-- â”€â”€ Enrollments â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS enrollments (
    id              BIGSERIAL PRIMARY KEY,
    institution_id  INTEGER NOT NULL,
    course_id       INTEGER NOT NULL,
    batch_id        INTEGER,               -- NULL = self-paced
    user_id         INTEGER NOT NULL,
    enrolled_by     INTEGER,               -- admin/trainer who enrolled, or user themselves
    enrollment_type TEXT DEFAULT 'self',   -- self/admin_enrolled/bulk/complimentary
    status          TEXT DEFAULT 'active', -- active/completed/withdrawn/expired
    progress_pct    INTEGER DEFAULT 0,
    completed_at    TEXT,
    certificate_issued INTEGER DEFAULT 0,
    certificate_url TEXT,
    fee_paid        REAL DEFAULT 0,
    payment_ref     TEXT,
    notes           TEXT,
    enrolled_at     TEXT,
    expires_at      TEXT,
    UNIQUE(course_id, user_id)
);

-- â”€â”€ Live Classes / Sessions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS live_classes (
    id              BIGSERIAL PRIMARY KEY,
    institution_id  INTEGER NOT NULL,
    batch_id        INTEGER,
    course_id       INTEGER,
    trainer_id      INTEGER NOT NULL,
    title           TEXT NOT NULL,
    description     TEXT,
    scheduled_at    TEXT NOT NULL,
    duration_minutes INTEGER DEFAULT 60,
    platform        TEXT DEFAULT 'zoom',   -- zoom/meet/teams/jitsi/custom
    meeting_id      TEXT,
    meeting_password TEXT,
    join_url        TEXT,
    host_url        TEXT,
    recording_url   TEXT,
    status          TEXT DEFAULT 'scheduled', -- scheduled/live/completed/cancelled
    actual_start    TEXT,
    actual_end      TEXT,
    attendance_count INTEGER DEFAULT 0,
    notes           TEXT,
    created_at      TEXT,
    updated_at      TEXT
);

-- â”€â”€ Live Class Attendance â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS live_attendance (
    id              BIGSERIAL PRIMARY KEY,
    live_class_id   INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER NOT NULL,
    join_time       TEXT,
    leave_time      TEXT,
    duration_minutes INTEGER DEFAULT 0,
    attended        INTEGER DEFAULT 1,     -- 1=present, 0=absent, 2=late
    marked_by       INTEGER,               -- trainer/admin user_id
    notes           TEXT,
    created_at      TEXT,
    UNIQUE(live_class_id, user_id)
);

-- â”€â”€ Quizzes â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS quizzes (
    id              BIGSERIAL PRIMARY KEY,
    institution_id  INTEGER NOT NULL,
    course_id       INTEGER,
    topic_id        INTEGER,
    batch_id        INTEGER,
    created_by      INTEGER NOT NULL,
    title           TEXT NOT NULL,
    description     TEXT,
    quiz_type       TEXT DEFAULT 'practice', -- practice/graded/assessment
    time_limit_mins INTEGER DEFAULT 30,
    pass_score      INTEGER DEFAULT 60,       -- percentage
    max_attempts    INTEGER DEFAULT 3,
    shuffle_questions INTEGER DEFAULT 1,
    show_answers    INTEGER DEFAULT 1,        -- show correct answers after submission
    instructions    TEXT,
    status          TEXT DEFAULT 'active',
    total_questions INTEGER DEFAULT 0,
    total_marks     INTEGER DEFAULT 0,
    created_at      TEXT,
    updated_at      TEXT
);

-- â”€â”€ Quiz Questions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS quiz_questions (
    id              BIGSERIAL PRIMARY KEY,
    quiz_id         INTEGER NOT NULL,
    institution_id  INTEGER NOT NULL,
    question_text   TEXT NOT NULL,
    question_type   TEXT DEFAULT 'mcq',  -- mcq/true_false/multi_select/short_answer
    options_json    TEXT,                -- JSON: ["Option A","Option B","Option C","Option D"]
    correct_answer  TEXT,                -- for MCQ: "0" (index); for multi: "0,2"; for T/F: "true"
    explanation     TEXT,
    marks           INTEGER DEFAULT 1,
    difficulty      TEXT DEFAULT 'medium', -- easy/medium/hard
    topic_tag       TEXT,
    display_order   INTEGER DEFAULT 0,
    image_url       TEXT,
    created_at      TEXT
);

-- â”€â”€ Quiz Attempts â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS quiz_attempts (
    id              BIGSERIAL PRIMARY KEY,
    quiz_id         INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER NOT NULL,
    attempt_number  INTEGER DEFAULT 1,
    started_at      TEXT,
    submitted_at    TEXT,
    time_taken_mins INTEGER DEFAULT 0,
    score           REAL DEFAULT 0,
    max_score       REAL DEFAULT 0,
    score_pct       REAL DEFAULT 0,
    passed          INTEGER DEFAULT 0,
    answers_json    TEXT,                -- JSON: {question_id: selected_answer}
    status          TEXT DEFAULT 'in_progress'  -- in_progress/submitted/timed_out
);

-- â”€â”€ Assignments â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS assignments (
    id              BIGSERIAL PRIMARY KEY,
    institution_id  INTEGER NOT NULL,
    course_id       INTEGER,
    topic_id        INTEGER,
    batch_id        INTEGER,
    created_by      INTEGER NOT NULL,
    title           TEXT NOT NULL,
    description     TEXT NOT NULL,
    instructions    TEXT,
    due_date        TEXT,
    max_marks       INTEGER DEFAULT 100,
    pass_marks      INTEGER DEFAULT 50,
    submission_type TEXT DEFAULT 'file',  -- file/text/url/practical
    allowed_file_types TEXT DEFAULT 'pdf,docx,xlsx,png,jpg',
    max_file_size_mb INTEGER DEFAULT 10,
    late_submission INTEGER DEFAULT 0,
    late_penalty_pct INTEGER DEFAULT 0,
    status          TEXT DEFAULT 'active',
    total_submitted INTEGER DEFAULT 0,
    created_at      TEXT,
    updated_at      TEXT
);

-- â”€â”€ Assignment Submissions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS assignment_submissions (
    id              BIGSERIAL PRIMARY KEY,
    assignment_id   INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER NOT NULL,
    submission_text TEXT,
    file_path       TEXT,
    file_name       TEXT,
    submitted_url   TEXT,
    submitted_at    TEXT,
    is_late         INTEGER DEFAULT 0,
    marks_obtained  REAL,
    feedback        TEXT,
    graded_by       INTEGER,
    graded_at       TEXT,
    status          TEXT DEFAULT 'submitted',  -- submitted/graded/returned/resubmit
    attempt_number  INTEGER DEFAULT 1,
    UNIQUE(assignment_id, user_id)
);

-- â”€â”€ Lesson Progress (per user per lesson) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS lesson_progress (
    id              BIGSERIAL PRIMARY KEY,
    user_id         INTEGER NOT NULL,
    lesson_id       INTEGER NOT NULL,
    course_id       INTEGER NOT NULL,
    institution_id  INTEGER NOT NULL,
    started         INTEGER DEFAULT 0,
    completed       INTEGER DEFAULT 0,
    progress_pct    INTEGER DEFAULT 0,
    watch_time_secs INTEGER DEFAULT 0,   -- for video lessons
    last_position_secs INTEGER DEFAULT 0,
    completed_at    TEXT,
    updated_at      TEXT,
    UNIQUE(user_id, lesson_id)
);

-- â”€â”€ Course Ratings & Reviews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS course_reviews (
    id              BIGSERIAL PRIMARY KEY,
    course_id       INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    institution_id  INTEGER NOT NULL,
    rating          INTEGER NOT NULL,    -- 1â€“5
    review_text     TEXT,
    is_verified     INTEGER DEFAULT 0,   -- verified purchaser
    is_approved     INTEGER DEFAULT 1,
    created_at      TEXT,
    UNIQUE(course_id, user_id)
);

-- â”€â”€ Certificates â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS certificates (
    id              BIGSERIAL PRIMARY KEY,
    institution_id  INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    course_id       INTEGER NOT NULL,
    enrollment_id   INTEGER NOT NULL,
    certificate_number TEXT UNIQUE,      -- CERT-YYYYMMDD-NNNNNN
    issued_at       TEXT,
    valid_until     TEXT,
    final_score     REAL DEFAULT 0,
    grade           TEXT,                -- A+/A/B+/B/C/Pass/Fail
    issued_by       INTEGER,
    pdf_url         TEXT,
    verified_url    TEXT,                -- public verification link
    revoked         INTEGER DEFAULT 0,
    revoked_reason  TEXT
);

-- â”€â”€ Announcements â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS announcements (
    id              BIGSERIAL PRIMARY KEY,
    institution_id  INTEGER NOT NULL,
    course_id       INTEGER,             -- NULL = institution-wide
    batch_id        INTEGER,
    created_by      INTEGER NOT NULL,
    title           TEXT NOT NULL,
    content         TEXT NOT NULL,
    priority        TEXT DEFAULT 'normal', -- urgent/high/normal/low
    target_roles    TEXT DEFAULT 'all',   -- all/students/trainers
    is_pinned       INTEGER DEFAULT 0,
    attachment_url  TEXT,
    status          TEXT DEFAULT 'active',
    published_at    TEXT,
    expires_at      TEXT,
    created_at      TEXT
);

-- â”€â”€ Discussion Forum â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS forum_threads (
    id              BIGSERIAL PRIMARY KEY,
    institution_id  INTEGER NOT NULL,
    course_id       INTEGER,
    lesson_id       INTEGER,
    batch_id        INTEGER,
    created_by      INTEGER NOT NULL,
    title           TEXT NOT NULL,
    content         TEXT NOT NULL,
    is_pinned       INTEGER DEFAULT 0,
    is_locked       INTEGER DEFAULT 0,
    reply_count     INTEGER DEFAULT 0,
    view_count      INTEGER DEFAULT 0,
    last_reply_at   TEXT,
    status          TEXT DEFAULT 'active',
    created_at      TEXT
);

CREATE TABLE IF NOT EXISTS forum_replies (
    id              BIGSERIAL PRIMARY KEY,
    thread_id       INTEGER NOT NULL,
    institution_id  INTEGER NOT NULL,
    user_id         INTEGER NOT NULL,
    content         TEXT NOT NULL,
    is_solution     INTEGER DEFAULT 0,   -- marked as the answer
    upvotes         INTEGER DEFAULT 0,
    parent_reply_id INTEGER,
    created_at      TEXT,
    updated_at      TEXT
);

-- â”€â”€ Student Leaderboard â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS teaching_leaderboard (
    id              BIGSERIAL PRIMARY KEY,
    institution_id  INTEGER NOT NULL,
    course_id       INTEGER,
    batch_id        INTEGER,
    user_id         INTEGER NOT NULL,
    period          TEXT DEFAULT 'all_time', -- all_time/monthly/weekly
    quiz_score      REAL DEFAULT 0,
    assignment_score REAL DEFAULT 0,
    attendance_score REAL DEFAULT 0,
    participation_score REAL DEFAULT 0,
    total_score     REAL DEFAULT 0,
    rank            INTEGER DEFAULT 0,
    badges_json     TEXT,                -- JSON array of earned badges
    computed_at     TEXT,
    UNIQUE(institution_id, course_id, user_id, period)
);

-- â”€â”€ Batch Schedule (class timetable) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS batch_schedule (
    id              BIGSERIAL PRIMARY KEY,
    batch_id        INTEGER NOT NULL,
    institution_id  INTEGER NOT NULL,
    day_of_week     TEXT,               -- Mon/Tue/Wed/Thu/Fri/Sat/Sun
    start_time      TEXT,               -- HH:MM 24h
    end_time        TEXT,
    topic           TEXT,
    trainer_id      INTEGER,
    notes           TEXT
);

-- â”€â”€ Trainer evaluation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS trainer_ratings (
    id              BIGSERIAL PRIMARY KEY,
    institution_id  INTEGER NOT NULL,
    trainer_id      INTEGER NOT NULL,
    batch_id        INTEGER,
    rated_by        INTEGER NOT NULL,   -- student user_id
    rating          INTEGER,            -- 1â€“5
    feedback        TEXT,
    period          TEXT,
    created_at      TEXT,
    UNIQUE(trainer_id, rated_by, batch_id)
);

-- â”€â”€ Resource Library â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE TABLE IF NOT EXISTS resource_library (
    id              BIGSERIAL PRIMARY KEY,
    institution_id  INTEGER NOT NULL,
    course_id       INTEGER,
    uploaded_by     INTEGER NOT NULL,
    title           TEXT NOT NULL,
    description     TEXT,
    resource_type   TEXT DEFAULT 'pdf',  -- pdf/xlsx/pptx/video/link/image
    file_path       TEXT,
    file_url        TEXT,
    file_size_kb    REAL,
    category        TEXT,
    tags            TEXT,
    download_count  INTEGER DEFAULT 0,
    access_level    TEXT DEFAULT 'enrolled', -- enrolled/all_students/trainers_only
    status          TEXT DEFAULT 'active',
    created_at      TEXT
);

-- â”€â”€ Indexes â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
CREATE INDEX IF NOT EXISTS idx_courses_inst    ON courses(institution_id, status);
CREATE INDEX IF NOT EXISTS idx_batches_inst    ON batches(institution_id, status);
CREATE INDEX IF NOT EXISTS idx_enrollments_user ON enrollments(user_id, status);
CREATE INDEX IF NOT EXISTS idx_enrollments_course ON enrollments(course_id, institution_id);
CREATE INDEX IF NOT EXISTS idx_lesson_progress ON lesson_progress(user_id, course_id);
CREATE INDEX IF NOT EXISTS idx_quiz_attempts   ON quiz_attempts(user_id, quiz_id);
CREATE INDEX IF NOT EXISTS idx_live_classes    ON live_classes(institution_id, scheduled_at);
CREATE INDEX IF NOT EXISTS idx_leaderboard     ON teaching_leaderboard(institution_id, course_id, total_score DESC);
CREATE INDEX IF NOT EXISTS idx_forum_threads   ON forum_threads(institution_id, course_id);
CREATE INDEX IF NOT EXISTS idx_assignments     ON assignments(course_id, batch_id);

