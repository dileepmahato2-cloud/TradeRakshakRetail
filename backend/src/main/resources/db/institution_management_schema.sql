-- ════════════════════════════════════════════════════════════════════════════
-- Institution Management System — Database Schema (PostgreSQL)
-- Run this once to create all institution management tables.
-- All tables are scoped by institution_id for multi-tenant isolation.
-- ════════════════════════════════════════════════════════════════════════════

-- 1. Institution Profile
CREATE TABLE IF NOT EXISTS inst_profile (
    id                  BIGINT PRIMARY KEY,
    institution_name    VARCHAR(255) NOT NULL,
    address             TEXT,
    city                VARCHAR(100),
    state               VARCHAR(100),
    pincode             VARCHAR(20),
    phone               VARCHAR(20),
    email               VARCHAR(255),
    website             VARCHAR(255),
    logo_url            VARCHAR(500),
    registration_number VARCHAR(100),
    established_date    VARCHAR(20),
    status              VARCHAR(20) DEFAULT 'active',
    created_at          VARCHAR(30),
    updated_at          VARCHAR(30),
    updated_by          BIGINT
);

-- 2. Courses
CREATE TABLE IF NOT EXISTS inst_courses (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    course_name      VARCHAR(255) NOT NULL,
    course_code      VARCHAR(50),
    category         VARCHAR(100),
    duration_months  INT DEFAULT 0,
    max_students     INT DEFAULT 50,
    fee_amount       NUMERIC(12,2) DEFAULT 0,
    description      TEXT,
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30),
    updated_at       VARCHAR(30),
    created_by       BIGINT,
    updated_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_courses_iid ON inst_courses(institution_id);

-- 3. Students
CREATE TABLE IF NOT EXISTS inst_students (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    student_name     VARCHAR(255) NOT NULL,
    student_email    VARCHAR(255),
    phone            VARCHAR(20),
    enrollment_no    VARCHAR(50) UNIQUE,
    gender           VARCHAR(10),
    dob              VARCHAR(20),
    address          TEXT,
    guardian_name    VARCHAR(255),
    guardian_phone   VARCHAR(20),
    course_id        BIGINT REFERENCES inst_courses(id) ON DELETE SET NULL,
    batch_id         BIGINT,
    admission_date   VARCHAR(20),
    fee_total        NUMERIC(12,2) DEFAULT 0,
    fee_paid         NUMERIC(12,2) DEFAULT 0,
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30),
    updated_at       VARCHAR(30),
    created_by       BIGINT,
    updated_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_students_iid ON inst_students(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_students_course ON inst_students(course_id);
CREATE INDEX IF NOT EXISTS idx_inst_students_batch ON inst_students(batch_id);

-- 4. Faculty / Teachers
CREATE TABLE IF NOT EXISTS inst_faculty (
    id                     BIGSERIAL PRIMARY KEY,
    institution_id         BIGINT NOT NULL,
    teacher_name           VARCHAR(255) NOT NULL,
    email                  VARCHAR(255),
    phone                  VARCHAR(20),
    subject_specialization VARCHAR(255),
    qualification          VARCHAR(255),
    experience_years       INT DEFAULT 0,
    salary                 NUMERIC(12,2) DEFAULT 0,
    joining_date           VARCHAR(20),
    status                 VARCHAR(20) DEFAULT 'active',
    created_at             VARCHAR(30),
    updated_at             VARCHAR(30),
    created_by             BIGINT,
    updated_by             BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_faculty_iid ON inst_faculty(institution_id);

-- 5. Batches
CREATE TABLE IF NOT EXISTS inst_batches (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    batch_name       VARCHAR(255) NOT NULL,
    course_id        BIGINT REFERENCES inst_courses(id) ON DELETE SET NULL,
    faculty_id       BIGINT REFERENCES inst_faculty(id) ON DELETE SET NULL,
    start_date       VARCHAR(20),
    end_date         VARCHAR(20),
    max_students     INT DEFAULT 30,
    timing           VARCHAR(100),
    room_number      VARCHAR(50),
    status           VARCHAR(20) DEFAULT 'active',
    created_at       VARCHAR(30),
    updated_at       VARCHAR(30),
    created_by       BIGINT,
    updated_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_batches_iid ON inst_batches(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_batches_course ON inst_batches(course_id);

-- 6. Student Attendance
CREATE TABLE IF NOT EXISTS inst_attendance (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    student_id       BIGINT REFERENCES inst_students(id) ON DELETE CASCADE,
    batch_id         BIGINT,
    attendance_date  VARCHAR(20) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'present',
    remarks          TEXT,
    marked_by        BIGINT,
    created_at       VARCHAR(30),
    UNIQUE(institution_id, student_id, attendance_date)
);
CREATE INDEX IF NOT EXISTS idx_inst_att_iid ON inst_attendance(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_att_date ON inst_attendance(attendance_date);

-- 7. Faculty Attendance
CREATE TABLE IF NOT EXISTS inst_faculty_attendance (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    faculty_id       BIGINT REFERENCES inst_faculty(id) ON DELETE CASCADE,
    attendance_date  VARCHAR(20) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'present',
    remarks          TEXT,
    marked_by        BIGINT,
    created_at       VARCHAR(30),
    UNIQUE(institution_id, faculty_id, attendance_date)
);
CREATE INDEX IF NOT EXISTS idx_inst_fatt_iid ON inst_faculty_attendance(institution_id);

-- 8. Fees
CREATE TABLE IF NOT EXISTS inst_fees (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    student_id       BIGINT REFERENCES inst_students(id) ON DELETE CASCADE,
    fee_type         VARCHAR(50),
    amount           NUMERIC(12,2) DEFAULT 0,
    discount         NUMERIC(12,2) DEFAULT 0,
    paid_amount      NUMERIC(12,2) DEFAULT 0,
    balance          NUMERIC(12,2) DEFAULT 0,
    due_date         VARCHAR(20),
    payment_date     VARCHAR(20),
    payment_mode     VARCHAR(50),
    receipt_no       VARCHAR(50),
    payment_status   VARCHAR(20) DEFAULT 'pending',
    remarks          TEXT,
    created_at       VARCHAR(30),
    updated_at       VARCHAR(30),
    created_by       BIGINT,
    updated_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_fees_iid ON inst_fees(institution_id);
CREATE INDEX IF NOT EXISTS idx_inst_fees_student ON inst_fees(student_id);

-- 9. Class Schedule / Timetable
CREATE TABLE IF NOT EXISTS inst_schedule (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    course_id        BIGINT REFERENCES inst_courses(id) ON DELETE SET NULL,
    batch_id         BIGINT,
    faculty_id       BIGINT REFERENCES inst_faculty(id) ON DELETE SET NULL,
    day_of_week      VARCHAR(20) NOT NULL,
    start_time       VARCHAR(10),
    end_time         VARCHAR(10),
    subject          VARCHAR(255),
    room_number      VARCHAR(50),
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_schedule_iid ON inst_schedule(institution_id);

-- 10. Study Materials
CREATE TABLE IF NOT EXISTS inst_materials (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    title            VARCHAR(255) NOT NULL,
    description      TEXT,
    material_type    VARCHAR(50),
    file_url         VARCHAR(500),
    course_id        BIGINT REFERENCES inst_courses(id) ON DELETE SET NULL,
    uploaded_by      BIGINT,
    created_at       VARCHAR(30)
);
CREATE INDEX IF NOT EXISTS idx_inst_materials_iid ON inst_materials(institution_id);

-- 11. Exams / Tests
CREATE TABLE IF NOT EXISTS inst_exams (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    exam_name        VARCHAR(255) NOT NULL,
    exam_type        VARCHAR(50),
    course_id        BIGINT REFERENCES inst_courses(id) ON DELETE SET NULL,
    batch_id         BIGINT,
    exam_date        VARCHAR(20),
    start_time       VARCHAR(10),
    end_time         VARCHAR(10),
    total_marks      INT DEFAULT 100,
    passing_marks    INT DEFAULT 35,
    status           VARCHAR(20) DEFAULT 'scheduled',
    created_at       VARCHAR(30),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_inst_exams_iid ON inst_exams(institution_id);

-- 12. Exam Results
CREATE TABLE IF NOT EXISTS inst_exam_results (
    id               BIGSERIAL PRIMARY KEY,
    institution_id   BIGINT NOT NULL,
    exam_id          BIGINT REFERENCES inst_exams(id) ON DELETE CASCADE,
    student_id       BIGINT REFERENCES inst_students(id) ON DELETE CASCADE,
    marks_obtained   NUMERIC(8,2) DEFAULT 0,
    grade            VARCHAR(10),
    remarks          TEXT,
    created_at       VARCHAR(30),
    created_by       BIGINT,
    UNIQUE(exam_id, student_id)
);
CREATE INDEX IF NOT EXISTS idx_inst_results_iid ON inst_exam_results(institution_id);

-- 13. Enquiries / Leads
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
